package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetRepackTest {

    @TempDir
    Path dir;

    /** A stand-in for a client jar: something from every subtree a camera reads, plus the bulk that makes it 39 MB. */
    private Path clientJar() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>(Zips.completeBase("26.2"));
        entries.put(AssetStack.BLOCK_TEXTURES + "water_still.png.mcmeta", "{\"animation\":{\"frametime\":2}}");
        entries.put(AssetStack.ENTITY_TEXTURES + "creeper/creeper.png", "creeper-png");

        entries.put(AssetStack.ITEM_TEXTURES + "diamond.png", "diamond-png");

        entries.put("assets/minecraft/sounds/ambient/cave.ogg", "a very large sound");
        entries.put(AssetStack.ITEM_MODELS + "diamond.json", "an item model, which is read for how the item is held");
        entries.put(EntityVariants.REGISTRIES + "cat" + EntityVariants.REGISTRY + "calico.json", "which texture a coat wears");
        entries.put(EntityVariants.REGISTRIES + "damage_type/lava.json", "data, but nothing a camera draws");
        entries.put("assets/minecraft/lang/en_us.json", "language files, which live in the object store anyway");
        entries.put("net/minecraft/client/Minecraft.class", "the actual game");
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0");

        return Zips.write(dir.resolve("client.jar"), entries);
    }

    @Test
    void keepsWhatACameraReadsAndDropsTheRest() throws IOException {
        Path out = dir.resolve("subset.zip");
        int kept = AssetRepack.subset(clientJar(), out);

        try (AssetPack pack = AssetPack.open(out)) {
            assertTrue(pack.has("version.json"), "the version check has nothing to read without it");
            assertTrue(pack.has(AssetStack.BLOCK_TEXTURES + "stone.png"));
            assertTrue(pack.has(AssetStack.BLOCKSTATES + "stone.json"));
            assertTrue(pack.has(AssetStack.BLOCK_MODELS + "cube_all.json"));
            assertTrue(pack.has(AssetStack.ENTITY_TEXTURES + "creeper/creeper.png"), "entity textures are nested a directory deeper");
            assertTrue(pack.has(AssetStack.ITEM_TEXTURES + "diamond.png"), "a dropped item is drawn as its own sprite");
            assertTrue(pack.has(AssetStack.ITEM_MODELS + "diamond.json"), "and a held one is turned the way its model says");
            assertTrue(pack.has(AssetStack.BLOCK_TEXTURES + "water_still.png.mcmeta"), "an animated texture is one strip of frames, and this is what says so");
            assertTrue(pack.has(EntityVariants.REGISTRIES + "cat" + EntityVariants.REGISTRY + "calico.json"), "a coat's texture is stated here and nowhere else");

            assertFalse(pack.has(EntityVariants.REGISTRIES + "damage_type/lava.json"), "the rest of the data is not a camera's business");
            assertFalse(pack.has("assets/minecraft/sounds/ambient/cave.ogg"));
            assertFalse(pack.has("assets/minecraft/lang/en_us.json"));
            assertFalse(pack.has("net/minecraft/client/Minecraft.class"));
            assertFalse(pack.has("META-INF/MANIFEST.MF"));

            assertEquals(10, kept);
            assertTrue(AssetRepack.isCurrent(out), "freshly packed, so stamped with this revision");
        }
    }

    /**
     * A subset packed before a subtree was added has to be refetched rather than reused, and the stamp is the only
     * thing that can say so - the cache is keyed by Minecraft version, which has not changed.
     */
    @Test
    void aSubsetStampedWithAnEarlierRevisionIsStale() throws IOException {
        Map<String, String> older = new LinkedHashMap<>(Zips.completeBase("26.2"));
        older.put(AssetRepack.SUBSET_FILE, Integer.toString(AssetRepack.SUBSET_REVISION - 1));

        assertFalse(AssetRepack.isCurrent(Zips.write(dir.resolve("older.zip"), older)));
    }

    /** The whole point of the repack is that the result opens as a pack with no second code path. */
    @Test
    void resultIsUsableAsABase() throws IOException {
        Path out = dir.resolve("subset.zip");
        AssetRepack.subset(clientJar(), out);

        try (AssetPack pack = AssetPack.open(out)) {
            assertTrue(AssetStack.isComplete(pack));
            assertEquals("stone-png", Zips.text(pack.read(AssetStack.BLOCK_TEXTURES + "stone.png")));
        }
    }
}
