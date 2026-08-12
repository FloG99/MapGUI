package de.flog99.mapgui.ui;

public record Rect(int x, int y, int width, int height) {

    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean contains(int px, int py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    public Rect shrink(Insets insets) {
        return new Rect(
                x + insets.left(),
                y + insets.top(),
                Math.max(0, width - insets.left() - insets.right()),
                Math.max(0, height - insets.top() - insets.bottom())
        );
    }

    public Rect translate(int dx, int dy) {
        return new Rect(x + dx, y + dy, width, height);
    }

    /** The smallest rect holding both. An empty one contributes nothing rather than dragging a corner to 0,0. */
    public Rect union(Rect other) {
        if (width <= 0 || height <= 0) return other;
        if (other.width <= 0 || other.height <= 0) return this;

        int x1 = Math.min(x, other.x);
        int y1 = Math.min(y, other.y);
        int x2 = Math.max(right(), other.right());
        int y2 = Math.max(bottom(), other.bottom());
        return new Rect(x1, y1, x2 - x1, y2 - y1);
    }

    /** Overlap of two rects, or {@link #EMPTY} if they don't touch. Used for clipping. */
    public Rect intersect(Rect other) {
        int x1 = Math.max(x, other.x);
        int y1 = Math.max(y, other.y);
        int x2 = Math.min(right(), other.right());
        int y2 = Math.min(bottom(), other.bottom());
        return x2 <= x1 || y2 <= y1 ? EMPTY : new Rect(x1, y1, x2 - x1, y2 - y1);
    }
}
