package de.flog99.mapgui.ui;

/**
 * An area, as the question "is this pixel inside it".
 *
 * <p>Which is all a painter needs. Filling is the test over a rectangle, and the outline is the pixels that are
 * inside but within the stroke width of somewhere that is not - so every shape gets a fill, an outline and a
 * line thickness from one implementation, and a new shape is one method rather than a new drawing routine.
 *
 * <p>Coordinates are surface pixels, and {@link #contains} must answer for any of them: pixels outside
 * {@link #bounds} are outside the shape.
 */
public interface Shape {

    boolean contains(int x, int y);

    /** Everything the shape could cover. Nothing outside this is drawn, or even asked about. */
    Rect bounds();

    static Shape of(Rect rect) {
        return new Rectangle(rect);
    }

    static Shape circle(int centerX, int centerY, int radius) {
        return ellipse(centerX, centerY, radius, radius);
    }

    static Shape ellipse(int centerX, int centerY, int radiusX, int radiusY) {
        return new Ellipse(centerX, centerY, radiusX, radiusY);
    }

    static Shape triangle(int x1, int y1, int x2, int y2, int x3, int y3) {
        return polygon(new int[]{x1, x2, x3}, new int[]{y1, y2, y3});
    }

    /** The corners in order, open or closed - the last one joins back to the first either way. */
    static Shape polygon(int[] xs, int[] ys) {
        return new Polygon(xs.clone(), ys.clone());
    }

    record Rectangle(Rect rect) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            return rect.contains(x, y);
        }

        @Override
        public Rect bounds() {
            return rect;
        }
    }

    /**
     * An ellipse, measured to the outside edge of the boundary pixel rather than to its middle.
     *
     * <p>Measuring to the middle - the exact test, is this pixel's centre within the radius - draws a star. At
     * the end of an axis the boundary runs straight through the middle of the one pixel sitting on it, covering
     * exactly half and so taking it, while clipping its neighbours too lightly to take them. Every radius
     * therefore ends in a one-pixel spike, invisible at twenty and most of what you see at four.
     *
     * <p>Half a pixel of radius moves the boundary onto the grid instead of through it, so no row can be the
     * half-covered case and no pole comes to a point. Taking less than half - just enough to widen the pole -
     * looks like the smaller change and is not: it cuts corners the circle owns, and a disc of thirteen across
     * comes out an octagon.
     */
    record Ellipse(int centerX, int centerY, int radiusX, int radiusY) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            int dx = x - centerX;
            int dy = y - centerY;
            if (radiusX < 0 || radiusY < 0) return false;
            // The half pixel is slack against the radius asked for, and must not draw past it.
            if (Math.abs(dx) > radiusX || Math.abs(dy) > radiusY) return false;

            // Radii in half pixels, which keeps the whole test in integers: (dx/rx)² + (dy/ry)² <= 1, times four.
            long rx = 2L * radiusX + 1;
            long ry = 2L * radiusY + 1;
            return 4 * (long) dx * dx * ry * ry + 4 * (long) dy * dy * rx * rx <= rx * rx * ry * ry;
        }

        @Override
        public Rect bounds() {
            return new Rect(centerX - radiusX, centerY - radiusY, radiusX * 2 + 1, radiusY * 2 + 1);
        }
    }

    /**
     * Any polygon, convex or not, tested by counting how many edges a ray from the pixel crosses.
     *
     * <p>Measured at the middle of the pixel rather than its corner, so a shape whose edge runs exactly along a
     * pixel boundary does not depend on rounding to decide which side it falls.
     */
    record Polygon(int[] xs, int[] ys) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            if (xs.length < 3 || xs.length != ys.length) return false;

            double px = x + 0.5;
            double py = y + 0.5;
            boolean inside = false;

            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                boolean straddles = ys[i] > py != ys[j] > py;
                if (straddles && px < (double) (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                    inside = !inside;
                }
            }
            return inside;
        }

        @Override
        public Rect bounds() {
            if (xs.length == 0 || xs.length != ys.length) return Rect.EMPTY;

            int left = xs[0];
            int right = xs[0];
            int top = ys[0];
            int bottom = ys[0];
            for (int i = 1; i < xs.length; i++) {
                left = Math.min(left, xs[i]);
                right = Math.max(right, xs[i]);
                top = Math.min(top, ys[i]);
                bottom = Math.max(bottom, ys[i]);
            }
            return new Rect(left, top, right - left + 1, bottom - top + 1);
        }
    }
}
