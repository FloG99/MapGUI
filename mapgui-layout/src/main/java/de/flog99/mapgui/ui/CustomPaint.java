package de.flog99.mapgui.ui;

import java.util.function.Consumer;

/**
 * Escape hatch for drawing raw pixels inside an auto-laid-out box - graphs, icons, a video
 * frame, anything the widget set doesn't cover.
 */
public final class CustomPaint extends AbstractNode<CustomPaint> {

    private final Consumer<PaintContext> painter;
    private int preferredWidth;
    private int preferredHeight;

    public CustomPaint(Consumer<PaintContext> painter) {
        this.painter = painter;
    }

    /** Size to fall back on when neither a fixed size nor fill is set. */
    public CustomPaint preferred(int width, int height) {
        this.preferredWidth = width;
        this.preferredHeight = height;
        return this;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        return new Measured(Math.min(preferredWidth, availableWidth), Math.min(preferredHeight, availableHeight));
    }

    @Override
    protected void paintContent(Painter target) {
        Rect box = contentBounds();
        Rect previous = target.pushClip(box);
        painter.accept(new PaintContext(target, box, hovered()));
        target.popClip(previous);
    }
}
