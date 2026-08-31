package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
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

    /**
     * A set of ids per layer, the first of which is what an ordinary wall uses.
     *
     * <p>Further ones exist for prerendered frames: the client keeps a picture per id it has been sent, so a
     * second set is a second copy of the wall sitting in the client waiting to be pointed at.
     */
    private final List<int[]> layers = new ArrayList<>();
    private final MapMount mount;
    private final Bandwidth cost = new Bandwidth();

    WallTiles(MapTransport transport, World world, WallLayout layout, FrameStyle style) {
        this.transport = transport;
        this.layout = layout;

        int[] mapIds = ids(0);
        List<FramedMap> maps = new ArrayList<>(mapIds.length);
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                int mapId = mapIds[row * layout.cols() + col];
                maps.add(new FramedMap(mapId, layout.blockX(col, row), layout.blockY(col, row), layout.blockZ(col, row), layout.facing()));
            }
        }
        this.mount = transport.framedMaps(world, maps, style);
    }

    /** Ids for one layer, minted the first time that layer is asked for. */
    private int[] ids(int layer) {
        while (layers.size() <= layer) {
            int[] ids = new int[layout.count()];
            for (int i = 0; i < ids.length; i++) ids[i] = MapIds.next();
            layers.add(ids);
        }
        return layers.get(layer);
    }

    /** Whether these maps can be repointed at all, which is what makes a prerendered loop possible. */
    boolean canShowLayers() {
        return mount.repoints();
    }

    /** Points this viewer's frames at a layer they have already been sent, which shows it immediately. */
    void showLayer(Player player, int layer) {
        mount.showMaps(player, ids(layer));
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

    void sendAll(Player player, MapSurface surface, TileRegions frame) {
        sendAll(player, surface, 0, frame);
    }

    /** Every pixel, under one layer's ids. */
    void sendAll(Player player, MapSurface surface, int layer, TileRegions frame) {
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                send(player, surface, col, row,
                        new Rect(layout.surfaceX(col), layout.surfaceY(row), WallLayout.TILE, WallLayout.TILE), layer, frame
                );
            }
        }
    }

    /**
     * Only the maps that changed, and only the parts of each that did.
     *
     * <p>Parts, plural: a map whose changes are in two places goes as two updates rather than as the box
     * around both, whenever that is the cheaper of the two.
     */
    void sendChanged(Player player, MapSurface surface, TileRegions frame) {
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                for (Rect changed : surface.dirtyRegions(col, band(row))) {
                    send(player, surface, col, row, changed, 0, frame);
                }
            }
        }
    }

    /**
     * Which band of the surface a wall row is.
     *
     * <p>Row 0 is the bottom of the wall as a viewer sees it, and the surface starts at the top, so the two
     * count in opposite directions.
     */
    private int band(int row) {
        return layout.surfaceY(row) / WallLayout.TILE;
    }

    /** Markers on one tile, with no pixels - which is all a moving cursor ever needs to cost. */
    void sendMarkers(Player player, int tile, List<Marker> markers) {
        transport.sendMarkers(player, ids(0)[tile], markers);
    }

    /** Surface coordinates in, map-local coordinates out - each map thinks it is the only one. */
    private void send(Player player, MapSurface surface, int col, int row, Rect area, int layer, TileRegions frame) {
        int tile = row * layout.cols() + col;
        cost.add((long) area.width() * area.height());

        transport.sendMap(player, ids(layer)[tile],
                area.x() - layout.surfaceX(col), area.y() - layout.surfaceY(row), area.width(), area.height(),
                frame.of(surface, tile, area)
        );
    }
}
