package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which texture a mob's variant wears, out of the game's own variant registries.
 *
 * <pre>{@code
 * // data/minecraft/cat_variant/british_shorthair.json
 * { "asset_id": "minecraft:entity/cat/cat_british_shorthair",
 *   "baby_asset_id": "minecraft:entity/cat/cat_british_shorthair_baby" }
 * }</pre>
 *
 * <p>Read rather than guessed from the texture's spelling, which says nothing about a coat a datapack invented. The
 * registry name is a rule - {@code <type>_variant} for every one of them - so a version that adds another needs
 * nothing here.
 */
public final class EntityVariants {

    /** Data rather than assets, but shipped in the same jar and read the same way. */
    static final String REGISTRIES = "data/minecraft/";

    /** Also what {@link AssetRepack} keeps these by, so what is kept and what is read cannot drift. */
    static final String REGISTRY = "_variant/";

    /** Which of a wolf's three textures to take: the markings are the variant's and the face is the mood's. */
    public enum Mood {

        WILD("wild"),
        TAME("tame"),
        ANGRY("angry");

        private final String key;

        Mood(String key) {
            this.key = key;
        }
    }

    private final AssetStack stack;

    /** Keyed by everything that goes into the answer, since one entry states up to six textures. */
    private final Map<String, String> textures = new ConcurrentHashMap<>();

    /** Absent means "not looked up yet"; this means "looked up and there is nothing", which is the common answer. */
    private static final String NONE = "";

    /** What the client's renderers state, for the variants that never became registry entries. Read once. */
    private Map<String, List<RendererCoats.Coat>> coats;

    public EntityVariants(AssetStack stack) {
        this.stack = stack;
    }

    /**
     * The texture the client's own renderer hands out for this coat, or null when it names none.
     *
     * <p>By name first and by position second, because the two sides do not always agree on the name: a parrot the
     * server calls {@code cyan} the client calls {@code yellow_blue}, and the only thing tying them together is that
     * both are the fourth. Where the names do match, matching on them is safe against a reordering that position
     * alone would get wrong.
     *
     * @param ordinal where the server's own variant sits in its enum, or -1 when it is not one
     */
    public String coatOf(String type, String variant, int ordinal) {
        if (type == null || variant == null) return null;

        List<RendererCoats.Coat> stated = renderers().get(type);
        if (stated == null) return null;

        for (RendererCoats.Coat coat : stated) {
            if (coat.variant().equals(variant)) return coat.texture();
        }
        return ordinal >= 0 && ordinal < stated.size() ? stated.get(ordinal).texture() : null;
    }

    private synchronized Map<String, List<RendererCoats.Coat>> renderers() {
        if (coats == null) {
            try {
                coats = RendererCoats.read(stack.read(RendererCoats.FILE));
            } catch (IOException e) {
                coats = Map.of();
            }
        }
        return coats;
    }

    /**
     * The texture this variant of this mob wears, or null when nothing states one - the ordinary answer for the mobs
     * whose variants are still written into the client, and the caller's cue to fall back to its own guess.
     *
     * @param type    the vanilla entity id, lowercase and unqualified: {@code cat}, {@code wolf}
     * @param variant the variant id, likewise: {@code british_shorthair}, {@code ashen}
     * @param baby    falling back to the adult texture where the entry states only one
     */
    public String textureOf(String type, String variant, boolean baby, Mood mood) {
        if (type == null || variant == null) return null;

        String key = type + "/" + variant + (baby ? "/baby" : "") + "/" + mood;
        String found = textures.computeIfAbsent(key, ignored -> {
            String texture = read(type, variant, baby, mood);
            return texture == null ? NONE : texture;
        });
        return found.equals(NONE) ? null : found;
    }

    private String read(String type, String variant, boolean baby, Mood mood) {
        JsonObject entry = json(REGISTRIES + type + REGISTRY + variant + ".json");
        if (entry == null) return null;

        // The baby texture where one is stated, and the adult's where it is not - a frog has no young form to draw.
        String texture = baby ? asset(entry, "baby_asset_id", "baby_assets", mood) : null;
        return texture != null ? texture : asset(entry, "asset_id", "assets", mood);
    }

    /** One texture, whether the entry states a single one or a set to choose from by mood. */
    private static String asset(JsonObject entry, String single, String several, Mood mood) {
        if (entry.has(single) && entry.get(single).isJsonPrimitive()) {
            return unqualified(entry.get(single).getAsString());
        }
        if (!entry.has(several) || !entry.get(several).isJsonObject()) return null;

        JsonObject assets = entry.getAsJsonObject(several);
        // The mood it is in, or the wild coat, which every entry that states a set states.
        String key = assets.has(mood.key) ? mood.key : Mood.WILD.key;
        return assets.has(key) && assets.get(key).isJsonPrimitive() ? unqualified(assets.get(key).getAsString()) : null;
    }

    private JsonObject json(String path) {
        try {
            byte[] raw = stack.read(path);
            if (raw == null) return null;

            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // One unreadable entry costs that variant its coat, not the capture.
            return null;
        }
    }

    private static String unqualified(String asset) {
        int colon = asset.indexOf(':');
        return colon < 0 ? asset : asset.substring(colon + 1);
    }
}
