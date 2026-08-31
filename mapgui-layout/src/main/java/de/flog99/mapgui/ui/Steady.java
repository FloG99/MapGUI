package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * A {@link Quantizer} that keeps the entry it chose last frame when the new one would barely differ.
 *
 * <p><b>What it is for.</b> A pixel in real footage wobbles a little between frames - sensor noise, compression -
 * and in full colour that is invisible. Rounded to a palette of a couple of hundred entries it is not: a pixel
 * sitting between two entries flips back and forth between them every frame, and the picture shimmers in a way
 * the source never did.
 *
 * <p>That costs twice. It looks wrong, and MapGUI sends only the part of a map that changed - so flickering
 * pixels scattered across a frame make the changed part the whole frame, every frame. See
 * {@code docs/performance.md} for what that is worth in bytes.
 *
 * <p><b>What it does.</b> Before handing back a pixel it looks at the entry it handed back for that pixel last
 * time. If the newly chosen entry is within {@link #DEFAULT_THRESHOLD} of it, the old one is kept. So a pixel
 * only moves when it has somewhere worth moving to, and everything that was only ever noise holds still.
 *
 * <p>It wraps whatever mode is underneath rather than replacing one, because the flicker is in the rounding and
 * not in the dithering: an ordered mode flickers on a moving gradient and a diffusing one flickers wherever its
 * error lands differently, and both are fixed by the same memory of what was there before.
 *
 * <p><b>Stateful, so one of these belongs to one stream of frames.</b> Sharing it between two videos would have
 * each judging its pixels against the other's. {@link Quantizer#of} hands back stateless quantizers that are
 * safe to share; this one is built per source on purpose, and is not thread-safe.
 */
final class Steady implements Quantizer {

    /**
     * How close two entries have to be before a change between them is treated as noise.
     *
     * <p>How much worse the entry already shown may be, in squared RGB, before it is worth replacing. Which is
     * the same number as the most extra error a held pixel can ever carry.
     *
     * <p>Measured against the map palette rather than picked. Over its 244 opaque entries the distance from one
     * entry to the <b>nearest other entry</b> has a median of 186, so this sits deliberately below that: a pixel
     * may stay put through less than the gap between two neighbouring entries, and never through a whole one.
     * About seven levels in one channel, which is under the noise floor of any real footage.
     */
    static final int DEFAULT_THRESHOLD = 150;

    private final Quantizer inner;
    private final Palette palette;
    private final int threshold;

    /** Last frame's output, and the shape it was, so a source that changes size starts again rather than lying. */
    private byte[] previous;
    private int previousWidth;
    private int previousHeight;

    Steady(Quantizer inner, Palette palette, int threshold) {
        this.inner = inner;
        this.palette = palette;
        this.threshold = Math.max(0, threshold);
    }

    @Override
    public Palette perPixel() {
        // Nothing to hold against: a caller with one colour and no rect has no previous frame either.
        return inner.perPixel();
    }

    @Override
    public boolean diffuses() {
        return inner.diffuses();
    }

    @Override
    public void quantize(int[] argb, int width, int height, byte[] out) {
        inner.quantize(argb, width, height, out);

        if (previous != null && previousWidth == width && previousHeight == height && previous.length == out.length) {
            hold(argb, out);
        }
        if (previous == null || previous.length != out.length) {
            previous = new byte[out.length];
        }
        System.arraycopy(out, 0, previous, 0, out.length);
        previousWidth = width;
        previousHeight = height;
    }

    /** Puts back last frame's entry wherever changing would not be worth the bytes. */
    private void hold(int[] argb, byte[] out) {
        for (int at = 0; at < out.length; at++) {
            byte was = previous[at];
            byte now = out[at];
            if (was == now) continue;

            // A pixel that appeared or disappeared is a real change however small the colours look, and the
            // transparent entry is not a colour to measure a distance to.
            if (was == TRANSPARENT || now == TRANSPARENT) continue;

            if (worthKeeping(argb[at], was, now)) out[at] = was;
        }
    }

    /**
     * Whether the entry already shown is close enough to what is wanted that changing is not worth it.
     *
     * <p>Both distances are to the wanted colour, so what is bounded is how wrong the pixel on screen is, rather
     * than how far apart two entries happen to be. A held pixel is at most {@code threshold} worse than it would
     * have been, this frame and every frame, with nothing carried forward into the next.
     */
    private boolean worthKeeping(int wanted, byte was, byte now) {
        Color before = palette.color(was);
        Color after = palette.color(now);
        if (before == null || after == null) return false;

        return distance(wanted, before) - distance(wanted, after) <= threshold;
    }

    private static long distance(int argb, Color entry) {
        long dr = (argb >> 16 & 0xFF) - entry.getRed();
        long dg = (argb >> 8 & 0xFF) - entry.getGreen();
        long db = (argb & 0xFF) - entry.getBlue();
        return dr * dr + dg * dg + db * db;
    }
}
