package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an item is drawn as, dropped on the ground or held in a hand.
 *
 * <p>The item's own definition decides, the way it does in the client: it names a model, and a model with geometry in
 * it is drawn as that shape rather than as a picture, since a cube of one texture has bark on its end grain wherever
 * it is lying. Only once nothing states a shape is the model's {@code layer0} read, which is where an icon is named -
 * 588 of the 1537 items in 26.2 are one.
 *
 * <p>Asking in that order rather than the other way round is what keeps a pack honest: adding an item texture for a
 * block does not make the client draw a sprite, because the definition still points at the block model.
 *
 * <p>Both dropped and held are extruded along the icon's own outline the way the client extrudes them (see
 * {@link SpriteShape}), and differ only in size: a dropped one is shrunk by whatever its own model's {@code ground}
 * transform states.
 *
 * <p>Ahead of all of it are the shapes the client draws in code - a chest, a head, a shield, a banner - whose
 * definition names no model to read. Those come from the same mesh the block entity is drawn from, placed inside the
 * item's box by the transform the definition itself states.
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

    /** What the client's {@code ground} transform shrinks a block to and an icon to, where a model states neither. */
    private static final float GROUND_BLOCK = 0.25f;

    private static final float GROUND_SPRITE = 0.5f;

    private final TextureAtlas atlas;
    private final BlockItems blocks;
    private final BlockModels models;

    /** For the one thing a dropped item needs out of a model's {@code display} block, which is how far it shrinks. */
    private final ItemPoses poses;

    /** Keyed by texture name, since that is what the shape is built from and what a resource pack changes. */
    private final Map<String, EntityModel> extruded = new ConcurrentHashMap<>();

    /** The same extrusion at the size the ground transform states, since a dropped item is looked up every capture. */
    private final Map<String, EntityModel> grounded = new ConcurrentHashMap<>();

    /** And one of each shape the client draws in code, since placing a mesh in an item box copies its whole tree. */
    private final Map<String, EntityModel> specials = new ConcurrentHashMap<>();

    public ItemModels(TextureAtlas atlas, BlockItems blocks, BlockModels models, ItemPoses poses) {
        this.atlas = atlas;
        this.blocks = blocks;
        this.models = models;
        this.poses = poses;
    }

    /**
     * One dropped item, as one layer per texture, or empty when nothing resolved. A dropped block is the same model a
     * held one is under the client's {@code ground} transform rather than its {@code thirdperson} one, which buys the
     * difference between a log and a cube of bark for a few pixels.
     *
     * @param item   the item id, unqualified and lowercase: {@code diamond_sword}, {@code oak_log}
     * @param facing where the sprite should look, in Bukkit's yaw convention - an icon is a pixel thick and only a
     *               picture from the front, so this wants to be the direction of whoever is watching
     */
    public List<EntitySnapshot> dropped(String item, double x, double y, double z, float facing) {
        List<EntitySnapshot> drawn = special(item);
        if (!drawn.isEmpty()) {
            float shrunk = poses.groundScale(item, GROUND_BLOCK);
            return drawn.stream()
                    .map(layer -> new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                            layer.model().onGround(shrunk), layer.texture(), layer.tint()))
                    .toList();
        }

        // Shrunk by what the item's own model states rather than by one number per kind of shape: heavy core is a
        // block that says a half where the block model it inherits from says a quarter, and drawn at the inherited
        // one it lies on the floor at half the size the client draws it.
        List<EntitySnapshot> model = blocks.layers(item, atlas);
        if (!model.isEmpty()) {
            float shrink = poses.groundScale(item, GROUND_BLOCK);
            return model.stream()
                    .map(layer -> new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                            layer.model().onGround(shrink), layer.texture(), layer.tint()))
                    .toList();
        }

        String sprite = spriteOf(item);
        if (sprite != null) {
            return List.of(snapshot(x, y, z, facing, grounded(sprite, poses.groundScale(item, GROUND_SPRITE)), sprite));
        }

        String texture = blockTexture(item);
        return texture == null ? List.of() : List.of(snapshot(x, y, z, facing, EntityModel.itemBlock(), texture));
    }

    /**
     * The same item at the size its own model states, for putting in a hand, as one layer per texture - a list
     * because a block model states up to seven and a snapshot samples one. Empty when nothing resolved.
     */
    public List<EntitySnapshot> held(String item) {
        List<EntitySnapshot> drawn = special(item);
        if (!drawn.isEmpty()) return drawn;

        List<EntitySnapshot> model = blocks.layers(item, atlas);
        if (!model.isEmpty()) return model;

        String sprite = spriteOf(item);
        if (sprite != null) {
            return List.of(snapshot(0, 0, 0, 0, extruded(sprite), sprite));
        }

        String texture = blockTexture(item);
        return texture == null ? List.of() : List.of(snapshot(0, 0, 0, 0, EntityModel.heldBlock(), texture));
    }

    /**
     * One block state as something else is carrying it, which is what a minecart displays.
     *
     * <p>The block's own model rather than its item's, the way the client resolves one, and the block entity mesh for
     * the blocks the client keeps a built-in model for - a chest has no geometry in its json at all, and a chest
     * minecart with no chest in it is a minecart.
     *
     * @param state {@code BlockData#getAsString()}
     * @param id    the block id, unqualified, for the built-in lookup
     */
    public List<EntitySnapshot> displayed(String state, String id) {
        EntitySnapshot built = builtIn(id);
        if (built != null) return List.of(built);

        return blocks.stateLayers(state, atlas);
    }

    /**
     * One model by name, as its layers, for the shapes the client resolves by name and hangs on nothing - an item
     * frame's own frame is {@code block/item_frame} and there is no block or item that names it.
     *
     * <p>Full size and at the origin, like every other layer here: where it goes is the caller's.
     */
    public List<EntitySnapshot> modelled(String model) {
        return blocks.modelLayers(model, atlas);
    }

    /**
     * How an item sits when something other than a hand is holding it still.
     *
     * @param context {@link ItemPoses#IN_FRAME} or {@link ItemPoses#ON_SHELF}
     */
    public ItemPoses.Pose stated(String item, String context) {
        return poses.stated(item, context);
    }

    /**
     * The block entities the client draws from a built-in model rather than from json, as the mesh and texture each
     * wears. Only the ones something can carry are here - a banner needs data no capture carries anyway.
     */
    private static final Map<String, String> BUILT_IN = Map.of("chest", "entity/chest/normal");

    /** One built-in block entity in the block's own box, or null for the great majority that have json geometry. */
    private EntitySnapshot builtIn(String id) {
        String texture = BUILT_IN.get(AssetStack.pathOf(id));
        if (texture == null) return null;

        EntityMeshes.Mob mesh = EntityMeshes.of(AssetStack.pathOf(id), null, false);
        if (mesh == null) return null;

        // Half turned, since a mesh faces the other way to a block model and everything downstream of here expects
        // what a block model produces - see BlockItems.
        return snapshot(0, 0, 0, 0,
                specials.computeIfAbsent("built-in/" + id, key -> mesh.model().inItemBox().halfTurned()), texture);
    }

    /**
     * Which mesh each shape the client draws in code is drawn from, by the name the definition calls it.
     *
     * <p>The names are vanilla's own {@code SpecialModelRenderers} ids. A head is the odd one: the renderer is the
     * same for all seven and which head it is comes from the item rather than from the definition, so that one is
     * looked up by the item's own name.
     *
     * <p>Missing on purpose: a book and an end cube, which nothing carries.
     */
    private static final Map<String, List<String>> SPECIAL_MESHES = Map.of(
            "chest", List.of("chest"),
            "shulker_box", List.of("shulker_box"),
            "conduit", List.of("conduit"),
            "shield", List.of("shield"),
            "trident", List.of("trident"),
            // A banner is two: the pole and crossbar it hangs from, and the cloth, which is the only part the dye
            // colors and the only part a pattern is drawn on.
            "banner", List.of("banner", "banner_flag"),
            // And a pot is two for the same kind of reason: the clay body and the four sides, which wear whichever
            // sherds were pressed into them. Which sherds those are lives in the stack rather than in the assets, so
            // an item is drawn with the four plain sides every undecorated pot has.
            "decorated_pot", List.of("decorated_pot", "decorated_pot_sides"),
            "copper_golem_statue", List.of("copper_golem_statue")
    );

    /** The meshes a dye colors rather than a texture, which is the cloth of a banner and nothing else so far. */
    private static final String DYED = "_flag";

    /**
     * One shape the client draws in code, placed inside the item's own box the way its definition says, or null for
     * every item that is not one.
     *
     * <p>Read rather than tabulated. Each of these states a translation, a scale and a pair of quaternions, and it is
     * exactly the placing that differs between them: a shulker box stands a block and a half up, a banner is two
     * thirds size, a chest states nothing at all and sits in the box as it comes.
     *
     * <p>The texture is what the definition names where it names one and the mesh's own otherwise, which for a player
     * head is only the default - whose face it wears is the stack's business, and the caller swaps it in with
     * {@link EntitySnapshot#texture}.
     *
     * <p>Half turned after the placing, for the reason {@link #builtIn} is: a mesh faces the other way to a block
     * model, and what reads one of these next expects what a block model produces. Invisible on the ones whose
     * definition centres them in the box - a banner, a head, a chest all turn about their own middle - and a block
     * and a half out on the two that state no translation at all, a trident and a shield, which sit against the box
     * corner and so swing their whole length across it.
     */
    private List<EntitySnapshot> special(String item) {
        ItemDefinitions.Special drawn = blocks.specialOf(item);
        if (drawn == null) return List.of();

        List<String> types = drawn.type().endsWith("head")
                ? List.of(AssetStack.pathOf(item))
                : SPECIAL_MESHES.getOrDefault(drawn.type(), List.of());

        List<EntitySnapshot> layers = new ArrayList<>(types.size());
        for (String type : types) {
            EntityMeshes.Mob mesh = EntityMeshes.of(type, null, false);
            EntityModel built = EntityMeshes.asBuilt(type);
            if (mesh == null || built == null) continue;

            EntitySnapshot layer = snapshot(0, 0, 0, 0,
                    specials.computeIfAbsent(type, key -> built.placedBy(drawn).halfTurned()),
                    textureOf(drawn, mesh.texture()));

            int dye = type.endsWith(DYED) && drawn.color() != null ? Tints.dye(drawn.color()) : 0;
            layers.add(dye == 0 ? layer : layer.tint(dye));
        }
        return List.copyOf(layers);
    }

    /**
     * Which texture a special wears: the one its definition names, resolved against the folder the mesh's own texture
     * sits in, and the mesh's own where it names none.
     *
     * <p>Two spellings, and both are vanilla's own. A copper golem statue names the whole file,
     * {@code minecraft:textures/entity/copper_golem/copper_golem.png}; a chest names one word,
     * {@code minecraft:normal}, which means nothing on its own - so that one's folder comes from the mesh's own
     * texture rather than from a second table.
     */
    private String textureOf(ItemDefinitions.Special drawn, String authored) {
        String named = drawn.texture();
        if (named == null) return authored;

        String whole = named.startsWith(TEXTURES) ? named.substring(TEXTURES.length()) : named;
        if (whole.endsWith(PNG)) {
            whole = whole.substring(0, whole.length() - PNG.length());
        }
        if (atlas.has(whole)) return whole;

        int slash = authored.lastIndexOf('/');
        String beside = slash < 0 ? whole : authored.substring(0, slash + 1) + whole;
        return atlas.has(beside) ? beside : authored;
    }

    /** What a whole texture path is written with either side of the name the atlas keys it by. */
    private static final String TEXTURES = "textures/";

    private static final String PNG = ".png";

    /**
     * A texture named after the block, for one whose model states nothing this can draw - the six-sided cube every
     * block item used to be, and the only guess left in this path.
     */
    private String blockTexture(String item) {
        for (String suffix : BLOCK_SUFFIXES) {
            String texture = AssetStack.beside(item, "block/" + AssetStack.pathOf(item) + suffix);
            if (atlas.has(texture)) return texture;
        }

        return null;
    }

    /**
     * The icon this item draws, or null when nothing resolves.
     *
     * <p>The model's own {@code layer0} first, which is the only place the connection is written down: dead coral is
     * drawn from {@code block/dead_tube_coral} and has no icon of its own at all. Only then the icon named after the
     * item, for a pack's item that ships a png and no model.
     */
    private String spriteOf(String item) {
        String stated = models.sprite(blocks.modelOf(item));
        if (stated != null && atlas.has(stated)) return stated;

        String named = AssetStack.beside(item, "item/" + AssetStack.pathOf(item));
        return atlas.has(named) ? named : null;
    }

    /** One icon's extrusion, built once. Reading the pixels is cheap; doing it per held item per capture is waste. */
    private EntityModel extruded(String texture) {
        return extruded.computeIfAbsent(texture, name -> EntityModel.heldSprite(atlas.get(name)));
    }

    /** The same shape resting on the ground at whatever the {@code ground} transform shrinks it to. */
    private EntityModel grounded(String texture, float shrink) {
        return grounded.computeIfAbsent(texture + " " + shrink, key -> extruded(texture).onGround(shrink));
    }

    /** Head yaw and pitch are the body's, since neither shape has a head to turn. */
    private static EntitySnapshot snapshot(double x, double y, double z, float facing, EntityModel model, String texture) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f, model, texture);
    }
}
