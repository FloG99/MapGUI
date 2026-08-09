package de.flog99.mapgui.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a block state into the boxes and textures a ray hits.
 *
 * <p>The chain is the client's own: a blockstate json picks a model for the state, the model inherits elements and
 * texture variables from its parents, and the variables resolve to texture names. Walking {@code parent} is not
 * optional - 2314 of 2657 vanilla block models declare no elements of their own.
 *
 * <p>Cached per state string, so the json work happens once per state rather than once per block.
 */
public final class BlockModels {

    /** How a texture's own pixels behave, so this class never has to decode a png. */
    interface TextureAlpha {

        BakedState.Alpha classify(String texture);
    }

    /** Fluids have no geometry in json, since the client renders them itself, so they are built rather than read. */
    private static final Map<String, String> FLUID_TEXTURES = Map.of(
            "water", "block/water_still",
            "lava", "block/lava_still"
    );

    /** The moving surface, which is a second image rather than the still one turned. Named in the client's own code too. */
    private static final Map<String, String> FLUID_FLOW = Map.of(
            "water", "block/water_flow",
            "lava", "block/lava_flow"
    );

    /**
     * Blocks that stand in water without a {@code waterlogged} property to say so. A plugin cannot ask what fluid a
     * block sits in, so without these an ocean of kelp is a field of holes with air around every stalk.
     *
     * <p>Counted rather than collected: these are exactly the five vanilla blocks whose own {@code getFluidState}
     * hands back water while carrying no waterlogged property. The server could be asked instead, which would cover
     * a modded block too, but only through a fifth thing that touches {@code net.minecraft} - and for vanilla the
     * answer would be this list.
     */
    private static final Set<String> ALWAYS_FLOODED = Set.of(
            "kelp", "kelp_plant", "seagrass", "tall_seagrass", "bubble_column"
    );

    private final AssetStack stack;
    private final TextureAlpha alpha;

    /**
     * Concurrent because baking happens on demand from a trace that runs on several threads, so the first capture
     * after a start has all of them inserting at once. A plain map threw {@code ConcurrentModificationException} out
     * of {@code computeIfAbsent} and failed that capture, reliably the first and only the first.
     */
    private final Map<String, BakedState> baked = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> models = new ConcurrentHashMap<>();

    /** Keyed by model name rather than by state, for the items drawn from one - see {@link #shape}. */
    private final Map<String, List<BakedElement>> shapes = new ConcurrentHashMap<>();

    public BlockModels(AssetStack stack, TextureAlpha alpha) {
        this.stack = stack;
        this.alpha = alpha;
    }

    /**
     * Bakes one block state, named the way the server names it.
     *
     * @param state {@code BlockData#getAsString()}, so {@code minecraft:oak_log[axis=y]} - which is exactly the key a
     *              blockstate json is written against, and the whole bridge from the server to the assets
     */
    public BakedState bake(String state) {
        return bake(state, false);
    }

    /**
     * @param covered whether the same fluid stands directly above, which fills a fluid block to the top the way
     *                vanilla does - only the surface of a pool is the eight ninths a level states, and without this
     *                every block in an ocean would be short and the whole body would come out as steps
     */
    public BakedState bake(String state, boolean covered) {
        return baked.computeIfAbsent(covered ? state + COVERED : state, this::resolve);
    }

    /** Part of the cache key rather than of the state, so it cannot collide with a real property. */
    private static final String COVERED = " covered";

    /** How many distinct states have been baked, for the ready message and for a sanity check in tests. */
    public int size() {
        return baked.size();
    }

    /**
     * One named model baked on its own, with no blockstate above it to pick a variant or turn it - for the 731 items
     * whose definition names a block model outright. Empty for a name that resolves to no geometry.
     */
    public List<BakedElement> shape(String model) {
        return shapes.computeIfAbsent(model, this::resolveShape);
    }

    private List<BakedElement> resolveShape(String model) {
        Geometry geometry = new Geometry();
        // Nameless, so a stated tintindex comes back as a plain "this face is tinted" - an item states its own colour
        // in its definition, and a held pale oak leaf is untinted where the block in the world is not.
        collectElements(AssetStack.canonical(model), 0, 0, geometry);
        return List.copyOf(geometry.elements);
    }

    private BakedState resolve(String key) {
        boolean covered = key.endsWith(COVERED);
        String state = covered ? key.substring(0, key.length() - COVERED.length()) : key;

        String id = blockId(state);
        Map<String, String> properties = properties(state);

        JsonObject blockstate = readJson(AssetStack.BLOCKSTATES + id + ".json");
        if (blockstate == null) {
            BakedState built = fluid(id, properties, covered);
            return built != null ? built : BakedState.EMPTY;
        }

        Geometry geometry = new Geometry();
        geometry.blockId = id;
        if (blockstate.has("multipart")) {
            for (JsonElement part : blockstate.getAsJsonArray("multipart")) {
                JsonObject entry = part.getAsJsonObject();
                if (matches(entry.get("when"), properties)) {
                    apply(entry.get("apply"), geometry);
                }
            }
        } else if (blockstate.has("variants")) {
            apply(variantFor(blockstate.getAsJsonObject("variants"), properties), geometry);
        }

        if (geometry.elements.isEmpty()) {
            BakedState built = fluid(id, properties, covered);
            if (built != null) return built;

            BakedState drawn = drawnByTheClient(id);
            return drawn != null ? drawn : BakedState.EMPTY;
        }

        // A waterlogged stair or fence is standing in water, and no part of its own model says so - vanilla draws
        // the fluid separately, so the water cube is added here.
        if ("true".equals(properties.get("waterlogged")) || ALWAYS_FLOODED.contains(id)) {
            int standing = covered ? FULL : SOURCE;
            geometry.elements.add(fluidCube(FLUID_TEXTURES.get("water"), true, standing));
            return new BakedState(List.copyOf(geometry.elements), false, BakedState.Alpha.TRANSLUCENT, true, false,
                    standing, FLUID_FLOW.get("water"));
        }

        // Every element being a full block, rather than there being only one: grass_block is a full cube of dirt
        // and grass with a second full cube of tinted side overlay on it, and it is most of what an outdoor scene
        // is made of. Both layers still composite - this only says the face the DDA reported is the one that was
        // hit, so no slab test is needed to find it.
        boolean fullCube = geometry.elements.stream().allMatch(BakedElement::isFullBlock);
        return new BakedState(List.copyOf(geometry.elements), fullCube, alphaOf(geometry), false, id.endsWith("_leaves"), 0);
    }

    /**
     * A stand-in for the two blocks whose model json carries no geometry because the client draws them itself. Baked
     * as they come they produce no elements, and you look through the portal at whatever is under it.
     *
     * <p>Approximated rather than reproduced: vanilla's is a scrolling parallax of sixteen layers, and none of that
     * survives one byte per pixel. Top face only, because a portal is a floor you fall through.
     */
    private BakedState drawnByTheClient(String id) {
        if (!id.equals("end_portal") && !id.equals("end_gateway")) return null;

        BakedFace[] faces = new BakedFace[6];
        faces[Direction.UP.ordinal()] = BakedFace.whole("entity/end_portal/end_portal");
        BakedElement surface = new BakedElement(0, 0, 0, 16, PORTAL_SURFACE, 16, faces, false, 0, 0, null, 15);

        return new BakedState(List.of(surface), false, BakedState.Alpha.OPAQUE);
    }

    /** Where vanilla puts the portal's surface within its block, in sixteenths. */
    private static final float PORTAL_SURFACE = 12;

    /**
     * Elements as they are collected, plus whether anything in them insisted on blending. {@code force_translucent}
     * is stated per texture reference but decides how the whole block behaves, since the client picks a render layer
     * per block rather than per face.
     */
    private static final class Geometry {

        private final List<BakedElement> elements = new ArrayList<>();
        private boolean forcedTranslucent;

        /** What this block's {@code tintindex} resolves to, since the number alone does not say. */
        private String blockId = "";
    }

    /**
     * Which color a tinted face on this block actually wants. Not derivable from the model - every one of these
     * states the same {@code tintindex: 0} - so it mirrors the client's registry of per-block color providers, and
     * only the blocks whose answer is not the grass color need naming.
     */
    private static int tintOf(String id, int stated) {
        return switch (id) {
            case "spruce_leaves" -> Tints.EVERGREEN;
            case "birch_leaves" -> Tints.BIRCH;
            case "lily_pad" -> Tints.LILY_PAD;
            case "redstone_wire" -> Tints.REDSTONE;
            // Pink and white already, so the petals want no tint - index 1 is the leaves under them.
            case "pink_petals", "wildflowers" -> stated == 0 ? Tints.NONE : Tints.GRASS;
            // Dead rather than green, which is what the third colormap is for.
            case "leaf_litter", "pale_oak_leaves" -> Tints.DRY_FOLIAGE;
            // Already coloured on disk rather than grey, so the client leaves them alone.
            case "cherry_leaves", "azalea_leaves", "flowering_azalea_leaves", "stonecutter" -> Tints.NONE;
            default -> id.endsWith("_leaves") || id.equals("vine") || id.equals("bamboo") ? Tints.FOLIAGE : Tints.GRASS;
        };
    }

    /** A body of the still texture, as deep as its level says, translucent for water and opaque for lava. */
    private BakedState fluid(String id, Map<String, String> properties, boolean covered) {
        String texture = FLUID_TEXTURES.get(id);
        if (texture == null) return null;

        boolean water = id.equals("water");
        int height = covered ? FULL : depthOf(properties);
        // Only a full one can cull the block above it, and only a full one is a full cube to the tracer.
        return new BakedState(List.of(fluidCube(texture, water, height)), height == FULL,
                water ? BakedState.Alpha.TRANSLUCENT : BakedState.Alpha.OPAQUE, water, false, height,
                FLUID_FLOW.get(id));
    }

    private static final int FULL = 16;

    /** Eight ninths, which is what a source stands at and the dip you can see across any pool. */
    private static final int SOURCE = Math.round(8 / 9f * FULL);

    /**
     * How deep a fluid stands, in sixteenths, from the {@code level} its state carries.
     *
     * <p>Vanilla's own arithmetic. A source is eight ninths rather than full, which is the dip you can see across
     * any pool, and each step away from it loses another ninth - so a stream is a staircase and reads as running
     * downhill without anything here having to work out which way that is. Level eight and up is fluid that is
     * falling, which fills its block whatever the number says.
     */
    private static int depthOf(Map<String, String> properties) {
        String stated = properties.get("level");
        if (stated == null) return FULL;

        int level;
        try {
            level = Integer.parseInt(stated);
        } catch (NumberFormatException e) {
            return FULL;
        }
        if (level >= 8) return FULL;

        return Math.round((8 - level) / 9f * FULL);
    }

    /**
     * A cube of fluid, every side face culled by its neighbour so a body of it reads as one surface.
     *
     * <p>The top is only culled when the fluid fills its block. Culling it on a shallow one opens a hole in the
     * surface of every stream, since what is above is air and there is nothing behind to draw.
     */
    private static BakedElement fluidCube(String texture, boolean water, int height) {
        BakedFace[] faces = new BakedFace[6];
        for (Direction direction : Direction.values()) {
            boolean culled = height == FULL || direction != Direction.UP;
            // Fluid whether or not it is water: lava's sides meet lava's the same way, and marking only water left
            // every lava block drawing all six of them.
            faces[direction.ordinal()] = new BakedFace(texture, 0, 0, 16, 16, 0,
                    water ? Tints.WATER : Tints.NONE, culled ? direction : null, true);
        }

        // Shaded whatever it is standing at. Its depth decides how tall the box is and nothing else: the client
        // lights a fluid by the direction of the face like any other block, so tying the two together left the
        // side of every shallow stream flat and bright against the full blocks beside it.
        return new BakedElement(0, 0, 0, 16, height, 16, faces, true, 0, 0);
    }

    /**
     * A variant value is one model or a weighted list the client picks from at random for visual variety. A
     * screenshot has to come out the same every time it is taken, so the first is always chosen.
     */
    private void apply(JsonElement value, Geometry into) {
        if (value == null) return;

        JsonObject model = value.isJsonArray()
                ? value.getAsJsonArray().get(0).getAsJsonObject()
                : value.getAsJsonObject();

        String name = model.has("model") ? stripNamespace(model.get("model").getAsString()) : null;
        if (name == null) return;

        int x = model.has("x") ? model.get("x").getAsInt() : 0;
        int y = model.has("y") ? model.get("y").getAsInt() : 0;
        collectElements(name, x, y, into);
    }

    /** The variant whose every {@code key=value} is satisfied. {@code ""} matches a block with no properties. */
    private JsonElement variantFor(JsonObject variants, Map<String, String> properties) {
        JsonElement fallback = null;

        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty()) {
                fallback = entry.getValue();
                continue;
            }

            boolean all = true;
            for (String condition : key.split(",")) {
                int split = condition.indexOf('=');
                if (split < 0) {
                    continue;
                }
                if (!condition.substring(split + 1).equals(properties.get(condition.substring(0, split)))) {
                    all = false;
                    break;
                }
            }

            if (all) return entry.getValue();
        }

        return fallback;
    }

    /**
     * Multipart conditions: every key has to match, {@code OR} takes a list of alternatives, and a value of
     * {@code a|b} means either. A part with no {@code when} always applies - the post of a fence.
     */
    private boolean matches(JsonElement when, Map<String, String> properties) {
        if (when == null) return true;

        JsonObject conditions = when.getAsJsonObject();
        if (conditions.has("OR")) {
            for (JsonElement option : conditions.getAsJsonArray("OR")) {
                if (matches(option, properties)) return true;
            }
            return false;
        }

        if (conditions.has("AND")) {
            for (JsonElement option : conditions.getAsJsonArray("AND")) {
                if (!matches(option, properties)) return false;
            }
            return true;
        }

        for (Map.Entry<String, JsonElement> condition : conditions.entrySet()) {
            String actual = properties.get(condition.getKey());
            if (actual == null) return false;

            boolean any = false;
            for (String allowed : condition.getValue().getAsString().split("\\|")) {
                if (allowed.equals(actual)) {
                    any = true;
                    break;
                }
            }
            if (!any) return false;
        }

        return true;
    }

    /** Walks the parent chain for elements and texture variables, then rotates what it finds. */
    private void collectElements(String modelName, int rotX, int rotY, Geometry into) {
        Map<String, JsonElement> textures = new LinkedHashMap<>();
        JsonArray elements = null;

        String name = modelName;
        // Bounded rather than while(true): a pack with a parent cycle would otherwise hang the bake, and a
        // vanilla chain is four deep.
        for (int depth = 0; depth < 16 && name != null; depth++) {
            JsonObject model = model(name);
            if (model == null) break;

            if (model.has("textures")) {
                // The child was read first and wins, so a parent only fills in what is still missing.
                for (Map.Entry<String, JsonElement> entry : model.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }

            if (elements == null && model.has("elements")) {
                elements = model.getAsJsonArray("elements");
            }

            name = model.has("parent") ? AssetStack.canonical(model.get("parent").getAsString()) : null;
        }

        if (elements == null) return;

        for (JsonElement element : elements) {
            BakedElement box = element(element.getAsJsonObject(), textures, rotX, rotY, into);
            if (box != null) {
                into.elements.add(box);
            }
        }
    }

    private BakedElement element(JsonObject element, Map<String, JsonElement> textures, int rotX, int rotY, Geometry into) {
        if (!element.has("from") || !element.has("to")) return null;

        float[] from = triple(element.getAsJsonArray("from"));
        float[] to = triple(element.getAsJsonArray("to"));

        float[] a = rotatePoint(from, rotX, rotY);
        float[] b = rotatePoint(to, rotX, rotY);

        BakedFace[] faces = new BakedFace[6];
        if (element.has("faces")) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject("faces").entrySet()) {
                Direction side = Direction.byKey(entry.getKey());
                if (side == null) {
                    continue;
                }

                BakedFace face = face(entry.getValue().getAsJsonObject(), textures, into, side, from, to);
                if (face != null) {
                    Direction cull = face.cull() == null ? null : face.cull().rotate(rotX, rotY);
                    // The face moves with the box, so a rotated model's "up" can be the world's "north" - and its
                    // cullface names a world direction, so that turns with it too.
                    faces[side.rotate(rotX, rotY).ordinal()] = cull == face.cull()
                            ? face
                            : new BakedFace(face.texture(), face.u1(), face.v1(), face.u2(), face.v2(), face.rotation(), face.tint(), cull);
                }
            }
        }

        return new BakedElement(
                Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]),
                faces,
                !element.has("shade") || element.get("shade").getAsBoolean(),
                rotX,
                rotY,
                rotationOf(element, rotX, rotY),
                element.has("light_emission") ? Math.clamp(element.get("light_emission").getAsInt(), 0, 15) : 0
        );
    }

    /**
     * The box's own turn, moved into the space the blockstate rotation left the model in. Both the point and the axis
     * are stated in the model's own coordinates, so the quarter turn carries them along - and an axis that comes back
     * pointing the other way is the same axis turning the other way, which is why the angle picks up the sign.
     */
    private static ElementRotation rotationOf(JsonObject element, int rotX, int rotY) {
        if (!element.has("rotation")) return null;

        JsonObject rotation = element.getAsJsonObject("rotation");
        if (!rotation.has("angle") || !rotation.has("axis")) return null;

        float angle = rotation.get("angle").getAsFloat();
        if (angle == 0) return null;

        Direction axis = switch (rotation.get("axis").getAsString()) {
            case "x" -> Direction.EAST;
            case "y" -> Direction.UP;
            default -> Direction.SOUTH;
        };
        Direction turned = axis.rotate(rotX, rotY);

        float[] origin = rotation.has("origin") ? triple(rotation.getAsJsonArray("origin")) : new float[]{8, 8, 8};
        float[] at = rotatePoint(origin, rotX, rotY);

        int index = turned.dx() != 0 ? 0 : turned.dy() != 0 ? 1 : 2;
        boolean reversed = turned.dx() + turned.dy() + turned.dz() < 0;

        return new ElementRotation(at[0], at[1], at[2], index, reversed ? -angle : angle,
                rotation.has("rescale") && rotation.get("rescale").getAsBoolean());
    }

    private BakedFace face(JsonObject face, Map<String, JsonElement> textures, Geometry into,
                           Direction side, float[] from, float[] to) {
        if (!face.has("texture")) return null;

        Resolved resolved = resolveTexture(face.get("texture").getAsString(), textures);
        if (resolved == null) return null;

        into.forcedTranslucent |= resolved.forceTranslucent();

        // What the model would have derived for this face if it stated no uv, which is also the pair a stated one is
        // understood against.
        float[] auto = autoUv(side, from, to);
        float[] uv = face.has("uv") ? quad(face.getAsJsonArray("uv")) : auto;

        // Fitted to the box rather than to the block, so the tracer can go on sampling in plain block coordinates.
        float[] across = fit(uv[0], uv[2], auto[0], auto[2]);
        float[] down = fit(uv[1], uv[3], auto[1], auto[3]);
        float u1 = across[0];
        float u2 = across[1];
        float v1 = down[0];
        float v2 = down[1];

        int rotation = face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt(), 360) : 0;
        int tint = face.has("tintindex")
                ? tintOf(into.blockId, face.get("tintindex").getAsInt())
                : Tints.NONE;
        Direction cull = face.has("cullface") ? Direction.byKey(face.get("cullface").getAsString()) : null;

        return new BakedFace(resolved.texture(), u1, v1, u2, v2, rotation, tint, cull);
    }

    /**
     * The uv a face would get if the model stated none, as {@code u1, v1, u2, v2} with the smaller end first.
     *
     * <p>{@link BakedFace#u} and {@link BakedFace#v} evaluated at the box's own bounds, and it has to be: a face with
     * no stated uv is drawn by sampling straight off the block coordinate, so the rect that reproduces it is the one
     * that formula spans.
     */
    private static float[] autoUv(Direction side, float[] from, float[] to) {
        float[] u = switch (side) {
            case UP, DOWN, SOUTH -> new float[]{from[0], to[0]};
            case NORTH -> new float[]{16 - to[0], 16 - from[0]};
            case WEST -> new float[]{from[2], to[2]};
            case EAST -> new float[]{16 - to[2], 16 - from[2]};
        };
        float[] v = switch (side) {
            case UP -> new float[]{from[2], to[2]};
            case DOWN -> new float[]{16 - to[2], 16 - from[2]};
            default -> new float[]{16 - to[1], 16 - from[1]};
        };

        return new float[]{Math.min(u[0], u[1]), Math.min(v[0], v[1]), Math.max(u[0], u[1]), Math.max(v[0], v[1])};
    }

    /**
     * The stated range rewritten so that spreading it over the whole block spreads it over this box instead.
     *
     * <p>Fire is what this exists for: its quads are 22.4 sixteenths tall and state a uv of the whole texture, so
     * sampling off the block coordinate ran past the texture's edge and wrapped, repeating the pattern up the flames.
     * A face stating exactly what it would have been given comes back as the full 0 to 16 unchanged.
     */
    private static float[] fit(float low, float high, float autoLow, float autoHigh) {
        float span = autoHigh - autoLow;
        // A box with no extent across this axis has nothing to fit to - a cross model's plane is one.
        if (Math.abs(span) < 1e-4f) return new float[]{low, high};

        float scale = 16 * (high - low) / span;
        float start = low - autoLow * scale / 16;
        return new float[]{start, start + scale};
    }

    /** A texture name and whether the model insisted it blends. */
    private record Resolved(String texture, boolean forceTranslucent) {
    }

    /**
     * Follows {@code #side} through the merged texture map until it lands on a real name. The value may be a plain
     * string or an object carrying {@code sprite} alongside {@code force_translucent}, which is how glass declares
     * that a texture with no transparent pixels should still blend - 110 vanilla models use it, and reading it is
     * what keeps the translucent set out of a hardcoded list of materials.
     */
    private Resolved resolveTexture(String reference, Map<String, JsonElement> textures) {
        String current = reference;
        boolean forced = false;

        for (int depth = 0; depth < 16; depth++) {
            if (!current.startsWith("#")) {
                return new Resolved(AssetStack.canonical(current), forced);
            }

            JsonElement value = textures.get(current.substring(1));
            if (value == null) return null;

            if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                forced |= object.has("force_translucent") && object.get("force_translucent").getAsBoolean();
                if (!object.has("sprite")) return null;

                current = object.get("sprite").getAsString();
            } else {
                current = value.getAsString();
            }
        }

        return null;
    }

    /** Translucent beats cutout beats opaque, since either of the first two means the ray carries on. */
    private BakedState.Alpha alphaOf(Geometry geometry) {
        // The model's own word overrides what the pixels look like. Glass declares this and its texture has no
        // partial alpha at all, so reading the png alone would call it opaque or a cutout and never blend it.
        if (geometry.forcedTranslucent) return BakedState.Alpha.TRANSLUCENT;

        boolean cutout = false;

        for (BakedElement element : geometry.elements) {
            for (BakedFace face : element.faces()) {
                if (face == null) {
                    continue;
                }

                switch (alpha.classify(face.texture())) {
                    case TRANSLUCENT -> {
                        return BakedState.Alpha.TRANSLUCENT;
                    }
                    case CUTOUT -> cutout = true;
                    case OPAQUE -> {
                    }
                }
            }
        }

        return cutout ? BakedState.Alpha.CUTOUT : BakedState.Alpha.OPAQUE;
    }

    /**
     * A quarter turn about the block's middle, x first then y, matching {@link Direction#rotate}. Ninety degree steps
     * keep a box axis-aligned, so the result stays a {@link BakedElement} rather than an oriented box.
     */
    private static float[] rotatePoint(float[] point, int rotX, int rotY) {
        float[] result = point.clone();

        for (int i = 0; i < Math.floorMod(rotX, 360) / 90; i++) {
            result = new float[]{result[0], result[2], 16 - result[1]};
        }
        for (int i = 0; i < Math.floorMod(rotY, 360) / 90; i++) {
            result = new float[]{16 - result[2], result[1], result[0]};
        }

        return result;
    }

    private JsonObject model(String name) {
        return models.computeIfAbsent(name, key -> readJson(AssetStack.asset(qualified(key), "models", ".json")));
    }

    /** Model references carry a {@code block/} prefix the directory already implies. */
    private static String strip(String name) {
        return name.startsWith("block/") ? name.substring("block/".length()) : name;
    }

    /**
     * A model id with its folder made explicit, so the path builder needs no special case.
     *
     * <p>A bare name is a block model, which is what every unqualified parent in vanilla's own block files means.
     * Anything already saying {@code block/} or {@code item/} is left alone - the second is how a pack's own item
     * model gets here at all.
     */
    private static String qualified(String name) {
        String path = AssetStack.pathOf(name);
        if (path.startsWith("block/") || path.startsWith("item/")) return name;

        return AssetStack.beside(name, "block/" + path);
    }

    private JsonObject readJson(String path) {
        try {
            byte[] raw = stack.read(path);
            if (raw == null) return null;

            return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            // A pack with one unreadable json should cost that one block its texture, not the whole bake.
            return null;
        }
    }

    static String blockId(String state) {
        String withoutProperties = state.indexOf('[') < 0 ? state : state.substring(0, state.indexOf('['));
        return stripNamespace(withoutProperties);
    }

    static Map<String, String> properties(String state) {
        int open = state.indexOf('[');
        if (open < 0 || !state.endsWith("]")) return Map.of();

        Map<String, String> properties = new LinkedHashMap<>();
        for (String pair : state.substring(open + 1, state.length() - 1).split(",")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                properties.put(pair.substring(0, split).trim(), pair.substring(split + 1).trim());
            }
        }
        return properties;
    }

    private static String stripNamespace(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    private static float[] triple(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] quad(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }
}
