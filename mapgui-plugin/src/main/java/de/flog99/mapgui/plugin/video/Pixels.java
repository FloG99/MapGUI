package de.flog99.mapgui.plugin.video;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Fitting a decoded picture to what a wall can show, before anything matches it to the palette.
 *
 * <p>Shared by everything here that decodes a still rather than a stream. {@link FfmpegSource} does its own,
 * because a video is scaled inside the decoder - it is told the size up front so no full-size frame is ever
 * built, which matters thirty times a second and not at all for one picture.
 *
 * <p>Only the shrinking is here. Matching to the palette is {@link de.flog99.mapgui.ui.Quantizer}'s, which is
 * what lets a still be dithered like anything else decoded once - and it already decides a translucent edge the
 * same way this used to, against the same threshold.
 */
final class Pixels {

    /** A picture at the size it will be kept at, still as colors. */
    record Shrunk(int width, int height, int[] argb) {
    }

    /** A size, in pixels. */
    record Size(int width, int height) {
    }

    /**
     * The largest picture with the source's proportions that fits inside the box, never enlarged.
     *
     * <p>Shared by the two places that have to make something match a box, and the reason both keep the shape:
     * squashing cannot be undone afterwards. {@link de.flog99.mapgui.media.LivePlayer} letterboxes whatever it
     * is handed, so a picture that arrives already distorted is letterboxed faithfully distorted.
     *
     * <p>Enlarging is not this method's job either. A small video on a big wall is scaled once, when it is
     * drawn, rather than carried around at a size it does not have.
     *
     * <p>A source that never said how big it is falls back to the box. Being wrong about the shape of
     * something that did not describe itself costs a stretch; refusing it would cost the picture.
     */
    static Size fit(int sourceWidth, int sourceHeight, int boxWidth, int boxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new Size(boxWidth, boxHeight);

        double scale = Math.min(1.0, Math.min(boxWidth / (double) sourceWidth, boxHeight / (double) sourceHeight));
        return new Size(Math.max(1, (int) Math.round(sourceWidth * scale)),
                Math.max(1, (int) Math.round(sourceHeight * scale)));
    }

    /**
     * Fits {@code image} inside a {@code maxSize} box, keeping its shape, and never enlarges it.
     *
     * <p>ARGB throughout: a WebP or a PNG may be transparent, and without somewhere to keep the alpha every
     * see-through pixel composites onto black and arrives as black.
     */
    static Shrunk shrink(BufferedImage image, int maxSize) {
        Size size = fit(image.getWidth(), image.getHeight(), maxSize, maxSize);
        int width = size.width();
        int height = size.height();

        BufferedImage kept = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = kept.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Replace rather than blend: the canvas starts transparent, and blending onto it would darken the
        // edges of a transparent picture instead of leaving them alone.
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();

        int[] argb = new int[width * height];
        kept.getRGB(0, 0, width, height, argb, 0, width);
        return new Shrunk(width, height, argb);
    }

    private Pixels() {
    }
}
