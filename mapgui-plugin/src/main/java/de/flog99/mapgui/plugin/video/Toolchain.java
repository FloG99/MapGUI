package de.flog99.mapgui.plugin.video;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The two programs MapGUI runs as child processes to turn a <i>page</i> url into a media url: yt-dlp, and the
 * JavaScript runtime yt-dlp needs. Each one MapGUI's own copy, kept in {@code plugins/MapGUI/tools/} and never
 * the machine's.
 *
 * <p><b>The decoder is not here, and that is the difference from every other plugin that does this.</b> MapGUI
 * decodes in-process with JavaCV, fetched through Paper's own library loader by {@link VideoLibraryLoader} and
 * pinned to {@link VideoNatives#JAVACV_VERSION} because the Java bindings and the natives are one release and
 * cannot be mixed. So nothing here decodes anything. yt-dlp is asked one question - what is the media url behind
 * this page - and the answer goes to {@link FfmpegSource}, which has always been able to play a url.
 *
 * <p><b>PATH is never searched, and nothing is installed onto the machine.</b> A deliberate trade with a real
 * cost - a machine with a perfectly good yt-dlp downloads a second one - bought for two things:
 *
 * <ul>
 *   <li><b>A yt-dlp on PATH rots at YouTube's pace</b> rather than anybody's schedule, and cannot be repaired
 *       from here at all: {@code -U} refuses on a pip install and says so. The 403 that comes back says nothing
 *       about the version behind it.
 *   <li><b>Reproducibility.</b> Which of four JavaScript runtimes solved a challenge would otherwise be a
 *       property of the host rather than of this plugin, so two servers seeing different 403 behaviour would
 *       have an extra axis to differ on before anything could be reproduced.
 * </ul>
 *
 * <p>Speed was never the argument either way: the challenge runs once per stream start, and resolution is
 * network-bound. Everything lives under {@code tools/} and leaves with the plugin.
 *
 * <p>What is verified: the runtime is pinned to a release and a SHA-256 per platform in
 * {@code media-tools.properties}. <b>yt-dlp deliberately is not pinned</b> - YouTube breaks old releases within
 * weeks, and a version frozen into a plugin jar is a 403 with a delay fuse. The newest release is resolved at
 * fetch time, checked against the {@code SHA2-256SUMS} published beside it, and a copy over a week old updates
 * itself through {@code yt-dlp -U}. The trust anchor there is HTTPS to github.com rather than a pinned hash; a
 * hash pinned to a version that must not be pinned would be honest about nothing.
 *
 * <p><b>Failure is graded rather than uniform.</b> None of it is fatal: a missing yt-dlp costs page urls only,
 * and files, direct media urls and rtsp streams are all unaffected. A missing JavaScript runtime is reported
 * loudly and then the resolve is attempted anyway, because plenty of videos need no signature solving and
 * refusing them all to be consistent about a runtime would take away playback that works.
 *
 * <p>Resolution blocks - it starts processes and may download - so every call has to be off the main thread.
 * {@link #warm()} exists so the cost and the line saying what was obtained land at startup rather than inside
 * somebody's first play.
 */
public final class Toolchain {

    private static final String YTDLP_LATEST = "https://github.com/yt-dlp/yt-dlp/releases/latest";
    private static final String YTDLP_RELEASES = "https://github.com/yt-dlp/yt-dlp/releases/download/";
    private static final String QJS_RELEASES = "https://github.com/quickjs-ng/quickjs/releases/download/";

    /** Which yt-dlp each platform gets. macOS is universal; the musl builds are picked in {@link #ytdlpAsset}. */
    private static final Map<String, String> YTDLP_ASSETS = Map.of(
            "windows-x86_64", "yt-dlp.exe",
            "windows-x86", "yt-dlp_x86.exe",
            "linux-x86_64", "yt-dlp_linux",
            "linux-aarch64", "yt-dlp_linux_aarch64",
            "linux-armv7", "yt-dlp_linux_armv7l.zip",
            "darwin-x86_64", "yt-dlp_macos",
            "darwin-arm64", "yt-dlp_macos");

    /** Which quickjs-ng build each platform gets. Two megabytes, and one more platform than anything else here. */
    private static final Map<String, String> QJS_ASSETS = Map.of(
            "windows-x86_64", "qjs-windows-x86_64.exe",
            "windows-x86", "qjs-windows-x86.exe",
            "linux-x86_64", "qjs-linux-x86_64",
            "linux-aarch64", "qjs-linux-aarch64",
            "linux-armv7", "qjs-linux-armv7",
            "linux-riscv64", "qjs-linux-riscv64",
            "linux-x86", "qjs-linux-x86",
            "darwin-arm64", "qjs-darwin-arm64",
            "darwin-x86_64", "qjs-darwin-x86_64");

    /** How stale a fetched yt-dlp may get before it updates itself. YouTube's own pace decides this. */
    private static final Duration YTDLP_MAX_AGE = Duration.ofDays(7);

    private static Properties pins;

    private final Path dir;
    private final Logger log;

    @Nullable
    private String ytdlp;
    private boolean ytdlpResolved;

    /**
     * Whether resolving has been tried and come back with nothing.
     *
     * <p>Volatile and read without the lock on purpose: {@link #ytdlp()} holds the monitor for as long as a
     * download takes, and {@link StreamResolver#available()} is answered on the main thread. False until a
     * resolve has actually failed, so it is optimistic before startup has warmed anything - which is the right
     * way round for a question whose caller is deciding whether to explain a limitation to a player.
     */
    private volatile boolean ytdlpMissing;

    @Nullable
    private Path qjs;
    private boolean qjsResolved;

    /**
     * @param dataDir the plugin's folder; everything this runs lands in {@code tools/} inside it
     */
    public Toolchain(Path dataDir, Logger log) {
        this.dir = dataDir.resolve("tools");
        this.log = log;
    }

    /**
     * The yt-dlp to run, or null when there is none. Only a page url ever needs it.
     *
     * <p>Settled once per {@link Toolchain}, <b>failures included</b>. Without that, a server that cannot reach
     * github.com would attempt the download on every play, which is slow, futile and rude to a mirror. A reload
     * builds a new Toolchain, and that is how a network somebody has since fixed gets noticed.
     *
     * <p>Blocks on the first call, which may be a download - never call it on the main thread.
     */
    @Nullable
    public synchronized String ytdlp() {
        if (!ytdlpResolved) {
            ytdlpResolved = true;
            ytdlp = resolveYtdlp();
            ytdlpMissing = ytdlp == null;
        }
        return ytdlp;
    }

    /** Whether a resolve is known to be impossible. Never blocks, so it is safe on the main thread. */
    public boolean ytdlpMissing() {
        return ytdlpMissing;
    }

    /**
     * A quickjs binary to hand yt-dlp, or null when there is none to be had. {@link JsRuntime} is where what it
     * is for is written down.
     *
     * <p>Verified by hash on every start rather than by a versioned filename: it is two megabytes, so re-reading
     * it costs nothing and a release bump then replaces it by itself.
     */
    @Nullable
    public synchronized Path jsRuntime() {
        if (!qjsResolved) {
            qjsResolved = true;
            qjs = resolveJsRuntime();
        }
        return qjs;
    }

    /**
     * Where yt-dlp may keep its own cache: signature functions, the EJS solver scripts
     * {@code --remote-components} fetches, whatever a future extractor decides to remember.
     *
     * <p>Inside the plugin's folder rather than yt-dlp's default of {@code ${XDG_CACHE_HOME}/yt-dlp}, which is
     * the server user's home directory. {@link JsRuntime#ytdlpArgs} is what passes it.
     */
    public Path cacheDir() {
        return dir.resolve("cache");
    }

    /**
     * Resolves both now, and says what was obtained.
     *
     * <p>One line each, deliberately: the first question about a 403 is which runtime, if any, the plugin
     * actually got, and the second is which yt-dlp. Neither is answerable from a log that says nothing.
     */
    public void warm() {
        String resolved = ytdlp();
        Path runtime = jsRuntime();
        if (resolved == null) return;

        log.info("Page urls will be resolved with " + resolved + ", JavaScript runtime "
                + (runtime == null ? "NONE - see the warning above" : "quickjs at " + runtime) + ".");
    }

    // ---- yt-dlp ----

    @Nullable
    private String resolveYtdlp() {
        Path local = dir.resolve(exe("yt-dlp"));
        if (Files.isRegularFile(local) && runs(local.toString(), "--version")) {
            refresh(local);
            return local.toString();
        }

        String asset = ytdlpAsset(platform(), musl());
        if (asset == null) {
            log.warning("No yt-dlp is published for " + platform() + ", so page urls (YouTube and the like) will"
                    + " not play. Video files, direct media urls and rtsp streams are unaffected.");
            return null;
        }

        try {
            String release = latestYtdlpRelease();
            log.info("Fetching yt-dlp " + release + " into " + dir + ".");
            String expected = checksumFor(get(URI.create(YTDLP_RELEASES + release + "/SHA2-256SUMS")), asset);
            fetch(URI.create(YTDLP_RELEASES + release + "/" + asset), expected, local, asset.endsWith(".zip"));
            if (!runs(local.toString(), "--version")) {
                throw new IOException("the fetched build would not run - antivirus or a noexec mount is the"
                        + " usual cause");
            }
            return local.toString();
        } catch (IOException | RuntimeException e) {
            log.warning("Could not fetch yt-dlp (" + e.getMessage() + ") - page urls will not play until the"
                    + " server can reach github.com. A yt-dlp installed on this machine is deliberately not"
                    + " used: it rots at YouTube's pace and cannot be repaired from here. Video files and direct"
                    + " media urls still work.");
            return null;
        }
    }

    /**
     * The yt-dlp build for this machine, taking musl into account - a glibc build will not start on Alpine, and
     * that is a failure no code review catches.
     */
    @Nullable
    static String ytdlpAsset(String platform, boolean musl) {
        String asset = YTDLP_ASSETS.get(platform);
        if (asset == null || !musl) return asset;

        return switch (platform) {
            case "linux-x86_64" -> "yt-dlp_musllinux";
            case "linux-aarch64" -> "yt-dlp_musllinux_aarch64";
            default -> asset;
        };
    }

    /**
     * Lets a fetched yt-dlp update itself once it is a week old.
     *
     * <p>Its own {@code -U} rather than another download: it is the mechanism yt-dlp supports, and it costs one
     * process and no bytes when there is nothing new. A stale yt-dlp is not a slightly worse yt-dlp - YouTube
     * rejects old releases outright. The timestamp moves <b>either way</b>, so a failed update retries in a week
     * rather than on every restart.
     */
    private void refresh(Path binary) {
        try {
            FileTime stamp = Files.getLastModifiedTime(binary);
            if (stamp.toInstant().plus(YTDLP_MAX_AGE).isAfter(Instant.now())) return;

            log.info("The fetched yt-dlp is over a week old - asking it to update itself.");
            boolean updated = runs(binary.toString(), "-U");
            Files.setLastModifiedTime(binary, FileTime.from(Instant.now()));
            if (!updated) log.warning("yt-dlp could not update itself - page urls may start failing with 403.");
        } catch (IOException | RuntimeException e) {
            log.warning("Could not update yt-dlp: " + e.getMessage());
        }
    }

    /** The newest release tag, read off the redirect rather than the API - which is rate-limited per IP. */
    private static String latestYtdlpRelease() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(YTDLP_LATEST))
                .timeout(Duration.ofSeconds(30))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient http = client(HttpClient.Redirect.NEVER)) {
            HttpResponse<Void> response = send(http, request, HttpResponse.BodyHandlers.discarding());
            return tagFromLocation(response.headers().firstValue("location").orElse(""));
        }
    }

    /** The tag at the end of a {@code /releases/tag/<tag>} redirect. */
    static String tagFromLocation(String location) throws IOException {
        int slash = location.lastIndexOf('/');
        if (slash < 0 || slash == location.length() - 1) {
            throw new IOException("could not tell which yt-dlp release is current");
        }
        return location.substring(slash + 1);
    }

    /** The line for one asset out of a {@code SHA2-256SUMS} file: {@code <hash>  <name>}. */
    static String checksumFor(String sums, String asset) throws IOException {
        for (String line : sums.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(asset)) return parts[0];
        }
        throw new IOException("no checksum published for " + asset);
    }

    // ---- the JavaScript runtime yt-dlp needs ----

    @Nullable
    private Path resolveJsRuntime() {
        String release = pinned("qjs.release");
        String asset = QJS_ASSETS.get(platform());
        String expected = asset == null ? null : pinned(asset);
        if (release == null || expected == null) {
            log.warning("No JavaScript runtime is published for " + platform() + " - YouTube will answer 403 on"
                    + " any video whose signature needs solving. The resolve is still attempted, because plenty"
                    + " of videos need no solving at all.");
            return null;
        }

        Path local = dir.resolve(asset);
        if (Files.isRegularFile(local) && expected.equalsIgnoreCase(sha256(local))
                && runs(local.toString(), "-e", "0")) {
            return local;
        }

        try {
            log.info("Fetching a JavaScript runtime for yt-dlp into " + dir + " - about 2 MB, once.");
            fetch(URI.create(QJS_RELEASES + release + "/" + asset), expected, local, false);
            // A binary that exists is not the same as one antivirus will let run, which is why this is tested
            // before it is offered rather than after yt-dlp has failed on it.
            if (!runs(local.toString(), "-e", "0")) {
                throw new IOException("it would not run - antivirus or a noexec mount is the usual cause");
            }
            return local;
        } catch (IOException | RuntimeException e) {
            log.warning("Could not fetch a JavaScript runtime (" + e.getMessage() + ") - YouTube will answer 403"
                    + " on any video whose signature needs solving. Installing deno or node does not help,"
                    + " because MapGUI runs only its own copy: let the server reach github.com and restart it."
                    + " Resolving is still attempted, since plenty of videos need no solving.");
            return null;
        }
    }

    // ---- fetching ----

    /**
     * Downloads one file, checks it against {@code expected}, and only then puts it where it belongs.
     *
     * <p>The hash covers <b>what came off the wire</b> rather than what was written: with an archive those
     * differ, and hashing the unpacked bytes would verify the unpacker instead of the download. Which is also
     * why the stream is drained after the unpacker has what it wanted - a zip reader stops at the end of its
     * entry, and a digest over three quarters of a file matches nothing.
     *
     * <p>It lands under a temporary name and is moved into place last, so an interrupted download is never
     * mistaken for an installed tool on the next start.
     *
     * @param unzip whether the asset is a zip around the one binary, which only armv7's is
     */
    private void fetch(URI uri, @Nullable String expected, Path target, boolean unzip) throws IOException {
        if (expected == null || expected.isBlank()) throw new IOException("no hash to check " + uri + " against");
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, target.getFileName().toString(), ".part");
        try {
            MessageDigest digest = digest();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();

            // The client is closed only once the body has been read, since closing it waits for what is in
            // flight - and with a streamed body, that is this download.
            try (HttpClient http = client(HttpClient.Redirect.NORMAL)) {
                HttpResponse<InputStream> response = send(http, request, HttpResponse.BodyHandlers.ofInputStream());

                try (InputStream wire = new DigestInputStream(response.body(), digest)) {
                    InputStream content = unzip ? firstEntry(wire, uri) : wire;
                    Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
                    wire.transferTo(OutputStream.nullOutputStream());
                }
            }

            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("checksum mismatch on " + uri + " (expected " + expected + ", got " + actual
                        + ") - refusing to run it");
            }
            makeExecutable(temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** A zip positioned at the one binary inside it - only armv7's yt-dlp is packed. */
    private static InputStream firstEntry(InputStream wire, URI uri) throws IOException {
        ZipInputStream zip = new ZipInputStream(wire);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (!entry.isDirectory()) return zip;
        }
        throw new IOException("nothing inside " + uri);
    }

    private static String get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        try (HttpClient http = client(HttpClient.Redirect.NORMAL)) {
            return send(http, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        }
    }

    private static <T> HttpResponse<T> send(HttpClient http, HttpRequest request,
                                            HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            HttpResponse<T> response = http.send(request, handler);
            if (response.statusCode() >= 400) {
                throw new IOException(request.uri() + " answered " + response.statusCode());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** A client that honours whatever proxy the JVM was started with - a server behind one is not unusual. */
    private static HttpClient client(HttpClient.Redirect redirects) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(redirects)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    // ---- the housekeeping ----

    /**
     * Whether this command exists and answers.
     *
     * <p>The only test that tells an executable from a file: antivirus, a {@code noexec} mount and a build for
     * the wrong libc all fail here rather than anywhere earlier, which is why nothing fetched is offered before
     * it has run once.
     */
    private static boolean runs(String command, String... args) {
        try {
            String[] line = new String[args.length + 1];
            line[0] = command;
            System.arraycopy(args, 0, line, 1, args.length);

            // No shell, and output discarded: nothing here is interested in what it says, only whether it ran.
            Process process = new ProcessBuilder(line)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void makeExecutable(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException | UncheckedIOException ignored) {
            // Windows, where being a file is all the permission an exe needs.
        }
    }

    private static String exe(String name) {
        return platform().startsWith("windows") ? name + ".exe" : name;
    }

    /** A pinned value from the jar: the quickjs release tag, or one asset's SHA-256. */
    @Nullable
    private static synchronized String pinned(String key) {
        if (pins == null) {
            pins = new Properties();
            try (InputStream in = Toolchain.class.getResourceAsStream("/media-tools.properties")) {
                if (in != null) pins.load(in);
            } catch (IOException ignored) {
                // Left empty, which fetch() reports as "no hash to check against" rather than downloading
                // something unverified and running it.
            }
        }
        return pins.getProperty(key);
    }

    /** A file's SHA-256, or "" if it cannot be read - which no pinned hash matches. */
    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[65536];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            return "";
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /** This machine, as the asset names spell it. A platform this cannot name is one nothing is fetched for. */
    static String platform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        String base = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "darwin"
                : os.contains("linux") ? "linux" : "";
        String cpu = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            // Only on macOS is it spelled arm64, and only because that is how the assets are named.
            case "aarch64", "arm64" -> base.equals("darwin") ? "arm64" : "aarch64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm", "armv7l" -> "armv7";
            case "riscv64" -> "riscv64";
            default -> "";
        };
        return base + "-" + cpu;
    }

    /** Whether this is a musl system - Alpine, and the containers built on it, where a glibc build will not start. */
    private static boolean musl() {
        for (String loader : List.of("/lib/ld-musl-x86_64.so.1", "/lib/ld-musl-aarch64.so.1")) {
            if (Files.exists(Path.of(loader))) return true;
        }
        return false;
    }
}
