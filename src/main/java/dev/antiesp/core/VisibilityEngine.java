package dev.antiesp.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import dev.antiesp.AntiESP;
import dev.antiesp.packet.EspPacketListener;
import dev.antiesp.util.BlockKey;
import dev.antiesp.util.Raycaster;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Main-thread brain: every tick it decides, per viewer, which protected entities and
 * storage blocks are in line of sight, and reveals / hides them.
 * Viewers are spread over ticks so the cost is even.
 */
public final class VisibilityEngine implements Runnable {

    private final AntiESP plugin;
    private final Raycaster ray = new Raycaster();
    /** Last-tick positions of entities, for movement lookahead (players have no server velocity). */
    private final Map<UUID, double[]> lastPos = new HashMap<>();
    private long tick;

    // stats for /antiesp status
    public volatile long lastTickNanos;
    public volatile int hiddenEntityCount, hiddenBlockCount;

    public VisibilityEngine(AntiESP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        tick++;
        Settings s = plugin.settings();
        Collection<? extends Player> online = plugin.getServer().getOnlinePlayers();

        int index = 0;
        int hiddenE = 0, hiddenB = 0;
        for (Player viewer : online) {
            ViewerState st = plugin.states().get(viewer.getUniqueId());
            if (st == null) continue;
            refreshCache(viewer, st, s);
            EspPacketListener.expireMarkers(st, start);

            if (s.entitiesEnabled && (tick + index) % s.entityInterval == 0) {
                checkEntities(viewer, st, s);
            }
            if (s.blocksEnabled && (tick + index) % s.blockInterval == 0) {
                checkBlocks(viewer, st, s);
            }
            hiddenE += st.hiddenEntities.size();
            for (Map<Long, Boolean> m : st.blocks.values()) for (Boolean b : m.values()) if (!b) hiddenB++;
            index++;
        }

        if (tick % 100 == 0) {
            plugin.states().keySet().removeIf(u -> plugin.getServer().getPlayer(u) == null);
        }

        // update motion history after checks
        lastPos.keySet().removeIf(u -> plugin.getServer().getPlayer(u) == null);
        for (Player p : online) {
            Location l = p.getLocation();
            lastPos.put(p.getUniqueId(), new double[]{l.getX(), l.getY(), l.getZ()});
        }

        hiddenEntityCount = hiddenE;
        hiddenBlockCount = hiddenB;
        lastTickNanos = System.nanoTime() - start;
    }

    private void refreshCache(Player viewer, ViewerState st, Settings s) {
        Location eye = viewer.getEyeLocation();
        st.x = eye.getX();
        st.y = eye.getY();
        st.z = eye.getZ();
        st.selfEntityId = viewer.getEntityId();
        Entity vehicle = viewer.getVehicle();
        st.vehicleId = vehicle == null ? -1 : vehicle.getEntityId();
        st.bypass = viewer.hasPermission(s.bypassPermission)
                || (s.spectatorsBypass && viewer.getGameMode() == GameMode.SPECTATOR)
                || s.isWorldDisabled(viewer.getWorld());
    }

    // =====================================================================
    // Entities
    // =====================================================================

    private void checkEntities(Player viewer, ViewerState st, Settings s) {
        World world = viewer.getWorld();

        if (st.bypass) {
            if (!st.hiddenEntities.isEmpty()) {
                for (Integer id : List.copyOf(st.hiddenEntities)) {
                    Entity e = SpigotConversionUtil.getEntityById(world, id);
                    if (e != null) reveal(viewer, st, e);
                    else st.hiddenEntities.remove(id);
                }
            }
            st.hiddenPositions = new double[0];
            return;
        }

        if (st.hiddenEntities.isEmpty() && st.revealedEntities.isEmpty()) {
            st.hiddenPositions = new double[0];
            return;
        }

        double max = s.entityMaxDistance, max2 = max * max;
        double always2 = s.entityAlwaysVisible * s.entityAlwaysVisible;
        Location vLoc = viewer.getEyeLocation();
        List<double[]> origins = null; // lazily built, raycasts are the expensive part

        Collection<? extends Entity> candidates = s.onlyPlayers
                ? world.getPlayers()
                : viewer.getNearbyEntities(max, max, max);

        List<double[]> hiddenPos = new ArrayList<>();
        for (Entity target : candidates) {
            if (target == viewer || !target.isValid()) continue;
            if (!s.bukkitEntityTypes.contains(target.getType())) continue;
            int id = target.getEntityId();
            boolean hidden = st.hiddenEntities.contains(id);
            boolean revealed = st.revealedEntities.contains(id);
            if (!hidden && !revealed) continue; // client has never been sent this entity

            Location tLoc = target.getLocation();
            double dx = tLoc.getX() - vLoc.getX(), dy = tLoc.getY() + target.getHeight() / 2 - vLoc.getY(), dz = tLoc.getZ() - vLoc.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) {
                if (hidden) hiddenPos.add(new double[]{tLoc.getX(), tLoc.getY(), tLoc.getZ()});
                continue;
            }

            boolean visible;
            if (d2 <= always2 || isExempt(viewer, target) || (s.showGlowing && target.isGlowing())) {
                visible = true;
            } else {
                if (origins == null) origins = ray.cameraOrigins(viewer, s.thirdPerson, s.thirdPersonDistance);
                visible = ray.canSeeBox(world, origins, predictBox(target, s));
            }

            if (visible) {
                st.missStreak.remove(id);
                if (hidden) reveal(viewer, st, target);
            } else if (revealed) {
                int misses = st.missStreak.merge(id, 1, Integer::sum);
                if (misses >= s.hideAfterChecks) {
                    st.missStreak.remove(id);
                    hide(viewer, st, id);
                    hiddenPos.add(new double[]{tLoc.getX(), tLoc.getY(), tLoc.getZ()});
                }
            } else {
                hiddenPos.add(new double[]{tLoc.getX(), tLoc.getY(), tLoc.getZ()});
            }
        }

        st.missStreak.keySet().removeIf(id -> !st.revealedEntities.contains(id));
        double[] flat = new double[hiddenPos.size() * 3];
        for (int i = 0; i < hiddenPos.size(); i++) {
            double[] p = hiddenPos.get(i);
            flat[i * 3] = p[0];
            flat[i * 3 + 1] = p[1] + 0.9;
            flat[i * 3 + 2] = p[2];
        }
        st.hiddenPositions = flat;
    }

    private boolean isExempt(Player viewer, Entity target) {
        Entity vv = viewer.getVehicle();
        if (vv != null && (vv.equals(target) || vv.equals(target.getVehicle()))) return true;
        if (viewer.equals(target.getVehicle())) return true;
        if (viewer.getSpectatorTarget() != null && viewer.getSpectatorTarget().equals(target)) return true;
        return false;
    }

    /** Target box, grown for latency and stretched along its movement direction. */
    private BoundingBox predictBox(Entity target, Settings s) {
        BoundingBox box = target.getBoundingBox().expand(s.boxExpand);
        double mx, my, mz;
        double[] prev = lastPos.get(target.getUniqueId());
        if (target instanceof Player && prev != null) {
            Location l = target.getLocation();
            mx = l.getX() - prev[0];
            my = l.getY() - prev[1];
            mz = l.getZ() - prev[2];
        } else {
            var v = target.getVelocity();
            mx = v.getX();
            my = v.getY();
            mz = v.getZ();
        }
        double t = s.lookaheadTicks;
        // ignore teleports / huge jumps
        if (mx * mx + my * my + mz * mz < 4.0) {
            box.expandDirectional(mx * t, my * t, mz * t);
        }
        return box;
    }

    private void hide(Player viewer, ViewerState st, int id) {
        st.revealedEntities.remove(id);
        st.hiddenEntities.add(id);
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user != null) user.sendPacketSilently(new WrapperPlayServerDestroyEntities(id));
    }

    /**
     * The client no longer has the entity, so it needs a full respawn (spawn, metadata,
     * equipment, attributes, passengers...). The cleanest way to get the server to send
     * exactly that is a Bukkit hide+show in the same call. The listener lets those packets
     * through via {@link ViewerState#pendingReveal} and keeps the tab-list entry.
     */
    private void reveal(Player viewer, ViewerState st, Entity target) {
        int id = target.getEntityId();
        long now = System.nanoTime();
        st.pendingReveal.put(id, now);
        st.hiddenEntities.remove(id);
        st.revealedEntities.add(id);
        if (target instanceof Player p) st.keepTabEntry.put(p.getUniqueId(), now);
        viewer.hideEntity(plugin, target);
        viewer.showEntity(plugin, target);
    }

    // =====================================================================
    // Storage blocks
    // =====================================================================

    private void checkBlocks(Player viewer, ViewerState st, Settings s) {
        if (st.blocks.isEmpty()) return;
        World world = viewer.getWorld();
        User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
        if (user == null) return;

        double max2 = s.blockMaxDistance * s.blockMaxDistance;
        double always2 = s.blockAlwaysVisible * s.blockAlwaysVisible;
        List<double[]> origins = null;
        int vcx = ((int) Math.floor(st.x)) >> 4, vcz = ((int) Math.floor(st.z)) >> 4;
        int chunkRange = (int) Math.ceil(s.blockMaxDistance / 16.0) + 1;

        for (Map.Entry<Long, Map<Long, Boolean>> chunk : st.blocks.entrySet()) {
            long ck = chunk.getKey();
            boolean chunkInRange = Math.abs(BlockKey.chunkX(ck) - vcx) <= chunkRange
                    && Math.abs(BlockKey.chunkZ(ck) - vcz) <= chunkRange;
            Map<Long, Boolean> blocks = chunk.getValue();
            for (Map.Entry<Long, Boolean> e : blocks.entrySet()) {
                long key = e.getKey();
                boolean revealed = e.getValue();
                int x = BlockKey.x(key), y = BlockKey.y(key), z = BlockKey.z(key);

                if (st.bypass) {
                    if (!revealed) revealBlock(viewer, user, st, world, blocks, key, x, y, z, s);
                    continue;
                }
                if (!chunkInRange) {
                    if (revealed && s.rehideBlocks) hideBlock(user, blocks, key, x, y, z, world, s);
                    continue;
                }

                double d2 = st.distanceSq(x + 0.5, y + 0.5, z + 0.5);
                boolean visible;
                if (d2 <= always2) {
                    visible = true;
                } else if (d2 > max2) {
                    visible = false;
                } else {
                    if (origins == null) origins = ray.cameraOrigins(viewer, s.thirdPerson, s.thirdPersonDistance);
                    visible = ray.canSeeBlock(world, origins, x, y, z);
                }

                if (visible && !revealed) {
                    revealBlock(viewer, user, st, world, blocks, key, x, y, z, s);
                } else if (!visible && revealed && s.rehideBlocks) {
                    hideBlock(user, blocks, key, x, y, z, world, s);
                }
            }
        }
    }

    private void revealBlock(Player viewer, User user, ViewerState st, World world, Map<Long, Boolean> blocks,
                             long key, int x, int y, int z, Settings s) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        Block block = world.getBlockAt(x, y, z);
        int realId = SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId();
        if (!s.hiddenMaterials.contains(block.getType())) {
            // block changed without us noticing: resync the client and forget it
            blocks.remove(key);
        } else {
            blocks.put(key, Boolean.TRUE);
        }
        user.sendPacketSilently(new WrapperPlayServerBlockChange(new Vector3i(x, y, z), realId));
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            // passes the listener because the block is now marked revealed
            viewer.sendBlockUpdate(block.getLocation(), tile);
        }
    }

    private void hideBlock(User user, Map<Long, Boolean> blocks, long key, int x, int y, int z, World world, Settings s) {
        blocks.put(key, Boolean.FALSE);
        user.sendPacketSilently(new WrapperPlayServerBlockChange(new Vector3i(x, y, z), s.replacementFor(world.getEnvironment(), y)));
    }
}
