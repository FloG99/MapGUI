package de.flog99.mapgui.plugin.video;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Turns a <i>page</i> url - a YouTube or Twitch link - into the media url behind it, by asking yt-dlp.
 *
 * <p>That is the whole gap this fills. {@link FfmpegSource} has always opened a url as readily as a file, so an
 * HLS playlist or a signed mp4 url plays today; what it cannot do is read a web page and work out which of those
 * a link stands for.
 *
 * <p><b>A resolved url expires, and that is the part that is easy to get wrong.</b> YouTube signs its media urls
 * and they lapse within hours, so a wall left up overnight stops unless something re-resolves it. {@link Resolved}
 * therefore carries an expiry rather than just a url, and {@link ResolvingSource} reconnects <i>before</i> it
 * lapses rather than after the stream has died. Nothing about that is visible in a short test, which is exactly
 * why it is built deliberately.
 */
public final class StreamResolver {

    /**
     * How long a resolved url is assumed good for when it does not say.
     *
     * <p>Short on purpose. Re-resolving costs one short-lived process; guessing too long costs a wall that goes
     * black in the middle of the evening and stays black.
     */
    private static final Duration DEFAULT_LEASE = Duration.ofMinutes(30);

    /** Beyond this, an {@code expire=} in a url is somebody else's number rather than an expiry. */
    private static final Duration LONGEST_LEASE = Duration.ofDays(400);

    /** How long yt-dlp gets. It runs a challenge script and talks to the network, so this is generous. */
    private static final long TIMEOUT_SECONDS = 90;

    /**
     * What to ask for: one stream, already muxed, that a wall can play.
     *
     * <p>{@code b} is the best pre-muxed format and {@code bv*} the best video-only one, in that order, because a
     * separate audio track would be a second url and a map has no sound. Resolutions above 1080 are declined -
     * a wall is a couple of hundred pixels across, and 4K would be decoded and thrown away.
     */
    private static final String FORMAT = "b[height<=?1080]/b/bv*[height<=?1080]/bv*";

    /** A path ending in one of these is already media, so nothing has to be read to find that out. */
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".flv", ".ts", ".m3u8", ".mpd", ".ogv");

    private final Toolchain tools;
    private final Logger log;
    private final boolean enabled;

    /**
     * @param enabled what {@code media.resolve-page-urls} says. Off, nothing here runs and nothing is fetched
     */
    public StreamResolver(Toolchain tools, Logger log, boolean enabled) {
        this.tools = tools;
        this.log = log;
        this.enabled = enabled;
    }

    /**
     * A media url and when it stops working.
     *
     * @param url       what to hand FFmpeg
     * @param expiresAt when the url lapses. Never null: a url that does not say gets {@link #DEFAULT_LEASE},
     *                  because assuming a signed url is permanent is how a wall goes black overnight
     */
    public record Resolved(String url, Instant expiresAt) {
    }

    /**
     * Whether a page url stands any chance here, for a caller that wants to say why before trying.
     *
     * <p>Never blocks, so it is safe on the main thread - which is why it is optimistic rather than certain
     * before startup has warmed the toolchain: it answers "this is turned on and nothing has failed yet", and
     * the honest answer arrives once {@link Toolchain#warm()} has run.
     */
    public boolean available() {
        return enabled && !tools.ytdlpMissing();
    }

    /**
     * Whether {@code source} is a page rather than media.
     *
     * <p>Deliberately generous: anything http(s) that does not end in a media extension is offered to yt-dlp,
     * which handles a direct url by handing it straight back. Being wrong here therefore costs one short-lived
     * process rather than playback - and {@link ResolvingSource} falls back to the url as given if the resolve
     * fails, so a misjudgement cannot stop something that would have played.
     *
     * <p>A file path, an rtsp or rtmp feed and a udp stream are never pages.
     */
    public static boolean isPageUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;

        int query = lower.indexOf('?');
        String path = query < 0 ? lower : lower.substring(0, query);
        for (String extension : MEDIA_EXTENSIONS) {
            if (path.endsWith(extension)) return false;
        }
        return true;
    }

    /**
     * Asks yt-dlp what is behind {@code pageUrl}. Blocks - it starts a process - so never on the main thread.
     *
     * @throws IOException with a reason a caller can pass on: not turned on, no yt-dlp, or whatever yt-dlp said.
     *                     Its own message is included, because "Sign in to confirm you are not a bot" and "video
     *                     unavailable" are different problems with the same symptom
     */
    public Resolved resolve(String pageUrl) throws IOException {
        if (!enabled) {
            throw new IOException("page urls are not resolved on this server - media.resolve-page-urls is off in"
                    + " config.yml. A direct media url, a file or an rtsp stream needs none of this.");
        }

        String ytdlp = tools.ytdlp();
        if (ytdlp == null) {
            throw new IOException("there is no yt-dlp to resolve it with - see the warning logged at startup."
                    + " Direct media urls, files and rtsp streams are unaffected.");
        }

        List<String> command = new ArrayList<>();
        command.add(ytdlp);
        command.addAll(JsRuntime.ytdlpArgs(tools));
        command.addAll(List.of(
                // A playlist url would otherwise resolve every video in it, and a wall wants one.
                "--no-playlist",
                "--no-progress",
                "--format", FORMAT,
                // Print the url and nothing else. -j would carry an expiry field, but only sometimes, and the
                // signed url itself says so more reliably - see expiryOf.
                "--get-url",
                pageUrl));

        String url = run(command);
        Resolved resolved = new Resolved(url, expiryOf(url, Instant.now()));
        // One line per resolve, which is once per stream start and once per refresh after that. Worth it: the
        // first question about a stream that stopped overnight is when its url was due to lapse.
        log.info("Resolved " + pageUrl + " to a media url good until " + resolved.expiresAt() + ".");
        return resolved;
    }

    /**
     * Runs yt-dlp and returns the first url it printed.
     *
     * <p>No shell, and <b>both pipes drained on threads of their own</b> - not just stderr. A traceback long
     * enough to fill a pipe buffer would deadlock a process whose other pipe nobody is reading, and reading
     * either one on this thread would put a blocking read in front of the timeout: an unbounded wait for EOF
     * cannot be interrupted, and a yt-dlp stalled on a socket never reaches it. The wait has to be
     * {@link Process#waitFor(long, TimeUnit)}, and nothing may block before it.
     */
    private String run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).start();
        try {
            StringBuilder output = new StringBuilder();
            StringBuilder errors = new StringBuilder();
            Thread stdout = drain(process.getInputStream(), output);
            Thread stderr = drain(process.getErrorStream(), errors);

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("yt-dlp did not answer within " + TIMEOUT_SECONDS + " seconds");
            }
            // After waitFor, so neither join can outlast the process, and both writes are visible here. Killed
            // above, the pipes are closed by the kill, so these return rather than hanging on a dead process.
            stdout.join();
            stderr.join();

            String url = firstUrl(output.toString());
            if (process.exitValue() != 0 || url == null) {
                String err = errors.toString();
                throw new IOException("yt-dlp could not resolve it: " + (err.isBlank() ? "no reason given" : summarise(err)));
            }
            return url;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while resolving");
        } finally {
            process.destroy();
        }
    }

    /** Reads one of the process's pipes to its end, on a thread, so nothing blocks the timeout. */
    private static Thread drain(InputStream pipe, StringBuilder into) {
        return Thread.ofVirtual().start(() -> {
            try {
                into.append(read(pipe));
            } catch (IOException ignored) {
                // Nothing to say about a pipe that closed early; the exit code is what decides.
            }
        });
    }

    @Nullable
    private static String firstUrl(String out) {
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return null;
    }

    /**
     * yt-dlp's complaint, shortened to something that fits in a log line and still identifies the problem.
     *
     * <p>The last line rather than the first: a traceback ends with what went wrong, and the extractor's own
     * message - the bot check, the age gate, the region block - is the last thing printed.
     */
    private static String summarise(String err) {
        String last = "";
        for (String line : err.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) last = trimmed;
        }
        return last.length() > 300 ? last.substring(0, 300) + "..." : last;
    }

    private static String read(InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                text.append(line).append('\n');
            }
            return text.toString();
        }
    }

    /**
     * When a media url stops working, read off the url itself.
     *
     * <p>A signed url carries its own deadline: YouTube's {@code expire=} in seconds since the epoch, and a
     * CDN's {@code Expires=} the same way. Reading it beats guessing, and beats yt-dlp's own metadata, which
     * reports an expiry only for some extractors.
     *
     * <p>Anything unreadable, in the past, or absurdly far away - a number that is plainly not an expiry - falls
     * back to {@link #DEFAULT_LEASE}. Which is the safe direction: re-resolving early costs one process.
     */
    static Instant expiryOf(String url, Instant now) {
        for (String key : List.of("expire=", "Expires=", "expires=")) {
            int at = url.indexOf(key);
            if (at < 0) continue;

            int end = at + key.length();
            int stop = end;
            while (stop < url.length() && Character.isDigit(url.charAt(stop))) stop++;
            if (stop == end) continue;

            try {
                Instant expires = Instant.ofEpochSecond(Long.parseLong(url.substring(end, stop)));
                if (expires.isAfter(now) && expires.isBefore(now.plus(LONGEST_LEASE))) return expires;
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Not a timestamp after all, which is what the default lease is for.
            }
        }
        return now.plus(DEFAULT_LEASE);
    }
}
