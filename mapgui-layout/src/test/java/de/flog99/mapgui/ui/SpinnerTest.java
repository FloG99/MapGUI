package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Point;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Spinner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinnerTest {

    private static final int CANVAS = 128;
    private static final Rect SCREEN = new Rect(0, 0, CANVAS, CANVAS);

    private final Animator animator = new Animator();
    private final Grey surface = new Grey();
    private final Painter painter = new Painter(surface, surface, TestFont.INSTANCE);

    /**
     * A surface and a palette in one, where an index <i>is</i> a brightness.
     *
     * <p>Which is what lets a test read a spinner: the dots are one colour at different alphas over a black
     * background, so what lands in a pixel is the alpha it was drawn with, and the brightest pixel is the head.
     */
    private static final class Grey implements Surface, Palette {

        private final byte[] pixels = new byte[CANVAS * CANVAS];

        @Override
        public int width() {
            return CANVAS;
        }

        @Override
        public int height() {
            return CANVAS;
        }

        @Override
        public void set(int x, int y, byte color) {
            pixels[x + y * CANVAS] = color;
        }

        @Override
        public byte get(int x, int y) {
            return pixels[x + y * CANVAS];
        }

        @Override
        public byte index(Color color) {
            return (byte) color.getRed();
        }

        @Override
        public Color color(byte index) {
            int grey = index & 0xFF;
            return new Color(grey, grey, grey);
        }

        Point brightest() {
            Point at = null;
            int best = 0;
            for (int y = 0; y < CANVAS; y++) {
                for (int x = 0; x < CANVAS; x++) {
                    int value = pixels[x + y * CANVAS] & 0xFF;
                    if (value > best) {
                        best = value;
                        at = new Point(x, y);
                    }
                }
            }
            return at;
        }

        boolean anythingDrawn() {
            for (byte pixel : pixels) {
                if (pixel != 0) return true;
            }
            return false;
        }

        void clear() {
            java.util.Arrays.fill(pixels, (byte) 0);
        }
    }

    /**
     * A fresh black canvas each time, since a dot is drawn with alpha and so blends with whatever is already
     * there - painting a second frame over the first would leave every dot as bright as it has ever been.
     */
    private void paintAt(long millis, Node root) {
        surface.clear();
        animator.clock(millis);
        animator.beginLayout();
        LayoutContext context = new LayoutContext(TestFont.INSTANCE, animator);
        root.measure(context, CANVAS, CANVAS);
        root.arrange(context, SCREEN);
        root.paint(painter);
    }

    @Test
    void itTakesTheSquareItWasAskedFor() {
        Spinner spinner = Spinner().size(20);
        paintAt(0, Column(spinner));

        assertEquals(20, spinner.bounds().width());
        assertEquals(20, spinner.bounds().height());
    }

    /** A spinner never finishes, so it has to keep asking for frames or it would turn once and stop. */
    @Test
    void itKeepsFramesComing() {
        paintAt(0, Column(Spinner()));

        assertTrue(animator.animating(), "a spinner on screen should keep frames coming");
    }

    /**
     * A screen with animation turned off asked for no movement, and a spinner is movement. Standing still is the
     * right answer there - repainting forever to draw the same pixels would be the wrong one.
     */
    @Test
    void animationsOffLeavesItStandingStillRatherThanRepaintingForever() {
        animator.enabled(false);
        paintAt(0, Column(Spinner()));

        assertFalse(animator.animating(), "animations off must not leave a spinner asking for frames");
        assertTrue(surface.anythingDrawn(), "it should still be drawn, just not turning");
    }

    /** Half a turn later the bright dot is on the far side of the ring, which is what turning means. */
    @Test
    void theBrightDotTravelsRoundTheRing() {
        Spinner spinner = Spinner().size(13).period(1000);
        Node root = Column(spinner);

        paintAt(0, root);
        Point top = surface.brightest();

        paintAt(500, root);
        Point bottom = surface.brightest();

        Rect box = spinner.bounds();
        assertTrue(top.y < box.y() + box.height() / 2, "at the start of a turn the bright dot is at the top");
        assertTrue(bottom.y > box.y() + box.height() / 2, "half a turn later it is at the bottom");
        assertEquals(top.x, bottom.x, 1, "and on the same vertical, since it went half way round");
    }

    /**
     * It steps from dot to dot rather than sliding between them. Two clocks inside the same eighth of a turn have
     * to paint the same pixels, or the map would be resent for a movement too small to see.
     */
    @Test
    void itStepsFromDotToDotRatherThanSlidingBetweenThem() {
        Spinner spinner = Spinner().size(13).period(1000).dots(8);
        Node root = Column(spinner);

        paintAt(0, root);
        Point start = surface.brightest();

        // Still inside the first eighth, so nothing may have moved.
        paintAt(100, root);
        assertEquals(start, surface.brightest());

        // Over into the second, so it must have.
        paintAt(130, root);
        assertNotEquals(start, surface.brightest());
    }

    /**
     * Every dot whole, and the ring the same distance out on all four sides.
     *
     * <p>Half a pixel is dropped twice when a dot is placed - the middle of a box is not the middle of a pixel, and
     * half of an even dot is not a whole number - and two halves in the same direction move the ring a whole pixel.
     * That is what it looked like: the top dot half outside the box with its other half cut off, and a blank row
     * under the bottom one.
     *
     * <p>Sizes either side of the odd and even cases, since the dot's own size is the box's sixth and so changes
     * parity with it.
     */
    @Test
    void everyDotIsWholeAndTheRingIsCentred() {
        for (int size : new int[]{13, 14, 20, 21}) {
            Spinner spinner = Spinner().size(size).dots(8);
            paintAt(0, Column(spinner));

            Rect box = spinner.bounds();
            int dot = Math.max(2, Math.min(box.width(), box.height()) / 6);

            assertEquals(8 * dot * dot, lit(box), "size " + size + ": eight whole dots, none clipped by the box");

            // The far side of the ring is as far from the middle as the near side, which is the whole of round.
            assertEquals(first(box, true) - box.x(), box.x() + box.width() - 1 - last(box, true),
                    "size " + size + ": as far in from the left as from the right");
            assertEquals(first(box, false) - box.y(), box.y() + box.height() - 1 - last(box, false),
                    "size " + size + ": as far in from the top as from the bottom");
        }
    }

    /** Lit pixels inside the spinner's own box, which is every pixel it should have drawn. */
    private int lit(Rect box) {
        int count = 0;
        for (int y = box.y(); y < box.y() + box.height(); y++) {
            for (int x = box.x(); x < box.x() + box.width(); x++) {
                if (surface.get(x, y) != 0) count++;
            }
        }
        return count;
    }

    private int first(Rect box, boolean across) {
        for (int at = 0; at < (across ? box.width() : box.height()); at++) {
            if (anyAt(box, across, at)) return (across ? box.x() : box.y()) + at;
        }
        return -1;
    }

    private int last(Rect box, boolean across) {
        for (int at = (across ? box.width() : box.height()) - 1; at >= 0; at--) {
            if (anyAt(box, across, at)) return (across ? box.x() : box.y()) + at;
        }
        return -1;
    }

    private boolean anyAt(Rect box, boolean across, int at) {
        int length = across ? box.height() : box.width();
        for (int other = 0; other < length; other++) {
            int x = across ? box.x() + at : box.x() + other;
            int y = across ? box.y() + other : box.y() + at;
            if (surface.get(x, y) != 0) return true;
        }
        return false;
    }
}
