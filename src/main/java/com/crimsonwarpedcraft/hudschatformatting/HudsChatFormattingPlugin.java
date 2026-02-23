package com.crimsonwarpedcraft.hudschatformatting;

import io.papermc.lib.PaperLib;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
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

  private boolean placeholderApiEnabled;
  private boolean multiverseEnabled;

  @Override
  public void onEnable() {
    PaperLib.suggestPaper(this);
    this.placeholderApiEnabled = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
    this.multiverseEnabled = getServer().getPluginManager().isPluginEnabled("Multiverse-Core");

    saveDefaultConfig();
    mergeMissingConfigDefaults();
    getServer().getPluginManager().registerEvents(
        new ChatFormatListener(
            this,
            getLuckPerms(),
            getVaultChat(),
            getVaultEconomy(),
            this.placeholderApiEnabled,
            this.multiverseEnabled),
        this);
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

  private void mergeMissingConfigDefaults() {
    final InputStream input = getResource("config.yml");
    if (input == null) {
      getLogger().warning("Bundled config.yml not found; skipping config update.");
      return;
    }

    final YamlConfiguration defaults;
    try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      defaults = YamlConfiguration.loadConfiguration(reader);
    } catch (Exception ex) {
      getLogger().warning("Failed to read bundled config.yml; skipping config update.");
      return;
    }

    final FileConfiguration current = getConfig();
    boolean changed = false;
    for (final String path : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(path) || current.isSet(path)) {
        continue;
      }
      current.set(path, defaults.get(path));
      changed = true;
    }

    if (changed) {
      saveConfig();
      getLogger().info("Config updated with missing default settings.");
    }
  }
}
