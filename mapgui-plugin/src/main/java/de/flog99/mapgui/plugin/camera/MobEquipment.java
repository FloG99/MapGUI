package de.flog99.mapgui.plugin.camera;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.Equippable;
import de.flog99.mapgui.camera.EntityDetails;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.EquipmentAssets;
import de.flog99.mapgui.render.ItemModels;
import de.flog99.mapgui.render.ItemPoses;
import de.flog99.mapgui.render.TextureAtlas;
import org.bukkit.entity.Entity;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Fox;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.TraderLlama;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.List;
import java.util.Map;

/**
 * What a mob is wearing and holding, as one snapshot per layer.
 *
 * <p>Driven by the item rather than by a table of materials: every equippable stack carries vanilla's own
 * {@code equippable} component naming its asset, so a datapack piece that sets one is drawn as correctly as an iron
 * helmet. Each piece needs a mesh, which is the body inflated the way the client inflates it for that slot, and a
 * texture under the layer the asset states. Anything that does not resolve is left off.
 */
final class MobEquipment {

    /** The armor mesh each slot is drawn from. One table, because vanilla builds all four together. */
    private static final Map<EquipmentSlot, String> ARMOR = Map.of(
            EquipmentSlot.HEAD, "armor/head",
            EquipmentSlot.CHEST, "armor/chest",
            EquipmentSlot.LEGS, "armor/legs",
            EquipmentSlot.FEET, "armor/feet"
    );

    private MobEquipment() {
    }

    /**
     * Every layer this entity wears over {@code base}, and nothing for the many that wear none.
     *
     * <p>Into two lists because the two turn differently: what is worn is drawn on the mob's own parts and follows
     * the head, where an item in a hand is posed off the arm and follows the body. Only {@link MobCache} cares,
     * since it stands a shape back up later and has to know which angle each layer was taking.
     *
     * @param worn adds to, for the layers that turn with the head, and {@code held} for the ones that turn with the body
     */
    static void wornBy(Entity entity, EntitySnapshot base, String type, MobAssets assets, SkinCache skins,
                       EntityDetails details, List<EntitySnapshot> worn, List<EntitySnapshot> held) {
        if (!(entity instanceof LivingEntity living)) return;

        EntityEquipment dressed = living.getEquipment();
        if (dressed == null) return;

        TextureAtlas atlas = assets.atlas();
        EquipmentAssets equipment = assets.equipment();

        // Armor moves with a sneaking player and a saddle does not, since only the humanoid pieces share the body
        // whose parts the crouch shifts.
        boolean crouching = entity instanceof Player player && player.isSneaking();
        ARMOR.forEach((slot, mesh) -> add(worn, base, atlas, equipment, mesh,
                slot == EquipmentSlot.LEGS ? "humanoid_leggings" : "humanoid", dressed.getItem(slot), crouching));

        // The animals, whose layer is named after the animal rather than after its shape: a pig saddle is not a horse
        // saddle and neither is drawn from the other mesh.
        add(worn, base, atlas, equipment, saddleMesh(type), type + "_saddle", dressed.getItem(EquipmentSlot.SADDLE), false);
        // Body armor names its layer after the shape rather than after the animal, which is the same answer for all
        // but the three that borrow another's mesh: a trader llama's carpet is a llama's, on a llama's body.
        add(worn, base, atlas, equipment, bodyMesh(type), bodyMesh(type), dressed.getItem(EquipmentSlot.BODY), false);

        // One skeleton in twenty is left-handed, and vanilla poses a held item by the arm rather than by the hand.
        boolean rightHanded = !leftHanded(entity);
        hold(held, base, assets, skins, dressed.getItemInMainHand(), rightHanded);
        hold(held, base, assets, skins, dressed.getItemInOffHand(), !rightHanded);

        wear(worn, base, assets, entity);
        sprout(worn, base, assets, entity);
        hoist(worn, base, assets, dressed.getItem(EquipmentSlot.HEAD));
        carry(worn, base, assets, skins, entity, dressed.getItemInMainHand());
        decorate(worn, base, atlas, equipment, entity);
        contains(worn, base, assets, type, dressed.getItem(EquipmentSlot.BODY));
        carried(held, base, assets, entity);
        offering(held, base, assets, details, entity);
    }

    /**
     * A trader llama's own decoration, which is the one piece of equipment nothing is wearing: {@code LlamaDecorLayer}
     * draws it off an empty stack, from an asset named in the client rather than on any item.
     *
     * <p>Only when the llama has no carpet on. A trader llama somebody has dressed wears the carpet instead, which is
     * the ordinary body slot and drawn above.
     */
    private static void decorate(List<EntitySnapshot> into, EntitySnapshot base, TextureAtlas atlas,
                                 EquipmentAssets equipment, Entity entity) {
        if (!(entity instanceof TraderLlama llama) || asset(llama.getEquipment().getItem(EquipmentSlot.BODY)) != null) return;

        put(into, base, atlas, equipment, LLAMA_BODY, LLAMA_BODY, TRADER_LLAMA, null, false);
    }

    /** The asset the client names for it, which is a piece of equipment with no item behind it. */
    private static final String TRADER_LLAMA = "trader_llama";

    /**
     * Whatever a fox has in its mouth, which is a held item drawn on the head rather than in a hand.
     *
     * <p>{@code FoxHeldItemLayer}'s own chain: to the head, along it, a quarter circle over, and the item drawn at the
     * transform it would be lying on the ground at. A sleeping fox turns it a further quarter circle, and a cub is
     * three quarters of the size and holds it further forward.
     */
    private static void carry(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets, SkinCache skins,
                              Entity entity, ItemStack item) {
        if (!(entity instanceof Fox fox) || item == null || item.isEmpty()) return;

        boolean baby = !fox.isAdult();
        boolean asleep = fox.isSleeping();
        float small = baby ? CUB_SCALE : 1;

        float[] mouth = asleep ? (baby ? CUB_ASLEEP : FOX_ASLEEP) : (baby ? CUB_MOUTH : FOX_MOUTH);
        float[] shift = {mouth[0] * small, mouth[1] * small, mouth[2] * small};
        float[] turn = asleep ? MOUTH_ASLEEP_TURN : MOUTH_TURN;

        for (String id : ItemIds.of(item)) {
            List<EntitySnapshot> layers = assets.items().held(id);
            if (layers.isEmpty()) continue;

            ItemPoses.Pose pose = assets.poses().carried(id, ItemPoses.ON_GROUND, shift, turn);
            ItemPoses.Pose sized = baby
                    ? new ItemPoses.Pose(pose.offset(), pose.rotation(), pose.scale() * CUB_SCALE)
                    : pose;

            for (EntitySnapshot layer : skins.faced(layers, item)) {
                EntitySnapshot held = EntitySnapshot.on(base, FOX_HEAD, layer, sized, true);
                if (held != null) {
                    into.add(held);
                }
            }
            return;
        }
    }

    /** The part a fox's renderer hangs what it is carrying off, and the only name it looks one up by. */
    private static final String FOX_HEAD = "head";

    /** The client's own four offsets, in blocks: awake and asleep, grown and cub. */
    private static final float[] FOX_MOUTH = {0.06f, 0.27f, -0.5f};

    private static final float[] FOX_ASLEEP = {0.46f, 0.26f, 0.22f};

    private static final float[] CUB_MOUTH = {0.06f, 0.26f, -0.5f};

    private static final float[] CUB_ASLEEP = {0.4f, 0.26f, 0.15f};

    /** A quarter circle over, so the item lies flat in the jaws, and a second one for a fox lying on its side. */
    private static final float[] MOUTH_TURN = {90, 0, 0};

    private static final float[] MOUTH_ASLEEP_TURN = {90, 0, 90};

    private static final float CUB_SCALE = 0.75f;

    /**
     * The carved pumpkin a snow golem wears, which is not equipment and is not in any slot.
     *
     * <p>Vanilla draws it as a further pass over the head part, from the block's own model rather than from anything
     * on the mesh - so a snow golem with no pumpkin is not a golem missing a layer, it is the same mesh with this
     * left off. Shearing one is the only way to see the snow head underneath.
     */
    private static void wear(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets, Entity entity) {
        if (!(entity instanceof Snowman golem) || golem.isDerp()) return;

        for (EntitySnapshot layer : assets.items().held(PUMPKIN)) {
            EntitySnapshot worn = EntitySnapshot.onHead(base, layer, PUMPKIN_POSE);
            if (worn != null) {
                into.add(worn);
            }
        }
    }

    /**
     * A banner worn on the head, which is what marks a raid captain.
     *
     * <p>Not armour, whatever slot it is in: a banner carries no {@code equippable} component, so the armour path
     * above resolves nothing for it. The client draws it as {@code CustomHeadLayer} draws anything that is not a
     * skull - the item's own shape, a quarter of a block down the head, turned about and at five eighths.
     *
     * <p>Its cloth is woven per stack like a block banner's, since an ominous banner is nine patterns and nothing in
     * the assets holds that picture.
     */
    private static void hoist(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets, ItemStack head) {
        if (head == null || head.isEmpty() || !head.getType().name().endsWith(BANNER)) return;

        String id = head.getType().getKey().asString();
        DyeColor color = dyeOf(head.getType().name());
        List<Pattern> patterns = head.getItemMeta() instanceof BannerMeta meta ? meta.getPatterns() : List.of();
        String woven = assets.atlas().dyed(BannerCloth.layersOf(color, patterns, assets));

        ItemPoses.Pose pose = onHead(assets.poses().displayed(id, ON_HEAD_CONTEXT));
        for (EntitySnapshot layer : assets.items().held(id)) {
            // Two meshes come back - the pole and crossbar, and the cloth - and only the cloth is dyed and patterned.
            EntitySnapshot cloth = woven != null && BannerCloth.BASE.equals(layer.texture()) ? layer.texture(woven) : layer;

            EntitySnapshot flown = EntitySnapshot.onHead(base, cloth, pose);
            if (flown != null) {
                into.add(flown);
            }
        }
    }

    private static final String BANNER = "_BANNER";

    /** Where an item states how it is worn, which is a display context like the two held ones. */
    private static final String ON_HEAD_CONTEXT = "head";

    /**
     * The whole head chain: what {@code CustomHeadLayer} does, and then what the item's own model asks for.
     *
     * <p>The layer drops the item a quarter of a block down the head, turns it about and draws it at five eighths -
     * and everything after that is the item's business. A banner's is not decoration: it asks to be lifted a whole
     * block and pushed seven sixteenths back, at half again the size, which is exactly what puts one behind a raid
     * captain's head like a standard rather than flat on top of it.
     *
     * <p>The item's translation comes through the layer's own five eighths, since the layer scales before the item
     * places itself; its scale multiplies. Its depth changes sign: measured by drawing a pillager from above, a
     * banner's seven sixteenths of stated forward lands it north of a mob facing north, so what the assets call
     * forward is behind here. Its height does not - the layer's own flip turns the frame the right way up and
     * reverses only the depth.
     *
     * @param stated {@code {x, y, z, scale}} from the item's own {@code head} transform
     */
    private static ItemPoses.Pose onHead(float[] stated) {
        return new ItemPoses.Pose(
                new float[]{stated[0] * ON_HEAD_SCALE, HEAD_DROP + stated[1] * ON_HEAD_SCALE, stated[2] * ON_HEAD_SCALE},
                new float[]{0, (float) Math.PI, 0},
                ON_HEAD_SCALE * stated[3]);
    }

    /** A quarter of a block down the head, in entity pixels, and the five eighths the layer draws at. */
    private static final float HEAD_DROP = 4;

    private static final float ON_HEAD_SCALE = 0.625f;

    /** {@code WHITE_BANNER} to {@code WHITE}, since a banner's base colour is the item and not a component. */
    private static DyeColor dyeOf(String material) {
        try {
            return DyeColor.valueOf(material.substring(0, material.length() - BANNER.length()));
        } catch (IllegalArgumentException notADye) {
            return null;
        }
    }

    /**
     * The three mushrooms growing on a mooshroom, which are three copies of the mushroom's own block model rather
     * than anything on the cow's mesh - which is why an ordinary mooshroom came out a plain red cow.
     *
     * <p>Two of them stand on the back and one on the head, at the client's own offsets and each turned a different
     * way so they do not read as three of the same thing. The turn each carries is that angle plus the half circle
     * between the flip {@code MushroomCowMushroomLayer} uses and the one a block on a mob part arrives with.
     */
    private static void sprout(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets, Entity entity) {
        if (!(entity instanceof MushroomCow cow) || cow.getVariant() == null) return;

        Material mushroom = cow.getVariant() == MushroomCow.Variant.BROWN ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM;
        BlockData block = mushroom.createBlockData();

        for (EntitySnapshot layer : assets.items().displayed(block.getAsString(), mushroom.getKey().asString())) {
            for (Sprout sprout : SPROUTS) {
                EntitySnapshot grown = EntitySnapshot.on(base, sprout.part(), layer, sprout.pose(), sprout.onHead());
                if (grown != null) {
                    into.add(grown);
                }
            }
        }
    }

    /** One mushroom: which part it grows on, where, and whether it turns with the head. */
    private record Sprout(String part, ItemPoses.Pose pose, boolean onHead) {
    }

    /**
     * The block an enderman has pulled up, held out in front of its chest rather than in either hand - which is why
     * it does not go through {@link #hold} and is not equipment at all: it is a block state on the mob.
     */
    private static void carried(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets, Entity entity) {
        if (!(entity instanceof Enderman enderman)) return;

        BlockData block = enderman.getCarriedBlock();
        if (block == null) return;

        for (EntitySnapshot layer : assets.items().displayed(block.getAsString(), block.getMaterial().getKey().asString())) {
            EntitySnapshot lifted = EntitySnapshot.on(base, BODY, layer, CARRIED, false);
            if (lifted != null) {
                into.add(lifted);
            }
        }
    }

    /**
     * A poppy an iron golem is offering, which it holds out for twenty seconds after handing one to a villager.
     *
     * <p>Off the server rather than out of Bukkit, which knows nothing about it - so a fork that will not answer
     * draws the golem empty handed, the way it was drawn before.
     */
    private static void offering(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets,
                                 EntityDetails details, Entity entity) {
        if (details == null || !(entity instanceof IronGolem) || !details.offeringFlower(entity)) return;

        BlockData poppy = Material.POPPY.createBlockData();
        for (EntitySnapshot layer : assets.items().displayed(poppy.getAsString(), Material.POPPY.getKey().asString())) {
            EntitySnapshot held = EntitySnapshot.on(base, RIGHT_ARM, layer, OFFERED, false);
            if (held != null) {
                into.add(held);
            }
        }
    }

    /** The arm a golem holds its flower in, which is its right one whichever way it is facing. */
    private static final String RIGHT_ARM = "right_arm";

    /**
     * Both placements are {@code CarriedBlockLayer}'s and {@code IronGolemFlowerLayer}'s own chains composed down to
     * one offset and one turn each, in this module's frame.
     *
     * <p>Composed rather than copied, since a pose is one transform and the client's order decides the result.
     *
     * <p><b>Two frames are crossed, not one.</b> A mesh is a half circle about Z from the space the chain is written
     * in, and a block's mesh a half circle about <i>Y</i> from the model it states - so a turn converts as
     * {@code Z half turn * the client's turn * Y half turn}. Leaving the last off draws every block upside down and
     * back to front, which shows on a grass block and not on a cobblestone. Measured by CarriedBlockAxesTest.
     */
    private static final ItemPoses.Pose CARRIED = new ItemPoses.Pose(
            new float[]{0, -10.06f, -12.34f},
            new float[]{(float) Math.toRadians(-152.76), (float) Math.toRadians(-41.64), (float) Math.toRadians(161.12)},
            0.5f);

    /**
     * The golem's is off the arm it holds the poppy in, twenty five pixels down it - the fist, near the bottom of a
     * thirty pixel arm - and seven out in front.
     *
     * <p>{@code IronGolemFlowerLayer}'s own {@code -1.1875, 1.0625, -0.9375} blocks with the middle of its
     * turn-about-the-centre sandwich taken out, which lands on whole pixels. The quarter turn lays the poppy flat
     * pointing away rather than standing it up, and is negative here for the reason {@link #CARRIED} gives.
     */
    private static final ItemPoses.Pose OFFERED = new ItemPoses.Pose(
            new float[]{11f, -25f, -7f}, new float[]{(float) Math.toRadians(-90), 0, 0}, 0.5f);

    /** The part a mooshroom's back is, which is the root of its mesh - the client hangs two of the three off it. */
    private static final String BODY = "root";

    private static final String HEAD = "head";

    /**
     * The client's three placements, converted into this module's space: an offset in entity pixels with X and Y
     * running the other way, and a turn about Y that runs the other way too.
     */
    private static final List<Sprout> SPROUTS = List.of(
            new Sprout(BODY, turned(-3.2f, 5.6f, 8f, -132), false),
            new Sprout(BODY, turned(-2.035f, 5.6f, -0.205f, -174), false),
            new Sprout(HEAD, turned(0, 11.2f, -3.2f, -102), true)
    );

    private static ItemPoses.Pose turned(float x, float y, float z, float degrees) {
        return new ItemPoses.Pose(new float[]{x, y, z}, new float[]{0, (float) Math.toRadians(degrees), 0}, 1f);
    }

    /**
     * What has been put inside a sulfur cube, which its renderer draws as a block model rather than as a worn mesh.
     *
     * <p>It arrives in the body slot like a piece of armor, but nothing about it is armor: there is no
     * {@code equippable} component naming an asset, so the armor path above resolves nothing and draws nothing. The
     * block goes in the middle of the cube, on the one part its mesh names.
     */
    private static void contains(List<EntitySnapshot> into, EntitySnapshot base, MobAssets assets,
                                 String type, ItemStack item) {
        if (!type.equals("sulfur_cube") || item == null || item.isEmpty()) return;

        for (String id : ItemIds.of(item)) {
            List<EntitySnapshot> layers = assets.items().held(id);
            if (layers.isEmpty()) continue;

            for (EntitySnapshot layer : layers) {
                EntitySnapshot inside = EntitySnapshot.on(base, CUBE, layer, CONTAINED_POSE, false);
                if (inside != null) {
                    into.add(inside);
                }
            }
            return;
        }
    }

    /**
     * The root rather than the shell's own part, because the shell is not there any more: it is what the block
     * replaces, and {@link EntityCapture} has already taken it off by the time this runs.
     */
    private static final String CUBE = "root";

    /**
     * Turned over, off {@code SulfurCubeInnerLayer}, and left at full size.
     *
     * <p>That layer halves the block, but it halves it inside a cube its own renderer has already halved - and this
     * mesh is not halved, because the halving is folded into the lift instead. Relative to the shell around it the
     * block is a whole one, which is what it looks like in the game.
     */
    private static final ItemPoses.Pose CONTAINED_POSE =
            new ItemPoses.Pose(new float[]{0, 0, 0}, new float[]{(float) Math.PI, 0, 0}, 1f);

    private static final String PUMPKIN = "minecraft:carved_pumpkin";

    /**
     * Where the pumpkin sits on the head, read off {@code SnowGolemHeadLayer}: a third of a block up, turned to face
     * back the way the head does, at five eighths of a block. The client's own numbers, in the units a pose is
     * stated in - {@code 0.34375} of a block is the 5.5 pixels below.
     */
    private static final ItemPoses.Pose PUMPKIN_POSE =
            new ItemPoses.Pose(new float[]{0, 5.5f, 0}, new float[]{0, (float) Math.PI, 0}, 0.625f);

    /** Whether this entity's main hand is its left one, which the server states for mobs and players separately. */
    static boolean leftHanded(Entity entity) {
        if (entity instanceof Mob mob) return mob.isLeftHanded();

        return entity instanceof HumanEntity human && human.getMainHand() == MainHand.LEFT;
    }

    /**
     * Whatever is in one hand, drawn there. {@link ItemModels} decides what shape an item is, {@link ItemPoses} says
     * how the client holds it, and this puts the two together.
     */
    private static void hold(List<EntitySnapshot> into, EntitySnapshot holder, MobAssets assets, SkinCache skins,
                             ItemStack item, boolean rightArm) {
        if (item == null || item.isEmpty()) return;

        // The pose comes from the same id as the shape, or a stick drawn as a sword would lie flat like a stick.
        for (String id : ItemIds.of(item)) {
            List<EntitySnapshot> layers = assets.items().held(id);
            if (layers.isEmpty()) {
                continue;
            }

            ItemPoses.Pose pose = assets.poses().of(id, rightArm);
            // Built at the origin and then put in the hand, since where the hand is depends on the holder's mesh.
            for (EntitySnapshot layer : skins.faced(layers, item)) {
                EntitySnapshot inHand = EntitySnapshot.held(holder, rightArm, layer, pose);
                if (inHand != null) {
                    into.add(inHand);
                }
            }
            return;
        }
    }

    /**
     * One piece of equipment, in as many passes as its own json states. Usually one; leather is a dyeable base plus
     * an overlay that keeps its own color, and that is why the json is read rather than the texture being named after
     * the asset - undyed, the greyscale base draws as iron.
     */
    private static void add(List<EntitySnapshot> into, EntitySnapshot base, TextureAtlas atlas,
                            EquipmentAssets equipment, String mesh, String layer, ItemStack item, boolean crouching) {
        String asset = asset(item);
        if (asset == null) return;

        put(into, base, atlas, equipment, mesh, layer, asset, item, crouching);
    }

    /** The same for a piece named outright, which is the trader llama's - there is no item behind that one. */
    private static void put(List<EntitySnapshot> into, EntitySnapshot base, TextureAtlas atlas,
                            EquipmentAssets equipment, String mesh, String layer, String asset,
                            ItemStack item, boolean crouching) {
        for (EquipmentAssets.Pass pass : equipment.of(asset, layer)) {
            if (!atlas.has(pass.texture())) continue;

            EntitySnapshot piece = EntitySnapshot.worn(base, mesh, pass.texture(), crouching);
            if (piece == null) continue;

            int dye = pass.undyed() == 0 ? 0 : dyed(item, pass.undyed());
            into.add(dye == 0 ? piece : piece.tint(dye));
        }
    }

    /**
     * What color a dyeable pass is multiplied by: the stack's own dye, or the color the asset states for undyed -
     * which for leather is a brown rather than white.
     */
    private static int dyed(ItemStack item, int undyed) {
        DyedItemColor color = item.getData(DataComponentTypes.DYED_COLOR);
        if (color == null || color.color() == null) return undyed;

        return 0xFF000000 | color.color().asRGB();
    }

    /** What an item says it is worn as, or null for anything that is not equipment. */
    private static String asset(ItemStack item) {
        if (item == null || item.isEmpty()) return null;

        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        return equippable == null || equippable.assetId() == null ? null : equippable.assetId().value();
    }

    /** The undead horses are saddled off the ordinary horse mesh, and a husk camel off a camel one. */
    private static String saddleMesh(String type) {
        return switch (type) {
            case "skeleton_horse", "zombie_horse" -> "horse_saddle";
            case "camel_husk" -> "camel_saddle";
            default -> type + "_saddle";
        };
    }

    private static String bodyMesh(String type) {
        return switch (type) {
            case "skeleton_horse", "zombie_horse" -> "horse_body";
            case "trader_llama" -> LLAMA_BODY;
            default -> type + "_body";
        };
    }

    /** The mesh and the layer a llama's carpet is drawn from, which a trader llama shares and is not named after. */
    private static final String LLAMA_BODY = "llama_body";
}
