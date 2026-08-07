package de.flog99.mapgui.plugin.camera;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Which item model a stack is drawn from, which is not always its material.
 *
 * <p>Vanilla's {@code minecraft:item_model} component overrides the model an item draws with, and it names one out
 * of {@code assets/minecraft/items/} - the same place an item's own definition lives. So a stick given
 * {@code item_model=minecraft:diamond_sword} is a sword to everybody looking at it, and a capture that read the
 * material would photograph a stick. Datapacks and item plugins lean on this heavily.
 */
final class ItemIds {

    private ItemIds() {
    }

    /**
     * The ids worth trying, best first: what the stack says it draws as, then what its material is.
     *
     * <p>Two rather than one because the component may name a model this cannot draw - a custom one from a resource
     * pack MapGUI was not given, or one the client renders in code. Falling back to the material then draws a stick
     * rather than nothing, which is the same rule the rest of the item path follows.
     */
    static List<String> of(ItemStack item) {
        // Whole keys rather than bare values: a pack's model lives under its own namespace, and an id that has
        // lost it resolves against vanilla's assets, where it is never going to be.
        String material = item.getType().getKey().asString();
        Key stated = item.getData(DataComponentTypes.ITEM_MODEL);
        if (stated == null || stated.asString().equals(material)) return List.of(material);

        return List.of(stated.asString(), material);
    }
}
