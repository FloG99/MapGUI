package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.LiveSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * A {@link LiveSource} that keeps itself connected: it resolves a page url before playing it, re-resolves before
 * the url lapses, and reconnects a stream that dropped.
 *
 * <p><b>The expiry is the whole point of this class.</b> A YouTube media url is signed and stops working within
 * hours, so a wall left up overnight goes black unless something notices in advance. Noticing afterwards is not
 * good enough - by then the stream is already dead and the wall has already gone. So the refresh happens at
 * {@link #MARGIN} <i>before</i> the deadline, while the old connection is still delivering frames, and the new
 * one is only swapped in once it has produced a picture of its own. Nothing about that is visible in a five
 * minute test, which is why it is built this way deliberately rather than after the first bug report.
 *
 * <p>Everything happens on one supervisor thread. {@link #frame()} never blocks and never waits on a resolve:
 * it hands back the newest picture there is, and during a swap that is the outgoing connection's - so a refresh
 * is invisible on the wall rather than a black flash.
 *
 * <p><b>Failure is reported, not thrown.</b> A url that cannot be resolved falls back to being played as given,
 * because a page-url guess that was wrong should not stop something FFmpeg would have opened. A connection that
 * drops is retried with a widening backoff for as long as it ever worked. One that never worked at all gives up
 * after {@link #ATTEMPTS} tries and leaves the reason in {@link #error()}, which is where a caller reads it -
 * handle an end, not a guarantee.
 */
public final class ResolvingSource implements LiveSource {

    /**
     * How far before a url lapses to reconnect.
     *
     * <p>Two minutes covers a resolve that has to run a signature challenge and a connection that has to buffer,
     * on a link slow enough to make both slow. It is also clamped to a tenth of the lease, so a url that is only
     * good for ten minutes still gets refreshed at nine rather than immediately.
     */
    private static final Duration MARGIN = Duration.ofMinutes(2);

    /** How soon to try again when a refresh could not resolve. The old connection is still playing meanwhile. */
    private static final Duration RETRY = Duration.ofMinutes(1);

    /**
     * The soonest a connection is ever replaced, whatever its url claims.
     *
     * <p>A floor rather than a nicety. An expiry is read off the url and compared against this machine's clock,
     * so a clock running ahead makes every lease look nearly spent - and without a floor that is a yt-dlp process
     * and a fresh connection every few seconds, per wall, for as long as the wall is up. Reconnecting a minute
     * after connecting is already pathological; doing it faster is a fault, not a deadline.
     */
    private static final Duration SOONEST = Duration.ofMinutes(1);

    /** How many times to open something that has never produced a frame before giving up on it. */
    private static final int ATTEMPTS = 5;

    private static final long POLL_MS = 100;
    private static final long MIN_BACKOFF_MS = 2000;
    private static final long MAX_BACKOFF_MS = 60_000;

    private final String source;
    private final boolean loop;

    /**
     * The size being handed out, which has to match the pixels {@link #frame()} returns or they would be read
     * off the end of the array.
     *
     * <p>The box asked for until a connection has produced a picture, and that connection's own size after -
     * it decodes inside the box keeping the source's proportions, so a portrait video is not squashed. Every
     * later connection is then given <i>that</i> as its box, which it already fits exactly, so the size is
     * settled once and a re-resolve cannot change it underneath whoever is painting.
     */
    private volatile int width;
    private volatile int height;

    @Nullable
    private final StreamResolver resolver;
    private final Logger log;
    private final Thread thread;

    /** What is playing now. Replaced whole, so a reader sees one connection or the next and never half of each. */
    @Nullable
    private volatile Attempt current;

    /** The last picture from any connection, so a swap or a stall shows the previous frame rather than nothing. */
    private volatile byte @Nullable [] last;

    private volatile boolean running = true;

    @Nullable
    private volatile String error;

    /**
     * @param source   a file path, a media url, or a page url when {@code resolver} can resolve one
     * @param loop     start again at the end, which is what a file wants and a stream cannot do
     * @param resolver null to play {@code source} exactly as given, which is what a server with
     *                 {@code media.resolve-page-urls} off does
     */
    public ResolvingSource(String source, int width, int height, boolean loop,
                           @Nullable StreamResolver resolver, Logger log) {
        this.source = source;
        this.width = width;
        this.height = height;
        this.loop = loop;
        this.resolver = resolver;
        this.log = log;

        this.thread = new Thread(this::supervise, "MapGUI-media");
        thread.setDaemon(true);
        thread.start();
    }

    /** One connection, and when it has to be replaced. */
    private record Attempt(LiveSource playing, Instant refreshAt) {

        boolean due() {
            return Instant.now().isAfter(refreshAt);
        }

        Attempt laterBy(Duration wait) {
            return new Attempt(playing, Instant.now().plus(wait));
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public byte @Nullable [] frame() {
        Attempt playing = current;
        byte[] latest = playing == null ? null : playing.playing().frame();
        return latest != null ? latest : last;
    }

    @Override
    public boolean running() {
        return running;
    }

    @Override
    @Nullable
    public String error() {
        return error;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }

    private void supervise() {
        long backoff = MIN_BACKOFF_MS;
        int failures = 0;
        try {
            if (!VideoNatives.available()) {
                // Permanent rather than a failure to retry: no download and no restart happens while this runs.
                error = "FFmpeg is not loaded, so nothing but a GIF can be played - set media.ffmpeg: true in"
                        + " config.yml and restart";
                return;
            }

            while (running) {
                Attempt playing = current;

                if (playing == null) {
                    if (failures >= ATTEMPTS) {
                        log.warning("Giving up on " + source + " after " + failures + " attempts that produced no"
                                + " picture: " + error + ".");
                        return;
                    }
                    // Waited before the retry rather than after the failure, so the first attempt is immediate
                    // and only a repeat pays. A server that cannot reach the source is not asked every second.
                    if (failures > 0) {
                        Thread.sleep(backoff);
                        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                    }
                    // Counted from the attempt, not from the failure: FFmpeg opens on its own thread, so an
                    // unplayable url comes back as a connection that starts and then dies rather than as a
                    // refusal here. Anything that produces a picture resets this, so only the never-worked
                    // case runs out of attempts.
                    failures++;
                    playing = open();
                    if (playing == null) continue;

                    current = playing;
                }

                byte[] latest = playing.playing().frame();
                if (latest != null) {
                    adopt(playing.playing());
                    last = latest;
                    // A connection that has delivered a picture has proved the url and the network, so the next
                    // drop is a drop rather than a mistake, and is worth retrying from scratch.
                    failures = 0;
                    backoff = MIN_BACKOFF_MS;
                }

                if (!playing.playing().running()) {
                    if (ended(playing)) return;

                    continue;
                }
                if (playing.due()) {
                    refresh(playing);
                    continue;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Attempt playing = current;
            if (playing != null) playing.playing().close();
            running = false;
        }
    }

    /**
     * Deals with a connection that stopped.
     *
     * @return whether this is the end of it, rather than something to reconnect
     */
    private boolean ended(Attempt playing) {
        String why = playing.playing().error();
        playing.playing().close();
        current = null;

        if (why == null) {
            // A file that reached its end, or a stream the far side closed cleanly. Nothing is wrong, so
            // nothing is retried and the last frame stays up.
            return true;
        }
        error = why;
        log.warning("Playback of " + source + " stopped (" + why + ") - reconnecting.");
        return false;
    }

    /**
     * Replaces the connection before its url lapses, keeping the old one until the new one has a picture.
     *
     * <p>A refresh that cannot resolve leaves the old connection playing and tries again in {@link #RETRY} -
     * which is better than closing something that still works to make room for something that does not.
     */
    private void refresh(Attempt playing) throws InterruptedException {
        Attempt next = open();
        if (next == null) {
            current = playing.laterBy(RETRY);
            return;
        }

        // Waited for here rather than swapped straight in: a connection with no frame yet would put a hole in
        // the wall for however long the new stream takes to buffer.
        try {
            for (int waited = 0; waited < 100 && next.playing().frame() == null && next.playing().running(); waited++) {
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException closing) {
            // Closed while waiting on it, which the supervisor's own finally cannot do: current is still the old
            // connection, so without this the new one's grabber and its thread are simply dropped.
            next.playing().close();
            throw closing;
        }
        byte[] first = next.playing().frame();
        if (first == null) {
            next.playing().close();
            current = playing.laterBy(RETRY);
            log.warning("A refreshed connection to " + source + " produced no picture - keeping the old one for now.");
            return;
        }

        adopt(next.playing());
        last = first;
        current = next;
        playing.playing().close();
    }

    /**
     * Takes on the size a connection is actually decoding at, before any of its pixels are handed out.
     *
     * <p>Only the first one changes anything. After that every connection is opened with this as its box and
     * fits it exactly, which is what keeps the size stable across a re-resolve.
     */
    private void adopt(LiveSource from) {
        if (from.width() > 0 && from.height() > 0) {
            width = from.width();
            height = from.height();
        }
    }

    /**
     * Resolves if it has to, then opens. Null when nothing could be opened, with the reason in {@link #error}.
     *
     * <p>A resolve that fails falls through to the url as given rather than refusing: {@code isPageUrl} is a
     * guess, and being wrong about a direct url should cost a wasted process rather than the playback.
     */
    @Nullable
    private Attempt open() {
        String url = source;
        Instant expires = null;

        if (resolver != null && StreamResolver.isPageUrl(source)) {
            try {
                StreamResolver.Resolved resolved = resolver.resolve(source);
                url = resolved.url();
                expires = resolved.expiresAt();
            } catch (IOException e) {
                error = e.getMessage();
                log.warning("Could not resolve " + source + " (" + e.getMessage() + ") - trying it as a direct url.");
            }
        }

        try {
            return new Attempt(new FfmpegSource(url, width, height, loop), refreshAt(expires));
        } catch (RuntimeException | LinkageError e) {
            // A native library that loaded far enough to be found and not far enough to run. Reported rather
            // than thrown out of a thread nobody is watching.
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return null;
        }
    }

    /**
     * When to reconnect: {@link #MARGIN} before the url lapses, or a tenth of the lease if that is sooner, and
     * never inside {@link #SOONEST}.
     *
     * <p>A url with no expiry is left alone entirely - a file, an rtsp camera or an HLS playlist has no deadline
     * to beat, and reconnecting one for no reason would drop frames on purpose.
     */
    static Instant refreshAt(@Nullable Instant expires) {
        if (expires == null) return Instant.MAX;

        Instant now = Instant.now();
        Duration lease = Duration.between(now, expires);
        Instant floor = now.plus(SOONEST);
        if (lease.isNegative() || lease.isZero()) return floor;

        Duration margin = lease.dividedBy(10).compareTo(MARGIN) < 0 ? lease.dividedBy(10) : MARGIN;
        Instant wanted = expires.minus(margin);
        // A lease this machine's clock thinks is nearly spent is a clock to distrust, not a deadline to chase.
        return wanted.isBefore(floor) ? floor : wanted;
    }
}
