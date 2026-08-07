package de.flog99.mapgui.render;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layers of assets, nearest first, with the vanilla base underneath.
 *
 * <p>The client resolves a texture by walking its enabled packs from the top and taking the first that has
 * it, so this does the same: a server that already ships a resource pack gets its own look in a camera
 * frame, and vanilla fills in everything the pack left alone. That behavior is free once lookup is a walk
 * rather than a map, which is the whole reason for the layer list.
 *
 * <p>The base is separate from the overlays rather than just being the last of them, because it is the only
 * one whose version means anything. Resource packs travel across versions and the client only warns about a
 * {@code pack_format} it does not like, so the version check applies to the base alone.
 */
public final class AssetStack implements AutoCloseable {

    /** What an id means when it names no namespace, which is what nearly every id in vanilla's own files does. */
    static final String VANILLA = "minecraft";

    static final String BLOCKSTATES = "assets/minecraft/blockstates/";
    static final String BLOCK_MODELS = "assets/minecraft/models/block/";
    static final String BLOCK_TEXTURES = "assets/minecraft/textures/block/";
    static final String ENTITY_TEXTURES = "assets/minecraft/textures/entity/";

    /**
     * The item sprites, which is what a dropped item is drawn as.
     *
     * <p>Cheap enough not to think twice about - 797 sprites for 156 KB in 26.2 - and there is no substitute:
     * an item's icon is its own 16x16 png and nothing in the block textures resembles it.
     */
    static final String ITEM_TEXTURES = "assets/minecraft/textures/item/";

    /**
     * The item models, read for one thing only: how an item is held.
     *
     * <p>Not needed to draw a dropped item, whose sprite is found at its own path with no json to say so. Needed for
     * a held one, because where an item points in a hand is stated here and nowhere else, and the answers differ
     * enough between an apple, a sword and a bow that no rule over the id gets there. 1272 files for 186 KB in 26.2,
     * nearly all of them four lines long.
     */
    static final String ITEM_MODELS = "assets/minecraft/models/item/";

    /**
     * The item definitions, which say which model each item is drawn from.
     *
     * <p>Needed because that is no longer a name you can work out: a block item has no item model at all - the
     * definition for {@code oak_planks} points straight at {@code block/oak_planks} - so without these every block in a
     * hand falls back to the flat-item pose, half again too big and not turned at all.
     */
    static final String ITEM_DEFINITIONS = "assets/minecraft/items/";

    /** The sun, the moon phases and the cloud sheet, so the sky is drawn with Minecraft's own art. */
    static final String ENVIRONMENT_TEXTURES = "assets/minecraft/textures/environment/";

    /**
     * Which texture each piece of equipment wears, on which layer of which mob.
     *
     * <p>One json per material - {@code iron.json}, {@code saddle.json} - naming a texture per layer type, and the
     * layer type is what says where it goes: {@code humanoid} for a helmet and a chestplate, {@code humanoid_leggings}
     * for the trousers, {@code pig_saddle} and {@code horse_body} for the animals. Forty-six files and a few KB.
     *
     * <p>Kept as json rather than baked into a table here for the same reason the block models are: a resource pack
     * may retexture a material, and the mapping is the pack's to state.
     */
    static final String EQUIPMENT = "assets/minecraft/equipment/";

    /** Grass and foliage color by temperature and downfall - what makes a real biome tint possible. */
    static final String COLORMAPS = "assets/minecraft/textures/colormap/";

    /**
     * The biome definitions, which are shipped with the client even though they are not textures.
     *
     * <p>Worth taking because they carry the two numbers the colormaps are indexed by, temperature and downfall,
     * along with the water color and the handful of biomes that override their grass or foliage outright. Without
     * them a tint has to come from a table somebody typed in; with them it is the same arithmetic the client does.
     */
    static final String BIOMES = "data/minecraft/worldgen/biome/";

    /**
     * Blocks every world has, used to tell a real base from a resource pack that happens to carry a
     * {@code version.json}. A pack that only retextures ores looks like a successful load right up until
     * somebody points the camera at grass, so the cheap probe is worth it.
     */
    private static final List<String> MUST_HAVE = List.of(
            BLOCK_TEXTURES + "stone.png",
            BLOCK_TEXTURES + "dirt.png",
            BLOCKSTATES + "stone.json",
            BLOCK_MODELS + "cube_all.json"
    );

    /**
     * The namespace an id states, or {@code minecraft} for one that states none.
     *
     * <p>Vanilla writes both - {@code block/stone} and {@code minecraft:item/apple} mean the same kind of thing -
     * and a resource pack that adds its own items writes a third, {@code yourpack:item/whatever}. Reading the
     * namespace rather than assuming it is what lets a pack's own items be drawn at all.
     */
    static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? VANILLA : id.substring(0, colon);
    }

    /**
     * An id with a redundant {@code minecraft:} taken off and anything else left alone.
     *
     * <p>For the places that used to strip the namespace outright. Vanilla keeps the one spelling it has always
     * had, so nothing is cached twice under two names for the same file, and a pack's own id survives to be
     * looked up under its own namespace instead of being hunted for in vanilla's.
     */
    static String canonical(String id) {
        return VANILLA.equals(namespaceOf(id)) ? pathOf(id) : id;
    }

    /** The rest of the id, which is its path inside that namespace. */
    static String pathOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /**
     * The file an id names: {@code mapcamera:item/camera} in {@code models} is
     * {@code assets/mapcamera/models/item/camera.json}.
     */
    static String asset(String id, String folder, String extension) {
        return "assets/" + namespaceOf(id) + "/" + folder + "/" + pathOf(id) + extension;
    }

    /**
     * The same path under the namespace of {@code like}, for building one id out of another.
     *
     * <p>Vanilla comes back unqualified, because that is how ids are written everywhere else here and a texture is
     * cached under whatever name asked for it - qualifying only vanilla's would key the atlas twice for one png.
     */
    static String beside(String like, String path) {
        String namespace = namespaceOf(like);
        return VANILLA.equals(namespace) ? path : namespace + ":" + path;
    }

    private final List<AssetPack> overlays;
    private final AssetPack base;
    private final String version;
    private final int meshCount;

    private AssetStack(List<AssetPack> overlays, AssetPack base, String version, int meshCount) {
        this.overlays = List.copyOf(overlays);
        this.base = base;
        this.version = version;
        this.meshCount = meshCount;
    }

    static AssetStack of(List<AssetPack> overlays, AssetPack base, String version) {
        Map<String, List<MeshPart>> meshes = meshes(base);
        EntityMeshes.install(meshes);
        return new AssetStack(overlays, base, version, meshes.size());
    }

    /**
     * How many entity meshes came out of the base, for the one log line that says what got loaded.
     *
     * <p>Worth reporting because zero is a state a server can end up in without anything looking broken: mobs are
     * drawn as bounding boxes, which is a fallback rather than a fault, and the only way to know that is what
     * happened is to be told.
     */
    public int entityMeshCount() {
        return meshCount;
    }

    /**
     * The entity geometry this base carries, or none.
     *
     * <p>Two ways in. A cache MapGUI repacked has the meshes baked into it already; a client jar an admin dropped
     * into {@code assets/} has not, but still carries the model classes and so can be baked from directly - which is
     * what stops "supply the jar yourself" from being the worse option. That baking costs a second or two, and it
     * lands where the layers are read rather than where a capture is taken.
     *
     * <p>Installed here because this is the one place that knows a base has been settled on.
     */
    private static Map<String, List<MeshPart>> meshes(AssetPack base) {
        try {
            byte[] baked = base.read(AssetRepack.MESH_FILE);
            if (baked != null) return MeshCodec.read(baked);

            if (base.has(MeshExtractor.MODEL_CLASS)) {
                return MeshExtractor.extract(base.source(), AssetStack.class.getClassLoader(), EntityMeshes.specs());
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Mob shapes are the only casualty, and a bounding box is what was drawn before them.
        }
        return Map.of();
    }

    /** What the base declares it is, for the check against the running server. */
    public String version() {
        return version;
    }

    /** Whether a pack could serve as the base, as opposed to sitting on top of one. */
    static boolean isComplete(AssetPack pack) {
        return MUST_HAVE.stream().allMatch(pack::has);
    }

    public boolean has(String path) {
        if (base.has(path)) return true;

        return overlays.stream().anyMatch(pack -> pack.has(path));
    }

    /** First layer that has it wins, base last. Null when no layer does. */
    public byte[] read(String path) throws IOException {
        for (AssetPack pack : overlays) {
            byte[] found = pack.read(path);
            if (found != null) return found;
        }
        return base.read(path);
    }

    /**
     * Every path under a prefix across all layers, deduplicated.
     *
     * <p>The union rather than the top layer's view, so a pack adding a blockstate the base has never heard
     * of still gets baked. Reading any of these goes back through {@link #read} and picks the right layer.
     */
    List<String> list(String prefix) {
        Set<String> paths = new LinkedHashSet<>();
        for (AssetPack pack : overlays) {
            paths.addAll(pack.list(prefix));
        }
        paths.addAll(base.list(prefix));
        return new ArrayList<>(paths);
    }

    /** For the ready message, which is the one place a count means anything to a reader. */
    public int blockTextureCount() {
        return (int) list(BLOCK_TEXTURES).stream().filter(path -> path.endsWith(".png")).count();
    }

    /**
     * Layers that opened cleanly and have since failed to read, as {@code name: what went wrong}. Normally empty.
     *
     * <p>Asked after the fact rather than thrown at the time, because a failed read is handled the same way as a
     * missing one all the way up - it has to be, or one absent texture would take a whole capture down. That makes
     * a pack going bad underneath invisible: everything still renders, out of the layers that still work.
     */
    public List<String> damage() {
        List<String> hurt = new ArrayList<>();
        for (AssetPack pack : overlays) {
            if (pack.damage() != null) {
                hurt.add(pack.name() + ": " + pack.damage());
            }
        }
        if (base.damage() != null) {
            hurt.add(base.name() + ": " + base.damage());
        }
        return hurt;
    }

    /** Named layers, base last, for a log line that says what actually got loaded. */
    public List<String> layerNames() {
        List<String> names = new ArrayList<>();
        for (AssetPack pack : overlays) {
            names.add(pack.name());
        }
        names.add(base.name());
        return names;
    }

    @Override
    public void close() {
        for (AssetPack pack : overlays) {
            closeQuietly(pack);
        }
        closeQuietly(base);
    }

    /** A zip that will not close is not something a reload can do anything about. */
    private static void closeQuietly(AssetPack pack) {
        try {
            pack.close();
        } catch (IOException e) {
            // Nothing useful to do with it, and throwing here would strand the other layers open.
        }
    }
}
