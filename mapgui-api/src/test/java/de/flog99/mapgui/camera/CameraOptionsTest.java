package de.flog99.mapgui.camera;

import de.flog99.mapgui.map.MapPrinter;
import org.junit.jupiter.api.Test;

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
}
