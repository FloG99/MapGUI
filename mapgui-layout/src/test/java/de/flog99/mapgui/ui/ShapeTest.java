package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // ---- combining ----

    @Test
    void anIntersectionIsOnlyWhereBothCover() {
        Shape left = Shape.of(new Rect(10, 10, 20, 20));
        Shape right = Shape.of(new Rect(20, 10, 20, 20));
        Shape both = left.intersectionWith(right);

        assertTrue(both.contains(25, 15), "the overlap");
        assertFalse(both.contains(15, 15), "only the left one");
        assertFalse(both.contains(35, 15), "only the right one");
        assertEquals(new Rect(20, 10, 10, 20), both.bounds(), "bounded by the overlap, so nothing else is asked");
    }

    @Test
    void combiningCoversEitherAndBoundsBoth() {
        Shape combined = Shape.of(new Rect(10, 10, 10, 10)).combinedWith(Shape.of(new Rect(40, 40, 10, 10)));

        assertTrue(combined.contains(15, 15));
        assertTrue(combined.contains(45, 45));
        assertFalse(combined.contains(30, 30), "the gap between them belongs to neither");
        assertEquals(new Rect(10, 10, 40, 40), combined.bounds());
    }

    @Test
    void withoutCutsTheOtherShapeOut() {
        Shape ring = Shape.circle(32, 32, 20).without(Shape.circle(32, 32, 10));

        assertTrue(ring.contains(32, 47), "out in the ring");
        assertFalse(ring.contains(32, 32), "the middle was cut away");
    }

    /** The aperture case: what gets drawn is everything the shape does not cover. */
    @Test
    void aHoleIsTheBoxWithTheShapePunchedOut() {
        Rect box = new Rect(0, 0, 64, 64);
        Shape blades = Shape.circle(32, 32, 10).holeIn(box);

        assertFalse(blades.contains(32, 32), "the opening is not covered");
        assertTrue(blades.contains(2, 2), "the corner is");
        assertFalse(blades.contains(70, 2), "and nothing outside the box");
        assertEquals(box, blades.bounds());
    }

    /** The corners are the contract: a turn of 0 points right, and turning goes clockwise. */
    @Test
    void aRegularPolygonPutsItsCornersOnTheRadius() {
        Shape.Polygon octagon = Shape.regularPolygon(32, 32, 20, 8, 0);

        assertEquals(8, octagon.xs().length);
        assertEquals(52, octagon.xs()[0], 0.001);
        assertEquals(32, octagon.ys()[0], 0.001);
        assertTrue(octagon.contains(32, 32));
        assertFalse(octagon.contains(51, 13), "a corner of the square it fits inside is cut off");

        Shape.Polygon turned = Shape.regularPolygon(32, 32, 20, 4, 90);
        assertEquals(52, turned.ys()[0], 0.001, "a quarter turn goes down the screen, not up");
    }

    @Test
    void aSideOfALineKeepsWhatIsToItsRight() {
        Rect box = new Rect(0, 0, 64, 64);
        Shape below = Shape.sideOfLine(box, 0, 32, 64, 32);

        assertTrue(below.contains(32, 40), "below a line drawn left to right");
        assertFalse(below.contains(32, 20), "above it");
    }

    /** Several straight cuts describing one area between them, which is what a polygon is from the outside. */
    @Test
    void cutsCombineIntoAnAreaBetweenThem() {
        Rect box = new Rect(0, 0, 64, 64);
        Shape band = Shape.sideOfLine(box, 0, 20, 64, 20).intersectionWith(Shape.sideOfLine(box, 64, 40, 0, 40));

        assertTrue(band.contains(32, 30), "between the two lines");
        assertFalse(band.contains(32, 10), "above both");
        assertFalse(band.contains(32, 50), "below both");
    }

    /**
     * The one invariant the fast path has to hold: a shape's rows and its pixels must agree.
     *
     * <p>A filled shape is drawn row by row, and the same shape with an outline is drawn pixel by pixel - so a shape
     * whose {@code spansAt} disagreed with its {@code contains} would come out a different size depending on whether
     * it happened to be given a border, which is not the sort of thing anybody would think to look for.
     */
    @Test
    void rowsAndPixelsCoverTheSameThing() {
        Rect box = new Rect(0, 0, 64, 64);
        Shape.Polygon arrowhead = Shape.polygon(new double[]{30, 50, 30, 10}, new double[]{5, 45, 30, 45});

        for (Shape shape : List.of(
                Shape.of(new Rect(10, 8, 21, 17)),
                Shape.regularPolygon(32, 32, 20, 8, 0),
                Shape.regularPolygon(32, 32, 17.5, 5, 11.25),
                arrowhead,
                arrowhead.intersectionWith(Shape.of(new Rect(20, 20, 30, 30))),
                Shape.regularPolygon(32, 32, 20, 8, 22.5).intersectionWith(Shape.of(box)).holeIn(box),
                Shape.regularPolygon(90, 90, 60, 8, 5).intersectionWith(Shape.of(box)).holeIn(box))) {

            for (int y = box.y(); y < box.bottom(); y++) {
                int[] spans = shape.spansAt(y);
                assertNotNull(spans, shape + " row " + y + " should answer in spans");

                for (int x = box.x(); x < box.right(); x++) {
                    assertEquals(shape.contains(x, y), covers(spans, x), shape + " at " + x + "," + y);
                }
            }
        }
    }

    private static boolean covers(int[] spans, int x) {
        for (int i = 0; i + 1 < spans.length; i += 2) {
            if (x >= spans[i] && x < spans[i + 1]) return true;
        }
        return false;
    }

    @Test
    void aShapeClipCutsWhateverIsDrawnAfterIt() {
        Buffer buffer = new Buffer();
        Painter painter = painter(buffer);

        Painter.Clip previous = painter.pushClip(Shape.circle(32, 32, 10));
        painter.fill(new Rect(0, 0, 64, 64), FILL);
        painter.popClip(previous);

        assertEquals(FILLED, buffer.get(32, 32), "inside the clip");
        assertEquals(NOTHING, buffer.get(0, 0), "the corner was clipped away");
        assertTrue(buffer.count(FILLED) < 64 * 64 / 4, "a disc of ten, not the whole square");

        painter.fill(new Rect(0, 0, 2, 2), FILL);
        assertEquals(FILLED, buffer.get(0, 0), "and the clip was given back");
    }
}
