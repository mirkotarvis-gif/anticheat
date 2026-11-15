package dev.antiesp.core;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything AntiESP knows about what one player's client can see.
 * Fields shared with netty threads are concurrent / volatile; the rest is main-thread only.
 */
public final class ViewerState {

    public final UUID uuid;

    // ---- entities ----
    /** Entity ids the client must not know about. Packets for these are dropped. */
    public final Set<Integer> hiddenEntities = ConcurrentHashMap.newKeySet();
    /** Entity ids the client currently has spawned with our approval. */
    public final Set<Integer> revealedEntities = ConcurrentHashMap.newKeySet();
    /** Ids we are re-spawning right now (destroy+spawn toggle); value = nanoTime. */
    public final Map<Integer, Long> pendingReveal = new ConcurrentHashMap<>();
    /** Tab-list entries to keep when our respawn toggle sends a PlayerInfoRemove. */
    public final Map<UUID, Long> keepTabEntry = new ConcurrentHashMap<>();
    /** Entity id -> type, learned from spawn packets. */
    public final Map<Integer, EntityType> entityTypes = new ConcurrentHashMap<>();
    /** Main thread: consecutive failed visibility checks per entity id. */
    public final Map<Integer, Integer> missStreak = new HashMap<>();
    /** Flattened x,y,z of hidden entities, used to mask sounds. */
    public volatile double[] hiddenPositions = new double[0];

    // ---- blocks ----
    /** chunkKey -> (blockKey -> revealed?). Only storage blocks the client was sent are tracked. */
    public final Map<Long, Map<Long, Boolean>> blocks = new ConcurrentHashMap<>();

    // ---- cached player info for netty threads ----
    public volatile boolean bypass;
    public volatile int selfEntityId = -1;
    public volatile int vehicleId = -1;
    public volatile double x, y, z;

    public ViewerState(UUID uuid) {
        this.uuid = uuid;
    }

    /** Client changed world / respawned: it forgot every entity and chunk. */
    public void clearWorldData() {
        hiddenEntities.clear();
        revealedEntities.clear();
        pendingReveal.clear();
        entityTypes.clear();
        blocks.clear();
        hiddenPositions = new double[0];
    }

    public Map<Long, Boolean> chunkBlocks(long chunkKey) {
        return blocks.computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
    }

    public Boolean blockState(long chunkKey, long blockKey) {
        Map<Long, Boolean> m = blocks.get(chunkKey);
        return m == null ? null : m.get(blockKey);
    }

    public void untrackBlock(long chunkKey, long blockKey) {
        Map<Long, Boolean> m = blocks.get(chunkKey);
        if (m != null) m.remove(blockKey);
    }

    public double distanceSq(double px, double py, double pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
