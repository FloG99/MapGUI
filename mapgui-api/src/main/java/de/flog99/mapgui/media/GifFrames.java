package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Palette;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * An animated GIF, decoded with nothing but the JDK.
 *
 * <p>Frames are composited on the way in rather than handed over raw. A GIF only stores what changed
 * since the last frame, at an offset, with a rule for what to do with the old pixels - so read
 * naively you get fragments on a black background rather than a picture.
 */
public final class GifFrames implements Frames {

    /** What GIF means by "no delay". Players treat it as the slowest sensible speed, so we do too. */
    private static final int DEFAULT_DELAY_MS = 100;

    /**
     * Longest edge frames are kept at, since every frame lives in memory for the life of the animation.
     *
     * <p>128 because that is the most a single map can show, and the difference matters: twenty seconds
     * of 256x256 is 200 frames, which is 13 MB kept at source size and 3 MB at this one. Anything that
     * only ever draws into a corner of a map should ask for less again.
     */
    public static final int MAP_SIZE = 128;

    private final int width;
    private final int height;
    private final List<byte[]> frames;
    private final int[] endsAt;
    private final int durationMs;

    private GifFrames(int width, int height, List<byte[]> frames, int[] endsAt) {
        this.width = width;
        this.height = height;
        this.frames = frames;
        this.endsAt = endsAt;
        this.durationMs = endsAt[endsAt.length - 1];
    }

    /** Kept no larger than {@link #MAP_SIZE}, which is all a map can show anyway. */
    public static GifFrames read(InputStream source, Palette palette) throws IOException {
        return read(source, palette, MAP_SIZE);
    }

    public static GifFrames read(InputStream source, Palette palette, int maxSize) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream stream = ImageIO.createImageInputStream(source)) {
            reader.setInput(stream);
            return read(reader, palette, maxSize);
        } finally {
            reader.dispose();
        }
    }

    private static GifFrames read(ImageReader reader, Palette palette, int maxSize) throws IOException {
        int count = reader.getNumImages(true);
        if (count == 0) throw new IOException("The GIF has no frames in it.");

        // Compositing has to happen at source size to land in the right place; only the copy we keep
        // is shrunk. Doing it the other way round would drift a frame's offset by the scale factor.
        // ARGB, not RGB: a GIF may be transparent, and without somewhere to keep the alpha every
        // see-through pixel composites onto black and arrives as black.
        BufferedImage first = reader.read(0);
        BufferedImage canvas = new BufferedImage(first.getWidth(), first.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D compositing = canvas.createGraphics();

        double scale = Math.min(1.0, maxSize / (double) Math.max(canvas.getWidth(), canvas.getHeight()));
        int width = Math.max(1, (int) Math.round(canvas.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(canvas.getHeight() * scale));

        BufferedImage kept = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D shrinking = kept.createGraphics();
        shrinking.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Replace rather than blend, or last frame's pixels show through this one's transparent parts.
        shrinking.setComposite(AlphaComposite.Src);

        List<byte[]> frames = new ArrayList<>(count);
        int[] endsAt = new int[count];
        int[] scratch = new int[width * height];
        int elapsed = 0;

        BufferedImage snapshot = null;

        for (int i = 0; i < count; i++) {
            BufferedImage frame = i == 0 ? first : reader.read(i);
            Control control = controlFor(reader.getImageMetadata(i));

            if (control.disposal == Disposal.PREVIOUS) {
                snapshot = copyOf(canvas);
            }
            compositing.drawImage(frame, control.x, control.y, null);

            shrinking.drawImage(canvas, 0, 0, width, height, null);
            kept.getRGB(0, 0, width, height, scratch, 0, width);
            frames.add(quantize(scratch, palette));
            elapsed += control.delayMs;
            endsAt[i] = elapsed;

            // Disposal describes what happens *after* this frame is shown, so it is applied once the frame
            // has been kept - not before drawing it. Getting that backwards leaves one frame of the
            // previous picture showing through wherever this one is transparent.
            dispose(compositing, canvas, frame, control, snapshot);
        }
        compositing.dispose();
        shrinking.dispose();

        return new GifFrames(width, height, frames, endsAt);
    }

    /**
     * Anything this faint counts as see-through. Scaling blends alpha at the edges of a transparent
     * shape, and half a pixel of translucency cannot be shown in a palette with no alpha - so the edge
     * is decided one way or the other.
     */
    private static final int OPAQUE_ENOUGH = 128;

    /**
     * Palette matching, done once here instead of per frame while painting.
     *
     * <p>No pixel position is passed, deliberately. A dithering palette chooses between entries by
     * where the pixel lands, and the player scales frames *after* this - which would resample the
     * dither pattern into moire. Video snaps to the nearest color instead.
     */
    private static byte[] quantize(int[] argb, Palette palette) {
        byte[] indices = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            // Index 0 is the palette's transparent entry, and nothing opaque ever matches to it, so it
            // doubles as "leave this pixel alone".
            indices[i] = (argb[i] >>> 24) < OPAQUE_ENOUGH
                    ? TRANSPARENT
                    : palette.index(new Color(argb[i]));
        }
        return indices;
    }

    /** What the canvas should look like once this frame has had its turn. */
    private enum Disposal {

        /** Leave it. The next frame draws on top, which is how most GIFs store only what moved. */
        KEEP,

        /** This frame's own rectangle goes back to nothing - and only that rectangle. */
        BACKGROUND,

        /** Undo this frame entirely, back to whatever was there before it. */
        PREVIOUS
    }

    /** Where this frame goes, how long it lasts, and what to do with the canvas afterwards. */
    private record Control(int x, int y, int delayMs, Disposal disposal) {
    }

    private static void dispose(Graphics2D compositing, BufferedImage canvas, BufferedImage frame,
                                Control control, BufferedImage snapshot) {
        switch (control.disposal) {
            case BACKGROUND -> {
                // Cleared through the composite rather than clearRect, so "nothing" means transparent
                // rather than black - and only over the frame's own area, which is all the GIF asked for.
                compositing.setComposite(AlphaComposite.Clear);
                compositing.fillRect(control.x, control.y, frame.getWidth(), frame.getHeight());
                compositing.setComposite(AlphaComposite.SrcOver);
            }
            case PREVIOUS -> {
                if (snapshot == null) return;

                compositing.setComposite(AlphaComposite.Src);
                compositing.drawImage(snapshot, 0, 0, null);
                compositing.setComposite(AlphaComposite.SrcOver);
            }
            case KEEP -> {
            }
        }
    }

    private static BufferedImage copyOf(BufferedImage image) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(image, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static Control controlFor(IIOMetadata metadata) {
        Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
        int x = 0;
        int y = 0;
        int delayMs = DEFAULT_DELAY_MS;
        Disposal disposal = Disposal.KEEP;

        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            switch (node.getNodeName()) {
                case "ImageDescriptor" -> {
                    x = attribute(node, "imageLeftPosition", 0);
                    y = attribute(node, "imageTopPosition", 0);
                }
                case "GraphicControlExtension" -> {
                    // Stored in hundredths of a second, and zero means "as fast as you like".
                    int centiseconds = attribute(node, "delayTime", 0);
                    delayMs = centiseconds <= 0 ? DEFAULT_DELAY_MS : centiseconds * 10;
                    // "none" and "doNotDispose" both mean leave it, and anything unrecognized is safest
                    // treated the same way - drawing over is what a GIF expects by default.
                    disposal = switch (String.valueOf(text(node, "disposalMethod"))) {
                        case "restoreToBackgroundColor" -> Disposal.BACKGROUND;
                        case "restoreToPrevious" -> Disposal.PREVIOUS;
                        default -> Disposal.KEEP;
                    };
                }
                default -> {
                }
            }
        }
        return new Control(x, y, delayMs, disposal);
    }

    private static int attribute(Node node, String name, int fallback) {
        String value = text(node, name);
        if (value == null) return fallback;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String text(Node node, String name) {
        Node attribute = node.getAttributes().getNamedItem(name);
        return attribute == null ? null : attribute.getNodeValue();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int count() {
        return frames.size();
    }

    @Override
    public int durationMs() {
        return durationMs;
    }

    @Override
    public int indexAt(int millis) {
        int at = Math.floorMod(millis, durationMs);
        for (int i = 0; i < endsAt.length; i++) {
            if (at < endsAt[i]) return i;
        }
        return endsAt.length - 1;
    }

    @Override
    public byte[] pixels(int index) {
        return frames.get(index);
    }
}
