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
import org.bukkit.entity.Mob;
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
    static List<EntitySnapshot> wornBy(Entity entity, EntitySnapshot base, String type, MobAssets assets) {
        if (!(entity instanceof LivingEntity living)) return List.of();

        EntityEquipment worn = living.getEquipment();
        if (worn == null) return List.of();

        TextureAtlas atlas = assets.atlas();
        EquipmentAssets equipment = assets.equipment();
        List<EntitySnapshot> layers = new ArrayList<>();

        ARMOR.forEach((slot, mesh) -> add(layers, base, atlas, equipment, mesh,
                slot == EquipmentSlot.LEGS ? "humanoid_leggings" : "humanoid", worn.getItem(slot)));

        // The animals, whose layer is named after the animal rather than after its shape: a pig saddle is not a horse
        // saddle and neither is drawn from the other mesh.
        add(layers, base, atlas, equipment, saddleMesh(type), type + "_saddle", worn.getItem(EquipmentSlot.SADDLE));
        add(layers, base, atlas, equipment, bodyMesh(type), type + "_body", worn.getItem(EquipmentSlot.BODY));

        // One skeleton in twenty is left-handed, and vanilla poses a held item by the arm rather than by the hand.
        boolean rightHanded = !leftHanded(entity);
        hold(layers, base, assets, worn.getItemInMainHand(), rightHanded);
        hold(layers, base, assets, worn.getItemInOffHand(), !rightHanded);

        return layers;
    }

    /** Whether this entity's main hand is its left one, which the server states for mobs and players separately. */
    static boolean leftHanded(Entity entity) {
        if (entity instanceof Mob mob) return mob.isLeftHanded();

        return entity instanceof HumanEntity human && human.getMainHand() == MainHand.LEFT;
    }

    /**
     * Whatever is in one hand, drawn there. {@link ItemModels} decides what shape an item is, {@link ItemPoses} says
     * how the client holds it, and this puts the two together.
     */
    private static void hold(List<EntitySnapshot> into, EntitySnapshot holder, MobAssets assets,
                             ItemStack item, boolean rightArm) {
        if (item == null || item.isEmpty()) return;

        String id = item.getType().getKey().value();
        ItemPoses.Pose pose = assets.poses().of(id, rightArm);

        // Built at the origin and then put in the hand, because where the hand is depends on the holder's own mesh.
        for (EntitySnapshot layer : assets.items().held(id)) {
            EntitySnapshot inHand = EntitySnapshot.held(holder, rightArm, layer, pose);
            if (inHand != null) {
                into.add(inHand);
            }
        }
    }

    /**
     * One piece of equipment, in as many passes as its own json states. Usually one; leather is a dyeable base plus
     * an overlay that keeps its own color, and that is why the json is read rather than the texture being named after
     * the asset - undyed, the greyscale base draws as iron.
     */
    private static void add(List<EntitySnapshot> into, EntitySnapshot base, TextureAtlas atlas,
                            EquipmentAssets equipment, String mesh, String layer, ItemStack item) {
        String asset = asset(item);
        if (asset == null) return;

        for (EquipmentAssets.Pass pass : equipment.of(asset, layer)) {
            if (!atlas.has(pass.texture())) continue;

            EntitySnapshot piece = EntitySnapshot.worn(base, mesh, pass.texture());
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
