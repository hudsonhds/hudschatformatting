package com.crimsonwarpedcraft.hudschatformatting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Handles all chat formatting for the plugin.
 *
 * @author Copyright (c) Levi Muniz. All Rights Reserved.
 */
public final class ChatFormatListener implements Listener {

  private static final String MESSAGE_PLACEHOLDER = "{message}";
  private static final String DEFAULT_FORMAT = "&7[{time}] {prefix}&f{player}&7: {message}";
  private static final char SECTION_SIGN = (char) 167;
  private static final String BALANCE_UNAVAILABLE = "N/A";
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
    final String displayName = normalizeLegacyCodes(
        AMPERSAND_SERIALIZER.serialize(player.displayName()));
    final String onlinePlayers = Integer.toString(
        this.plugin.getServer().getOnlinePlayers().size());
    final String maxPlayers = Integer.toString(this.plugin.getServer().getMaxPlayers());
    final String formattedWorldName = getConfiguredWorldName(player);
    final String normalizedPrefix = normalizeLegacyCodes(prefix);
    String output = input
        .replace("{prefix}", normalizedPrefix)
        .replace("{player}", player.getName())
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
