package de.flog99.mapgui;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * What stands between an eye and a point on a wall.
 *
 * <p>Two questions, not one, and the difference is a pane of glass. Whether a <b>click</b> reaches the wall it
 * was aimed at is about collision: you cannot press a button through a window. Whether a wall is worth
 * <b>sending frames to</b> is about what can be seen: you can watch a film through one. Sharing a single answer
 * between them would either let clicks through walls or freeze a screen behind an aquarium.
 */
final class Sightlines {

    /** Stop looking just short of the target, since blocks sit on whole numbers and a wall hangs on one. */
    private static final double SKIN = 0.05;

    /**
     * How many things a view will be traced through before it gives up and calls the wall visible.
     *
     * <p>Reached by foliage rather than by glass - a tree is a great many leaf blocks, none of which stops a
     * view - and giving up in favour of sending is the right answer there, since a wall behind leaves is
     * half visible. A dozen is far past any window.
     */
    private static final int LAYERS = 12;

    /** Enough to be inside the next block along rather than on the boundary of the one just left. */
    private static final double NUDGE = 1e-3;

    private Sightlines() {
    }

    /**
     * Whether something with collision stands between the eye and that point, which is what stops a click.
     *
     * <p>Passable blocks are ignored, which the shorter {@code rayTraceBlocks} overload does not do: it counts
     * grass, signs, carpets and torches as hits, so a flower in front of a wall would swallow the cursor aimed
     * through it.
     */
    static boolean solid(Location eye, double x, double y, double z) {
        Vector toPoint = toPoint(eye, x, y, z);
        double distance = toPoint.length();

        return distance > SKIN && trace(eye, toPoint.normalize(), distance - SKIN) != null;
    }

    /**
     * Whether the view from the eye to that point is stopped by something it cannot see through.
     *
     * <p>Glass, panes, bars, ice and barriers all have full collision and are all seen through, so none of them
     * is an answer on its own - the trace steps past whatever it can see through and carries on looking for
     * something it cannot. A window in front of a wall is not what hides it; the wall behind the window is.
     *
     * <p>{@code isOccluding} is the question actually being asked - does this stop vision - where collision is
     * the question a click asks. Asked of the block's state rather than its material, since a stair or a slab
     * occludes or not by how it is placed.
     *
     * <p>Traced rather than walked block by block for the same reason: {@code rayTraceBlocks} knows the shape
     * of what it hits, so a line passing over a slab is not stopped by it, where a test that only asked which
     * blocks the line crossed would say it was.
     */
    static boolean opaque(Location eye, double x, double y, double z) {
        Vector toPoint = toPoint(eye, x, y, z);
        double distance = toPoint.length();
        if (distance <= SKIN) return false;

        Vector direction = toPoint.normalize();
        Location from = eye.clone();
        double left = distance - SKIN;

        for (int layer = 0; layer < LAYERS && left > 0; layer++) {
            RayTraceResult hit = trace(from, direction, left);
            if (hit == null) return false;

            Block block = hit.getHitBlock();
            if (block == null || block.getBlockData().isOccluding()) return true;

            // Past the far side of what was just seen through, or the next trace starts inside it and finds it
            // all over again. Its whole cube, since the shape it was hit on is behind us either way.
            double beyond = beyond(block, from, direction);
            if (beyond <= 0) return false;

            from.add(direction.getX() * beyond, direction.getY() * beyond, direction.getZ() * beyond);
            left -= beyond;
        }
        return false;
    }

    private static Vector toPoint(Location eye, double x, double y, double z) {
        return new Vector(x - eye.getX(), y - eye.getY(), z - eye.getZ());
    }

    @Nullable
    private static RayTraceResult trace(Location from, Vector direction, double distance) {
        World world = from.getWorld();
        return world == null ? null : world.rayTraceBlocks(from, direction, distance, FluidCollisionMode.NEVER, true);
    }

    /**
     * How far along the ray it leaves that block's cube, nudged over the boundary.
     *
     * <p>The nearest of the three far faces, which is where a line leaves a box - so a ray clipping a corner
     * advances by the sliver it actually crossed rather than by a whole block, and nothing behind is skipped.
     */
    private static double beyond(Block block, Location from, Vector direction) {
        double exit = Math.min(
                face(block.getX(), from.getX(), direction.getX()),
                Math.min(
                        face(block.getY(), from.getY(), direction.getY()),
                        face(block.getZ(), from.getZ(), direction.getZ())
                )
        );
        return exit == Double.MAX_VALUE ? 0 : exit + NUDGE;
    }

    /** Where the ray crosses this block's far face on one axis, or never if it runs parallel to that pair. */
    private static double face(int block, double origin, double step) {
        if (step == 0) return Double.MAX_VALUE;

        return ((step > 0 ? block + 1 : block) - origin) / step;
    }
}
