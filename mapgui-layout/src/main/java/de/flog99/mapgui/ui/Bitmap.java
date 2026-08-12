package de.flog99.mapgui.ui;

import java.awt.image.BufferedImage;

/**
 * A picture from a file, laid out like any other node.
 *
 * <p>Drawn a pixel for a pixel at the top left of its box, since a map GUI is drawn at the size it will be shown
 * at and resampling a 128 pixel canvas loses more than it gains. Size it and it crops rather than scales.
 *
 * <p>A null image draws nothing rather than failing, so a background on this node is what shows when an asset is
 * missing - which is usually the right answer for artwork read out of a jar at runtime.
 */
public final class Bitmap extends AbstractNode<Bitmap> {

    private final BufferedImage image;

    public Bitmap(BufferedImage image) {
        this.image = image;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        if (image == null) return Measured.ZERO;

        return new Measured(Math.min(image.getWidth(), availableWidth), Math.min(image.getHeight(), availableHeight));
    }

    @Override
    protected void paintContent(Painter target) {
        if (image == null) return;

        Rect box = contentBounds();
        Rect previous = target.pushClip(box);
        target.image(box.x(), box.y(), image);
        target.popClip(previous);
    }
}
