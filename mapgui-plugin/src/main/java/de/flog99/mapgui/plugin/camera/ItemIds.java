package de.flog99.mapgui.plugin.camera;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
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
     * The ids worth trying, best first: what a pack would switch this stack to, then what it says it draws as, then
     * what its material is.
     *
     * <p>More than one because any of them may name a model this cannot draw - a custom one from a resource pack
     * MapGUI was not given, or one the client renders in code. Falling through to the material then draws a stick
     * rather than nothing, which is the same rule the rest of the item path follows.
     *
     * <p><b>The custom_model_data ones are here because the switch that reads them is the client's.</b> A pack that
     * wants an item to keep working for players who declined it points {@code item_model} at something vanilla and
     * puts its own model behind a {@code select} on a custom_model_data string. Only a client with the pack can run
     * that select; a capture reads the fallback and photographs the plain vanilla item. So where the string is
     * itself a key naming an item definition - {@code mapcamera:film} against
     * {@code assets/mapcamera/items/film.json} - that definition is what the pack meant, and it is tried first. A
     * string that names nothing resolves to no layers and the next id is tried, which costs a capture nothing.
     */
    static List<String> of(ItemStack item) {
        // Whole keys rather than bare values: a pack's model lives under its own namespace, and an id that has
        // lost it resolves against vanilla's assets, where it is never going to be.
        String material = item.getType().getKey().asString();

        List<String> ids = new ArrayList<>(3);
        CustomModelData switching = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (switching != null) {
            for (String string : switching.strings()) {
                String named = keyed(string);
                if (named != null) {
                    ids.add(named);
                }
            }
        }

        Key stated = item.getData(DataComponentTypes.ITEM_MODEL);
        if (stated != null) {
            ids.add(stated.asString());
        }
        ids.add(material);

        return ids.stream().distinct().toList();
    }

    /**
     * The string as a key, or null for one that is not one.
     *
     * <p>A namespace is required rather than assumed. Custom_model_data is free text and a plugin may keep a count
     * or a flag in it; read {@code "5"} as a key and it becomes {@code minecraft:5}, which is a lookup into vanilla's
     * own assets for something that will never be there. Demanding the colon keeps this to strings that were written
     * to name something.
     */
    private static String keyed(String string) {
        if (string.indexOf(':') < 0) return null;

        try {
            return Key.key(string).asString();
        } catch (InvalidKeyException notAKey) {
            return null;
        }
    }
}
