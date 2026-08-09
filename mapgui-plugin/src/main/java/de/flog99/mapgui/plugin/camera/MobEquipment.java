package de.flog99.mapgui.plugin.camera;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.datacomponent.item.Equippable;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.EquipmentAssets;
import de.flog99.mapgui.render.ItemModels;
import de.flog99.mapgui.render.ItemPoses;
import de.flog99.mapgui.render.TextureAtlas;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;

import java.util.ArrayList;
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

    /** Every layer this entity wears over {@code base}, empty for the many that wear nothing. */
    static List<EntitySnapshot> wornBy(Entity entity, EntitySnapshot base, String type, MobAssets assets, SkinCache skins) {
        if (!(entity instanceof LivingEntity living)) return List.of();

        EntityEquipment worn = living.getEquipment();
        if (worn == null) return List.of();

        TextureAtlas atlas = assets.atlas();
        EquipmentAssets equipment = assets.equipment();
        List<EntitySnapshot> layers = new ArrayList<>();

        // Armor moves with a sneaking player and a saddle does not, since only the humanoid pieces share the body
        // whose parts the crouch shifts.
        boolean crouching = entity instanceof Player player && player.isSneaking();
        ARMOR.forEach((slot, mesh) -> add(layers, base, atlas, equipment, mesh,
                slot == EquipmentSlot.LEGS ? "humanoid_leggings" : "humanoid", worn.getItem(slot), crouching));

        // The animals, whose layer is named after the animal rather than after its shape: a pig saddle is not a horse
        // saddle and neither is drawn from the other mesh.
        add(layers, base, atlas, equipment, saddleMesh(type), type + "_saddle", worn.getItem(EquipmentSlot.SADDLE), false);
        add(layers, base, atlas, equipment, bodyMesh(type), type + "_body", worn.getItem(EquipmentSlot.BODY), false);

        // One skeleton in twenty is left-handed, and vanilla poses a held item by the arm rather than by the hand.
        boolean rightHanded = !leftHanded(entity);
        hold(layers, base, assets, skins, worn.getItemInMainHand(), rightHanded);
        hold(layers, base, assets, skins, worn.getItemInOffHand(), !rightHanded);

        wear(layers, base, assets, entity);
        contains(layers, base, assets, type, worn.getItem(EquipmentSlot.BODY));
        return layers;
    }

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
            default -> type + "_body";
        };
    }
}
