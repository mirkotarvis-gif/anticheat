package dev.antiesp;

import dev.antiesp.core.ViewerState;
import dev.antiesp.core.VisibilityEngine;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class AntiESPCommand implements TabExecutor {

    private final AntiESP plugin;

    public AntiESPCommand(AntiESP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadSettings();
                sender.sendMessage("§a[AntiESP] Config reloaded.");
            }
            case "status" -> {
                VisibilityEngine e = plugin.engine();
                sender.sendMessage("§b[AntiESP] §7tick cost: §f" + String.format("%.3f", e.lastTickNanos / 1_000_000.0) + "ms"
                        + " §7| hidden entities: §f" + e.hiddenEntityCount
                        + " §7| hidden storage blocks: §f" + e.hiddenBlockCount);
            }
            case "inspect" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /" + label + " inspect <player>");
                    return true;
                }
                Player target = plugin.getServer().getPlayerExact(args[1]);
                ViewerState st = target == null ? null : plugin.states().get(target.getUniqueId());
                if (st == null) {
                    sender.sendMessage("§cPlayer not found.");
                    return true;
                }
                int hiddenBlocks = 0, revealedBlocks = 0;
                for (Map<Long, Boolean> m : st.blocks.values()) {
                    for (Boolean b : m.values()) {
                        if (b) revealedBlocks++; else hiddenBlocks++;
                    }
                }
                sender.sendMessage("§b[AntiESP] §f" + target.getName() + (st.bypass ? " §e(bypass)" : ""));
                sender.sendMessage("§7 entities hidden/shown: §f" + st.hiddenEntities.size() + "§7/§f" + st.revealedEntities.size());
                sender.sendMessage("§7 storage hidden/shown: §f" + hiddenBlocks + "§7/§f" + revealedBlocks);
            }
            default -> sender.sendMessage("§cUsage: /" + label + " <status|inspect|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "inspect", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
