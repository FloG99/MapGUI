package de.flog99.mapgui.plugin.video;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * Clips downloaded to be played more than once, kept in {@code plugins/MapGUI/cache/media}.
 *
 * <p>The same shape as {@code ServerPackCache} and {@code AssetCache} in the camera, for the same reasons:
 * download to a {@code .part} file and move it into place last, name the file after a hash so the name is the
 * cache key, and prune oldest-first so a long-lived server does not accumulate forever. The difference is which
 * hash - <b>the url's, not the content's</b>, because the key has to be known before anything is downloaded.
 * Content-addressing by url is also what gives the sharing away for free: one download serves every wall showing
 * it, which matches the one-decode-shared-by-every-wall design the rest of this package already has.
 *
 * <p><b>A button that downloads is a way to fill a disk</b>, which is what the two caps are for. They hold
 * whatever url arrives and whoever asked for it - that is deliberate, because the API accepts any url and an
 * allowlist would protect nobody who could not already open a socket. A refusal names the cap it hit, since "it
 * did not play" is not an answer anybody can act on.
 */
public final class MediaCache {

    /** Generous: this runs off the main thread, and a clip can be large and slowly served. */
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    /** What a url with no recognisable extension is saved as. FFmpeg probes the content, so this is cosmetic. */
    private static final String DEFAULT_EXTENSION = ".media";

    private final Path directory;
    private final long maxFileBytes;
    private final long maxTotalBytes;

    public MediaCache(Path cacheDir, int maxFileMb, int maxTotalMb) {
        this.directory = cacheDir.resolve("media");
        this.maxFileBytes = maxFileMb * 1024L * 1024L;
        this.maxTotalBytes = Math.max(maxFileMb, maxTotalMb) * 1024L * 1024L;
    }

    /**
     * Downloads {@code url} unless it is already here.
     *
     * <p>A cache hit costs one timestamp write: the file's modification time is when it was last <i>used</i>, so
     * pruning takes the clip nobody has shown for longest rather than the one downloaded longest ago.
     *
     * @param key      what identifies this media to the caller, which for a page url is the page url itself
     * @param url       what to actually download, which for a page url is what it resolved to
     * @param progress  0 to 100 as the bytes arrive, on this thread
     * @return the file, ready to decode
     * @throws IOException if it could not be fetched, or if either cap says no. The message says which
     */
    public Path fetch(String key, String url, IntConsumer progress) throws IOException {
        Path target = directory.resolve(nameFor(key, url));
        if (Files.isRegularFile(target)) {
            touch(target);
            progress.accept(100);
            return target;
        }

        Files.createDirectories(directory);
        Path part = Files.createTempFile(directory, "download", ".part");
        try (HttpClient http = client()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                // Closed rather than dropped: an unread streamed body leaves the exchange unfinished, and
                // closing the client waits for every exchange - so this would hang instead of reporting.
                Toolchain.discard(response.body());
                throw new IOException("HTTP " + response.statusCode() + " downloading it");
            }

            long stated = response.headers().firstValueAsLong("content-length").orElse(-1);
            String refusal = refuse(stated);
            if (refusal != null) throw new IOException(refusal);

            // Room made for what it says it is, or for the whole per-file cap when it says nothing - a chunked
            // response has no length, and finding out halfway through would mean deleting somebody's clip for a
            // download that is about to be refused anyway.
            if (!makeRoom(stated > 0 ? stated : maxFileBytes)) {
                throw new IOException("the cache is full and nothing in it could be freed - media.download"
                        + ".max-total-mb is " + (maxTotalBytes / 1024 / 1024) + " MB");
            }

            download(response.body(), part, stated, progress);
            // Atomic, and in the same directory, so a crash cannot leave half a clip under the name of a whole
            // one - which is the mistake that has something else read a truncated file for the rest of the run.
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading");
        } catch (IllegalArgumentException notAUrl) {
            throw new IOException("that is not a url: " + url, notAUrl);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private void download(InputStream body, Path part, long stated, IntConsumer progress) throws IOException {
        long written = 0;
        int reported = -1;
        byte[] buffer = new byte[64 * 1024];

        try (InputStream in = body; OutputStream out = Files.newOutputStream(part)) {
            for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
                written += read;
                // Checked as it arrives as well as up front, because a Content-Length is what a server claims
                // and the bytes are what it sends.
                String refusal = refuse(written);
                if (refusal != null) throw new IOException(refusal);

                out.write(buffer, 0, read);

                if (stated > 0) {
                    int percent = Math.clamp(written * 100 / stated, 0, 100);
                    if (percent != reported) {
                        reported = percent;
                        progress.accept(percent);
                    }
                }
            }
        }
        progress.accept(100);
    }

    /**
     * Whether a download of this size is allowed, and if not, why.
     *
     * <p>Only the per-file cap is asked here, because it is the smaller of the two by construction: the total is
     * raised to meet it, so a file inside the per-file cap can always be held. Whether it will <i>fit</i> beside
     * what is already there is {@link #makeRoom}'s question, and has a different answer and a different message.
     *
     * @param size in bytes, or negative when the far side did not say - which is not a refusal on its own
     * @return the reason to refuse, or null to go ahead
     */
    @Nullable
    String refuse(long size) {
        if (size > maxFileBytes) {
            return "it is over " + (maxFileBytes / 1024 / 1024) + " MB, which is what media.download.max-file-mb"
                    + " allows. Raise it, or play the url as a stream instead of downloading it";
        }
        return null;
    }

    /**
     * Deletes the least recently used clips until {@code room} more bytes would fit inside the budget.
     *
     * <p>Oldest-first by modification time, which {@link #fetch} keeps as the time of last use, so what goes is
     * what nobody has shown for longest rather than what happened to be downloaded first.
     *
     * @return whether there is now room. False means the budget cannot hold this even empty
     */
    boolean makeRoom(long room) throws IOException {
        if (room > maxTotalBytes) return false;
        if (!Files.isDirectory(directory)) return true;

        List<Path> files = files();
        long total = 0;
        for (Path file : files) total += sizeOf(file);

        for (Path oldest : files) {
            if (total + room <= maxTotalBytes) return true;

            long size = sizeOf(oldest);
            if (delete(oldest)) {
                total -= size;
            }
        }
        return total + room <= maxTotalBytes;
    }

    /**
     * Deletes one clip, or says it could not be.
     *
     * <p>Windows refuses to delete a file something has open, and the clip being played is exactly the one a
     * least-recently-used sweep reaches last - so a locked file is skipped rather than thrown, or a wall playing
     * something would make every download fail with an AccessDeniedException instead of a reason.
     */
    private static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException inUse) {
            return false;
        }
    }

    /** Everything in the cache, least recently used first. {@code .part} files belong to a download in flight. */
    private List<Path> files() throws IOException {
        try (Stream<Path> listed = Files.list(directory)) {
            return listed.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".part"))
                    .sorted(Comparator.comparingLong(MediaCache::modifiedAt))
                    .toList();
        }
    }

    /**
     * What one piece of media is stored as: the SHA-256 of the <i>key</i>, plus whatever extension the url had.
     *
     * <p>The extension is cosmetic - FFmpeg reads the content, not the name - and kept only so that somebody
     * looking in the folder can tell an mp4 from a webm. It comes from the url because that is the one that names
     * a format; the hash comes from the key because that is the one that is the same twice.
     */
    static String nameFor(String key, String url) {
        return sha256(key) + extensionOf(url);
    }

    private static String extensionOf(String url) {
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash || dot == path.length() - 1) return DEFAULT_EXTENSION;

        String extension = path.substring(dot).toLowerCase(Locale.ROOT);
        // Anything longer is not an extension, it is a path with a dot in it.
        return extension.length() > 6 || !extension.substring(1).matches("[a-z0-9]+")
                ? DEFAULT_EXTENSION
                : extension;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /** A client that honours whatever proxy the JVM was started with - a server behind one is not unusual. */
    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (IOException ignored) {
            // Only pruning order suffers, and a clip whose timestamp cannot be written is one that is being
            // played anyway.
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

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
