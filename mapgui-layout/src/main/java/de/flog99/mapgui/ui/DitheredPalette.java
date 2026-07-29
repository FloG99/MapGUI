package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Blends two palette entries in a 4x4 threshold pattern instead of snapping to the nearest one.
 *
 * <p>The map palette is a few dozen base colors times four brightnesses, so an arbitrary ramp has
 * very few stops to land on - a green to yellow gradient snaps to about four distinct colors across
 * 110 pixels, which reads as stripes rather than a gradient. Alternating between the two nearest
 * entries per pixel trades that banding for a fine texture the eye averages out.
 *
 * <p>Finding the pair is two nearest-neighbor searches over the whole palette, so results are
 * memoized per color; per pixel it costs a lookup and a comparison.
 */
public final class DitheredPalette implements Palette {

    /** Ordered 4x4 Bayer thresholds, the classic pattern for this. */
    private static final int[][] THRESHOLD = {
            {0, 8, 2, 10},
            {12, 4, 14, 6},
            {3, 11, 1, 9},
            {15, 7, 13, 5},
    };

    private static final int LEVELS = 16;

    /**
     * A fill whose colors keep changing - an animated gradient, a scrolling rainbow - would grow
     * this without limit, so it is dropped wholesale once it gets silly. Rebuilding costs one search
     * per color and only happens on a frame that used thousands of new ones.
     */
    private static final int MAX_CACHED = 8192;

    private final Palette base;
    private final byte[] entries;
    private final Map<Integer, Blend> blends = new HashMap<>();

    /** Two entries and how far between them the wanted color sits. */
    private record Blend(byte low, byte high, int ratio) {
    }

    public DitheredPalette(Palette base) {
        this.base = base;
        this.entries = base.entries();
    }

    @Override
    public byte index(Color color) {
        return base.index(color);
    }

    @Override
    public Color color(byte index) {
        return base.color(index);
    }

    @Override
    public byte[] entries() {
        return entries;
    }

    @Override
    public byte index(Color color, int x, int y) {
        if (color.getAlpha() < 255) return base.index(color);

        if (blends.size() > MAX_CACHED) {
            blends.clear();
        }

        Blend blend = blends.computeIfAbsent(color.getRGB(), rgb -> pair(new Color(rgb)));
        int threshold = THRESHOLD[Math.floorMod(y, 4)][Math.floorMod(x, 4)];
        return blend.ratio() > threshold ? blend.high() : blend.low();
    }

    /**
     * Nearest entry, plus whichever entry best covers the error left over - the color the nearest
     * one is missing, mirrored through the target.
     */
    private Blend pair(Color wanted) {
        byte nearest = closest(wanted.getRed(), wanted.getGreen(), wanted.getBlue(), (byte) -1);
        Color low = base.color(nearest);

        int mirrorRed = clamp(2 * wanted.getRed() - low.getRed());
        int mirrorGreen = clamp(2 * wanted.getGreen() - low.getGreen());
        int mirrorBlue = clamp(2 * wanted.getBlue() - low.getBlue());
        byte other = closest(mirrorRed, mirrorGreen, mirrorBlue, nearest);
        Color high = base.color(other);

        return new Blend(nearest, other, ratio(wanted, low, high));
    }

    /** How far along the low-to-high line the wanted color sits, in threshold levels. */
    private static int ratio(Color wanted, Color low, Color high) {
        int spanRed = high.getRed() - low.getRed();
        int spanGreen = high.getGreen() - low.getGreen();
        int spanBlue = high.getBlue() - low.getBlue();

        long spanLength = (long) spanRed * spanRed + (long) spanGreen * spanGreen + (long) spanBlue * spanBlue;
        if (spanLength == 0) return 0;

        long along = (long) (wanted.getRed() - low.getRed()) * spanRed
                + (long) (wanted.getGreen() - low.getGreen()) * spanGreen
                + (long) (wanted.getBlue() - low.getBlue()) * spanBlue;

        return (int) Math.max(0, Math.min(LEVELS, along * LEVELS / spanLength));
    }

    private byte closest(int red, int green, int blue, byte exclude) {
        long best = Long.MAX_VALUE;
        byte found = exclude;

        for (byte entry : entries) {
            if (entry == exclude) continue;

            Color candidate = base.color(entry);
            long dr = red - candidate.getRed();
            long dg = green - candidate.getGreen();
            long db = blue - candidate.getBlue();
            long distance = dr * dr + dg * dg + db * db;
            if (distance < best) {
                best = distance;
                found = entry;
            }
        }
        return found;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
