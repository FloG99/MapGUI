package de.flog99.mapgui;

import de.flog99.mapgui.ui.LayoutContext;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.TextFont;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders a layout to an image, with no server anywhere.
 *
 * <p>For looking at a screen while writing one. A map GUI is 128 pixels of 143 colours, so how it will actually look
 * is not something to judge from the code - and an animation that is over in a fifth of a second cannot practically
 * be judged in game either. Render the stages here and look at them side by side.
 *
 * <p>The real layout engine, font and palette, so what comes out is quantised exactly as the map will be.
 *
 * <p>Hand it the node tree, not the {@link Screen} - a screen usually reads the player holding it, and there is none
 * here. Building the tree in a method that takes what it needs as arguments is what makes one renderable this way.
 *
 * <pre>{@code
 * BufferedImage frame = MapImage.of(MyScreen.body(state), 128, 128);
 * MapImage.write(MapImage.scaled(frame, 3), Path.of("build/screen.png"));
 * }</pre>
 */
public final class MapImage {

    private MapImage() {
    }

    /** One frame of a tree, at the map's own size and palette. */
    public static BufferedImage of(Node tree, int width, int height) {
        return of(tree, width, height, MapTextFont.INSTANCE);
    }

    /** The same with a font of your own, for a screen that overrides {@link Screen#font()}. */
    public static BufferedImage of(Node tree, int width, int height, TextFont font) {
        MapSurface surface = new MapSurface(width, height);
        Painter painter = surface.painter();
        painter.font(font);

        LayoutContext context = new LayoutContext(font);
        tree.measure(context, width, height);
        tree.arrange(context, new Rect(0, 0, width, height));
        tree.paint(painter);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, MapColors.INSTANCE.color(surface.get(x, y)).getRGB());
            }
        }
        return image;
    }

    /** Nearest neighbour, because a smoothed map pixel is a lie about what the palette did. */
    public static BufferedImage scaled(BufferedImage source, int factor) {
        if (factor <= 1) return source;

        BufferedImage big = new BufferedImage(source.getWidth() * factor, source.getHeight() * factor, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < big.getHeight(); y++) {
            for (int x = 0; x < big.getWidth(); x++) {
                big.setRGB(x, y, source.getRGB(x / factor, y / factor));
            }
        }
        return big;
    }

    /** Frames in a row, which is how a sequence too short to watch is looked at. */
    public static BufferedImage strip(List<BufferedImage> frames, int gap, Color background) {
        if (frames.isEmpty()) return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        int width = -gap;
        int height = 0;
        for (BufferedImage frame : frames) {
            width += frame.getWidth() + gap;
            height = Math.max(height, frame.getHeight());
        }

        BufferedImage strip = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D pen = strip.createGraphics();
        try {
            pen.setColor(background);
            pen.fillRect(0, 0, width, height);

            int at = 0;
            for (BufferedImage frame : frames) {
                pen.drawImage(frame, at, 0, null);
                at += frame.getWidth() + gap;
            }
        } finally {
            pen.dispose();
        }
        return strip;
    }

    /** Writes a PNG, making the folder if it is not there yet. */
    public static void write(BufferedImage image, Path target) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        if (folder != null) {
            Files.createDirectories(folder);
        }
        ImageIO.write(image, "png", target.toFile());
    }
}
