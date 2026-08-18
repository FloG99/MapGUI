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

    /**
     * How far up the range the second, finer table reaches, and how wide its cells are.
     *
     * <p>Everything below 64 in all three channels gets its own table at <b>two</b> to a cell rather than eight, which is
     * the whole of the dark half of the picture for another 32 KB.
     *
     * <p>Why the dark needs it: eight to a cell is an error of up to eight <i>absolute</i>, and the palette's dark end is
     * a neutral ramp four apart - (13,13,13), (17,17,17), (21,21,21), (25,25,25). So a cell wider than that decides which
     * entry is nearest by itself, and it decides it the same way every time, because taking the low end of a cell drags
     * every colour <b>down</b> toward the darkest entries there are. Those are TERRACOTTA_BLACK at rgb(19,11,8) and
     * COLOR_BLACK at rgb(13,13,13), and the first of them is warm.
     *
     * <p>So dark colours came out <b>red</b>. Measured: dark leaves at rgb(10,16,5) landed on the terracotta where an
     * exact match gives the neutral black, and cobble in a dim room went to rgb(40,28,24) where the answer is
     * rgb(40,40,40). Visible as a red cast over anything dim, and worst on greens, which have no dark entry of their own
     * to fall back to.
     *
     * <p>The bright range is left exactly as it was. Cells are wide there too, but so is the spacing between entries, and
     * a smooth sky is the one thing that would show a change in the rounding.
     */
    private static final int DARK_TOP = 64;
    private static final int DARK_STEP = 2;
    private static final int DARK_LEVELS = DARK_TOP / DARK_STEP;

    private final Palette base;
    private final byte[] indices = new byte[LEVELS * LEVELS * LEVELS];

    /** The same again for the dark corner of the cube, at four times the resolution per axis. */
    private final byte[] dark = new byte[DARK_LEVELS * DARK_LEVELS * DARK_LEVELS];

    /**
     * Costs one nearest-entry search per cell, so a little under 33 thousand of them, and another 32 thousand for the
     * dark table. Around a fifth of a second, once, against a search per pixel per frame forever.
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

        // The middle of each cell rather than its floor, which is the other half of not dragging everything downward.
        for (int r = 0; r < DARK_LEVELS; r++) {
            for (int g = 0; g < DARK_LEVELS; g++) {
                for (int b = 0; b < DARK_LEVELS; b++) {
                    dark[(r * DARK_LEVELS + g) * DARK_LEVELS + b] = nearest(middle(r), middle(g), middle(b));
                }
            }
        }
    }

    /** The value at the middle of one of the dark table's cells. */
    private static int middle(int level) {
        return level * DARK_STEP + DARK_STEP / 2;
    }

    /**
     * The nearest entry to a dark colour, measured straight rather than through the base palette's own matcher.
     *
     * <p>The one place this class does not simply cache what it was given, and worth the words. Bukkit's matcher is
     * vanilla's: a weighted distance that counts green four times and lets blue error off lightly. That is a reasonable
     * approximation of how the eye compares two <i>colours</i>, and it goes wrong on dark <i>greys</i>, because at that
     * end the palette's neighbours differ mostly in blue. Measured: for rgb(33,33,33) it prefers rgb(40,28,24) - a brown -
     * over rgb(40,40,40), by half a percent of its own metric. For near-black it prefers TERRACOTTA_BLACK rgb(19,11,8)
     * over COLOR_BLACK rgb(13,13,13) even though the latter is plainly closer.
     *
     * <p>Which is what "it turns stuff a bit red when it's dark" was. Nothing about fidelity requires that formula: the
     * matching is <b>ours</b>, since the client draws whichever entry we hand it, and the question here is only which
     * entry looks most like the colour.
     *
     * <p>What it uses instead depends on whether the colour has a hue to keep, and that is the whole of it:
     *
     * <ul>
     *   <li><b>Grey enough that a hue would be invented</b> - see {@link #NEUTRAL_SPREAD} - and the difference is split
     *       into brightness and colour, with the colour part weighed more heavily (see {@link #CHROMA}). Getting a dim
     *       stone's shade slightly wrong is barely visible; giving it a brown tint is the thing being complained about.
     *   <li><b>Anything with a colour of its own</b> and it is plain distance, which is what stops the rule above turning
     *       into its own bug: weighing hue heavily everywhere made a dark purple reach for a <i>bright</i> purple to keep
     *       it, drawing rgb(16,0,50) at rgb(67,33,94). A dark thing has to stay dark, and a palette with no dark purple in
     *       it has to lose the purple rather than the darkness.
     * </ul>
     *
     * <p>Above 64 the base palette's matcher is untouched, and so is every colour that reaches it.
     */
    private byte nearest(int red, int green, int blue) {
        byte[] available = base.entries();
        // Whether this colour has a hue of its own worth keeping, or is grey enough that any hue would be invented.
        boolean grey = Math.max(Math.max(red, green), blue) - Math.min(Math.min(red, green), blue) <= NEUTRAL_SPREAD;
        long best = Long.MAX_VALUE;
        byte found = available.length == 0 ? 0 : available[0];

        for (byte entry : available) {
            Color candidate = base.color(entry);
            if (candidate == null) continue;

            long dr = red - candidate.getRed();
            long dg = green - candidate.getGreen();
            long db = blue - candidate.getBlue();

            // Three times the mean error, so that everything below stays in whole numbers.
            long brightness = dr + dg + db;
            long redAgainstGreen = dr - dg;
            long greenAgainstBlue = dg - db;
            long colour = redAgainstGreen * redAgainstGreen + greenAgainstBlue * greenAgainstBlue;

            long distance = grey
                    ? brightness * brightness + CHROMA * 9 * colour
                    : dr * dr + dg * dg + db * db;
            if (distance < best) {
                best = distance;
                found = entry;
            }
        }
        return found;
    }

    /**
     * How much more a colour error counts than a brightness error, when matching a dark colour.
     *
     * <p>Two, which is enough to settle the cases that were wrong and not enough to reach for a bright entry of the right
     * hue over a dark one of nearly the right hue. The measured case it was chosen against: rgb(32,28,28), a dim stone,
     * sits almost exactly between rgb(25,25,25) and rgb(40,28,24) - a grey and a brown, 67 and 80 away by plain distance,
     * so the brown won as soon as rounding nudged the input. Weighing the colour part twice puts the grey four times
     * clearer, while dim oak planks still land on the brown they are, and dark leaves still land on the neutral black
     * rather than the warm one, having no dark green to go to.
     */
    private static final int CHROMA = 2;

    /**
     * How far apart a colour's channels may be and still count as grey, for the rule above.
     *
     * <p>Ten, which takes in the things a dim room is actually made of - stone, cobble, deepslate, a shadowed path - and
     * leaves out anything anybody would call coloured. Oak planks at rgb(28,22,13) are fifteen apart and keep their brown
     * by plain distance.
     */
    private static final int NEUTRAL_SPREAD = 10;

    @Override
    public byte index(Color color) {
        return index(color.getRGB());
    }

    /** The same from a packed int, which is what an image hands over and costs no allocation to ask about. */
    public byte index(int argb) {
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;

        // One test for all three, since a colour is dark only if none of its channels is bright. See DARK_TOP.
        if ((r | g | b) < DARK_TOP) {
            return dark[((r / DARK_STEP) * DARK_LEVELS + g / DARK_STEP) * DARK_LEVELS + b / DARK_STEP];
        }
        return indices[((r >> (8 - BITS)) << BITS | g >> (8 - BITS)) << BITS | b >> (8 - BITS)];
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
