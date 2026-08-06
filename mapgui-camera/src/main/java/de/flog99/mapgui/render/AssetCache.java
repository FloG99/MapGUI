package de.flog99.mapgui.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * MapGUI's own copy of the textures, one repacked zip per Minecraft version.
 *
 * <p>Separate from {@code plugins/MapGUI/assets/} on purpose: this directory is ours to write, prune and
 * replace, and that one is the admin's and is only ever read. A file somebody put there by hand may be
 * pinned deliberately, so nothing here ever touches it.
 *
 * <p>Keyed by version rather than kept as a single current copy, because at 2.9 MB each there is no reason
 * not to - and it makes rolling a server back instant instead of another download.
 */
public final class AssetCache {

    /** Enough that a normal upgrade path never re-downloads, small enough that forty snapshots cannot pile up. */
    private static final int KEEP_VERSIONS = 5;

    private final Path directory;

    public AssetCache(Path directory) {
        this.directory = directory;
    }

    Path zipFor(String minecraftVersion) {
        return directory.resolve(minecraftVersion + ".zip");
    }

    /**
     * Whether this version is cached <i>and</i> holds everything the current renderer reads.
     *
     * <p>A subset that predates a subtree being added is worse than no subset at all: it loads, it reports itself
     * ready, and then the camera quietly draws a checkerboard where the sun should be. So an old stamp counts as
     * not having it, and the ordinary fetch path replaces it.
     */
    boolean has(String minecraftVersion) {
        Path zip = zipFor(minecraftVersion);
        return Files.isRegularFile(zip) && AssetRepack.isCurrent(zip);
    }

    /**
     * Downloads the client jar for a version, checks it, keeps the ~2.9 MB a camera needs and deletes the rest.
     *
     * <p>Progress is reported against the size Mojang states rather than the {@code Content-Length} header, so
     * a truncated transfer shows as stalling short of 100 rather than as a complete download of the wrong
     * thing. The SHA-1 is what actually catches it.
     *
     * @param progress called with 0-100 as the jar comes down, on this thread
     * @return the repacked zip, ready for {@link AssetPack#open}
     */
    public Path fetch(String minecraftVersion, IntConsumer progress) throws IOException {
        MojangCatalog catalog = MojangCatalog.resolve(minecraftVersion);
        Files.createDirectories(directory);

        Path jar = directory.resolve(minecraftVersion + ".jar.part");
        Path packing = directory.resolve(minecraftVersion + ".zip.part");
        try {
            // The repack takes a few seconds with nothing to measure, so the transfer only reaches 90 - at 100
            // the last stretch reads as stuck.
            download(catalog, jar, percent -> progress.accept(percent * 90 / 100));
            progress.accept(92);

            Files.deleteIfExists(packing);
            AssetRepack.subset(jar, packing);
            progress.accept(98);

            // Atomic, so a crash mid-repack cannot leave something that opens as a zip and is missing half
            // the textures. Same directory as the target, so the move stays within one filesystem.
            Path target = zipFor(minecraftVersion);
            Files.move(packing, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            prune();
            return target;
        } finally {
            Files.deleteIfExists(jar);
            Files.deleteIfExists(packing);
        }
    }

    private void download(MojangCatalog catalog, Path target, IntConsumer progress) throws IOException {
        MessageDigest sha1 = sha1();

        try (HttpClient http = MojangCatalog.client()) {
            HttpRequest request = HttpRequest.newBuilder(catalog.clientJar())
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading the client jar");
            }

            long written = 0;
            int reported = -1;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target)) {

                int read;
                while ((read = in.read(buffer)) != -1) {
                    sha1.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    written += read;

                    int percent = catalog.size() > 0 ? Math.clamp(written * 100 / catalog.size(), 0, 100) : 0;
                    if (percent != reported) {
                        reported = percent;
                        progress.accept(percent);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading the client jar", e);
        }

        String actual = HexFormat.of().formatHex(sha1.digest());
        if (!actual.equalsIgnoreCase(catalog.sha1())) {
            throw new IOException("The downloaded client jar does not match Mojang's SHA-1 (expected " + catalog.sha1() + ", got " + actual + ")");
        }
    }

    /** Oldest first, so an upgrade path keeps working and a long-lived server does not accumulate forever. */
    private void prune() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> zips = files
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(AssetCache::modifiedAt).reversed())
                    .toList();

            for (Path stale : zips.subList(Math.min(KEEP_VERSIONS, zips.size()), zips.size())) {
                Files.deleteIfExists(stale);
            }
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            // Treated as oldest, so an unreadable timestamp makes it a pruning candidate rather than a throw.
            return 0;
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required of every JVM", e);
        }
    }
}
