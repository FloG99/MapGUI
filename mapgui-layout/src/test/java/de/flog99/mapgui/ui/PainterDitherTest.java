package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who decides the dither on the paint path: the fill first, the painter's scope second, and nothing else.
 *
 * <p>The palette here has black and white and no gray at all, so "did this dither" is a question with a plain
 * answer - an undithered mid-gray is one index across the whole rect, and a dithered one is both.
 */
class PainterDitherTest {

    private static final byte TRANSPARENT = 0;
    private static final byte BLACK = 1;
    private static final byte WHITE = 2;

    private static final Palette PALETTE = new Palette() {
        @Override
        public byte index(Color color) {
            int level = (color.getRed() + color.getGreen() + color.getBlue()) / 3;
            return level < 128 ? BLACK : WHITE;
        }

        @Override
        public Color color(byte index) {
            return switch (index) {
                case BLACK -> Color.BLACK;
                case WHITE -> Color.WHITE;
                default -> null;
            };
        }

        @Override
        public byte[] entries() {
            return new byte[]{BLACK, WHITE};
        }
    };

    private static final int SIZE = 16;

    private static final class Buffer implements Surface {
        private final byte[] pixels = new byte[SIZE * SIZE];

        @Override
        public int width() {
            return SIZE;
        }

        @Override
        public int height() {
            return SIZE;
        }

        @Override
        public void set(int x, int y, byte color) {
            if (inBounds(x, y)) {
                pixels[y * SIZE + x] = color;
            }
        }

        @Override
        public byte get(int x, int y) {
            return inBounds(x, y) ? pixels[y * SIZE + x] : 0;
        }

        boolean rowIsFlat(int y) {
            for (int x = 1; x < SIZE; x++) {
                if (get(x, y) != get(0, y)) return false;
            }
            return true;
        }

        int count(byte index) {
            int total = 0;
            for (byte pixel : pixels) {
                if (pixel == index) total++;
            }
            return total;
        }
    }

    /** A mid gray, which this palette has no entry for and so has to be made out of the two it does have. */
    private static final Color GRAY = new Color(128, 128, 128);

    private static Painter painterOn(Buffer buffer) {
        return new Painter(buffer, PALETTE, TestFont.INSTANCE);
    }

    private static void paint(Painter painter, Fill fill) {
        painter.box(new Rect(0, 0, SIZE, SIZE), fill, Border.none(), Corner.SQUARE, 0);
    }

    /** Decision D1, both halves of it: the gradient carries its own default and the flat fill has no opinion. */
    @Test
    void aGradientDithersAndASolidFillDoesNot() {
        Buffer gradient = new Buffer();
        paint(painterOn(gradient), Fill.gradient(Color.BLACK, Color.WHITE, Fill.Direction.VERTICAL));

        boolean anyRowDithered = false;
        for (int y = 0; y < SIZE; y++) {
            anyRowDithered |= !gradient.rowIsFlat(y);
        }
        assertTrue(anyRowDithered, "a gradient asks for a ramp the palette cannot express, so it has to dither");

        Buffer solid = new Buffer();
        paint(painterOn(solid), Fill.solid(GRAY));
        assertEquals(SIZE * SIZE, solid.count(WHITE), "a flat fill must not be dithered by default");
    }

    /** And overriding it sticks: banded on purpose is a choice the painter has to honor. */
    @Test
    void aGradientToldNotToDitherBands() {
        Buffer buffer = new Buffer();
        paint(painterOn(buffer), Fill.gradient(Color.BLACK, Color.WHITE, Fill.Direction.VERTICAL).dither(Dither.NONE));

        for (int y = 0; y < SIZE; y++) {
            assertTrue(buffer.rowIsFlat(y), "row " + y + " was dithered by a fill that asked for banding");
        }
    }

    /** The scope reaches a fill that has no opinion of its own, and popping it puts things back. */
    @Test
    void pushDitherReachesAFillWithNoOpinion() {
        Buffer buffer = new Buffer();
        Painter painter = painterOn(buffer);

        Dither previous = painter.pushDither(Dither.ORDERED);
        assertEquals(Dither.NONE, previous, "the painter starts undithered");
        assertEquals(Dither.ORDERED, painter.dither());
        paint(painter, Fill.solid(GRAY));

        assertTrue(buffer.count(BLACK) > 0 && buffer.count(WHITE) > 0, "the scope should have dithered a flat fill");

        painter.popDither(previous);
        assertEquals(Dither.NONE, painter.dither());

        Buffer after = new Buffer();
        Painter restored = painterOn(after);
        restored.pushDither(Dither.ORDERED);
        restored.popDither(Dither.NONE);
        paint(restored, Fill.solid(GRAY));
        assertEquals(SIZE * SIZE, after.count(WHITE), "popping should have put the painter back as it was");
    }

    /**
     * A fill this interface did not write - a caller's own lambda - dithers without having to ask for it.
     *
     * <p>The rule before there were modes was "not a flat color means dither it", and it has to keep holding:
     * driving the choice off {@link Fill#dither()} alone snaps every caller-supplied fill, because a gradient is
     * the only implementation that overrides it. That took the dithering off the rainbow fill this repository
     * ships in {@code GalleryScreen} and documents in {@code docs/widgets.md}, whose whole point is that it
     * leans on dithering.
     */
    @Test
    void aCallerSuppliedFillDithersWithoutAsking() {
        Buffer buffer = new Buffer();
        Painter painter = painterOn(buffer);

        // A lambda rather than Fill.solid, so it is not the flat case, and it names no mode.
        paint(painter, (x, y, bounds) -> GRAY);

        assertTrue(buffer.count(BLACK) > 0 && buffer.count(WHITE) > 0,
                "a caller's fill should dither by default rather than band"
        );
    }

    /** A fill that asked for banding keeps it inside a scope that dithers everything else. */
    @Test
    void aFillThatAsksForNoneStaysBandedInADitheredScope() {
        Buffer buffer = new Buffer();
        Painter painter = painterOn(buffer);

        painter.pushDither(Dither.BLUE_NOISE);
        paint(painter, Fill.solid(GRAY).dither(Dither.NONE));

        assertEquals(SIZE * SIZE, buffer.count(WHITE), "the fill said NONE, which has to win over the scope");
    }

    /**
     * A vector fill under a diffusing mode gets the ordered stand-in rather than nothing, and rather than an
     * exception - there is no rect of colors at this point to diffuse over. See {@link Quantizer#perPixel()}.
     */
    @Test
    void anErrorDiffusionModeStandsInForAFill() {
        Buffer buffer = new Buffer();
        Painter painter = painterOn(buffer);

        painter.pushDither(Dither.ATKINSON);
        paint(painter, Fill.solid(GRAY));

        assertTrue(buffer.count(BLACK) > 0 && buffer.count(WHITE) > 0,
                "a fill under a diffusing mode should still be dithered by the stand-in"
        );

        Buffer fine = new Buffer();
        Painter orderedFine = painterOn(fine);
        orderedFine.pushDither(Dither.ORDERED_FINE);
        paint(orderedFine, Fill.solid(GRAY));

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertEquals(fine.get(x, y), buffer.get(x, y), "the stand-in should be ORDERED_FINE at (" + x + "," + y + ")");
            }
        }
    }

    /** An image, on the other hand, has the whole rect - so it gets the real thing. */
    @Test
    void anImageIsDiffusedWhenTheModeDiffuses() {
        Buffer plain = new Buffer();
        Painter painter = painterOn(plain);
        painter.image(0, 0, filled(GRAY, 255));
        assertEquals(SIZE * SIZE, plain.count(WHITE), "an image is not dithered unless something asks");

        for (Dither mode : new Dither[]{Dither.FLOYD_STEINBERG, Dither.ATKINSON, Dither.SIERRA_LITE}) {
            Buffer buffer = new Buffer();
            Painter diffusing = painterOn(buffer);
            diffusing.pushDither(mode);
            diffusing.image(0, 0, filled(GRAY, 255));

            int white = buffer.count(WHITE);
            // 128 of 255 is a little over half, so about half the pixels should have come out white.
            assertTrue(white > SIZE * SIZE / 3 && white < 2 * SIZE * SIZE / 3,
                    mode + " should have split the rect about evenly, got " + white + " white of " + SIZE * SIZE
            );
        }
    }

    /**
     * The halo rule as it reaches the paint path: a see-through pixel is not drawn at all, so whatever was on
     * the surface stays, and no error crosses into it.
     */
    @Test
    void aTransparentPixelIsLeftAloneOnTheDiffusingPath() {
        Buffer buffer = new Buffer();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) buffer.set(x, y, WHITE);
        }

        BufferedImage image = filled(GRAY, 255);
        for (int y = 0; y < SIZE; y++) {
            for (int x = SIZE / 2; x < SIZE; x++) image.setRGB(x, y, 0);
        }

        Painter painter = painterOn(buffer);
        painter.pushDither(Dither.FLOYD_STEINBERG);
        painter.image(0, 0, image);

        for (int y = 0; y < SIZE; y++) {
            for (int x = SIZE / 2; x < SIZE; x++) {
                assertEquals(WHITE, buffer.get(x, y), "a transparent pixel was drawn at (" + x + "," + y + ")");
            }
        }
        assertTrue(buffer.count(BLACK) > 0, "the opaque half should still have been dithered");
    }

    /**
     * Translucency has to be resolved before diffusing rather than while drawing: half-covered white over
     * black is a mid gray, and a mid gray on this palette is a mixture. Composited afterwards it would be
     * white everywhere, since by then the pixel is an index and there is nothing left to blend.
     */
    @Test
    void aTranslucentImageIsBlendedAgainstTheSurfaceFirst() {
        Buffer buffer = new Buffer();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) buffer.set(x, y, BLACK);
        }

        Painter painter = painterOn(buffer);
        painter.pushDither(Dither.FLOYD_STEINBERG);
        painter.image(0, 0, filled(Color.WHITE, 128));

        Set<Byte> seen = new HashSet<>();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) seen.add(buffer.get(x, y));
        }
        assertTrue(seen.contains(BLACK) && seen.contains(WHITE),
                "half-covered white over black is a mid gray, which this palette has to mix"
        );
        assertEquals(0, buffer.count(TRANSPARENT), "every pixel was covered by something");
    }

    private static BufferedImage filled(Color color, int alpha) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int argb = alpha << 24 | color.getRGB() & 0xFFFFFF;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) image.setRGB(x, y, argb);
        }
        return image;
    }
}
