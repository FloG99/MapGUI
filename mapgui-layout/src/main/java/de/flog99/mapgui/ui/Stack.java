package de.flog99.mapgui.ui;

/**
 * Overlays every child on the same rect.
 *
 * <p>Children keep their own size and are placed with {@link AbstractNode#place}, which is what makes
 * this useful: a background that fills, and a badge pinned to a corner of it.
 */
public final class Stack extends AbstractContainer<Stack> {

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        int width = 0;
        int height = 0;
        for (Node kid : visibleChildren()) {
            Measured measured = kid.measure(context, availableWidth, availableHeight);
            width = Math.max(width, measured.width());
            height = Math.max(height, measured.height());
        }
        return new Measured(width, height);
    }

    @Override
    protected void arrangeContent(LayoutContext context, Rect content) {
        for (Node kid : visibleChildren()) {
            Measured measured = kid.measure(context, content.width(), content.height());
            // Clamped rather than simply taken, or a fill child's maxWidth would be ignored here alone.
            int width = kid.widthSizing().isFill() ? kid.widthSizing().clamp(content.width()) : Math.min(measured.width(), content.width());
            int height = kid.heightSizing().isFill() ? kid.heightSizing().clamp(content.height()) : Math.min(measured.height(), content.height());

            int x = content.x() + across(content.width() - width, kid.placeX());
            int y = content.y() + down(content.height() - height, kid.placeY());
            kid.arrange(context, new Rect(x, y, width, height));
        }
    }

    private static int across(int spare, Justify place) {
        return switch (place) {
            case CENTER -> spare / 2;
            case END -> spare;
            default -> 0;
        };
    }

    private static int down(int spare, Align place) {
        return switch (place) {
            case CENTER -> spare / 2;
            case END -> spare;
            default -> 0;
        };
    }
}
