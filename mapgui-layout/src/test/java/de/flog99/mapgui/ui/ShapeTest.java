package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeTest {

    private static final Color FILL = new Color(10, 0, 0);
    private static final Color STROKE = new Color(20, 0, 0);

    private static final byte NOTHING = 0;
    private static final byte FILLED = 10;
    private static final byte STROKED = 20;

    /** Indices are the red channel, so a pixel says which of the two colors put it there. */
    private static final Palette PALETTE = new Palette() {

        @Override
        public byte index(Color color) {
            return (byte) color.getRed();
        }

        @Override
        public Color color(byte index) {
            return new Color(index & 0xFF, 0, 0);
        }
    };

    private static final class Buffer implements Surface {

        private static final int SIDE = 64;

        private final byte[] pixels = new byte[SIDE * SIDE];

        @Override
        public int width() {
            return SIDE;
        }

        @Override
        public int height() {
            return SIDE;
        }

        @Override
        public void set(int x, int y, byte color) {
            if (inBounds(x, y)) {
                pixels[y * SIDE + x] = color;
            }
        }

        @Override
        public byte get(int x, int y) {
            return inBounds(x, y) ? pixels[y * SIDE + x] : 0;
        }

        int count(byte of) {
            int total = 0;
            for (byte pixel : pixels) {
                if (pixel == of) {
                    total++;
                }
            }
            return total;
        }
    }

    private static Painter painter(Buffer buffer) {
        return new Painter(buffer, PALETTE, new TestFont());
    }

    @Test
    void aTriangleIsFilledInsideAndOutlinedOnItsEdge() {
        Buffer buffer = new Buffer();
        painter(buffer).triangle(10, 10, 50, 10, 30, 40, Fill.solid(FILL), Border.solid(1, STROKE));

        assertEquals(STROKED, buffer.get(30, 10), "the top edge");
        assertEquals(FILLED, buffer.get(30, 20), "well inside");
        assertEquals(NOTHING, buffer.get(12, 35), "outside the sloping side");
    }

    @Test
    void aPolygonCanBeConcave() {
        Buffer buffer = new Buffer();
        // An arrowhead: the notch at the bottom middle has to stay empty.
        int[] xs = {30, 50, 30, 10};
        int[] ys = {5, 45, 30, 45};
        painter(buffer).polygon(xs, ys, Fill.solid(FILL), Border.none());

        assertTrue(buffer.get(30, 20) != NOTHING, "inside the head");
        assertEquals(NOTHING, buffer.get(30, 42), "the notch is outside the shape");
    }

    @Test
    void thicknessMakesTheOutlineThicker() {
        Buffer thin = new Buffer();
        painter(thin).circle(32, 32, 20, null, Border.solid(1, STROKE));

        Buffer thick = new Buffer();
        painter(thick).circle(32, 32, 20, null, Border.solid(4, STROKE));

        assertTrue(thick.count(STROKED) > thin.count(STROKED) * 3, "four pixels of outline against one");
        assertEquals(NOTHING, thick.get(32, 32), "no fill was asked for, so the middle stays empty");
    }

    @Test
    void aBorderlessShapeIsFilledToItsEdge() {
        Buffer buffer = new Buffer();
        painter(buffer).circle(32, 32, 5, Fill.solid(FILL), Border.none());

        assertEquals(FILLED, buffer.get(32, 32));
        assertEquals(FILLED, buffer.get(37, 32), "the edge pixel is fill, not outline");
        assertEquals(0, buffer.count(STROKED));
    }

    @Test
    void aThickLineIsWiderThanAThinOne() {
        Buffer thin = new Buffer();
        painter(thin).line(10, 32, 50, 32, STROKE);

        Buffer thick = new Buffer();
        painter(thick).line(10, 32, 50, 32, STROKE, 5);

        assertEquals(41, thin.count(STROKED));
        assertTrue(thick.count(STROKED) > 41 * 4);
        assertTrue(thick.get(30, 34) != NOTHING, "two pixels either side of the middle");
    }

    @Test
    void aPolylineIsOpenAndAPolygonIsClosed() {
        int[] xs = {10, 50, 50};
        int[] ys = {10, 10, 50};

        Buffer open = new Buffer();
        painter(open).polyline(xs, ys, STROKE, 1);

        Buffer closed = new Buffer();
        painter(closed).polygon(STROKE, xs, ys);

        assertFalse(open.get(30, 30) != NOTHING, "the run home is not drawn");
        assertTrue(closed.get(30, 30) != NOTHING, "the closing edge is");
    }

    @Test
    void aShapeOffTheSurfaceDrawsNothingAndDoesNotThrow() {
        Buffer buffer = new Buffer();
        painter(buffer).triangle(-40, -40, -20, -40, -30, -20, Fill.solid(FILL), Border.solid(2, STROKE));

        assertEquals(0, buffer.count(FILLED));
        assertEquals(0, buffer.count(STROKED));
    }

    @Test
    void containsAnswersOutsideTheBoundsToo() {
        Shape triangle = Shape.triangle(0, 0, 10, 0, 5, 10);

        assertFalse(triangle.contains(-5, -5));
        assertFalse(triangle.contains(100, 100));
        assertTrue(triangle.contains(5, 5));
    }
}
