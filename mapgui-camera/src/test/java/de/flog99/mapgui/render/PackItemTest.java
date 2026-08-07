package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item a resource pack invented, drawn from the pack's own model.
 *
 * <p>Every step of this used to throw the namespace away and go looking in {@code assets/minecraft/}, where a
 * pack's files are never going to be. It failed quietly each time - the item fell back to its material, so a
 * camera made from a knowledge book photographed as a knowledge book, and once the paths were fixed but the
 * geometry was not it photographed as its own texture sheet extruded flat.
 *
 * <p>So this walks the whole chain with a pack that looks like a real one: definition, model, parent, texture.
 */
class PackItemTest {

    @TempDir
    Path dir;

    private AssetStack stack;

    @AfterEach
    void closeStack() {
        if (stack != null) {
            stack.close();
        }
    }

    /** A pack with one item in it, built the way a Blockbench export plus an item definition would be. */
    private void pack(Map<String, String> extra) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>(Zips.completeBase("26.2"));
        entries.put("assets/mapcamera/items/camera.json",
                "{\"model\": {\"type\": \"minecraft:model\", \"model\": \"mapcamera:item/camera\"}}");
        entries.put("assets/mapcamera/models/item/camera.json", """
                {
                  "texture_size": [128, 128],
                  "textures": {"0": "mapcamera:item/camera"},
                  "elements": [
                    {"from": [0, 0, 0], "to": [16, 8, 12],
                     "faces": {"north": {"uv": [0, 0, 4, 2], "texture": "#0"}}}
                  ]
                }
                """);
        entries.put("assets/mapcamera/textures/item/camera.png", "camera-png");
        entries.putAll(extra);

        stack = AssetStack.of(List.of(), AssetPack.open(Zips.write(dir.resolve("pack.zip"), entries)), "26.2");
    }

    private BlockModels models() {
        return new BlockModels(stack, new TextureAtlas(stack));
    }

    @Test
    void theDefinitionIsReadFromThePacksOwnNamespace() throws IOException {
        pack(Map.of());

        ItemDefinitions.Definition definition =
                new ItemDefinitions(stack, new BiomeColors(stack, new TextureAtlas(stack))).of("mapcamera:camera");

        assertEquals("mapcamera:item/camera", definition.model(),
                "the model it names has to keep its namespace, or it is looked for in vanilla's assets");
    }

    @Test
    void theModelIsBakedRatherThanFallingThroughToTheSprite() throws IOException {
        pack(Map.of());

        List<BakedElement> baked = models().shape("mapcamera:item/camera");

        assertFalse(baked.isEmpty(), "a pack's item model has geometry, and geometry is what should be drawn");
        assertEquals(1, baked.size());
    }

    @Test
    void theTextureResolvesUnderThePacksNamespace() throws IOException {
        pack(Map.of());

        TextureAtlas atlas = new TextureAtlas(stack);
        assertTrue(atlas.has("mapcamera:item/camera"), "the pack's own png, at the pack's own path");
        assertFalse(atlas.has("item/camera"), "and not under vanilla's, which is where it used to be hunted for");
    }

    /** A pack model whose parent is vanilla's still finds the parent, since a bare id means minecraft. */
    @Test
    void aVanillaParentStillResolvesFromInsideAPack() throws IOException {
        pack(Map.of(
                "assets/mapcamera/models/item/film.json",
                "{\"parent\": \"item/generated\", \"textures\": {\"layer0\": \"mapcamera:item/film\"}}",
                "assets/minecraft/models/item/generated.json",
                "{\"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 16, 1], \"faces\": {}}]}"));

        assertFalse(models().shape("mapcamera:item/film").isEmpty(),
                "the parent is vanilla's even though the child is not");
    }

    /** The reason none of this may quietly regress into stripping namespaces again. */
    @Test
    void vanillaIdsAreUnchangedByAnyOfIt() {
        assertEquals("block/stone", AssetStack.canonical("minecraft:block/stone"));
        assertEquals("block/stone", AssetStack.canonical("block/stone"));
        assertEquals("mapcamera:item/camera", AssetStack.canonical("mapcamera:item/camera"));

        assertEquals("assets/minecraft/models/block/stone.json", AssetStack.asset("block/stone", "models", ".json"));
        assertEquals("assets/mapcamera/models/item/camera.json", AssetStack.asset("mapcamera:item/camera", "models", ".json"));
    }
}
