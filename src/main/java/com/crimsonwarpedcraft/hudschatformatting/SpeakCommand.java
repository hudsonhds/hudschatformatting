package com.crimsonwarpedcraft.hudschatformatting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * Commands for sending chat as another player.
 */
public final class SpeakCommand implements TabExecutor {

  private static final String SPEAK_PERMISSION = "hudschatformatting.speak";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final HudsChatFormattingPlugin plugin;

  /**
   * Creates a command executor for speak commands.
   *
   * @param plugin the owning plugin instance
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Command executors keep a plugin reference for config and logger access.")
  public SpeakCommand(final HudsChatFormattingPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Handles the /speak command.
   *
   * @param sender the command sender
   * @param command the command
   * @param label the label used
   * @param args the command arguments
   * @return true if handled
   */
  @Override
  public boolean onCommand(
      final CommandSender sender,
      final Command command,
      final String label,
      final String[] args) {
    return handleSpeak(sender, label, args, 0);
  }

  /**
   * Handles /hudschatformatting speak and /hcf speak.
   *
   * @param sender the command sender
   * @param label the label used
   * @param args the command arguments
   * @param offset the argument offset for subcommands
   * @return true if handled
   */
  public boolean handleSpeak(
      final CommandSender sender,
      final String label,
      final String[] args,
      final int offset) {
    if (!canUse(sender)) {
      sender.sendMessage(color("&cYou do not have permission to use this command."));
      return true;
    }
    final String baseCommand = "/" + label + (offset > 0 ? " speak" : "");
    if (args.length <= offset) {
      sender.sendMessage(color("&cUsage: " + baseCommand + " <player|all|random> <message>"));
      return true;
    }

    if (args.length <= offset + 1) {
      sender.sendMessage(color("&cMessage cannot be blank."));
      return true;
    }

    final String targetToken = args[offset];
    final String message = joinArgs(args, offset + 1).trim();
    if (message.isBlank()) {
      sender.sendMessage(color("&cMessage cannot be blank."));
      return true;
    }

    final List<Player> targets = resolveTargets(sender, targetToken);
    if (targets.isEmpty()) {
      sender.sendMessage(color("&cNo matching online players for: &f" + targetToken));
      return true;
    }

    for (final Player target : targets) {
      target.chat(message);
    }

    if (targets.size() == 1) {
      sender.sendMessage(color("&aSent message as &f" + targets.get(0).getName() + "&a."));
      return true;
    }
    sender.sendMessage(color("&aSent message as &f" + targets.size() + " &aplayer(s)."));
    return true;
  }

  /**
   * Tab completion for speak commands.
   *
   * @param sender the command sender
   * @param command the command
   * @param alias the alias
   * @param args the arguments
   * @return completion suggestions
   */
  @Override
  public List<String> onTabComplete(
      final CommandSender sender,
      final Command command,
      final String alias,
      final String[] args) {
    if (!canUse(sender)) {
      return Collections.emptyList();
    }
    if (args.length == 1) {
      return filterStartsWith(args[0], getTargetSuggestions());
    }
    return Collections.emptyList();
  }

  /**
   * Checks permission for speak commands.
   *
   * @param sender the command sender
   * @return true if allowed
   */
  public boolean canUse(final CommandSender sender) {
    return sender.isOp() || sender.hasPermission(SPEAK_PERMISSION);
  }

  /**
   * Provides tab completion target suggestions.
   *
   * @return the suggestion list
   */
  public List<String> getTargetSuggestions() {
    final List<String> options = new ArrayList<>();
    options.add("all");
    options.add("*");
    options.add("random");
    options.add("@a");
    options.add("@p");
    options.add("@r");
    options.add("@s");
    options.addAll(getOnlinePlayerNames());
    return options;
  }

  private List<Player> resolveTargets(final CommandSender sender, final String token) {
    if ("all".equalsIgnoreCase(token) || "*".equals(token) || "@a".equalsIgnoreCase(token)) {
      return new ArrayList<>(Bukkit.getOnlinePlayers());
    }
    if ("random".equalsIgnoreCase(token) || "@r".equalsIgnoreCase(token)) {
      final List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
      if (online.isEmpty()) {
        return Collections.emptyList();
      }
      return List.of(online.get(RANDOM.nextInt(online.size())));
    }
    if ("@s".equalsIgnoreCase(token)) {
      if (sender instanceof Player player) {
        return List.of(player);
      }
      return Collections.emptyList();
    }
    if ("@p".equalsIgnoreCase(token)) {
      if (sender instanceof Player player) {
        final Player nearest = findNearestPlayer(player.getLocation());
        return nearest == null ? Collections.emptyList() : List.of(nearest);
      }
      if (sender instanceof BlockCommandSender blockSender) {
        final Player nearest = findNearestPlayer(blockSender.getBlock().getLocation());
        return nearest == null ? Collections.emptyList() : List.of(nearest);
      }
      return Collections.emptyList();
    }

    final Player exact = Bukkit.getPlayerExact(token);
    if (exact != null) {
      return List.of(exact);
    }

    for (final Player player : Bukkit.getOnlinePlayers()) {
      if (player.getName().equalsIgnoreCase(token)) {
        return List.of(player);
      }
    }
    return Collections.emptyList();
  }

  private Player findNearestPlayer(final Location origin) {
    if (origin == null) {
      return null;
    }
    Player nearest = null;
    double best = Double.MAX_VALUE;
    for (final Player candidate : Bukkit.getOnlinePlayers()) {
      if (!candidate.getWorld().equals(origin.getWorld())) {
        continue;
      }
      final double distance = candidate.getLocation().distanceSquared(origin);
      if (distance < best) {
        best = distance;
        nearest = candidate;
      }
    }
    return nearest;
  }

  private List<String> getOnlinePlayerNames() {
    return Bukkit.getOnlinePlayers().stream()
        .map(Player::getName)
        .collect(Collectors.toList());
  }

  private List<String> filterStartsWith(final String prefix, final List<String> candidates) {
    final String lowerPrefix = prefix.toLowerCase(Locale.ENGLISH);
    return candidates.stream()
        .filter(candidate -> candidate.toLowerCase(Locale.ENGLISH).startsWith(lowerPrefix))
        .collect(Collectors.toList());
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

  private String color(final String input) {
    return ChatColor.translateAlternateColorCodes('&', input);
  }
}
