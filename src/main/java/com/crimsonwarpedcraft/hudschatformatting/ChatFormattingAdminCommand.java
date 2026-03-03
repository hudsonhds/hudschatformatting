package com.crimsonwarpedcraft.hudschatformatting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.advancement.Advancement;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Administrative commands for runtime config management.
 */
public final class ChatFormattingAdminCommand implements TabExecutor {

  private static final String ROOT_PERMISSION = "hudschatformatting.admin";
  private static final String RELOAD_PERMISSION = "hudschatformatting.admin.reload";
  private static final String FILTER_LIST_PERMISSION = "hudschatformatting.admin.filter.list";
  private static final String FILTER_ADD_PERMISSION = "hudschatformatting.admin.filter.add";
  private static final String FILTER_EDIT_PERMISSION = "hudschatformatting.admin.filter.edit";
  private static final String FILTER_REMOVE_PERMISSION = "hudschatformatting.admin.filter.remove";
  private static final String FILTER_TOGGLE_PERMISSION = "hudschatformatting.admin.filter.toggle";
  private static final String MESSAGE_LIST_PERMISSION = "hudschatformatting.admin.messages.list";
  private static final String MESSAGE_SET_PERMISSION = "hudschatformatting.admin.messages.set";
  private static final String MESSAGE_CLEAR_PERMISSION = "hudschatformatting.admin.messages.clear";
  private static final String MESSAGE_DISABLE_PERMISSION =
      "hudschatformatting.admin.messages.disable";
  private static final String MESSAGE_ENABLE_PERMISSION =
      "hudschatformatting.admin.messages.enable";

  private final HudsChatFormattingPlugin plugin;

  /**
   * Creates a command executor for managing plugin config values at runtime.
   *
   * @param plugin the owning plugin instance
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Command executors keep a plugin reference for config and logger access.")
  public ChatFormattingAdminCommand(final HudsChatFormattingPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(
      final CommandSender sender,
      final Command command,
      final String label,
      final String[] args) {
    if (!hasAdminAccess(sender)) {
      sender.sendMessage(color("&cYou do not have permission to use this command."));
      return true;
    }

    if (args.length == 0) {
      sendHelp(sender, label);
      return true;
    }

    final String sub = args[0].toLowerCase(Locale.ENGLISH);
    if ("reload".equals(sub)) {
      return handleReload(sender);
    }
    if ("filter".equals(sub)) {
      return handleFilter(sender, label, args);
    }
    if ("messages".equals(sub) || "message".equals(sub)) {
      return handleMessages(sender, label, args);
    }

    sendHelp(sender, label);
    return true;
  }

  private boolean handleReload(final CommandSender sender) {
    if (!sender.hasPermission(RELOAD_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + RELOAD_PERMISSION));
      return true;
    }

    this.plugin.reloadPluginConfig();
    sender.sendMessage(color("&aReloaded hudschatformatting config."));
    return true;
  }

  private boolean handleFilter(
      final CommandSender sender, final String label, final String[] args) {
    if (args.length < 2) {
      sendFilterHelp(sender, label);
      return true;
    }

    final String action = args[1].toLowerCase(Locale.ENGLISH);
    if ("list".equals(action)) {
      return handleFilterList(sender, args);
    }
    if ("add".equals(action)) {
      return handleFilterAdd(sender, args);
    }
    if ("edit".equals(action)) {
      return handleFilterEdit(sender, args);
    }
    if ("remove".equals(action)) {
      return handleFilterRemove(sender, args);
    }
    if ("toggle".equals(action)) {
      return handleFilterToggle(sender, args);
    }

    sendFilterHelp(sender, label);
    return true;
  }

  private boolean handleFilterList(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(FILTER_LIST_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + FILTER_LIST_PERMISSION));
      return true;
    }

    final FileConfiguration config = this.plugin.getConfig();
    final String target = args.length >= 3 ? args[2].toLowerCase(Locale.ENGLISH) : "all";

    if ("all".equals(target) || "blocked".equals(target)) {
      final List<String> blocked = config.getStringList("chat.filter.blocked-keywords");
      sender.sendMessage(color("&eBlocked keywords (" + blocked.size() + "):"));
      if (blocked.isEmpty()) {
        sender.sendMessage(color("&7  (none)"));
      } else {
        for (int i = 0; i < blocked.size(); i++) {
          sender.sendMessage(color("&7  " + (i + 1) + ". &f" + blocked.get(i)));
        }
      }
    }

    if ("all".equals(target) || "replacements".equals(target)) {
      final Map<String, Object> replacements = getReplacementMap(config);
      sender.sendMessage(color("&eReplacement rules (" + replacements.size() + "):"));
      if (replacements.isEmpty()) {
        sender.sendMessage(color("&7  (none)"));
      } else {
        for (final Map.Entry<String, Object> entry : replacements.entrySet()) {
          final String value = entry.getValue() == null ? "" : entry.getValue().toString();
          sender.sendMessage(color("&7  - &f" + entry.getKey() + " &7=> &f" + value));
        }
      }
    }

    if (!"all".equals(target) && !"blocked".equals(target) && !"replacements".equals(target)) {
      sender.sendMessage(color("&cUnknown filter list type. Use: blocked or replacements"));
    }
    return true;
  }

  private boolean handleFilterAdd(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(FILTER_ADD_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + FILTER_ADD_PERMISSION));
      return true;
    }
    if (args.length < 4) {
      sender.sendMessage(
          color("&cUsage: /hudschatformatting filter add <blocked|replacement> ..."));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    final FileConfiguration config = this.plugin.getConfig();
    if ("blocked".equals(type)) {
      final String rule = joinArgs(args, 3);
      if (rule.isBlank()) {
        sender.sendMessage(color("&cBlocked rule cannot be blank."));
        return true;
      }

      final List<String> blocked =
          new ArrayList<>(config.getStringList("chat.filter.blocked-keywords"));
      blocked.add(rule);
      config.set("chat.filter.blocked-keywords", blocked);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aAdded blocked filter #" + blocked.size() + ": &f" + rule));
      return true;
    }

    if ("replacement".equals(type)) {
      final String payload = joinArgs(args, 3);
      final String[] parts = splitReplacementPayload(payload);
      if (parts == null) {
        sender.sendMessage(
            color("&cUsage: /hudschatformatting filter add replacement <match> => <replacement>"));
        return true;
      }

      final Map<String, Object> replacements = getReplacementMap(config);
      replacements.put(parts[0], parts[1]);
      config.set("chat.filter.replacements", replacements);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aAdded replacement: &f" + parts[0] + " &7=> &f" + parts[1]));
      return true;
    }

    sender.sendMessage(color("&cUnknown filter add type. Use: blocked or replacement"));
    return true;
  }

  private boolean handleFilterEdit(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(FILTER_EDIT_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + FILTER_EDIT_PERMISSION));
      return true;
    }
    if (args.length < 5) {
      sender.sendMessage(
          color("&cUsage: /hudschatformatting filter edit <blocked|replacement> ..."));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    final FileConfiguration config = this.plugin.getConfig();
    if ("blocked".equals(type)) {
      final Integer index = parseIndex(args[3]);
      if (index == null || index < 1) {
        sender.sendMessage(color("&cBlocked filter index must be a positive number."));
        return true;
      }

      final List<String> blocked =
          new ArrayList<>(config.getStringList("chat.filter.blocked-keywords"));
      if (index > blocked.size()) {
        sender.sendMessage(
            color("&cBlocked filter index out of range. Current size: " + blocked.size()));
        return true;
      }

      final String rule = joinArgs(args, 4);
      if (rule.isBlank()) {
        sender.sendMessage(color("&cBlocked rule cannot be blank."));
        return true;
      }

      blocked.set(index - 1, rule);
      config.set("chat.filter.blocked-keywords", blocked);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aUpdated blocked filter #" + index + " to: &f" + rule));
      return true;
    }

    if ("replacement".equals(type)) {
      final String payload = joinArgs(args, 3);
      final String[] parts = splitReplacementPayload(payload);
      if (parts == null) {
        sender.sendMessage(
            color("&cUsage: /hudschatformatting filter edit replacement <match> => <replacement>"));
        return true;
      }

      final Map<String, Object> replacements = getReplacementMap(config);
      if (!replacements.containsKey(parts[0])) {
        sender.sendMessage(color("&cReplacement rule not found: &f" + parts[0]));
        return true;
      }

      replacements.put(parts[0], parts[1]);
      config.set("chat.filter.replacements", replacements);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aUpdated replacement: &f" + parts[0] + " &7=> &f" + parts[1]));
      return true;
    }

    sender.sendMessage(color("&cUnknown filter edit type. Use: blocked or replacement"));
    return true;
  }

  private boolean handleFilterRemove(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(FILTER_REMOVE_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + FILTER_REMOVE_PERMISSION));
      return true;
    }
    if (args.length < 4) {
      sender.sendMessage(
          color("&cUsage: /hudschatformatting filter remove <blocked|replacement> ..."));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    final FileConfiguration config = this.plugin.getConfig();
    if ("blocked".equals(type)) {
      final Integer index = parseIndex(args[3]);
      if (index == null || index < 1) {
        sender.sendMessage(color("&cBlocked filter index must be a positive number."));
        return true;
      }

      final List<String> blocked =
          new ArrayList<>(config.getStringList("chat.filter.blocked-keywords"));
      if (index > blocked.size()) {
        sender.sendMessage(
            color("&cBlocked filter index out of range. Current size: " + blocked.size()));
        return true;
      }

      final String removed = blocked.remove(index - 1);
      config.set("chat.filter.blocked-keywords", blocked);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aRemoved blocked filter #" + index + ": &f" + removed));
      return true;
    }

    if ("replacement".equals(type)) {
      final String key = joinArgs(args, 3).trim();
      if (key.isBlank()) {
        sender.sendMessage(color("&cReplacement key cannot be blank."));
        return true;
      }

      final Map<String, Object> replacements = getReplacementMap(config);
      if (!replacements.containsKey(key)) {
        sender.sendMessage(color("&cReplacement rule not found: &f" + key));
        return true;
      }

      replacements.remove(key);
      config.set("chat.filter.replacements", replacements);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aRemoved replacement rule: &f" + key));
      return true;
    }

    sender.sendMessage(color("&cUnknown filter remove type. Use: blocked or replacement"));
    return true;
  }

  private boolean handleFilterToggle(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(FILTER_TOGGLE_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + FILTER_TOGGLE_PERMISSION));
      return true;
    }
    if (args.length < 3) {
      sender.sendMessage(color("&cUsage: /hudschatformatting filter toggle <true|false>"));
      return true;
    }

    final String raw = args[2].toLowerCase(Locale.ENGLISH);
    if (!"true".equals(raw) && !"false".equals(raw)) {
      sender.sendMessage(color("&cValue must be true or false."));
      return true;
    }

    final boolean enabled = Boolean.parseBoolean(raw);
    this.plugin.getConfig().set("chat.filter.enabled", enabled);
    this.plugin.saveConfig();
    sender.sendMessage(
        color("&aChat filter is now " + (enabled ? "&2enabled" : "&cdisabled") + "&a."));
    return true;
  }

  private boolean handleMessages(
      final CommandSender sender, final String label, final String[] args) {
    if (args.length < 2) {
      sendMessageHelp(sender, label);
      return true;
    }

    final String action = args[1].toLowerCase(Locale.ENGLISH);
    if ("list".equals(action)) {
      return handleMessageList(sender, args);
    }
    if ("set".equals(action)) {
      return handleMessageSet(sender, args);
    }
    if ("clear".equals(action)) {
      return handleMessageClear(sender, args);
    }
    if ("disable".equals(action)) {
      return handleMessageDisable(sender, args);
    }
    if ("enable".equals(action)) {
      return handleMessageEnable(sender, args);
    }

    sendMessageHelp(sender, label);
    return true;
  }

  private boolean handleMessageList(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(MESSAGE_LIST_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + MESSAGE_LIST_PERMISSION));
      return true;
    }

    final String target = args.length >= 3 ? args[2].toLowerCase(Locale.ENGLISH) : "all";
    final FileConfiguration config = this.plugin.getConfig();

    if ("all".equals(target) || "join".equals(target)) {
      sender.sendMessage(
          color("&eJoin default: &f"
              + getConfigOrUnset(config, "messages.join.default")));
      final ConfigurationSection section =
          config.getConfigurationSection("messages.join.per-player");
      sender.sendMessage(
          color("&eJoin per-player overrides: &f"
              + (section == null ? 0 : section.getKeys(false).size())));
    }
    if ("all".equals(target) || "leave".equals(target)) {
      sender.sendMessage(
          color("&eLeave default: &f"
              + getConfigOrUnset(config, "messages.leave.default")));
      final ConfigurationSection section =
          config.getConfigurationSection("messages.leave.per-player");
      sender.sendMessage(
          color("&eLeave per-player overrides: &f"
              + (section == null ? 0 : section.getKeys(false).size())));
    }
    if ("all".equals(target) || "death".equals(target)) {
      sender.sendMessage(
          color("&eDeath default: &f"
              + getConfigOrUnset(config, "messages.death.default")));
      final ConfigurationSection mobSection =
          config.getConfigurationSection("messages.death.by-mob");
      sender.sendMessage(
          color("&eDeath mob overrides: &f"
              + (mobSection == null ? 0 : mobSection.getKeys(false).size())));
      if (mobSection != null) {
        for (final String key : mobSection.getKeys(false)) {
          sender.sendMessage(
              color("&7  - &f" + key + " &7=> &f" + mobSection.getString(key, "")));
        }
      }

      final ConfigurationSection section =
          config.getConfigurationSection("messages.death.by-cause");
      sender.sendMessage(
          color("&eDeath cause overrides: &f"
              + (section == null ? 0 : section.getKeys(false).size())));
      if (section != null) {
        for (final String key : section.getKeys(false)) {
          sender.sendMessage(color("&7  - &f" + key + " &7=> &f" + section.getString(key, "")));
        }
      }
    }
    if ("all".equals(target) || "advancement".equals(target)) {
      sender.sendMessage(
          color("&eAdvancement default: &f"
              + getConfigOrUnset(config, "messages.advancement.default")));
      final ConfigurationSection section =
          config.getConfigurationSection("messages.advancement.by-key");
      sender.sendMessage(
          color("&eAdvancement key overrides: &f"
              + (section == null ? 0 : section.getKeys(false).size())));
      if (section != null) {
        for (final String key : section.getKeys(false)) {
          sender.sendMessage(color("&7  - &f" + key + " &7=> &f" + section.getString(key, "")));
        }
      }
    }

    if (!"all".equals(target)
        && !"join".equals(target)
        && !"leave".equals(target)
        && !"death".equals(target)
        && !"advancement".equals(target)) {
      sender.sendMessage(color("&cUnknown list type. Use: join, leave, death, advancement, all"));
    }
    return true;
  }

  private boolean handleMessageSet(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(MESSAGE_SET_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + MESSAGE_SET_PERMISSION));
      return true;
    }
    if (args.length < 5) {
      sender.sendMessage(color("&cUsage: /hudschatformatting messages set <type> ..."));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    final FileConfiguration config = this.plugin.getConfig();

    if ("join".equals(type) || "leave".equals(type)) {
      final String scope = args[3].toLowerCase(Locale.ENGLISH);
      if ("default".equals(scope) || "global".equals(scope)) {
        final String template = joinArgs(args, 4);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages." + type + ".default", template);
        this.plugin.saveConfig();
        sender.sendMessage(color("&aUpdated " + type + " default template."));
        return true;
      }

      if ("player".equals(scope)) {
        if (args.length < 6) {
          sender.sendMessage(color(
              "&cUsage: /hudschatformatting messages set " + type + " player <name> <template>"));
          return true;
        }
        final String playerName = args[4];
        final String template = joinArgs(args, 5);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages." + type + ".per-player." + playerName, template);
        this.plugin.saveConfig();
        sender.sendMessage(
            color("&aUpdated "
                + type
                + " template for player &f"
                + playerName
                + "&a."));
        return true;
      }

      sender.sendMessage(color("&cUnknown scope. Use: default|global|player"));
      return true;
    }

    if ("death".equals(type)) {
      final String scope = args[3].toLowerCase(Locale.ENGLISH);
      if ("default".equals(scope)) {
        final String template = joinArgs(args, 4);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages.death.default", template);
        this.plugin.saveConfig();
        sender.sendMessage(color("&aUpdated death default template."));
        return true;
      }

      if ("cause".equals(scope)) {
        if (args.length < 6) {
          sender.sendMessage(color(
              "&cUsage: /hudschatformatting messages set death cause <CAUSE> <template>"));
          return true;
        }
        final String cause = args[4].toUpperCase(Locale.ENGLISH);
        final String template = joinArgs(args, 5);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages.death.by-cause." + cause, template);
        this.plugin.saveConfig();
        sender.sendMessage(color("&aUpdated death template for cause &f" + cause + "&a."));
        return true;
      }

      if ("mob".equals(scope)) {
        if (args.length < 6) {
          sender.sendMessage(
              color("&cUsage: /hudschatformatting messages set death mob <MOB> <template>"));
          return true;
        }
        final String mob = args[4].toUpperCase(Locale.ENGLISH);
        final String template = joinArgs(args, 5);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages.death.by-mob." + mob, template);
        this.plugin.saveConfig();
        sender.sendMessage(color("&aUpdated death template for mob &f" + mob + "&a."));
        return true;
      }

      sender.sendMessage(color("&cUnknown scope. Use: default|cause|mob"));
      return true;
    }

    if ("advancement".equals(type)) {
      final String scope = args[3].toLowerCase(Locale.ENGLISH);
      if ("default".equals(scope)) {
        final String template = joinArgs(args, 4);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages.advancement.default", template);
        this.plugin.saveConfig();
        sender.sendMessage(color("&aUpdated advancement default template."));
        return true;
      }

      if ("key".equals(scope)) {
        if (args.length < 6) {
          sender.sendMessage(
              color("&cUsage: /hudschatformatting messages set advancement key "
                  + "<namespace:key> <template>"));
          return true;
        }
        final String key = args[4];
        final String template = joinArgs(args, 5);
        if (template.isBlank()) {
          sender.sendMessage(color("&cTemplate cannot be blank."));
          return true;
        }
        config.set("messages.advancement.by-key." + key, template);
        this.plugin.saveConfig();
        sender.sendMessage(color("&aUpdated advancement template for key &f" + key + "&a."));
        return true;
      }

      sender.sendMessage(color("&cUnknown scope. Use: default|key"));
      return true;
    }

    sender.sendMessage(color("&cUnknown message type. Use: join, leave, death, advancement"));
    return true;
  }

  private boolean handleMessageClear(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(MESSAGE_CLEAR_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + MESSAGE_CLEAR_PERMISSION));
      return true;
    }
    if (args.length < 5) {
      sender.sendMessage(color("&cUsage: /hudschatformatting messages clear <type> ..."));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    final String scope = args[3].toLowerCase(Locale.ENGLISH);
    final FileConfiguration config = this.plugin.getConfig();

    if (("join".equals(type) || "leave".equals(type)) && "player".equals(scope)) {
      final String playerName = args[4];
      final String path = "messages." + type + ".per-player." + playerName;
      if (!config.isSet(path)) {
        sender.sendMessage(color("&cNo " + type + " override exists for &f" + playerName));
        return true;
      }
      config.set(path, null);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aCleared " + type + " override for &f" + playerName + "&a."));
      return true;
    }

    if ("death".equals(type) && "cause".equals(scope)) {
      final String cause = args[4].toUpperCase(Locale.ENGLISH);
      final String path = "messages.death.by-cause." + cause;
      if (!config.isSet(path)) {
        sender.sendMessage(color("&cNo death override exists for cause &f" + cause));
        return true;
      }
      config.set(path, null);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aCleared death override for cause &f" + cause + "&a."));
      return true;
    }

    if ("death".equals(type) && "mob".equals(scope)) {
      final String mob = args[4].toUpperCase(Locale.ENGLISH);
      final String path = "messages.death.by-mob." + mob;
      if (!config.isSet(path)) {
        sender.sendMessage(color("&cNo death override exists for mob &f" + mob));
        return true;
      }
      config.set(path, null);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aCleared death override for mob &f" + mob + "&a."));
      return true;
    }

    if ("advancement".equals(type) && "key".equals(scope)) {
      final String key = args[4];
      final String path = "messages.advancement.by-key." + key;
      if (!config.isSet(path)) {
        sender.sendMessage(color("&cNo advancement override exists for key &f" + key));
        return true;
      }
      config.set(path, null);
      this.plugin.saveConfig();
      sender.sendMessage(color("&aCleared advancement override for key &f" + key + "&a."));
      return true;
    }

    sender.sendMessage(color("&cUnknown clear target. See /hudschatformatting messages"));
    return true;
  }

  private boolean handleMessageDisable(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(MESSAGE_DISABLE_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + MESSAGE_DISABLE_PERMISSION));
      return true;
    }
    if (args.length < 5) {
      sender.sendMessage(
          color("&cUsage: /hudschatformatting messages disable <type> player <name>"));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    if (!List.of("join", "leave", "death", "advancement").contains(type)) {
      sender.sendMessage(color("&cUnknown message type. Use: join, leave, death, advancement"));
      return true;
    }

    if (!"player".equalsIgnoreCase(args[3])) {
      sender.sendMessage(color("&cUsage: /hudschatformatting messages disable "
          + type + " player <name>"));
      return true;
    }

    final String playerName = args[4];
    final FileConfiguration config = this.plugin.getConfig();
    final String path = "messages." + type + ".disabled-players";
    final List<String> disabled = new ArrayList<>(config.getStringList(path));
    if (!containsIgnoreCase(disabled, playerName)) {
      disabled.add(playerName);
    }
    config.set(path, disabled);
    this.plugin.saveConfig();
    sender.sendMessage(color("&aDisabled " + type + " messages for &f" + playerName + "&a."));
    return true;
  }

  private boolean handleMessageEnable(final CommandSender sender, final String[] args) {
    if (!sender.hasPermission(MESSAGE_ENABLE_PERMISSION)) {
      sender.sendMessage(color("&cMissing permission: " + MESSAGE_ENABLE_PERMISSION));
      return true;
    }
    if (args.length < 5) {
      sender.sendMessage(
          color("&cUsage: /hudschatformatting messages enable <type> player <name>"));
      return true;
    }

    final String type = args[2].toLowerCase(Locale.ENGLISH);
    if (!List.of("join", "leave", "death", "advancement").contains(type)) {
      sender.sendMessage(color("&cUnknown message type. Use: join, leave, death, advancement"));
      return true;
    }

    if (!"player".equalsIgnoreCase(args[3])) {
      sender.sendMessage(color("&cUsage: /hudschatformatting messages enable "
          + type + " player <name>"));
      return true;
    }

    final String playerName = args[4];
    final FileConfiguration config = this.plugin.getConfig();
    final String path = "messages." + type + ".disabled-players";
    final List<String> disabled = new ArrayList<>(config.getStringList(path));
    final boolean removed = removeIgnoreCase(disabled, playerName);
    if (!removed) {
      sender.sendMessage(color("&c" + playerName + " was not disabled for " + type + "."));
      return true;
    }

    config.set(path, disabled);
    this.plugin.saveConfig();
    sender.sendMessage(color("&aEnabled " + type + " messages for &f" + playerName + "&a."));
    return true;
  }

  private Map<String, Object> getReplacementMap(final FileConfiguration config) {
    final ConfigurationSection section = config.getConfigurationSection("chat.filter.replacements");
    if (section == null) {
      return new LinkedHashMap<>();
    }
    return new LinkedHashMap<>(section.getValues(false));
  }

  private Integer parseIndex(final String raw) {
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private void sendHelp(final CommandSender sender, final String label) {
    sender.sendMessage(color("&e/" + label + " reload"));
    sender.sendMessage(color("&e/" + label + " filter list [blocked|replacements]"));
    sender.sendMessage(color("&e/" + label + " filter add blocked <rule>"));
    sender.sendMessage(
        color("&e/" + label + " filter add replacement <match> => <replacement>"));
    sender.sendMessage(color("&e/" + label + " filter edit blocked <index> <rule>"));
    sender.sendMessage(
        color("&e/" + label + " filter edit replacement <match> => <replacement>"));
    sender.sendMessage(color("&e/" + label + " filter remove blocked <index>"));
    sender.sendMessage(color("&e/" + label + " filter remove replacement <match>"));
    sender.sendMessage(color("&e/" + label + " filter toggle <true|false>"));
    sender.sendMessage(color("&e/" + label + " messages <list|set|clear> ..."));
  }

  private void sendFilterHelp(final CommandSender sender, final String label) {
    sender.sendMessage(color("&e/" + label + " filter list [blocked|replacements]"));
    sender.sendMessage(color("&e/" + label + " filter add blocked <rule>"));
    sender.sendMessage(
        color("&e/" + label + " filter add replacement <match> => <replacement>"));
    sender.sendMessage(color("&e/" + label + " filter edit blocked <index> <rule>"));
    sender.sendMessage(
        color("&e/" + label + " filter edit replacement <match> => <replacement>"));
    sender.sendMessage(color("&e/" + label + " filter remove blocked <index>"));
    sender.sendMessage(color("&e/" + label + " filter remove replacement <match>"));
    sender.sendMessage(color("&e/" + label + " filter toggle <true|false>"));
  }

  private void sendMessageHelp(final CommandSender sender, final String label) {
    sender.sendMessage(
        color("&e/" + label + " messages list [join|leave|death|advancement|all]"));
    sender.sendMessage(color("&e/" + label + " messages set join default <template>"));
    sender.sendMessage(color("&e/" + label + " messages set join player <name> <template>"));
    sender.sendMessage(color("&e/" + label + " messages set leave default <template>"));
    sender.sendMessage(color("&e/" + label + " messages set leave player <name> <template>"));
    sender.sendMessage(color("&e/" + label + " messages set death default <template>"));
    sender.sendMessage(color("&e/" + label + " messages set death cause <CAUSE> <template>"));
    sender.sendMessage(color("&e/" + label + " messages set death mob <MOB> <template>"));
    sender.sendMessage(color("&e/" + label + " messages set advancement default <template>"));
    sender.sendMessage(
        color("&e/" + label + " messages set advancement key <namespace:key> <template>"));
    sender.sendMessage(color("&e/" + label + " messages clear join player <name>"));
    sender.sendMessage(color("&e/" + label + " messages clear leave player <name>"));
    sender.sendMessage(color("&e/" + label + " messages clear death cause <CAUSE>"));
    sender.sendMessage(color("&e/" + label + " messages clear death mob <MOB>"));
    sender.sendMessage(color("&e/" + label + " messages clear advancement key <namespace:key>"));
    sender.sendMessage(color("&e/" + label + " messages disable <type> player <name>"));
    sender.sendMessage(color("&e/" + label + " messages enable <type> player <name>"));
  }

  private String[] splitReplacementPayload(final String payload) {
    final int separator = payload.indexOf("=>");
    if (separator < 0) {
      return null;
    }

    final String key = payload.substring(0, separator).trim();
    final String value = payload.substring(separator + 2).trim();
    if (key.isBlank()) {
      return null;
    }
    return new String[] {key, value};
  }

  private String joinArgs(final String[] args, final int start) {
    if (args.length <= start) {
      return "";
    }
    final StringBuilder result = new StringBuilder();
    for (int i = start; i < args.length; i++) {
      if (i > start) {
        result.append(' ');
      }
      result.append(args[i]);
    }
    return result.toString();
  }

  private String getConfigOrUnset(final FileConfiguration config, final String path) {
    final String value = config.getString(path);
    if (value == null || value.isBlank()) {
      return "(unset)";
    }
    return value;
  }

  private String color(final String input) {
    return ChatColor.translateAlternateColorCodes('&', input);
  }

  @Override
  public List<String> onTabComplete(
      final CommandSender sender,
      final Command command,
      final String alias,
      final String[] args) {
    if (!hasAdminAccess(sender)) {
      return Collections.emptyList();
    }

    if (args.length == 1) {
      return filterStartsWith(args[0], List.of("reload", "filter", "messages"));
    }

    if (args.length == 2 && "filter".equalsIgnoreCase(args[0])) {
      return filterStartsWith(args[1], List.of("list", "add", "edit", "remove", "toggle"));
    }

    if (args.length == 2
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))) {
      return filterStartsWith(args[1], List.of("list", "set", "clear", "disable", "enable"));
    }

    if (args.length == 3
        && "filter".equalsIgnoreCase(args[0])
        && ("add".equalsIgnoreCase(args[1])
            || "edit".equalsIgnoreCase(args[1])
            || "remove".equalsIgnoreCase(args[1]))) {
      return filterStartsWith(args[2], List.of("blocked", "replacement"));
    }

    if (args.length == 3
        && "filter".equalsIgnoreCase(args[0])
        && "list".equalsIgnoreCase(args[1])) {
      return filterStartsWith(args[2], List.of("blocked", "replacements"));
    }

    if (args.length == 3
        && "filter".equalsIgnoreCase(args[0])
        && "toggle".equalsIgnoreCase(args[1])) {
      return filterStartsWith(args[2], List.of("true", "false"));
    }

    if (args.length == 3
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "list".equalsIgnoreCase(args[1])) {
      return filterStartsWith(args[2], List.of("join", "leave", "death", "advancement", "all"));
    }

    if (args.length == 3
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && ("set".equalsIgnoreCase(args[1])
            || "clear".equalsIgnoreCase(args[1])
            || "disable".equalsIgnoreCase(args[1])
            || "enable".equalsIgnoreCase(args[1]))) {
      return filterStartsWith(args[2], List.of("join", "leave", "death", "advancement"));
    }

    if (args.length == 4
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && ("disable".equalsIgnoreCase(args[1]) || "enable".equalsIgnoreCase(args[1]))
        && List.of("join", "leave", "death", "advancement").contains(
            args[2].toLowerCase(Locale.ENGLISH))) {
      return filterStartsWith(args[3], List.of("player"));
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "set".equalsIgnoreCase(args[1])
        && ("join".equalsIgnoreCase(args[2]) || "leave".equalsIgnoreCase(args[2]))
        && "player".equalsIgnoreCase(args[3])) {
      return filterStartsWith(args[4], getOnlinePlayerNames());
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "clear".equalsIgnoreCase(args[1])
        && ("join".equalsIgnoreCase(args[2]) || "leave".equalsIgnoreCase(args[2]))
        && "player".equalsIgnoreCase(args[3])) {
      return filterStartsWith(args[4], getOnlinePlayerNames());
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "disable".equalsIgnoreCase(args[1])
        && List.of("join", "leave", "death", "advancement").contains(
            args[2].toLowerCase(Locale.ENGLISH))
        && "player".equalsIgnoreCase(args[3])) {
      return filterStartsWith(args[4], getOnlinePlayerNames());
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "enable".equalsIgnoreCase(args[1])
        && List.of("join", "leave", "death", "advancement").contains(
            args[2].toLowerCase(Locale.ENGLISH))
        && "player".equalsIgnoreCase(args[3])) {
      return filterStartsWith(args[4], getOnlinePlayerNames());
    }

    if (args.length == 4
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "set".equalsIgnoreCase(args[1])
        && ("join".equalsIgnoreCase(args[2]) || "leave".equalsIgnoreCase(args[2]))) {
      return filterStartsWith(args[3], List.of("default", "global", "player"));
    }

    if (args.length == 4
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "clear".equalsIgnoreCase(args[1])
        && ("join".equalsIgnoreCase(args[2]) || "leave".equalsIgnoreCase(args[2]))) {
      return filterStartsWith(args[3], List.of("player"));
    }

    if (args.length == 4
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && ("set".equalsIgnoreCase(args[1]) || "clear".equalsIgnoreCase(args[1]))
        && "death".equalsIgnoreCase(args[2])) {
      return filterStartsWith(args[3], List.of("default", "cause", "mob"));
    }

    if (args.length == 4
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && ("set".equalsIgnoreCase(args[1]) || "clear".equalsIgnoreCase(args[1]))
        && "advancement".equalsIgnoreCase(args[2])) {
      return filterStartsWith(args[3], List.of("default", "key"));
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "set".equalsIgnoreCase(args[1])
        && "death".equalsIgnoreCase(args[2])
        && "cause".equalsIgnoreCase(args[3])) {
      final List<String> causes = new ArrayList<>();
      for (final org.bukkit.event.entity.EntityDamageEvent.DamageCause cause
          : org.bukkit.event.entity.EntityDamageEvent.DamageCause.values()) {
        causes.add(cause.name());
      }
      return filterStartsWith(args[4], causes);
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "set".equalsIgnoreCase(args[1])
        && "death".equalsIgnoreCase(args[2])
        && "mob".equalsIgnoreCase(args[3])) {
      final List<String> mobs = new ArrayList<>();
      for (final EntityType type : EntityType.values()) {
        if (!type.isAlive()) {
          continue;
        }
        mobs.add(type.name());
      }
      return filterStartsWith(args[4], mobs);
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "set".equalsIgnoreCase(args[1])
        && "advancement".equalsIgnoreCase(args[2])
        && "key".equalsIgnoreCase(args[3])) {
      final List<String> advancementKeys = new ArrayList<>();
      final java.util.Iterator<Advancement> iterator = Bukkit.advancementIterator();
      while (iterator.hasNext()) {
        advancementKeys.add(iterator.next().getKey().toString());
      }
      return filterStartsWith(args[4], advancementKeys);
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "clear".equalsIgnoreCase(args[1])
        && "death".equalsIgnoreCase(args[2])
        && "cause".equalsIgnoreCase(args[3])) {
      final ConfigurationSection section =
          this.plugin.getConfig().getConfigurationSection("messages.death.by-cause");
      final List<String> keys = section == null
          ? Collections.emptyList()
          : new ArrayList<>(section.getKeys(false));
      return filterStartsWith(args[4], keys);
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "clear".equalsIgnoreCase(args[1])
        && "death".equalsIgnoreCase(args[2])
        && "mob".equalsIgnoreCase(args[3])) {
      final ConfigurationSection section =
          this.plugin.getConfig().getConfigurationSection("messages.death.by-mob");
      final List<String> keys = section == null
          ? Collections.emptyList()
          : new ArrayList<>(section.getKeys(false));
      return filterStartsWith(args[4], keys);
    }

    if (args.length == 5
        && ("messages".equalsIgnoreCase(args[0]) || "message".equalsIgnoreCase(args[0]))
        && "clear".equalsIgnoreCase(args[1])
        && "advancement".equalsIgnoreCase(args[2])
        && "key".equalsIgnoreCase(args[3])) {
      final ConfigurationSection section =
          this.plugin.getConfig().getConfigurationSection("messages.advancement.by-key");
      final List<String> keys = section == null
          ? Collections.emptyList()
          : new ArrayList<>(section.getKeys(false));
      return filterStartsWith(args[4], keys);
    }

    if (args.length == 4
        && "filter".equalsIgnoreCase(args[0])
        && ("edit".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))
        && "blocked".equalsIgnoreCase(args[2])) {
      final List<String> blocked =
          this.plugin.getConfig().getStringList("chat.filter.blocked-keywords");
      final List<String> indexes = new ArrayList<>();
      for (int i = 1; i <= blocked.size(); i++) {
        indexes.add(Integer.toString(i));
      }
      return filterStartsWith(args[3], indexes);
    }

    if (args.length == 4
        && "filter".equalsIgnoreCase(args[0])
        && ("edit".equalsIgnoreCase(args[1]) || "remove".equalsIgnoreCase(args[1]))
        && "replacement".equalsIgnoreCase(args[2])) {
      final List<String> keys =
          new ArrayList<>(getReplacementMap(this.plugin.getConfig()).keySet());
      return filterStartsWith(args[3], keys);
    }

    return Collections.emptyList();
  }

  private List<String> filterStartsWith(final String prefix, final List<String> candidates) {
    final String lowerPrefix = prefix.toLowerCase(Locale.ENGLISH);
    return candidates.stream()
        .filter(candidate -> candidate.toLowerCase(Locale.ENGLISH).startsWith(lowerPrefix))
        .collect(Collectors.toList());
  }

  private boolean containsIgnoreCase(final List<String> values, final String target) {
    for (final String value : values) {
      if (value != null && value.equalsIgnoreCase(target)) {
        return true;
      }
    }
    return false;
  }

  private boolean removeIgnoreCase(final List<String> values, final String target) {
    for (int i = 0; i < values.size(); i++) {
      final String value = values.get(i);
      if (value != null && value.equalsIgnoreCase(target)) {
        values.remove(i);
        return true;
      }
    }
    return false;
  }

  private List<String> getOnlinePlayerNames() {
    final List<String> names = new ArrayList<>();
    for (final Player online : Bukkit.getOnlinePlayers()) {
      names.add(online.getName());
    }
    return names;
  }

  private boolean hasAdminAccess(final CommandSender sender) {
    if (sender.isOp()) {
      return true;
    }

    final String configured = this.plugin.getConfig()
        .getString("permissions.admin-command", ROOT_PERMISSION);
    if (configured == null || configured.isBlank()) {
      return sender.hasPermission(ROOT_PERMISSION);
    }

    return sender.hasPermission(configured);
  }
}
