package de.flog99.mapgui.render;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds the zips the asset code reads, so the tests need neither a client jar nor a network. */
final class Zips {

    /** Everything {@link AssetStack#isComplete} probes for, so a pack built with this can be a base. */
    static Map<String, String> completeBase(String version) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("version.json", "{\"id\": \"" + version + "\"}");
        entries.put(AssetStack.BLOCK_TEXTURES + "stone.png", "stone-png");
        entries.put(AssetStack.BLOCK_TEXTURES + "dirt.png", "dirt-png");
        entries.put(AssetStack.BLOCKSTATES + "stone.json", "{}");
        entries.put(AssetStack.BLOCK_MODELS + "cube_all.json", "{}");
        return entries;
    }

    /**
     * A base as it would sit in MapGUI's own cache, stamped with the subset it was packed as.
     *
     * <p>The stamp is what tells a current cached copy from one packed before MapGUI started reading something
     * new, so a zip written without it is correctly treated as stale rather than loaded.
     */
    static Map<String, String> cachedBase(String version) {
        Map<String, String> entries = completeBase(version);
        entries.put(AssetRepack.SUBSET_FILE, Integer.toString(AssetRepack.SUBSET_REVISION));
        return entries;
    }

    static Path write(Path path, Map<String, String> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream file = Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(file)) {

            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    static String text(byte[] raw) {
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    private Zips() {
    }
}
