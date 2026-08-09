package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rotation cases here are the ones the real 26.2 assets were checked against: {@code furnace} pins the y
 * sign because {@code facing=north} is unrotated and {@code facing=east} is {@code y=90}, and the column and
 * log cases then follow from it without being told. Getting either sign backwards mirrors every asymmetric
 * block in a scene and is invisible in the code.
 */
class BlockModelsTest {

    @TempDir
    Path dir;

    private final Map<String, String> files = new HashMap<>();

    /** Closed after every test: an open ZipFile locks its file on Windows, and @TempDir then cannot delete it. */
    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void closeStacks() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    private void blockstate(String name, String json) {
        files.put(AssetStack.BLOCKSTATES + name + ".json", json);
    }

    private void model(String name, String json) {
        files.put(AssetStack.BLOCK_MODELS + name + ".json", json);
    }

    /** A full cube whose six faces are named, so a rotation is visible in which face carries which texture. */
    private void cubeModel(String name, String down, String up, String north, String south, String west, String east) {
        model(name, """
                {
                  "elements": [
                    { "from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                        "down": {"texture": "%s"}, "up": {"texture": "%s"},
                        "north": {"texture": "%s"}, "south": {"texture": "%s"},
                        "west": {"texture": "%s"}, "east": {"texture": "%s"}
                    }}
                  ]
                }
                """.formatted(down, up, north, south, west, east));
    }

    private BlockModels models() throws IOException {
        return models(texture -> BakedState.Alpha.OPAQUE);
    }

    private BlockModels models(BlockModels.TextureAlpha alpha) throws IOException {
        Map<String, String> all = new HashMap<>(Zips.completeBase("26.2"));
        all.putAll(files);
        AssetPack pack = AssetPack.open(Zips.write(dir.resolve("assets-" + open.size() + ".zip"), all));
        AssetStack stack = AssetStack.of(List.of(), pack, "26.2");
        open.add(stack);
        return new BlockModels(stack, alpha);
    }

    private String textureOn(BakedState state, Direction side) {
        for (BakedElement element : state.elements()) {
            BakedFace face = element.face(side);
            if (face != null) return face.texture();
        }
        return null;
    }

    // --- parent chain and texture variables ---

    @Test
    void parentSuppliesElementsAndTheChildSuppliesTextures() throws IOException {
        blockstate("thing", """
                {"variants": {"": {"model": "minecraft:block/thing"}}}
                """);
        model("thing", """
                {"parent": "minecraft:block/cube_all_ish", "textures": {"all": "block/thing_texture"}}
                """);
        cubeModel("cube_all_ish", "#all", "#all", "#all", "#all", "#all", "#all");

        BakedState state = models().bake("minecraft:thing");

        assertEquals(1, state.elements().size());
        assertTrue(state.fullCube());
        for (Direction side : Direction.values()) {
            assertEquals("block/thing_texture", textureOn(state, side), side.key());
        }
    }

    /** Three deep, because 2314 of 2657 vanilla models get their elements from an ancestor rather than a parent. */
    @Test
    void variablesResolveThroughSeveralGenerations() throws IOException {
        blockstate("deep", """
                {"variants": {"": {"model": "block/deep"}}}
                """);
        model("deep", """
                {"parent": "block/middle", "textures": {"end": "block/log_top", "side": "block/log_side"}}
                """);
        model("middle", """
                {"parent": "block/base", "textures": {"down": "#end", "up": "#end", "north": "#side", "south": "#side", "west": "#side", "east": "#side"}}
                """);
        cubeModel("base", "#down", "#up", "#north", "#south", "#west", "#east");

        BakedState state = models().bake("minecraft:deep");

        assertEquals("block/log_top", textureOn(state, Direction.UP));
        assertEquals("block/log_side", textureOn(state, Direction.NORTH));
    }

    @Test
    void childTextureBeatsParent() throws IOException {
        blockstate("thing", """
                {"variants": {"": {"model": "block/child"}}}
                """);
        model("child", """
                {"parent": "block/parent", "textures": {"all": "block/child_texture"}}
                """);
        model("parent", """
                {"textures": {"all": "block/parent_texture"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all"}}}
                ]}
                """);

        assertEquals("block/child_texture", textureOn(models().bake("minecraft:thing"), Direction.UP));
    }

    @Test
    void aParentCycleTerminatesInsteadOfHanging() throws IOException {
        blockstate("loop", """
                {"variants": {"": {"model": "block/a"}}}
                """);
        model("a", "{\"parent\": \"block/b\"}");
        model("b", "{\"parent\": \"block/a\"}");

        assertSame(BakedState.EMPTY, models().bake("minecraft:loop"));
    }

    // --- rotation ---

    /** The oracle: furnace leaves facing=north alone and rotates the rest, so y=90 has to carry north to east. */
    @Test
    void yRotationCarriesTheFrontFaceRound() throws IOException {
        blockstate("furnace_ish", """
                {"variants": {
                    "facing=north": {"model": "block/furnace_ish"},
                    "facing=east": {"model": "block/furnace_ish", "y": 90},
                    "facing=south": {"model": "block/furnace_ish", "y": 180},
                    "facing=west": {"model": "block/furnace_ish", "y": 270}
                }}
                """);
        cubeModel("furnace_ish", "#top", "#top", "#front", "#side", "#side", "#side");
        model("furnace_ish_textures", "{}");
        files.put(AssetStack.BLOCK_MODELS + "furnace_ish.json", files.get(AssetStack.BLOCK_MODELS + "furnace_ish.json")
                .replace("\"elements\"", "\"textures\": {\"front\": \"block/front\", \"side\": \"block/side\", \"top\": \"block/top\"}, \"elements\""));

        BlockModels models = models();

        assertEquals("block/front", textureOn(models.bake("minecraft:furnace_ish[facing=north]"), Direction.NORTH));
        assertEquals("block/front", textureOn(models.bake("minecraft:furnace_ish[facing=east]"), Direction.EAST));
        assertEquals("block/front", textureOn(models.bake("minecraft:furnace_ish[facing=south]"), Direction.SOUTH));
        assertEquals("block/front", textureOn(models.bake("minecraft:furnace_ish[facing=west]"), Direction.WEST));
    }

    /** A log: ends on up and down unrotated, on north and south at x=90, on west and east at x=90 plus y=90. */
    @Test
    void xRotationLaysAColumnDownAndYThenTurnsIt() throws IOException {
        blockstate("log_ish", """
                {"variants": {
                    "axis=y": {"model": "block/log_ish"},
                    "axis=z": {"model": "block/log_ish", "x": 90},
                    "axis=x": {"model": "block/log_ish", "x": 90, "y": 90}
                }}
                """);
        model("log_ish", """
                {"textures": {"end": "block/log_top", "side": "block/log_side"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {
                        "down": {"texture": "#end"}, "up": {"texture": "#end"},
                        "north": {"texture": "#side"}, "south": {"texture": "#side"},
                        "west": {"texture": "#side"}, "east": {"texture": "#side"}
                    }}
                ]}
                """);

        BlockModels models = models();

        BakedState upright = models.bake("minecraft:log_ish[axis=y]");
        assertEquals("block/log_top", textureOn(upright, Direction.UP));
        assertEquals("block/log_top", textureOn(upright, Direction.DOWN));

        BakedState alongZ = models.bake("minecraft:log_ish[axis=z]");
        assertEquals("block/log_top", textureOn(alongZ, Direction.NORTH));
        assertEquals("block/log_top", textureOn(alongZ, Direction.SOUTH));
        assertEquals("block/log_side", textureOn(alongZ, Direction.UP));

        BakedState alongX = models.bake("minecraft:log_ish[axis=x]");
        assertEquals("block/log_top", textureOn(alongX, Direction.WEST));
        assertEquals("block/log_top", textureOn(alongX, Direction.EAST));
        assertEquals("block/log_side", textureOn(alongX, Direction.UP));
    }

    /** Rotation has to move the box too, or a rotated stair keeps its geometry and only repaints. */
    @Test
    void rotationMovesGeometryNotOnlyFaces() throws IOException {
        blockstate("slab_ish", """
                {"variants": {
                    "state=flat": {"model": "block/slab_ish"},
                    "state=tipped": {"model": "block/slab_ish", "x": 90}
                }}
                """);
        model("slab_ish", """
                {"textures": {"all": "block/planks"}, "elements": [
                    { "from": [0,0,0], "to": [16,8,16], "faces": {"up": {"texture": "#all"}}}
                ]}
                """);

        BlockModels models = models();

        BakedElement flat = models.bake("minecraft:slab_ish[state=flat]").elements().getFirst();
        assertEquals(0, flat.fromY());
        assertEquals(8, flat.toY());

        // A bottom slab tipped by x=90 becomes the south half: y opens to full and z closes to 8..16.
        BakedElement tipped = models.bake("minecraft:slab_ish[state=tipped]").elements().getFirst();
        assertEquals(0, tipped.fromY());
        assertEquals(16, tipped.toY());
        assertEquals(8, tipped.fromZ());
        assertEquals(16, tipped.toZ());
    }

    // --- blockstate schemas ---

    @Test
    void multipartAppliesEveryMatchingPart() throws IOException {
        blockstate("fence_ish", """
                {"multipart": [
                    {"apply": {"model": "block/post"}},
                    {"apply": {"model": "block/arm"}, "when": {"north": "true"}},
                    {"apply": {"model": "block/arm", "y": 90}, "when": {"east": "true"}}
                ]}
                """);
        model("post", """
                {"textures": {"all": "block/planks"}, "elements": [{"from": [6,0,6], "to": [10,16,10], "faces": {"up": {"texture": "#all"}}}]}
                """);
        model("arm", """
                {"textures": {"all": "block/planks"}, "elements": [{"from": [7,6,0], "to": [9,9,9], "faces": {"up": {"texture": "#all"}}}]}
                """);

        BlockModels models = models();

        assertEquals(1, models.bake("minecraft:fence_ish[north=false,east=false]").elements().size(), "the post always applies");
        assertEquals(2, models.bake("minecraft:fence_ish[north=true,east=false]").elements().size());
        assertEquals(3, models.bake("minecraft:fence_ish[north=true,east=true]").elements().size());
    }

    @Test
    void multipartHandlesOrAndAlternatives() throws IOException {
        blockstate("thing", """
                {"multipart": [
                    {"apply": {"model": "block/box"}, "when": {"OR": [{"a": "true"}, {"b": "true"}]}},
                    {"apply": {"model": "block/box"}, "when": {"shape": "one|two"}}
                ]}
                """);
        model("box", """
                {"textures": {"all": "block/planks"}, "elements": [{"from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all"}}}]}
                """);

        BlockModels models = models();

        assertTrue(models.bake("minecraft:thing[a=false,b=false,shape=three]").isEmpty());
        assertEquals(1, models.bake("minecraft:thing[a=true,b=false,shape=three]").elements().size());
        assertEquals(1, models.bake("minecraft:thing[a=false,b=true,shape=three]").elements().size());
        assertEquals(1, models.bake("minecraft:thing[a=false,b=false,shape=two]").elements().size());
        assertEquals(2, models.bake("minecraft:thing[a=true,b=false,shape=one]").elements().size());
    }

    /** A variant list is the client picking at random for variety. A screenshot has to be repeatable. */
    @Test
    void variantListAlwaysTakesTheFirst() throws IOException {
        blockstate("varied", """
                {"variants": {"": [
                    {"model": "block/varied"},
                    {"model": "block/varied", "y": 90},
                    {"model": "block/varied", "y": 180}
                ]}}
                """);
        cubeModel("varied", "#d", "#u", "#n", "#s", "#w", "#e");
        files.put(AssetStack.BLOCK_MODELS + "varied.json", files.get(AssetStack.BLOCK_MODELS + "varied.json")
                .replace("\"elements\"", "\"textures\": {\"d\":\"block/d\",\"u\":\"block/u\",\"n\":\"block/n\",\"s\":\"block/s\",\"w\":\"block/w\",\"e\":\"block/e\"}, \"elements\""));

        BlockModels models = models();
        for (int i = 0; i < 5; i++) {
            assertEquals("block/n", textureOn(models.bake("minecraft:varied"), Direction.NORTH), "run " + i);
        }
    }

    @Test
    void unknownBlockIsEmpty() throws IOException {
        assertSame(BakedState.EMPTY, models().bake("minecraft:air"));
        assertSame(BakedState.EMPTY, models().bake("minecraft:something_a_pack_never_shipped"));
    }

    // --- alpha ---

    /**
     * Glass declares {@code force_translucent} on a texture whose pixels carry no partial alpha at all, so
     * reading the png alone would never blend it. 110 vanilla models rely on this.
     */
    @Test
    void forceTranslucentOverridesThePixels() throws IOException {
        blockstate("glass_ish", """
                {"variants": {"": {"model": "block/glass_ish"}}}
                """);
        model("glass_ish", """
                {"textures": {"all": {"force_translucent": true, "sprite": "block/glass"}}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all"}}}
                ]}
                """);

        BakedState state = models(texture -> BakedState.Alpha.OPAQUE).bake("minecraft:glass_ish");

        assertEquals(BakedState.Alpha.TRANSLUCENT, state.alpha());
        assertEquals("block/glass", textureOn(state, Direction.UP), "the sprite still has to resolve past the wrapper");
    }

    @Test
    void alphaComesFromTheTextureWhenTheModelIsSilent() throws IOException {
        blockstate("leaves_ish", """
                {"variants": {"": {"model": "block/leaves_ish"}}}
                """);
        model("leaves_ish", """
                {"textures": {"all": "block/oak_leaves"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all"}}}
                ]}
                """);

        BlockModels.TextureAlpha classifier = texture -> texture.equals("block/oak_leaves") ? BakedState.Alpha.CUTOUT : BakedState.Alpha.OPAQUE;
        assertEquals(BakedState.Alpha.CUTOUT, models(classifier).bake("minecraft:leaves_ish").alpha());
    }

    // --- fluids, faces, caching ---

    /** water.json is a texture reference with no elements, because the client renders fluids itself. */
    @Test
    void fluidsAreBuiltRatherThanRead() throws IOException {
        BlockModels models = models();

        BakedState water = models.bake("minecraft:water[level=0]");
        assertFalse(water.fullCube(), "a surface source stands at eight ninths, which is the dip across a pool");
        assertEquals(BakedState.Alpha.TRANSLUCENT, water.alpha());
        assertEquals("block/water_still", textureOn(water, Direction.UP));
        assertEquals(Tints.WATER, water.elements().getFirst().face(Direction.UP).tint(), "water is tinted per biome");
        assertTrue(water.water(), "so a neighbouring block of it can drop the face between them");
        assertTrue(water.elements().getFirst().face(Direction.UP).fluid());

        BakedState lava = models.bake("minecraft:lava[level=0]");
        assertEquals(BakedState.Alpha.OPAQUE, lava.alpha());
        assertEquals("block/lava_still", textureOn(lava, Direction.UP));
        assertFalse(lava.water());
    }

    /**
     * How deep a fluid stands, which is what makes a stream read as running rather than as a full block of water
     * lying in a trench. Only the shape is checked: the heights themselves are vanilla's own arithmetic.
     */
    @Test
    void aFluidIsAsDeepAsItsLevelSays() throws IOException {
        BlockModels models = models();

        float source = models.bake("minecraft:water[level=0]").elements().getFirst().toY();
        float flowing = models.bake("minecraft:water[level=4]").elements().getFirst().toY();
        float falling = models.bake("minecraft:water[level=8]").elements().getFirst().toY();

        assertTrue(flowing < source, "flowing water sits below the source it came from");
        assertEquals(16f, falling, "falling water fills its block whatever the level says");

        // What keeps an ocean from coming out as steps: only the surface is short.
        BakedState submerged = models.bake("minecraft:water[level=0]", true);
        assertEquals(16f, submerged.elements().getFirst().toY());
        assertTrue(submerged.fullCube());
    }

    /** How deep it stands decides how tall the box is and nothing else - a shallow stream is lit like a full block. */
    @Test
    void aFluidIsShadedWhateverDepthItStandsAt() throws IOException {
        BlockModels models = models();

        assertTrue(models.bake("minecraft:water[level=0]").elements().getFirst().shade(), "a source");
        assertTrue(models.bake("minecraft:water[level=4]").elements().getFirst().shade(), "a shallow stream");
        assertTrue(models.bake("minecraft:lava[level=6]").elements().getFirst().shade(), "and lava the same");
    }

    /**
     * The block's own model says nothing about the water it stands in, because vanilla draws the fluid separately.
     * Without this a waterlogged stair in a pool is a dry hole in it.
     */
    @Test
    void waterloggedBlocksGetTheirWater() throws IOException {
        blockstate("oak_stairs", """
                {"variants": {"waterlogged=false": {"model": "block/step"}, "waterlogged=true": {"model": "block/step"}}}
                """);
        model("step", """
                {"textures": {"all": "block/planks"}, "elements": [
                    { "from": [0,0,0], "to": [16,8,16], "faces": {"up": {"texture": "#all"}}}
                ]}
                """);

        BlockModels models = models();

        BakedState dry = models.bake("minecraft:oak_stairs[waterlogged=false]");
        assertEquals(1, dry.elements().size());
        assertFalse(dry.water());

        BakedState wet = models.bake("minecraft:oak_stairs[waterlogged=true]");
        assertEquals(2, wet.elements().size(), "the stair plus a cube of water");
        assertTrue(wet.water());
        assertEquals(BakedState.Alpha.TRANSLUCENT, wet.alpha());
        assertEquals("block/water_still", wet.elements().getLast().face(Direction.UP).texture());
    }

    /** Kelp and seagrass have no waterlogged property and are only ever placed in water. */
    @Test
    void alwaysFloodedBlocksGetWaterWithoutBeingTold() throws IOException {
        blockstate("kelp", """
                {"variants": {"age=0": {"model": "block/kelp"}}}
                """);
        model("kelp", """
                {"textures": {"all": "block/kelp"}, "elements": [
                    { "from": [0,0,8], "to": [16,16,8], "faces": {"north": {"texture": "#all"}}}
                ]}
                """);

        BakedState kelp = models().bake("minecraft:kelp[age=0]");

        assertTrue(kelp.water());
        assertEquals(2, kelp.elements().size());
    }

    /**
     * A cross is two flat planes turned 45 degrees, and the turn is the whole reason it reads as a plant rather
     * than a decal. It also has to be rescaled, or the X shrinks inside its own block.
     */
    @Test
    void elementRotationSurvivesTheBake() throws IOException {
        blockstate("short_grass", """
                {"variants": {"": {"model": "block/cross"}}}
                """);
        model("cross", """
                {"textures": {"cross": "block/short_grass"}, "elements": [
                    { "from": [0.8, 0, 8], "to": [15.2, 16, 8], "shade": false,
                      "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
                      "faces": {"north": {"texture": "#cross"}, "south": {"texture": "#cross"}}}
                ]}
                """);

        BakedElement element = models().bake("minecraft:short_grass").elements().getFirst();
        ElementRotation turn = element.rotation();

        assertNotNull(turn, "an unrotated cross is a single plane facing north");
        assertEquals(1, turn.axis(), "y");
        assertEquals(45f, turn.angle());
        assertTrue(turn.rescale());
        assertEquals(8f, turn.originY());
        assertFalse(element.isFullBlock(), "a turned box has corners outside its own bounds");
    }

    /**
     * A quarter turn of the whole model carries the axis a box turns about along with it, and an axis that comes
     * back reversed turns the other way. Getting this wrong tilts a rotated plant the opposite way.
     */
    @Test
    void blockstateRotationCarriesTheElementAxis() throws IOException {
        blockstate("thing", """
                {"variants": {"": {"model": "block/thing", "x": 90}}}
                """);
        model("thing", """
                {"textures": {"all": "block/planks"}, "elements": [
                    { "from": [0,0,8], "to": [16,16,8],
                      "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 22.5},
                      "faces": {"north": {"texture": "#all"}}}
                ]}
                """);

        ElementRotation turn = models().bake("minecraft:thing").elements().getFirst().rotation();

        // x=90 tips up onto the model's own -Z, so the y axis comes back pointing north and the turn reverses.
        assertEquals(2, turn.axis(), "z");
        assertEquals(-22.5f, turn.angle());
    }

    /**
     * Every one of these blocks states the same {@code tintindex: 0} and the client colors them from different
     * places. Drawn with the grass color, leaves are a green that changes with the biome and is wrong in all of
     * them.
     */
    @Test
    void tintIndexResolvesPerBlock() throws IOException {
        for (String block : List.of("grass_block", "oak_leaves", "spruce_leaves", "birch_leaves", "cherry_leaves", "vine")) {
            blockstate(block, "{\"variants\": {\"\": {\"model\": \"block/tinted\"}}}");
        }
        model("tinted", """
                {"textures": {"all": "block/thing"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all", "tintindex": 0}}}
                ]}
                """);

        BlockModels models = models();

        assertEquals(Tints.GRASS, tintOn(models, "grass_block"));
        assertEquals(Tints.FOLIAGE, tintOn(models, "oak_leaves"));
        assertEquals(Tints.FOLIAGE, tintOn(models, "vine"));
        assertEquals(Tints.EVERGREEN, tintOn(models, "spruce_leaves"), "spruce is a fixed green in vanilla");
        assertEquals(Tints.BIRCH, tintOn(models, "birch_leaves"));
        assertEquals(Tints.NONE, tintOn(models, "cherry_leaves"), "pink already, so the client leaves it alone");

        assertNotEquals(0, Tints.fixed(Tints.EVERGREEN), "a fixed tint needs no world to resolve it");
        assertEquals(0, Tints.fixed(Tints.GRASS), "and a biome one does");
    }

    private static int tintOn(BlockModels models, String block) {
        return models.bake("minecraft:" + block).elements().getFirst().face(Direction.UP).tint();
    }

    /** A face the model does not draw has to stay absent, or a ray cannot pass through the inside of a stair. */
    @Test
    void undrawnFacesStayNull() throws IOException {
        blockstate("step", """
                {"variants": {"": {"model": "block/step"}}}
                """);
        model("step", """
                {"textures": {"all": "block/planks"}, "elements": [
                    { "from": [0,8,0], "to": [16,16,8], "faces": {"up": {"texture": "#all"}, "north": {"texture": "#all"}}}
                ]}
                """);

        BakedElement element = models().bake("minecraft:step").elements().getFirst();

        assertNotNull(element.face(Direction.UP));
        assertNotNull(element.face(Direction.NORTH));
        assertNull(element.face(Direction.DOWN), "the underside is internal and the model culls it");
        assertNull(element.face(Direction.EAST));
    }

    @Test
    void uvAndRotationAndTintSurvive() throws IOException {
        blockstate("thing", """
                {"variants": {"": {"model": "block/thing"}}}
                """);
        model("thing", """
                {"textures": {"all": "block/grass_block_top"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {
                        "up": {"texture": "#all", "uv": [0, 8, 16, 16], "rotation": 180, "tintindex": 0}
                    }}
                ]}
                """);

        BakedFace face = models().bake("minecraft:thing").elements().getFirst().face(Direction.UP);

        assertEquals(0, face.u1());
        assertEquals(8, face.v1());
        assertEquals(16, face.u2());
        assertEquals(16, face.v2());
        assertEquals(180, face.rotation());
        assertEquals(0, face.tint());
    }

    @Test
    void fullCubeNeedsEveryElementToFillTheBlock() throws IOException {
        blockstate("layered", """
                {"variants": {"": {"model": "block/layered"}}}
                """);
        // Two coincident full cubes, which is exactly what grass_block is: base plus a tinted side overlay.
        model("layered", """
                {"textures": {"all": "block/planks"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all"}}},
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"north": {"texture": "#all"}}}
                ]}
                """);
        blockstate("partly", """
                {"variants": {"": {"model": "block/partly"}}}
                """);
        model("partly", """
                {"textures": {"all": "block/planks"}, "elements": [
                    { "from": [0,0,0], "to": [16,16,16], "faces": {"up": {"texture": "#all"}}},
                    { "from": [0,0,0], "to": [16,8,16], "faces": {"up": {"texture": "#all"}}}
                ]}
                """);

        BlockModels models = models();
        assertTrue(models.bake("minecraft:layered").fullCube(), "two coincident full cubes are still a full cube");
        assertFalse(models.bake("minecraft:partly").fullCube());
    }

    @Test
    void statesAreBakedOnceAndCached() throws IOException {
        blockstate("thing", """
                {"variants": {"": {"model": "block/thing"}}}
                """);
        cubeModel("thing", "#a", "#a", "#a", "#a", "#a", "#a");

        BlockModels models = models();
        BakedState first = models.bake("minecraft:thing");
        BakedState second = models.bake("minecraft:thing");

        assertSame(first, second);
        assertEquals(1, models.size());
    }

    /**
     * Baking from several threads at once into a cold cache, which is what the first capture after a server start
     * actually does and what used to fail it with a {@link java.util.ConcurrentModificationException}.
     *
     * <p>The trace runs on a pool and bakes states on demand, so the first capture has every thread inserting into a
     * cache that is still empty. Only the first: by the second, everything in view was cached and nothing was being
     * inserted any more, which made a threading bug look like a startup one.
     *
     * <p><b>Each thread starts at a different point in the list, and that is what makes this test work at all.</b>
     * A first version had them all walk the same order and it passed against the broken code, because the first
     * thread won every race and the others found each state already there - so the insert that races never happened
     * twice at once. Staggering them is what puts two threads inside the cache at the same moment.
     */
    @Test
    void bakingFromManyThreadsAtOnceDoesNotFail() throws Exception {
        int count = 200;
        for (int i = 0; i < count; i++) {
            blockstate("thing" + i, """
                    {"variants": {"": {"model": "block/thing%d"}}}
                    """.formatted(i));
            cubeModel("thing" + i, "#a", "#a", "#a", "#a", "#a", "#a");
        }

        BlockModels models = models();
        int threads = 6;
        List<Callable<Integer>> bakers = new ArrayList<>();
        for (int thread = 0; thread < threads; thread++) {
            int from = thread * (count / threads);
            bakers.add(() -> {
                int baked = 0;
                for (int step = 0; step < count; step++) {
                    baked += models.bake("minecraft:thing" + (from + step) % count).isEmpty() ? 0 : 1;
                }
                return baked;
            });
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (Future<Integer> done : pool.invokeAll(bakers)) {
                // get() rethrows whatever its thread hit, which is the point: a failure in here is a failed capture
                // out there.
                assertEquals(count, done.get(), "every state should have baked on every thread");
            }
        }
        assertEquals(count, models.size());
    }

    @Test
    void stateStringsSplitIntoIdAndProperties() {
        assertEquals("oak_log", BlockModels.blockId("minecraft:oak_log[axis=y]"));
        assertEquals("stone", BlockModels.blockId("minecraft:stone"));
        assertEquals("stone", BlockModels.blockId("stone"));

        assertEquals(Map.of("axis", "y"), BlockModels.properties("minecraft:oak_log[axis=y]"));
        assertEquals(Map.of("facing", "east", "lit", "false"), BlockModels.properties("minecraft:furnace[facing=east,lit=false]"));
        assertEquals(Map.of(), BlockModels.properties("minecraft:stone"));
    }
}
