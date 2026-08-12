package de.flog99.mapgui.render;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
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

/**
 * The resource packs the server hands its players, kept so a capture can be drawn with them.
 *
 * <p>A server that sends its players a pack has already answered the question these assets exist to ask - what
 * does the world look like to the people in it - and answered it in a form that is fetchable. Following that is
 * the difference between a capture matching what a player sees and matching what vanilla would have looked like.
 *
 * <p>Stored under the SHA-1 of the bytes, which makes the file name the cache key: the same pack pushed to a
 * hundred players is downloaded once, a pack that changes lands beside the old one rather than over it, and a
 * restart re-uses everything. That is also why nothing here is ever written twice to the same path, which is the
 * mistake that corrupts a zip somebody else has open.
 */
public final class ServerPackCache {

    /** Generous, because this runs off the main thread and a pack can be large and slowly served. */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * Past this a pack is not something to pull onto a server's disk unasked. Textures and models are a fraction
     * of a big pack - the rest is sounds - and there is no way to ask for only part of a zip over HTTP.
     */
    private static final long MAX_BYTES = 512L * 1024 * 1024;

    /** Enough for a server that has changed its pack a few times. Older ones are nothing but disk. */
    private static final int KEEP = 5;

    private final Path directory;

    /**
     * Swept on the way up, which is the only moment in a run when nothing has these files open.
     *
     * <p>{@link #prune()} otherwise only runs after a write, and by then the stack is holding every pack it
     * layers - so on Windows the stale ones could never be deleted and only stopped being read.
     */
    public ServerPackCache(Path cacheDir) {
        this.directory = cacheDir.resolve("packs");

        try {
            if (Files.isDirectory(directory)) {
                prune();
            }
        } catch (IOException swept) {
            // A cache that cannot be tidied still works. What is past KEEP is not layered either way.
        }
    }

    /**
     * The packs to layer: the newest {@link #KEEP} of them, newest first.
     *
     * <p>Newest first because a pack is named by its own bytes, so a plugin that changes its pack does not replace
     * an entry, it adds one - and every older version of it is still a full pack claiming the same file names.
     * By name, which is by hash, which is at random, one of those stale copies won and captures were drawn with
     * a texture the plugin had already stopped shipping.
     *
     * <p>The count is the same limit {@link #prune()} was written to hold, and holding it here as well is what
     * makes it true. Pruning can only delete a file nothing has open, and every pack in this list is open in the
     * asset stack, so on Windows - where a delete is refused rather than deferred - the limit never applied to
     * anything and one test session left thirty-two of them stacked up.
     */
    public List<Path> stored() {
        if (!Files.isDirectory(directory)) return List.of();

        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(ServerPackCache::modifiedAt).reversed()
                            .thenComparing(path -> path.getFileName().toString()))
                    .limit(KEEP)
                    .toList();
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    public boolean has(String sha1) {
        return sha1 != null && !sha1.isBlank() && Files.isRegularFile(zipFor(sha1));
    }

    /**
     * Downloads a pack and stores it under the SHA-1 of what actually arrived.
     *
     * <p>Checked against the hash the server stated when it stated one, which is the same check the client makes.
     * A mismatch is a failure rather than a warning: the point of following the server's pack is to draw what its
     * players are drawing, and bytes that are not the ones they got are worse than no pack at all.
     *
     * @param hash the SHA-1 the server told clients to expect, or empty if it told them nothing
     * @return the stored zip, which may already have existed
     */
    public Path fetch(String url, String hash) throws IOException {
        if (has(hash)) return zipFor(hash);

        Files.createDirectories(directory);
        Path part = Files.createTempFile(directory, "pack", ".part");
        String actual;
        try {
            actual = download(url, part);

            if (!hash.isBlank() && !actual.equalsIgnoreCase(hash)) {
                throw new IOException("the pack at " + host(url) + " is not the one the server told clients to expect"
                        + " (SHA-1 " + actual + ", expected " + hash + ")");
            }

            Path target = zipFor(actual);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            prune();
            return target;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Keeps a pack somebody handed over directly, rather than one that had to be fetched.
     *
     * <p>Same shelf, same content-addressed name, so it layers and de-duplicates exactly as a downloaded one does
     * and a plugin handing over the same bytes on every start writes nothing after the first.
     *
     * @return the stored zip, its hash, and whether this was the first time these bytes were seen
     */
    public Stored keep(byte[] pack) throws IOException {
        String sha1 = HexFormat.of().formatHex(sha1().digest(pack));
        Path target = zipFor(sha1);
        if (Files.isRegularFile(target) && Files.size(target) == pack.length) {
            return new Stored(target, sha1, false);
        }

        Files.createDirectories(directory);
        Path part = Files.createTempFile(directory, "pack", ".part");
        try {
            Files.write(part, pack);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            prune();
            return new Stored(target, sha1, true);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * @param sha1  of the bytes, in lowercase hex. Handed back out through {@code Camera#useResourcePack} so that a
     *              plugin serving the same pack to clients offers it under the hash MapGUI measured rather than one
     *              it worked out itself
     * @param fresh whether anything was actually written, which is what decides if a reload is worth it
     */
    public record Stored(Path zip, String sha1, boolean fresh) {
    }

    /** @return the SHA-1 of what was written, in lowercase hex */
    private static String download(String url, Path target) throws IOException {
        MessageDigest sha1 = sha1();

        try (HttpClient http = MojangCatalog.client()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + host(url));
            }

            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target)) {

                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_BYTES) {
                        throw new IOException("the pack at " + host(url) + " is over " + (MAX_BYTES / 1024 / 1024) + " MB");
                    }

                    sha1.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading a resource pack", e);
        } catch (IllegalArgumentException notAUrl) {
            throw new IOException("The server sent its players a resource pack at an address that is not a URL: " + url, notAUrl);
        }

        return HexFormat.of().formatHex(sha1.digest());
    }

    private Path zipFor(String sha1) {
        return directory.resolve(sha1.toLowerCase() + ".zip");
    }

    /**
     * The host alone, since a pack URL can carry a token and this goes in a log line.
     *
     * <p>Falls back to the whole thing only when it does not parse, which is the one case where the reader needs
     * to see it to understand the message.
     */
    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException notAUrl) {
            return url;
        }
    }

    /**
     * Newest kept, and a file still open is left where it is rather than fought over.
     *
     * <p>Which on Windows is most of them, since the stack has every pack it layers open. That is why
     * {@link #stored()} holds the same limit itself: a pack this cannot delete stops being layered anyway, and
     * stops being open at the next reload, so the one after this deletes it.
     */
    private void prune() throws IOException {
        List<Path> zips;
        try (var files = Files.list(directory)) {
            zips = files.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(ServerPackCache::modifiedAt).reversed())
                    .toList();
        }

        for (Path stale : zips.subList(Math.min(KEEP, zips.size()), zips.size())) {
            try {
                Files.deleteIfExists(stale);
            } catch (IOException inUse) {
                // Open somewhere, which on Windows is a refusal rather than a delayed delete. Next restart.
            }
        }
    }

    private static long modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every JVM has SHA-1", impossible);
        }
    }
}
