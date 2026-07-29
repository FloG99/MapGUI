package de.flog99.mapgui;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The maps a wall is made of, and how pixels get to them.
 *
 * <p>Owns the synthetic ids and the frames holding them, since an id is only worth having if something is
 * showing it. Everything above works in surface pixels and never learns that a wall is more than one map.
 */
final class WallTiles {

    private final MapTransport transport;
    private final WallLayout layout;
    private final int[] mapIds;
    private final MapMount mount;
    private final Bandwidth cost = new Bandwidth();

    WallTiles(MapTransport transport, World world, WallLayout layout) {
        this.transport = transport;
        this.layout = layout;
        this.mapIds = new int[layout.count()];

        List<FramedMap> maps = new ArrayList<>(mapIds.length);
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                int mapId = MapIds.next();
                mapIds[row * layout.cols() + col] = mapId;
                maps.add(new FramedMap(mapId, layout.blockX(col, row), layout.blockY(col, row), layout.blockZ(col, row), layout.facing()));
            }
        }
        this.mount = transport.framedMaps(world, maps);
    }

    void show(Player player) {
        mount.show(player);
    }

    void hide(Player player) {
        mount.hide(player);
    }

    Bandwidth cost() {
        return cost;
    }

    void sendAll(Player player, MapSurface surface) {
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                send(player, surface, col, row, layout.surfaceX(col), layout.surfaceY(row), WallLayout.TILE, WallLayout.TILE);
            }
        }
    }

    /** Only the tiles the dirty rectangle actually touches, and only the part of each that it touches. */
    void sendChanged(Player player, MapSurface surface) {
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                int left = Math.max(surface.dirtyMinX(), layout.surfaceX(col));
                int top = Math.max(surface.dirtyMinY(), layout.surfaceY(row));
                int right = Math.min(surface.dirtyMaxX(), layout.surfaceX(col) + WallLayout.TILE - 1);
                int bottom = Math.min(surface.dirtyMaxY(), layout.surfaceY(row) + WallLayout.TILE - 1);
                if (left > right || top > bottom) continue;

                send(player, surface, col, row, left, top, right - left + 1, bottom - top + 1);
            }
        }
    }

    /** Markers on one tile, with no pixels - which is all a moving cursor ever needs to cost. */
    void sendMarkers(Player player, int tile, List<Marker> markers) {
        transport.sendMarkers(player, mapIds[tile], markers);
    }

    /** Surface coordinates in, map-local coordinates out - each map thinks it is the only one. */
    private void send(Player player, MapSurface surface,
                      int col, int row, int x, int y, int width, int height) {
        cost.add((long) width * height);
        transport.sendMap(player, mapIds[row * layout.cols() + col],
                x - layout.surfaceX(col), y - layout.surfaceY(row), width, height,
                surface.region(x, y, width, height)
        );
    }
}
