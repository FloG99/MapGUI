package de.flog99.mapgui;

import de.flog99.mapgui.ui.Dither;
import de.flog99.mapgui.ui.Fill;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Painter;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders every dither mode over the three kinds of content that pick between them, and writes the figure
 * {@code docs/images/dither-modes.png} was made from into {@code build/figure}.
 *
 * <p>A test rather than a script because it is also the one place every mode is asked to draw all three, so a
 * mode that throws on one of them fails here rather than in somebody's screen. Copy the output over the
 * committed figure when the rendering deliberately changes.
 *
 * <p>Tiles are drawn at 2x and the figure is kept under the width a renderer will downscale. Downscaling a
 * dithered tile aliases the pattern into false colour, which would make the figure lie about the thing it is
 * documenting.
 */
class DitherFigureTest {

    private static final Path OUT = Path.of("build/figure");
    private static final int TILE = 128;
    private static final List<Dither> MODES = List.of(Dither.NONE, Dither.ORDERED, Dither.ORDERED_FINE,
            Dither.BLUE_NOISE, Dither.FLOYD_STEINBERG, Dither.ATKINSON, Dither.SIERRA_LITE);

    private static BufferedImage flat(Dither mode) {
        return MapImage.of(de.flog99.mapgui.ui.Ui.Box(Color.BLACK)
                .fill(Fill.solid(new Color(88, 101, 168)).dither(mode)).fill(), TILE, TILE);
    }

    private static BufferedImage ramp(Dither mode) {
        return MapImage.of(de.flog99.mapgui.ui.Ui.Box(Color.BLACK)
                .fill(Fill.gradient(new Color(15, 30, 90), new Color(150, 190, 245), Fill.Direction.VERTICAL).dither(mode))
                .fill(), TILE, TILE);
    }

    private static BufferedImage photo(Dither mode) {
        BufferedImage source = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < TILE; y++) {
            double light = 0.25 + 0.75 * y / (TILE - 1.0);
            for (int x = 0; x < TILE; x++) {
                double across = x / (TILE - 1.0);
                source.setRGB(x, y, new Color((int) (255 * (1 - across) * light),
                        (int) (90 * light), (int) (255 * across * light)).getRGB());
            }
        }
        Node node = de.flog99.mapgui.ui.Ui.Draw(ctx -> {
            Painter painter = ctx.painter();
            Dither previous = painter.pushDither(mode);
            painter.image(ctx.bounds().x(), ctx.bounds().y(), source);
            painter.popDither(previous);
        }).size(TILE, TILE);
        return MapImage.of(node, TILE, TILE);
    }

    @Test
    void everyModeDrawsEveryKindOfContent() throws IOException {
        record Row(String label, java.util.function.Function<Dither, BufferedImage> render) { }
        List<Row> rows = List.of(
                new Row("flat fill", DitherFigureTest::flat),
                new Row("gradient", DitherFigureTest::ramp),
                new Row("photograph", DitherFigureTest::photo));

        int header = 20;
        int gutter = 96;
        int scale = 2;   // map pixels drawn 2x, or any renderer that downscales turns the pattern into moire
        int gap = 4;   // even, so every tile lands on the same dither parity
        // Modes down the page rather than across it: three tiles wide keeps the figure under the width a
        // renderer will downscale, and downscaling a dithered tile is the moire this feature warns about.
        int cell = TILE * scale;
        int width = gutter + rows.size() * (cell + gap);
        int height = header + MODES.size() * (cell + gap);

        BufferedImage figure = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = figure.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(24, 24, 27));
        g.fillRect(0, 0, width, height);

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(new Color(228, 228, 231));
        for (int c = 0; c < rows.size(); c++) {
            g.drawString(rows.get(c).label(), gutter + c * (cell + gap) + 1, header - 6);
        }

        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        for (int r = 0; r < MODES.size(); r++) {
            int y = header + r * (cell + gap);
            g.setColor(new Color(228, 228, 231));
            g.drawString(MODES.get(r).name().replace("_", " "), 4, y + cell / 2);
            for (int c = 0; c < rows.size(); c++) {
                g.drawImage(MapImage.scaled(rows.get(c).render().apply(MODES.get(r)), scale), gutter + c * (cell + gap), y, null);
            }
        }
        g.dispose();

        MapImage.write(figure, OUT.resolve("dither-modes.png"));

        // The figure is the by-product; this is the assertion. A gradient must differ from the same gradient
        // undithered, or a mode is quietly doing nothing.
        BufferedImage plain = ramp(Dither.NONE);
        for (Dither mode : MODES) {
            if (mode == Dither.NONE) continue;
            org.junit.jupiter.api.Assertions.assertFalse(same(plain, ramp(mode)), mode + " left the ramp unchanged");
        }
    }

    private static boolean same(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }
}
