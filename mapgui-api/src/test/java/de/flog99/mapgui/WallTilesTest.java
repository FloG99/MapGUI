package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What actually goes on the wire for a wall.
 *
 * <p>The surface tests say which pixels changed; these say what a client is told about them, which is the part
 * the bandwidth figures in the docs are about.
 */
class WallTilesTest {

    private static final byte INK = 42;

    private static final WallLayout WALL = WallLayout.anchoredAt(0, 64, 0, BlockFace.NORTH).resized(3, 3);

    private static final Player VIEWER = FakePlayer.named("viewer");

    private static MapSurface surface() {
        return new MapSurface(WALL.pixelWidth(), WALL.pixelHeight());
    }

    @Test
    void afreshViewerIsSentEveryMap() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        tiles.sendAll(VIEWER, surface(), new TileRegions());

        assertEquals(9, transport.updates(), "one per map");
        assertEquals(9 * 128 * 128, transport.pixelsSent());
    }

    /** The whole point of tracking changes per map rather than per wall. */
    @Test
    void oppositeCornersSendTwoMapsRatherThanNine() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        MapSurface surface = surface();
        surface.set(1, 1, INK);
        surface.set(WALL.pixelWidth() - 2, WALL.pixelHeight() - 2, INK);

        tiles.sendChanged(VIEWER, surface, new TileRegions());

        assertEquals(2, transport.updates(), "the seven maps between them did not change");
        assertEquals(2, transport.pixelsSent(), "and a pixel each is all that goes");
    }

    @Test
    void aMapIsToldAboutItsOwnCornerNotTheWalls() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        MapSurface surface = surface();
        surface.set(300, 40, INK);

        tiles.sendChanged(VIEWER, surface, new TileRegions());

        FakeTransport.Sent sent = transport.sent().getFirst();
        assertEquals(44, sent.x(), "300 is 44 into the third map across");
        assertEquals(40, sent.y());
        assertEquals(1, sent.width());
    }

    @Test
    void nothingChangedSendsNothing() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        tiles.sendChanged(VIEWER, surface(), new TileRegions());

        assertEquals(0, transport.updates());
    }

    @Test
    void oneFrameIsOneBundleHoweverManyMapsItTouches() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        MapSurface surface = surface();
        surface.markAllDirty();

        transport.bundled(VIEWER, () -> tiles.sendChanged(VIEWER, surface, new TileRegions()));

        assertEquals(9, transport.updates());
        assertEquals(1, transport.bundleCount(), "nine maps, one frame, one bundle - or the wall tears");
    }

    @Test
    void separateFramesAreSeparateBundles() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        MapSurface surface = surface();
        surface.markAllDirty();

        transport.bundled(VIEWER, () -> tiles.sendChanged(VIEWER, surface, new TileRegions()));
        transport.bundled(VIEWER, () -> tiles.sendChanged(VIEWER, surface, new TileRegions()));

        assertEquals(2, transport.bundleCount());
    }

    // ---- prerendered playback ----

    private static WallContent bar() {
        return (painter, bounds, millis) -> painter.fill(
                new Rect(0, 0, (int) Math.max(1, millis / 10), 20), Color.RED
        );
    }

    @Test
    void aPrerenderedLoopSendsEveryFrameOnceAndThenNoPixelsAtAll() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);
        WallLoop loop = WallLoop.paint(WALL, bar(), 4, 1000);

        loop.start(VIEWER, tiles, 0, new TileRegions());

        assertEquals(4 * 9, transport.updates(), "four copies of a nine map wall");
        assertEquals(1, transport.pointedAt().size(), "and pointed at the one that is due");

        transport.clear();

        // A quarter of the way round the loop is the next step, so it has to move on...
        loop.tick(VIEWER, tiles, 250);

        assertEquals(0, transport.updates(), "playback is a nudge, not a frame");
        assertEquals(1, transport.pointedAt().size());
    }

    @Test
    void aStepThatHasNotChangedSendsNothingAtAll() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);
        WallLoop loop = WallLoop.paint(WALL, bar(), 4, 1000);

        loop.start(VIEWER, tiles, 0, new TileRegions());
        transport.clear();

        loop.tick(VIEWER, tiles, 10);
        loop.tick(VIEWER, tiles, 100);

        assertEquals(0, transport.pointedAt().size(), "still the same step, so there is nothing to say");
    }

    @Test
    void everyStepPointsAtItsOwnSetOfMapIds() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);
        WallLoop loop = WallLoop.paint(WALL, bar(), 4, 1000);

        loop.start(VIEWER, tiles, 0, new TileRegions());
        loop.tick(VIEWER, tiles, 250);
        loop.tick(VIEWER, tiles, 500);

        List<int[]> pointed = transport.pointedAt();
        assertEquals(3, pointed.size());
        assertNotEquals(pointed.get(0)[0], pointed.get(1)[0], "a different step is a different id");
        assertNotEquals(pointed.get(1)[0], pointed.get(2)[0]);
        assertEquals(9, pointed.getFirst().length, "one id per map");
    }

    /** Twenty people watching one wall should cost one copy of it, not twenty. */
    @Test
    void everyViewerOfOneWallIsHandedTheSameBytes() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        MapSurface surface = surface();
        surface.markAllDirty();

        TileRegions frame = new TileRegions();
        tiles.sendChanged(FakePlayer.named("one"), surface, frame);
        tiles.sendChanged(FakePlayer.named("two"), surface, frame);

        assertEquals(18, transport.updates(), "both were told about all nine maps");
        assertEquals(9, transport.distinctArrays(), "but the pixels were only cut out of the surface once");
    }

    @Test
    void adifferentFrameIsCutOutAgain() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);

        MapSurface surface = surface();
        surface.markAllDirty();

        tiles.sendChanged(VIEWER, surface, new TileRegions());
        tiles.sendChanged(VIEWER, surface, new TileRegions());

        assertEquals(18, transport.distinctArrays(), "a cache lasts one frame, because the pixels change");
    }

    @Test
    void aTransportThatCannotRepointSaysSoBeforeAnythingIsPainted() {
        WallTiles tiles = new WallTiles(new FakeTransport().cannotRepoint(), null, WALL);

        assertFalse(tiles.canShowLayers(), "so the wall streams instead of prerendering");
    }

    @Test
    void stationaryMarkersAreSuppressedAfterTheFirstTransportSend() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);
        WallCursors cursors = new WallCursors(WALL, tiles, false, 0);

        List<Marker> markers = List.of(new Marker(null, 1, 1, (byte) 8, null));
        cursors.send(FakePlayer.named("stationary"), List.of(), markers);
        cursors.send(FakePlayer.named("stationary"), List.of(), markers);

        assertEquals(1, transport.markerSends(), "stationary markers should be sent once");
    }
    @Test
    void markerClearIsSentWhenMarkersDisappear() {
        FakeTransport transport = new FakeTransport();
        WallTiles tiles = new WallTiles(transport, null, WALL);
        WallCursors cursors = new WallCursors(WALL, tiles, false, 0);
        var viewer = FakePlayer.named("clear");

        cursors.send(viewer, List.of(), List.of(new Marker(null, 1, 1, (byte) 8, null)));
        cursors.send(viewer, List.of(), List.of());

        assertEquals(2, transport.markerSends(), "the empty update clears the stale marker");
    }
}
