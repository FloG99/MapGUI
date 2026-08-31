package de.flog99.mapgui.ui;

import org.jetbrains.annotations.ApiStatus;

/**
 * Turns colors into map palette indices, one pixel at a time or a whole rect at once.
 *
 * <p>Two entry points rather than one, because the two dither families genuinely cannot share a shape. An
 * ordered mode is a function of a color and a position, so it answers a pixel at a time and can be handed to
 * anything that draws - it is a {@link Palette}. An error diffusion mode hands each pixel's leftover error to
 * neighbors that have not been decided yet, so it needs every color in the rect before it can answer for any of
 * them.
 *
 * <p>{@link PaletteLut} has quantized a whole frame in one call since long before this interface existed; this
 * is that seam widened, not a new concept.
 *
 * <p>Which one a caller can use depends on what it has:
 *
 * <ul>
 *   <li><b>One color at a time</b> - a fill, a shape, a glyph, terrain. {@link #perPixel()}, and a diffusing
 *       mode has to stand something in.
 *   <li><b>A whole rect of colors</b> - {@link Painter#image}, a GIF or video frame at decode, a camera shot.
 *       {@link #quantize}, which is where the diffusing modes earn their keep. Once at decode rather than once
 *       per repaint per viewer.
 * </ul>
 */
@ApiStatus.Experimental
public interface Quantizer {

    /**
     * This quantizer as a palette, for callers that have one color and a position.
     *
     * <p>For the ordered family this is the mode itself and nothing is lost. For a diffusing mode it is the
     * nearest thing that works without a rect, which is {@link Dither#ORDERED_FINE} - ask {@link #diffuses()}
     * if the difference matters.
     */
    Palette perPixel();

    /**
     * Quantizes a rect of packed ARGB into palette indices, row by row.
     *
     * <p>{@code out} must be at least as long as {@code argb}, and both are {@code width * height} in row-major
     * order.
     *
     * <p>A pixel fainter than {@link #OPAQUE_ENOUGH} becomes {@link #TRANSPARENT} rather than being matched to
     * anything, and takes no part in diffusion in either direction - see {@link #TRANSPARENT}.
     */
    void quantize(int[] argb, int width, int height, byte[] out);

    /**
     * Whether {@link #perPixel()} is a faithful stand-in for this quantizer or only the closest available one.
     *
     * <p>True for the error diffusion family, whose per-pixel form is a different mode.
     */
    boolean diffuses();

    /**
     * The index that means "leave this pixel alone", which is the palette's transparent entry.
     *
     * <p>Zero, matching {@code Frames.TRANSPARENT}: nothing opaque ever matches to it, so it is free to mean
     * this. Diffusion must neither push error into such a pixel nor pull error out of one - a transparent GIF
     * whose error leaked across its own edge grows a halo of whatever color was next to the hole, which is
     * visible immediately and impossible to explain afterwards.
     */
    byte TRANSPARENT = 0;

    /**
     * How faint a pixel has to be to count as see-through.
     *
     * <p>Half. Scaling an image blends alpha at the edges of a transparent shape, and half a pixel of
     * translucency cannot be shown in a palette that has no alpha - so the edge is decided one way or the other.
     */
    int OPAQUE_ENOUGH = 128;

    /** Nearest entry, no dithering. */
    static Quantizer of(Palette palette) {
        return of(palette, Dither.NONE);
    }

    /** The quantizer for a mode, over the palette that will draw the result. A null mode means {@link Dither#NONE}. */
    static Quantizer of(Palette palette, Dither mode) {
        if (mode == null || mode == Dither.NONE) return new Ordered(palette);
        if (mode.diffuses()) return new ErrorDiffusion(palette, mode);

        return new Ordered(new DitheredPalette(palette, OrderedMatrix.of(mode)));
    }

    /**
     * Any palette, asked pixel by pixel. Which covers {@link Dither#NONE} as well as the ordered family - the
     * difference between them is entirely inside the palette it holds.
     */
    record Ordered(Palette perPixel) implements Quantizer {

        @Override
        public void quantize(int[] argb, int width, int height, byte[] out) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int at = y * width + x;
                    int pixel = argb[at];
                    // Forced opaque rather than passed as it is: a palette drops to plain matching for anything
                    // translucent, and a pixel this far along has already been decided to be one or the other.
                    out[at] = (pixel >>> 24) < OPAQUE_ENOUGH ? TRANSPARENT : perPixel.index(pixel | 0xFF000000, x, y);
                }
            }
        }

        @Override
        public boolean diffuses() {
            return false;
        }
    }
}
