package dev.antiesp.util;

/** Packs block / chunk coordinates into longs (same layout as vanilla BlockPos.asLong). */
public final class BlockKey {

    private BlockKey() {}

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 38);
    }

    public static int y(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int z(long key) {
        return (int) (key << 26 >> 38);
    }

    public static long chunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long chunkKey) {
        return (int) (chunkKey >> 32);
    }

    public static int chunkZ(long chunkKey) {
        return (int) chunkKey;
    }
}
