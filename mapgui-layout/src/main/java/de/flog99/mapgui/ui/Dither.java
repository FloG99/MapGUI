package de.flog99.mapgui.ui;

import org.jetbrains.annotations.ApiStatus;

/**
 * How a color the map palette cannot say is turned into one it can.
 *
 * <p>The palette is a few dozen hues times four brightnesses, so most colors are not in it and something has to
 * be done about the difference. Snapping to the nearest entry is the honest minimum, and it is what every other
 * mode here is measured against: a green to yellow ramp snaps to about four distinct colors across 110 pixels,
 * which reads as stripes. The rest spend some texture to buy back the shades in between.
 *
 * <p>The modes fall into two families, and the split is not a taxonomy - it decides where a mode can be used at
 * all. A {@link Surface} holds palette bytes rather than colors, and a {@link Painter} matches each pixel as it
 * draws it. So a mode that has to hand its leftover error to pixels that have not been drawn yet cannot run
 * there: the next draw call paints over the very neighbors it was counting on.
 *
 * <ul>
 *   <li><b>Ordered</b> - {@link #ORDERED}, {@link #ORDERED_FINE}, {@link #BLUE_NOISE}. A function of the color
 *       and of where the pixel is going, and of nothing else. Usable everywhere: fills, shapes, text, images,
 *       video decode.
 *   <li><b>Error diffusion</b> - {@link #FLOYD_STEINBERG}, {@link #ATKINSON}, {@link #SIERRA_LITE}. Needs the
 *       whole rect of colors up front, so it is only usable where there is one: {@link Painter#image},
 *       a GIF or video frame at decode, a camera shot. Asked for anywhere else it stands in
 *       {@link #ORDERED_FINE} - see {@link Quantizer#perPixel()}.
 * </ul>
 */
@ApiStatus.Experimental
public enum Dither {

    /**
     * The nearest entry, and nothing else.
     *
     * <p>The default everywhere, and the right default: dithering a flat button only adds noise, and
     * {@code docs/performance.md} records that the pattern is poor material for the map packet's own
     * compression - so dithering by default would cost bandwidth on every screen to help the few that ramp.
     * What needs it asks for it, and a gradient {@link Fill} asks on its own behalf.
     */
    NONE,

    /**
     * Bayer 4x4: sixteen thresholds in a four-pixel tile.
     *
     * <p>What a gradient {@link Fill} defaults to, because a gradient is by definition asking for a ramp the
     * palette cannot express, and what {@link DitheredPalette} has always done. The tile being small and
     * periodic is the point for anything that moves - the same four pixels repeat, so the packet's compression
     * has something to find.
     */
    ORDERED,

    /**
     * Bayer 8x8: sixty-four thresholds in an eight-pixel tile.
     *
     * <p>Four times the shades of {@link #ORDERED} at four times the tile, which is the trade: a large smooth
     * area gets a finer texture, and a small one gets a pattern that may not repeat inside it at all. Prefer it
     * across a whole map and {@link #ORDERED} inside a button.
     *
     * <p>Also what an error diffusion mode becomes where there is no rect to diffuse over.
     */
    ORDERED_FINE,

    /**
     * A blue noise tile: aperiodic, so there is no grid to see.
     *
     * <p>The best-looking ordered mode and the most expensive one to send. {@code docs/performance.md} notes
     * that the 4x4 pattern already compresses poorly; noise with no repeat in it compresses worse still,
     * because there is no repeat for the compressor to find. So the pairing rule is: <b>blue noise for a still
     * that is sent once, ordered for anything that moves.</b>
     */
    BLUE_NOISE,

    /**
     * Floyd-Steinberg: the classic, passing every bit of its error on to four neighbors.
     *
     * <p>The most faithful of the three on average, and the one most likely to show the map palette's limits as
     * texture: error that no nearby entry can absorb has to go somewhere, and here all of it does.
     */
    FLOYD_STEINBERG,

    /**
     * Atkinson: six neighbors, and only three quarters of the error handed to them.
     *
     * <p>Throwing a quarter away is a defect on a rich palette and a virtue on a sparse one, which is what the
     * map palette is. When no nearby entry can absorb the error, diffusing all of it smears it across the
     * picture as visible worms; discarding some lets the error die out instead, at the cost of a little
     * contrast. Expect it to beat {@link #FLOYD_STEINBERG} on camera captures and photographs - but measure it
     * on the content in question rather than assuming.
     */
    ATKINSON,

    /**
     * Sierra Lite: three neighbors, all of the error.
     *
     * <p>The cheapest of the family and the least smeared, since error travels one pixel rather than two. A
     * reasonable middle when Floyd-Steinberg worms and Atkinson washes out.
     */
    SIERRA_LITE;

    /**
     * Whether this mode needs a whole rect of colors rather than one color at a time.
     *
     * <p>Which is to say: whether it can only be used at {@link Painter#image} or at decode. Everything else
     * has one color and a position, which is all an ordered mode wants.
     */
    public boolean diffuses() {
        return ordinal() >= FLOYD_STEINBERG.ordinal();
    }
}
