package de.flog99.mapgui.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Any font the JVM can load, rasterized once per character and blitted like a bitmap one.
 *
 * <p>The vanilla map font is one size, one weight and one alphabet. This is a way out of all three: a TrueType
 * file shipped with a plugin, at whatever size the design wants, in whatever script it needs.
 *
 * <p>Glyphs are rendered to coverage rather than to on-or-off pixels, so with {@link #antiAliased} on, the
 * part-covered edge pixels are handed to the painter as translucent colors and blended with whatever is behind
 * them. On a palette of a couple of hundred colors that is worth having at large sizes and mostly noise at
 * small ones, which is why it is a choice rather than always on.
 *
 * <p>Rasterizing is cached and the cache is safe to share, so one font can serve every screen on the server.
 */
public final class AwtFont implements TextFont {

    private static final char FALLBACK = '?';

    private final Font font;
    private final boolean antiAliased;
    private final FontMetrics metrics;
    private final Map<Character, Glyph> glyphs = new ConcurrentHashMap<>();
    private final Map<Integer, AwtFont> derived = new ConcurrentHashMap<>();

    /**
     * A glyph as coverage, 0 for untouched and 255 for solid, laid out row by row.
     *
     * <p>The width is the advance too, since that is what it was rasterized into - so measuring a string is
     * the same cache lookup as drawing it rather than a second trip through the font metrics.
     */
    private record Glyph(byte[] coverage, int width, int height) {
    }

    public AwtFont(Font font, boolean antiAliased) {
        this.font = font;
        this.antiAliased = antiAliased;

        // A one pixel image is enough to be asked for metrics, and nothing is ever drawn into it.
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY).createGraphics();
        graphics.setFont(font);
        this.metrics = graphics.getFontMetrics();
        graphics.dispose();
    }

    /**
     * Loads a TrueType or OpenType font from a stream, which is how a plugin ships its own.
     *
     * <p>The stream is read in full and closed. Load it once and keep it: the file is parsed here, and every
     * screen drawing with the result shares one glyph cache.
     *
     * @throws IOException if the font cannot be read or is not a font
     */
    public static AwtFont load(InputStream stream, float size, boolean antiAliased) throws IOException {
        try (stream) {
            Font loaded = Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(size);
            return new AwtFont(loaded, antiAliased);
        } catch (java.awt.FontFormatException e) {
            throw new IOException("Not a font this JVM can read", e);
        }
    }

    /** One the JVM already has, by name - {@code "SansSerif"}, {@code "Monospaced"} and so on. */
    public static AwtFont named(String family, int style, int size, boolean antiAliased) {
        return new AwtFont(new Font(family, style, size), antiAliased);
    }

    public Font awt() {
        return font;
    }

    public boolean antiAliased() {
        return antiAliased;
    }

    @Override
    public int lineHeight() {
        return metrics.getAscent() + metrics.getDescent();
    }

    /** None: an outline font carries its spacing inside each glyph's advance. */
    @Override
    public int letterSpacing() {
        return 0;
    }

    @Override
    public int widthOf(String text) {
        if (text == null || text.isEmpty()) return 0;

        return metrics.stringWidth(sanitize(text));
    }

    @Override
    public int charWidth(char ch) {
        return glyphOf(ch).width();
    }

    @Override
    public String sanitize(String text) {
        if (text == null) return "";

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            out.append(font.canDisplay(ch) ? ch : FALLBACK);
        }
        return out.toString();
    }

    @Override
    public TextFont styled(boolean bold, boolean italic) {
        int style = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
        if (style == font.getStyle()) return this;

        return derived.computeIfAbsent(style, wanted -> new AwtFont(font.deriveFont(wanted), antiAliased));
    }

    /** Flat, for callers that only have a surface. Coverage is thresholded, so this is the jagged version. */
    @Override
    public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
        Glyph glyph = glyphOf(ch);

        for (int row = 0; row < glyph.height(); row++) {
            for (int column = 0; column < glyph.width(); column++) {
                if ((glyph.coverage()[row * glyph.width() + column] & 0xFF) < 128) continue;

                int px = x + column;
                int py = y + row;
                if (clip.contains(px, py)) {
                    surface.set(px, py, color);
                }
            }
        }
    }

    /** Through the painter, where a part-covered pixel can be blended instead of rounded off. */
    @Override
    public void drawChar(Painter painter, int x, int y, char ch, Color color) {
        Glyph glyph = glyphOf(ch);
        int packed = color.getRGB();

        for (int row = 0; row < glyph.height(); row++) {
            for (int column = 0; column < glyph.width(); column++) {
                int coverage = glyph.coverage()[row * glyph.width() + column] & 0xFF;
                if (coverage == 0) continue;

                painter.pixel(x + column, y + row,
                        coverage == 255 ? packed : packed & 0x00FFFFFF | coverage << 24
                );
            }
        }
    }

    private Glyph glyphOf(char ch) {
        return glyphs.computeIfAbsent(font.canDisplay(ch) ? ch : FALLBACK, this::rasterize);
    }

    /**
     * White on black, read back as coverage.
     *
     * <p>Drawn at the baseline so the glyph sits where the line does, and given the advance width to work in.
     * Anything that overhangs it - the tail of an italic f - is cut off, which is the price of every glyph
     * being a plain rectangle the blitter can walk.
     */
    private Glyph rasterize(char ch) {
        int width = Math.max(1, metrics.charWidth(ch));
        int height = Math.max(1, lineHeight());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antiAliased
                ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
        );
        graphics.setFont(font);
        graphics.setColor(Color.WHITE);
        graphics.drawString(String.valueOf(ch), 0, metrics.getAscent());
        graphics.dispose();

        byte[] coverage = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        return new Glyph(coverage, width, height);
    }
}
