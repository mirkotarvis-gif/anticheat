package dev.antiesp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import dev.antiesp.core.Settings;
import dev.antiesp.core.ViewerState;
import dev.antiesp.core.VisibilityEngine;
import dev.antiesp.packet.EspPacketListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class AntiESP extends JavaPlugin implements Listener {

    private final Map<UUID, ViewerState> states = new ConcurrentHashMap<>();
    private volatile Settings settings;
    private VisibilityEngine engine;
    private BukkitTask task;
    private PacketListenerCommon packetListener;
    private volatile long lastErrorLog;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        packetListener = PacketEvents.getAPI().getEventManager()
                .registerListener(new EspPacketListener(this), PacketListenerPriority.HIGH);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers()) createState(p);

        engine = new VisibilityEngine(this);
        task = getServer().getScheduler().runTaskTimer(this, engine, 1L, 1L);

        PluginCommand cmd = getCommand("antiesp");
        if (cmd != null) {
            AntiESPCommand executor = new AntiESPCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        getLogger().info("AntiESP enabled - players behind walls and hidden storage are no longer sent to clients.");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        if (packetListener != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        // Players keep whatever their client has; a relog / chunk reload restores everything.
        states.clear();
    }

    public void reloadSettings() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
    }

    public Settings settings() {
        return settings;
    }

    public Map<UUID, ViewerState> states() {
        return states;
    }

    public VisibilityEngine engine() {
        return engine;
    }

    private void createState(Player p) {
        state(p);
    }

    /** Created lazily from the packet listener too, so it exists before the first chunk is sent. */
    public ViewerState state(Player p) {
        return state(p.getUniqueId());
    }

    public ViewerState state(UUID uuid) {
        return states.computeIfAbsent(uuid, ViewerState::new);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        createState(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        UUID quitting = e.getPlayer().getUniqueId();
        // Make sure the real "player left" tab removal is never swallowed.
        for (ViewerState st : states.values()) st.keepTabEntry.remove(quitting);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitLate(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    public void logPacketError(PacketTypeCommon type, Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog < 10_000) return;
        lastErrorLog = now;
        getLogger().log(Level.WARNING, "Error while filtering " + type + " (further errors suppressed for 10s)", t);
    }
}
