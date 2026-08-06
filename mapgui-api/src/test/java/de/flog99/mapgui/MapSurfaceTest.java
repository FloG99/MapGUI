package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapSurfaceTest {

    private static final byte INK = 42;

    @Test
    void nothingIsDirtyToBeginWith() {
        MapSurface surface = new MapSurface(256, 256);

        assertFalse(surface.isDirty());
        assertNull(surface.dirtyBounds());
        assertNull(surface.dirtyTile(0, 0));
    }

    @Test
    void writingTheSameColorChangesNothing() {
        MapSurface surface = new MapSurface(128, 128);
        surface.set(4, 4, (byte) 0);

        assertFalse(surface.isDirty(), "the pixel was already that color");
    }

    /** The whole point: two far-apart changes must not drag the maps between them into the update. */
    @Test
    void oppositeCornersLeaveTheTilesBetweenThemClean() {
        MapSurface surface = new MapSurface(384, 384);

        surface.set(1, 2, INK);
        surface.set(380, 381, INK);

        assertEquals(new Rect(1, 2, 1, 1), surface.dirtyTile(0, 0));
        assertEquals(new Rect(380, 381, 1, 1), surface.dirtyTile(2, 2));

        assertNull(surface.dirtyTile(1, 1), "the middle map changed nothing and must not be sent");
        assertNull(surface.dirtyTile(2, 0));
        assertNull(surface.dirtyTile(0, 2));
    }

    @Test
    void aChangeIsMeasuredInsideItsOwnTile() {
        MapSurface surface = new MapSurface(256, 128);
        surface.set(130, 10, INK);
        surface.set(140, 20, INK);

        assertNull(surface.dirtyTile(0, 0));
        assertEquals(new Rect(130, 10, 11, 11), surface.dirtyTile(1, 0), "surface coordinates, not map-local ones");
    }

    @Test
    void boundsAreTheBoxAroundEverything() {
        MapSurface surface = new MapSurface(256, 256);
        surface.set(10, 10, INK);
        surface.set(200, 100, INK);

        assertEquals(new Rect(10, 10, 191, 91), surface.dirtyBounds());
    }

    @Test
    void clearingSettlesEveryTile() {
        MapSurface surface = new MapSurface(256, 256);
        surface.fill(INK);
        assertTrue(surface.isDirty());

        surface.clearDirty();

        assertFalse(surface.isDirty());
        for (int row = 0; row < surface.tileRows(); row++) {
            for (int col = 0; col < surface.tileCols(); col++) {
                assertNull(surface.dirtyTile(col, row));
            }
        }
    }

    @Test
    void markingAllDirtyCoversEveryTileAndNoMore() {
        MapSurface surface = new MapSurface(256, 384);
        surface.markAllDirty();

        assertEquals(2, surface.tileCols());
        assertEquals(3, surface.tileRows());
        assertEquals(new Rect(0, 0, 128, 128), surface.dirtyTile(0, 0));
        assertEquals(new Rect(128, 256, 128, 128), surface.dirtyTile(1, 2));
    }

    /** A preview renders at whatever size it likes, so the last tile can be a sliver. */
    @Test
    void aPartTileIsClampedToTheSurface() {
        MapSurface surface = new MapSurface(200, 130);
        surface.markAllDirty();

        assertEquals(new Rect(128, 128, 72, 2), surface.dirtyTile(1, 1));
    }

    @Test
    void aRegionComesOutRowByRow() {
        MapSurface surface = new MapSurface(128, 128);
        surface.set(5, 5, INK);
        surface.set(6, 6, (byte) 7);

        Rect changed = surface.dirtyBounds();
        assertNotNull(changed);

        byte[] region = surface.region(changed);
        assertEquals(4, region.length);
        assertEquals(INK, region[0]);
        assertEquals(7, region[3]);
    }
}
