package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.media.Frames;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Turning a decoded picture into the one byte per pixel a map wants: shrunk to what a wall can show, then
 * matched to the palette.
 *
 * <p>Shared by everything here that decodes a still rather than a stream. {@link FfmpegSource} does its own,
 * because a video is scaled inside the decoder - it is told the size up front so no full-size frame is ever
 * built, which matters thirty times a second and not at all for one picture.
 */
final class Pixels {

    /**
     * Anything this faint counts as see-through, matching {@link de.flog99.mapgui.media.GifFrames}.
     *
     * <p>Scaling blends alpha at the edge of a transparent shape, and half a pixel of translucency cannot be
     * shown in a palette with no alpha - so the edge is decided one way or the other.
     */
    private static final int OPAQUE_ENOUGH = 128;

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

    /**
     * Palette indices, with see-through pixels kept see-through.
     *
     * <p>{@link MapColors#quantize} on its own ignores alpha, which is right for video - every pixel of it is
     * opaque - and wrong for a still, where transparency is half the reason to use a PNG.
     */
    static byte[] quantize(int[] argb) {
        byte[] indices = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            indices[i] = (argb[i] >>> 24) < OPAQUE_ENOUGH
                    ? Frames.TRANSPARENT
                    : MapColors.INSTANCE.index(argb[i]);
        }
        return indices;
    }

    private Pixels() {
    }
}
