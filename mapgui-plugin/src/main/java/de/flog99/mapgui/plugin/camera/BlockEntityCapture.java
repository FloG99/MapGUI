package de.flog99.mapgui.plugin.camera;

import com.destroystokyo.paper.profile.PlayerProfile;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.ItemPoses;
import de.flog99.mapgui.render.TextureAtlas;
import de.flog99.mapgui.render.Tints;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.DyeColor;
import org.bukkit.block.Banner;
import org.bukkit.block.Bell;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Conduit;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.EnchantingTable;
import org.bukkit.block.Shelf;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Skull;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.CopperGolemStatue;
import org.bukkit.block.banner.Pattern;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The blocks whose shape is an entity model rather than block json, copied out in the same tick as everything else.
 *
 * <p>A chest has no geometry in {@code models/block/chest.json} - the client draws it from {@code ChestModel} the way
 * it draws a mob, which is why one baked from the blockstate alone comes out invisible. So they are gathered here and
 * handed to the trace as entities, which they already are as far as it is concerned: a mesh, a texture and a yaw.
 *
 * <p>Read from {@link Chunk#getTileEntities()} rather than by walking the blocks in view. The capture has one tick,
 * and a 64 block box is a quarter of a million block reads to find the six chests in it; the chunk already keeps the
 * list.
 */
final class BlockEntityCapture {

    /** Past this a chest is a couple of pixels, and the same bound the entities use. */
    private static final double MAX_DISTANCE = 64;

    /** A cap for the same reason the entities have one: a storage room should not turn one capture into thousands. */
    private static final int MAX_BLOCK_ENTITIES = 64;

    private BlockEntityCapture() {
    }

    static List<EntitySnapshot> take(Location eye, MobAssets assets, SkinCache skins) {
        World world = eye.getWorld();
        int radius = (int) Math.ceil(MAX_DISTANCE / 16);
        int originX = eye.getBlockX() >> 4;
        int originZ = eye.getBlockZ() >> 4;

        List<EntitySnapshot> drawn = new ArrayList<>();
        double limit = MAX_DISTANCE * MAX_DISTANCE;

        for (int x = originX - radius; x <= originX + radius && drawn.size() < MAX_BLOCK_ENTITIES; x++) {
            for (int z = originZ - radius; z <= originZ + radius && drawn.size() < MAX_BLOCK_ENTITIES; z++) {
                // Never loaded here: a chunk the server does not have is one the trace already draws through.
                if (!world.isChunkLoaded(x, z)) continue;

                for (BlockState state : world.getChunkAt(x, z).getTileEntities()) {
                    if (drawn.size() >= MAX_BLOCK_ENTITIES) break;

                    Block block = state.getBlock();
                    if (block.getLocation().distanceSquared(eye) > limit) continue;

                    drawn.addAll(snapshotOf(block, state, assets, skins));
                }
            }
        }
        return drawn;
    }

    /** Empty for the great many block entities that are drawn from their own block model and need nothing here. */
    private static List<EntitySnapshot> snapshotOf(Block block, BlockState state, MobAssets assets, SkinCache skins) {
        BlockData data = block.getBlockData();
        if (state instanceof Banner banner) return banner(block, data, banner, assets);
        if (state instanceof Skull skull) return one(skull(block, data, skull, skins));
        if (state instanceof ShulkerBox) return one(shulkerBox(block, data));
        if (state instanceof Conduit) return one(middleOf(block, "conduit"));
        if (state instanceof DecoratedPot pot) return decoratedPot(block, data, pot);
        // Asked of the block data rather than the state, since Bukkit gives a statue no state type of its own - the
        // pose and the facing are both properties of the block.
        if (data instanceof CopperGolemStatue statue) return one(statue(block, statue));
        // The book above an enchanting table, which is the whole of what that renderer draws - the table itself is an
        // ordinary block model. Shut and square to the world, since both the page flip and the turn toward whoever is
        // standing there are animations the server does not carry.
        if (state instanceof EnchantingTable) return one(book(block));
        if (state instanceof Shelf shelf) return shelved(block, data, shelf, assets);
        // The bell itself, which hangs in the same place whichever way the block faces and whatever holds it up -
        // its renderer neither turns nor moves it. The posts and the bar are the block model's and are drawn already.
        if (state instanceof Bell) return one(middleOf(block, "bell"));
        if (!(data instanceof Chest chest)) return List.of();

        String mesh = switch (chest.getType()) {
            case LEFT -> "chest_left";
            case RIGHT -> "chest_right";
            case SINGLE -> "chest";
        };

        // Placed at the middle of the block and at its floor, which is where the model is measured from.
        EntitySnapshot authored = EntitySnapshot.mob(mesh,
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5,
                yawOf(chest.getFacing()), yawOf(chest.getFacing()), 0, 1f);
        if (authored == null) return List.of();

        String texture = textureOf(block, chest, assets);
        return texture == null ? List.of() : one(authored.texture(texture));
    }

    /** One snapshot as a list, or none when it did not resolve. */
    private static List<EntitySnapshot> one(EntitySnapshot snapshot) {
        return snapshot == null ? List.of() : List.of(snapshot);
    }

    /**
     * A banner, which is two: the pole and crossbar, and the cloth hung off it in the banner's own dye.
     *
     * <p>Its renderer is the one here that lifts the mesh nowhere - it flips it about the middle of the block, draws
     * it at two thirds and turns it, and that is all - so both meshes carry the whole of vanilla's ground offset back
     * off and the two thirds is their scale. A standing banner turns to whichever sixteenth of a circle it was placed
     * at and a wall one faces the way the block does, neither of them turned away from it the way a skull is.
     *
     * <p>The cloth is one texture rather than a tinted one: vanilla draws the white base and then one white mask per
     * pattern, each in the dye that layer was made with, so the picture only exists once they are painted over each
     * other. A snapshot carries one colour and a banner has as many as it has layers, which is why the compositing
     * happens in the atlas.
     */
    private static List<EntitySnapshot> banner(Block block, BlockData data, Banner state, MobAssets assets) {
        boolean onWall = data instanceof Directional;
        String mesh = onWall ? "wall_banner" : "banner";

        float turned = onWall
                ? yawOf(((Directional) data).getFacing())
                : yawOf(data instanceof Rotatable rotatable ? rotatable.getRotation() : BlockFace.SOUTH);

        EntitySnapshot pole = EntitySnapshot.mob(mesh,
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5, turned, turned, 0, 1f);
        EntitySnapshot cloth = EntitySnapshot.mob(mesh + "_flag",
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5, turned, turned, 0, 1f);
        if (pole == null || cloth == null) return List.of();

        String woven = assets.atlas().dyed(patterns(state, assets));
        return List.of(pole, woven == null ? cloth : cloth.texture(woven));
    }

    /**
     * The cloth as its layers, bottom first: the base in the banner's own dye, then a mask per pattern in the dye it
     * was made with.
     *
     * <p>A pattern this version carries no texture for is left out rather than drawn as a checkerboard, since one
     * unknown layer should cost its own stripe and not the whole flag - and a layer painted over the others is exactly
     * where a checkerboard would do the most damage.
     */
    private static List<TextureAtlas.Dyed> patterns(Banner state, MobAssets assets) {
        List<TextureAtlas.Dyed> layers = new ArrayList<>();
        layers.add(new TextureAtlas.Dyed(BANNER_BASE, dye(state.getBaseColor())));

        for (Pattern pattern : state.getPatterns()) {
            // Through the registry rather than off the constant, since a datapack may add a pattern and the constants
            // are on their way out.
            NamespacedKey key = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.BANNER_PATTERN).getKey(pattern.getPattern());
            if (key == null) continue;

            String mask = "entity/banner/" + key.getKey();
            if (assets.atlas().has(mask)) {
                layers.add(new TextureAtlas.Dyed(mask, dye(pattern.getColor())));
            }
        }
        return List.copyOf(layers);
    }

    private static final String BANNER_BASE = "entity/banner/base";

    private static int dye(DyeColor color) {
        return color == null ? 0 : Tints.dye(color.name().toLowerCase(Locale.ROOT));
    }

    /**
     * A decorated pot: the clay body, and its four sides in whatever sherds were pressed into them.
     *
     * <p>Its renderer neither turns the mesh over nor lifts it - it turns the block to face and nothing else - so this
     * is measured from the block's corner like a chest, and the pot's own wobble is an animation nothing here carries.
     *
     * <p>The sides are one mesh with parts named for the four faces, and a snapshot samples one texture, so they are
     * grouped by the sherd they wear: a plain pot is one layer with all four sides on it and a fully decorated one is
     * four.
     */
    private static List<EntitySnapshot> decoratedPot(Block block, BlockData data, DecoratedPot state) {
        float turned = facingYaw(data);
        EntitySnapshot body = EntitySnapshot.mob("decorated_pot",
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5, turned, turned, 0, 1f);
        if (body == null) return List.of();

        List<EntitySnapshot> drawn = new ArrayList<>();
        drawn.add(body);

        EntitySnapshot sides = EntitySnapshot.mob("decorated_pot_sides",
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5, turned, turned, 0, 1f);
        if (sides == null) return List.copyOf(drawn);

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (DecoratedPot.Side side : DecoratedPot.Side.values()) {
            grouped.computeIfAbsent(sherd(state.getSherd(side)), texture -> new ArrayList<>())
                    .add(side.name().toLowerCase(Locale.ROOT));
        }

        grouped.forEach((texture, faces) -> {
            List<String> hidden = new ArrayList<>(POT_SIDES);
            hidden.removeAll(faces);
            drawn.add(sides.without(hidden.toArray(String[]::new)).texture(texture));
        });
        return List.copyOf(drawn);
    }

    /** The four parts vanilla's sides mesh is built from, named as {@link DecoratedPot.Side} names them. */
    private static final List<String> POT_SIDES = List.of("back", "left", "right", "front");

    /**
     * Which pattern a sherd is drawn with, as a rule over the name rather than as a table of the twenty-odd.
     *
     * <p>The assets pair them up: {@code angler_pottery_sherd} is pressed into {@code angler_pottery_pattern}. A face
     * with no sherd - or with a brick, which leaves no mark - wears the plain side, and so does a sherd this version's
     * textures have never heard of.
     */
    private static String sherd(Material pressed) {
        if (pressed == null) return PLAIN_POT_SIDE;

        String name = pressed.getKey().value();
        return name.endsWith(SHERD)
                ? "entity/decorated_pot/" + name.substring(0, name.length() - SHERD.length()) + PATTERN
                : PLAIN_POT_SIDE;
    }

    private static final String SHERD = "_pottery_sherd";

    private static final String PATTERN = "_pottery_pattern";

    private static final String PLAIN_POT_SIDE = "entity/decorated_pot/decorated_pot_side";

    /**
     * A copper golem statue: the golem's own mesh in one of four poses, weathered to whatever the block is.
     *
     * <p>The pose is a mesh of vanilla's own rather than angles applied here, so it is asked for as a variant - and
     * the standing one is the mesh itself, which is why it is the fallback rather than a fourth name.
     */
    private static EntitySnapshot statue(Block block, CopperGolemStatue data) {
        String pose = data.getCopperGolemPose().name().toLowerCase(Locale.ROOT);
        float turned = facingYaw(data);

        EntitySnapshot authored = EntitySnapshot.mob("copper_golem_statue", pose,
                block.getX() + 0.5, block.getY(), block.getZ() + 0.5, turned, turned, 0, 1f, false);
        if (authored == null) return null;

        return authored.texture("entity/copper_golem/copper_golem" + weathering(block));
    }

    /**
     * How far a copper block has weathered, as the suffix its texture carries. Read off the block's own name, which
     * is where the game keeps it - and waxed only stops it going further, so it looks like whatever it stopped at.
     */
    private static String weathering(Block block) {
        String name = block.getType().getKey().value();
        for (String stage : List.of("exposed", "weathered", "oxidized")) {
            if (name.contains(stage)) return "_" + stage;
        }
        return "";
    }

    /**
     * The book over an enchanting table, at the place {@code EnchantTableRenderer} puts it: three quarters of a block
     * up plus the tenth its hover starts at, and tipped eighty degrees about Z.
     *
     * <p>Built the right way up, so nothing here turns it over. The hover and the spin toward a nearby player are both
     * animations - and both start from here, which is why standing still is the pose a photograph can claim.
     */
    private static EntitySnapshot book(Block block) {
        EntitySnapshot authored = EntitySnapshot.mob("book",
                block.getX() + 0.5, block.getY() + BOOK_HEIGHT, block.getZ() + 0.5, UNTURNED, UNTURNED, 0, 1f);
        if (authored == null) return null;

        return authored.turned(0, 0, (float) Math.toRadians(BOOK_TILT), 0);
    }

    /** Vanilla's 0.75 up plus the 0.1 its hover is measured from, in blocks. */
    private static final double BOOK_HEIGHT = 0.85;

    private static final float BOOK_TILT = 80;

    /**
     * What is standing on a shelf: up to three items, each in its own slot.
     *
     * <p>The shelf itself is an ordinary block model and is drawn already. Its renderer turns the whole thing to face
     * the way the block does, puts each slot a fifth of a block either side of the middle and a quarter of a block
     * forward, and stands whatever is there at a quarter size on the slot's own point - measured off the item so a
     * tall one and a flat one hang from the same place.
     *
     * <p>Items are drawn centred on that point. A shelf can be told to drop them to the bottom instead, but that is a
     * flag on the block entity's own data and Bukkit exposes nothing for it, so the default is what is drawn.
     *
     * <p>Turned by the facing's own yaw rather than a chest's: the shelf turns the way a chest does, and what is being
     * placed is a block model, which arrives a half circle about Y from where its json states it. The two half
     * circles cancel.
     */
    private static List<EntitySnapshot> shelved(Block block, BlockData data, Shelf state, MobAssets assets) {
        float turned = facingYaw(data);

        List<EntitySnapshot> drawn = new ArrayList<>();
        ItemStack[] contents = state.getSnapshotInventory().getContents();

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stood = contents[slot];
            if (stood == null || stood.isEmpty()) continue;

            float[] offset = {(slot - 1) * SHELF_SPACING, 0, -SHELF_FORWARD};
            for (String id : ItemIds.of(stood)) {
                List<EntitySnapshot> layers = assets.items().held(id);
                if (layers.isEmpty()) continue;

                for (EntitySnapshot layer : layers) {
                    drawn.add(EntitySnapshot.onShelf(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5,
                            turned, layer, assets.items().stated(id, ItemPoses.ON_SHELF), offset));
                }
                break;
            }
        }
        return List.copyOf(drawn);
    }

    /** Vanilla's 0.3125 and 0.25 of a block, in the entity pixels an offset is stated in here. */
    private static final float SHELF_SPACING = 0.3125f * 16;

    private static final float SHELF_FORWARD = 0.25f * 16;

    /**
     * A head on a block, drawn the way {@code SkullBlockRenderer} draws one: standing on the floor of its block and
     * turned to whichever sixteenth of a circle it was placed at, or hung a quarter of a block off the wall it is on
     * and looking away from it.
     *
     * <p>The mesh is named after the block with {@code _wall} taken out, since the two forms of a head are the same
     * head. A player head wears its owner's skin where that has come down, and vanilla's default face until it does.
     */
    private static EntitySnapshot skull(Block block, BlockData data, Skull state, SkinCache skins) {
        String mesh = block.getType().getKey().value().replace("_wall", "");
        BlockFace wall = data instanceof Directional facing ? facing.getFacing() : null;

        double x = block.getX() + 0.5 - (wall == null ? 0 : WALL_OFFSET * wall.getModX());
        double y = block.getY() + (wall == null ? 0 : WALL_OFFSET);
        double z = block.getZ() + 0.5 - (wall == null ? 0 : WALL_OFFSET * wall.getModZ());

        float turned = wall != null
                ? yawOf(wall.getOppositeFace())
                : yawOf(data instanceof Rotatable rotatable ? rotatable.getRotation() : BlockFace.SOUTH);

        EntitySnapshot authored = EntitySnapshot.mob(mesh, x, y, z, turned, turned, 0, 1f);
        if (authored == null) return null;

        String skin = mesh.equals("player_head") ? skins.nameFor(ownerOf(state)) : null;
        return skin == null ? authored : authored.texture(skin);
    }

    /**
     * Whose head this is, as a profile with textures on it.
     *
     * <p>Rebuilt out of the component the block carries rather than resolved: {@link ResolvableProfile#resolve} hands
     * back a future and a capture has one tick. The properties are what a head whose owner the server has already
     * looked up carries, and the skin url lives in them.
     */
    private static PlayerProfile ownerOf(Skull state) {
        ResolvableProfile owner = state.getProfile();
        if (owner == null || (owner.uuid() == null && owner.name() == null)) return null;

        PlayerProfile profile = Bukkit.createProfile(owner.uuid(), owner.name());
        profile.setProperties(owner.properties());
        return profile;
    }

    /** How far off the wall a hanging head sits, and how far up it, which are vanilla's one number for both. */
    private static final double WALL_OFFSET = 0.25;

    /**
     * A shulker box, which sits on whichever of its block's six faces it was placed against.
     *
     * <p>Its renderer turns the mesh by {@code Direction#getRotation} about the middle of the block, which is a
     * quarter circle onto its side for the four horizontal faces and a half circle for the one underneath. The colour
     * is in the block's own name, and an undyed box has none.
     */
    private static EntitySnapshot shulkerBox(Block block, BlockData data) {
        EntitySnapshot authored = middleOf(block, "shulker_box");
        if (authored == null) return null;

        String dye = block.getType().getKey().value().replace("shulker_box", "").replace("_", "");
        String texture = "entity/shulker/shulker" + (dye.isEmpty() ? "" : "_" + dye);

        BlockFace facing = data instanceof Directional directional ? directional.getFacing() : BlockFace.UP;
        return standing(authored, facing).texture(texture);
    }

    /**
     * One block entity in the middle of its block, unturned - which is where a conduit's renderer puts it and where
     * anything turned by something other than a yaw starts from.
     */
    private static EntitySnapshot middleOf(Block block, String mesh) {
        return EntitySnapshot.mob(mesh, block.getX() + 0.5, block.getY(), block.getZ() + 0.5,
                UNTURNED, UNTURNED, 0, 1f);
    }

    /** The yaw that leaves the trace's own half circle undone, so a bodily turn is stated in world axes. */
    private static final float UNTURNED = -180;

    /**
     * The same turned onto the face it was placed against, about the middle of its block.
     *
     * <p>Vanilla's {@code Direction#getRotation} written out in this module's own order. Those quaternions are
     * {@code Rx} then {@code Rz} where a part here is {@code Rz} then {@code Ry} then {@code Rx}, and the two meet by
     * conjugation: a turn about Z after a quarter circle about X is the same as one about Y before it, the other way
     * round.
     */
    private static EntitySnapshot standing(EntitySnapshot upright, BlockFace facing) {
        float quarter = (float) (Math.PI / 2);
        return switch (facing) {
            case UP -> upright;
            case DOWN -> upright.turned((float) Math.PI, 0, 0, MIDDLE);
            case SOUTH -> upright.turned(quarter, 0, 0, MIDDLE);
            case NORTH -> upright.turned(quarter, (float) Math.PI, 0, MIDDLE);
            case WEST -> upright.turned(quarter, -quarter, 0, MIDDLE);
            case EAST -> upright.turned(quarter, quarter, 0, MIDDLE);
            default -> upright;
        };
    }

    /** Where the middle of a block is, in the entity pixels a bodily turn states its pivot in. */
    private static final float MIDDLE = 8;

    /**
     * The chest this one is: its wood or its metal, and which half of a double it is.
     *
     * <p>Probed against the naming the assets follow rather than tabulated - {@code trapped_chest} wears
     * {@code entity/chest/trapped}, and its left half {@code trapped_left}. A chest whose texture is not there falls
     * back to the plain one, which is what an unknown modded chest gets.
     */
    private static String textureOf(Block block, Chest chest, MobAssets assets) {
        String half = switch (chest.getType()) {
            case LEFT -> "_left";
            case RIGHT -> "_right";
            case SINGLE -> "";
        };

        String stem = block.getType().getKey().value().replace("_chest", "").replace("chest", "normal");
        for (String candidate : List.of(stem, reversed(stem), "normal")) {
            String texture = "entity/chest/" + candidate + half;
            if (assets.atlas().has(texture)) return texture;
        }
        return null;
    }

    /**
     * The same words the other way round, because the two sides do not agree on the order: the block is a
     * {@code weathered_copper_chest} and the texture it wears is {@code copper_weathered}. A rule rather than a
     * table of the four copper states, so a fifth costs nothing.
     */
    private static String reversed(String stem) {
        String[] words = stem.split("_");
        StringBuilder out = new StringBuilder(stem.length());
        for (int i = words.length - 1; i >= 0; i--) {
            out.append(words[i]);
            if (i > 0) {
                out.append('_');
            }
        }
        return out.toString();
    }

    /**
     * The yaw that leaves a block entity facing the way the block says.
     *
     * <p>Vanilla turns a chest by the negative of its facing's yaw and leaves a south-facing one alone. The trace
     * turns a model by {@code -180 - yaw}, since a mob's face is on its model's -Z side, so the two meet half a turn
     * apart - which is where the 180 comes from rather than from anything about chests.
     *
     * <p>Snapped to a sixteenth of a circle, which a skull's rotation is stored as. Bukkit hands one back as a
     * compass face and a compass face is a whole-number vector, so the angle it points at is a few degrees off the
     * one the game stored - {@code (-1, 0, 2)} is 26.6 degrees where the segment it came from is 22.5.
     */
    private static float yawOf(BlockFace facing) {
        Vector direction = facing.getDirection();
        float yaw = (float) -Math.toDegrees(Math.atan2(direction.getX(), direction.getZ()));
        return Math.round(yaw / SEGMENT) * SEGMENT - 180;
    }

    /** A sixteenth of a circle, which is the whole range a placed head can be turned to. */
    private static final float SEGMENT = 360 / 16f;

    /**
     * The yaw for a block entity whose renderer turns it to <i>face</i> the way the block does, rather than to look
     * along it the way a chest does.
     *
     * <p>Half a circle from {@link #yawOf}, and the two are not interchangeable: a pot and a statue both state their
     * turn against the block's facing rather than its negative, so drawn with a chest's yaw each has its back to you.
     */
    private static float facingYaw(BlockData data) {
        BlockFace facing = data instanceof Directional directional ? directional.getFacing() : BlockFace.SOUTH;
        return yawOf(facing) + 180;
    }
}
