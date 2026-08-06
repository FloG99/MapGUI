package de.flog99.mapgui.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One zip of assets, read on demand.
 *
 * <p>A client jar, a resource pack and MapGUI's own repacked subset are all the same thing here: a zip with
 * {@code assets/minecraft/...} inside it. That is what lets an admin hand over whichever they have without
 * the reader caring, and it is why the repack step produces a zip rather than a directory of loose files -
 * measured on 26.2, the subset is 5968 files against a 4 KB cluster, so 23 MB of disk for 2.8 MB of bytes.
 *
 * <p>Entries are read when asked for rather than up front. The baking pass touches a few thousand of them
 * once and then never comes back, so holding them all in memory would be paying for the whole jar to keep
 * the part that gets baked.
 */
final class AssetPack implements AutoCloseable {

    private final Path source;
    private final ZipFile zip;

    private AssetPack(Path source, ZipFile zip) {
        this.source = source;
        this.zip = zip;
    }

    /**
     * @throws IOException if it is not a readable zip, which is the common way an admin-supplied file is
     *                     wrong - an unzipped folder, or a jar that only half copied
     */
    static AssetPack open(Path source) throws IOException {
        return new AssetPack(source, new ZipFile(source.toFile()));
    }

    Path source() {
        return source;
    }

    /** Named for the file alone, since the full path is noise in a message an admin reads. */
    String name() {
        return source.getFileName().toString();
    }

    boolean has(String path) {
        return zip.getEntry(path) != null;
    }

    /** Null rather than throwing, because "this layer does not have it" is the normal case in a stack. */
    byte[] read(String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) return null;

        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** Every file directly or indirectly under a prefix, directories left out. */
    List<String> list(String prefix) {
        List<String> found = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(prefix)) {
                found.add(entry.getName());
            }
        }
        return found;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
