package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block in a hand, drawn from the block's own model rather than as a cube of one texture.
 *
 * <p>Three things here can be wrong without anything failing to draw, so those are what is asserted. Which side of
 * the mesh a side of the block lands on, since the two frames are a half circle apart and getting it backwards
 * mirrors every block that has a front. How a face is sampled, since a UV convention can be upside down or reversed
 * and still be a texture. And where a turned element ends up, since the client turns and then widens along the world
 * axes while a mesh part widens and then turns.
 */
class BlockItemsTest {

    @TempDir
    Path dir;

    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    /** Closed after every test: an open ZipFile locks its file on Windows, and @TempDir then cannot delete it. */
    @AfterEach
    void closeStacks() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    private void definition(String item, String json) {
        files.put(AssetStack.ITEM_DEFINITIONS + item + ".json", json);
    }

    private void model(String name, String json) {
        files.put(AssetStack.BLOCK_MODELS + name + ".json", json);
    }

    private void itemModel(String name, String json) {
        files.put(AssetStack.ITEM_MODELS + name + ".json", json);
    }

    private void texture(String name) {
        files.put("assets/minecraft/textures/" + name + ".png", name);
    }

    /** A block item drawn from a block model, which is what a definition has to say for any of this to happen. */
    private void blockItem(String item, String model) {
        definition(item, """
                {"model": {"type": "minecraft:model", "model": "minecraft:block/%s"}}
                """.formatted(model));
    }

    private ItemModels baked() throws IOException {
        Map<String, String> all = new LinkedHashMap<>(Zips.completeBase("26.2"));
        all.putAll(files);

        AssetStack stack = AssetStack.of(List.of(), AssetPack.open(Zips.write(dir.resolve("pack-" + open.size() + ".zip"), all)), "26.2");
        open.add(stack);

        TextureAtlas atlas = new TextureAtlas(stack);
        BlockModels models = new BlockModels(stack, texture -> BakedState.Alpha.OPAQUE);
        ItemDefinitions definitions = new ItemDefinitions(stack, new BiomeColors(stack, atlas));
        return new ItemModels(atlas, new BlockItems(models, definitions), models, new ItemPoses(stack, definitions));
    }

    private static EntitySnapshot layerOf(List<EntitySnapshot> layers, String texture) {
        return layers.stream().filter(layer -> layer.texture().equals(texture)).findFirst().orElse(null);
    }

    /**
     * Which sides of the mesh one layer draws, so the half turn between the two frames is visible.
     *
     * <p>Down the tree, since a shape that has been placed - on the ground or in a hand - hangs under a part that
     * carries the placement.
     */
    private static List<Direction> sidesOf(EntitySnapshot layer) {
        List<Direction> sides = new ArrayList<>();
        for (MeshCube box : boxesOf(layer.model().parts())) {
            for (Direction side : Direction.values()) {
                if (box.face(side) != null) {
                    sides.add(side);
                }
            }
        }
        return sides;
    }

    private static float[] faceOf(EntityModel model, Direction side) {
        for (MeshCube box : boxesOf(model.parts())) {
            if (box.face(side) != null) return box.face(side);
        }
        return null;
    }

    private static List<MeshCube> boxesOf(List<MeshPart> parts) {
        List<MeshCube> boxes = new ArrayList<>();
        for (MeshPart part : parts) {
            boxes.addAll(part.cubes());
            boxes.addAll(boxesOf(part.children()));
        }
        return boxes;
    }

    /**
     * Every baked UV is worked out by asking a cube where a corner of one of its sides is, so this is the pair that
     * holds them all up: ask for the point at a stated place and the cube has to agree that is where it is.
     *
     * <p>Cheap and worth it because the two are separate pieces of arithmetic. Let them drift and every held block
     * wears its textures turned, rotated per face and differently on each of the six.
     */
    @Test
    void aCubeAgreesWhereTheCornersOfItsOwnSidesAre() {
        MeshCube box = MeshCube.plain(-3, 5, 11, 8, 2, 6);

        for (Direction side : Direction.values()) {
            for (float across = 0; across <= 1; across++) {
                for (float down = 0; down <= 1; down++) {
                    float[] at = box.pointAt(side, across, down);

                    assertEquals(across, box.across(side, at[0], at[1], at[2]), 1e-5, side.key() + " across");
                    assertEquals(down, box.down(side, at[0], at[1], at[2]), 1e-5, side.key() + " down");
                }
            }
        }
    }

    /**
     * One layer per texture, each on the sides the model puts it - and a block's south face on the mesh's north one,
     * which is the half circle between a model that is looked at from +Z and a mesh that faces -Z.
     */
    @Test
    void aBlocksSidesLandOnTheMeshSidesTheHalfTurnPutsThem() throws IOException {
        blockItem("thing", "thing");
        model("thing", """
                {"textures": {
                    "floor": "block/floor", "roof": "block/roof", "front": "block/front",
                    "back": "block/back", "left": "block/left", "right": "block/right"
                }, "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "down": {"texture": "#floor"}, "up": {"texture": "#roof"},
                    "north": {"texture": "#front"}, "south": {"texture": "#back"},
                    "west": {"texture": "#left"}, "east": {"texture": "#right"}
                }}]}
                """);
        for (String name : List.of("floor", "roof", "front", "back", "left", "right")) {
            texture("block/" + name);
        }

        List<EntitySnapshot> layers = baked().held("thing");

        assertEquals(6, layers.size(), "six textures, six layers");
        assertEquals(List.of(Direction.DOWN), sidesOf(layerOf(layers, "block/floor")), "down is its own axis");
        assertEquals(List.of(Direction.UP), sidesOf(layerOf(layers, "block/roof")), "and so is up");
        assertEquals(List.of(Direction.SOUTH), sidesOf(layerOf(layers, "block/front")), "the model's north");
        assertEquals(List.of(Direction.NORTH), sidesOf(layerOf(layers, "block/back")), "and its south, where a sprite keeps its picture");
        assertEquals(List.of(Direction.EAST), sidesOf(layerOf(layers, "block/left")), "the model's west");
        assertEquals(List.of(Direction.WEST), sidesOf(layerOf(layers, "block/right")), "and its east");
    }

    /**
     * A full cube's face is sampled exactly the way the item sprite's picture is.
     *
     * <p>Which is the anchor for the whole conversion, and not a coincidence: a generated item is a quad in the same
     * model box taking the whole texture, so a block face that also takes the whole texture has to come out with the
     * same four corners. The sprite's own are already pinned against the client - so if these agree, the block frame
     * is the client's frame, and if the conversion were mirrored or upside down they would not.
     */
    @Test
    void aBlocksFaceIsSampledLikeASpritesPicture() throws IOException {
        blockItem("plain", "plain");
        model("plain", """
                {"textures": {"all": "block/plain"}, "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "south": {"texture": "#all"}
                }}]}
                """);
        texture("block/plain");

        List<EntitySnapshot> layers = baked().held("plain");

        // An icon with no transparent pixel is one box over the whole frame, which is the case the two can be compared
        // in: both take the whole texture over the whole model box.
        int[] opaque = new int[16 * 16];
        java.util.Arrays.fill(opaque, 0xFF808080);

        assertEquals(1, layers.size());
        // Within a hair, which is all a sprite's picture is pulled inside its own rectangle by so that neither end
        // floors onto the texel past it.
        assertArrayEquals(faceOf(EntityModel.heldSprite(Texture.opaqueOf(16, 16, opaque)), Direction.NORTH),
                faceOf(layers.getFirst().model(), Direction.NORTH), 1e-3f,
                "a whole-texture block face and the sprite's picture read the same four corners");
    }

    /**
     * A turned element where the client turns it, checked against the client's own order rather than against numbers
     * copied out of a run.
     *
     * <p>The client turns the box about the author's origin and then widens it along the world axes; a mesh part
     * widens in its own space and then turns. Those agree only because a rescale is the same factor on both axes
     * across the turn, and that is the kind of thing that is silently wrong - so one corner is put through both.
     */
    @Test
    void aTurnedElementLandsWhereTheClientTurnsItTo() throws IOException {
        blockItem("crossed", "crossed");
        model("crossed", """
                {"textures": {"cross": "block/leaf"}, "elements": [{"from": [0, 0, 8], "to": [16, 16, 8], "rotation":
                    {"origin": [8, 8, 8], "axis": "y", "angle": 45, "rescale": true},
                    "faces": {"north": {"texture": "#cross"}}
                }]}
                """);
        texture("block/leaf");

        MeshPart part = baked().held("crossed").getFirst().model().parts().getFirst();
        MeshCube box = part.cubes().getFirst();

        // The client's answer for the corner at the model's (0, 0, 8): turn 45 degrees about y, widen by 1 / cos 45,
        // then the half circle into this frame.
        double spread = 1 / Math.cos(Math.toRadians(45));
        double turnedX = (0 - 8) * Math.cos(Math.toRadians(45)) * spread;
        double turnedZ = -(0 - 8) * Math.sin(Math.toRadians(45)) * spread;
        double[] client = {16 - (8 + turnedX), 0, 16 - (8 + turnedZ)};

        float[] turn = Turns.part(part.xRot(), part.yRot(), part.zRot());
        float[] ours = Turns.apply(turn, box.maxX() * part.xScale(), box.minY() * part.yScale(), box.maxZ() * part.zScale());

        assertEquals(client[0], ours[0] + part.x(), 1e-3, "across");
        assertEquals(client[1], ours[1] + part.y(), 1e-3, "up");
        assertEquals(client[2], ours[2] + part.z(), 1e-3, "through");
    }

    /**
     * The color a tinted face is multiplied by comes from the item's own definition, and only tinted faces take it.
     *
     * <p>The definition is the authority rather than the block, and they differ: the same {@code oak_leaves} model is
     * drawn a fixed green in a hand and the biome's green in the world, and a pale oak leaf that the world tints is
     * held untinted. Read the block's rule instead and a held pale oak leaf comes out the wrong color.
     */
    @Test
    void aTintedFaceTakesTheColorTheDefinitionStates() throws IOException {
        definition("leafy", """
                {"model": {"type": "minecraft:model", "model": "minecraft:block/leafy",
                    "tints": [{"type": "minecraft:constant", "value": 4764952}]}}
                """);
        model("leafy", """
                {"textures": {"leaf": "block/leaf", "stem": "block/stem"},
                 "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "up": {"texture": "#leaf", "tintindex": 0}, "down": {"texture": "#stem"}
                }}]}
                """);
        texture("block/leaf");
        texture("block/stem");

        List<EntitySnapshot> layers = baked().held("leafy");

        assertEquals(0xFF48B518, layerOf(layers, "block/leaf").tint(), "the tinted face wears the stated color");
        assertEquals(0, layerOf(layers, "block/stem").tint(), "and the untinted one wears none");
    }

    /** An item that states no tint leaves every face alone, whatever the model says about tinting them. */
    @Test
    void aFaceTheDefinitionStatesNoColorForIsDrawnAsItIs() throws IOException {
        blockItem("grey", "grey");
        model("grey", """
                {"textures": {"all": "block/pale"}, "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "up": {"texture": "#all", "tintindex": 0}
                }}]}
                """);
        texture("block/pale");

        assertEquals(0, baked().held("grey").getFirst().tint());
    }

    /**
     * Two elements in the same place, the later one nearer - which is the order the client draws them in.
     *
     * <p>Grass block is why: a full cube of dirt and grass with a second full cube of green side overlay exactly on
     * top of it. Two surfaces at the same distance are a coin toss, and losing the toss draws a held grass block as a
     * cube of dirt.
     */
    @Test
    void anOverlayInTheSamePlaceIsDrawnInFrontOfWhatItCovers() throws IOException {
        blockItem("turfy", "turfy");
        model("turfy", """
                {"textures": {"soil": "block/soil", "turf": "block/turf"}, "elements": [
                    {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"north": {"texture": "#soil"}}},
                    {"from": [0, 0, 0], "to": [16, 16, 16], "faces": {"north": {"texture": "#turf"}}}
                ]}
                """);
        texture("block/soil");
        texture("block/turf");

        List<EntitySnapshot> layers = baked().held("turfy");
        MeshCube soil = layerOf(layers, "block/soil").model().parts().getFirst().cubes().getFirst();
        MeshCube turf = layerOf(layers, "block/turf").model().parts().getFirst().cubes().getFirst();

        assertTrue(turf.maxZ() > soil.maxZ(), "the overlay reaches past the surface it covers");
        assertTrue(turf.maxZ() - soil.maxZ() < 0.1f, "but not far enough to see");
    }

    /**
     * A block whose model says nothing is still drawn, as the cube of one texture every held block used to be.
     *
     * <p>Which is what a datapack block or an asset subset packed before the item definitions were kept comes to, and
     * a recognizable cube beats an empty hand.
     */
    @Test
    void aBlockWithNoModelToReadFallsBackToItsOwnTexture() throws IOException {
        texture("block/mystery");

        List<EntitySnapshot> layers = baked().held("mystery");

        assertEquals(1, layers.size());
        assertEquals("block/mystery", layers.getFirst().texture());
        assertEquals(6, sidesOf(layers.getFirst()).size(), "one texture on all six sides");
    }

    /**
     * A block state drawn away from the world wears the tint its own item states.
     *
     * <p>The only place the answer is written down: a carried block has no biome to ask, and {@code grass_block_top}
     * is flat grey until something colors it.
     */
    @Test
    void aCarriedBlockIsTintedTheWayItsOwnItemStatesRatherThanNotAtAll() throws IOException {
        files.put(AssetStack.BLOCKSTATES + "greenery.json", """
                {"variants": {"": {"model": "minecraft:block/greenery"}}}
                """);
        model("greenery", """
                {"textures": {"top": "block/greenery_top", "side": "block/greenery_side"},
                 "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "up": {"texture": "#top", "tintindex": 0},
                    "north": {"texture": "#side"}
                 }}]}
                """);
        definition("greenery", """
                {"model": {"type": "minecraft:model", "model": "minecraft:block/greenery",
                    "tints": [{"type": "minecraft:constant", "value": 4243520}]}}
                """);
        texture("block/greenery_top");
        texture("block/greenery_side");

        List<EntitySnapshot> layers = baked().displayed("minecraft:greenery", "minecraft:greenery");

        assertEquals(2, layers.size(), "one layer per texture");
        assertEquals(0xFF40C040, layerOf(layers, "block/greenery_top").tint(), "the face that states a tintindex");
        assertEquals(0, layerOf(layers, "block/greenery_side").tint(), "and the one that does not is left alone");
    }

    /**
     * A shape the client draws in code arrives facing the way a block model faces.
     *
     * <p>A mesh and a block model are a half circle apart, and everything downstream of here expects what a block
     * model produces. The two this shows up on are the trident and the shield, the only specials whose definition
     * states no translation: they sit against the box corner rather than about its middle, so a missing half turn
     * swings their whole length across the box and puts a drowned's trident a block and a half to one side.
     *
     * <p>The mesh here is a marker in one half of the box and nowhere near its middle, since a shape centred on the
     * middle turns onto itself and would pass either way.
     */
    @Test
    void aShapeTheClientDrawsInCodeIsTurnedToFaceTheWayABlockModelDoes() throws IOException {
        definition("spear", """
                {"model": {"type": "minecraft:special", "base": "minecraft:item/spear",
                    "model": {"type": "minecraft:trident"}}}
                """);
        texture("entity/trident/trident");

        // After the stack, which installs whatever meshes its own base carries. Built the way the extractor stores a
        // mob-space one: X and Y flipped, and stood up off the ground.
        ItemModels items = baked();
        EntityMeshes.install(Map.of("object.projectile.TridentModel#createLayer",
                List.of(MeshPart.of("spike", List.of(MeshCube.plain(-8, MeshExtractor.GROUND, 0, 8, 2, 2))))));

        List<EntitySnapshot> layers = items.held("spear");

        assertEquals(1, layers.size());
        float[] box = boundsOf(layers.getFirst().model());
        assertEquals(8, box[0], 0.01f, "the near half of the box comes out as the far half");
        assertEquals(16, box[1], 0.01f);
    }

    /** Least and greatest X the geometry of a model reaches, walking the part offsets and turns down the tree. */
    private static float[] boundsOf(EntityModel model) {
        float[] box = {Float.MAX_VALUE, -Float.MAX_VALUE};
        reach(model.parts(), 0, Turns.none(), box);
        return box;
    }

    private static void reach(List<MeshPart> parts, float x, float[] turn, float[] box) {
        for (MeshPart part : parts) {
            float[] offset = Turns.apply(turn, part.x(), part.y(), part.z());
            float at = x + offset[0];
            float[] turned = Turns.times(turn, Turns.part(part.xRot(), part.yRot(), part.zRot()));

            for (MeshCube cube : part.cubes()) {
                for (float cx : new float[]{cube.minX(), cube.maxX()}) {
                    for (float cy : new float[]{cube.minY(), cube.maxY()}) {
                        for (float cz : new float[]{cube.minZ(), cube.maxZ()}) {
                            float reached = at + Turns.apply(turned, cx, cy, cz)[0];
                            box[0] = Math.min(box[0], reached);
                            box[1] = Math.max(box[1], reached);
                        }
                    }
                }
            }
            reach(part.children(), at, turned, box);
        }
    }

    /** And a definition the client draws in code - a banner, a shulker box - is read without throwing. */
    @Test
    void aDefinitionThatNamesNoModelIsNotReadAsOne() throws IOException {
        definition("banner_ish", """
                {"model": {"type": "minecraft:special", "base": "minecraft:item/banner_ish",
                    "model": {"type": "minecraft:banner", "color": "white"}}}
                """);

        assertTrue(baked().held("banner_ish").isEmpty(), "nothing to draw beats a capture that throws");
    }

    /**
     * A dropped block is the same model, shrunk by the client's {@code ground} transform instead of its
     * {@code thirdperson} one.
     *
     * <p>Both, because the two used to disagree: a held block read its model and a dropped one wore a probed texture on
     * all six sides, so the same log had rings in a hand and bark on the floor.
     */
    @Test
    void aDroppedBlockIsTheSameModelAtAQuarterOfTheSize() throws IOException {
        blockItem("logged", "logged");
        model("logged", """
                {"textures": {"end": "block/rings", "side": "block/bark"},
                 "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
                    "up": {"texture": "#end"}, "north": {"texture": "#side"}
                }}]}
                """);
        texture("block/rings");
        texture("block/bark");

        List<EntitySnapshot> dropped = baked().dropped("logged", 0.5, 0, 0.5, 0);

        assertEquals(2, dropped.size(), "one layer per texture, the same as in a hand");
        assertEquals(List.of(Direction.UP), sidesOf(layerOf(dropped, "block/rings")), "rings on top, not all round");
        assertEquals(5f, layerOf(dropped, "block/rings").model().height(), 1e-4,
                "a quarter of a block, which is what the ground transform states, a pixel clear of the floor");
    }

    /**
     * The definition decides, not whether a same-named item texture happens to exist.
     *
     * <p>The client reads the item definition and draws whatever model it names, so a pack that adds
     * {@code textures/item/<id>.png} for a block does not turn that block into a flat icon. Probing for the sprite
     * first would, and no vanilla item can tell the two rules apart: measured on 26.2, <b>zero</b> of the 1537 items
     * have both an item texture and a definition naming a {@code block/} model.
     *
     * <p>The blocks that really are held flat say so in their own definition - a torch names {@code item/torch} and a
     * ladder {@code item/ladder}, and neither has an item texture at all.
     */
    @Test
    void aDefinitionNamingABlockModelBeatsASameNamedItemTexture() throws IOException {
        blockItem("torch_ish", "torch_ish");
        model("torch_ish", """
                {"textures": {"torch": "block/torch_ish"},
                 "elements": [{"from": [7, 0, 7], "to": [9, 10, 9], "faces": {"north": {"texture": "#torch"}}}]}
                """);
        texture("block/torch_ish");
        texture("item/torch_ish");

        List<EntitySnapshot> layers = baked().held("torch_ish");

        assertEquals(1, layers.size());
        assertEquals("block/torch_ish", layers.getFirst().texture(), "the model the definition names, not the icon");
    }

    /** And an item whose definition names no block model is the sprite, which is most of them. */
    @Test
    void anItemWithNoBlockModelIsDrawnFromItsSprite() throws IOException {
        definition("sword_ish", """
                {"model": {"type": "minecraft:model", "model": "minecraft:item/sword_ish"}}
                """);
        texture("item/sword_ish");

        List<EntitySnapshot> layers = baked().held("sword_ish");

        assertEquals(1, layers.size());
        assertEquals("item/sword_ish", layers.getFirst().texture());
    }

    /**
     * The icon comes out of the model's own {@code layer0} rather than off the item's name.
     *
     * <p>Dead coral is the case: its model names {@code block/dead_tube_coral}, there is no
     * {@code item/dead_tube_coral.png} anywhere, and the name rule found nothing - so it fell all the way through to
     * the six-sided cube of one texture that a block with no model gets, and a dropped stalk of coral was a brick.
     */
    @Test
    void anIconIsTheTextureTheModelNamesRatherThanOneNamedAfterTheItem() throws IOException {
        definition("coral", """
                {"model": {"type": "minecraft:model", "model": "minecraft:item/coral"}}
                """);
        itemModel("coral", """
                {"parent": "minecraft:item/generated", "textures": {"layer0": "minecraft:block/coral"}}
                """);
        texture("block/coral");

        List<EntitySnapshot> layers = baked().held("coral");

        assertEquals(1, layers.size());
        assertEquals("block/coral", layers.getFirst().texture());
    }
}
