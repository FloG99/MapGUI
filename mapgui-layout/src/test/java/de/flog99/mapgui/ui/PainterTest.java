package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PainterTest {

    /** Identity palette: index 1 means "painted", 0 means "untouched". */
    private static final Palette PALETTE = new Palette() {
        @Override
        public byte index(Color color) {
            return 1;
        }

        @Override
        public Color color(byte index) {
            return Color.BLACK;
        }
    };

    private static final class Buffer implements Surface {
        private final byte[] pixels = new byte[32 * 32];

        @Override
        public int width() {
            return 32;
        }

        @Override
        public int height() {
            return 32;
        }

        @Override
        public void set(int x, int y, byte color) {
            if (inBounds(x, y)) {
                pixels[y * 32 + x] = color;
            }
        }

        @Override
        public byte get(int x, int y) {
            return inBounds(x, y) ? pixels[y * 32 + x] : 0;
        }

        boolean painted(int x, int y) {
            return get(x, y) != 0;
        }

        int count() {
            int total = 0;
            for (byte pixel : pixels) {
                if (pixel != 0) {
                    total++;
                }
            }
            return total;
        }
    }

    private static Painter painter(Buffer buffer) {
        return new Painter(buffer, PALETTE, TestFont.INSTANCE);
    }

    @Test
    void radiusOneCircleIsAPlus() {
        Buffer buffer = new Buffer();
        painter(buffer).circle(10, 10, 1, Color.WHITE, null);

        assertEquals(5, buffer.count());
        assertTrue(buffer.painted(10, 10));
        assertTrue(buffer.painted(9, 10));
        assertTrue(buffer.painted(10, 9));
        assertFalse(buffer.painted(9, 9));
    }

    @Test
    void radiusZeroCircleIsOnePixel() {
        Buffer buffer = new Buffer();
        painter(buffer).circle(4, 4, 0, Color.WHITE, null);

        assertEquals(1, buffer.count());
        assertTrue(buffer.painted(4, 4));
    }

    @Test
    void circlesAreSymmetric() {
        Buffer buffer = new Buffer();
        painter(buffer).circle(15, 15, 6, Color.WHITE, null);

        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                boolean here = buffer.painted(15 + dx, 15 + dy);
                assertEquals(here, buffer.painted(15 - dx, 15 + dy), "mirrored horizontally at " + dx + "," + dy);
                assertEquals(here, buffer.painted(15 + dx, 15 - dy), "mirrored vertically at " + dx + "," + dy);
            }
        }
    }

    @Test
    void roundedRectCutsItsCornersButNotItsEdges() {
        Buffer buffer = new Buffer();
        painter(buffer).rect(new Rect(4, 4, 20, 20), Color.WHITE, 0, null, 5);

        assertFalse(buffer.painted(4, 4), "top-left corner should be cut away");
        assertTrue(buffer.painted(14, 4), "middle of the top edge should be solid");
        assertTrue(buffer.painted(4, 14), "middle of the left edge should be solid");
    }

    @Test
    void radiusIsClampedToHalfTheShortestSide() {
        Buffer buffer = new Buffer();
        painter(buffer).rect(new Rect(0, 0, 10, 10), Color.WHITE, 0, null, 999);

        // A radius of half the side makes a circle, so the center is filled and corners are not.
        assertTrue(buffer.painted(5, 5));
        assertFalse(buffer.painted(0, 0));
    }

    @Test
    void clipStopsDrawingOutsideIt() {
        Buffer buffer = new Buffer();
        Painter painter = painter(buffer);

        Rect previous = painter.pushClip(new Rect(0, 0, 8, 8));
        painter.fill(new Rect(0, 0, 32, 32), Color.WHITE);
        painter.popClip(previous);

        assertEquals(64, buffer.count());
        assertTrue(buffer.painted(7, 7));
        assertFalse(buffer.painted(8, 8));
    }

    @Test
    void wrapHardBreaksWordsThatCannotFit() {
        Buffer buffer = new Buffer();
        var lines = painter(buffer).wrap("aaaaaaaaaa", 20);

        assertEquals(4, lines.size());
        assertEquals("aaa", lines.get(0));
    }

    @Test
    void ellipsizeKeepsWithinTheGivenWidth() {
        Buffer buffer = new Buffer();
        Painter painter = painter(buffer);
        String result = painter.ellipsize("aaaaaaaaaa", 30);

        assertTrue(result.endsWith(".."));
        assertTrue(painter.font().widthOf(result) <= 30);
    }

    @Test
    void ellipsizeKeepsASingleGlyphRatherThanReplacingItWithDots() {
        Buffer buffer = new Buffer();

        assertEquals("x", painter(buffer).ellipsize("x", 3));
    }

    @Test
    void cornerShapesCutDifferentAmounts() {
        int round = filled(Corner.ROUND);
        int bevel = filled(Corner.BEVEL);
        int notch = filled(Corner.NOTCH);
        int square = filled(Corner.SQUARE);

        assertEquals(20 * 20, square, "square keeps every pixel");
        // A quarter circle keeps more of each corner than a straight 45 degree cut, which in turn
        // keeps more than a square bite.
        assertTrue(round > bevel, "round should keep more than bevel");
        assertTrue(bevel > notch, "bevel should keep more than notch");
        assertTrue(notch < square);
    }

    private static int filled(Corner corner) {
        Buffer buffer = new Buffer();
        painter(buffer).box(new Rect(4, 4, 20, 20), Fill.solid(Color.WHITE), Border.none(), corner, 6);
        return buffer.count();
    }

    @Test
    void everyCornerShapeStaysSymmetric() {
        for (Corner corner : Corner.values()) {
            Buffer buffer = new Buffer();
            painter(buffer).box(new Rect(0, 0, 21, 21), Fill.solid(Color.WHITE), Border.none(), corner, 7);

            for (int y = 0; y < 21; y++) {
                for (int x = 0; x < 21; x++) {
                    boolean here = buffer.painted(x, y);
                    assertEquals(here, buffer.painted(20 - x, y), corner + " mirrored at " + x + "," + y);
                    assertEquals(here, buffer.painted(x, 20 - y), corner + " flipped at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void bevelLightsTheTopLeftAndShadesTheBottomRight() {
        Buffer buffer = new Buffer();
        Palette twoTone = new Palette() {
            @Override
            public byte index(Color color) {
                return color.equals(Color.WHITE) ? (byte) 1 : (byte) 2;
            }

            @Override
            public Color color(byte index) {
                return Color.BLACK;
            }
        };
        new Painter(buffer, twoTone, TestFont.INSTANCE)
                .box(new Rect(0, 0, 12, 12), null, Border.bevel(1, Color.WHITE, Color.BLACK), Corner.SQUARE, 0);

        assertEquals(1, buffer.get(6, 0), "top edge is lit");
        assertEquals(1, buffer.get(0, 6), "left edge is lit");
        assertEquals(2, buffer.get(6, 11), "bottom edge is shaded");
        assertEquals(2, buffer.get(11, 6), "right edge is shaded");
    }

    @Test
    void raisedAndSunkenAreInverses() {
        Border raised = Border.raised(1).resolve(new Color(100, 100, 100));
        Border sunken = Border.sunken(1).resolve(new Color(100, 100, 100));

        assertEquals(raised.primary(), sunken.secondary());
        assertEquals(raised.secondary(), sunken.primary());
    }

    @Test
    void borderFollowsTheRounding() {
        Buffer buffer = new Buffer();
        painter(buffer).rect(new Rect(0, 0, 16, 16), null, 2, Color.WHITE, 4);

        assertTrue(buffer.painted(8, 0), "border along the top edge");
        assertFalse(buffer.painted(8, 8), "middle stays empty when there is no fill");
        assertFalse(buffer.painted(0, 0), "corner is still rounded off");
    }
}
