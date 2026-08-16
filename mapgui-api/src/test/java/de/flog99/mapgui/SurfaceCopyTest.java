package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Copying a canvas onto the surface that gets sent.
 *
 * <p>The thing being pinned is that the copy marks what a pixel-by-pixel one would have marked and nothing
 * more, since that is the whole reason it is allowed to be faster: a screen is wiped and repainted every
 * frame, and only what really changed may cross over.
 */
class SurfaceCopyTest {

    private static final byte INK = 42;

    private static MapSurface painted(int width, int height, byte color) {
        MapSurface surface = new MapSurface(width, height);
        surface.fill(color);
        return surface;
    }

    @Test
    void anIdenticalCanvasChangesNothing() {
        MapSurface canvas = painted(128, 128, INK);
        MapSurface surface = painted(128, 128, INK);
        surface.clearDirty();

        surface.copyFrom(canvas);

        assertFalse(surface.isDirty(), "a frame that came out the same must send nothing at all");
    }

    /** A canvas wiped and repainted is dirty everywhere; what crosses over must not be. */
    @Test
    void awipedAndRepaintedCanvasOnlyCarriesTheDifference() {
        MapSurface canvas = painted(128, 128, INK);
        MapSurface surface = new MapSurface(128, 128);
        surface.copyFrom(canvas);
        surface.clearDirty();

        canvas.fill(INK);
        canvas.set(60, 30, (byte) 7);
        assertTrue(canvas.isDirty());

        surface.copyFrom(canvas);

        assertEquals(new Rect(60, 30, 1, 1), surface.dirtyBounds(), "the wipe must not come with it");
    }

    @Test
    void thePixelsThemselvesArriveWhole() {
        MapSurface canvas = new MapSurface(200, 130);
        Random random = new Random(7);
        for (int y = 0; y < 130; y++) {
            for (int x = 0; x < 200; x++) canvas.set(x, y, (byte) random.nextInt(128));
        }

        MapSurface surface = new MapSurface(200, 130);
        surface.copyFrom(canvas);

        assertArrayEquals(canvas.pixels(), surface.pixels());
    }

    @Test
    void aChangeSpanningTwoMapsIsRecordedAgainstBoth() {
        MapSurface canvas = new MapSurface(384, 128);
        MapSurface surface = new MapSurface(384, 128);

        // A run crossing the seam between the first and second map, and nothing on the third.
        for (int x = 100; x < 160; x++) canvas.set(x, 40, INK);
        surface.copyFrom(canvas);

        assertEquals(new Rect(100, 40, 28, 1), surface.dirtyTile(0, 0));
        assertEquals(new Rect(128, 40, 32, 1), surface.dirtyTile(1, 0), "cut at the map boundary, not sent across it");
        assertNull(surface.dirtyTile(2, 0));
    }

    /** Two far-apart changes in one row span the gap, which is what a row's tracking has always done. */
    @Test
    void arowKeepsTheSpanAroundWhatChanged() {
        MapSurface canvas = new MapSurface(128, 128);
        MapSurface surface = new MapSurface(128, 128);

        canvas.set(10, 5, INK);
        canvas.set(100, 5, INK);
        surface.copyFrom(canvas);

        assertEquals(new Rect(10, 5, 91, 1), surface.dirtyBounds());
    }

    /**
     * The point of the whole exercise, checked against the thing it replaced: whatever the frame, copying it
     * marks exactly what setting every pixel by hand would have.
     */
    @Test
    void whatIsMarkedMatchesAPixelByPixelCopy() {
        Random random = new Random(11);

        for (int attempt = 0; attempt < 200; attempt++) {
            MapSurface canvas = new MapSurface(256, 256);
            MapSurface copied = new MapSurface(256, 256);
            MapSurface byHand = new MapSurface(256, 256);

            // A frame already on screen, so there is something for the next one to differ from.
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) canvas.set(x, y, (byte) random.nextInt(4));
            }
            copied.copyFrom(canvas);
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) byHand.set(x, y, canvas.get(x, y));
            }
            copied.clearDirty();
            byHand.clearDirty();

            // Then a handful of scattered changes, which is what a real frame is.
            for (int i = 0; i < random.nextInt(20); i++) {
                canvas.set(random.nextInt(256), random.nextInt(256), (byte) random.nextInt(4));
            }

            copied.copyFrom(canvas);
            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) byHand.set(x, y, canvas.get(x, y));
            }

            assertArrayEquals(byHand.pixels(), copied.pixels(), "attempt " + attempt);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 2; col++) {
                    assertEquals(byHand.dirtyTile(col, row), copied.dirtyTile(col, row), "attempt " + attempt);
                    assertEquals(byHand.dirtyRegions(col, row), copied.dirtyRegions(col, row), "attempt " + attempt);
                }
            }
        }
    }

    @Test
    void adifferentlySizedSurfaceIsRefused() {
        MapSurface surface = new MapSurface(128, 128);

        assertThrows(IllegalArgumentException.class, () -> surface.copyFrom(new MapSurface(128, 256)));
    }
}
