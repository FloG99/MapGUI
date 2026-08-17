package de.flog99.mapgui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where a marker is allowed to sit, which is every half pixel rather than every pixel.
 *
 * <p>The client draws icons in a space twice as fine as the map's pixels. Rounding a position to a whole one
 * before it got there left every other spot unreachable, so a pointer following a head could only ever land on
 * even ground.
 */
class MarkerTest {

    /** Null, since the icon types live in a registry a unit test has no server to initialize - and none of this reads it. */
    private static Marker at(double x, double y) {
        return Marker.at(null, x, y);
    }

    @Test
    void halvesSurvive() {
        assertEquals(10.5, at(10.5, 20.5).x(), 0.0);
        assertEquals(20.5, at(10.5, 20.5).y(), 0.0);
    }

    @Test
    void anythingFinerSnapsToTheNearestHalf() {
        assertEquals(10.0, at(10.2, 0).x(), 0.0);
        assertEquals(10.5, at(10.3, 0).x(), 0.0);
        assertEquals(10.5, at(10.7, 0).x(), 0.0);
        assertEquals(11.0, at(10.8, 0).x(), 0.0);
    }

    @Test
    void wholePixelsAreLeftWhereTheyAre() {
        assertEquals(64.0, at(64, 32).x(), 0.0);
        assertEquals(32.0, at(64, 32).y(), 0.0);
    }

    /**
     * The reason snapping happens here rather than at the transport: markers are resent when they stop being
     * equal, so a position that never settles would cost a packet for movement the client could not draw anyway.
     */
    @Test
    void movementTooSmallToDrawIsNotAMove() {
        assertEquals(at(10.2, 5), at(10.24, 5));
        assertNotEquals(at(10.2, 5), at(10.3, 5));
    }

    /** Which pixel a marker is in, for the wall's tile split - a half pixel belongs to the pixel it starts. */
    @Test
    void thePixelIsTheOneItStartsIn() {
        assertEquals(10, at(10.0, 0).pixelX());
        assertEquals(10, at(10.5, 0).pixelX());
        assertEquals(11, at(11.0, 0).pixelX());
        assertEquals(20, at(0, 20.5).pixelY());
    }

    /** Rebuilding for a rotation or a caption must not quietly move it. */
    @Test
    void derivingKeepsThePosition() {
        Marker marker = at(10.5, 20.5);

        assertEquals(10.5, marker.rotation(4).x(), 0.0);
        assertEquals(20.5, marker.label("hi").y(), 0.0);
    }
}
