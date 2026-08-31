package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A/B on flicker: the same noisy footage quantized with and without holding the last frame, and the pixels that
 * changed between frames counted rather than argued about.
 *
 * <p>Counting changed pixels is not a proxy for the thing that matters, it <i>is</i> the thing that matters
 * twice over - it is what shimmers to a viewer, and it is what decides how much of the map has to be sent.
 */
class SteadyAbTest {

    /** A ramp of one hue plus greys, spaced the way the map palette spaces its shades: tens apart, not ones. */
    private static final Color[] SPARSE = {
            null,
            new Color(0, 0, 0),
            new Color(40, 40, 40),
            new Color(80, 80, 80),
            new Color(120, 120, 120),
            new Color(160, 160, 160),
            new Color(200, 200, 200),
            new Color(240, 240, 240),
            new Color(60, 100, 160),
            new Color(90, 130, 190),
    };

    private static final Palette BASE = new Palette() {
        @Override
        public byte index(Color color) {
            int best = 1;
            long closest = Long.MAX_VALUE;
            for (int i = 1; i < SPARSE.length; i++) {
                long dr = color.getRed() - SPARSE[i].getRed();
                long dg = color.getGreen() - SPARSE[i].getGreen();
                long db = color.getBlue() - SPARSE[i].getBlue();
                long distance = dr * dr + dg * dg + db * db;
                if (distance < closest) {
                    closest = distance;
                    best = i;
                }
            }
            return (byte) best;
        }

        @Override
        public Color color(byte index) {
            int at = index & 0xFF;
            return at < SPARSE.length ? SPARSE[at] : null;
        }
    };

    /**
     * This palette's neighbours are 40 apart in every channel, which is 4800 squared - far coarser than the real
     * map palette, whose nearest entry is a median of 186 away. So the mechanism is tested at a threshold scaled
     * to the palette under test, well under its own neighbour spacing for the same reason
     * {@link Steady#DEFAULT_THRESHOLD} is well under the real one's.
     */
    private static final int THRESHOLD = 1200;

    private static Quantizer steady(Dither mode) {
        return new Steady(Quantizer.of(BASE, mode), BASE, THRESHOLD);
    }

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int FRAMES = 30;

    /**
     * A still shot with sensor noise on it: the picture never moves, so every changed pixel is a lie.
     *
     * <p>The noise is deliberately smaller than the gap between palette entries. That is the whole case - it is
     * invisible in full colour and turns into a pixel flipping between two entries once it is rounded.
     */
    @Test
    void holdsANoisyStillInsteadOfLettingItShimmer() {
        int plain = changesOver(Quantizer.of(BASE, Dither.NONE), 6, false);
        int held = changesOver(steady(Dither.NONE), 6, false);

        System.out.printf("still with noise: plain %d changed pixels, steady %d%n", plain, held);
        assertTrue(held < plain / 4,
                "holding should remove most of it, not a little: plain " + plain + ", steady " + held);
    }

    /** The same for a dithered mode, since the flicker is in the rounding rather than in the dithering. */
    @Test
    void holdsADitheredNoisyStillToo() {
        int plain = changesOver(Quantizer.of(BASE, Dither.FLOYD_STEINBERG), 6, false);
        int held = changesOver(steady(Dither.FLOYD_STEINBERG), 6, false);

        System.out.printf("dithered still with noise: plain %d changed pixels, steady %d%n", plain, held);
        assertTrue(held < plain, "plain " + plain + ", steady " + held);
    }

    /**
     * The other half, and the one that matters if the threshold is wrong: a moving picture still moves.
     *
     * <p>Fewer changed pixels is the <i>point</i> here too - a pan across a coarse palette genuinely does not
     * change every pixel's nearest entry every frame - so counting changes cannot tell holding from smearing.
     * What can is fidelity: how far the pixels actually shown sit from the colours asked for. Smearing is
     * exactly that error growing, and it would grow frame on frame as a held pixel fell further behind.
     */
    @Test
    void realMovementIsNotSmearedAway() {
        int plainChanges = changesOver(Quantizer.of(BASE, Dither.NONE), 0, true);
        int heldChanges = changesOver(steady(Dither.NONE), 0, true);
        double plainError = errorOver(Quantizer.of(BASE, Dither.NONE), true);
        double heldError = errorOver(steady(Dither.NONE), true);

        System.out.printf("moving picture: plain %d changed pixels / error %.1f, steady %d / error %.1f%n",
                plainChanges, plainError, heldChanges, heldError);

        assertTrue(heldChanges > plainChanges / 4,
                "the picture has to keep moving: plain " + plainChanges + ", steady " + heldChanges);
        assertTrue(heldError < plainError * 1.6,
                "holding must not cost much fidelity: plain " + plainError + ", steady " + heldError);
    }

    /**
     * The smearing check proper: error on the <b>last</b> frame of a long pan, where anything that accumulates
     * has had thirty frames to accumulate in.
     */
    @Test
    void holdingDoesNotDriftFurtherBehindAsItGoes() {
        double plainFirst = errorOnFrame(Quantizer.of(BASE, Dither.NONE), 1);
        double heldFirst = errorOnFrame(steady(Dither.NONE), 1);
        double plainLast = errorOnFrame(Quantizer.of(BASE, Dither.NONE), FRAMES - 1);
        double heldLast = errorOnFrame(steady(Dither.NONE), FRAMES - 1);

        System.out.printf("pan: frame 1 plain %.1f steady %.1f, frame %d plain %.1f steady %.1f%n",
                plainFirst, heldFirst, FRAMES - 1, plainLast, heldLast);

        double drift = (heldLast - plainLast) - (heldFirst - plainFirst);
        assertTrue(drift < plainFirst * 0.25,
                "the gap must not widen over the clip - it drifted by " + drift);
    }

    /** Mean squared distance between the colours shown and the colours asked for, over the whole clip. */
    private double errorOver(Quantizer quantizer, boolean moving) {
        Random random = new Random(7);
        double total = 0;
        for (int at = 0; at < FRAMES; at++) {
            total += error(quantizer, frame(at, 0, moving, random));
        }
        return total / FRAMES;
    }

    /** The same on one frame, having played the clip up to it so anything stateful is warmed the same way. */
    private double errorOnFrame(Quantizer quantizer, int wanted) {
        Random random = new Random(7);
        double last = 0;
        for (int at = 0; at <= wanted; at++) {
            last = error(quantizer, frame(at, 0, true, random));
        }
        return last;
    }

    private double error(Quantizer quantizer, int[] argb) {
        byte[] out = new byte[argb.length];
        quantizer.quantize(argb, WIDTH, HEIGHT, out);

        double total = 0;
        for (int at = 0; at < argb.length; at++) {
            Color shown = BASE.color(out[at]);
            if (shown == null) continue;

            int dr = (argb[at] >> 16 & 0xFF) - shown.getRed();
            int dg = (argb[at] >> 8 & 0xFF) - shown.getGreen();
            int db = (argb[at] & 0xFF) - shown.getBlue();
            total += dr * dr + dg * dg + db * db;
        }
        return total / argb.length;
    }

    /** With nothing to hold against, the first frame through a steady quantizer is the ordinary one. */
    @Test
    void theFirstFrameIsExactlyWhatItWouldHaveBeen() {
        int[] frame = frame(0, 4, false, new Random(1));
        byte[] plain = new byte[frame.length];
        byte[] held = new byte[frame.length];

        Quantizer.of(BASE, Dither.NONE).quantize(frame, WIDTH, HEIGHT, plain);
        steady(Dither.NONE).quantize(frame, WIDTH, HEIGHT, held);

        assertArrayEquals(plain, held);
    }

    /** A source that changes size starts again rather than measuring against a frame of another shape. */
    @Test
    void aSizeChangeStartsAgain() {
        Quantizer steady = steady(Dither.NONE);

        byte[] first = new byte[WIDTH * HEIGHT];
        steady.quantize(frame(0, 4, false, new Random(2)), WIDTH, HEIGHT, first);

        int[] smaller = new int[16 * 16];
        java.util.Arrays.fill(smaller, 0xFF808080);
        byte[] out = new byte[16 * 16];
        steady.quantize(smaller, 16, 16, out);

        byte expected = BASE.index(new Color(128, 128, 128));
        for (byte value : out) assertEquals(expected, value, "nothing from the larger frame may leak in");
    }

    /** How many pixels differ from the frame before, summed over the whole clip. */
    private int changesOver(Quantizer quantizer, int noise, boolean moving) {
        Random random = new Random(7);
        byte[] previous = null;
        int changed = 0;

        for (int at = 0; at < FRAMES; at++) {
            byte[] out = new byte[WIDTH * HEIGHT];
            quantizer.quantize(frame(at, noise, moving, random), WIDTH, HEIGHT, out);

            if (previous != null) {
                for (int i = 0; i < out.length; i++) {
                    if (out[i] != previous[i]) changed++;
                }
            }
            previous = out;
        }
        return changed;
    }

    /**
     * One frame of a vertical ramp, optionally drifting sideways, optionally with noise on it.
     *
     * @param noise  peak wobble per channel, in levels. Smaller than the palette's own spacing on purpose
     * @param moving whether the picture itself changes, which is what must survive being held
     */
    private int[] frame(int at, int noise, boolean moving, Random random) {
        int[] argb = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int shift = moving ? at * 4 : 0;
                int level = Math.clamp((x + shift) * 255 / WIDTH, 0, 255);
                int wobbled = Math.clamp(level + (noise == 0 ? 0 : random.nextInt(noise * 2 + 1) - noise), 0, 255);
                argb[y * WIDTH + x] = 0xFF000000 | wobbled << 16 | wobbled << 8 | wobbled;
            }
        }
        return argb;
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
