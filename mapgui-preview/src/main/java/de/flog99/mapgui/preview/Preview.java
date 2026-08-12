package de.flog99.mapgui.preview;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapImage;
import de.flog99.mapgui.MapSurface;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Image helpers shared by the one-shot renderer and the live server.
 *
 * <p>Rendering uses the real layout engine, the real map font and the real palette, so colors come
 * out quantized exactly as they will be in game.
 */
public final class Preview {

    public static final int MAP_SIZE = 128;

    private Preview() {
    }

    /** Stands in for terrain, which needs a world that a headless preview hasn't got. */
    static void paintBackdrop(MapSurface surface, BufferedImage backdrop) {
        for (int y = 0; y < surface.height(); y++) {
            for (int x = 0; x < surface.width(); x++) {
                int sourceX = x * backdrop.getWidth() / surface.width();
                int sourceY = y * backdrop.getHeight() / surface.height();
                surface.set(x, y, MapColors.INSTANCE.index(new Color(backdrop.getRGB(sourceX, sourceY))));
            }
        }
    }

    /** Nearest neighbor: smoothing a 128px pixel-art canvas hides exactly what you came to see. */
    public static BufferedImage scale(BufferedImage source, int factor) {
        return MapImage.scaled(source, factor);
    }

    public static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static BufferedImage readOrNull(Path file) throws IOException {
        return file != null && Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
    }

    public static void write(BufferedImage image, Path target) throws IOException {
        File file = target.toFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create " + parent);
        }
        ImageIO.write(image, "png", file);
    }
}
