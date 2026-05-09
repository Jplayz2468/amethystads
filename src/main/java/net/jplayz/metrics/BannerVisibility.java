package net.jplayz.metrics;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Pure geometry helpers for the metrics scan. No allocations beyond the two
 * scratch Vectors per call; ray-trace is gated behind cheap rejects.
 */
public final class BannerVisibility {

    public static final double VIEW_MAX_DISTANCE = 24.0;
    public static final double VIEW_MAX_DISTANCE_SQ = VIEW_MAX_DISTANCE * VIEW_MAX_DISTANCE;
    // half-angle thresholds expressed as cos() so we can compare via dot product.
    public static final double VIEW_COS = Math.cos(Math.toRadians(70.0));        // on-screen
    public static final double IMPRESSION_COS = Math.cos(Math.toRadians(15.0));  // facing

    private BannerVisibility() {}

    public static double distanceSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns the alignment dot in [-1,1] of player look vs. (eye -> banner). */
    public static double lookDot(Vector lookUnit, Location eye, Location banner) {
        double dx = banner.getX() - eye.getX();
        double dy = banner.getY() - eye.getY();
        double dz = banner.getZ() - eye.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return 0.0;
        double inv = 1.0 / len;
        return lookUnit.getX() * dx * inv + lookUnit.getY() * dy * inv + lookUnit.getZ() * dz * inv;
    }

    /** Frame faces the player: dot(frameNormal, eye - banner) > 0. */
    public static boolean frameFacesPlayer(Vector frameNormal, Location eye, Location banner) {
        double dx = eye.getX() - banner.getX();
        double dy = eye.getY() - banner.getY();
        double dz = eye.getZ() - banner.getZ();
        return frameNormal.getX() * dx + frameNormal.getY() * dy + frameNormal.getZ() * dz > 0.0;
    }

    /** Block-only LOS check from eye to banner center, sparing the last ~0.6 blocks. */
    public static boolean clearLineOfSight(Player p, Location banner, double dist) {
        if (dist <= 0.7) return true;
        Location eye = p.getEyeLocation();
        Vector dir = new Vector(
                banner.getX() - eye.getX(),
                banner.getY() - eye.getY(),
                banner.getZ() - eye.getZ()).normalize();
        World w = p.getWorld();
        double rayDist = dist - 0.6;
        try {
            RayTraceResult hit = w.rayTraceBlocks(eye, dir, rayDist);
            return hit == null;
        } catch (Throwable ignored) {
            return true; // never block metrics on a ray-trace error
        }
    }

    /** Convert a BlockFace to its outward normal. */
    public static Vector normalOf(BlockFace face) {
        return new Vector(face.getModX(), face.getModY(), face.getModZ());
    }
}
