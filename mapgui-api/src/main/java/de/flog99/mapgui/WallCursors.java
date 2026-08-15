package de.flog99.mapgui;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCursor;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is pointing where, and the markers that show it.
 *
 * <p>Markers rather than pixels, which is the whole reason a shared wall works: the picture goes to everyone
 * identically and only the icon differs, so a pointer moving costs a few bytes instead of a frame.
 */
final class WallCursors {

    /** How far a viewer can be and still point at the wall. They are already in range to see it. */
    private static final int REACH = 64;

    /** Stop looking for something in the way just short of the map, since blocks sit on whole numbers. */
    private static final double SKIN = 0.05;

    private final WallLayout layout;
    private final WallTiles tiles;
    private final boolean showOthers;
    private final int margin;

    /**
     * Read from the network thread, which is why it is concurrent: whether a click belongs to this wall has
     * to be answered before the packet is passed on. A tick out of date is fine there.
     */
    private final Map<UUID, WallLayout.Aim> aiming = new ConcurrentHashMap<>();

    /**
     * Which tiles each viewer currently has markers on, so they can be taken off again.
     *
     * <p>A set rather than one tile, because a screen's own markers can be anywhere - a minimap may have
     * one per player, spread across the whole wall.
     */
    private final Map<UUID, Set<Integer>> marked = new HashMap<>();
    /** Exact marker lists last sent per viewer and tile; immutable snapshots make equality stable. */
    private final Map<UUID, Map<Integer, List<Marker>>> sentMarkers = new HashMap<>();

    /** This tick's measurements, held between {@link #measure} and {@link #accept}. Main thread only. */
    private final Map<UUID, Crossing> candidates = new HashMap<>();

    WallCursors(WallLayout layout, WallTiles tiles, boolean showOthers, int margin) {
        this.layout = layout;
        this.tiles = tiles;
        this.showOthers = showOthers;
        this.margin = margin;
    }

    @Nullable
    WallLayout.Aim aimOf(Player player) {
        return aiming.get(player.getUniqueId());
    }

    boolean isAiming(Player player) {
        return aiming.containsKey(player.getUniqueId());
    }

    /**
     * Where this player's sight crosses the wall and how far away that is, deciding nothing: the answer only
     * holds if no other wall is crossed first, which a wall cannot know.
     *
     * <p>Returns the distance in blocks, or -1 for a miss. Always followed by {@link #accept}.
     */
    double measure(Player player) {
        Crossing crossing = crossing(player);
        if (crossing == null) {
            candidates.remove(player.getUniqueId());
            return -1;
        }

        candidates.put(player.getUniqueId(), crossing);
        return crossing.distance();
    }

    /** Takes the measured crossing, or throws it away because a nearer wall got there first. */
    void accept(Player player, boolean nearest) {
        Crossing crossing = candidates.get(player.getUniqueId());
        if (nearest && crossing != null) {
            aiming.put(player.getUniqueId(), crossing.aim());
        } else {
            aiming.remove(player.getUniqueId());
        }
    }

    /** How far along the line of sight the wall was crossed, and where. */
    private record Crossing(double distance, WallLayout.Aim aim) {
    }

    /**
     * Where this player's line of sight crosses the wall, or null if it does not.
     *
     * <p>Crossed rather than hit: the frames are not real, so there is nothing to ray trace against, and the
     * block behind cannot answer for it either - asking which block was hit gives away the margin along with
     * any wall hung over open air. So the plane is solved directly and the three things a hit used to imply
     * are said outright: in front rather than behind, pointed at it rather than away, nothing solid between.
     */
    @Nullable
    private Crossing crossing(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        double depth = layout.depthOf(eye.getX(), eye.getY(), eye.getZ());
        double approach = direction.getX() * layout.facing().getModX()
                + direction.getY() * layout.facing().getModY()
                + direction.getZ() * layout.facing().getModZ();
        if (depth <= 0 || approach >= 0) return null;

        double crossed = -depth / approach;
        if (crossed > REACH) return null;

        WallLayout.Aim aim = layout.aimedAt(
                eye.getX() + direction.getX() * crossed,
                eye.getY() + direction.getY() * crossed,
                eye.getZ() + direction.getZ() * crossed,
                margin
        );
        if (aim == null) return null;

        return visible(eye, aim);
    }

    /**
     * The crossing, measured to the pixel being pointed at rather than to the plane, or null if something
     * solid is in the way of it.
     *
     * <p>The distinction is the whole of the margin. An overshoot is pinned back onto the picture, so the
     * point the sight line crossed is <i>not</i> on the map - it is a fraction of a block off the edge,
     * inside whatever the map hangs next to. Tracing there asks whether the floor under the wall is visible,
     * which it is not, so the margin used to work from some angles and not others. Tracing to the pinned
     * pixel asks the real question: can this viewer see the part of the picture they are pointing at.
     */
    @Nullable
    private Crossing visible(Location eye, WallLayout.Aim aim) {
        Vector toPixel = new Vector(layout.pixelX(aim) - eye.getX(), layout.pixelY(aim) - eye.getY(), layout.pixelZ(aim) - eye.getZ());

        double distance = toPixel.length();
        if (distance > REACH) return null;
        if (distance > SKIN && blocked(eye, toPixel.normalize(), distance - SKIN)) return null;

        return new Crossing(distance, aim);
    }

    /**
     * Whether something solid stands between the eye and that point.
     *
     * <p>Passable blocks are ignored, which the shorter {@code rayTraceBlocks} overload does not do: it counts
     * grass, signs, carpets and torches as hits, so a flower in front of a wall would swallow the cursor.
     */
    private static boolean blocked(Location eye, Vector direction, double distance) {
        return eye.getWorld().rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true) != null;
    }

    /**
     * Pushes this viewer's pointer and whatever their screen wanted drawn, tile by tile.
     *
     * <p>{@code owned} is the screen's own markers in surface pixels - a minimap's player dots, say - which
     * the client treats exactly like a cursor.
     */
    void send(Player player, Collection<Player> viewers, List<Marker> owned) {
        Map<Integer, List<Marker>> byTile = new HashMap<>();
        for (Marker marker : owned) place(byTile, marker);

        WallLayout.Aim aim = aimOf(player);
        if (aim != null) {
            place(byTile, new Marker(MapCursor.Type.RED_MARKER, aim.x(), aim.y(), (byte) 8, null));
            if (showOthers) {
                addOthers(byTile, player, viewers);
            }
        }

        UUID id = player.getUniqueId();
        Map<Integer, List<Marker>> before = sentMarkers.getOrDefault(id, Map.of());
        Set<Integer> was = marked.getOrDefault(id, Set.of());
        for (Map.Entry<Integer, List<Marker>> entry : byTile.entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) {
                tiles.sendMarkers(player, entry.getKey(), entry.getValue());
            }
        }
        // Tiles that had something last tick and do not now have to be told, or the old icon stays put.
        for (int tile : was) {
            if (!byTile.containsKey(tile)) {
                tiles.sendMarkers(player, tile, List.of());
            }
        }

        if (byTile.isEmpty()) {
            marked.remove(id);
            sentMarkers.remove(id);
        } else {
            marked.put(id, Set.copyOf(byTile.keySet()));
            sentMarkers.put(id, immutableMarkers(byTile));
        }
    }

    void forget(UUID player) {
        aiming.remove(player);
        marked.remove(player);
        sentMarkers.remove(player);
        candidates.remove(player);
    }

    void clear() {
        aiming.clear();
        marked.clear();
        sentMarkers.clear();
        candidates.clear();
    }

    private void addOthers(Map<Integer, List<Marker>> byTile, Player player, Collection<Player> viewers) {
        for (Player other : viewers) {
            if (other.equals(player)) continue;

            WallLayout.Aim theirs = aimOf(other);
            if (theirs == null) continue;

            place(byTile, new Marker(MapCursor.Type.BLUE_MARKER, theirs.x(), theirs.y(), (byte) 8, other.getName()));
        }
    }

    /** Markers live inside one map, so a surface pixel loses its tile's offset on the way in. */
    private void place(Map<Integer, List<Marker>> byTile, Marker marker) {
        int tile = layout.tileOf(marker.x(), marker.y());
        byTile.computeIfAbsent(tile, id -> new ArrayList<>(2)).add(new Marker(marker.type(),
                marker.x() - layout.tileOriginX(marker.x()),
                marker.y() - layout.tileOriginY(marker.y()),
                marker.rotation(), marker.label())
        );
    }
    private static Map<Integer, List<Marker>> immutableMarkers(Map<Integer, List<Marker>> byTile) {
        Map<Integer, List<Marker>> copy = new HashMap<>(byTile.size());
        for (Map.Entry<Integer, List<Marker>> entry : byTile.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }
}
