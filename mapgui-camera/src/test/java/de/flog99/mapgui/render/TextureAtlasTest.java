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

class TextureAtlasTest {

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

    /** @param filler given x and y, returns ARGB, so a test can describe a texture rather than build one */
    private void png(String name, int width, int height, Filler filler) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, filler.at(x, y));
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        files.put("assets/minecraft/textures/" + name + ".png", bytes.toByteArray());
    }

    /**
     * A single-channel png, the way Minecraft stores a texture whose every pixel is a neutral grey.
     *
     * <p>{@code TYPE_BYTE_GRAY} is what makes the encoder write color type 0, which is the case that matters: a
     * three-channel image of the same greys goes down a different path and would not test anything.
     */
    private void greyPng(String name, int width, int height, int grey) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.getRaster().setSample(x, y, 0, grey);
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        files.put("assets/minecraft/textures/" + name + ".png", bytes.toByteArray());
    }

    private void mcmeta(String name, String json) {
        files.put("assets/minecraft/textures/" + name + ".png.mcmeta", json.getBytes(StandardCharsets.UTF_8));
    }

    private interface Filler {

        int at(int x, int y);
    }

    private TextureAtlas atlas() throws IOException {
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

        AssetStack stack = AssetStack.of(List.of(), AssetPack.open(zip), "26.2");
        open.add(stack);
        return new TextureAtlas(stack);
    }

    @Test
    void fullyOpaquePixelsAreOpaque() throws IOException {
        png("block/solid", 16, 16, (x, y) -> 0xFF804020);

        assertEquals(BakedState.Alpha.OPAQUE, atlas().classify("block/solid"));
    }

    /** Leaves, bars and tall grass: alpha is only ever fully on or fully off, and the ray passes where it is off. */
    @Test
    void allOrNothingAlphaIsACutout() throws IOException {
        png("block/leafy", 16, 16, (x, y) -> x % 2 == 0 ? 0xFF206020 : 0x00000000);

        assertEquals(BakedState.Alpha.CUTOUT, atlas().classify("block/leafy"));
    }

    @Test
    void partialAlphaIsTranslucent() throws IOException {
        png("block/icy", 16, 16, (x, y) -> 0x8091B7FD);

        assertEquals(BakedState.Alpha.TRANSLUCENT, atlas().classify("block/icy"));
    }

    /** Water is a 16x512 strip on disk. Left uncropped it draws as a very tall smear. */
    @Test
    void anAnimatedStripIsCroppedToItsFirstFrame() throws IOException {
        png("block/flowing", 16, 512, (x, y) -> y < 16 ? 0xFF0000FF : 0xFF00FF00);
        mcmeta("block/flowing", "{\"animation\": {\"frametime\": 2}}");

        Texture texture = atlas().get("block/flowing");

        assertEquals(16, texture.width());
        assertEquals(16, texture.height());
        assertEquals(0xFF0000FF, texture.sample(8, 8, 0), "the first frame is the blue one");
    }

    /** Only when a mcmeta says so - a pack is free to ship a texture that is simply not square. */
    @Test
    void aTallTextureWithoutAnimationMetadataIsLeftAlone() throws IOException {
        png("block/banner", 16, 64, (x, y) -> 0xFF123456);

        assertEquals(64, atlas().get("block/banner").height());
    }

    @Test
    void aMissingTextureIsTheCheckerboardRatherThanAFailure() throws IOException {
        TextureAtlas atlas = atlas();

        Texture texture = atlas.get("block/never_shipped");

        assertEquals(16, texture.width());
        assertEquals(BakedState.Alpha.OPAQUE, texture.alpha());
        assertEquals(0xF800F8, texture.average() & 0xFFFFFF, "magenta reads as wrong texture to any player");
        assertEquals(0, atlas.count(), "the checkerboard is not a decoded texture");
    }

    /** Averaging the transparent pixels in would drag a cutout toward black, and the LOD leans on this. */
    @Test
    void theAverageIgnoresFullyTransparentPixels() throws IOException {
        png("block/half", 16, 16, (x, y) -> y < 8 ? 0xFFFFFFFF : 0x00000000);

        assertEquals(0xFFFFFF, atlas().get("block/half").average() & 0xFFFFFF);
    }

    @Test
    void samplingMapsModelCoordinatesOntoPixels() throws IOException {
        // A gradient in x only, so a sample's column is visible in the value it returns.
        png("block/ramp", 16, 16, (x, y) -> 0xFF000000 | x << 16);

        Texture texture = atlas().get("block/ramp");

        assertEquals(0, texture.sample(0, 0, 0) >> 16 & 0xFF);
        assertEquals(8, texture.sample(8, 0, 0) >> 16 & 0xFF);
        assertEquals(15, texture.sample(15.5f, 0, 0) >> 16 & 0xFF);
    }

    /** Several vanilla models state uv outside 0 to 16 and rely on it repeating. */
    @Test
    void samplingWrapsRatherThanClamping() throws IOException {
        png("block/ramp", 16, 16, (x, y) -> 0xFF000000 | x << 16);

        Texture texture = atlas().get("block/ramp");

        assertEquals(texture.sample(3, 0, 0), texture.sample(19, 0, 0));
        assertEquals(texture.sample(3, 0, 0), texture.sample(-13, 0, 0));
    }

    @Test
    void faceRotationTurnsTheTextureWithoutMovingTheFace() throws IOException {
        // Distinct in both axes, so a rotation that mixes them up is visible.
        png("block/corner", 16, 16, (x, y) -> 0xFF000000 | x << 16 | y << 8);

        Texture texture = atlas().get("block/corner");

        int unrotated = texture.sample(2, 4, 0);
        assertEquals(2, unrotated >> 16 & 0xFF);
        assertEquals(4, unrotated >> 8 & 0xFF);

        // 90 degrees puts what was at v into u.
        int turned = texture.sample(2, 4, 90);
        assertEquals(4, turned >> 16 & 0xFF);
        assertNotEquals(unrotated, turned);

        assertEquals(texture.sample(2, 4, 0), texture.sample(2, 4, 360 % 360));
    }

    /**
     * A single-channel png has to come back as the value it stores.
     *
     * <p>581 vanilla textures are stored that way, including stone, grass_block_top and spruce_leaves. ImageIO hands
     * such a file over in a grey color space, and {@code getRGB} converts out of it rather than copying - a gamma
     * curve, which lifted stone's 143 to 197 and made it read as almost white in game while the same greys inside
     * the indexed copper ore texture came out perfect.
     */
    @Test
    void aSingleChannelTextureKeepsItsOwnValues() throws IOException {
        greyPng("block/stoneish", 16, 16, 143);
        png("block/indexedish", 16, 16, (x, y) -> 0xFF8F8F8F);

        TextureAtlas atlas = atlas();

        assertEquals(0xFF8F8F8F, atlas.get("block/stoneish").argb()[0], "143 stored is 143 read, not brightened");
        assertEquals(atlas.get("block/indexedish").argb()[0], atlas.get("block/stoneish").argb()[0],
                "the same grey written either way has to render the same");
    }

    /**
     * A villager is a bare body with its clothes painted on, so a layer has to show where it is opaque and let what
     * is under it through where it is not - without disturbing the texture it was painted onto, which every other
     * villager in the world is still drawn from.
     */
    @Test
    void aLayerShowsWhereItIsOpaqueAndLetsTheOneUnderItThrough() throws IOException {
        png("entity/villager/villager", 16, 16, (x, y) -> 0xFF102030);
        png("entity/villager/type/plains", 16, 16, (x, y) -> y < 8 ? 0xFF884400 : 0x00000000);

        TextureAtlas atlas = atlas();
        String dressed = atlas.layered("entity/villager/villager", List.of("entity/villager/type/plains"));
        Texture texture = atlas.get(dressed);

        assertEquals(0xFF884400, texture.argb()[0], "the robe where it is painted");
        assertEquals(0xFF102030, texture.argb()[15 * 16], "the body where it is not");
        assertEquals(0xFF102030, atlas.get("entity/villager/villager").argb()[0], "and the bare body left as it was");
    }
}
