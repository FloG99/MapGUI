package de.flog99.mapgui.render;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an item is drawn as, dropped on the ground or held in a hand.
 *
 * <p>The item's own definition decides, the way it does in the client: it names a model, and a model under
 * {@code block/} is drawn as that block rather than as a picture, since a cube of one texture has bark on its end
 * grain wherever it is lying. Only once nothing states a block model is the sprite at {@code textures/item/<id>.png}
 * probed - 588 of the 1537 items in 26.2 are one, and probing also answers for a pack's items and for anything added
 * since.
 *
 * <p>Asking in that order rather than the other way round is what keeps a pack honest: adding an item texture for a
 * block does not make the client draw a sprite, because the definition still points at the block model.
 *
 * <p>What differs between dropped and held is the picture half, because of how each is seen: a held sprite is looked
 * at from wherever its holder stands, so it is extruded along its own outline the way the client extrudes it (see
 * {@link SpriteShape}), while a dropped one is turned to face the viewer and stays a single flat quad.
 *
 * <p>An instance rather than a static so the extrusions can be cached, tied to the atlas a reload last loaded.
 */
public final class ItemModels {

    /**
     * The block textures worth trying, in the order they read best on a cube.
     *
     * <p>{@code side} ahead of {@code top} because a dropped block is seen from its sides, and because several of
     * the {@code _top} textures are the greyscale ones the client tints by biome - a grass block drawn from
     * {@code grass_block_top} with no tint applied comes out white, and from {@code grass_block_side} it comes out
     * looking like dirt, which is at least a block.
     */
    private static final List<String> BLOCK_SUFFIXES = List.of("", "_side", "_top");

    /** What the client's {@code ground} transform shrinks a block to, which is a quarter of one. */
    private static final float GROUND_BLOCK = 0.25f;

    private final TextureAtlas atlas;
    private final BlockItems blocks;

    /** Keyed by texture name, since that is what the shape is built from and what a resource pack changes. */
    private final Map<String, EntityModel> extruded = new ConcurrentHashMap<>();

    public ItemModels(TextureAtlas atlas, BlockItems blocks) {
        this.atlas = atlas;
        this.blocks = blocks;
    }

    /**
     * One dropped item, as one layer per texture, or empty when nothing resolved. A dropped block is the same model a
     * held one is under the client's {@code ground} transform rather than its {@code thirdperson} one, which buys the
     * difference between a log and a cube of bark for a few pixels.
     *
     * @param item   the item id, unqualified and lowercase: {@code diamond_sword}, {@code oak_log}
     * @param facing where the sprite should look, in Bukkit's yaw convention - a flat quad is only a picture from the
     *               front, so this wants to be the direction of whoever is watching
     */
    public List<EntitySnapshot> dropped(String item, double x, double y, double z, float facing) {
        List<EntitySnapshot> model = blocks.layers(item, atlas);
        if (!model.isEmpty()) {
            return model.stream()
                    .map(layer -> new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                            layer.model().onGround(GROUND_BLOCK), layer.texture(), layer.tint()))
                    .toList();
        }

        String sprite = "item/" + item;
        if (atlas.has(sprite)) {
            return List.of(snapshot(x, y, z, facing, EntityModel.itemSprite(), sprite));
        }

        String texture = blockTexture(item);
        return texture == null ? List.of() : List.of(snapshot(x, y, z, facing, EntityModel.itemBlock(), texture));
    }

    /**
     * The same item at the size its own model states, for putting in a hand, as one layer per texture - a list
     * because a block model states up to seven and a snapshot samples one. Empty when nothing resolved.
     */
    public List<EntitySnapshot> held(String item) {
        List<EntitySnapshot> model = blocks.layers(item, atlas);
        if (!model.isEmpty()) return model;

        String sprite = "item/" + item;
        if (atlas.has(sprite)) {
            return List.of(snapshot(0, 0, 0, 0, extruded(sprite), sprite));
        }

        String texture = blockTexture(item);
        return texture == null ? List.of() : List.of(snapshot(0, 0, 0, 0, EntityModel.heldBlock(), texture));
    }

    /**
     * A texture named after the block, for one whose model states nothing this can draw - the six-sided cube every
     * block item used to be, and the only guess left in this path.
     */
    private String blockTexture(String item) {
        for (String suffix : BLOCK_SUFFIXES) {
            String texture = "block/" + item + suffix;
            if (atlas.has(texture)) return texture;
        }

        return null;
    }

    /** One icon's extrusion, built once. Reading the pixels is cheap; doing it per held item per capture is waste. */
    private EntityModel extruded(String texture) {
        return extruded.computeIfAbsent(texture, name -> EntityModel.heldSprite(atlas.get(name)));
    }

    /** Head yaw and pitch are the body's, since neither shape has a head to turn. */
    private static EntitySnapshot snapshot(double x, double y, double z, float facing, EntityModel model, String texture) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f, model, texture);
    }
}
