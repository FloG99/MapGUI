package de.flog99.mapgui.ui;

import java.awt.Color;

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

    /**
     * Color-to-blend memo, open-addressed on a raw {@code int} key so the per-pixel lookup allocates
     * nothing - a {@code HashMap<Integer, Blend>} boxes the key on every probe, which is a 32-byte
     * object per pixel of a dithered gradient. Both {@code computeIfAbsent} paths write and read here.
     */
    private int[] blendKeys = new int[64];
    private Blend[] blendValues = new Blend[64];
    private int blendCount;

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

        Blend blend = blendFor(color.getRGB() & 0xFFFFFF);
        int threshold = THRESHOLD[Math.floorMod(y, 4)][Math.floorMod(x, 4)];
        return blend.ratio() > threshold ? blend.high() : blend.low();
    }

    @Override
    public byte index(int argb, int x, int y) {
        // The alpha lives in the top byte; a translucent pixel drops to the base palette exactly as the
        // Color form does. Opaque pixels share the same 24-bit RGB cache key, so the packed path is a
        // bit-for-bit match for index(Color, x, y) without building a Color.
        if ((argb >>> 24) != 0xFF) return base.index(new Color(argb, true));

        Blend blend = blendFor(argb & 0xFFFFFF);
        int threshold = THRESHOLD[Math.floorMod(y, 4)][Math.floorMod(x, 4)];
        return blend.ratio() > threshold ? blend.high() : blend.low();
    }

    /** The blend for an opaque color's int key, computing on a miss. Allocation-free apart from growth. */
    private Blend blendFor(int key) {
        int mask = blendKeys.length - 1;
        int slot = (key ^ key >>> 16) & mask;
        while (true) {
            Blend value = blendValues[slot];
            if (value != null) {
                if (blendKeys[slot] == key) return value;
            } else {
                if (blendCount >= (blendKeys.length * 3) >> 2) {
                    grow();
                    mask = blendKeys.length - 1;
                    slot = (key ^ key >>> 16) & mask;
                    continue;
                }
                Blend blend = pairArgb(key);
                blendKeys[slot] = key;
                blendValues[slot] = blend;
                blendCount++;
                return blend;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** Doubles the table, reinserting everything, or drops it wholesale past {@link #MAX_CACHED}. */
    private void grow() {
        if (blendKeys.length >= MAX_CACHED) {
            blendKeys = new int[64];
            blendValues = new Blend[64];
            blendCount = 0;
            return;
        }

        int[] oldKeys = blendKeys;
        Blend[] oldValues = blendValues;
        blendKeys = new int[oldKeys.length * 2];
        blendValues = new Blend[oldKeys.length * 2];
        blendCount = 0;

        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != null) {
                blendFor(oldKeys[i]);
            }
        }
    }

    /**
     * Nearest entry, plus whichever entry best covers the error left over - the color the nearest
     * one is missing, mirrored through the target.
     *
     * <p>From a packed opaque {@code 0xRRGGBB} int, building nothing. The channel-splitting is the
     * same {@code getRGB()} lay-out the {@code Color} form used.
     */
    private Blend pairArgb(int rgb) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;

        byte nearest = closest(red, green, blue, (byte) -1);
        Color low = base.color(nearest);

        int mirrorRed = clamp(2 * red - low.getRed());
        int mirrorGreen = clamp(2 * green - low.getGreen());
        int mirrorBlue = clamp(2 * blue - low.getBlue());
        byte other = closest(mirrorRed, mirrorGreen, mirrorBlue, nearest);
        Color high = base.color(other);

        return new Blend(nearest, other, ratio(red, green, blue, low, high));
    }

    /** How far along the low-to-high line the wanted color sits, in threshold levels. */
    private static int ratio(int red, int green, int blue, Color low, Color high) {
        int spanRed = high.getRed() - low.getRed();
        int spanGreen = high.getGreen() - low.getGreen();
        int spanBlue = high.getBlue() - low.getBlue();

        long spanLength = (long) spanRed * spanRed + (long) spanGreen * spanGreen + (long) spanBlue * spanBlue;
        if (spanLength == 0) return 0;

        long along = (long) (red - low.getRed()) * spanRed
                + (long) (green - low.getGreen()) * spanGreen
                + (long) (blue - low.getBlue()) * spanBlue;

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
