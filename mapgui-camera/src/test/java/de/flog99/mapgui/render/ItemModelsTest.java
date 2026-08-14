package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static de.flog99.mapgui.render.Patches.assertPatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A dropped item is a flat sprite turned to face the camera, or a small cube for a block. Both are small enough
 * that the sizes are worth pinning: a sprite that filled the hitbox would be a quarter of what the client draws,
 * and one that filled a block would be four times it.
 */
class ItemModelsTest {

    private static final int SPRITE = 0xFFCC3311;
    private static final int BLOCK = 0xFF3388CC;

    /** Clear on its left half, so a ray that met the sprite's transparent surround is told apart from a miss. */
    private TestWorld world() {
        return new TestWorld()
                .texture("item/diamond", TestWorld.halfClear(SPRITE))
                .texture("block/stone", TestWorld.solid(BLOCK));
    }

    private EntitySnapshot sprite(float facing) {
        EntityModel model = EntityModel.heldSprite(TestWorld.halfClear(SPRITE)).onGround(0.5f);
        return new EntitySnapshot(0.5, 0, 0.5, facing, facing, 0, 1f, model, "item/diamond");
    }

    private EntitySnapshot cube() {
        return new EntitySnapshot(0.5, 0, 0.5, 0, 0, 0, 1f, EntityModel.itemBlock(), "block/stone");
    }

    private int pixel(TestWorld world, EntitySnapshot entity, CameraView view) {
        int[] out = new int[1];
        new RayCaster(world).render(world, view, List.of(entity), 1, 1, out);
        return out[0];
    }

    /** Level with the sprite's middle and looking north at it, so a single ray crosses it head on. */
    private CameraView facingNorthAt(double x, double y) {
        return new CameraView(x, y, 3, 180, 0, 70, 64);
    }

    /**
     * The opaque half draws and the clear half does not - the same texel alpha rule the skin overlays rely on,
     * which is what makes a sprite's transparent surround work at all.
     */
    @Test
    void aSpriteDrawsWhereItIsOpaqueAndLetsItsClearPixelsThrough() {
        TestWorld world = world();

        // Facing north, east is on the camera's right, so a texture reading rightward puts its high u at high x -
        // and the sprite's opaque half is the high one.
        assertPatch(SPRITE, pixel(world, sprite(0), facingNorthAt(0.6, 0.25)), "the opaque half of the sprite");
        assertEquals(TestWorld.SKY, pixel(world, sprite(0), facingNorthAt(0.4, 0.25)), "the clear half is seen through");
    }

    /**
     * Half a block across and half a block tall, which is the {@code ground} transform's half scale - and standing a
     * pixel clear of the floor rather than on it, which is where {@code ItemEntityRenderer} puts one.
     */
    @Test
    void aSpriteIsHalfABlockAcross() {
        TestWorld world = world();

        assertPatch(SPRITE, pixel(world, sprite(0), facingNorthAt(0.6, 0.5)), "just under the top of the sprite");
        assertEquals(TestWorld.SKY, pixel(world, sprite(0), facingNorthAt(0.6, 0.6)), "just over it");
        assertEquals(TestWorld.SKY, pixel(world, sprite(0), facingNorthAt(0.8, 0.25)), "beyond its edge");
    }

    /**
     * And the pixel underneath it, which is the whole of what keeps a dropped item from sinking into the floor at the
     * bottom of its bob.
     */
    @Test
    void aDroppedSpriteNeverQuiteTouchesTheGround() {
        TestWorld world = world();

        assertEquals(TestWorld.SKY, pixel(world, sprite(0), facingNorthAt(0.6, 0.03)), "under the sprite's own bottom");
        assertPatch(SPRITE, pixel(world, sprite(0), facingNorthAt(0.6, 0.1)), "and just inside it");
    }

    /**
     * Two-sided, with the picture painted through the quad rather than onto each face - so it is in the same place
     * whichever side you are on, and from behind it reads mirrored.
     *
     * <p>Vanilla's own two constants say exactly that: a generated item takes uv {@code (0, 0, 16, 16)} on its front
     * face and {@code (16, 0, 0, 16)} on its back, the same rect read the other way along. Painted un-reversed on both
     * faces instead, a sprite reads correctly from either side - which nobody notices on a dropped apple, because a
     * dropped one is always turned to face the camera, and everybody notices on a held bow: right from one side and
     * mirrored from the other.
     */
    @Test
    void theBackOfASpriteIsThePictureSeenThroughIt() {
        TestWorld world = world();
        CameraView fromTheNorth = new CameraView(0.6, 0.25, -3, 0, 0, 70, 64);
        CameraView alsoFromTheNorth = new CameraView(0.4, 0.25, -3, 0, 0, 70, 64);

        assertPatch(SPRITE, pixel(world, sprite(0), fromTheNorth), "the opaque half is where the front has it too");
        assertEquals(TestWorld.SKY, pixel(world, sprite(0), alsoFromTheNorth), "and the clear half likewise");
    }

/**
     * Seen from the side a sprite is its own rim: the outermost column of the picture, one texel thick.
     *
     * <p>Which is what the extrusion is for. An icon is a picture a pixel deep and the client draws that pixel, so a
     * held bow seen edge on is a thin line of colour rather than nothing at all.
     */
    @Test
    void aSpriteSeenEdgeOnIsItsOwnRim() {
        TestWorld world = world();

        assertPatch(SPRITE, pixel(world, sprite(0), new CameraView(3, 0.25, 0.5, 90, 0, 70, 64)), "edge on");
    }

    /** Which is the whole reason the capture works out a yaw: turned to the camera, the quad is a picture again. */
    @Test
    void aSpriteTurnedToTheCameraIsDrawnFromWhereverTheCameraIs() {
        TestWorld world = world();

        // The camera to the east looking west, and the sprite turned to face east - which is yaw 270.
        assertPatch(SPRITE, pixel(world, sprite(270), new CameraView(3, 0.25, 0.4, 90, 0, 70, 64)), "the opaque half");
    }

    /** A quarter of a block on every side, which is what the client scales a block's icon to on the ground. */
    @Test
    void aDroppedBlockIsAQuarterBlockCubeOfItsTexture() {
        TestWorld world = world();

        assertPatch(BLOCK, pixel(world, cube(), facingNorthAt(0.5, 0.125)), "the side of the cube");
        assertPatch(BLOCK, pixel(world, cube(), new CameraView(0.5, 3, 0.5, 0, 90, 70, 64)), "and its top from above");
        assertEquals(TestWorld.SKY, pixel(world, cube(), facingNorthAt(0.5, 0.35)), "above the cube");
    }

    @TempDir
    Path dir;

    private AssetStack stack;

    @AfterEach
    void closeStack() {
        if (stack != null) {
            stack.close();
        }
    }

    /**
     * An atlas over a pack holding exactly these texture paths. Content does not matter - what is under test is
     * which path is picked, and picking one is a question of existence.
     */
    private TextureAtlas atlas(String... textures) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>(Zips.completeBase("26.2"));
        for (String texture : textures) {
            entries.put("assets/minecraft/textures/" + texture + ".png", texture);
        }

        stack = AssetStack.of(List.of(), AssetPack.open(Zips.write(dir.resolve("pack-" + textures.length + ".zip"), entries)), "26.2");
        return new TextureAtlas(stack);
    }

    /** The one layer a dropped sprite or a probed block cube comes to, or null when nothing resolved. */
    private EntitySnapshot resolve(String item, TextureAtlas atlas) {
        BlockModels models = new BlockModels(stack, atlas);
        ItemDefinitions definitions = new ItemDefinitions(stack, new BiomeColors(stack, atlas));
        ItemModels items = new ItemModels(atlas, new BlockItems(models, definitions), models, new ItemPoses(stack, definitions));
        List<EntitySnapshot> layers = items.dropped(item, 0.5, 0, 0.5, 0);
        return layers.isEmpty() ? null : layers.getFirst();
    }

    @Test
    void anItemWithASpriteIsDrawnAsTheSprite() throws IOException {
        EntitySnapshot dropped = resolve("diamond", atlas("item/diamond"));

        assertEquals("item/diamond", dropped.texture());
        assertEquals(9f, dropped.model().height(), "eight pixels of sprite, a pixel clear of the floor");
    }

    /** A block has no sprite of its own, so the block's texture stands in for it. */
    @Test
    void aBlockItemFallsBackToItsBlockTexture() throws IOException {
        EntitySnapshot dropped = resolve("stone", atlas("block/stone"));

        assertEquals("block/stone", dropped.texture());
        assertEquals(4f, dropped.model().height(), "the cube is four pixels tall");
    }

    /** A sprite wins over a block texture of the same name, since it is what the client actually draws. */
    @Test
    void aSpriteIsPreferredToABlockTextureOfTheSameName() throws IOException {
        assertEquals("item/apple", resolve("apple", atlas("item/apple", "block/apple")).texture());
    }

    /**
     * Grass block is the case this order is for: it has a {@code _top} and a {@code _side} and no plain texture,
     * and the top one is the greyscale sheet the client tints by biome, which nothing here does.
     */
    @Test
    void aBlockWithNoPlainTextureTakesItsSideBeforeItsTop() throws IOException {
        TextureAtlas atlas = atlas("block/grass_block_top", "block/grass_block_side");

        assertEquals("block/grass_block_side", resolve("grass_block", atlas).texture());
    }

    @Test
    void aBlockWithOnlyATopTextureTakesThat() throws IOException {
        assertEquals("block/cactus_top", resolve("cactus", atlas("block/cactus_top")).texture());
    }

    /** Slabs and stairs land here: their icon comes from a model, and no rule over the id finds its texture. */
    @Test
    void anItemThatResolvesToNothingIsLeftToTheCaller() throws IOException {
        assertNull(resolve("acacia_slab", atlas("block/acacia_planks")));
    }
}
