package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The colormaps here encode their own coordinates into the pixel, so a lookup can be checked against the index
 * arithmetic rather than against a color somebody read off a screenshot. The index is the part worth pinning:
 * downfall is scaled by temperature before it is used, and forgetting that makes every wet biome too green.
 */
class BiomeColorsTest {

    @TempDir
    Path dir;

    private final Map<String, byte[]> files = new HashMap<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeStacks() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    /** Red carries x and green carries y, so a sampled pixel says where it was sampled from. */
    private void colormap(String name) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                image.setRGB(x, y, 0xFF000000 | x << 16 | y << 8);
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        files.put(AssetStack.COLORMAPS + name + ".png", bytes.toByteArray());
    }

    private void biome(String name, String json) {
        files.put(AssetStack.BIOMES + name + ".json", json.getBytes(StandardCharsets.UTF_8));
    }

    private BiomeColors colors() throws IOException {
        colormap("grass");
        colormap("foliage");

        Map<String, byte[]> all = new HashMap<>();
        Zips.completeBase("26.2").forEach((path, text) -> all.put(path, text.getBytes(StandardCharsets.UTF_8)));
        all.putAll(files);

        Path zip = dir.resolve("assets-" + open.size() + ".zip");
        Files.createDirectories(zip.getParent());
        try (OutputStream file = Files.newOutputStream(zip);
             ZipOutputStream out = new ZipOutputStream(file)) {

            for (Map.Entry<String, byte[]> entry : all.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }

        AssetPack pack = AssetPack.open(zip);
        AssetStack stack = AssetStack.of(List.of(), pack, "26.2");
        open.add(stack);
        return new BiomeColors(stack, new TextureAtlas(stack));
    }

    @Test
    void grassIsIndexedByTemperatureAndScaledDownfall() throws IOException {
        biome("plains", """
                {"temperature": 0.8, "downfall": 0.4, "effects": {"water_color": "#3f76e4"}}
                """);

        BiomeColors.Tint plains = colors().of("plains");

        // x = (1 - 0.8) * 255, y = (1 - 0.4 * 0.8) * 255, both truncated. 50 rather than 51 because 0.8 does not
        // exist as a float: the stored value is a hair above it, so the subtraction lands just under 0.2. The
        // client truncates the same arithmetic and reads the same pixel.
        assertEquals(50, plains.grass() >> 16 & 0xFF);
        assertEquals(173, plains.grass() >> 8 & 0xFF);
        assertEquals(0xFF3F76E4, plains.water());
    }

    @Test
    void hotAndDryLandsInADifferentCornerFromColdAndWet() throws IOException {
        biome("desert", """
                {"temperature": 2.0, "downfall": 0.0, "effects": {}}
                """);
        biome("snowy_taiga", """
                {"temperature": -0.5, "downfall": 0.4, "effects": {}}
                """);

        BiomeColors colors = colors();

        // Both clamp into range before the lookup, so neither can index outside the image.
        assertEquals(0, colors.of("desert").grass() >> 16 & 0xFF, "temperature 2 clamps to 1");
        assertEquals(255, colors.of("desert").grass() >> 8 & 0xFF, "no rain at all");
        assertEquals(255, colors.of("snowy_taiga").grass() >> 16 & 0xFF, "below freezing clamps to 0");
        assertNotEquals(colors.of("desert").grass(), colors.of("snowy_taiga").grass());
    }

    /** Four biomes state a grass or foliage color outright, and deriving one for them is visibly wrong. */
    @Test
    void statedColorsBeatTheColormap() throws IOException {
        biome("badlands", """
                {"temperature": 2.0, "downfall": 0.0,
                 "effects": {"grass_color": "#90814d", "foliage_color": "#9e814d", "water_color": "#3f76e4"}}
                """);

        BiomeColors.Tint badlands = colors().of("badlands");

        assertEquals(0xFF90814D, badlands.grass());
        assertEquals(0xFF9E814D, badlands.foliage());
    }

    @Test
    void theSwampBendsItsGrassRatherThanStatingIt() throws IOException {
        biome("swamp", """
                {"temperature": 0.8, "downfall": 0.9,
                 "effects": {"foliage_color": "#6a7039", "grass_color_modifier": "swamp"}}
                """);

        BiomeColors.Tint swamp = colors().of("swamp");

        assertEquals(0xFF6A7039, swamp.grass(), "the modifier replaces whatever the colormap said");
        assertEquals(0xFF6A7039, swamp.foliage());
    }

    /** Older versions wrote these as packed integers, and a server may still be running one. */
    @Test
    void colorsParseAsHexOrAsAnInteger() throws IOException {
        biome("old", """
                {"temperature": 0.5, "downfall": 0.5, "effects": {"water_color": 4159204}}
                """);

        assertEquals(0xFF3F76E4, colors().of("old").water());
    }

    /** A datapack biome has no definition to read, and the caller has to know that to go and ask the server. */
    @Test
    void anUnknownBiomeIsNullRatherThanAGuess() throws IOException {
        BiomeColors colors = colors();

        assertNull(colors.of("someones_custom_biome"));
        assertNotEquals(0, colors.fromClimate(0.8f, 0.4f).grass(), "but its climate is still enough to resolve one");
    }
}
