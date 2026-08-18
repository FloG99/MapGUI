package de.flog99.mapgui;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * A world whose player list can be swapped between ticks, which is all a wall asks one for.
 *
 * <p>One instance per test, so the wall and the players in it agree on identity - {@code equals} is what
 * {@code Location.distanceSquared} checks before it will measure anything.
 */
final class FakeWorld {

    /**
     * What a ray runs into: how far along it that happens, and whether it stops a view as well as a click.
     *
     * <p>{@code occluding} false is glass, a pane, bars, ice or a barrier - solid to a click and transparent to
     * a viewer. A view traced through one carries on looking, so a test can lay out a window and a wall behind
     * it and check that the wall is what counts.
     */
    record Hit(double along, boolean occluding) {
    }

    /**
     * The first thing a ray from {@code from} along {@code direction} meets within {@code distance}, or null for
     * a clear line. Null by default, which is an empty world - nothing is ever in the way.
     */
    @FunctionalInterface
    interface Blocking {
        @Nullable
        Hit hit(Location from, Vector direction, double distance);
    }

    List<Player> players = List.of();

    @Nullable
    Blocking blocking;

    private final World world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[]{World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getPlayers" -> players;
                case "key" -> net.kyori.adventure.key.Key.key("fake", "world");
                case "isLoaded" -> true;
                case "rayTraceBlocks" -> trace(args);
                case "equals" -> args.length == 1 && args[0] == proxy;
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "FakeWorld";
                default -> null;
            }
    );

    /**
     * The overload MapGUI uses is {@code (Location, Vector, double, FluidCollisionMode, boolean)}.
     *
     * <p>The hit has to carry a block, and that block has to know where it is: whether the view can pass is read
     * off its state, and stepping past one that it can is worked out from its coordinates. A result without a
     * block reads as clear and would quietly defeat any test of occlusion.
     */
    @Nullable
    private RayTraceResult trace(Object[] args) {
        if (blocking == null) return null;

        Location from = (Location) args[0];
        Vector direction = (Vector) args[1];
        double distance = (double) args[2];

        Hit hit = blocking.hit(from, direction, distance);
        if (hit == null) return null;

        Vector at = from.toVector().add(direction.clone().multiply(hit.along()));
        return new RayTraceResult(at, block(at, hit.occluding()), BlockFace.SELF);
    }

    /**
     * A block that answers for its own state and its own position, which is all that is ever asked of one here.
     *
     * <p>Asking a {@code Material} instead cannot work off a server: {@code Material#isOccluding} goes through
     * the block-type registry, and there is no registry without one. {@code BlockData} answers for itself.
     */
    private static Block block(Vector at, boolean occluding) {
        BlockData data = (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOccluding" -> occluding;
                    case "equals" -> args.length == 1 && args[0] == proxy;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeBlockData(occluding=" + occluding + ")";
                    default -> null;
                }
        );

        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBlockData" -> data;
                    case "getX" -> (int) Math.floor(at.getX());
                    case "getY" -> (int) Math.floor(at.getY());
                    case "getZ" -> (int) Math.floor(at.getZ());
                    case "equals" -> args.length == 1 && args[0] == proxy;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeBlock" + at;
                    default -> null;
                }
        );
    }

    World world() {
        return world;
    }
}
