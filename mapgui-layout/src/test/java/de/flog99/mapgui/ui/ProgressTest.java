package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Progress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTest {

    private static final int CANVAS = 64;
    private static final Rect SCREEN = new Rect(0, 0, CANVAS, CANVAS);

    private final Animator animator = new Animator();
    private final Grey surface = new Grey();
    private final Painter painter = new Painter(surface, surface, TestFont.INSTANCE);

    /** A surface and a palette in one, where an index <i>is</i> a red channel - so a pixel says what drew it. */
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
            int red = index & 0xFF;
            return new Color(red, 0, 0);
        }

        /** How many pixels of one row are lit, which for a bar is how much of it is filled. */
        int litInRow(int y) {
            int lit = 0;
            for (int x = 0; x < CANVAS; x++) {
                if (pixels[x + y * CANVAS] != 0) lit++;
            }
            return lit;
        }

        boolean lit(int x, int y) {
            return pixels[x + y * CANVAS] != 0;
        }

        void clear() {
            java.util.Arrays.fill(pixels, (byte) 0);
        }
    }

    private void paintAt(long millis, Node root) {
        surface.clear();
        animator.clock(millis);
        animator.beginLayout();
        LayoutContext context = new LayoutContext(TestFont.INSTANCE, animator);
        root.measure(context, CANVAS, CANVAS);
        root.arrange(context, SCREEN);
        root.paint(painter);
    }

    /** No width of its own means the width it was offered, and the default height. */
    @Test
    void itTakesTheWidthItIsOfferedAndItsOwnHeight() {
        Progress bar = Progress();
        paintAt(0, Column(bar));

        assertEquals(CANVAS, bar.bounds().width());
        assertEquals(Progress.DEFAULT_HEIGHT, bar.bounds().height());
    }

    @Test
    void theFilledPartIsTheValue() {
        Progress bar = Progress().bar(Color.WHITE);
        paintAt(0, Column(bar.value(0.25)));
        assertEquals(CANVAS / 4, surface.litInRow(1));

        paintAt(0, Column(bar.value(1)));
        assertEquals(CANVAS, surface.litInRow(1));

        paintAt(0, Column(bar.value(0)));
        assertEquals(0, surface.litInRow(1));
    }

    /** A count that overshoots its own total is a full bar rather than one drawn past its own end. */
    @Test
    void valueIsClamped() {
        Progress bar = Progress().bar(Color.WHITE).value(4);
        paintAt(0, Column(bar));
        assertEquals(CANVAS, surface.litInRow(1));

        paintAt(0, Column(bar.value(-1)));
        assertEquals(0, surface.litInRow(1));
    }

    /**
     * The gradient belongs to the track, so half a bar is the first half of the ramp rather than the whole of it
     * squeezed into half the pixels.
     */
    @Test
    void aGradientIsAnchoredToTheTrackRatherThanToTheFilledPart() {
        Fill ramp = Fill.gradient(new Color(0, 0, 0), new Color(254, 0, 0), Fill.Direction.HORIZONTAL);

        paintAt(0, Column(Progress().bar(ramp).value(1)));
        int atHalfOfFull = surface.get(CANVAS / 2, 1) & 0xFF;

        paintAt(0, Column(Progress().bar(ramp).value(0.5)));
        int atEndOfHalf = surface.get(CANVAS / 2 - 1, 1) & 0xFF;

        assertTrue(atEndOfHalf < atHalfOfFull + 8, "half a bar should end near the middle of the ramp, not at its end");
        assertFalse(surface.lit(CANVAS / 2 + 4, 1), "nothing past the half should be drawn");
    }

    /** A full segmented bar reaches the end of the track, and the gaps between the pips stay unlit. */
    @Test
    void segmentsSpanTheTrackAndLeaveGaps() {
        paintAt(0, Column(Progress().bar(Color.WHITE).segments(8).value(1)));

        assertEquals(CANVAS - 7, surface.litInRow(1), "eight pips with a pixel between them");
        assertTrue(surface.lit(CANVAS - 1, 1), "the last pip should reach the end of the track");
        assertTrue(surface.lit(0, 1), "the first pip should start at the beginning of it");
    }

    /** A pip lights only once it is earned, so a bar one short of the end never draws a full one. */
    @Test
    void aPartlyEarnedPipStaysDark() {
        paintAt(0, Column(Progress().bar(Color.WHITE).segments(4).value(0.74)));
        int nearlyThree = surface.litInRow(1);
        assertFalse(surface.lit(CANVAS / 2, 1), "the third pip has not been earned at 0.74");

        paintAt(0, Column(Progress().bar(Color.WHITE).segments(4).value(0.75)));
        assertTrue(surface.lit(CANVAS / 2, 1), "and has at 0.75");
        assertTrue(surface.litInRow(1) > nearlyThree, "so a whole pip more is lit");
    }

    /** An indeterminate bar never finishes, so it has to keep asking for frames or it would sweep once and stop. */
    @Test
    void anIndeterminateBarKeepsFramesComing() {
        paintAt(0, Column(Progress().bar(Color.WHITE).indeterminate()));

        assertTrue(animator.animating(), "an indeterminate bar on screen should keep frames coming");
    }

    /** And it moves, which is the only thing that separates it from a bar stuck at a third. */
    @Test
    void theSweepTravels() {
        Progress bar = Progress().bar(Color.WHITE).indeterminate().period(1000);

        paintAt(100, Column(bar));
        int early = firstLit();

        paintAt(600, Column(bar));
        int later = firstLit();

        assertNotEquals(early, later, "the block should be somewhere else half a sweep later");
        assertTrue(later > early, "and further along rather than back at the start");
    }

    /** Animation turned off asked for no movement, so the sweep sits at the start instead of repainting forever. */
    @Test
    void withoutAnimationItStandsStill() {
        animator.enabled(false);
        Progress bar = Progress().bar(Color.WHITE).indeterminate();

        paintAt(0, Column(bar));
        assertFalse(animator.animating(), "a still bar should not keep frames coming");
    }

    private int firstLit() {
        for (int x = 0; x < CANVAS; x++) {
            if (surface.lit(x, 1)) return x;
        }
        return -1;
    }
}
