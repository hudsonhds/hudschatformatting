package com.crimsonwarpedcraft.hudschatformatting;

import io.papermc.lib.PaperLib;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
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
}
