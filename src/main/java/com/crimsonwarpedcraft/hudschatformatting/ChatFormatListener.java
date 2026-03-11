package com.crimsonwarpedcraft.hudschatformatting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Handles all chat formatting for the plugin.
 *
 * @author Copyright (c) Levi Muniz. All Rights Reserved.
 */
public final class ChatFormatListener implements Listener {

  private static final String MESSAGE_PLACEHOLDER = "{message}";
  private static final String DEFAULT_FORMAT = "&7[{time}] {prefix}&f{player}&7: {message}";
  private static final String VANILLA_TEMPLATE_TOKEN = "{vanilla}";
  private static final String DEFAULT_JOIN_MESSAGE = VANILLA_TEMPLATE_TOKEN;
  private static final String DEFAULT_LEAVE_MESSAGE = VANILLA_TEMPLATE_TOKEN;
  private static final String DEFAULT_DEATH_MESSAGE = VANILLA_TEMPLATE_TOKEN;
  private static final String DEFAULT_ADVANCEMENT_MESSAGE = VANILLA_TEMPLATE_TOKEN;
  private static final long RECENT_EVENT_WINDOW_MS = 5000L;
  private static final String VANILLA_JOIN_SUFFIX = " joined the game";
  private static final String VANILLA_LEAVE_SUFFIX = " left the game";
  private static final char SECTION_SIGN = (char) 167;
  private static final String BALANCE_UNAVAILABLE = "N/A";
  private static final List<String> NICKNAME_PLACEHOLDER_CANDIDATES = List.of(
      "%hexnicks_nickname%",
      "%hexnicks_name%",
      "%hexnicks_displayname%",
      "%essentials_nickname%",
      "%cmi_user_nickname%");
  private static final Pattern HEX_AMPERSAND_PATTERN =
      Pattern.compile("(?i)&?#([0-9a-f]{6})");
  private static final LegacyComponentSerializer AMPERSAND_SERIALIZER =
      LegacyComponentSerializer.builder().character('&').hexColors().build();
  private static final LegacyComponentSerializer SECTION_SERIALIZER =
      LegacyComponentSerializer.builder().character(SECTION_SIGN).hexColors().build();
  private static final PlainTextComponentSerializer PLAIN_TEXT_SERIALIZER =
      PlainTextComponentSerializer.plainText();

  private final HudsChatFormattingPlugin plugin;
  private final LuckPerms luckPerms;
  private final Chat vaultChat;
  private final Economy economy;
  private final boolean placeholderApiEnabled;
  private final boolean multiverseEnabled;
  private final Map<String, Long> recentJoins = new LinkedHashMap<>();
  private final Map<String, Long> recentLeaves = new LinkedHashMap<>();
  private final Map<UUID, Boolean> vanishStates = new LinkedHashMap<>();
  private java.lang.reflect.Method vanishCanSeeMethod;
  private boolean vanishCanSeeResolved;
  private int vanishPollTaskId = -1;

  /**
   * Creates a new listener.
   *
   * @param plugin the plugin instance
   * @param luckPerms the LuckPerms API, if available
   * @param vaultChat the Vault chat provider, if available
   * @param economy the Vault economy provider, if available
   * @param placeholderApiEnabled true if PlaceholderAPI is available
   * @param multiverseEnabled true if Multiverse-Core is available
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Bukkit listeners keep a plugin reference for config and logger access.")
  public ChatFormatListener(
      final HudsChatFormattingPlugin plugin,
      final LuckPerms luckPerms,
      final Chat vaultChat,
      final Economy economy,
      final boolean placeholderApiEnabled,
      final boolean multiverseEnabled) {
    this.plugin = plugin;
    this.luckPerms = luckPerms;
    this.vaultChat = vaultChat;
    this.economy = economy;
    this.placeholderApiEnabled = placeholderApiEnabled;
    this.multiverseEnabled = multiverseEnabled;
  }

  /**
   * Registers vanish plugin hooks for fake join/leave announcements.
   */
  public void registerVanishMessageHooks() {
    disableSuperVanishFakeMessagesIfPossible();
    registerVanishEvent("de.myzelyam.api.vanish.PlayerHideEvent", "leave");
    registerVanishEvent("de.myzelyam.api.vanish.PlayerShowEvent", "join");
    registerVanishEvent("de.myzelyam.api.vanish.VanishStatusChangeEvent", null);
    startVanishStatePolling();
  }

  /**
   * Replaces player chat formatting in a fully config-driven way.
   *
   * @param event the chat event
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onAsyncChat(final AsyncChatEvent event) {
    final Player player = event.getPlayer();
    final String plainMessage = PLAIN_TEXT_SERIALIZER.serialize(event.message());
    final FilterResult filterResult = applyChatFilter(player, plainMessage);
    if (filterResult.blocked()) {
      event.setCancelled(true);
      sendBlockedMessageNotice(player, plainMessage);
      return;
    }

    // Write filtered text back to the event so other plugins/renderers receive it too.
    event.message(Component.text(filterResult.message()));

    final String prefix = getResolvedPrefix(player);
    final String formatted = applyFormatPlaceholders(player, prefix);
    int messagePosition = formatted.indexOf(MESSAGE_PLACEHOLDER);
    String output = formatted;
    if (messagePosition < 0) {
      output = formatted + " " + MESSAGE_PLACEHOLDER;
      messagePosition = output.indexOf(MESSAGE_PLACEHOLDER);
    }

    final String before = output.substring(0, messagePosition);
    final String after = output.substring(messagePosition + MESSAGE_PLACEHOLDER.length());
    final Component beforeComponent = parseTemplateText(before);
    final Component afterComponent = parseTemplateText(after);

    event.renderer((source, sourceDisplayName, message, viewer) -> beforeComponent
        .append(buildPlayerMessage(player, message, prefix))
        .append(afterComponent));
  }

  /**
   * Applies configurable join messages (global or per-player override).
   *
   * @param event the join event
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerJoin(final PlayerJoinEvent event) {
    final Player player = event.getPlayer();
    trackRecentJoin(player);
    final Component vanillaMessage = event.joinMessage();
    if (!isMessageEnabled("messages.join.enabled", true)) {
      event.joinMessage(null);
      return;
    }
    if (shouldSuppressPlayerMessage("join", player)) {
      event.joinMessage(null);
      return;
    }

    final String template = getJoinOrLeaveTemplate(player, "join", DEFAULT_JOIN_MESSAGE);
    if (isVanillaTemplate(template)) {
      if (!usePrefixedNicknamesInVanillaMessages()) {
        event.joinMessage(vanillaMessage);
        return;
      }
      event.joinMessage(rewriteVanillaMessage(
          vanillaMessage, Map.of(player.getName(), getVanillaFormattedName(player))));
      return;
    }

    final String rendered = applyGeneralPlaceholders(player, template, "")
        .replace("{event}", "join");
    event.joinMessage(AMPERSAND_SERIALIZER.deserialize(rendered));
  }

  /**
   * Applies configurable quit messages (global or per-player override).
   *
   * @param event the quit event
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerQuit(final PlayerQuitEvent event) {
    final Player player = event.getPlayer();
    trackRecentLeave(player);
    clearVanishState(player);
    final Component vanillaMessage = event.quitMessage();
    if (!isMessageEnabled("messages.leave.enabled", true)) {
      event.quitMessage(null);
      return;
    }
    if (shouldSuppressPlayerMessage("leave", player)) {
      event.quitMessage(null);
      return;
    }

    final String template = getJoinOrLeaveTemplate(player, "leave", DEFAULT_LEAVE_MESSAGE);
    if (isVanillaTemplate(template)) {
      if (!usePrefixedNicknamesInVanillaMessages()) {
        event.quitMessage(vanillaMessage);
        return;
      }
      event.quitMessage(rewriteVanillaMessage(
          vanillaMessage, Map.of(player.getName(), getVanillaFormattedName(player))));
      return;
    }

    final String rendered = applyGeneralPlaceholders(player, template, "")
        .replace("{event}", "leave");
    event.quitMessage(AMPERSAND_SERIALIZER.deserialize(rendered));
  }

  /**
   * Applies configurable death messages by damage cause.
   *
   * @param event the death event
   */
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerDeath(final PlayerDeathEvent event) {
    final Component baseDeathMessage = event.deathMessage();
    if (!isMessageEnabled("messages.death.enabled", true)) {
      event.deathMessage(null);
      return;
    }

    final Player player = event.getEntity();
    if (shouldSuppressPlayerMessage("death", player)) {
      event.deathMessage(null);
      return;
    }
    final DeathContext deathContext = getDeathContext(player);
    final String template = getDeathTemplate(deathContext);
    if (isVanillaTemplate(template)) {
      if (!usePrefixedNicknamesInVanillaMessages()) {
        event.deathMessage(baseDeathMessage);
        return;
      }
      event.deathMessage(rewriteVanillaDeathMessage(baseDeathMessage, player, deathContext));
      return;
    }

    String defaultMessage;
    if (baseDeathMessage == null) {
      defaultMessage = player.getName() + " died.";
    } else {
      defaultMessage = rewritePlainName(
          PLAIN_TEXT_SERIALIZER.serialize(baseDeathMessage),
          player.getName(),
          getVanillaFormattedName(player));
      defaultMessage = rewritePlainName(
          defaultMessage,
          deathContext.killerPlayerName(),
          deathContext.killerDecoratedName());
    }
    final String rendered = applyGeneralPlaceholders(player, template, "")
        .replace("{event}", "death")
        .replace("{death_message}", defaultMessage)
        .replace("{death_cause}", deathContext.causeKey())
        .replace("{killer}", deathContext.killerName())
        .replace("{killer_type}", deathContext.killerTypeKey());
    event.deathMessage(AMPERSAND_SERIALIZER.deserialize(rendered));
  }

  /**
   * Applies configurable advancement announcements keyed by advancement id.
   *
   * @param event the advancement completion event
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPlayerAdvancementDone(final PlayerAdvancementDoneEvent event) {
    final Component baseMessage = event.message();
    if (!isMessageEnabled("messages.advancement.enabled", true)) {
      event.message(null);
      return;
    }

    final Player player = event.getPlayer();
    if (shouldSuppressPlayerMessage("advancement", player)) {
      event.message(null);
      return;
    }
    final Advancement advancement = event.getAdvancement();
    final String key = advancement.getKey().toString();
    final String template = getAdvancementTemplate(key);
    if (isVanillaTemplate(template)) {
      if (!usePrefixedNicknamesInVanillaMessages()) {
        event.message(baseMessage);
        return;
      }
      event.message(rewriteVanillaMessage(
          baseMessage, Map.of(player.getName(), getVanillaFormattedName(player))));
      return;
    }

    final String vanillaMessage = baseMessage == null
        ? key
        : PLAIN_TEXT_SERIALIZER.serialize(baseMessage);
    final String rendered = applyGeneralPlaceholders(player, template, "")
        .replace("{event}", "advancement")
        .replace("{advancement_key}", key)
        .replace("{advancement_message}", vanillaMessage)
        .replace("{advancement_title}", getAdvancementTitle(event));
    event.message(AMPERSAND_SERIALIZER.deserialize(rendered));
  }

  /**
   * Rewrites vanilla-looking broadcast join/leave messages (fake vanish messages).
   *
   * @param event the broadcast event
   */
  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onBroadcastMessage(final org.bukkit.event.server.BroadcastMessageEvent event) {
    final Component baseMessage = event.message();
    final String plainText = PLAIN_TEXT_SERIALIZER.serialize(baseMessage).trim();
    if (plainText.isBlank()) {
      return;
    }

    final VanillaBroadcast broadcast = parseVanillaJoinLeave(plainText);
    if (broadcast == null) {
      return;
    }
    if (isRecentBroadcast(broadcast)) {
      return;
    }

    final Player player = findOnlinePlayerByName(broadcast.playerName());
    if (player == null) {
      return;
    }

    final String type = broadcast.type();
    if (!isMessageEnabled("messages." + type + ".enabled", true)) {
      event.setCancelled(true);
      return;
    }
    if (shouldSuppressPlayerMessage(type, player, true)) {
      event.setCancelled(true);
      return;
    }

    final String fallback = switch (type) {
      case "join" -> DEFAULT_JOIN_MESSAGE;
      case "leave" -> DEFAULT_LEAVE_MESSAGE;
      default -> DEFAULT_JOIN_MESSAGE;
    };
    final String template = getJoinOrLeaveTemplate(player, type, fallback);
    if (isVanillaTemplate(template)) {
      if (!usePrefixedNicknamesInVanillaMessages()) {
        return;
      }
      event.message(rewriteVanillaMessage(
          baseMessage, Map.of(player.getName(), getVanillaFormattedName(player))));
      return;
    }

    final String rendered = applyGeneralPlaceholders(player, template, "")
        .replace("{event}", type);
    event.message(AMPERSAND_SERIALIZER.deserialize(rendered));
  }

  private void registerVanishEvent(final String className, final String fixedType) {
    final Class<? extends Event> eventClass = resolveEventClass(className);
    if (eventClass == null) {
      return;
    }

    this.plugin.getServer().getPluginManager().registerEvent(
        eventClass,
        this,
        EventPriority.HIGH,
        (listener, event) -> handleVanishEvent(event, fixedType),
        this.plugin,
        true);
  }

  @SuppressWarnings("unchecked")
  private Class<? extends Event> resolveEventClass(final String className) {
    try {
      final Class<?> raw = Class.forName(className);
      if (!Event.class.isAssignableFrom(raw)) {
        return null;
      }
      return (Class<? extends Event>) raw;
    } catch (ClassNotFoundException ex) {
      return null;
    }
  }

  private void handleVanishEvent(final Event event, final String fixedType) {
    final Player player = getPlayerFromEvent(event);
    if (player == null) {
      return;
    }

    final String type = fixedType == null ? resolveVanishType(event) : fixedType;
    if (type == null) {
      return;
    }

    suppressVanishEventMessages(event);
    updateVanishState(player, "leave".equals(type));
    sendVanishJoinLeaveMessage(player, type);
  }

  private Player getPlayerFromEvent(final Event event) {
    if (event == null) {
      return null;
    }
    try {
      final java.lang.reflect.Method getPlayer = event.getClass().getMethod("getPlayer");
      final Object value = getPlayer.invoke(event);
      return value instanceof Player ? (Player) value : null;
    } catch (ReflectiveOperationException ex) {
      return null;
    }
  }

  private String resolveVanishType(final Event event) {
    final boolean vanished = invokeBooleanGetter(event, "isVanished");
    if (vanished) {
      return "leave";
    }

    final boolean vanishing = invokeBooleanGetter(event, "isVanishing");
    if (vanishing) {
      return "leave";
    }

    final boolean invisible = invokeBooleanGetter(event, "isInvisible");
    if (invisible) {
      return "leave";
    }

    if (hasMethod(event, "isVanished") || hasMethod(event, "isVanishing")
        || hasMethod(event, "isInvisible")) {
      return "join";
    }

    final String simpleName = event.getClass().getSimpleName().toLowerCase(Locale.ENGLISH);
    if (simpleName.contains("hide") || simpleName.contains("vanish")) {
      return "leave";
    }
    if (simpleName.contains("show") || simpleName.contains("reappear")) {
      return "join";
    }
    return null;
  }

  private boolean invokeBooleanGetter(final Object target, final String methodName) {
    if (target == null) {
      return false;
    }
    try {
      final java.lang.reflect.Method method = target.getClass().getMethod(methodName);
      final Object value = method.invoke(target);
      return value instanceof Boolean && (Boolean) value;
    } catch (ReflectiveOperationException ex) {
      return false;
    }
  }

  private boolean hasMethod(final Object target, final String methodName) {
    if (target == null) {
      return false;
    }
    try {
      target.getClass().getMethod(methodName);
      return true;
    } catch (ReflectiveOperationException ex) {
      return false;
    }
  }

  private void suppressVanishEventMessages(final Event event) {
    invokeBooleanSetter(event, "setSilent", true);
    invokeBooleanSetter(event, "setSendMessage", false);
    invokeBooleanSetter(event, "setBroadcast", false);
    invokeBooleanSetter(event, "setAnnounce", false);
  }

  private void invokeBooleanSetter(
      final Object target, final String methodName, final boolean value) {
    if (target == null) {
      return;
    }
    try {
      final java.lang.reflect.Method method =
          target.getClass().getMethod(methodName, boolean.class);
      method.invoke(target, value);
    } catch (ReflectiveOperationException ex) {
      // Ignore missing setters from specific vanish APIs.
    }
  }

  private void sendVanishJoinLeaveMessage(final Player player, final String type) {
    if (!isMessageEnabled("messages." + type + ".enabled", true)) {
      return;
    }
    if (shouldSuppressPlayerMessage(type, player, true)) {
      return;
    }

    final Component vanillaMessage = createVanillaJoinLeaveComponent(player, type);
    final Component rendered = buildJoinLeaveComponent(player, type, vanillaMessage);
    if (rendered == null) {
      return;
    }

    if ("join".equals(type)) {
      markRecent(recentJoins, player.getName());
    } else if ("leave".equals(type)) {
      markRecent(recentLeaves, player.getName());
    }
    sendVanishMessageToRecipients(player, rendered);
  }

  private Component buildJoinLeaveComponent(
      final Player player,
      final String type,
      final Component vanillaMessage) {
    final String template = getJoinOrLeaveTemplate(player, type, DEFAULT_JOIN_MESSAGE);
    if (isVanillaTemplate(template)) {
      if (!usePrefixedNicknamesInVanillaMessages()) {
        return vanillaMessage;
      }
      return rewriteVanillaMessage(
          vanillaMessage, Map.of(player.getName(), getVanillaFormattedName(player)));
    }

    final String rendered = applyGeneralPlaceholders(player, template, "")
        .replace("{event}", type);
    return AMPERSAND_SERIALIZER.deserialize(rendered);
  }

  private Component createVanillaJoinLeaveComponent(final Player player, final String type) {
    final String suffix = "join".equals(type) ? VANILLA_JOIN_SUFFIX : VANILLA_LEAVE_SUFFIX;
    return Component.text(player.getName() + suffix, NamedTextColor.YELLOW);
  }

  private void sendVanishMessageToRecipients(
      final Player vanishedPlayer, final Component message) {
    for (final Player viewer : this.plugin.getServer().getOnlinePlayers()) {
      if (viewer.equals(vanishedPlayer)) {
        continue;
      }
      viewer.sendMessage(message);
    }
  }

  private boolean isVanishCanSeeAvailable() {
    if (this.vanishCanSeeResolved) {
      return this.vanishCanSeeMethod != null;
    }
    this.vanishCanSeeResolved = true;

    try {
      final Class<?> vanishApi = Class.forName("de.myzelyam.api.vanish.VanishAPI");
      this.vanishCanSeeMethod =
          vanishApi.getMethod("canSee", Player.class, Player.class);
    } catch (ReflectiveOperationException ex) {
      this.vanishCanSeeMethod = null;
    }
    return this.vanishCanSeeMethod != null;
  }

  private boolean canSeeViaVanishApi(final Player viewer, final Player vanishedPlayer) {
    if (!isVanishCanSeeAvailable()) {
      return true;
    }
    try {
      final Object result = this.vanishCanSeeMethod.invoke(null, viewer, vanishedPlayer);
      return result instanceof Boolean && (Boolean) result;
    } catch (ReflectiveOperationException ex) {
      return true;
    }
  }

  private void disableSuperVanishFakeMessagesIfPossible() {
    if (!isPluginEnabled("SuperVanish") && !isPluginEnabled("PremiumVanish")) {
      return;
    }

    final FileConfiguration config = getSuperVanishConfig();
    if (config == null) {
      return;
    }

    boolean changed = false;
    changed |= setConfigIfPresent(
        config, "Messages.VanishReappearMessages.BroadcastMessageOnVanish", false);
    changed |= setConfigIfPresent(
        config, "Messages.VanishReappearMessages.BroadcastMessageOnReappear", false);
    changed |= setConfigIfPresent(
        config, "Messages.VanishReappearMessages.BroadcastFakeQuitOnVanish", false);
    changed |= setConfigIfPresent(
        config, "Messages.VanishReappearMessages.BroadcastFakeJoinOnReappear", false);
    changed |= setConfigIfPresent(
        config, "VanishReappearMessages.BroadcastMessageOnVanish", false);
    changed |= setConfigIfPresent(
        config, "VanishReappearMessages.BroadcastMessageOnReappear", false);

    if (!changed) {
      return;
    }

    saveSuperVanishConfig();
  }

  private FileConfiguration getSuperVanishConfig() {
    final FileConfiguration config = tryGetSuperVanishConfig("de.myzelyam.api.vanish.SVAPI");
    if (config != null) {
      return config;
    }
    return tryGetSuperVanishConfig("de.myzelyam.api.vanish.VanishAPI");
  }

  private FileConfiguration tryGetSuperVanishConfig(final String className) {
    try {
      final Class<?> api = Class.forName(className);
      final java.lang.reflect.Method method = api.getMethod("getConfiguration");
      final Object result = method.invoke(null);
      return result instanceof FileConfiguration ? (FileConfiguration) result : null;
    } catch (ReflectiveOperationException ex) {
      return null;
    }
  }

  private boolean setConfigIfPresent(
      final FileConfiguration config, final String path, final boolean value) {
    if (!config.contains(path)) {
      return false;
    }
    final boolean current = config.getBoolean(path);
    if (current == value) {
      return false;
    }
    config.set(path, value);
    return true;
  }

  private void saveSuperVanishConfig() {
    final Plugin superVanish = this.plugin.getServer().getPluginManager()
        .getPlugin("SuperVanish");
    final Plugin premiumVanish = this.plugin.getServer().getPluginManager()
        .getPlugin("PremiumVanish");
    final Plugin target = superVanish != null ? superVanish : premiumVanish;
    if (target instanceof JavaPlugin javaPlugin) {
      javaPlugin.saveConfig();
    }

    reloadSuperVanishConfig("de.myzelyam.api.vanish.SVAPI");
    reloadSuperVanishConfig("de.myzelyam.api.vanish.VanishAPI");
  }

  private void reloadSuperVanishConfig(final String className) {
    try {
      final Class<?> api = Class.forName(className);
      final java.lang.reflect.Method method = api.getMethod("reloadConfig");
      method.invoke(null);
    } catch (ReflectiveOperationException ex) {
      // Ignore if API does not expose reloadConfig.
    }
  }

  private boolean isPluginEnabled(final String pluginName) {
    return this.plugin.getServer().getPluginManager().isPluginEnabled(pluginName);
  }

  private Component buildPlayerMessage(
      final Player player, final Component originalMessage, final String prefix) {
    final String plainMessage = PLAIN_TEXT_SERIALIZER.serialize(originalMessage);
    final FileConfiguration config = this.plugin.getConfig();
    final String colorPermission = getConfigString(
        config, "permissions.chat-color", "hudschatformatting.chat.color");
    final String formatPermission = getConfigString(
        config, "permissions.chat-format", "hudschatformatting.chat.format");

    final boolean opBypass = player.isOp();
    final boolean canUseColors = opBypass || player.hasPermission(colorPermission);
    final boolean canUseFormats = opBypass || player.hasPermission(formatPermission);

    final Component baseMessage;
    if (!canUseColors && !canUseFormats) {
      baseMessage = Component.text(plainMessage);
    } else {
      final String translated = translateAmpersandCodes(plainMessage, canUseColors, canUseFormats);
      baseMessage = SECTION_SERIALIZER.deserialize(translated);
    }

    return applyMessageTemplate(player, baseMessage, prefix);
  }

  private String applyFormatPlaceholders(final Player player, final String prefix) {
    String format = this.plugin.getConfig().getString("chat.format", DEFAULT_FORMAT);
    if (format == null || format.isBlank()) {
      format = DEFAULT_FORMAT;
    }
    return applyGeneralPlaceholders(player, format, prefix);
  }

  private Component parseTemplateText(final String templateText) {
    final boolean parseLegacyColors =
        this.plugin.getConfig().getBoolean("chat.enable-legacy-codes-in-format", true);
    if (!parseLegacyColors) {
      return Component.text(templateText);
    }
    return AMPERSAND_SERIALIZER.deserialize(templateText);
  }

  private Component applyMessageTemplate(
      final Player player, final Component playerMessage, final String prefix) {
    final FileConfiguration config = this.plugin.getConfig();
    final String template = getConfigString(config, "chat.message-format", "{message}");
    int messagePosition = template.indexOf(MESSAGE_PLACEHOLDER);
    String output = template;
    if (messagePosition < 0) {
      output = template + " " + MESSAGE_PLACEHOLDER;
      messagePosition = output.indexOf(MESSAGE_PLACEHOLDER);
    }

    final String beforeRaw = output.substring(0, messagePosition);
    final String afterRaw = output.substring(messagePosition + MESSAGE_PLACEHOLDER.length());
    final String before = applyGeneralPlaceholders(player, beforeRaw, prefix);
    final String after = applyGeneralPlaceholders(player, afterRaw, prefix);

    final boolean parseLegacy =
        config.getBoolean("chat.enable-legacy-codes-in-message-format", true);
    if (!parseLegacy) {
      return Component.text(before).append(playerMessage).append(Component.text(after));
    }

    return AMPERSAND_SERIALIZER.deserialize(before)
        .append(playerMessage)
        .append(AMPERSAND_SERIALIZER.deserialize(after));
  }

  private String getCurrentTime() {
    final FileConfiguration config = this.plugin.getConfig();
    final String pattern = config.getString("chat.time-pattern", "HH:mm:ss");
    final String configuredZone = config.getString("chat.time-zone", "server");

    ZoneId zone = ZoneId.systemDefault();
    if (configuredZone != null && !configuredZone.equalsIgnoreCase("server")) {
      try {
        zone = ZoneId.of(configuredZone);
      } catch (DateTimeException ex) {
        this.plugin.getLogger().warning(
            "Invalid chat.time-zone in config, using server time zone.");
      }
    }

    DateTimeFormatter formatter;
    try {
      formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    } catch (IllegalArgumentException ex) {
      this.plugin.getLogger().warning("Invalid chat.time-pattern in config, using HH:mm:ss.");
      formatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);
    }

    return LocalDateTime.now(zone).format(formatter);
  }

  private String getWorldTime24(final Player player) {
    final long ticks = player.getWorld().getTime();
    final long totalMinutes = ((ticks + 6000L) % 24000L) * 60L / 1000L;
    final long hours = totalMinutes / 60L;
    final long minutes = totalMinutes % 60L;
    return String.format(Locale.ENGLISH, "%02d:%02d", hours, minutes);
  }

  private String getWorldTime12(final Player player) {
    final String worldTime24 = getWorldTime24(player);
    final String[] parts = worldTime24.split(":");
    final int hour24 = Integer.parseInt(parts[0]);
    final int minute = Integer.parseInt(parts[1]);
    final int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
    final String suffix = hour24 < 12 ? "AM" : "PM";
    return String.format(Locale.ENGLISH, "%d:%02d %s", hour12, minute, suffix);
  }

  private String getVaultBalance(final Player player) {
    if (this.economy == null) {
      return BALANCE_UNAVAILABLE;
    }
    return String.format(Locale.ENGLISH, "%.2f", this.economy.getBalance(player));
  }

  private String getVaultBalanceFormatted(final Player player) {
    if (this.economy == null) {
      return BALANCE_UNAVAILABLE;
    }
    return this.economy.format(this.economy.getBalance(player));
  }

  private String getResolvedPrefix(final Player player) {
    final String luckPermsPrefix = getLuckPermsPrefix(player);
    if (!luckPermsPrefix.isBlank()) {
      return luckPermsPrefix;
    }
    return getVaultPrefix(player);
  }

  private String getJoinOrLeaveTemplate(
      final Player player, final String type, final String fallback) {
    final FileConfiguration config = this.plugin.getConfig();
    final String playerPath = "messages." + type + ".per-player." + player.getName();
    final String playerSpecific = config.getString(playerPath);
    if (playerSpecific != null && !playerSpecific.isBlank()) {
      return playerSpecific;
    }

    final String uuidPath = "messages." + type + ".per-player-uuid." + player.getUniqueId();
    final String uuidSpecific = config.getString(uuidPath);
    if (uuidSpecific != null && !uuidSpecific.isBlank()) {
      return uuidSpecific;
    }

    return getConfiguredMessageFormat(config, "messages." + type, fallback);
  }

  private String getDeathTemplate(final DeathContext deathContext) {
    final FileConfiguration config = this.plugin.getConfig();
    if (!deathContext.killerTypeKey().isBlank()) {
      final String mobPath = "messages.death.by-mob." + deathContext.killerTypeKey();
      final String mobTemplate = config.getString(mobPath);
      if (mobTemplate != null && !mobTemplate.isBlank()) {
        return mobTemplate;
      }
    }

    final String exactPath = "messages.death.by-cause." + deathContext.causeKey();
    final String exactTemplate = config.getString(exactPath);
    if (exactTemplate != null && !exactTemplate.isBlank()) {
      return exactTemplate;
    }

    return getConfiguredMessageFormat(config, "messages.death", DEFAULT_DEATH_MESSAGE);
  }

  private String getAdvancementTemplate(final String advancementKey) {
    final FileConfiguration config = this.plugin.getConfig();
    final String exactPath = "messages.advancement.by-key." + advancementKey;
    final String exactTemplate = config.getString(exactPath);
    if (exactTemplate != null && !exactTemplate.isBlank()) {
      return exactTemplate;
    }

    return getConfiguredMessageFormat(
        config, "messages.advancement", DEFAULT_ADVANCEMENT_MESSAGE);
  }

  private String getConfiguredMessageFormat(
      final FileConfiguration config, final String basePath, final String fallback) {
    final String legacyDefault = config.getString(basePath + ".default");
    if (legacyDefault != null && !legacyDefault.isBlank()) {
      return legacyDefault;
    }

    final String format = config.getString(basePath + ".format");
    if (format != null && !format.isBlank()) {
      return format;
    }
    return fallback;
  }

  private String getDeathCauseKey(final Player player) {
    final EntityDamageEvent causeEvent = player.getLastDamageCause();
    if (causeEvent == null || causeEvent.getCause() == null) {
      return "UNKNOWN";
    }
    return causeEvent.getCause().name();
  }

  private DeathContext getDeathContext(final Player player) {
    final String causeKey = getDeathCauseKey(player);
    final EntityDamageEvent causeEvent = player.getLastDamageCause();
    if (!(causeEvent instanceof EntityDamageByEntityEvent entityDamage)) {
      return new DeathContext(causeKey, "", "", "", "");
    }

    final org.bukkit.entity.Entity killer = resolveDamager(entityDamage.getDamager());
    if (killer == null) {
      return new DeathContext(causeKey, "", "", "", "");
    }

    if (killer instanceof Player killerPlayer) {
      final String decoratedKiller = getVanillaFormattedName(killerPlayer);
      return new DeathContext(
          causeKey,
          "PLAYER",
          decoratedKiller,
          killerPlayer.getName(),
          decoratedKiller);
    }

    final String killerType = killer.getType().name();
    final Component customName = killer.customName();
    final String killerName = customName == null
        ? killerType
        : PLAIN_TEXT_SERIALIZER.serialize(customName);
    return new DeathContext(causeKey, killerType, killerName, "", "");
  }

  private org.bukkit.entity.Entity resolveDamager(final org.bukkit.entity.Entity damager) {
    if (damager instanceof org.bukkit.entity.Projectile projectile) {
      final ProjectileSource shooter = projectile.getShooter();
      if (shooter instanceof org.bukkit.entity.Entity shooterEntity) {
        return shooterEntity;
      }
    }
    return damager;
  }

  private String getAdvancementTitle(final PlayerAdvancementDoneEvent event) {
    final Component baseMessage = event.message();
    if (baseMessage == null) {
      return event.getAdvancement().getKey().toString();
    }

    final String fullText = PLAIN_TEXT_SERIALIZER.serialize(baseMessage).trim();
    if (fullText.isBlank()) {
      return event.getAdvancement().getKey().toString();
    }

    final String playerName = event.getPlayer().getName();
    final int playerStart = fullText.indexOf(playerName);
    if (playerStart >= 0) {
      final int playerEnd = playerStart + playerName.length();
      if (playerEnd < fullText.length()) {
        final String trailing = fullText.substring(playerEnd).trim();
        if (!trailing.isBlank()) {
          return trailing;
        }
      }
    }

    return fullText;
  }

  private String getLuckPermsPrefix(final Player player) {
    if (this.luckPerms == null) {
      return "";
    }

    final User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
    if (user == null) {
      return "";
    }

    final QueryOptions queryOptions = this.luckPerms.getContextManager().getQueryOptions(player);

    final String prefix = user.getCachedData().getMetaData(queryOptions).getPrefix();
    return prefix == null ? "" : prefix;
  }

  private String getVaultPrefix(final Player player) {
    if (this.vaultChat == null) {
      return "";
    }

    final String worldName = player.getWorld().getName();
    final String prefix = this.vaultChat.getPlayerPrefix(worldName, player);
    return prefix == null ? "" : prefix;
  }

  private String translateAmpersandCodes(
      final String message, final boolean allowColors, final boolean allowFormats) {
    final StringBuilder output = new StringBuilder(message.length());
    for (int i = 0; i < message.length(); i++) {
      final char current = message.charAt(i);
      if (current != '&' || i + 1 >= message.length()) {
        output.append(current);
        continue;
      }

      final char code = Character.toLowerCase(message.charAt(i + 1));
      if (isAllowedCode(code, allowColors, allowFormats)) {
        output.append(SECTION_SIGN).append(code);
        i++;
        continue;
      }

      output.append(current);
    }

    return output.toString();
  }

  private boolean isAllowedCode(
      final char code, final boolean allowColors, final boolean allowFormats) {
    final String colorCodes = "0123456789abcdefx";
    final String formatCodes = "klmno";

    if (allowColors && colorCodes.indexOf(code) >= 0) {
      return true;
    }
    if (allowFormats && formatCodes.indexOf(code) >= 0) {
      return true;
    }

    return code == 'r' && (allowColors || allowFormats);
  }

  private String applyGeneralPlaceholders(
      final Player player, final String input, final String prefix) {
    String resolvedPlayerName = getResolvedPlayerPlaceholder(player);
    final String displayName = getResolvedNickname(player);
    if (resolvedPlayerName.equals(player.getName()) && !displayName.isBlank()) {
      resolvedPlayerName = displayName;
    }
    resolvedPlayerName = normalizeLegacyCodes(resolvedPlayerName);
    final String onlinePlayers = Integer.toString(
        this.plugin.getServer().getOnlinePlayers().size());
    final String maxPlayers = Integer.toString(this.plugin.getServer().getMaxPlayers());
    final String formattedWorldName = getConfiguredWorldName(player);
    final String normalizedPrefix = normalizeLegacyCodes(prefix);
    String output = input
        .replace("{prefix}", normalizedPrefix)
        .replace("{player}", resolvedPlayerName)
        .replace("{real_player}", player.getName())
        .replace("{display_name}", displayName)
        .replace("{world}", formattedWorldName)
        .replace("{world_alias}", getMultiverseWorldAlias(player))
        .replace("{x}", Integer.toString(player.getLocation().getBlockX()))
        .replace("{y}", Integer.toString(player.getLocation().getBlockY()))
        .replace("{z}", Integer.toString(player.getLocation().getBlockZ()))
        .replace("{time}", getCurrentTime())
        .replace("{world_time_24}", getWorldTime24(player))
        .replace("{world_time_12}", getWorldTime12(player))
        .replace("{online_players}", onlinePlayers)
        .replace("{max_players}", maxPlayers)
        .replace("{balance}", getVaultBalance(player))
        .replace("{balance_formatted}", getVaultBalanceFormatted(player));

    if (this.placeholderApiEnabled
        && this.plugin.getConfig().getBoolean("chat.enable-placeholderapi", true)) {
      output = PlaceholderAPI.setPlaceholders(player, output);
    }
    return output;
  }

  private boolean isMessageEnabled(final String path, final boolean fallback) {
    final FileConfiguration config = this.plugin.getConfig();
    return config.getBoolean(path, fallback);
  }

  private boolean shouldSuppressPlayerMessage(final String type, final Player player) {
    return shouldSuppressPlayerMessage(type, player, false);
  }

  private boolean shouldSuppressPlayerMessage(
      final String type, final Player player, final boolean ignoreVanish) {
    if (!ignoreVanish && isPlayerVanished(player)) {
      return true;
    }

    final FileConfiguration config = this.plugin.getConfig();
    final List<String> disabledNames = config.getStringList(
        "messages." + type + ".disabled-players");
    if (containsIgnoreCase(disabledNames, player.getName())) {
      return true;
    }

    final List<String> disabledUuids = config.getStringList(
        "messages." + type + ".disabled-player-uuids");
    final String uuid = player.getUniqueId().toString();
    return containsIgnoreCase(disabledUuids, uuid);
  }

  private void trackRecentJoin(final Player player) {
    markRecent(recentJoins, player.getName());
    recentLeaves.remove(player.getName().toLowerCase(Locale.ENGLISH));
  }

  private void trackRecentLeave(final Player player) {
    markRecent(recentLeaves, player.getName());
    recentJoins.remove(player.getName().toLowerCase(Locale.ENGLISH));
  }

  private void markRecent(final Map<String, Long> map, final String name) {
    if (name == null || name.isBlank()) {
      return;
    }
    pruneOldEntries(map);
    map.put(name.toLowerCase(Locale.ENGLISH), System.currentTimeMillis());
  }

  private void startVanishStatePolling() {
    if (this.vanishPollTaskId != -1) {
      return;
    }
    this.vanishPollTaskId = this.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
        this.plugin,
        this::pollVanishStates,
        40L,
        20L);
  }

  private void pollVanishStates() {
    final boolean debug = isVanishDebugEnabled();
    for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
      final boolean vanished = isPlayerVanished(player);
      final UUID uuid = player.getUniqueId();
      final Boolean previous = this.vanishStates.get(uuid);
      this.vanishStates.put(uuid, vanished);
      if (previous == null || previous == vanished) {
        if (debug && previous != null) {
          this.plugin.getLogger().info(
              "[VanishDebug] " + player.getName() + " unchanged vanished=" + vanished);
        }
        continue;
      }
      if (debug) {
        this.plugin.getLogger().info(
            "[VanishDebug] " + player.getName() + " changed vanished=" + previous
                + " -> " + vanished);
      }
      final String type = vanished ? "leave" : "join";
      sendVanishJoinLeaveMessage(player, type);
    }

    if (!this.vanishStates.isEmpty()) {
      this.vanishStates.keySet().removeIf(
          uuid -> this.plugin.getServer().getPlayer(uuid) == null);
    }
  }

  private void updateVanishState(final Player player, final boolean vanished) {
    if (player == null) {
      return;
    }
    this.vanishStates.put(player.getUniqueId(), vanished);
  }

  private void clearVanishState(final Player player) {
    if (player == null) {
      return;
    }
    this.vanishStates.remove(player.getUniqueId());
  }

  private boolean isRecentBroadcast(final VanillaBroadcast broadcast) {
    pruneOldEntries(recentJoins);
    pruneOldEntries(recentLeaves);
    final String key = broadcast.playerName().toLowerCase(Locale.ENGLISH);
    if ("join".equals(broadcast.type())) {
      return recentJoins.containsKey(key);
    }
    if ("leave".equals(broadcast.type())) {
      return recentLeaves.containsKey(key);
    }
    return false;
  }

  private void pruneOldEntries(final Map<String, Long> map) {
    if (map.isEmpty()) {
      return;
    }
    final long cutoff = System.currentTimeMillis() - RECENT_EVENT_WINDOW_MS;
    map.entrySet().removeIf(entry -> entry.getValue() < cutoff);
  }

  private VanillaBroadcast parseVanillaJoinLeave(final String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    final String trimmed = message.trim();
    if (trimmed.endsWith(VANILLA_JOIN_SUFFIX)) {
      final String name = trimmed.substring(
          0, trimmed.length() - VANILLA_JOIN_SUFFIX.length()).trim();
      if (!name.isBlank()) {
        return new VanillaBroadcast("join", name);
      }
    }
    if (trimmed.endsWith(VANILLA_LEAVE_SUFFIX)) {
      final String name = trimmed.substring(
          0, trimmed.length() - VANILLA_LEAVE_SUFFIX.length()).trim();
      if (!name.isBlank()) {
        return new VanillaBroadcast("leave", name);
      }
    }
    return null;
  }

  private Player findOnlinePlayerByName(final String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    final Player exact = this.plugin.getServer().getPlayerExact(name);
    if (exact != null) {
      return exact;
    }
    final String candidate = name.trim();
    for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
      if (player.getName().equalsIgnoreCase(candidate)) {
        return player;
      }
    }
    for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
      if (containsIgnoreCase(candidate, player.getName())) {
        return player;
      }
      final String displayName = PLAIN_TEXT_SERIALIZER.serialize(player.displayName());
      if (!displayName.isBlank() && containsIgnoreCase(candidate, displayName)) {
        return player;
      }
    }
    return null;
  }

  private boolean isPlayerVanished(final Player player) {
    if (!this.plugin.getConfig().getBoolean("integrations.vanish.hide-messages", true)) {
      return false;
    }

    if (isVanishedViaSuperVanishApi(player)) {
      logVanishDebug(player, "SuperVanishAPI", true);
      return true;
    }
    if (isVanishedViaEssentials(player)) {
      logVanishDebug(player, "Essentials", true);
      return true;
    }

    final List<String> metadataKeys = this.plugin.getConfig().getStringList(
        "integrations.vanish.metadata-keys");
    for (final String key : metadataKeys) {
      if (key == null || key.isBlank()) {
        continue;
      }

      if (!player.hasMetadata(key)) {
        continue;
      }

      for (final MetadataValue value : player.getMetadata(key)) {
        if (value.asBoolean()) {
          logVanishDebug(player, "Metadata:" + key, true);
          return true;
        }
      }
    }
    logVanishDebug(player, "none", false);
    return false;
  }

  private boolean isVanishDebugEnabled() {
    return this.plugin.getConfig().getBoolean("integrations.vanish.debug", false);
  }

  private void logVanishDebug(final Player player, final String source, final boolean vanished) {
    if (!isVanishDebugEnabled()) {
      return;
    }
    this.plugin.getLogger().info(
        "[VanishDebug] " + player.getName() + " source=" + source + " vanished=" + vanished);
  }

  private boolean isVanishedViaSuperVanishApi(final Player player) {
    final boolean superVanish = this.plugin.getServer().getPluginManager()
        .isPluginEnabled("SuperVanish");
    final boolean premiumVanish = this.plugin.getServer().getPluginManager()
        .isPluginEnabled("PremiumVanish");
    if (!superVanish && !premiumVanish) {
      return false;
    }

    try {
      final Class<?> vanishApi = Class.forName("de.myzelyam.api.vanish.VanishAPI");
      final java.lang.reflect.Method method = vanishApi.getMethod("isInvisible", Player.class);
      final Object result = method.invoke(null, player);
      return result instanceof Boolean && (Boolean) result;
    } catch (ReflectiveOperationException ex) {
      return false;
    }
  }

  private boolean isVanishedViaEssentials(final Player player) {
    if (!this.plugin.getServer().getPluginManager().isPluginEnabled("Essentials")) {
      return false;
    }

    final org.bukkit.plugin.Plugin essentials = this.plugin.getServer().getPluginManager()
        .getPlugin("Essentials");
    if (essentials == null) {
      return false;
    }

    try {
      Object user = null;
      try {
        final java.lang.reflect.Method getUserByUuid = essentials.getClass()
            .getMethod("getUser", UUID.class);
        user = getUserByUuid.invoke(essentials, player.getUniqueId());
      } catch (ReflectiveOperationException ignored) {
        final java.lang.reflect.Method getUserByName = essentials.getClass()
            .getMethod("getUser", String.class);
        user = getUserByName.invoke(essentials, player.getName());
      }

      if (user == null) {
        return false;
      }

      final java.lang.reflect.Method isVanished = user.getClass().getMethod("isVanished");
      final Object result = isVanished.invoke(user);
      return result instanceof Boolean && (Boolean) result;
    } catch (ReflectiveOperationException ex) {
      return false;
    }
  }

  private boolean containsIgnoreCase(final String haystack, final String needle) {
    if (haystack == null || needle == null) {
      return false;
    }
    return haystack.toLowerCase(Locale.ENGLISH).contains(needle.toLowerCase(Locale.ENGLISH));
  }

  private boolean containsIgnoreCase(final List<String> list, final String value) {
    for (final String entry : list) {
      if (entry != null && entry.equalsIgnoreCase(value)) {
        return true;
      }
    }
    return false;
  }

  private String getResolvedPlayerPlaceholder(final Player player) {
    if (!this.plugin.getConfig().getBoolean(
        "integrations.libsdisguises.use-disguise-name-for-player-placeholder", true)) {
      return player.getName();
    }

    if (!this.plugin.getServer().getPluginManager().isPluginEnabled("LibsDisguises")) {
      return player.getName();
    }

    try {
      final Class<?> api = Class.forName("me.libraryaddict.disguise.DisguiseAPI");
      final java.lang.reflect.Method getDisguise = api.getMethod(
          "getDisguise", org.bukkit.entity.Entity.class);
      final Object disguise = getDisguise.invoke(null, player);
      if (disguise == null) {
        return player.getName();
      }

      final String disguiseName = invokeStringMethod(disguise, "getDisguiseName");
      if (disguiseName != null && !disguiseName.isBlank()) {
        return disguiseName;
      }

      final String altName = invokeStringMethod(disguise, "getName");
      if (altName != null && !altName.isBlank()) {
        return altName;
      }
    } catch (ReflectiveOperationException ex) {
      return player.getName();
    }

    return player.getName();
  }

  private String invokeStringMethod(final Object target, final String methodName) {
    try {
      final java.lang.reflect.Method method = target.getClass().getMethod(methodName);
      final Object value = method.invoke(target);
      if (value instanceof String) {
        return (String) value;
      }
    } catch (ReflectiveOperationException ex) {
      return null;
    }
    return null;
  }

  private boolean isVanillaTemplate(final String template) {
    return template != null && template.trim().equalsIgnoreCase(VANILLA_TEMPLATE_TOKEN);
  }

  private boolean usePrefixedNicknamesInVanillaMessages() {
    return this.plugin.getConfig().getBoolean(
        "messages.use-prefixed-nicknames-in-vanilla", true);
  }

  private boolean includePrefixInVanillaFormattedNames() {
    return this.plugin.getConfig().getBoolean(
        "messages.include-prefix-in-vanilla-names", true);
  }

  private String getResolvedNickname(final Player player) {
    final String placeholderNickname = getNicknameFromPlaceholderApi(player);
    if (!placeholderNickname.isBlank()) {
      return placeholderNickname;
    }

    final String displayName = normalizeLegacyCodes(
        AMPERSAND_SERIALIZER.serialize(player.displayName()));
    if (!displayName.isBlank()) {
      return displayName;
    }
    return player.getName();
  }

  private String getNicknameFromPlaceholderApi(final Player player) {
    if (!this.placeholderApiEnabled
        || !this.plugin.getConfig().getBoolean("chat.enable-placeholderapi", true)) {
      return "";
    }

    for (final String placeholder : NICKNAME_PLACEHOLDER_CANDIDATES) {
      final String resolved = PlaceholderAPI.setPlaceholders(player, placeholder);
      if (resolved == null) {
        continue;
      }

      final String trimmed = resolved.trim();
      if (!trimmed.isBlank() && !trimmed.equals(placeholder)) {
        return normalizeLegacyCodes(trimmed);
      }
    }
    return "";
  }

  private String getVanillaFormattedName(final Player player) {
    final String prefix = includePrefixInVanillaFormattedNames()
        ? normalizeLegacyCodes(getResolvedPrefix(player))
        : "";
    final String nickname = normalizeLegacyCodes(getResolvedNickname(player));
    return prefix + nickname;
  }

  private Component rewriteVanillaDeathMessage(
      final Component originalMessage, final Player victim, final DeathContext deathContext) {
    if (originalMessage == null) {
      return null;
    }

    final Map<String, String> replacements = new LinkedHashMap<>();
    replacements.put(victim.getName(), getVanillaFormattedName(victim));
    if (!deathContext.killerPlayerName().isBlank()
        && !deathContext.killerDecoratedName().isBlank()) {
      replacements.put(deathContext.killerPlayerName(), deathContext.killerDecoratedName());
    }
    return rewriteVanillaMessage(originalMessage, replacements);
  }

  private Component rewriteVanillaMessage(
      final Component originalMessage, final Map<String, String> replacements) {
    if (originalMessage == null || replacements.isEmpty()) {
      return originalMessage;
    }

    Component rewritten = originalMessage;
    for (final Map.Entry<String, String> replacement : replacements.entrySet()) {
      final String rawName = replacement.getKey();
      final String replacementName = replacement.getValue();
      if (rawName == null
          || rawName.isBlank()
          || replacementName == null
          || replacementName.isBlank()) {
        continue;
      }

      rewritten = rewritten.replaceText(builder -> builder
          .matchLiteral(rawName)
          .replacement(AMPERSAND_SERIALIZER.deserialize(replacementName)));
    }
    return rewritten;
  }

  private String rewritePlainName(
      final String input, final String rawName, final String replacementName) {
    if (input == null
        || input.isBlank()
        || rawName == null
        || rawName.isBlank()
        || replacementName == null
        || replacementName.isBlank()) {
      return input;
    }
    return input.replace(rawName, replacementName);
  }

  private String normalizeLegacyCodes(final String input) {
    if (input == null || input.isBlank()) {
      return "";
    }

    final String sectionAsAmpersand = input.replace(SECTION_SIGN, '&');
    final Matcher matcher = HEX_AMPERSAND_PATTERN.matcher(sectionAsAmpersand);
    final StringBuffer converted = new StringBuffer(sectionAsAmpersand.length());
    while (matcher.find()) {
      final String hex = matcher.group(1);
      final String replacement = "&x&"
          + hex.charAt(0)
          + "&"
          + hex.charAt(1)
          + "&"
          + hex.charAt(2)
          + "&"
          + hex.charAt(3)
          + "&"
          + hex.charAt(4)
          + "&"
          + hex.charAt(5);
      matcher.appendReplacement(converted, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(converted);
    return converted.toString();
  }

  private FilterResult applyChatFilter(final Player player, final String plainMessage) {
    final FileConfiguration config = this.plugin.getConfig();
    if (!config.getBoolean("chat.filter.enabled", true)) {
      return new FilterResult(false, plainMessage);
    }

    final String bypassPermission = getConfigString(
        config, "permissions.chat-filter-bypass", "hudschatformatting.chat.filter.bypass");
    if (player.isOp() || player.hasPermission(bypassPermission)) {
      return new FilterResult(false, plainMessage);
    }

    final boolean caseSensitive = config.getBoolean("chat.filter.case-sensitive", false);
    final boolean wholeWordOnly = config.getBoolean("chat.filter.whole-word-only", false);
    final boolean applyReplacementsFirst = config.getBoolean(
        "chat.filter.apply-replacements-before-block-check", true);
    final List<String> blockedKeywords = config.getStringList("chat.filter.blocked-keywords");
    final Map<String, Object> replacements = getReplacementRules(config);

    if (!applyReplacementsFirst && containsBlockedKeyword(
        plainMessage, blockedKeywords, caseSensitive, wholeWordOnly)) {
      return new FilterResult(true, plainMessage);
    }

    final String replaced = applyReplacementRules(
        plainMessage, replacements, caseSensitive, wholeWordOnly);
    if (containsBlockedKeyword(replaced, blockedKeywords, caseSensitive, wholeWordOnly)) {
      return new FilterResult(true, plainMessage);
    }

    return new FilterResult(false, replaced);
  }

  private Map<String, Object> getReplacementRules(final FileConfiguration config) {
    final org.bukkit.configuration.ConfigurationSection section =
        config.getConfigurationSection("chat.filter.replacements");
    if (section == null) {
      return Map.of();
    }
    return section.getValues(false);
  }

  private boolean containsBlockedKeyword(
      final String message,
      final List<String> keywords,
      final boolean caseSensitive,
      final boolean wholeWordOnly) {
    for (final String keyword : keywords) {
      if (keyword == null || keyword.isBlank()) {
        continue;
      }

      final Pattern pattern = compileRulePattern(keyword, caseSensitive, wholeWordOnly);
      if (pattern.matcher(message).find()) {
        return true;
      }
    }
    return false;
  }

  private String applyReplacementRules(
      final String message,
      final Map<String, Object> replacements,
      final boolean caseSensitive,
      final boolean wholeWordOnly) {
    String output = message;
    for (final Map.Entry<String, Object> entry : replacements.entrySet()) {
      final String keyword = entry.getKey();
      if (keyword == null || keyword.isBlank()) {
        continue;
      }

      final String replacement = entry.getValue() == null ? "" : entry.getValue().toString();
      final Pattern pattern = compileRulePattern(keyword, caseSensitive, wholeWordOnly);
      output = pattern.matcher(output).replaceAll(Matcher.quoteReplacement(replacement));
    }
    return output;
  }

  private Pattern compileRulePattern(
      final String rule, final boolean caseSensitive, final boolean wholeWordOnly) {
    final boolean regexRule = rule.regionMatches(true, 0, "regex:", 0, 6);
    final String patternText = regexRule ? rule.substring(6) : Pattern.quote(rule);
    final String boundedPattern = !regexRule && wholeWordOnly
        ? "\\b" + patternText + "\\b"
        : patternText;
    final int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    try {
      return Pattern.compile(boundedPattern, flags);
    } catch (PatternSyntaxException ex) {
      this.plugin.getLogger().warning("Invalid chat.filter regex rule: " + rule);
      return Pattern.compile(Pattern.quote(rule), flags);
    }
  }

  private void sendBlockedMessageNotice(final Player player, final String originalMessage) {
    final FileConfiguration config = this.plugin.getConfig();
    if (!config.getBoolean("chat.filter.send-blocked-message", true)) {
      return;
    }

    final String raw = getConfigString(
        config, "chat.filter.blocked-message", "&cYour message was blocked by chat filters.");
    final String resolved = applyGeneralPlaceholders(player, raw, "")
        .replace("{message}", originalMessage);
    player.sendMessage(AMPERSAND_SERIALIZER.deserialize(resolved));
  }

  private record FilterResult(boolean blocked, String message) {}

  private record DeathContext(
      String causeKey,
      String killerTypeKey,
      String killerName,
      String killerPlayerName,
      String killerDecoratedName) {}

  private record VanillaBroadcast(String type, String playerName) {}

  private String getConfiguredWorldName(final Player player) {
    final FileConfiguration config = this.plugin.getConfig();
    final String worldName = player.getWorld().getName();
    final String worldPath = "chat.world-name-formats.worlds." + worldName;
    final String configuredWorldName = config.getString(worldPath);
    if (configuredWorldName != null && !configuredWorldName.isBlank()) {
      return configuredWorldName;
    }

    final String defaultWorldName = config.getString("chat.world-name-formats.default");
    if (defaultWorldName != null && !defaultWorldName.isBlank()) {
      return defaultWorldName.replace("{world}", worldName);
    }

    return worldName;
  }

  private String getMultiverseWorldAlias(final Player player) {
    if (!this.multiverseEnabled) {
      return player.getWorld().getName();
    }

    final Plugin multiverse = this.plugin.getServer().getPluginManager()
        .getPlugin("Multiverse-Core");
    if (multiverse == null) {
      return player.getWorld().getName();
    }

    try {
      final Object worldManager =
          multiverse.getClass().getMethod("getMVWorldManager").invoke(multiverse);
      if (worldManager == null) {
        return player.getWorld().getName();
      }

      final Object mvWorld = worldManager.getClass()
          .getMethod("getMVWorld", World.class)
          .invoke(worldManager, player.getWorld());
      if (mvWorld == null) {
        return player.getWorld().getName();
      }

      final Object alias = mvWorld.getClass().getMethod("getAlias").invoke(mvWorld);
      if (alias instanceof String && !((String) alias).isBlank()) {
        return (String) alias;
      }
    } catch (ReflectiveOperationException ex) {
      return player.getWorld().getName();
    }

    return player.getWorld().getName();
  }

  private String getConfigString(
      final FileConfiguration config, final String path, final String fallback) {
    final String value = config.getString(path);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value;
  }
}
