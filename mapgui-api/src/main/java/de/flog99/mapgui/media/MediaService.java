package de.flog99.mapgui.media;

import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;
import java.util.function.IntConsumer;

/**
 * Plays a url your plugin was handed: a file, a direct media url, an rtsp camera, or a YouTube or Twitch page.
 *
 * <p>Reached through {@code MapGui.get().media()}. Before this existed, MapGUI's own FFmpeg {@link LiveSource}
 * lived in the plugin and could not be named at compile time, so the only way to reach it was to put a file or a
 * stream in {@code config.yml} - which is no use at all to a {@code /stream <url>} command.
 *
 * <pre>{@code
 * MediaService media = MapGui.get().media();
 *
 * // Live. Starts immediately, and keeps itself connected.
 * LiveSource source = media.stream(url);
 * wall.content(WallContent.live(source));
 *
 * // A clip to show more than once. Downloaded once, then it is a file forever.
 * media.download(url, percent -> bar.set(percent))
 *      .thenAccept(frames -> wall.content(WallContent.video(new VideoPlayer(frames))));
 * }</pre>
 *
 * <p><b>Stream or download is a real choice, not a preference.</b> Live content can only be streamed - there is
 * nothing to download, it is ongoing. A clip you will show more than once wants downloading: once it is local
 * there is no url expiry, no re-resolution, and no dependency on the source being reachable at the moment
 * somebody walks past. Something watched once wants streaming, because it starts immediately.
 *
 * <p><b>Any url is accepted, page urls included.</b> A plugin calling this is code already running on the server
 * - it can read files and open sockets without MapGUI's permission - so refusing its url argument would protect
 * nobody. What that means for a {@code /stream <url>} command your players can run is that <b>gating it by
 * permission is your job</b>: MapGUI holds the caps that stop a download filling a disk, and nothing more.
 *
 * <p><b>Failure is an end with a reason, not an exception.</b> {@link #stream} always returns a source; one that
 * could not be opened is simply not running, with {@link LiveSource#error()} saying why - "FFmpeg is not loaded"
 * is one of those reasons, since a server owner may have turned it off, and that is a decision to respect rather
 * than work around. So handle an end, not a guarantee. {@link #download} reports the same reasons by completing
 * exceptionally.
 *
 * <p><b>Threading.</b> Call these from the main thread. Both return immediately: {@link #stream} does its
 * resolving and connecting on a thread of its own, and {@link #download} is entirely off-thread because a 200 MB
 * download on the main thread would hang the server.
 */
@ApiStatus.Experimental
public interface MediaService {

    /**
     * Opens {@code source} and starts playing it, now.
     *
     * <p>A page url - YouTube, Twitch - is resolved to a media url first, and re-resolved before that url lapses:
     * a signed url is good for hours, not for a night, so a wall left up is reconnected in advance rather than
     * after it has gone black. A stream that drops is reconnected too. None of that is visible to you; the source
     * keeps its last picture while it happens.
     *
     * <p>Whether page urls can be resolved at all is the server owner's decision - see
     * {@link #canResolvePageUrls()}. A file or a stream loops when it ends; a live stream cannot and does not.
     *
     * @param source a file path, a media url, an rtsp/rtmp/hls stream, or a page url
     * @return a source, already opening. Never null, and never throws - a source that cannot be opened is one
     *         that has ended, with the reason in {@link LiveSource#error()}
     */
    LiveSource stream(String source);

    /**
     * Downloads {@code source} once and decodes it into frames, off the main thread.
     *
     * <p>Cached by url, so the second call for the same url writes nothing and returns as fast as it can decode,
     * and every wall showing it shares one file. A server owner caps both the size of one download and the total
     * the cache may hold; past either the future completes exceptionally with a message saying which cap it was.
     * Sampled down to the frame rate a wall can show, since holding more frames than can ever be drawn is only
     * memory.
     *
     * @param progress called with 0 to 100 as the download and decode run. <b>Not on the main thread</b> - hop
     *                 back yourself before touching anything of the server's
     * @return the frames, or a future that completed exceptionally with the reason. Held in memory, so read
     *         {@link Frames} on what that costs before downloading an hour of anything
     */
    CompletableFuture<Frames> download(String source, IntConsumer progress);

    /**
     * Whether a page url stands a chance on this server, so you can say why before trying.
     *
     * <p>False when the server owner has left {@code media.resolve-page-urls} off, and false once fetching yt-dlp
     * has been tried and failed. Direct media urls, files and rtsp streams do not depend on it.
     */
    boolean canResolvePageUrls();
}
