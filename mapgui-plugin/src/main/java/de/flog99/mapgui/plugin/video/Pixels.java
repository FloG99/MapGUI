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

    /**
     * Fits {@code image} inside a {@code maxSize} box, keeping its shape, and never enlarges it.
     *
     * <p>ARGB throughout: a WebP or a PNG may be transparent, and without somewhere to keep the alpha every
     * see-through pixel composites onto black and arrives as black.
     */
    static Shrunk shrink(BufferedImage image, int maxSize) {
        double scale = Math.min(1.0, maxSize / (double) Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));

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
