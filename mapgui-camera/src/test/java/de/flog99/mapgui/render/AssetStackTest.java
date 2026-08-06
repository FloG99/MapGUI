package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetStackTest {

    @TempDir
    Path dir;

    @Test
    void overlayWinsOverBase() throws IOException {
        Map<String, String> base = Zips.completeBase("26.2");
        base.put(AssetStack.BLOCK_TEXTURES + "grass_block_top.png", "vanilla-grass");

        Map<String, String> pack = Map.of(AssetStack.BLOCK_TEXTURES + "grass_block_top.png", "pack-grass");

        try (AssetPack basePack = AssetPack.open(Zips.write(dir.resolve("base.zip"), base));
             AssetPack overlay = AssetPack.open(Zips.write(dir.resolve("pack.zip"), pack));
             AssetStack stack = AssetStack.of(List.of(overlay), basePack, "26.2")) {

            assertEquals("pack-grass", Zips.text(stack.read(AssetStack.BLOCK_TEXTURES + "grass_block_top.png")));
            // What the pack does not carry still resolves, which is the whole point of layering rather
            // than replacing.
            assertEquals("stone-png", Zips.text(stack.read(AssetStack.BLOCK_TEXTURES + "stone.png")));
        }
    }

    @Test
    void earlierOverlayWinsOverLater() throws IOException {
        String texture = AssetStack.BLOCK_TEXTURES + "stone.png";

        try (AssetPack basePack = AssetPack.open(Zips.write(dir.resolve("base.zip"), Zips.completeBase("26.2")));
             AssetPack first = AssetPack.open(Zips.write(dir.resolve("first.zip"), Map.of(texture, "first")));
             AssetPack second = AssetPack.open(Zips.write(dir.resolve("second.zip"), Map.of(texture, "second")));
             AssetStack stack = AssetStack.of(List.of(first, second), basePack, "26.2")) {

            assertEquals("first", Zips.text(stack.read(texture)));
        }
    }

    @Test
    void missingEverywhereReadsAsNull() throws IOException {
        try (AssetPack basePack = AssetPack.open(Zips.write(dir.resolve("base.zip"), Zips.completeBase("26.2")));
             AssetStack stack = AssetStack.of(List.of(), basePack, "26.2")) {

            assertNull(stack.read(AssetStack.BLOCK_TEXTURES + "nothing_like_this.png"));
            assertFalse(stack.has(AssetStack.BLOCK_TEXTURES + "nothing_like_this.png"));
        }
    }

    /** A pack adding a block the base has never heard of still has to get baked, so listing is a union. */
    @Test
    void listIsTheUnionAcrossLayers() throws IOException {
        Map<String, String> pack = Map.of(AssetStack.BLOCKSTATES + "custom_thing.json", "{}");

        try (AssetPack basePack = AssetPack.open(Zips.write(dir.resolve("base.zip"), Zips.completeBase("26.2")));
             AssetPack overlay = AssetPack.open(Zips.write(dir.resolve("pack.zip"), pack));
             AssetStack stack = AssetStack.of(List.of(overlay), basePack, "26.2")) {

            List<String> states = stack.list(AssetStack.BLOCKSTATES);
            assertTrue(states.contains(AssetStack.BLOCKSTATES + "custom_thing.json"));
            assertTrue(states.contains(AssetStack.BLOCKSTATES + "stone.json"));
            assertEquals(2, states.size());
        }
    }

    /** The same path in two layers is one entry, or the baker would resolve it twice. */
    @Test
    void listDeduplicatesAcrossLayers() throws IOException {
        try (AssetPack basePack = AssetPack.open(Zips.write(dir.resolve("base.zip"), Zips.completeBase("26.2")));
             AssetPack overlay = AssetPack.open(Zips.write(dir.resolve("pack.zip"), Map.of(AssetStack.BLOCKSTATES + "stone.json", "{}")));
             AssetStack stack = AssetStack.of(List.of(overlay), basePack, "26.2")) {

            assertEquals(1, stack.list(AssetStack.BLOCKSTATES).size());
        }
    }

    @Test
    void completenessProbeRejectsATextureOnlyPack() throws IOException {
        Map<String, String> oresOnly = Map.of(
                AssetStack.BLOCK_TEXTURES + "iron_ore.png", "x",
                AssetStack.BLOCK_TEXTURES + "gold_ore.png", "x"
        );

        try (AssetPack pack = AssetPack.open(Zips.write(dir.resolve("ores.zip"), oresOnly))) {
            assertFalse(AssetStack.isComplete(pack));
        }

        try (AssetPack pack = AssetPack.open(Zips.write(dir.resolve("base.zip"), Zips.completeBase("26.2")))) {
            assertTrue(AssetStack.isComplete(pack));
        }
    }

    @Test
    void blockTextureCountIgnoresMcmeta() throws IOException {
        Map<String, String> base = Zips.completeBase("26.2");
        base.put(AssetStack.BLOCK_TEXTURES + "water_still.png.mcmeta", "{\"animation\":{}}");

        try (AssetPack basePack = AssetPack.open(Zips.write(dir.resolve("base.zip"), base));
             AssetStack stack = AssetStack.of(List.of(), basePack, "26.2")) {

            // stone.png and dirt.png, not the mcmeta beside them.
            assertEquals(2, stack.blockTextureCount());
        }
    }
}
