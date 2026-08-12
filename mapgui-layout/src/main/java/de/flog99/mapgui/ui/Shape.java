package de.flog99.mapgui.ui;

/**
 * An area, as the question "is this pixel inside it".
 *
 * <p>Which is all a painter needs. Filling is the test over a rectangle, and the outline is the pixels that are
 * inside but within the stroke width of somewhere that is not - so every shape gets a fill, an outline and a
 * line thickness from one implementation, and a new shape is one method rather than a new drawing routine.
 *
 * <p>It is also what lets shapes combine: {@link #intersectionWith}, {@link #combinedWith}, {@link #without} and
 * {@link #holeIn} answer for a pixel by asking the shapes they were built from. So an area none of the factories
 * here draws can still be described rather than plotted a row at a time.
 *
 * <p>Coordinates are surface pixels, and {@link #contains} must answer for any of them: pixels outside
 * {@link #bounds} are outside the shape.
 */
public interface Shape {

    boolean contains(int x, int y);

    /** Everything the shape could cover. Nothing outside this is drawn, or even asked about. */
    Rect bounds();

    /**
     * Where this shape covers one row, as {@code start, end} pairs with the end excluded - or null to be asked
     * {@link #contains} pixel by pixel instead.
     *
     * <p>The reason a drawn shape is affordable at map sizes. Asked per row rather than per pixel, a shape of eight
     * sides is eight sums a row instead of eight per pixel, which on a 96 square window is 770 against 74000. A shape
     * that cannot answer cheaply should return null and lose nothing but the speed.
     *
     * <p>Outlined shapes go through here too. An outline is grown from the boundary and so needs a pixel's
     * neighbours, but working those out from the rows is the same answer for a fraction of the asking.
     *
     * <p>Must agree with {@link #contains} exactly: the two are used for the same fill, and a shape whose spans and
     * whose pixels disagree draws differently depending on whether it was given a border.
     */
    default int[] spansAt(int y) {
        return null;
    }

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
    static Polygon polygon(int[] xs, int[] ys) {
        double[] px = new double[xs.length];
        double[] py = new double[ys.length];
        for (int i = 0; i < xs.length; i++) {
            px[i] = xs[i];
        }
        for (int i = 0; i < ys.length; i++) {
            py[i] = ys[i];
        }
        return new Polygon(px, py);
    }

    /**
     * The same with the corners between pixels rather than on them.
     *
     * <p>For corners that were worked out rather than typed - anything turned or swept. Rounding them first decides
     * which side of an edge a boundary pixel falls on, which at map sizes shows as a stepped edge.
     */
    static Polygon polygon(double[] xs, double[] ys) {
        return new Polygon(xs.clone(), ys.clone());
    }

    /**
     * A regular polygon: a triangle, a hexagon, an octagon, turned to any angle.
     *
     * <p>Returns the {@link Polygon} rather than a bare {@code Shape} so its corners can be read back - which is what
     * anything drawing along its edges needs, such as a line carrying on past a corner.
     *
     * @param cornerRadius distance from the centre to each corner, not to the middle of each side
     * @param turnDegrees  where the first corner sits. 0 points right, and turning goes clockwise, since a surface's
     *                     y axis runs down the screen
     */
    static Polygon regularPolygon(double centreX, double centreY, double cornerRadius, int sides, double turnDegrees) {
        double[] xs = new double[Math.max(0, sides)];
        double[] ys = new double[Math.max(0, sides)];

        for (int i = 0; i < xs.length; i++) {
            double angle = Math.toRadians(turnDegrees + i * 360.0 / sides);
            xs[i] = centreX + cornerRadius * Math.cos(angle);
            ys[i] = centreY + cornerRadius * Math.sin(angle);
        }
        return new Polygon(xs, ys);
    }

    /**
     * Everything in {@code within} on one side of a line, for a straight cut across a box.
     *
     * <p>The side kept is the one to the <b>right</b> of the arrow from the first point to the second, reading right
     * as the screen does with y running down - so a line drawn left to right keeps what is below it.
     *
     * <p>Bounded by a rect because a side of a line has no end of its own. Combine several with
     * {@link #intersectionWith} for an area cut by more than one.
     */
    static Shape sideOfLine(Rect within, double x1, double y1, double x2, double y2) {
        return new Side(within, x1, y1, x2 - x1, y2 - y1);
    }

    /** Only where both cover, which is how several straight cuts describe one area between them. */
    default Shape intersectionWith(Shape other) {
        return new Both(this, other);
    }

    /** Wherever either covers, for a shape made of parts. */
    default Shape combinedWith(Shape other) {
        return new Either(this, other);
    }

    /** This one with the other cut out of it. */
    default Shape without(Shape other) {
        return new Except(this, other);
    }

    /**
     * The other way round: {@code area} with this shape punched out of it.
     *
     * <p>An aperture, a mask, a vignette - anything where what gets drawn is everything the shape does not cover.
     * A rect rather than a shape because the result has to be bounded, and the box being drawn into usually is it.
     */
    default Shape holeIn(Rect area) {
        return new Hole(this, area);
    }

    record Rectangle(Rect rect) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            return rect.contains(x, y);
        }

        @Override
        public int[] spansAt(int y) {
            if (y < rect.y() || y >= rect.bottom()) return Spans.NONE;

            return new int[]{rect.x(), rect.right()};
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
    record Polygon(double[] xs, double[] ys) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            if (xs.length < 3 || xs.length != ys.length) return false;

            double px = x + 0.5;
            double py = y + 0.5;
            boolean inside = false;

            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                boolean straddles = ys[i] > py != ys[j] > py;
                if (!straddles) continue;

                // Where the edge crosses this row, compared without dividing by the edge's height - which is the
                // same test with the division multiplied out, and the division was most of what this cost. Every
                // pixel of every filled polygon comes through here, so it is worth the rearranged line.
                double height = ys[j] - ys[i];
                double crossing = (xs[j] - xs[i]) * (py - ys[i]) + xs[i] * height;
                if (height > 0 ? px * height < crossing : px * height > crossing) {
                    inside = !inside;
                }
            }
            return inside;
        }

        /**
         * The same rule read a row at a time: every edge that straddles the row, sorted, and paired off.
         *
         * <p>Which is the whole of why this is not per pixel. A pixel is inside when an odd number of crossings lie
         * to its right, so the crossings in order are the ends of the runs that are covered - concave shapes and all,
         * since a row through the notch of an arrowhead simply has four crossings rather than two.
         */
        @Override
        public int[] spansAt(int y) {
            if (xs.length < 3 || xs.length != ys.length) return Spans.NONE;

            double py = y + 0.5;
            double[] crossings = new double[xs.length];
            int found = 0;

            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if (ys[i] > py == ys[j] > py) continue;

                crossings[found++] = xs[i] + (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]);
            }
            if (found < 2) return Spans.NONE;

            java.util.Arrays.sort(crossings, 0, found);
            return Spans.between(crossings, found);
        }

        @Override
        public Rect bounds() {
            if (xs.length == 0 || xs.length != ys.length) return Rect.EMPTY;

            double left = xs[0];
            double right = xs[0];
            double top = ys[0];
            double bottom = ys[0];
            for (int i = 1; i < xs.length; i++) {
                left = Math.min(left, xs[i]);
                right = Math.max(right, xs[i]);
                top = Math.min(top, ys[i]);
                bottom = Math.max(bottom, ys[i]);
            }

            int x = (int) Math.floor(left);
            int y = (int) Math.floor(top);
            return new Rect(x, y, (int) Math.ceil(right) - x + 1, (int) Math.ceil(bottom) - y + 1);
        }
    }

    /** One side of a line, bounded. {@code dx} and {@code dy} are the direction the arrow runs in. */
    record Side(Rect within, double throughX, double throughY, double dx, double dy) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            if (!within.contains(x, y)) return false;

            // Which side of the arrow the pixel's middle falls, as the sign of the cross product.
            return dx * (y + 0.5 - throughY) - dy * (x + 0.5 - throughX) >= 0;
        }

        @Override
        public Rect bounds() {
            return within;
        }
    }

    record Both(Shape first, Shape second) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            return first.contains(x, y) && second.contains(x, y);
        }

        @Override
        public int[] spansAt(int y) {
            int[] mine = first.spansAt(y);
            int[] theirs = second.spansAt(y);
            return mine == null || theirs == null ? null : Spans.overlap(mine, theirs);
        }

        @Override
        public Rect bounds() {
            return first.bounds().intersect(second.bounds());
        }
    }

    record Either(Shape first, Shape second) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            return first.contains(x, y) || second.contains(x, y);
        }

        @Override
        public Rect bounds() {
            return first.bounds().union(second.bounds());
        }
    }

    record Except(Shape shape, Shape cut) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            return shape.contains(x, y) && !cut.contains(x, y);
        }

        @Override
        public Rect bounds() {
            return shape.bounds();
        }
    }

    record Hole(Shape shape, Rect area) implements Shape {

        @Override
        public boolean contains(int x, int y) {
            return area.contains(x, y) && !shape.contains(x, y);
        }

        /** The row's own width with the shape's runs taken out of it, which is at most one run more than it had. */
        @Override
        public int[] spansAt(int y) {
            if (y < area.y() || y >= area.bottom()) return Spans.NONE;

            int[] cut = shape.spansAt(y);
            return cut == null ? null : Spans.outside(cut, area.x(), area.right());
        }

        @Override
        public Rect bounds() {
            return area;
        }
    }
}
