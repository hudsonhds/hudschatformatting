package com.crimsonwarpedcraft.hudschatformatting;

import io.papermc.lib.PaperLib;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Created by Levi Muniz on 7/29/20.
 *
 * @author Copyright (c) Levi Muniz. All Rights Reserved.
 */
public class HudsChatFormattingPlugin extends JavaPlugin {

  private static final int LARGE_CONFIG_UPDATE_THRESHOLD = 8;
  private static final DateTimeFormatter BACKUP_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private boolean placeholderApiEnabled;
  private boolean multiverseEnabled;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);
    this.placeholderApiEnabled = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    this.multiverseEnabled = getServer().getPluginManager().isPluginEnabled("Multiverse-Core");

    saveDefaultConfig();
    final int addedDefaults = mergeMissingConfigDefaults();
    persistMergedConfigIfNeeded(addedDefaults, true);
    getServer().getPluginManager().registerEvents(
        new ChatFormatListener(
            this,
            getLuckPerms(),
            getVaultChat(),
            getVaultEconomy(),
            this.placeholderApiEnabled,
            this.multiverseEnabled),
        this);
    registerCommands();
  }

  private void registerCommands() {
    final PluginCommand command = getCommand("hudschatformatting");
    if (command == null) {
      getLogger().warning("Command 'hudschatformatting' is not defined in plugin.yml.");
      return;
    }

    final ChatFormattingAdminCommand executor = new ChatFormattingAdminCommand(this);
    command.setExecutor(executor);
    command.setTabCompleter(executor);
  }

  private LuckPerms getLuckPerms() {
    if (!getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
      return null;
    }

    try {
      return LuckPermsProvider.get();
    } catch (IllegalStateException ex) {
      getLogger().warning("LuckPerms is installed but the API is not ready yet.");
      return null;
    }
  }

  private Economy getVaultEconomy() {
    if (!getServer().getPluginManager().isPluginEnabled("Vault")) {
      return null;
    }

    final RegisteredServiceProvider<Economy> provider =
        getServer().getServicesManager().getRegistration(Economy.class);
    if (provider == null) {
      getLogger().warning("Vault is installed but no economy provider is registered.");
      return null;
    }

    return provider.getProvider();
  }

  private Chat getVaultChat() {
    if (!getServer().getPluginManager().isPluginEnabled("Vault")) {
      return null;
    }

    final RegisteredServiceProvider<Chat> provider =
        getServer().getServicesManager().getRegistration(Chat.class);
    if (provider == null) {
      getLogger().warning("Vault is installed but no chat provider is registered.");
      return null;
    }

    return provider.getProvider();
  }

  public boolean isPlaceholderApiEnabled() {
    return this.placeholderApiEnabled;
  }

  public boolean isMultiverseEnabled() {
    return this.multiverseEnabled;
  }

  private int mergeMissingConfigDefaults() {
    final InputStream input = getResource("config.yml");
    if (input == null) {
      getLogger().warning("Bundled config.yml not found; skipping config update.");
      return 0;
    }

    final YamlConfiguration defaults;
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      defaults = YamlConfiguration.loadConfiguration(reader);
    } catch (IOException ex) {
      getLogger().warning("Failed to read bundled config.yml; skipping config update.");
      return 0;
    }

    final FileConfiguration current = getConfig();
    current.setDefaults(defaults);
    return applyDefaultsRecursively(current, defaults);
  }

  /**
   * Reloads config.yml from disk, reapplies any missing defaults, and saves merged values.
   */
  public void reloadPluginConfig() {
    reloadConfig();
    final int addedDefaults = mergeMissingConfigDefaults();
    persistMergedConfigIfNeeded(addedDefaults, false);
  }

  private int applyDefaultsRecursively(
      final FileConfiguration current, final ConfigurationSection defaultsSection) {
    int added = 0;
    for (final String key : defaultsSection.getKeys(false)) {
      final String path = defaultsSection.getCurrentPath() == null
          ? key
          : defaultsSection.getCurrentPath() + "." + key;
      final Object defaultValue = defaultsSection.get(key);
      if (defaultValue instanceof ConfigurationSection nestedDefaults) {
        added += applyDefaultsRecursively(current, nestedDefaults);
        continue;
      }

      if (!current.isSet(path)) {
        current.set(path, defaultValue);
        added++;
      }
    }
    return added;
  }

  private void persistMergedConfigIfNeeded(final int addedDefaults, final boolean startup) {
    if (addedDefaults <= 0) {
      return;
    }

    final File configFile = new File(getDataFolder(), "config.yml");
    if (addedDefaults >= LARGE_CONFIG_UPDATE_THRESHOLD) {
      createConfigBackup(configFile, addedDefaults);
    }

    saveConfig();
    if (startup) {
      getLogger().info(
          "Added " + addedDefaults + " missing config option(s) to config.yml.");
      return;
    }

    getLogger().info(
        "Reload merged " + addedDefaults + " missing config option(s) into config.yml.");
  }

  private void createConfigBackup(final File configFile, final int addedDefaults) {
    if (!configFile.exists()) {
      return;
    }

    final String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
    final String backupName = "config.backup-" + timestamp + ".yml";
    final Path configPath = configFile.toPath();
    final Path parent = configPath.getParent();
    if (parent == null) {
      return;
    }
    final Path backupPath = parent.resolve(backupName);
    try {
      Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
      getLogger().info(
          "Large config update detected ("
              + addedDefaults
              + " keys). Backed up config.yml to "
              + backupName
              + ".");
    } catch (IOException ex) {
      getLogger().warning("Failed to create config backup before merge: " + backupName);
    }
  }
}
