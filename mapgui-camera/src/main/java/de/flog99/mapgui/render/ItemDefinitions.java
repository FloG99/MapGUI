package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each item is drawn from, out of {@code assets/minecraft/items/}.
 *
 * <p>A layer of indirection the game did not always have, and it cannot be worked around. A block item has no item
 * model of its own: the definition for {@code oak_planks} names {@code block/oak_planks} and that is the only place
 * the connection is written down. Guess the name instead and 731 of 26.2's items resolve to nothing, which is why a
 * block in a hand used to be a cube of one texture at the flat-item size.
 *
 * <p>One reader for both halves of the answer, since the pose and the geometry have to come from the same model. Two
 * readers is how they end up disagreeing, and a shape posed by somebody else's rules is worse than no shape.
 */
public final class ItemDefinitions {

    /**
     * What an item's definition says.
     *
     * @param model the model to draw it from, {@code block/oak_planks} or {@code item/apple}, never null and with the
     *              namespace already dropped - an item with no definition falls back to the model named after itself
     * @param tint  {@code 0xFFRRGGBB} to multiply the item's tinted faces by, or 0 for an item that states none.
     *              Separate from the model because it is the definition's own: the same {@code block/oak_leaves}
     *              geometry is drawn green in a hand and biome-green in the world
     */
    public record Definition(String model, int tint) {
    }

    private final AssetStack stack;

    /** For the one tint an item can state that is not a plain color, which is a colormap lookup. */
    private final BiomeColors colors;

    private final Map<String, Definition> definitions = new ConcurrentHashMap<>();

    public ItemDefinitions(AssetStack stack, BiomeColors colors) {
        this.stack = stack;
        this.colors = colors;
    }

    public Definition of(String item) {
        return definitions.computeIfAbsent(item, this::read);
    }

    /** An item with no definition, or one written in a shape this does not expect, is drawn from its own name. */
    private static Definition plain(String item) {
        return new Definition(item, 0);
    }

    private Definition read(String item) {
        JsonObject definition = json(AssetStack.ITEM_DEFINITIONS + item + ".json");
        if (definition == null || !definition.has("model") || !definition.get("model").isJsonObject()) {
            return plain(item);
        }

        JsonObject model = definition.getAsJsonObject("model");
        try {
            return new Definition(named(model, item), tintOf(model));
        } catch (RuntimeException e) {
            // Worth catching rather than trusting the shape: one definition read as something it is not used to throw
            // out of here, and what it took down was the whole entity pass of the capture.
            return plain(item);
        }
    }

    /**
     * The model a definition names, or the item's own name when it names none.
     *
     * <p>Two ways of naming none, and the second one is not a hypothetical. A definition may pick its model by
     * condition rather than stating one - a trident, a crossbow being drawn - and there is no {@code model} key at all.
     * Or it may be one of the fifty-one that the client draws in code, where {@code model} is present but is an object
     * describing a banner or a shulker box rather than a name. Read as a string that one threw, which took the whole
     * entity pass of a capture down with it for anybody holding a banner.
     */
    private static String named(JsonObject model, String item) {
        if (!model.has("model") || !model.get("model").isJsonPrimitive()) return item;

        String stated = model.get("model").getAsString();
        int colon = stated.indexOf(':');
        return colon < 0 ? stated : stated.substring(colon + 1);
    }

    /**
     * The first tint the definition states, resolved to a color.
     *
     * <p>Only the first, because a model states one {@code tintindex} per face and every block item that states any
     * uses index 0. The rest of vanilla's tint sources - a potion's contents, a firework's colors, a map's markers -
     * belong to items drawn as sprites, which carry their color in the png.
     */
    private int tintOf(JsonObject model) {
        if (!model.has("tints")) return 0;

        var tints = model.getAsJsonArray("tints");
        if (tints.isEmpty() || !tints.get(0).isJsonObject()) return 0;

        JsonObject tint = tints.get(0).getAsJsonObject();
        String type = tint.has("type") ? tint.get("type").getAsString() : "";
        return switch (type) {
            case "minecraft:constant" -> tint.has("value") ? 0xFF000000 | tint.get("value").getAsInt() : 0;
            // The one climate the client holds every item at, stated by the item rather than taken from where the
            // holder is standing - so a grass block is the same green in a swamp and on a mountain.
            case "minecraft:grass" -> colors.fromClimate(number(tint, "temperature", 0.5f), number(tint, "downfall", 1f)).grass();
            default -> 0;
        };
    }

    private static float number(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    /** One json by its whole path, or null for anything missing or malformed - which costs that item its model only. */
    private JsonObject json(String path) {
        try {
            byte[] raw = stack.read(path);
            if (raw == null) return null;

            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
