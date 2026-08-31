package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * Matches by how different two colours <i>look</i> rather than by how far apart their numbers are.
 *
 * <p>Walks the entries of the palette underneath and picks the nearest in {@link Oklab}, a space built so that
 * equal distances look equally different. Measured against the weighting Minecraft itself uses, that is closer
 * on saturated colour - and the difference is worth having on a photograph, a camera capture or terrain, where
 * a hue that drifts is what anybody notices first.
 *
 * <p><b>Only ever used to fill a {@link PaletteLut}</b>, which is why walking every entry is affordable: it
 * happens 32768 times at startup and never per pixel. The same is true of the matcher it replaces.
 *
 * <p><b>A nearly grey colour is treated differently, and that is not a refinement.</b> A perceptual space keeps
 * a faint hue faithfully, which is right for a colour and wrong for something that is almost grey. Measured: a
 * warm grey at rgb(129,123,118) reached past the neutral rgb(117,117,117) for a tan rgb(147,124,113) - thirty
 * four points warmer - because the tan's hue matched, and Oklab will pay in lightness to keep a hue. On a ramp
 * that is a band of skin tone through the middle of a grey, and it is the same complaint the dark end of the
 * palette already had by another route: it turns things a bit red.
 *
 * <p>So a colour with no hue worth keeping pays for a candidate's colourfulness. {@code PaletteLut} settles the
 * same argument the same way in the dark corner, and for the same reason: getting a grey's shade slightly wrong
 * is barely visible, and giving it a tint is the thing anybody notices.
 */
public final class PerceptualPalette implements Palette {

    /**
     * How colourful a wanted colour may be and still count as grey, in Oklab chroma.
     *
     * <p>Takes in the warm greys a room is made of - stone, planks in shade, a washed-out photograph - and
     * leaves out anything anybody would call coloured. Above it nothing is penalised and the match is Oklab's
     * own, which is where its advantage on saturated colour comes from.
     */
    private static final double GREY_CHROMA = 0.02;

    /**
     * What a candidate's colourfulness costs when matching a grey, against distance in the same units.
     *
     * <p>Enough to settle the measured cases, and not so much that a grey cannot reach a very slightly tinted
     * entry when that genuinely is the nearest thing the palette has.
     */
    private static final double CHROMA_COST = 1.5;

    private final Palette base;

    /**
     * @param base the palette whose entries are matched against. Only {@link Palette#entries()} and
     *             {@link Palette#color(byte)} are used, never its own matching
     */
    public PerceptualPalette(Palette base) {
        this.base = base;
    }

    @Override
    public byte index(Color color) {
        Oklab.Lab wanted = Oklab.of(color);
        boolean grey = chroma(wanted) < GREY_CHROMA;

        byte found = 0;
        double closest = Double.MAX_VALUE;
        for (byte entry : base.entries()) {
            Color candidate = base.color(entry);
            if (candidate == null) continue;

            Oklab.Lab lab = Oklab.of(candidate);
            double at = Oklab.difference(wanted, lab);
            if (grey) at += CHROMA_COST * chroma(lab);

            if (at < closest) {
                closest = at;
                found = entry;
            }
        }
        return found;
    }

    @Override
    public Color color(byte index) {
        return base.color(index);
    }

    @Override
    public byte[] entries() {
        return base.entries();
    }

    /** How far a colour sits from the grey axis, which is what "has a hue of its own" means here. */
    private static double chroma(Oklab.Lab lab) {
        return Math.hypot(lab.green(), lab.blue());
    }
}
