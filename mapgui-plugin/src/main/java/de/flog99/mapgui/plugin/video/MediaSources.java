package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.media.Frames;
import de.flog99.mapgui.media.LiveSource;
import de.flog99.mapgui.media.MediaService;
import de.flog99.mapgui.ui.Dither;
import de.flog99.mapgui.ui.Quantizer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

/**
 * {@link MediaService}: everything that turns a url into pixels, and the only place a page url, the download
 * cache and the decoder are held together.
 *
 * <p>Both doors go through here - {@code /mapgui wall place <name>} for a stream named in config.yml, and a
 * plugin's own url through the API - so there is one lifecycle to get right rather than two, and a named stream
 * is not a different kind of thing from a url somebody typed.
 *
 * <p><b>Config gates capability, not content.</b> Whether FFmpeg is fetched at all, whether page urls may be
 * resolved, and how much a download may weigh are the server owner's decisions and are enforced here. Which url
 * is played is not one of them: a plugin calling this can already open a socket, so refusing its argument would
 * protect nobody. {@code media.streams} survives as a shortcut for the command, not as an allowlist.
 */
public final class MediaSources implements MediaService {

    private final Logger log;
    private final StreamResolver resolver;
    private final MediaCache cache;

    /** Longest edge to decode at, and the frame rate a wall can show - {@code walls.video-size} and {@code walls.fps}. */
    private final int size;
    private final int fps;
    private final int maxFrames;

    /** What {@code media.dither} says, used by anything that does not name a mode of its own. */
    private final Dither dither;

    /** Bukkit's own pool, so a download is not a thread this plugin has to remember to stop. */
    private final Executor async;

    public MediaSources(Plugin plugin, StreamResolver resolver, MediaCache cache, int size, int fps, int maxFrames,
                        Dither dither) {
        this.log = plugin.getLogger();
        this.resolver = resolver;
        this.cache = cache;
        this.size = size;
        this.fps = fps;
        this.maxFrames = maxFrames;
        this.dither = dither;
        this.async = task -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public LiveSource stream(String source) {
        return stream(source, dither);
    }

    @Override
    public LiveSource stream(String source, Dither mode) {
        return open(source, size, !live(source), mode);
    }

    /**
     * The same, at a size and looping decided by the caller - which the wall path needs and the API does not.
     *
     * @param loop start again at the end. A file wants it, a live stream cannot do it
     */
    public LiveSource open(String source, int decodeSize, boolean loop) {
        return open(source, decodeSize, loop, dither);
    }

    /** The same, dithered how the caller asked rather than how {@code media.dither} says. */
    public LiveSource open(String source, int decodeSize, boolean loop, Dither mode) {
        if (source == null || source.isBlank()) return new FailedSource("there is no source to play");
        if (!VideoNatives.available()) {
            return new FailedSource("FFmpeg is not loaded - set media.ffmpeg: true in config.yml and restart."
                    + " GIFs and the image formats ImageIO reads need none of this.");
        }
        if (StreamResolver.isPageUrl(source) && !resolver.available()) {
            // Said plainly rather than attempted: without a resolver this is a web page being handed to a video
            // decoder, and "invalid data found when processing input" explains nothing to anybody.
            return new FailedSource("that looks like a page url, and this server does not resolve them -"
                    + " media.resolve-page-urls is off in config.yml. A direct media url, a file or an rtsp"
                    + " stream plays without it.");
        }
        return new ResolvingSource(source, decodeSize, decodeSize, loop, quantizer(mode),
                resolver.available() ? resolver : null, log);
    }

    @Override
    public CompletableFuture<Frames> download(String source, IntConsumer progress) {
        return download(source, dither, progress);
    }

    @Override
    public CompletableFuture<Frames> download(String source, Dither mode, IntConsumer progress) {
        if (source == null || source.isBlank()) return failed("there is no source to download");
        if (!VideoNatives.available()) {
            return failed("FFmpeg is not loaded - set media.ffmpeg: true in config.yml and restart");
        }
        if (StreamResolver.isPageUrl(source) && !resolver.available()) {
            // The same refusal open() gives, for the same reason: downloaded without resolving, this is an HTML
            // page handed to a video decoder, and "invalid data found when processing input" explains nothing.
            return failed("that looks like a page url, and this server does not resolve them -"
                    + " media.resolve-page-urls is off in config.yml. A direct media url plays without it.");
        }

        return CompletableFuture.supplyAsync(() -> fetchAndDecode(source, mode, progress), async);
    }

    @Override
    public boolean canResolvePageUrls() {
        return resolver.available();
    }

    /**
     * The whole of a download, on a worker: resolve if it is a page, fetch unless it is cached, then decode.
     *
     * <p>Progress is reported against the transfer up to 90 and the decode after it, the same split
     * {@code AssetCache} uses - at 100 with work still to do, the last stretch reads as stuck.
     *
     * <p>A page url is resolved every time even on a cache hit, which is one short process to find out that
     * nothing has to be downloaded. Worth it: the alternative is remembering that a page url maps to a file,
     * which is a second cache to keep true.
     */
    private Frames fetchAndDecode(String source, Dither mode, IntConsumer progress) {
        try {
            String url = source;
            if (StreamResolver.isPageUrl(source) && resolver.available()) {
                url = resolver.resolve(source).url();
            }

            // Keyed on the page url, downloaded from what it resolved to: a resolved url carries a signature and
            // an expiry, so keying on it would make every call a fresh download of a video already on disk.
            Path file = cache.fetch(source, url, percent -> progress.accept(percent * 90 / 100));
            progress.accept(90);
            Frames frames = FfmpegFrames.clip(file, quantizer(mode), size, fps, maxFrames, percent -> progress.accept(90 + percent / 10));
            progress.accept(100);
            return frames;
        } catch (IOException e) {
            // Wrapped rather than thrown, because supplyAsync only carries an unchecked exception - the future
            // completes exceptionally either way, and the message is what a caller reads.
            throw new CompletionException(new IOException(e.getMessage(), e));
        }
    }

    @Override
    public Dither defaultDither() {
        return dither;
    }

    /**
     * What matches a decoded frame to the palette.
     *
     * <p>Undithered unless something asked otherwise, like every other default. A wall showing a photograph
     * gains from Floyd-Steinberg, but which walls those are is known to the plugin putting them up and to the
     * server owner, not to this.
     */
    private static Quantizer quantizer(Dither mode) {
        return Quantizer.of(MapColors.INSTANCE, mode);
    }

    private static CompletableFuture<Frames> failed(String reason) {
        return CompletableFuture.failedFuture(new IOException(reason));
    }

    /**
     * Whether this is something that cannot be started over, and so must not be looped.
     *
     * <p>Seeking a live stream back to zero fails, which kills the decode - and being wrong the other way costs
     * only a file that stops at its end. A page url is not decided here: it is whatever it resolves to, and a
     * YouTube live stream that will not seek is reconnected by {@link ResolvingSource} rather than looped.
     */
    private static boolean live(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        for (String scheme : new String[] {"rtsp://", "rtmp://", "rtmps://", "udp://", "srt://"}) {
            if (lower.startsWith(scheme)) return true;
        }
        return lower.contains(".m3u8");
    }
}
