package de.flog99.mapgui.ui;

import java.awt.image.BufferedImage;

/**
 * A mutable grid of map palette indices.
 *
 * <p>Deliberately not tied to 128x128: a surface may be any size so it can later back a
 * multi-map wall display as easily as a single map in hand.
 */
public interface Surface {

    int width();

    int height();

    void set(int x, int y, byte color);

    byte get(int x, int y);

    default boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width() && y < height();
    }

    default Rect bounds() {
        return new Rect(0, 0, width(), height());
    }

    /** Snapshot as an image, for screenshots, saving to disk and the headless preview. */
    default BufferedImage toImage(Palette palette) {
        BufferedImage image = new BufferedImage(width(), height(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height(); y++) {
            for (int x = 0; x < width(); x++) {
                image.setRGB(x, y, palette.color(get(x, y)).getRGB());
            }
        }
        return image;
    }
}
