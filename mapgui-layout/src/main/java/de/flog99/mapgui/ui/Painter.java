package de.flog99.mapgui.ui;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Draws shapes, images and text onto a {@link Surface}.
 *
 * <p>All operations honour the current clip rect, which containers push while drawing their
 * children so a {@link Scroll} can cut off whatever runs past its edge.
 */
public final class Painter {

    private final Surface surface;
    private final Palette palette;
    private TextFont font;
    private Rect clip;

    /** Set only by {@link #pushClip(Shape)}, so an ordinary rect clip costs one null check a pixel. */
    private Shape clipShape;

    /** Built on first use, since a screen with no gradients never needs it. */
    private Palette dithered;
    /** One full row of an image being blitted, held so {@link #image} allocates nothing per call. */
    private int[] pixelRow;

    public Painter(Surface surface, Palette palette, TextFont font) {
        this.surface = surface;
        this.palette = palette;
        this.font = font;
        this.clip = surface.bounds();
    }

    public Surface surface() {
        return surface;
    }

    public Palette palette() {
        return palette;
    }

    public TextFont font() {
        return font;
    }

    /**
     * Draws with a different font from here on.
     *
     * <p>For whatever owns the painter to point it at the font of the screen it is about to draw - a painter
     * outlives any one screen, and a session can have several stacked. Set it to the same font that screen was
     * laid out with, or the words will not be where the layout put them.
     */
    public void font(TextFont value) {
        this.font = value;
    }

    public Rect clip() {
        return clip;
    }

    /** Narrows the clip to the intersection and returns the previous one for restoring. */
    public Rect pushClip(Rect rect) {
        Rect previous = clip;
        clip = clip.intersect(rect);
        return previous;
    }

    public void popClip(Rect previous) {
        clip = previous;
    }

    /**
     * Clips to a shape rather than to a box, so anything drawn afterwards only lands inside it.
     *
     * <p>For content sitting in a hole that is not rectangular - a picture behind a round lens, a video in a
     * porthole. It works for whatever draws next, including text and images, which have no shape of their own to cut.
     *
     * <p>Nests with the rect clips containers push; hand the returned {@link Clip} back to restore both.
     */
    public Clip pushClip(Shape shape) {
        Clip previous = new Clip(clip, clipShape);
        clip = clip.intersect(shape.bounds());
        clipShape = clipShape == null ? shape : clipShape.intersectionWith(shape);
        return previous;
    }

    public void popClip(Clip previous) {
        clip = previous.rect();
        clipShape = previous.shape();
    }

    /** A clip as it was, box and shape together, so restoring one restores both. */
    public record Clip(Rect rect, Shape shape) {
    }

    private boolean clipped(int x, int y) {
        return !clip.contains(x, y) || (clipShape != null && !clipShape.contains(x, y));
    }

    // ---- pixels ----

    public void pixel(int x, int y, byte color) {
        if (!clipped(x, y) && surface.inBounds(x, y)) {
            surface.set(x, y, color);
        }
    }

    public void pixel(int x, int y, Color color) {
        pixel(x, y, color, palette);
    }

    /** The packed image/glyph path, avoiding a Color object for each pixel. */
    void pixel(int x, int y, int argb) {
        int alpha = argb >>> 24;
        if (alpha == 0) return;
        if (clipped(x, y) || !surface.inBounds(x, y)) return;

        if (alpha == 255) {
            surface.set(x, y, palette.index(argb, x, y));
            return;
        }

        int under = palette.color(surface.get(x, y)).getRGB();
        surface.set(x, y, palette.index(blend(under, argb, alpha), x, y));
    }

    private void pixel(int x, int y, Color color, Palette with) {
        if (color == null) return;

        int alpha = color.getAlpha();
        if (alpha == 0) return;
        if (clipped(x, y) || !surface.inBounds(x, y)) return;

        if (alpha == 255) {
            surface.set(x, y, with.index(color, x, y));
            return;
        }

        // Mixed as packed ints rather than colors: every part-covered pixel of an anti-aliased glyph and every
        // pixel of a translucent fill comes through here, and a Color each would be an object per pixel.
        int under = palette.color(surface.get(x, y)).getRGB();
        surface.set(x, y, with.index(blend(under, color.getRGB(), alpha), x, y));
    }

    /** {@code weight} is the over colour's alpha, 0 to 255. */
    private static int blend(int under, int over, int weight) {
        int inverse = 255 - weight;

        int red = ((over >> 16 & 0xFF) * weight + (under >> 16 & 0xFF) * inverse + 127) / 255;
        int green = ((over >> 8 & 0xFF) * weight + (under >> 8 & 0xFF) * inverse + 127) / 255;
        int blue = ((over & 0xFF) * weight + (under & 0xFF) * inverse + 127) / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /** Gradients dither; flat colors snap, since dithering a solid button would just add noise. */
    private Palette paletteFor(Fill fill) {
        if (fill.uniform()) return palette;
        if (dithered == null) {
            dithered = new DitheredPalette(palette);
        }
        return dithered;
    }

    private static Color blend(Color under, Color over, float weight) {
        float inverse = 1f - weight;
        return new Color(
                Math.round(over.getRed() * weight + under.getRed() * inverse),
                Math.round(over.getGreen() * weight + under.getGreen() * inverse),
                Math.round(over.getBlue() * weight + under.getBlue() * inverse)
        );
    }

    public void clear(Color color) {
        fill(surface.bounds(), color);
    }

    // ---- shapes ----

    public void fill(Rect rect, Color color) {
        if (color == null) return;

        for (int y = rect.y(); y < rect.bottom(); y++) {
            for (int x = rect.x(); x < rect.right(); x++) {
                pixel(x, y, color);
            }
        }
    }

    /** Rounded rectangle with a flat fill and a single-color border. */
    public void rect(Rect rect, Color fill, int borderWidth, Color borderColor, int radius) {
        box(rect, fill == null ? null : Fill.solid(fill), Border.solid(borderWidth, borderColor), Corner.ROUND, radius);
    }

    /**
     * A box with shaped corners and an optional border.
     *
     * <p>The border follows the corner shape because its inner edge is that shape inset by the border width,
     * rather than a special case per corner. A bevel takes its color from whichever edge a pixel is nearest,
     * which is what makes the diagonal split at each corner.
     */
    public void box(Rect rect, Fill fill, Border border, Corner corner, int radius) {
        if (rect.width() <= 0 || rect.height() <= 0) return;

        double w = rect.width();
        double h = rect.height();
        double outer = Math.min(radius, Math.min(w, h) / 2.0);

        Palette fillPalette = fill == null ? palette : paletteFor(fill);
        Color bevelBase = fill == null ? null : fill.at(rect.x(), rect.y(), rect);
        Border resolved = border.resolve(bevelBase);
        int edge = resolved.visible() ? Math.max(0, Math.min(resolved.width(), (int) Math.min(w, h) / 2)) : 0;
        double inner = Math.max(0, outer - edge);

        for (int j = 0; j < rect.height(); j++) {
            for (int i = 0; i < rect.width(); i++) {
                double px = i + 0.5;
                double py = j + 0.5;
                if (!insideCorner(px, py, w, h, outer, corner)) continue;

                int x = rect.x() + i;
                int y = rect.y() + j;
                boolean onEdge = edge > 0
                        && !insideCorner(px - edge, py - edge, w - 2 * edge, h - 2 * edge, inner, corner);

                if (onEdge) {
                    pixel(x, y, edgeColor(resolved, px, py, w, h));
                } else if (fill != null) {
                    pixel(x, y, fill.at(x, y, rect), fillPalette);
                }
            }
        }
    }

    /** Top and left edges take the lit color, bottom and right the shaded one. */
    private static Color edgeColor(Border border, double px, double py, double w, double h) {
        if (border.kind() != Border.Kind.BEVEL) return border.primary();

        double toTop = py;
        double toLeft = px;
        double toBottom = h - py;
        double toRight = w - px;
        double nearest = Math.min(Math.min(toTop, toLeft), Math.min(toBottom, toRight));
        return nearest == toTop || nearest == toLeft ? border.primary() : border.secondary();
    }

    /** Whether a point is inside a box with the given corner treatment. Only the corner squares need deciding. */
    private static boolean insideCorner(double px, double py, double w, double h, double radius, Corner corner) {
        if (px < 0 || py < 0 || px > w || py > h) return false;
        if (radius <= 0 || corner == Corner.SQUARE) return true;

        double intoX = radius - Math.min(px, w - px);
        double intoY = radius - Math.min(py, h - py);
        if (intoX <= 0 || intoY <= 0) return true;

        return switch (corner) {
            case SQUARE -> true;
            case ROUND -> intoX * intoX + intoY * intoY <= radius * radius;
            case BEVEL -> intoX + intoY <= radius;
            case NOTCH -> false;
            case STEP -> tread(intoX) + tread(intoY) <= radius;
        };
    }

    /** Quantized to two pixels, which is what turns a diagonal into a staircase. */
    private static double tread(double value) {
        return Math.floor(value / 2) * 2;
    }

    /**
     * Fills a shape and draws its outline, whatever the shape is.
     *
     * <p>The outline is every pixel inside the shape that is within the border's width of somewhere outside
     * it, so thickness works the same on a triangle, a circle and a twelve-sided polygon, and a new shape only
     * has to answer {@link Shape#contains}. A bevel is drawn as a plain border here: light and shade need edges
     * to belong to, which a box has and an arbitrary outline does not.
     *
     * <p>Either half is optional: no fill outlines the shape, no border fills it flat.
     */
    public void shape(Shape shape, Fill fill, Border border) {
        Rect bounds = shape.bounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;

        Border resolved = (border == null ? Border.none() : border)
                .resolve(fill == null ? null : fill.at(bounds.x(), bounds.y(), bounds));
        int stroke = resolved.visible() ? resolved.width() : 0;
        Palette fillPalette = fill == null ? palette : paletteFor(fill);

        // A plain fill goes straight out row by row, with no mask in between - see Shape#spansAt.
        if (stroke == 0 && fill != null && fillBySpans(shape, bounds, fill, fillPalette)) return;

        // Tested once into a mask rather than per probe: an outline asks about the same pixel repeatedly, and
        // for a polygon each of those would be a walk round its edges. Grown by the stroke, since a pixel at
        // the edge has to ask about ones the shape does not cover.
        Rect probed = new Rect(bounds.x() - stroke, bounds.y() - stroke, bounds.width() + 2 * stroke, bounds.height() + 2 * stroke);
        boolean[] inside = mask(shape, probed);
        boolean[] outline = stroke > 0 ? outline(inside, probed, stroke) : null;

        for (int y = bounds.y(); y < bounds.bottom(); y++) {
            for (int x = bounds.x(); x < bounds.right(); x++) {
                int at = (y - probed.y()) * probed.width() + (x - probed.x());
                if (!inside[at]) continue;

                if (outline != null && outline[at]) {
                    pixel(x, y, resolved.primary());
                } else if (fill != null) {
                    pixel(x, y, fill.at(x, y, bounds), fillPalette);
                }
            }
        }
    }

    /** False if the shape would rather be asked pixel by pixel, in which case nothing has been drawn yet. */
    private boolean fillBySpans(Shape shape, Rect bounds, Fill fill, Palette fillPalette) {
        int[][] rows = new int[bounds.height()][];
        for (int y = 0; y < rows.length; y++) {
            rows[y] = shape.spansAt(bounds.y() + y);
            // Asked for every row before any of it is drawn, so a shape that gives up halfway leaves no half-shape
            // behind for the pixel path to draw over.
            if (rows[y] == null) return false;
        }

        for (int y = 0; y < rows.length; y++) {
            int row = bounds.y() + y;
            int[] spans = rows[y];
            for (int i = 0; i + 1 < spans.length; i += 2) {
                int from = Math.max(spans[i], bounds.x());
                int to = Math.min(spans[i + 1], bounds.right());
                for (int x = from; x < to; x++) {
                    pixel(x, row, fill.at(x, row, bounds), fillPalette);
                }
            }
        }
        return true;
    }

    /**
     * Which pixels the shape covers, as a flat grid.
     *
     * <p>An outline needs one of these - it is grown from the boundary, so it has to ask about a pixel's neighbours -
     * but filling it in does not have to be done a pixel at a time. Row by row where the shape can say, which is what
     * makes a bordered shape cost about what an unbordered one does rather than eight times more.
     */
    private static boolean[] mask(Shape shape, Rect area) {
        boolean[] fromSpans = maskFromSpans(shape, area);
        if (fromSpans != null) return fromSpans;

        boolean[] inside = new boolean[area.width() * area.height()];
        for (int j = 0; j < area.height(); j++) {
            for (int i = 0; i < area.width(); i++) {
                inside[j * area.width() + i] = shape.contains(area.x() + i, area.y() + j);
            }
        }
        return inside;
    }

    /** Null if any row would rather be asked pixel by pixel, since half a mask is no use. */
    private static boolean[] maskFromSpans(Shape shape, Rect area) {
        int[][] rows = new int[area.height()][];
        for (int y = 0; y < rows.length; y++) {
            rows[y] = shape.spansAt(area.y() + y);
            if (rows[y] == null) return null;
        }

        boolean[] inside = new boolean[area.width() * area.height()];
        for (int y = 0; y < rows.length; y++) {
            int[] spans = rows[y];
            for (int i = 0; i + 1 < spans.length; i += 2) {
                int from = Math.max(spans[i], area.x());
                int to = Math.min(spans[i + 1], area.right());
                for (int x = from; x < to; x++) {
                    inside[y * area.width() + (x - area.x())] = true;
                }
            }
        }
        return inside;
    }

    /**
     * Which pixels the outline covers: the shape's boundary, thickened inwards by a disc of the stroke width.
     *
     * <p>A disc so a corner is no thicker than a straight edge. Grown from the boundary rather than asked of
     * every pixel, because the answer for a pixel in the middle of a large shape is always no and finding that
     * out costs a scan of its whole neighbourhood - which on a filled shape is most of the work in the frame.
     *
     * <p>At width 1 the boundary is the answer, which is the edge as it was drawn before there was a thickness
     * to ask about.
     */
    private static boolean[] outline(boolean[] inside, Rect area, int width) {
        boolean[] covered = new boolean[inside.length];

        for (int y = 0; y < area.height(); y++) {
            for (int x = 0; x < area.width(); x++) {
                if (!inside[y * area.width() + x] || !onBoundary(inside, area, x, y)) continue;

                for (int dy = -width + 1; dy < width; dy++) {
                    for (int dx = -width + 1; dx < width; dx++) {
                        if (dx * dx + dy * dy >= width * width) continue;

                        int px = x + dx;
                        int py = y + dy;
                        if (px >= 0 && py >= 0 && px < area.width() && py < area.height()) {
                            covered[py * area.width() + px] = true;
                        }
                    }
                }
            }
        }
        return covered;
    }

    /** Inside, with at least one of the four neighbours outside. Off the mask counts as outside. */
    private static boolean onBoundary(boolean[] inside, Rect area, int x, int y) {
        return !at(inside, area, x - 1, y) || !at(inside, area, x + 1, y)
                || !at(inside, area, x, y - 1) || !at(inside, area, x, y + 1);
    }

    private static boolean at(boolean[] inside, Rect area, int x, int y) {
        if (x < 0 || y < 0 || x >= area.width() || y >= area.height()) return false;

        return inside[y * area.width() + x];
    }

    public void triangle(int x1, int y1, int x2, int y2, int x3, int y3, Fill fill, Border border) {
        shape(Shape.triangle(x1, y1, x2, y2, x3, y3), fill, border);
    }

    /** A filled polygon with an outline. Convex or not: the corners are joined in the order given. */
    public void polygon(int[] xs, int[] ys, Fill fill, Border border) {
        shape(Shape.polygon(xs, ys), fill, border);
    }

    public void circle(int centerX, int centerY, int radius, Fill fill, Border border) {
        shape(Shape.circle(centerX, centerY, radius), fill, border);
    }

    public void ellipse(int centerX, int centerY, int radiusX, int radiusY, Fill fill, Border border) {
        shape(Shape.ellipse(centerX, centerY, radiusX, radiusY), fill, border);
    }

    public void line(int x1, int y1, int x2, int y2, Color color) {
        line(x1, y1, x2, y2, color, 1);
    }

    /**
     * A line of any thickness, drawn by stamping a disc along it.
     *
     * <p>A disc rather than a perpendicular bar because it costs nothing to be round: the ends are then caps
     * rather than square cuts, and a corner in a {@link #polyline} joins without a notch on the outside of it.
     */
    public void line(int x1, int y1, int x2, int y2, Color color, int width) {
        if (color == null || width <= 0) return;

        double radius = width / 2.0;
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int error = dx - dy;

        while (true) {
            // At width 1 the disc is the one pixel under it, so there is no thin case to special-case.
            disc(x1, y1, radius, color);
            if (x1 == x2 && y1 == y2) break;

            int doubled = error * 2;
            if (doubled > -dy) {
                error -= dy;
                x1 += sx;
            }
            if (doubled < dx) {
                error += dx;
                y1 += sy;
            }
        }
    }

    private void disc(int centerX, int centerY, double radius, Color color) {
        int reach = (int) Math.floor(radius);
        for (int dy = -reach; dy <= reach; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    pixel(centerX + dx, centerY + dy, color);
                }
            }
        }
    }

    /**
     * A line whose ends were worked out rather than typed, rounded to the nearest pixel at each end.
     *
     * <p>For a ray leaving a point at an angle, where computing the far end and handing it over beats stepping
     * along it by hand. Only the ends are rounded, so the run between them is as straight as the integer
     * version - which is as straight as a pixel grid gets.
     */
    public void line(double x1, double y1, double x2, double y2, Color color) {
        line(x1, y1, x2, y2, color, 1);
    }

    public void line(double x1, double y1, double x2, double y2, Color color, int width) {
        line(pixelOf(x1), pixelOf(y1), pixelOf(x2), pixelOf(y2), color, width);
    }

    /**
     * Rounded, and held to somewhere a surface could plausibly be.
     *
     * <p>The bound is what stops a computed end that came out enormous - a step divided by something near zero -
     * from being walked pixel by pixel on the main thread. Nothing that far out is drawn either way.
     */
    private static int pixelOf(double value) {
        return Math.clamp(Math.round(value), -REACH, REACH);
    }

    /** Comfortably past any canvas, since even a wall is a few hundred pixels. */
    private static final int REACH = 1 << 16;

    /** Corner to corner, left open. {@link #polygon} for the closed one. */
    public void polyline(int[] xs, int[] ys, Color color, int width) {
        if (xs.length < 2 || xs.length != ys.length) return;

        for (int i = 0; i < xs.length - 1; i++) {
            line(xs[i], ys[i], xs[i + 1], ys[i + 1], color, width);
        }
    }

    /** The same with the corners between pixels - see {@link #line(double, double, double, double, Color)}. */
    public void polyline(double[] xs, double[] ys, Color color, int width) {
        if (xs.length < 2 || xs.length != ys.length) return;

        for (int i = 0; i < xs.length - 1; i++) {
            line(xs[i], ys[i], xs[i + 1], ys[i + 1], color, width);
        }
    }

    public void circle(int centerX, int centerY, int radius, Color fill, Color outline) {
        ellipse(centerX, centerY, radius, radius, fill, outline);
    }

    /**
     * Integer-exact ellipse with a one-pixel outline. The outline is derived from the fill mask (an inside
     * pixel with an outside neighbor), which stays symmetric and correct all the way down to a 1px radius - the
     * trigonometric sampling this replaces dropped pixels at small sizes.
     */
    public void ellipse(int centerX, int centerY, int radiusX, int radiusY, Color fill, Color outline) {
        if (radiusX < 0 || radiusY < 0) return;

        ellipse(centerX, centerY, radiusX, radiusY,
                fill == null ? null : Fill.solid(fill),
                outline == null ? Border.none() : Border.solid(1, outline)
        );
    }

    /** The outline of a polygon and nothing else, one pixel thick. */
    public void polygon(Color color, int[] xs, int[] ys) {
        if (xs.length < 2 || xs.length != ys.length) return;

        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            line(xs[i], ys[i], xs[next], ys[next], color);
        }
    }

    public void image(int x, int y, BufferedImage image) {
        if (image == null) return;

        int w = image.getWidth();
        if (pixelRow == null || pixelRow.length < w) pixelRow = new int[w];
        for (int j = 0; j < image.getHeight(); j++) {
            image.getRGB(0, j, w, 1, pixelRow, 0, w);
            for (int i = 0; i < w; i++) pixel(x + i, y + j, pixelRow[i]);
        }
    }

    // ---- text ----

    public int lineStride() {
        return font.lineHeight() + 1;
    }

    /** Lines of {@code text} that fit in {@code maxWidth}. */
    public List<String> wrap(String text, int maxWidth) {
        return font.wrap(text, maxWidth);
    }

    /**
     * Truncates with a trailing ".." instead of wrapping.
     *
     * <p>A single glyph is returned whole even when it does not fit, since ".." is the same width and says less.
     */
    public String ellipsize(String text, int maxWidth) {
        String clean = font.sanitize(text);
        if (font.widthOf(clean) <= maxWidth || clean.length() == 1) return clean;

        for (int length = clean.length() - 1; length > 0; length--) {
            String candidate = clean.substring(0, length) + "..";
            if (font.widthOf(candidate) <= maxWidth) return candidate;
        }
        return "..";
    }

    public void textLine(int x, int y, String text, Color color, boolean shadow) {
        if (text == null || text.isEmpty()) return;

        if (shadow) {
            drawGlyphs(x + 1, y + 1, text, Colors.shadow(color));
        }
        drawGlyphs(x, y, text, color);
    }

    private void drawGlyphs(int x, int y, String text, Color color) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            font.drawChar(this, cursor, y, ch, color);
            cursor += font.charWidth(ch) + font.letterSpacing();
        }
    }


    public void textBlock(Rect box, List<String> lines, Color color, TextAlign align, boolean shadow) {
        int stride = lineStride();
        int totalHeight = lines.size() * stride - (lines.isEmpty() ? 0 : 1);
        int y = box.y() + Math.max(0, (box.height() - totalHeight) / 2);

        for (String line : lines) {
            int width = font.widthOf(line);
            int x = switch (align) {
                case LEFT -> box.x();
                case CENTER -> box.x() + (box.width() - width) / 2;
                case RIGHT -> box.right() - width;
            };
            textLine(x, y, line, color, shadow);
            y += stride;
        }
    }
}
