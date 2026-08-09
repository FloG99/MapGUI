package de.flog99.mapgui.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Keeps the part of a client jar a camera needs and throws the rest away: 39 MB in, about 3.6 MB out.
 *
 * <p>A zip rather than a directory, because the subset is 9802 small files - 38 MB of 4 KB clusters loose against
 * 3649 KB repacked, and readable by {@link AssetPack} with no second code path.
 *
 * <p>The json stays rather than being baked away, so a resource pack that overrides a model still has the vanilla
 * json underneath to resolve its {@code parent} against.
 */
final class AssetRepack {

    /**
     * What a camera reads. {@code models/block} is self-contained: the deepest vanilla parent chain ends at
     * {@code block/block}, which is inside it. The {@code .mcmeta} files come along with the textures, which is what
     * makes an animated texture a strip of frames rather than a very tall block.
     */
    private static final List<String> KEEP = List.of(
            AssetStack.BLOCKSTATES,
            AssetStack.BLOCK_MODELS,
            AssetStack.BLOCK_TEXTURES,
            AssetStack.ENTITY_TEXTURES,
            AssetStack.EQUIPMENT,
            AssetStack.ITEM_TEXTURES,
            AssetStack.ITEM_MODELS,
            AssetStack.ITEM_DEFINITIONS,
            AssetStack.ENVIRONMENT_TEXTURES,
            AssetStack.PAINTING_TEXTURES,
            AssetStack.COLORMAPS,
            AssetStack.BIOMES
    );

    /** Read for the version check, so it has to survive the repack. */
    static final String VERSION_FILE = "version.json";

    /** Names which of these subsets a cached zip is, so an older one can be told apart from a current one. */
    static final String SUBSET_FILE = "mapgui-subset";

    /**
     * The entity geometry, baked out of the jar's own model classes and kept beside the textures rather than
     * generated into MapGUI.jar: it is Mojang's geometry, and nothing Mojang-derived is committed to this repository
     * or shipped in the artifact.
     */
    static final String MESH_FILE = "mapgui-meshes.json";

    /**
     * Which texture each of a mob's coats wears, read out of the client's own renderers and kept here for the same
     * reason as {@link #MESH_FILE}: it is Mojang's, and nothing Mojang-derived is committed or shipped.
     */
    static final String COAT_FILE = RendererCoats.FILE;

    /**
     * Bumped whenever {@link #wanted} keeps something new, or either extracted file changes shape.
     *
     * <p>A cached subset is keyed by Minecraft version and nothing else, so without this a server that fetched one
     * before a subtree was added goes on using it forever - and the symptom is a hole in the picture rather than an
     * error.
     *
     * <p><b>Renaming a mesh counts.</b> {@link #MESH_FILE} is keyed by the spec string {@link EntityMeshes} asks
     * for, so changing one - marking it a block entity, say - looks to a cached pack like a mesh that is simply not
     * there, and the mob falls back to its bounding box. Which reads as the model having broken rather than as the
     * cache being old, and costs an afternoon.
     */
    static final int SUBSET_REVISION = 17;

    private AssetRepack() {
    }

    /**
     * Writes the subset of {@code jar} to {@code out}, which must not already exist.
     *
     * @return how many entries were kept
     */
    static int subset(Path jar, Path out) throws IOException {
        int kept = 0;

        try (ZipFile source = new ZipFile(jar.toFile());
             OutputStream file = Files.newOutputStream(out);
             ZipOutputStream target = new ZipOutputStream(file)) {

            target.setLevel(Deflater.BEST_COMPRESSION);

            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !wanted(entry.getName())) {
                    continue;
                }

                // A fresh entry rather than the original: copying one carries its compressed size and CRC
                // across, which then has to agree with what this stream actually writes.
                target.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = source.getInputStream(entry)) {
                    in.transferTo(target);
                }
                target.closeEntry();
                kept++;
            }

            boolean client = source.getEntry(MeshExtractor.MODEL_CLASS) != null;

            byte[] meshes = client ? meshes(jar) : null;
            if (meshes != null) {
                target.putNextEntry(new ZipEntry(MESH_FILE));
                target.write(meshes);
                target.closeEntry();
                kept++;
            }

            byte[] coats = client ? coats(jar) : null;
            if (coats != null) {
                target.putNextEntry(new ZipEntry(COAT_FILE));
                target.write(coats);
                target.closeEntry();
                kept++;
            }

            target.putNextEntry(new ZipEntry(SUBSET_FILE));
            target.write(Integer.toString(SUBSET_REVISION).getBytes(StandardCharsets.UTF_8));
            target.closeEntry();
        }

        return kept;
    }

    /**
     * The entity meshes, or null if this jar will not give them up.
     *
     * <p>Never fatal. Extraction runs the client's own mesh builders, so it depends on the server carrying matching
     * versions of a dozen shared libraries - a skew loses the mob shapes and keeps everything else.
     */
    private static byte[] meshes(Path jar) {
        try {
            var extracted = MeshExtractor.extract(jar, AssetRepack.class.getClassLoader(), EntityMeshes.specs());
            return extracted.isEmpty() ? null : MeshCodec.write(extracted);
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * The coat tables, or null if this jar will not give them up.
     *
     * <p>Never fatal, for the same reason as the meshes: these come out of the client's renderers, which need
     * Minecraft's shared libraries to link. A skew loses the odd coats and leaves every mob on the name rule.
     */
    private static byte[] coats(Path jar) {
        try {
            var extracted = RendererCoats.extract(jar, AssetRepack.class.getClassLoader(), EntityMeshes.types());
            return extracted.isEmpty() ? null : RendererCoats.write(extracted);
        } catch (IOException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether a cached zip was packed by this version of the above, and so holds everything now read. */
    static boolean isCurrent(Path zip) {
        try (ZipFile packed = new ZipFile(zip.toFile())) {
            ZipEntry stamp = packed.getEntry(SUBSET_FILE);
            if (stamp == null) return false;

            try (InputStream in = packed.getInputStream(stamp)) {
                return Integer.parseInt(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim()) == SUBSET_REVISION;
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable, truncated, or stamped with something that is not a number: treat as stale and refetch.
            return false;
        }
    }

    private static boolean wanted(String path) {
        if (path.equals(VERSION_FILE)) return true;
        if (isVariantRegistry(path)) return true;

        return KEEP.stream().anyMatch(path::startsWith);
    }

    /**
     * The mob variant registries - {@code cat_variant}, {@code wolf_variant} and the rest - matched on the suffix so
     * that a version adding another one is kept without this having to hear about it.
     */
    private static boolean isVariantRegistry(String path) {
        return path.startsWith(EntityVariants.REGISTRIES) && path.contains(EntityVariants.REGISTRY);
    }
}
