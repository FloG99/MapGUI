package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
    public record Definition(String model, int tint, Special special) {

        /** For the great majority, which name a model and nothing else. */
        Definition(String model, int tint) {
            this(model, tint, null);
        }
    }

    /**
     * A shape the client draws in code rather than from a model, as the definition names it.
     *
     * <p>Thirteen of these in 26.2 - a chest, a banner, a shield, a head - and their common shape is what makes them
     * worth reading rather than special-casing: a name for the renderer, whatever that renderer takes, and the
     * transform that places its mesh inside the item's own box.
     *
     * @param type    the client's own id, unqualified: {@code chest}, {@code banner}, {@code shulker_box}
     * @param texture what the renderer takes where it takes one - a chest's wood, a shulker's sheet - or null
     * @param color   and the dye where it takes one of those, which is a banner, or null
     * @param turn    the stated rotation as a 3x3, the two quaternions and the sign of the scale composed together
     * @param scale   how far the mesh shrinks, which is uniform in every one vanilla states
     * @param offset  where in the item's box it sits, in entity pixels
     */
    public record Special(String type, String texture, String color, float[] turn, float scale, float[] offset) {
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
        JsonObject definition = json(AssetStack.asset(item, "items", ".json"));
        if (definition == null || !definition.has("model") || !definition.get("model").isJsonObject()) {
            return plain(item);
        }

        JsonObject outer = definition.getAsJsonObject("model");
        JsonObject model = chosen(outer, 0);
        try {
            return new Definition(named(model, item), tintOf(model), specialOf(model, placing(outer, 0)));
        } catch (RuntimeException e) {
            // Worth catching rather than trusting the shape: one definition read as something it is not used to throw
            // out of here, and what it took down was the whole entity pass of the capture.
            return plain(item);
        }
    }

    /**
     * Down through the branches a definition may be wrapped in, to the model it draws when nothing unusual is true.
     *
     * <p>A capture cannot evaluate any of these conditions - a shield is drawn one way while its holder is blocking
     * and a chest wears tinsel between the 24th and the 26th of December - so each is read at its own default: the
     * fallback of a {@code select}, the false branch of a {@code condition}. Not reading them at all is what left a
     * shield and a chest resolving to their own name and drawing nothing.
     */
    private static JsonObject chosen(JsonObject model, int depth) {
        if (depth > 8) return model;

        for (String branch : List.of("fallback", "on_false")) {
            if (model.has(branch) && model.get(branch).isJsonObject()) {
                return chosen(model.getAsJsonObject(branch), depth + 1);
            }
        }
        return model;
    }

    /**
     * The transform that places whatever this branch resolves to, which is not always written on the branch itself.
     *
     * <p>A shield states it on the {@code condition} wrapping its two poses and a copper golem statue on the
     * {@code select} wrapping its four, because the transform is the same whichever way the branch goes and vanilla
     * writes it once. So the walk down keeps the innermost one it finds and falls back to an outer one - which is the
     * difference between a shield the right way up and a shield hanging by its boss.
     *
     * @return the transformation object, or an empty one for a definition that states none
     */
    private static JsonObject placing(JsonObject model, int depth) {
        if (depth <= 8) {
            for (String branch : List.of("fallback", "on_false")) {
                if (model.has(branch) && model.get(branch).isJsonObject()) {
                    JsonObject inner = placing(model.getAsJsonObject(branch), depth + 1);
                    if (!inner.isEmpty()) return inner;
                }
            }
        }

        return model.has("transformation") && model.get("transformation").isJsonObject()
                ? model.getAsJsonObject("transformation")
                : new JsonObject();
    }

    /** The special this definition names, or null for the great majority that name a model. */
    private static Special specialOf(JsonObject model, JsonObject placed) {
        if (!SPECIAL.equals(typeOf(model)) || !model.has("model") || !model.get("model").isJsonObject()) return null;

        JsonObject drawn = model.getAsJsonObject("model");
        String type = typeOf(drawn);
        if (type == null) return null;

        float[] scale = triple(placed, "scale", 1);
        // Uniform in every one vanilla states, and the signs are a turn rather than a size: a scale of (1, -1, -1) is
        // a half circle about X, which is how these are stood the right way up.
        float size = Math.abs(scale[0]);
        float[] signs = {Math.signum(scale[0]), Math.signum(scale[1]), Math.signum(scale[2])};

        float[] turn = Turns.times(quaternion(placed, "left_rotation"),
                Turns.times(new float[]{signs[0], 0, 0, 0, signs[1], 0, 0, 0, signs[2]},
                        quaternion(placed, "right_rotation")));

        float[] offset = triple(placed, "translation", 0);
        return new Special(AssetStack.pathOf(type), string(drawn, "texture"), string(drawn, "color"),
                turn, size, new float[]{offset[0] * PIXELS, offset[1] * PIXELS, offset[2] * PIXELS});
    }

    /** A transform's translation is stated in blocks and everything downstream of here counts in sixteenths. */
    private static final float PIXELS = 16;

    private static final String SPECIAL = "minecraft:special";

    private static String typeOf(JsonObject json) {
        return json.has("type") && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : null;
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? AssetStack.pathOf(json.get(key).getAsString()) : null;
    }

    private static float[] quaternion(JsonObject placed, String key) {
        if (!placed.has(key)) return Turns.none();

        var array = placed.getAsJsonArray(key);
        if (array.size() < 4) return Turns.none();

        return Turns.quaternion(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(), array.get(3).getAsFloat());
    }

    private static float[] triple(JsonObject json, String key, float fallback) {
        float[] out = {fallback, fallback, fallback};
        if (!json.has(key)) return out;

        var array = json.getAsJsonArray(key);
        for (int i = 0; i < 3 && i < array.size(); i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }

    /**
     * The model a definition names, or the item's own name when it names none.
     *
     * <p>Two ways of naming none, and the second one is not a hypothetical. A definition may pick its model by
     * condition rather than stating one - a trident, a crossbow being drawn - and there is no {@code model} key at all.
     * Or it may be one of the fifty-one that the client draws in code, where {@code model} is present but is an object
     * describing a banner or a shulker box rather than a name. Read as a string that one threw, which took the whole
     * entity pass of a capture down with it for anybody holding a banner.
     *
     * <p>Those fifty-one do name a model, though, under {@code base}: a shape the client never draws that carries the
     * {@code display} block the transforms come from. Which is the difference between a head held the way the client
     * holds one and a head held like an apple.
     */
    private static String named(JsonObject model, String item) {
        if (model.has("model") && model.get("model").isJsonPrimitive()) {
            // Kept whole, namespace and all. A pack's item names its own model, and dropping the namespace here
            // is what used to send that lookup into assets/minecraft/ to find nothing.
            return model.get("model").getAsString();
        }
        if (model.has("base") && model.get("base").isJsonPrimitive()) return model.get("base").getAsString();

        return item;
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
