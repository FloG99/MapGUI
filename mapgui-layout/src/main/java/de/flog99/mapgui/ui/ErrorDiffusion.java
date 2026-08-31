package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.Arrays;

/**
 * Quantizes a whole rect by handing each pixel's leftover error to its neighbors.
 *
 * <p>Nearest-entry matching throws away the difference between what was asked for and what the palette could
 * say. This keeps it: whatever a pixel was short of is added to the pixels after it, so a run of colors that
 * each round the same way instead round different ways and average out to the color that was wanted. Which is
 * why it cannot be a {@link Palette}: the error has to reach pixels nobody has drawn yet, and on a
 * {@link Surface} of palette bytes the next draw call would paint over them.
 *
 * <p>Transparency is the part that is easy to get wrong, so it is stated plainly. A see-through pixel is
 * {@link Quantizer#TRANSPARENT} and takes no part in this in either direction: nothing reads the error pushed
 * into it, so that error is dropped, and it pushes none of its own. Anything else grows a halo around every
 * transparent hole in the picture, in whatever color happened to border it.
 */
final class ErrorDiffusion implements Quantizer {

    private final Palette palette;

    /** Where each share of the error goes, and how much of it. See {@link #kernelFor}. */
    private final int[] dx;
    private final int[] dy;
    private final int[] weight;

    /**
     * What the weights are shares <i>of</i>, which is not always their sum.
     *
     * <p>{@link Dither#ATKINSON} deliberately hands out six eighths and drops the rest - see its javadoc for
     * why that is a virtue on a palette this sparse.
     */
    private final int total;

    /**
     * Each neighbour's share of the error, in {@link #SCALE} units, worked out once.
     *
     * <p>{@code weight[i] * SCALE / total} is fixed for the life of the kernel, and {@link #spread} is the
     * innermost loop of the whole path - once per neighbour per pixel - so it is not the place to divide.
     */
    private final int[] share;

    /** How many rows below the current one the kernel reaches, which is how many error rows have to be kept. */
    private final int reach;

    /**
     * The palette's colors by index, so the inner loop can ask what it actually got without building a
     * {@link Color} per pixel - which on a 128x128 frame is sixteen thousand objects per frame.
     */
    private final int[] entryRgb = new int[256];

    /** Built only if something with one color at a time asks - see {@link #perPixel()}. */
    private Palette standIn;

    ErrorDiffusion(Palette palette, Dither mode) {
        this.palette = palette;
        int[][] kernel = kernelFor(mode);
        this.dx = kernel[0];
        this.dy = kernel[1];
        this.weight = kernel[2];
        this.total = kernel[3][0];

        this.share = new int[weight.length];
        for (int i = 0; i < weight.length; i++) this.share[i] = weight[i] * SCALE / total;

        int deepest = 0;
        for (int down : dy) deepest = Math.max(deepest, down);
        this.reach = deepest;

        for (int i = 0; i < entryRgb.length; i++) {
            Color color = palette.color((byte) i);
            entryRgb[i] = color == null ? 0 : color.getRGB();
        }
    }

    /**
     * {@link Dither#ORDERED_FINE} over the same palette, which is the closest an ordered mode gets to this.
     *
     * <p>There is no rect here to diffuse over, and inventing one - a scratch buffer per node rect - would cost
     * an {@code int[]} per box drawn to make a flat fill or a glyph edge very slightly more faithful. The fine
     * tile is chosen over the 4x4 because whatever asked for diffusion asked for the smoothest result available.
     */
    @Override
    public Palette perPixel() {
        if (standIn == null) {
            standIn = new DitheredPalette(palette, OrderedMatrix.bayer8());
        }
        return standIn;
    }

    @Override
    public boolean diffuses() {
        return true;
    }

    /**
     * Error is carried as a fixed-point fraction of a channel step rather than as whole steps.
     *
     * <p>Because the shares are small: seven sixteenths of an error of three is nought in integers, so a gentle
     * ramp - which is exactly the content this exists for - would diffuse nothing at all. 256 keeps every share
     * of every error worth keeping, and nothing here can overflow an {@code int}.
     */
    private static final int SCALE = 256;

    @Override
    public void quantize(int[] argb, int width, int height, byte[] out) {
        // One row per row the kernel reaches into, recycled: a row is cleared as soon as it is done with, at
        // which point it is the first row nothing has written to yet.
        int rows = reach + 1;
        int[] error = new int[rows * width * 3];

        for (int y = 0; y < height; y++) {
            int here = (y % rows) * width * 3;
            for (int x = 0; x < width; x++) {
                int at = y * width + x;
                int pixel = argb[at];
                if ((pixel >>> 24) < OPAQUE_ENOUGH) {
                    // Whatever error was pushed here dies with the row, which is the whole of the halo rule.
                    out[at] = TRANSPARENT;
                    continue;
                }

                int channel = here + x * 3;
                int red = clamp((pixel >> 16 & 0xFF) + rounded(error[channel]));
                int green = clamp((pixel >> 8 & 0xFF) + rounded(error[channel + 1]));
                int blue = clamp((pixel & 0xFF) + rounded(error[channel + 2]));

                byte chosen = palette.index(0xFF000000 | red << 16 | green << 8 | blue, x, y);
                out[at] = chosen;

                // Against the clamped value, not the original: error the palette could never absorb is dropped
                // at the ends of the range rather than accumulating into a streak across the picture.
                int got = entryRgb[chosen & 0xFF];
                spread(error, rows, width, height, x, y,
                        red - (got >> 16 & 0xFF), green - (got >> 8 & 0xFF), blue - (got & 0xFF)
                );
            }
            Arrays.fill(error, here, here + width * 3, 0);
        }
    }

    private void spread(int[] error, int rows, int width, int height, int x, int y, int red, int green, int blue) {
        for (int i = 0; i < weight.length; i++) {
            int toX = x + dx[i];
            int toY = y + dy[i];
            if (toX < 0 || toX >= width || toY >= height) continue;

            int channel = (toY % rows) * width * 3 + toX * 3;
            int part = share[i];
            error[channel] += red * part;
            error[channel + 1] += green * part;
            error[channel + 2] += blue * part;
        }
    }

    /** Nearest whole channel step, rounding a negative error the same way as a positive one. */
    private static int rounded(int fixed) {
        return fixed >= 0 ? (fixed + SCALE / 2) / SCALE : -((-fixed + SCALE / 2) / SCALE);
    }

    private static int clamp(int value) {
        return Math.clamp(value, 0, 255);
    }

    /**
     * The kernels, as {@code dx}, {@code dy}, {@code weight} and {@code total} rows.
     *
     * <p>All three run left to right and top to bottom, so every neighbor they write to is one nobody has
     * decided yet. None of them serpentine: alternating direction hides the diagonal seam a little better on a
     * photograph, and it would make a frame's dither depend on which row it started from, which for a wall
     * drawn in tiles is a seam of its own.
     */
    private static int[][] kernelFor(Dither mode) {
        return switch (mode) {
            // Everything forward, split four ways. The most faithful and the most prone to smearing.
            case FLOYD_STEINBERG -> new int[][]{
                    {1, -1, 0, 1},
                    {0, 1, 1, 1},
                    {7, 3, 5, 1},
                    {16},
            };
            // Six neighbors reaching two pixels out, sharing six eighths of the error between them.
            case ATKINSON -> new int[][]{
                    {1, 2, -1, 0, 1, 0},
                    {0, 0, 1, 1, 1, 2},
                    {1, 1, 1, 1, 1, 1},
                    {8},
            };
            // Half to the right and a quarter to each of two below, so error travels one pixel and stops.
            case SIERRA_LITE -> new int[][]{
                    {1, -1, 0},
                    {0, 1, 1},
                    {2, 1, 1},
                    {4},
            };
            default -> throw new IllegalArgumentException(mode + " does not diffuse");
        };
    }
}
