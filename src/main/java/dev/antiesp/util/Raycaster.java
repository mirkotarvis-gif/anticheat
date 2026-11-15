package dev.antiesp.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Voxel line-of-sight tests against occluding blocks (main thread only).
 * Uses a 3D DDA walk so every block a ray passes through is checked exactly once.
 */
public final class Raycaster {

    private final boolean[] occluding;

    // per-ray chunk cache (main thread only, so plain fields are fine)
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private boolean lastChunkLoaded;
    private World lastWorld;

    @SuppressWarnings("deprecation")
    public Raycaster() {
        Material[] materials = Material.values();
        occluding = new boolean[materials.length];
        for (Material m : materials) {
            if (m.isLegacy() || !m.isBlock()) continue;
            occluding[m.ordinal()] = m.isOccluding();
        }
    }

    public boolean isOccluding(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return false;
        int cx = x >> 4, cz = z >> 4;
        if (world != lastWorld || cx != lastChunkX || cz != lastChunkZ) {
            lastWorld = world;
            lastChunkX = cx;
            lastChunkZ = cz;
            lastChunkLoaded = world.isChunkLoaded(cx, cz);
        }
        // Never load chunks from a raycast. Unloaded = treat as see-through (fail-open).
        if (!lastChunkLoaded) return false;
        return occluding[world.getType(x, y, z).ordinal()];
    }

    /**
     * @return true if nothing occluding lies between origin and target.
     * The origin cell and the target cell are never tested.
     */
    public boolean clear(World w, double ox, double oy, double oz, double tx, double ty, double tz) {
        double dx = tx - ox, dy = ty - oy, dz = tz - oz;
        if (dx * dx + dy * dy + dz * dz < 1.0e-8) return true;

        int x = floor(ox), y = floor(oy), z = floor(oz);
        final int ex = floor(tx), ey = floor(ty), ez = floor(tz);

        final int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        final int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        final int sz = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        final double tdx = sx == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dx);
        final double tdy = sy == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dy);
        final double tdz = sz == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dz);

        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - ox) : (ox - x)) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - oy) : (oy - y)) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - oz) : (oz - z)) * tdz;

        int guard = Math.abs(ex - x) + Math.abs(ey - y) + Math.abs(ez - z) + 2;
        while (guard-- > 0) {
            if (x == ex && y == ey && z == ez) return true;
            if (tmx < tmy) {
                if (tmx < tmz) { x += sx; tmx += tdx; } else { z += sz; tmz += tdz; }
            } else {
                if (tmy < tmz) { y += sy; tmy += tdy; } else { z += sz; tmz += tdz; }
            }
            if (x == ex && y == ey && z == ez) return true;
            if (isOccluding(w, x, y, z)) return false;
        }
        return true;
    }

    /**
     * Camera positions the client could legitimately render from:
     * first person eye, plus clipped third-person back / front cameras (F5).
     */
    public List<double[]> cameraOrigins(Player viewer, boolean thirdPerson, double thirdPersonDistance) {
        List<double[]> list = new ArrayList<>(3);
        Location eye = viewer.getEyeLocation();
        list.add(new double[]{eye.getX(), eye.getY(), eye.getZ()});
        if (thirdPerson && thirdPersonDistance > 0) {
            Vector dir = eye.getDirection();
            list.add(clipCamera(viewer.getWorld(), eye, dir, -thirdPersonDistance));
            list.add(clipCamera(viewer.getWorld(), eye, dir, thirdPersonDistance));
        }
        return list;
    }

    private double[] clipCamera(World w, Location eye, Vector dir, double distance) {
        double step = Math.signum(distance) * 0.25;
        double travelled = 0;
        double px = eye.getX(), py = eye.getY(), pz = eye.getZ();
        while (Math.abs(travelled) < Math.abs(distance)) {
            double nx = eye.getX() + dir.getX() * (travelled + step);
            double ny = eye.getY() + dir.getY() * (travelled + step);
            double nz = eye.getZ() + dir.getZ() * (travelled + step);
            if (isOccluding(w, floor(nx), floor(ny), floor(nz))) break;
            px = nx; py = ny; pz = nz;
            travelled += step;
        }
        return new double[]{px, py, pz};
    }

    /** Can any origin see any sample point (8 corners + centre) of the box? */
    public boolean canSeeBox(World w, List<double[]> origins, BoundingBox box) {
        double[] xs = {box.getMinX(), box.getMaxX()};
        double[] ys = {box.getMinY(), box.getMaxY()};
        double[] zs = {box.getMinZ(), box.getMaxZ()};
        double cx = box.getCenterX(), cy = box.getCenterY(), cz = box.getCenterZ();
        for (double[] o : origins) {
            if (clear(w, o[0], o[1], o[2], cx, cy, cz)) return true;
            for (double px : xs) for (double py : ys) for (double pz : zs) {
                if (clear(w, o[0], o[1], o[2], px, py, pz)) return true;
            }
        }
        return false;
    }

    /** Can any origin see the block at x,y,z? Rays aim slightly inside the block so they end in its cell. */
    public boolean canSeeBlock(World w, List<double[]> origins, int x, int y, int z) {
        // If every face is covered by an occluding block, nothing can see it. Cheap early-out.
        if (isOccluding(w, x + 1, y, z) && isOccluding(w, x - 1, y, z)
                && isOccluding(w, x, y + 1, z) && isOccluding(w, x, y - 1, z)
                && isOccluding(w, x, y, z + 1) && isOccluding(w, x, y, z - 1)) {
            return false;
        }
        final double in = 0.08;
        double[] xs = {x + in, x + 1 - in};
        double[] ys = {y + in, y + 1 - in};
        double[] zs = {z + in, z + 1 - in};
        for (double[] o : origins) {
            if (clear(w, o[0], o[1], o[2], x + 0.5, y + 0.5, z + 0.5)) return true;
            for (double px : xs) for (double py : ys) for (double pz : zs) {
                if (clear(w, o[0], o[1], o[2], px, py, pz)) return true;
            }
        }
        return false;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
