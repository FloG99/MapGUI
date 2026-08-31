package de.flog99.mapgui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Draws the two matchers side by side, so the choice can be looked at rather than read about.
 *
 * <p>Off unless asked for with {@code -Pfigures}, because it writes a file into {@code docs/images} and because
 * the answer it illustrates is already asserted in {@code PerceptualMatcherAbTest}. A picture is for deciding
 * with, and the numbers are for holding.
 *
 * <p>Both halves are the same ramps quantized to the same palette, so every difference is the formula.
 */
class MatchingFigureTest {

    private static final Path OUT = Path.of("../docs/images/colour-matching.png");

    /** Ramps chosen for what they show: greys where vanilla wins, saturated ramps where Oklab does. */
    private static final Ramp[] RAMPS = {
            new Ramp("grey", new Color(20, 20, 20), new Color(235, 235, 235)),
            new Ramp("warm grey", new Color(30, 26, 24), new Color(230, 222, 214)),
            new Ramp("green", new Color(20, 60, 25), new Color(150, 230, 120)),
            new Ramp("blue", new Color(18, 30, 80), new Color(140, 190, 245)),
            new Ramp("red to yellow", new Color(140, 20, 20), new Color(245, 220, 60)),
            new Ramp("purple", new Color(40, 12, 70), new Color(215, 150, 245)),
    };

    private record Ramp(String name, Color from, Color to) {
    }

    private static final int WIDTH = 220;
    private static final int BAND = 20;
    private static final int HEADER = 13;
    private static final int GAP = 7;
    private static final int LABEL = 52;
    private static final int SCALE = 2;

    @Test
    @EnabledIfSystemProperty(named = "mapgui.figures", matches = "true")
    void writeTheFigure() throws IOException {
        // Both tables built once, exactly as the plugin builds whichever it was told to - so the dark range
        // keeps its own finer rule in both columns and the only difference on show is the metric.
        de.flog99.mapgui.ui.PaletteLut vanilla = new de.flog99.mapgui.ui.PaletteLut(new BukkitMatcher());
        de.flog99.mapgui.ui.PaletteLut oklab = new de.flog99.mapgui.ui.PaletteLut(new OklabMatcher());

        int rows = RAMPS.length;
        int height = rows * (HEADER + BAND * 2 + GAP) + GAP;
        BufferedImage figure = new BufferedImage(LABEL + WIDTH, height, BufferedImage.TYPE_INT_RGB);

        java.awt.Graphics2D graphics = figure.createGraphics();
        graphics.setColor(new Color(18, 18, 20));
        graphics.fillRect(0, 0, figure.getWidth(), figure.getHeight());
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int y = GAP;
        for (Ramp ramp : RAMPS) {
            graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));
            graphics.setColor(new Color(214, 218, 226));
            graphics.drawString(ramp.name(), 4, y + 9);

            graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9));
            graphics.setColor(new Color(128, 134, 146));
            graphics.drawString("vanilla", 4, y + HEADER + 13);
            graphics.drawString("oklab", 4, y + HEADER + BAND + 13);

            for (int x = 0; x < WIDTH; x++) {
                Color wanted = mix(ramp.from(), ramp.to(), x / (double) (WIDTH - 1));
                paint(figure, LABEL + x, y + HEADER, BAND, drawn(vanilla, wanted));
                paint(figure, LABEL + x, y + HEADER + BAND, BAND, drawn(oklab, wanted));
            }
            y += HEADER + BAND * 2 + GAP;
        }
        graphics.dispose();

        Files.createDirectories(OUT.getParent());
        ImageIO.write(scaled(figure), "png", OUT.toFile());
        System.out.println("wrote " + OUT.toAbsolutePath().normalize());
    }

    /** What a table draws for a colour, which is what a wall would show. */
    private static Color drawn(de.flog99.mapgui.ui.PaletteLut table, Color wanted) {
        return MapColors.INSTANCE.color(table.index(wanted));
    }

    /** Vanilla's weighting, the same one {@code MapColors} fills its table with by default. */
    @SuppressWarnings("removal")
    private static final class BukkitMatcher implements de.flog99.mapgui.ui.Palette {

        @Override
        public byte index(Color color) {
            return org.bukkit.map.MapPalette.matchColor(
                    new Color(color.getRed(), color.getGreen(), color.getBlue()));
        }

        @Override
        public Color color(byte index) {
            return MapColors.INSTANCE.color(index);
        }

        @Override
        public byte[] entries() {
            return MapColors.INSTANCE.entries();
        }
    }

    /** The same search in Oklab, which is what {@code Matching.PERCEPTUAL} fills its table with. */
    private static final class OklabMatcher implements de.flog99.mapgui.ui.Palette {

        @Override
        public byte index(Color color) {
            de.flog99.mapgui.ui.Oklab.Lab from = de.flog99.mapgui.ui.Oklab.of(color);
            byte found = 0;
            double closest = Double.MAX_VALUE;

            for (byte entry : MapColors.INSTANCE.entries()) {
                Color candidate = MapColors.INSTANCE.color(entry);
                if (candidate == null) continue;

                double at = de.flog99.mapgui.ui.Oklab.difference(from, de.flog99.mapgui.ui.Oklab.of(candidate));
                if (at < closest) {
                    closest = at;
                    found = entry;
                }
            }
            return found;
        }

        @Override
        public Color color(byte index) {
            return MapColors.INSTANCE.color(index);
        }

        @Override
        public byte[] entries() {
            return MapColors.INSTANCE.entries();
        }
    }

    private static void paint(BufferedImage into, int x, int top, int height, Color color) {
        for (int y = top; y < top + height && y < into.getHeight(); y++) {
            into.setRGB(x, y, color.getRGB());
        }
    }

    private static Color mix(Color from, Color to, double amount) {
        return new Color(
                (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
                (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
                (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
    }

    /** Nearest neighbour, so a palette entry stays one flat colour rather than being blurred into its neighbour. */
    private static BufferedImage scaled(BufferedImage from) {
        BufferedImage out = new BufferedImage(from.getWidth() * SCALE, from.getHeight() * SCALE, from.getType());
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                out.setRGB(x, y, from.getRGB(x / SCALE, y / SCALE));
            }
        }
        return out;
    }
}
