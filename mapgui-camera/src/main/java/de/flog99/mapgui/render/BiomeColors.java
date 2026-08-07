package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What color grass, leaves and water come out in a given biome, worked out the way the client works it out.
 *
 * <p>{@code grass_block_top} is a flat grey on disk - #C7C7C7 on average, and leaves and {@code water_still} are the
 * same - so a frame drawn without tints renders a forest as concrete. The client multiplies those greys by a
 * per-biome color, from a 256x256 colormap png indexed by the biome's temperature and downfall.
 *
 * <p>The biome's numbers come out of {@code data/minecraft/worldgen/biome}, which the client jar ships alongside its
 * textures - the actual source rather than a hand-typed table of 66 biomes. It also carries the biomes that state a
 * grass or foliage color outright, and it is the only place a water color exists at all, since no server API has one.
 *
 * <p>A biome the assets have never heard of, which is every datapack biome, is left to the caller to describe:
 * {@link #fromClimate} takes the temperature and downfall the server can still be asked for and does the rest of
 * the arithmetic unchanged.
 */
public final class BiomeColors {

    /** Vanilla's fallback when a colormap index lands outside the image, and near enough to grass to notice. */
    private static final int OFF_MAP = 0xFFFF00FF;

    private static final int DEFAULT_WATER = 0xFF3F76E4;

    /** What the biome format itself defaults {@code water_fog_color} to, and what nearly every biome states. */
    private static final int DEFAULT_WATER_FOG = 0xFF050533;

    /** The overworld's own, for a biome that states none - which is every biome that has a sky instead. */
    private static final int DEFAULT_FOG = 0xFFC0D8FF;

    /**
     * How far the client says you can see under water, in blocks.
     *
     * <p>Its own {@code WATER_FOG_END_DISTANCE} default, and worth stating where it came from because the wrong end of
     * it is a very different picture: the client multiplies this by a water vision that starts at a quarter and climbs
     * to one over the first few seconds under. A quarter of it fades everything to near-black within 24 blocks. The
     * whole of it is what somebody who swam down to take a photograph actually sees.
     */
    private static final float DEFAULT_WATER_SIGHT = 96;

    /**
     * Where 26.2 keeps a biome's visual colours.
     *
     * <p>Not all of them, and that is the trap. {@code water_color}, {@code grass_color}, {@code foliage_color} and
     * {@code grass_color_modifier} are still in {@code effects} while {@code fog_color}, {@code sky_color} and
     * {@code water_fog_color} moved out to attributes - so reading one place finds some of them and silently defaults
     * the rest. Counted across all 66 biomes rather than assumed: eight state a fog color and eight a water fog color,
     * and every one of those is here.
     */
    private static final String VISUAL = "minecraft:visual/";

    /**
     * What one biome multiplies the greys by.
     *
     * <p>No sky here, deliberately. The client does derive a sky color per biome, but across the whole overworld
     * the result spans eleven of 255 in one channel - plains and desert differ by less than the map palette can
     * express - so it is a constant per dimension instead, in {@link Sky}.
     *
     * @param waterFog what a camera under water fades everything into, which is a far darker blue than the water
     *                 itself and is stated separately by every biome that has an opinion about either
     * @param fog      what the air itself is, which only matters where there is no sky to see: it is the whole of the
     *                 background in the Nether, and it is the difference between a crimson forest and a soul sand
     *                 valley
     * @param waterSight how far a camera under water can see, in blocks, which two biomes shorten because their water
     *                   is murkier than the rest
     */
    public record Tint(int grass, int foliage, int dryFoliage, int water, int waterFog, int fog, float waterSight) {
    }

    private final AssetStack stack;
    private final Textures textures;

    private final Map<String, Tint> byBiome = new ConcurrentHashMap<>();

    public BiomeColors(AssetStack stack, Textures textures) {
        this.stack = stack;
        this.textures = textures;
    }

    /**
     * The tint for a biome by its id without the namespace, so {@code snowy_taiga}.
     *
     * @return null when the assets do not describe this biome, which means a datapack made it up
     */
    public Tint of(String biome) {
        Tint known = byBiome.get(biome);
        if (known != null) return known;

        Tint resolved = read(biome);
        if (resolved != null) {
            byBiome.put(biome, resolved);
        }
        return resolved;
    }

    /**
     * The same arithmetic from climate alone, for a biome with no definition to read.
     *
     * @param temperature 0 to 1, as the biome format states it - not degrees of anything
     */
    public Tint fromClimate(float temperature, float downfall) {
        return new Tint(grassAt(temperature, downfall), foliageAt(temperature, downfall),
                dryFoliageAt(temperature, downfall), DEFAULT_WATER, vivid(DEFAULT_WATER_FOG, DEFAULT_WATER),
                DEFAULT_FOG, DEFAULT_WATER_SIGHT);
    }

    private Tint read(String biome) {
        JsonObject json = json(AssetStack.BIOMES + biome + ".json");
        if (json == null) return null;

        float temperature = json.has("temperature") ? json.get("temperature").getAsFloat() : 0.5f;
        float downfall = json.has("downfall") ? json.get("downfall").getAsFloat() : 0.5f;
        JsonObject effects = json.has("effects") ? json.getAsJsonObject("effects") : new JsonObject();
        JsonObject visual = visualOf(json);

        int grass = effects.has("grass_color")
                ? color(effects, "grass_color")
                : modified(grassAt(temperature, downfall), effects);
        int foliage = effects.has("foliage_color") ? color(effects, "foliage_color") : foliageAt(temperature, downfall);
        int dry = effects.has("dry_foliage_color") ? color(effects, "dry_foliage_color") : dryFoliageAt(temperature, downfall);
        int water = effects.has("water_color") ? color(effects, "water_color") : DEFAULT_WATER;
        int waterFog = stated(visual, effects, "water_fog_color", DEFAULT_WATER_FOG);
        int fog = stated(visual, effects, "fog_color", DEFAULT_FOG);
        float sight = distance(visual, "water_fog_end_distance", DEFAULT_WATER_SIGHT);

        return new Tint(grass, foliage, dry, water, vivid(waterFog, water), fog, sight);
    }

    /**
     * How much of the water's own colour the fog carries.
     *
     * <p>The one place this parts company with the client on purpose, for the reason the night sky and the shadow
     * lift already do: {@code water_fog_color} is near-black by design - #050533 for every ocean, and only eight
     * biomes in 26.2 state anything else at all - and a map has 143 colours and a viewer whose eye is adapted to
     * whatever else is on their screen. Faithful comes out as a black rectangle rather than as being under water.
     *
     * <p>Blended toward the biome's own {@code water_color} rather than toward a colour chosen here, so it stays
     * per-biome: an ocean lands on a deep blue, a swamp still fogs green and a warm ocean still fogs cyan.
     */
    private static final float WATER_FOG_VIVIDNESS = 0.7f;

    private static int vivid(int waterFog, int water) {
        return mix(waterFog, water, WATER_FOG_VIVIDNESS);
    }

    private static int mix(int from, int to, float amount) {
        int red = Math.round((from >> 16 & 0xFF) * (1 - amount) + (to >> 16 & 0xFF) * amount);
        int green = Math.round((from >> 8 & 0xFF) * (1 - amount) + (to >> 8 & 0xFF) * amount);
        int blue = Math.round((from & 0xFF) * (1 - amount) + (to & 0xFF) * amount);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * The attributes a biome states its visual colours in, flattened to their short names.
     *
     * <p>Kept as a plain object so the reader below can look in it and in {@code effects} without caring which of the
     * two a given version puts a color in.
     */
    private static JsonObject visualOf(JsonObject json) {
        JsonObject visual = new JsonObject();
        if (!json.has("attributes")) return visual;

        for (var attribute : json.getAsJsonObject("attributes").entrySet()) {
            if (attribute.getKey().startsWith(VISUAL)) {
                visual.add(attribute.getKey().substring(VISUAL.length()), attribute.getValue());
            }
        }
        return visual;
    }

    /**
     * A distance a biome states, which it may state as a number or as a change to the default.
     *
     * <p>Both forms are in the assets: a swamp says {@code {modifier: multiply, argument: 0.85}} rather than 81.6,
     * because it is describing water that is murkier than water rather than water that is 81.6 blocks clear.
     */
    private static float distance(JsonObject visual, String key, float fallback) {
        if (!visual.has(key)) return fallback;

        var value = visual.get(key);
        if (value.isJsonPrimitive()) return value.getAsFloat();
        if (!value.isJsonObject()) return fallback;

        JsonObject stated = value.getAsJsonObject();
        if (!stated.has("argument")) return fallback;

        float argument = stated.get("argument").getAsFloat();
        String modifier = stated.has("modifier") ? stated.get("modifier").getAsString() : "multiply";
        return switch (modifier) {
            case "add" -> fallback + argument;
            case "multiply" -> fallback * argument;
            default -> argument;
        };
    }

    /** A color from wherever this version states it, or the fallback when the biome has no opinion. */
    private static int stated(JsonObject visual, JsonObject effects, String key, int fallback) {
        if (visual.has(key)) return color(visual, key);
        if (effects.has(key)) return color(effects, key);

        return fallback;
    }

    /**
     * The two biomes that bend their computed grass color rather than replacing it.
     *
     * <p>Vanilla's swamp picks between two greens from a noise field. One of them, since a screenshot has to come
     * out the same twice and the difference is a few shades of the same murk.
     */
    private static int modified(int grass, JsonObject effects) {
        if (!effects.has("grass_color_modifier")) return grass;

        return switch (effects.get("grass_color_modifier").getAsString()) {
            case "swamp" -> 0xFF6A7039;
            case "dark_forest" -> 0xFF000000 | (grass & 0xFEFEFE) + 0x28340A >> 1;
            default -> grass;
        };
    }

    private int grassAt(float temperature, float downfall) {
        return colormap("colormap/grass", temperature, downfall);
    }

    private int foliageAt(float temperature, float downfall) {
        return colormap("colormap/foliage", temperature, downfall);
    }

    /** A third colormap, for the leaves that are dead rather than green: leaf litter and pale oak. */
    private int dryFoliageAt(float temperature, float downfall) {
        return colormap("colormap/dry_foliage", temperature, downfall);
    }

    /**
     * The colormap lookup itself.
     *
     * <p>Downfall is scaled by temperature before it is used, which is why the populated part of the image is a
     * triangle: a cold biome cannot be wet on this chart however much it rains.
     */
    private int colormap(String name, float temperature, float downfall) {
        // Double, as the client does it. The index truncates, so the last bit of precision decides which of two
        // neighbouring pixels is read: 0.8 stored as a float is a hair over 0.8, which lands this on 50 and not 51.
        double clampedTemperature = Math.clamp((double) temperature, 0, 1);
        double clampedDownfall = Math.clamp((double) downfall, 0, 1) * clampedTemperature;

        int x = (int) ((1 - clampedTemperature) * 255);
        int y = (int) ((1 - clampedDownfall) * 255);

        Texture map = textures.get(name);
        if (x >= map.width() || y >= map.height()) return OFF_MAP;

        return 0xFF000000 | map.argb()[y * map.width() + x];
    }

    /** A hex string since 26.2, a packed integer before it, and both mean the same 24 bits. */
    private static int color(JsonObject effects, String key) {
        String raw = effects.get(key).getAsString();
        int rgb = raw.startsWith("#") ? Integer.parseInt(raw.substring(1), 16) : Integer.parseInt(raw);
        return 0xFF000000 | rgb;
    }

    private JsonObject json(String path) {
        try {
            byte[] raw = stack.read(path);
            if (raw == null) return null;

            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // One unreadable biome should cost that biome its tint, not the capture.
            return null;
        }
    }
}
