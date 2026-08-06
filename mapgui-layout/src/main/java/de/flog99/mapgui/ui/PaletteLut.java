package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * Any palette, answered from a fixed table instead of a search.
 *
 * <p>Matching a color is a nearest-entry search over a couple of hundred entries, which is brutal per pixel and
 * the reason palettes here used to memoize into a map. A map is fine for a menu drawn from a handful of colors
 * and no use at all for a photograph, a video frame or a camera shot: it grows without limit, allocates a boxed
 * key per lookup, and cannot be read from more than one thread.
 *
 * <p>Five bits a channel - 32768 entries, one byte each, so 32 KB. That is finer than the map palette can
 * express anyway: a couple of hundred entries across the whole cube means the nearest one rarely changes inside
 * an 8x8x8 cell. Immutable once built, so any number of threads can read it.
 */
public final class PaletteLut implements Palette {

    private static final int BITS = 5;
    private static final int LEVELS = 1 << BITS;

    private final Palette base;
    private final byte[] indices = new byte[LEVELS * LEVELS * LEVELS];

    /**
     * Costs one nearest-entry search per cell, so a little under 33 thousand of them. Around a tenth of a
     * second, once, against a search per pixel per frame forever.
     */
    public PaletteLut(Palette base) {
        this.base = base;

        for (int r = 0; r < LEVELS; r++) {
            for (int g = 0; g < LEVELS; g++) {
                for (int b = 0; b < LEVELS; b++) {
                    Color color = new Color(expand(r), expand(g), expand(b));
                    indices[(r << BITS | g) << BITS | b] = base.index(color);
                }
            }
        }
    }

    @Override
    public byte index(Color color) {
        return index(color.getRGB());
    }

    /** The same from a packed int, which is what an image hands over and costs no allocation to ask about. */
    public byte index(int argb) {
        int r = (argb >> 16 & 0xFF) >> (8 - BITS);
        int g = (argb >> 8 & 0xFF) >> (8 - BITS);
        int b = (argb & 0xFF) >> (8 - BITS);
        return indices[(r << BITS | g) << BITS | b];
    }

    /** Straight off the bits, which is the whole point of the table. */
    @Override
    public byte index(int argb, int x, int y) {
        return index(argb);
    }

    @Override
    public Color color(byte index) {
        return base.color(index);
    }

    @Override
    public byte[] entries() {
        return base.entries();
    }

    /**
     * Quantizes a whole frame into map indices, taking the nearest entry to each pixel and nothing cleverer.
     *
     * <p>Undithered, tried twice and rejected on how it looks. Dithering two entries that straddle a color does bring
     * the mean error down measurably - spruce leaves 15.0 to 10.8, stone 8.4 to 2.4 - but mean error is not what
     * anybody looks at, and over 128 pixels the pattern reads as grain.
     *
     * <p>Which leaves the hue limit standing, worth writing down rather than rediscovering: spruce leaves are a
     * neutral dark green and every dark green in the palette skews warm, so they land on an olive. A plain RGB metric
     * and a perceptual one pick the same entry, because it genuinely is the closest - the palette cannot say that
     * color, and reaching further for a better hue would be worse everywhere else.
     */
    public void quantize(int[] argb, byte[] out) {
        for (int i = 0; i < argb.length; i++) {
            out[i] = index(argb[i]);
        }
    }

    private static int expand(int level) {
        return Math.min(255, level * 255 / (LEVELS - 1));
    }
}
