package de.flog99.mapgui.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a piece of equipment is actually made of, out of its own json.
 *
 * <p>Read rather than guessed at, and the guess it replaces was reasonable and wrong: every vanilla asset happens to
 * name a texture after itself, so probing {@code entity/equipment/<layer>/<asset>} finds iron for iron and diamond for
 * diamond. It finds leather for leather too - and leather is a greyscale shape the client multiplies by a dye color, so
 * drawn as it comes it is a light grey, which is to say it looks like iron. That is what leather trousers on a zombie
 * came out as.
 *
 * <p>The json says the two things the name cannot. That a layer may be <b>several</b> passes - leather is a dyeable base
 * with an undyed overlay on top of it, which is what keeps the buckles brown on pink armor. And that a pass is dyeable
 * at all, along with the color to use when nobody has dyed it.
 */
public final class EquipmentAssets {

    /**
     * One pass of one layer.
     *
     * @param texture   the name to sample, already qualified the way {@link TextureAtlas} wants it
     * @param undyed    the color to multiply it by when the stack carries no dye, or 0 for a pass that is not dyeable
     */
    public record Pass(String texture, int undyed) {
    }

    /** Where the textures for a layer live, under the entity textures. */
    private static final String LAYERS = "entity/equipment/";

    private final AssetStack stack;

    /** Keyed by asset and layer together, since one asset describes several layers and a mob wears one of them. */
    private final Map<String, List<Pass>> passes = new ConcurrentHashMap<>();

    public EquipmentAssets(AssetStack stack) {
        this.stack = stack;
    }

    /**
     * The passes that draw this equipment on this layer, nearest the skin first.
     *
     * <p>Empty when the asset says nothing about that layer, which is the honest answer for a saddle asked about as a
     * helmet - and the caller's cue to draw nothing rather than to draw something wrong.
     */
    public List<Pass> of(String asset, String layer) {
        return passes.computeIfAbsent(asset + "/" + layer, key -> read(asset, layer));
    }

    private List<Pass> read(String asset, String layer) {
        JsonObject json = json(asset);
        if (json == null || !json.has("layers")) return fallback(asset, layer);

        JsonObject layers = json.getAsJsonObject("layers");
        if (!layers.has(layer)) return List.of();

        List<Pass> found = new ArrayList<>();
        for (var element : layers.getAsJsonArray(layer)) {
            if (!element.isJsonObject()) continue;

            JsonObject pass = element.getAsJsonObject();
            if (!pass.has("texture")) continue;

            int undyed = 0;
            if (pass.has("dyeable")) {
                JsonObject dyeable = pass.getAsJsonObject("dyeable");
                // Opaque, so that zero can mean "not dyeable" without a second field. An asset that states a
                // transparent dye color is stating that it wants nothing drawn, which no vanilla one does.
                undyed = dyeable.has("color_when_undyed") ? 0xFF000000 | dyeable.get("color_when_undyed").getAsInt() : 0xFFFFFFFF;
            }
            found.add(new Pass(LAYERS + layer + "/" + unqualified(pass.get("texture").getAsString()), undyed));
        }
        return List.copyOf(found);
    }

    /**
     * What to draw when there is no json to read, which is an asset from a datapack or a subset packed before these
     * were kept: the texture named after the asset, undyed, exactly as it was probed for before.
     */
    private static List<Pass> fallback(String asset, String layer) {
        return List.of(new Pass(LAYERS + layer + "/" + asset, 0));
    }

    private JsonObject json(String asset) {
        try {
            byte[] raw = stack.read(AssetStack.EQUIPMENT + asset + ".json");
            if (raw == null) return null;

            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // One unreadable asset costs that piece its dye, not the capture.
            return null;
        }
    }

    private static String unqualified(String texture) {
        int colon = texture.indexOf(':');
        return colon < 0 ? texture : texture.substring(colon + 1);
    }
}
