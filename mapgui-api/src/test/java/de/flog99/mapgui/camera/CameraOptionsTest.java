package de.flog99.mapgui.camera;

import de.flog99.mapgui.map.MapPrinter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CameraOptionsTest {

    /**
     * Loudly rather than quietly, and this is why: a size pulled down to fit stopped being a whole number of maps, so
     * the capture came back unprintable and read as a photograph that failed - layers away from the wrong number.
     */
    @Test
    void aSizeTooBigToTraceIsRefusedRatherThanShrunk() {
        assertThrows(IllegalArgumentException.class, () -> CameraOptions.defaults().size(MapPrinter.sizeFor(5)));
        assertThrows(IllegalArgumentException.class, () -> CameraOptions.defaults().size(0));
    }

    /**
     * A frame may be long and thin, and the ceiling it is held to is the pixels in it rather than either side.
     *
     * <p>Which is the whole point of counting them that way. A row of mirrors down a wall is thirteen blocks across and
     * one high: photographed as one frame at a map's worth of pixels per block it is 1664 by 128, more than three times
     * {@link CameraOptions#MAX_SIZE} on its long side and still smaller than a 512 square. Held to a limit per side it
     * would have been refused for costing less than what is allowed.
     */
    @Test
    void aLongThinCaptureIsMeasuredInPixelsRatherThanPerSide() {
        assertDoesNotThrow(() -> CameraOptions.defaults().size(1664, 128));
        assertEquals(1664 * 128, 1664 * CameraOptions.defaults().size(1664, 128).height());
    }

    /** And the pixels are what is capped, however they are arranged. */
    @Test
    void morePixelsThanTheCeilingIsRefusedWhicheverWayRound() {
        assertThrows(IllegalArgumentException.class, () -> CameraOptions.defaults().size(4096, 128));
        assertThrows(IllegalArgumentException.class, () -> CameraOptions.defaults().size(128, 4096));
        assertDoesNotThrow(() -> CameraOptions.defaults().size(CameraOptions.MAX_SIZE, CameraOptions.MAX_SIZE));
    }

    /** Thin is not the same as small: a frame still has to be a picture in both directions. */
    @Test
    void aSideThinnerThanTheFloorIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> CameraOptions.defaults().size(2048, 1));
        assertThrows(IllegalArgumentException.class, () -> CameraOptions.defaults().size(8, 8));
    }

    /**
     * The aspect is what keeps pixels square, so it is read the way a frame is: width over height.
     *
     * <p>Backwards, a wide capture would narrow its field of view instead of widening it - and the picture would come
     * back squeezed rather than stretched, which looks deliberate.
     */
    @Test
    void theAspectIsWidthOverHeight() {
        assertEquals(4.0, CameraOptions.defaults().size(512, 128).aspect(), 1e-9);
        assertEquals(1.0, CameraOptions.defaults().size(128).aspect(), 1e-9);
    }

    /** One number still means a square, since that is what a viewfinder and a photograph both are. */
    @Test
    void oneSizeIsStillASquare() {
        CameraOptions square = CameraOptions.defaults().size(64);

        assertEquals(64, square.width());
        assertEquals(64, square.height());
        assertEquals(64, square.size(), "the old accessor should still answer for a square one");
    }

    /**
     * The pre-1.2.0 constructor still compiles and still means a square.
     *
     * <p>Adding {@code height} moved the canonical constructor's arity, which is a source break for anybody who built one
     * positionally - and a record's canonical constructor is part of the published surface. This is the test that says the
     * old form was kept rather than merely remembered.
     */
    @Test
    void theSquareConstructorFromBeforeHeightExistedStillWorks() {
        CameraOptions old = new CameraOptions(192, 80f, 64, true, false, false, true);

        assertEquals(192, old.width(), "the one size it was given should be the width");
        assertEquals(192, old.height(), "and the height");
        assertEquals(new CameraOptions(192, 192, 80f, 64, true, false, false, true), old,
                "the old seven-argument form should build exactly what the eight-argument one does");
    }
}
