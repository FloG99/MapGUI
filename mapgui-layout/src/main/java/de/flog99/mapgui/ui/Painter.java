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
    private final TextFont font;
    private Rect clip;

    /** Built on first use, since a screen with no gradients never needs it. */
    private Palette dithered;

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

    // ---- pixels ----

    public void pixel(int x, int y, byte color) {
        if (clip.contains(x, y) && surface.inBounds(x, y)) {
            surface.set(x, y, color);
        }
    }

    public void pixel(int x, int y, Color color) {
        pixel(x, y, color, palette);
    }

    private void pixel(int x, int y, Color color, Palette with) {
        if (color == null) return;

        int alpha = color.getAlpha();
        if (alpha == 0) return;

        if (alpha == 255) {
            if (clip.contains(x, y) && surface.inBounds(x, y)) {
                surface.set(x, y, with.index(color, x, y));
            }
        } else if (clip.contains(x, y) && surface.inBounds(x, y)) {
            Color blended = blend(palette.color(surface.get(x, y)), color, alpha / 255f);
            surface.set(x, y, with.index(blended, x, y));
        }
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

    public void line(int x1, int y1, int x2, int y2, Color color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int error = dx - dy;

        while (true) {
            pixel(x1, y1, color);
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

    public void circle(int centerX, int centerY, int radius, Color fill, Color outline) {
        ellipse(centerX, centerY, radius, radius, fill, outline);
    }

    /**
     * Integer-exact ellipse. The outline is derived from the fill mask (an inside pixel with an
     * outside neighbor), which stays symmetric and correct all the way down to a 1px radius - the
     * trigonometric sampling this replaces dropped pixels at small sizes.
     */
    public void ellipse(int centerX, int centerY, int radiusX, int radiusY, Color fill, Color outline) {
        if (radiusX < 0 || radiusY < 0) return;

        for (int j = -radiusY; j <= radiusY; j++) {
            for (int i = -radiusX; i <= radiusX; i++) {
                if (!insideEllipse(i, j, radiusX, radiusY)) continue;

                boolean edge = outline != null && (
                        !insideEllipse(i - 1, j, radiusX, radiusY)
                                || !insideEllipse(i + 1, j, radiusX, radiusY)
                                || !insideEllipse(i, j - 1, radiusX, radiusY)
                                || !insideEllipse(i, j + 1, radiusX, radiusY)
                );
                pixel(centerX + i, centerY + j, edge ? outline : fill);
            }
        }
    }

    private static boolean insideEllipse(int x, int y, int radiusX, int radiusY) {
        if (radiusX == 0) return x == 0 && Math.abs(y) <= radiusY;
        if (radiusY == 0) return y == 0 && Math.abs(x) <= radiusX;

        long rx = radiusX;
        long ry = radiusY;
        return (long) x * x * ry * ry + (long) y * y * rx * rx <= rx * rx * ry * ry;
    }

    public void polygon(Color color, int[] xs, int[] ys) {
        if (xs.length < 2 || xs.length != ys.length) return;

        for (int i = 0; i < xs.length; i++) {
            int next = (i + 1) % xs.length;
            line(xs[i], ys[i], xs[next], ys[next], color);
        }
    }

    public void image(int x, int y, BufferedImage image) {
        if (image == null) return;

        for (int j = 0; j < image.getHeight(); j++) {
            for (int i = 0; i < image.getWidth(); i++) {
                pixel(x + i, y + j, new Color(image.getRGB(i, j), true));
            }
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
            drawGlyphs(x + 1, y + 1, text, palette.index(darken(color)));
        }
        drawGlyphs(x, y, text, palette.index(color));
    }

    private void drawGlyphs(int x, int y, String text, byte color) {
        int cursor = x;
        for (char ch : text.toCharArray()) {
            font.drawChar(surface, cursor, y, ch, color, clip);
            cursor += font.charWidth(ch) + 1;
        }
    }

    private static Color darken(Color color) {
        return new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4);
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
