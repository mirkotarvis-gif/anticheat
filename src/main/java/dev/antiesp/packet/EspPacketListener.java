package dev.antiesp.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import dev.antiesp.AntiESP;
import dev.antiesp.core.Settings;
import dev.antiesp.core.ViewerState;
import dev.antiesp.util.BlockKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Outgoing packet filter. Runs on netty threads.
 * <ul>
 *   <li>Drops every packet about an entity the viewer must not see (spawn, move, metadata, sounds...).</li>
 *   <li>Replaces hidden storage blocks in chunk / block-change packets and strips their block-entity data.</li>
 *   <li>Spoofs health / absorption in entity metadata.</li>
 *   <li>Masks positional sounds coming from hidden entities.</li>
 * </ul>
 */
public final class EspPacketListener implements PacketListener {

    private final AntiESP plugin;

    public EspPacketListener(AntiESP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Server server)) return;
        User user = event.getUser();
        UUID uuid = user.getUUID();
        if (uuid == null) return; // still logging in
        // PacketEvents may not have the Bukkit player attached for the very first play packets,
        // so state is keyed by UUID and nothing below requires a Player object.
        ViewerState st = plugin.state(uuid);
        Player player = event.getPlayer() instanceof Player p ? p : null;
        Settings s = plugin.settings();

        try {
            switch (server) {
                // ---------- world resets ----------
                case JOIN_GAME, RESPAWN -> st.clearWorldData();

                // ---------- entity lifecycle ----------
                case SPAWN_ENTITY -> onSpawn(event, st, s);
                case DESTROY_ENTITIES -> onDestroy(event, st);
                case PLAYER_INFO_REMOVE -> onInfoRemove(event, st);

                // ---------- entity data ----------
                case ENTITY_METADATA -> onMetadata(event, st, s, user);
                case ENTITY_RELATIVE_MOVE -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityRelativeMove(event).getEntityId());
                case ENTITY_RELATIVE_MOVE_AND_ROTATION -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId());
                case ENTITY_ROTATION -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityRotation(event).getEntityId());
                case ENTITY_HEAD_LOOK -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityHeadLook(event).getEntityId());
                case ENTITY_VELOCITY -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityVelocity(event).getEntityId());
                case ENTITY_EQUIPMENT -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityEquipment(event).getEntityId());
                case ENTITY_TELEPORT -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityTeleport(event).getEntityId());
                case ENTITY_POSITION_SYNC -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityPositionSync(event).getId());
                case ENTITY_STATUS -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityStatus(event).getEntityId());
                case ENTITY_ANIMATION -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityAnimation(event).getEntityId());
                case ENTITY_EFFECT -> dropIfHidden(event, st, () -> new WrapperPlayServerEntityEffect(event).getEntityId());
                case REMOVE_ENTITY_EFFECT -> dropIfHidden(event, st, () -> new WrapperPlayServerRemoveEntityEffect(event).getEntityId());
                case UPDATE_ATTRIBUTES -> dropIfHidden(event, st, () -> new WrapperPlayServerUpdateAttributes(event).getEntityId());
                case HURT_ANIMATION -> dropIfHidden(event, st, () -> new WrapperPlayServerHurtAnimation(event).getEntityId());
                case DAMAGE_EVENT -> dropIfHidden(event, st, () -> new WrapperPlayServerDamageEvent(event).getEntityId());
                case ENTITY_SOUND_EFFECT -> dropIfHidden(event, st, () -> new WrapperPlayServerEntitySoundEffect(event).getEntityId());
                case BLOCK_BREAK_ANIMATION -> dropIfHidden(event, st, () -> new WrapperPlayServerBlockBreakAnimation(event).getEntityId());
                case SET_PASSENGERS -> dropIfHidden(event, st, () -> new WrapperPlayServerSetPassengers(event).getEntityId());
                case ATTACH_ENTITY -> dropIfHidden(event, st, () -> new WrapperPlayServerAttachEntity(event).getAttachedId());
                case COLLECT_ITEM -> dropIfHidden(event, st, () -> new WrapperPlayServerCollectItem(event).getCollectorEntityId());
                case SOUND_EFFECT -> onSound(event, st, s);

                // ---------- storage blocks ----------
                case CHUNK_DATA -> onChunk(event, st, s, player);
                case UNLOAD_CHUNK -> {
                    WrapperPlayServerUnloadChunk w = new WrapperPlayServerUnloadChunk(event);
                    st.blocks.remove(BlockKey.chunk(w.getChunkX(), w.getChunkZ()));
                }
                case BLOCK_CHANGE -> onBlockChange(event, st, s, player);
                case MULTI_BLOCK_CHANGE -> onMultiBlockChange(event, st, s, player);
                case BLOCK_ACTION -> {
                    Vector3i p = new WrapperPlayServerBlockAction(event).getBlockPosition();
                    if (isHiddenBlock(st, p.getX(), p.getY(), p.getZ())) event.setCancelled(true);
                }
                case BLOCK_ENTITY_DATA -> {
                    Vector3i p = new WrapperPlayServerBlockEntityData(event).getPosition();
                    if (isHiddenBlock(st, p.getX(), p.getY(), p.getZ())) event.setCancelled(true);
                }
                default -> { }
            }
        } catch (Throwable t) {
            // Never kick a player because of us; just log once in a while.
            plugin.logPacketError(server, t);
        }
    }

    // =====================================================================
    // Entities
    // =====================================================================

    private void onSpawn(PacketSendEvent event, ViewerState st, Settings s) {
        WrapperPlayServerSpawnEntity w = new WrapperPlayServerSpawnEntity(event);
        int id = w.getEntityId();
        EntityType type = w.getEntityType();
        st.entityTypes.put(id, type);
        if (st.pendingReveal.remove(id) != null) {
            // our own respawn toggle - let it through
            st.hiddenEntities.remove(id);
            st.revealedEntities.add(id);
            return;
        }
        if (!s.entitiesEnabled || st.bypass || !s.packetEntityTypes.contains(type)) return;
        if (st.revealedEntities.contains(id)) return;

        // Default deny: a protected entity entering tracking range starts hidden.
        // The visibility task reveals it within a couple of ticks if it is in line of sight
        // (or close enough), so ESP never gets a free look at players behind walls.
        Vector3d pos = w.getPosition();
        if (st.distanceSq(pos.getX(), pos.getY() + 1.0, pos.getZ()) <= s.entityAlwaysVisible * s.entityAlwaysVisible) {
            st.revealedEntities.add(id);
            return;
        }
        st.hiddenEntities.add(id);
        event.setCancelled(true);
    }

    private void onDestroy(PacketSendEvent event, ViewerState st) {
        int[] ids = new WrapperPlayServerDestroyEntities(event).getEntityIds();
        for (int id : ids) {
            if (st.pendingReveal.containsKey(id)) continue; // part of our respawn toggle
            st.hiddenEntities.remove(id);
            st.revealedEntities.remove(id);
            st.entityTypes.remove(id);
        }
    }

    /** Our respawn toggle uses Bukkit hide/show which also removes the tab entry; undo that part. */
    private void onInfoRemove(PacketSendEvent event, ViewerState st) {
        if (st.keepTabEntry.isEmpty()) return;
        WrapperPlayServerPlayerInfoRemove w = new WrapperPlayServerPlayerInfoRemove(event);
        List<UUID> ids = w.getProfileIds();
        List<UUID> kept = new ArrayList<>(ids.size());
        boolean changed = false;
        for (UUID u : ids) {
            if (st.keepTabEntry.remove(u) != null) {
                changed = true;
            } else {
                kept.add(u);
            }
        }
        if (!changed) return;
        if (kept.isEmpty()) {
            event.setCancelled(true);
        } else {
            w.setProfileIds(kept);
            event.markForReEncode(true);
        }
    }

    @SuppressWarnings("unchecked")
    private void onMetadata(PacketSendEvent event, ViewerState st, Settings s, User viewer) {
        WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata(event);
        int id = w.getEntityId();
        if (st.hiddenEntities.contains(id)) {
            event.setCancelled(true);
            return;
        }
        if (!s.healthEnabled || id == viewer.getEntityId() || id == st.vehicleId) return;

        EntityType type = st.entityTypes.get(id);
        if (type == null) return;
        boolean isPlayer = EntityTypes.isTypeInstanceOf(type, EntityTypes.PLAYER);
        if (s.healthPlayersOnly ? !isPlayer : !EntityTypes.isTypeInstanceOf(type, EntityTypes.LIVINGENTITY)) return;

        boolean changed = false;
        for (EntityData<?> data : w.getEntityMetadata()) {
            if (data.getType() != EntityDataTypes.FLOAT) continue;
            int index = data.getIndex();
            EntityData<Float> f = (EntityData<Float>) data;
            if (index == 9) {
                // LivingEntity health. Keep <= 0 so death animations still play.
                Float v = f.getValue();
                if (v != null && v > 0f && v != s.healthSpoofValue) {
                    f.setValue(s.healthSpoofValue);
                    changed = true;
                }
            } else if (isPlayer && s.hideAbsorption && index >= 15) {
                // Player absorption is the only float field past the LivingEntity block.
                Float v = f.getValue();
                if (v != null && v != 0f) {
                    f.setValue(0f);
                    changed = true;
                }
            }
        }
        if (changed) event.markForReEncode(true);
    }

    /** Only decodes the packet when this viewer has hidden entities at all. */
    private void dropIfHidden(PacketSendEvent event, ViewerState st, IntSupplier entityId) {
        if (st.hiddenEntities.isEmpty()) return;
        if (st.hiddenEntities.contains(entityId.getAsInt())) event.setCancelled(true);
    }

    private void onSound(PacketSendEvent event, ViewerState st, Settings s) {
        if (!s.maskSounds) return;
        double[] hidden = st.hiddenPositions;
        if (hidden.length == 0) return;
        WrapperPlayServerSoundEffect w = new WrapperPlayServerSoundEffect(event);
        String cat = w.getSoundCategory() == null ? "" : w.getSoundCategory().name();
        if (cat.equals("WEATHER") || cat.equals("MUSIC") || cat.equals("RECORD") || cat.equals("MASTER")) return;
        // getEffectPosition() exists in every PacketEvents 2.x version; it is fixed-point (x8).
        Vector3i p = w.getEffectPosition();
        double px = p.getX() / 8.0, py = p.getY() / 8.0, pz = p.getZ() / 8.0;
        double r2 = s.soundRadius * s.soundRadius;
        for (int i = 0; i + 2 < hidden.length; i += 3) {
            double dx = px - hidden[i], dy = py - hidden[i + 1], dz = pz - hidden[i + 2];
            if (dx * dx + dy * dy + dz * dz <= r2) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // =====================================================================
    // Storage blocks
    // =====================================================================

    private boolean isHiddenBlock(ViewerState st, int x, int y, int z) {
        Boolean revealed = st.blockState(BlockKey.chunk(x >> 4, z >> 4), BlockKey.pack(x, y, z));
        return revealed != null && !revealed;
    }

    private boolean storageEnabled(ViewerState st, Settings s) {
        return s.blocksEnabled && !st.bypass && !s.hiddenStateTypes.isEmpty();
    }

    private void onChunk(PacketSendEvent event, ViewerState st, Settings s, Player player) {
        if (!storageEnabled(st, s)) return;
        WrapperPlayServerChunkData w = new WrapperPlayServerChunkData(event);
        Column column = w.getColumn();
        TileEntity[] tiles = column.getTileEntities();
        if (tiles == null || tiles.length == 0) return;

        int minY = event.getUser().getMinWorldHeight();
        World.Environment env = environment(player);
        BaseChunk[] sections = column.getChunks();
        int cx = column.getX(), cz = column.getZ();
        long chunkKey = BlockKey.chunk(cx, cz);
        Map<Long, Boolean> tracked = null;
        double always2 = s.blockAlwaysVisible * s.blockAlwaysVisible;

        List<TileEntity> keep = new ArrayList<>(tiles.length);
        boolean modified = false;
        for (TileEntity te : tiles) {
            int lx = te.getX(), y = te.getY(), lz = te.getZ();
            int sectionIndex = (y - minY) >> 4;
            if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) {
                keep.add(te);
                continue;
            }
            BaseChunk section = sections[sectionIndex];
            int stateId = section.getBlockId(lx, y & 15, lz);
            WrappedBlockState state = WrappedBlockState.getByGlobalId(stateId, false);
            if (!s.hiddenStateTypes.contains(state.getType())) {
                keep.add(te);
                continue;
            }
            int bx = (cx << 4) + lx, bz = (cz << 4) + lz;
            if (tracked == null) tracked = st.chunkBlocks(chunkKey);
            long key = BlockKey.pack(bx, y, bz);
            if (st.distanceSq(bx + 0.5, y + 0.5, bz + 0.5) <= always2) {
                tracked.put(key, Boolean.TRUE);
                keep.add(te);
                continue;
            }
            tracked.put(key, Boolean.FALSE);
            section.set(lx, y & 15, lz, s.replacementFor(env, y));
            modified = true;
        }

        if (!modified) return;
        Column replaced = new Column(cx, cz, column.isFullChunk(), sections,
                keep.toArray(new TileEntity[0]), column.getHeightmaps());
        w.setColumn(replaced);
        event.markForReEncode(true);
    }

    /** Returns the id to actually send for a block change at x,y,z, updating tracking. */
    private int filterBlock(ViewerState st, Settings s, World.Environment env, int x, int y, int z, int stateId) {
        long chunkKey = BlockKey.chunk(x >> 4, z >> 4);
        long key = BlockKey.pack(x, y, z);
        WrappedBlockState state = WrappedBlockState.getByGlobalId(stateId, false);
        if (!s.hiddenStateTypes.contains(state.getType())) {
            // real change to a non-storage block: stop tracking, send as-is
            st.untrackBlock(chunkKey, key);
            return stateId;
        }
        Boolean revealed = st.blockState(chunkKey, key);
        if (Boolean.TRUE.equals(revealed)) return stateId;
        if (st.distanceSq(x + 0.5, y + 0.5, z + 0.5) <= s.blockAlwaysVisible * s.blockAlwaysVisible) {
            st.chunkBlocks(chunkKey).put(key, Boolean.TRUE);
            return stateId;
        }
        st.chunkBlocks(chunkKey).put(key, Boolean.FALSE);
        return s.replacementFor(env, y);
    }

    private void onBlockChange(PacketSendEvent event, ViewerState st, Settings s, Player player) {
        if (!storageEnabled(st, s)) return;
        WrapperPlayServerBlockChange w = new WrapperPlayServerBlockChange(event);
        Vector3i p = w.getBlockPosition();
        int original = w.getBlockId();
        int out = filterBlock(st, s, environment(player), p.getX(), p.getY(), p.getZ(), original);
        if (out != original) {
            w.setBlockID(out);
            event.markForReEncode(true);
        }
    }

    private void onMultiBlockChange(PacketSendEvent event, ViewerState st, Settings s, Player player) {
        if (!storageEnabled(st, s)) return;
        WrapperPlayServerMultiBlockChange w = new WrapperPlayServerMultiBlockChange(event);
        World.Environment env = environment(player);
        boolean changed = false;
        for (WrapperPlayServerMultiBlockChange.EncodedBlock b : w.getBlocks()) {
            int original = b.getBlockId();
            int out = filterBlock(st, s, env, b.getX(), b.getY(), b.getZ(), original);
            if (out != original) {
                b.setBlockId(out);
                changed = true;
            }
        }
        if (changed) event.markForReEncode(true);
    }

    private static World.Environment environment(Player player) {
        if (player == null) return World.Environment.NORMAL;
        try {
            return player.getWorld().getEnvironment();
        } catch (Throwable t) {
            return World.Environment.NORMAL;
        }
    }

    /** Remove stale toggle markers (main thread calls this). */
    public static void expireMarkers(ViewerState st, long now) {
        long limit = 1_500_000_000L;
        for (Iterator<Map.Entry<Integer, Long>> it = st.pendingReveal.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > limit) it.remove();
        }
        for (Iterator<Map.Entry<UUID, Long>> it = st.keepTabEntry.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > limit) it.remove();
        }
    }
}
