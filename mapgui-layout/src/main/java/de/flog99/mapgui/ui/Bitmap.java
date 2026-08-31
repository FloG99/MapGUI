package de.flog99.mapgui.ui;

import java.awt.image.BufferedImage;

/**
 * A picture from a file, laid out like any other node.
 *
 * <p>Drawn a pixel for a pixel at the top left of its box, since a map GUI is drawn at the size it will be shown
 * at and resampling a 128 pixel canvas loses more than it gains. Size it and it crops rather than scales, unless
 * {@link #shrinkToFit()} says otherwise.
 *
 * <p>A null image draws nothing rather than failing, so a background on this node is what shows when an asset is
 * missing - which is usually the right answer for artwork read out of a jar at runtime.
 */
public final class Bitmap extends AbstractNode<Bitmap> {

    private final BufferedImage image;
    private boolean shrink;

    public Bitmap(BufferedImage image) {
        this.image = image;
    }

    /**
     * Draws the picture smaller when it does not fit, instead of showing the part of it that does.
     *
     * <p>Cropping is the right default at map resolution - artwork is drawn at the size it was made for and
     * resampling costs more than it returns - but it is a poor way to <b>run out of room</b>. A column with
     * nothing left takes it from whatever will give, and an image gives by losing its bottom rows with nothing
     * anywhere saying so. A caller stating a bigger font is enough to cause that.
     *
     * <p>With this on, the picture keeps its proportions and is centered in whatever room it is given, and is
     * never drawn larger than it is: a small picture in a big box is still a small picture, since scaling up at
     * this resolution only blurs it.
     */
    public Bitmap shrinkToFit() {
        this.shrink = true;
        return this;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        if (image == null) return Measured.ZERO;
        if (!shrink) {
            return new Measured(Math.min(image.getWidth(), availableWidth), Math.min(image.getHeight(), availableHeight));
        }

        // Asked for as a shape rather than as two independent numbers, or a picture squeezed in one axis would
        // be handed a box of the wrong proportions to draw itself into.
        Rect fitted = fitted(new Rect(0, 0, Math.max(0, availableWidth), Math.max(0, availableHeight)));
        return new Measured(fitted.width(), fitted.height());
    }

    @Override
    protected void paintContent(Painter target) {
        if (image == null) return;

        Rect box = contentBounds();
        Rect previous = target.pushClip(box);
        if (shrink) {
            target.image(fitted(box), image);
        } else {
            target.image(box.x(), box.y(), image);
        }
        target.popClip(previous);
    }

    /** The largest box with the picture's proportions that fits inside {@code box}, centered, never enlarged. */
    private Rect fitted(Rect box) {
        if (box.width() <= 0 || box.height() <= 0) return new Rect(box.x(), box.y(), 0, 0);

        double scale = Math.min(1.0, Math.min(
                box.width() / (double) image.getWidth(),
                box.height() / (double) image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return new Rect(box.x() + (box.width() - width) / 2, box.y() + (box.height() - height) / 2, width, height);
    }
}
