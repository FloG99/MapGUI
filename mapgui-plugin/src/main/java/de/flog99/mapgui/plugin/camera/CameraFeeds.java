package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.Session;
import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraEye;
import de.flog99.mapgui.camera.CameraFeed;
import de.flog99.mapgui.camera.CameraOptions;
import de.flog99.mapgui.camera.CameraShot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Every open live view, fed from one task rather than one each.
 *
 * <p>The loop this replaces was written once per plugin, and each thing it gets right is invisible when got wrong.
 * <b>One capture in flight</b>, so a long tick leaves the next frame late rather than two copies of the world in
 * memory. <b>Nothing while the screen is put away</b>, since a frame nobody sees still copies a few hundred chunk
 * columns. And <b>asking the budget every tick a frame is wanted</b>, because asking is what counts a view as a
 * viewer - so one that stops asking stops taking a share.
 *
 * <p>Owned by the plugin rather than by the camera service, so a reload that rebuilds the service leaves the feeds
 * running: they look the current camera up each tick rather than holding the one they were opened against.
 */
public final class CameraFeeds {

    private final Plugin plugin;
    private final Supplier<Camera> camera;

    /** Looked up late, since the sessions exist after the camera does and a feed pauses while its screen is away. */
    private final Supplier<MapGui> sessions;

    private final List<Feed> open = new ArrayList<>();

    public CameraFeeds(Plugin plugin, Supplier<Camera> camera, Supplier<MapGui> sessions) {
        this.plugin = plugin;
        this.camera = camera;
        this.sessions = sessions;
    }

    public CameraFeed open(Player player, Supplier<CameraOptions> options, Consumer<CameraShot> onFrame) {
        Feed feed = new Feed(player.getUniqueId(), null, options, onFrame);
        open.add(feed);
        return feed;
    }

    /** A view from an eye the caller places rather than out of the viewer's head - a mirror, a camera on a wall. */
    public CameraFeed open(Player viewer, Supplier<CameraEye> eye, Supplier<CameraOptions> options,
                           Consumer<CameraShot> onFrame) {
        Feed feed = new Feed(viewer.getUniqueId(), eye, options, onFrame);
        open.add(feed);
        return feed;
    }

    /**
     * Main thread, once a tick. A feed that says it is finished is forgotten here rather than left to be swept.
     *
     * <p><b>Whoever went longest ago asks first</b>, and that is not a nicety - without it a player with two views open
     * sees only one of them, forever. The budget is divided per player rather than per view, so the first feed to ask
     * inside an interval takes that player's whole allowance and the rest are refused. Asked in a fixed order the same
     * one wins every time and the others never draw a single frame: a mirror made of two panels drew half of itself and
     * left the other half grey, and so did any mirror seen by somebody holding a camera.
     *
     * <p>Ordering by when each feed was last served rather than <b>rotating by tick</b>, which was the obvious fix and
     * does not work. A frame is granted once per interval - every second tick at ten fps - so a pointer that moves once
     * a tick keeps step with it and the same feed is first on every tick that had a frame to give. Serving order has to
     * be driven by what was actually served, not by the clock it was served on. {@code CaptureBudgetTest} holds both
     * halves of that.
     *
     * <p>What this deliberately does not do is give each view a share of its own - see
     * {@link CaptureBudget#readyForFrame}, where being divided per player is the point. Two views get half the rate
     * each, not twice the cost.
     */
    public void tick() {
        // Over a copy, since a consumer asked what to capture is free to open or close a feed while being asked - and
        // this runs inside the server's tick, where a ConcurrentModificationException would take the other per-tick
        // jobs down with it.
        List<Feed> pumping = new ArrayList<>(open);
        // A feed that has never been served sorts first, so a mirror somebody has just walked up to draws at once
        // rather than waiting its turn behind one that is already running.
        pumping.sort(Comparator.comparingLong(feed -> feed.servedAt));

        for (Feed feed : pumping) {
            if (!feed.pump()) {
                open.remove(feed);
            }
        }
    }

    /** Counts frames handed out, only so that feeds can be ordered by which of them went longest ago. */
    private long served;

    /** Closes every feed, for a plugin shutting down. */
    public void closeAll() {
        open.forEach(Feed::close);
        open.clear();
    }

    public int openCount() {
        return open.size();
    }

    /**
     * Whether this player's screen is out of sight, in which case a frame would be drawn for nobody.
     *
     * <p>True of a screen put away in the hotbar or covered by an inventory. A player with no MapGUI session at all
     * is not paused: a feed does not have to be driving a screen, and something else may be using the frames.
     */
    private boolean paused(Player player) {
        MapGui open = sessions.get();
        if (open == null) return false;

        Session session = open.session(player);
        return session != null && session.suspended();
    }

    private final class Feed implements CameraFeed {

        private final UUID player;

        /** Null for a view out of this player's own head, which is what most of them are. */
        private final Supplier<CameraEye> eye;

        private final Supplier<CameraOptions> options;
        private final Consumer<CameraShot> onFrame;

        private boolean closed;
        private boolean inFlight;

        /**
         * When this feed last took a frame, on {@link #served}'s own count, and 0 for one that never has.
         *
         * <p>Only ever compared with another feed's, which is why it counts frames rather than time: what it has to
         * order is who went least recently, and a clock would tie every feed served in the same tick.
         */
        private long servedAt;

        private Feed(UUID player, Supplier<CameraEye> eye, Supplier<CameraOptions> options,
                     Consumer<CameraShot> onFrame) {
            this.player = player;
            this.eye = eye;
            this.options = options;
            this.onFrame = onFrame;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public boolean running() {
            return !closed && Bukkit.getPlayer(player) != null;
        }

        /** False once this feed is finished with and should be dropped from the list. */
        private boolean pump() {
            try {
                return take();
            } catch (RuntimeException e) {
                // A consumer that throws is a bug in that plugin, not a reason for every other feed to stop being
                // ticked - so this one closes and says why, and the loop carries on.
                plugin.getLogger().log(Level.WARNING, "A camera feed threw and has been closed", e);
                closed = true;
                return false;
            }
        }

        private boolean take() {
            if (closed) return false;

            Player holder = Bukkit.getPlayer(player);
            // Logged out. Nothing to hand frames to and nothing to reopen against, so the feed is over.
            if (holder == null) return false;
            if (inFlight || paused(holder)) return true;

            // The eye first, and that order is load-bearing rather than arbitrary: what to capture usually depends on
            // where from. A mirror solves its reflection here and reads the size and field of view off it, so asking
            // for the options first hands it a question it cannot answer yet - and a consumer that says "not now"
            // because it has not been asked the other one is a feed that pauses on its first tick and never resumes.
            CameraEye from = eye == null ? null : eye.get();
            if (eye != null && from == null) return true;

            // Null is the consumer saying "not this tick" - mid-animation, still loading, nothing worth drawing.
            // Both are read before the budget is asked, so a paused view stops counting as a viewer rather than
            // holding a share it is not spending.
            CameraOptions wanted = options.get();
            if (wanted == null) return true;

            if (!camera.get().readyForFrame(holder)) return true;

            inFlight = true;
            // Stamped where the frame is actually granted, so the ordering above follows what was served rather than
            // the tick it happened on - see tick().
            servedAt = ++served;
            Consumer<CameraShot> arrived = shot -> {
                inFlight = false;
                // A failed capture is dropped rather than handed on: every consumer of a live view would have to
                // write the same null check, and none of them can do anything about it.
                if (closed || shot == null) return;

                // Caught here as well as in pump(), because this arrives on a task of its own rather than on the
                // sweep - so without it a throwing consumer would be logged by the scheduler and go on being fed.
                try {
                    onFrame.accept(shot);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "A camera feed's frame handler threw and has been closed", e);
                    closed = true;
                }
            };

            if (from == null) {
                camera.get().capture(holder, wanted, arrived);
            } else {
                camera.get().capture(holder, from, wanted, arrived);
            }
            return true;
        }
    }
}
