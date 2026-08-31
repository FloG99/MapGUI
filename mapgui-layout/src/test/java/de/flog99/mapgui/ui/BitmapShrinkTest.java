package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A picture given less room than it wants: cropped by default, shrunk when asked.
 *
 * <p>The case this is for is not somebody sizing an image down on purpose - it is a column running out of height
 * and taking it from whatever will give. An image gives by losing its bottom rows, silently, and a caller
 * stating a bigger font is enough to cause it.
 */
class BitmapShrinkTest {

    private static final LayoutContext CONTEXT = new LayoutContext(TestFont.INSTANCE);

    private static BufferedImage picture(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * What the node asks for, which is what a parent lays out against.
     *
     * <p>Not {@code bounds()}: that is whatever rect the parent handed down, so it would report the box back
     * rather than the size the picture wanted inside it.
     */
    private static Measured wants(Node node, int width, int height) {
        return node.measure(CONTEXT, width, height);
    }

    /** What it has always done, and still does unless told otherwise. */
    @Test
    void byDefaultItKeepsItsSizeAndLosesWhatDoesNotFit() {
        Measured wanted = wants(new Bitmap(picture(32, 32)), 32, 12);

        assertEquals(32, wanted.width());
        assertEquals(12, wanted.height(), "cropped: it takes what is there and draws the part that fits");
    }

    /** Told to shrink, it keeps its shape rather than filling a box of the wrong proportions. */
    @Test
    void shrinkingKeepsTheProportions() {
        Measured wanted = wants(new Bitmap(picture(32, 32)).shrinkToFit(), 32, 12);

        assertEquals(12, wanted.height());
        assertEquals(12, wanted.width(), "a square picture in a wide flat box is a small square, not a wide one");
    }

    @Test
    void aWidePictureIsLimitedByWhicheverAxisRunsOutFirst() {
        Measured wanted = wants(new Bitmap(picture(64, 16)).shrinkToFit(), 32, 32);

        assertEquals(32, wanted.width());
        assertEquals(8, wanted.height(), "four to one stays four to one");
    }

    /**
     * Never enlarged. Scaling up at this resolution only blurs, and a small picture in a big box is a small
     * picture - the same rule the video decoder follows.
     */
    @Test
    void aPictureSmallerThanItsBoxIsLeftAlone() {
        Measured wanted = wants(new Bitmap(picture(8, 8)).shrinkToFit(), 64, 64);

        assertEquals(8, wanted.width());
        assertEquals(8, wanted.height());
    }

    /** Nothing to draw into is not something to divide by. */
    @Test
    void noRoomAtAllIsNotACrash() {
        Measured wanted = wants(new Bitmap(picture(32, 32)).shrinkToFit(), 0, 0);

        assertTrue(wanted.width() <= 0 || wanted.height() <= 0);
    }
}
