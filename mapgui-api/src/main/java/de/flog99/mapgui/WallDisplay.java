package de.flog99.mapgui;

import de.flog99.mapgui.event.MapGuiClickEvent;
import de.flog99.mapgui.event.MapGuiViewerChangeEvent;
import de.flog99.mapgui.event.MapGuiWallPlaceEvent;
import de.flog99.mapgui.event.MapGuiWallRemoveEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A grid of maps hung on blocks, showing one picture - a video, or a menu you can use.
 *
 * <p>A wall is either <b>shared</b>, with one surface and one screen for everybody, or <b>per player</b>,
 * where every viewer gets their own of each. That difference is only how many {@link WallView}s exist.
 *
 * <p>Cursors are per viewer either way, since they are map markers rather than pixels.
 *
 * <p><b>Full-frame video on a wall is expensive.</b> Every map is 16 KB a frame, so a 2x2 at 10 fps is
 * 640 KB/s - 5.2 Mbit/s - <i>per viewer</i>, and a 6x6 is nine times that. That is per viewer in both modes,
 * since a wall is sent to each client separately either way. Three things hold it down: an empty room costs
 * nothing, a wall sends no pixels to anyone who has turned away from it (see {@link Builder#cullOffScreen}),
 * and one that mostly sits still pays only for the part that moves. Painting is not among them - a wall with
 * an audience is drawn every frame whether or not any of them is looking at it. Per player costs a paint pass
 * and a surface pair each on top, so it suits something walked up to rather than something a crowd gathers
 * round.
 */
public final class WallDisplay {

    /**
     * The most steps {@link Builder#prerender} will take, so a caller can tell in advance whether an animation
     * is short enough to be worth sending once rather than streaming.
     */
    public static final int MAX_PRERENDER_STEPS = WallLoop.MAX_STEPS;

    /** How far a viewer can stand and still point at a wall, unless {@link Builder#reach} says otherwise. */
    public static final double DEFAULT_REACH = 64;

    private final WallServices services;
    private final World world;
    private final WallLayout layout;
    private final WallTiles tiles;
    private final WallCursors cursors;
    private final Consumer<WallDisplay> onClose;

    /** Set when every viewer draws their own, which is what makes a wall per-player. */
    @Nullable
    private final Function<Player, Screen> screenPerPlayer;

    /** The one view of a shared wall, or null when every viewer has their own. */
    @Nullable
    private final WallView shared;
    private final Map<UUID, WallView> owned = new HashMap<>();

    private final Set<UUID> viewers = new HashSet<>();
    private final Location center;
    private final boolean interactive;

    /**
     * What each viewer's screen last said it would take, for {@link #wouldTake}.
     *
     * <p>Concurrent for the same reason {@link WallCursors}' own map is: whether a click belongs to this wall has to
     * be answered before the packet is passed on, which happens on the network thread.
     */
    private final Map<UUID, Click> taking = new ConcurrentHashMap<>();

    /** Who is shown the wall at all, and who may work it - see {@link Builder#visibleTo} and {@link Builder#controlledBy}. */
    private final Predicate<Player> visibleTo;
    private final Predicate<Player> controlledBy;

    /** Painted over whatever the wall shows, for every viewer. Null unless one was asked for. */
    @Nullable
    private final WallContent overlay;

    /** Set when the wall was asked to prerender and the transport can repoint its maps. */
    @Nullable
    private final WallLoop loop;

    /**
     * Set when this wall shares its picture with others - see {@link Builder#channel}.
     *
     * <p>One member of a channel paints and sends; the rest hang their frames on the same map ids and do
     * neither. So a second wall showing the same clip costs a mount packet and nothing per frame.
     */
    @Nullable
    private final WallChannel channel;

    /** Set unless this wall streams to everyone in range regardless - see {@link Builder#cullOffScreen}. */
    @Nullable
    private final WallSight sight;

    /**
     * Viewers who need the whole picture rather than what changed since the last one - because they have just
     * arrived, or because something changed while they were looking away and were being sent nothing.
     *
     * <p>Nobody joins it for merely looking away. A still wall, or a menu nobody touched, leaves an absent
     * viewer with nothing to catch up on however long they were gone, which is what keeps the cull from ever
     * costing more than it saves.
     */
    private final Set<UUID> behind = new HashSet<>();

    private int rangeSquared;
    private int intervalMs;
    private boolean previewOnly;

    /**
     * Checked by everything that could otherwise build a view again.
     *
     * <p>Closing happens mid-tick - content may close its own wall while being painted - and a click read off
     * the connection arrives a tick later. Either would otherwise ask a closed wall for a view and get a
     * fresh screen that nothing will ever paint or detach.
     */
    private boolean closed;

    private WallDisplay(Builder builder) {
        this.services = builder.services;
        this.world = builder.world;
        this.layout = builder.layout;
        this.onClose = builder.onClose;
        this.screenPerPlayer = builder.screenPerPlayer;
        this.intervalMs = builder.fps <= 0 ? 0 : 1000 / builder.fps;
        this.rangeSquared = builder.range * builder.range;
        this.interactive = builder.sharedScreen != null || builder.screenPerPlayer != null;
        this.overlay = builder.overlay;
        this.visibleTo = builder.visibleTo;
        this.controlledBy = builder.controlledBy;

        this.channel = builder.channel == null ? null : WallChannel.join(builder.channel, layout, this);
        this.tiles = new WallTiles(services.transport(), world, layout, builder.frames, channel);
        this.cursors = new WallCursors(layout, tiles, builder.showOthers, builder.aimMargin, builder.reach);
        this.center = new Location(world, layout.centerX(), layout.centerY(), layout.centerZ());

        this.shared = screenPerPlayer != null ? null
                : builder.sharedScreen != null ? WallView.running(services, layout, builder.sharedScreen, null)
                : WallView.showing(layout, builder.content);
        if (shared != null) {
            prepare(shared);
        }

        // Painted up front, since the whole idea is that playback sends no pixels at all. A transport that
        // cannot repoint its maps is asked before any of that work is done, and the wall simply streams.
        this.loop = builder.prerenderSteps > 0 && tiles.canShowLayers()
                ? WallLoop.paint(layout, builder.content, builder.prerenderSteps, builder.prerenderPeriodMs)
                : null;

        // Never for a prerendered loop, which sends no pixels to cull and would have to re-send every layer
        // it had already placed in the client - megabytes, to save the kilobyte a second playback costs.
        this.sight = builder.cullOffScreen && loop == null ? new WallSight(layout) : null;
    }

    // ---- lifecycle ----

    /**
     * Brings the viewer set up to date, paints, and pushes whatever changed.
     *
     * <p>Called by MapGUI once a tick for as long as the wall is open. Not something to call yourself.
     */
    public void tick(long now) {
        if (previewOnly || closed) return;

        Audience audience = admitAndEvict(now);
        // A listener told who has just arrived is free to close the wall, and a closed one has nothing left to
        // paint or send.
        if (closed) return;

        List<Player> nearby = audience.watching();
        // Before anything else, and before the channel work, because a prerendered wall sends no pixels at all -
        // it repoints frames at copies the client already holds. The builder refuses to combine the two.
        if (loop != null) {
            playLoop(audience, now);
            return;
        }
        // A wall on its own with nobody near it has nothing to do. One in a channel still might: the wall the
        // viewers are standing at may not be the one drawing for them.
        boolean drawing = channel == null || channel.isDrawing(this);
        if (nearby.isEmpty() && channel == null) return;

        List<WallView> allViews = views();
        // Painted before anybody is judged behind, because being behind is about the frame they are missing -
        // asking whether the surface is dirty before painting it asks about the frame before that one.
        if (drawing) {
            for (WallView view : allViews) view.paint(now, intervalMs);
        }

        // Whether a viewer can have this wall on screen is this wall's own question, and it has to be answered
        // before the audience is pooled: a viewer facing the far television is not looking at this one, so the
        // wall that happens to be drawing must not decide on their behalf that they need nothing.
        List<Player> streaming = new ArrayList<>();
        for (Player player : nearby) {
            if (sight != null && !sight.streaming(player, now, interactive && cursors.isAiming(player))) {
                // Marked behind on this wall, since this is the wall that would owe them the picture. A channel
                // member that is not drawing owes nobody anything, and its own set is never read.
                if (drawing && viewOf(player).surface().isDirty()) behind.add(player.getUniqueId());
                continue;
            }
            streaming.add(player);
        }

        if (channel != null) {
            // Reported even when this wall has nobody, so a member with no viewers of its own is not mistaken
            // for one that has gone.
            channel.reportViewers(now, streaming.stream().map(Player::getUniqueId).toList());
            if (!drawing) return;
        }
        // Everyone any wall in the channel wants, since a viewer at the far wall needs these ids too and has no
        // other way to be sent them. This wall's own audience when there is no channel.
        List<Player> watching = channel == null ? streaming : online(channel.viewers());
        // Nothing is cleared on the way out: what was painted is still owed to whoever turns up next tick.
        if (watching.isEmpty()) return;

        // Everyone watching a shared wall is sent the same bytes, so they are cut out of the surface once.
        TileRegions frame = new TileRegions();

        for (Player player : watching) {
            WallView view = viewOf(player);
            UUID id = player.getUniqueId();
            boolean dirty = view.surface().isDirty();

            // The whole picture for anybody this wall was not sending to a moment ago: they have just come into
            // range, come back from being culled, or - on a channel - only just been picked up by any wall at
            // all. What changed since the last frame is not something they can use.
            boolean whole = behind.remove(id) | (channel != null && !channel.wasSentTo(id));

            // One frame is one packet per map that changed, and a wall that goes up in pieces tears.
            services.transport().bundled(player, () -> {
                if (whole) {
                    tiles.sendAll(player, view.surface(), frame);
                } else if (dirty) {
                    tiles.sendChanged(player, view.surface(), frame);
                }
                if (interactive) {
                    cursors.send(player, watching, markersOf(view));
                }
            });
        }

        for (WallView view : allViews) view.surface().clearDirty();
        if (channel != null) channel.sentTo(watching.stream().map(Player::getUniqueId).toList());
    }

    /** A prerendered wall: everything on arrival, and a nudge per frame after that. */
    private void playLoop(Audience audience, long now) {
        WallLoop playing = loop;
        List<Player> arrived = audience.arrived();
        TileRegions frame = new TileRegions();

        for (Player player : audience.watching()) {
            // Kept together, or a wall would change one map at a time in front of whoever is watching.
            services.transport().bundled(player, () -> {
                if (arrived.contains(player)) {
                    playing.start(player, tiles, now, frame);
                } else {
                    playing.tick(player, tiles, now);
                }
            });
        }
    }

    /**
     * Takes the wall down for everyone and stops it being ticked.
     *
     * <p>Call this when whatever the wall belongs to goes away. Safe to call twice, and forgetting leaves
     * nothing behind: the frames were never in the world and vanish with the client's next chunk unload.
     *
     * <p>A listener can refuse it - see {@link de.flog99.mapgui.event.MapGuiWallRemoveEvent} - in which case
     * the wall stays up and this does nothing.
     */
    public void close() {
        if (closed) return;
        // Nothing for a listener to protect in a preview: one frame, one client, nothing registered behind it.
        if (!previewOnly && !MapGuiWallRemoveEvent.allows(this)) return;

        closeAnyway();
    }

    /**
     * Takes it down without asking anybody, for a shutdown or a plugin unloading.
     *
     * <p>Neither can honor a veto: there would be nothing left to tick the wall a listener insisted on
     * keeping, so it would sit in every viewer's client with nobody drawing it.
     */
    @ApiStatus.Internal
    public void closeAnyway() {
        if (closed) return;

        closed = true;
        if (channel != null) channel.leave(this);
        for (Player player : online(viewers)) tiles.hide(player);
        for (WallView view : views()) view.stop();
        viewers.clear();
        cursors.clear();
        taking.clear();
        owned.clear();
        behind.clear();
        if (sight != null) {
            sight.clear();
        }
        onClose.accept(this);
    }

    public WallLayout layout() {
        return layout;
    }

    public World world() {
        return world;
    }

    /**
     * What this wall is showing one viewer, a map at a time, so a camera can photograph it.
     *
     * <p>Empty for somebody who is not watching. That is not a shortcut: a wall exists only in the clients of the
     * people in front of it, and one who has walked out of range has been sent nothing to see - so there is nothing
     * to put in their photograph either.
     *
     * <p>What the wall is currently painting, which is not quite what a viewer's client holds if they have
     * turned away and the stream has paused on them. Deliberately: a camera's eye is not its owner's eye, and
     * a fixed one pointed at a screen its owner has their back to should still photograph what is playing.
     *
     * <p>A copy of the pixels, since the caller reads them off the main thread while the wall goes on painting.
     */
    public List<WallTile> shownTo(Player viewer) {
        if (closed || previewOnly || !sees(viewer)) return List.of();

        WallView view = viewOf(viewer);
        MapSurface surface = view.surface();

        List<WallTile> shown = new ArrayList<>(layout.count());
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                shown.add(new WallTile(layout.blockX(col, row), layout.blockY(col, row), layout.blockZ(col, row),
                        layout.facing(), surface.region(layout.surfaceX(col), layout.surfaceY(row), WallLayout.TILE, WallLayout.TILE)));
            }
        }
        return List.copyOf(shown);
    }

    /** Changes the frame rate of a wall that is already up, so an admin can throttle a busy server. */
    public void fps(int fps) {
        intervalMs = fps <= 0 ? 0 : 1000 / fps;
    }

    public void range(int range) {
        rangeSquared = range * range;
    }

    /** What this wall alone is costing, summed across its viewers. */
    public Bandwidth bandwidth() {
        return tiles.cost();
    }

    public int viewerCount() {
        return viewers.size();
    }

    /**
     * Whether there is a menu on this wall rather than just a picture, so clicks mean anything.
     *
     * <p>Never true while previewing, which leaves the clicks to whoever is placing it.
     */
    public boolean interactive() {
        return interactive && !previewOnly;
    }

    public boolean sees(Player player) {
        return viewers.contains(player.getUniqueId());
    }

    /**
     * True if this player is pointing at the wall right now, which is what makes a click theirs.
     *
     * <p>Safe to ask from the network thread, where whether to swallow a click has to be decided.
     */
    public boolean isAiming(Player player) {
        return cursors.isAiming(player);
    }

    /**
     * Whether this viewer's screen would take a click at all, answered from the <b>network thread</b>.
     *
     * <p>Everything but {@link Click#NONE} says yes, so a screen that has never heard of this behaves exactly as it
     * always did: an interactive wall swallows what is aimed at it. A screen answering {@code NONE} lets the click
     * past to the world.
     *
     * <p>Read off a snapshot taken on the tick rather than by asking the screen, since the screen is not the network
     * thread's to touch. A tick out of date is fine - the worst case is one click going where the screen wanted it a
     * tick ago.
     */
    @ApiStatus.Internal
    public boolean wouldTake(Player player) {
        return taking.get(player.getUniqueId()) != Click.NONE;
    }

    // ---- input ----

    /**
     * Delivers a click, if the player is pointing at the wall and the screen wants that button.
     *
     * <p>Returns whether it was taken, so a wall can stay claimed on a nearby player without eating clicks
     * aimed at anything else.
     *
     * <p>A screen with <b>no cursor</b> is delivered to as well, at {@link Screen#clickedAnywhere} - which is
     * what that is for, and what the held-map path has always done. It used to be turned away here, which made
     * a cursorless wall the one surface in MapGUI whose clicks went nowhere: claimed off the connection, since
     * any wall carrying a screen is aimed at, and then dropped on the floor. A mirror is the case that found
     * it, being a wall you have to be able to punch and a screen that must not take your aim.
     *
     * <p>And it is told <b>where</b>, cursor or not, which a held screen cannot be: the position is a raytrace
     * against the wall rather than the player's own pointing, so there is a real pixel to report and nothing is
     * asked of the player to get it.
     */
    public boolean click(Player player, Click with) {
        WallLayout.Aim aim = cursors.aimOf(player);
        if (aim == null || closed || !controlledBy.test(player)) return false;

        WallSession session = viewOf(player).session();
        if (session == null) return false;

        Screen screen = session.screen();
        if (!screen.activateOn().accepts(with)) return false;

        // Only for a screen that has one, or this would move a cursor nothing draws and hover a tree whose
        // nodes are not interactive.
        if (screen.cursor()) {
            session.cursorAt(aim.x(), aim.y());
        }
        session.asActing(player, () -> deliver(player, session, aim, with));
        return true;
    }

    /**
     * Turns the wheel on whatever the player is pointing at, for a scrollable list or a palette.
     *
     * <p>Returns whether it was used, so the caller knows whether to let the hotbar change go through.
     */
    public boolean scroll(Player player, int notches) {
        WallLayout.Aim aim = cursors.aimOf(player);
        if (aim == null || notches == 0 || closed) return false;

        WallSession session = viewOf(player).session();
        if (session == null) return false;

        Screen screen = session.screen();
        if (!screen.cursor()) return false;

        session.cursorAt(aim.x(), aim.y());
        session.asActing(player, () -> screen.scroll(aim.x(), aim.y(), notches));
        return true;
    }

    private void deliver(Player player, WallSession session, WallLayout.Aim aim, Click with) {
        Screen screen = session.screen();
        // Raised here rather than where the packet was read: that is the network thread, and this is a tick
        // later on the main one, which is the only thread a Bukkit listener may be handed.
        if (!MapGuiClickEvent.allows(player, screen, this, aim.x(), aim.y(), with)) return;
        if (!screen.click(aim.x(), aim.y(), with)) return;

        Sound sound = screen.clickSound();
        if (sound != null) {
            player.playSound(player, sound, 0.4f, 1.7f);
        }
    }

    /**
     * How far along this player's line of sight this wall is crossed, or -1 if it is not.
     *
     * <p>Half of a two-step: every wall is measured, then the nearest is told it won. It has to work that
     * way round because no wall can see the others, so a menu behind a menu would take clicks through it.
     */
    @ApiStatus.Internal
    public double measureAim(Player player) {
        return closed || !controlledBy.test(player) ? -1 : cursors.measure(player);
    }

    /**
     * The other half, which also points this viewer's cursor. {@code nearest} false throws the measurement
     * away, leaving this wall unpointed-at.
     *
     * <p>Hover on a shared wall follows whoever moved last, since highlights are pixels and there is one set.
     */
    @ApiStatus.Internal
    public void settleAim(Player player, boolean nearest) {
        if (closed) return;

        cursors.accept(player, nearest);

        WallSession session = viewOf(player).session();
        if (session == null) return;

        // Snapshotted here because this runs on the tick, once per viewer of an interactive wall, and already has
        // the screen in hand. See wouldTake.
        taking.put(player.getUniqueId(), session.screen().activateOn());

        WallLayout.Aim aim = cursors.aimOf(player);
        session.cursorAt(aim == null ? -1 : aim.x(), aim == null ? -1 : aim.y());
    }

    /** A screen's own markers - a minimap's player dots, say - which the client draws like a cursor. */
    private static List<Marker> markersOf(WallView view) {
        WallSession session = view.session();
        return session == null ? List.of() : session.screen().markers();
    }

    // ---- views ----

    private WallView viewOf(Player player) {
        if (shared != null) return shared;

        return owned.computeIfAbsent(player.getUniqueId(),
                id -> prepare(WallView.running(services, layout, screenPerPlayer.apply(player), player))
        );
    }

    /** What every view is told regardless of who it belongs to. */
    private WallView prepare(WallView view) {
        view.center(center);
        view.overlay(overlay);
        return view;
    }

    private List<WallView> views() {
        return shared != null ? List.of(shared) : List.copyOf(owned.values());
    }

    // ---- viewers ----

    /**
     * Who is being shown the wall once this tick's comings and goings are settled, and which of them are new.
     *
     * <p>Both lists rather than the viewer set, because the set is UUIDs and everything downstream wants
     * players - and these are the very players the world was just asked for, so resolving them again would be
     * a lookup each to arrive back where we started.
     */
    private record Audience(List<Player> watching, List<Player> arrived) {
    }

    /**
     * Everyone in range gets the wall; everyone who left gets it taken away.
     *
     * <p>Walking out and back re-sends everything, since a client throws away entities whose chunk unloads.
     *
     * <p>Range and nothing else, deliberately. Which way somebody is facing, and which side of the wall they
     * are on, decide what is <i>sent</i> to a viewer and not whether they are one - see
     * {@link Builder#cullOffScreen}. Both change several times a minute, and being thrown out of the viewer
     * set closes a per-player screen and takes its state with it, so neither is a thing to be evicted over.
     */
    private Audience admitAndEvict(long now) {
        List<Player> watching = new ArrayList<>();
        List<Player> arrived = new ArrayList<>();
        Set<UUID> present = new HashSet<>();

        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) > rangeSquared) continue;
            // Not a viewer at all, rather than a viewer sent nothing. A frame with no pixels behind it is a
            // grey square, so withholding only the picture would leave the wall visibly there for somebody it
            // was never meant for - and losing visibility then takes it away exactly as walking off does.
            if (!visibleTo.test(player)) continue;

            present.add(player.getUniqueId());
            watching.add(player);
            if (!viewers.add(player.getUniqueId())) continue;

            tiles.show(player);
            viewOf(player).startedAt(now);
            arrived.add(player);

            // Their client has the frames and no pixels for them yet. Noted here rather than read off the
            // arrival list, which lasts one tick - and somebody can arrive facing away for a good deal longer.
            if (loop == null) {
                behind.add(player.getUniqueId());
            }
        }

        List<UUID> left = new ArrayList<>();
        viewers.removeIf(id -> {
            if (present.contains(id)) return false;

            // Nobody to tell if they left the world or the server, and their client has already forgotten.
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().equals(world)) {
                tiles.hide(player);
            }
            cursors.forget(id);
            taking.remove(id);
            behind.remove(id);
            if (sight != null) {
                sight.forget(id);
            }
            if (loop != null) {
                loop.forget(id);
            }

            // Their own screen goes with them, so anything it registered itself with hears about it.
            WallView view = owned.remove(id);
            if (view != null) {
                view.stop();
            }
            left.add(id);
            return true;
        });

        MapGuiViewerChangeEvent.fire(this, arrived, left);
        return new Audience(watching, arrived);
    }

    /**
     * Marks every viewer as owed the whole picture rather than the next changed part of it.
     *
     * <p>Used when a channel's drawing wall closes and another takes over: the ids still hold the old wall's
     * last frame, and the new one has never painted, so what changed since is not a question it can answer.
     */
    void resendEverything() {
        behind.addAll(viewers);
        if (channel != null) behind.addAll(channel.viewers());
    }

    /** Viewers as players, for {@link #close} - which has only the set to work from and no world walk to hand. */
    private List<Player> online(Set<UUID> ids) {
        List<Player> found = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                found.add(player);
            }
        }
        return found;
    }

    // ---- building ----

    /**
     * Puts a wall up. Obtained from {@link MapGui#wall()}, which supplies the transport and arranges for the
     * result to be ticked.
     *
     * <p>Nothing is saved: to survive a restart, persist whatever the wall belongs to and open it again on
     * startup. A plugin placing furniture already stores where its furniture is, and a second copy of that
     * here could only disagree.
     */
    public static final class Builder {

        private final WallServices services;
        private final Consumer<WallDisplay> onOpen;
        private final Consumer<WallDisplay> onClose;

        private World world;
        private WallLayout layout;
        private WallContent content = (painter, bounds, millis) -> {
        };
        private Screen sharedScreen;
        private Function<Player, Screen> screenPerPlayer;
        private WallContent overlay;
        private int fps = 10;
        private int range = 48;
        private int prerenderSteps;
        private long prerenderPeriodMs;
        private int aimMargin;
        private boolean showOthers;
        private boolean cullOffScreen = true;

        /** The channel this wall shares its picture with, or null when it keeps it to itself. */
        @Nullable
        private String channel;
        private FrameStyle frames = FrameStyle.DEFAULT;
        private double reach = DEFAULT_REACH;
        private Predicate<Player> visibleTo = player -> true;
        private Predicate<Player> controlledBy = player -> true;

        private int minCols = 1;
        private int minRows = 1;
        private int maxCols = WallLayout.MAX_SIDE;
        private int maxRows = WallLayout.MAX_SIDE;
        private int aspectCols;
        private int aspectRows;

        /** Built by {@link MapGui#wall()} - one made any other way is never ticked and never cleaned up. */
        @ApiStatus.Internal
        public Builder(WallServices services, Consumer<WallDisplay> onOpen, Consumer<WallDisplay> onClose) {
            this.services = services;
            this.onOpen = onOpen;
            this.onClose = onClose;
        }

        /** The bottom left block as a viewer sees it, and the face the maps sit against - both straight out of a click. */
        public Builder at(Block block, BlockFace facing) {
            return at(block.getWorld(), block.getX(), block.getY(), block.getZ(), facing);
        }

        /**
         * The same from coordinates, for putting a saved wall back up.
         *
         * <p>Which way is up follows from the face and is not a choice - see {@link WallLayout#anchoredAt}.
         */
        public Builder at(World world, int x, int y, int z, BlockFace facing) {
            this.world = world;
            this.layout = WallLayout.anchoredAt(x, y, z, facing);
            return this;
        }

        /**
         * Maps across and down, each one a block. Capped at {@link WallLayout#MAX_SIDE} a side.
         *
         * <p>A request rather than the last word - the content's own limits narrow it, so what you get is
         * {@link #layout()}. One sizing gesture then serves content that scales and content that does not.
         */
        public Builder size(int cols, int rows) {
            if (layout == null) throw new IllegalStateException("Call at(..) before size(..)");

            this.layout = layout.resized(cols, rows);
            return this;
        }

        /**
         * The sizes this content works at, in maps. Anything up to {@link WallLayout#MAX_SIDE} a side by default.
         *
         * <p>For a menu whose buttons stop fitting below two maps, or a picture that would only be upscaled
         * past its own resolution. Someone sizing it is held inside the range rather than told off afterwards.
         *
         * @throws IllegalArgumentException if a bound is outside 1..{@link WallLayout#MAX_SIDE}, or a minimum
         *         is above its maximum
         */
        public Builder sizeBetween(int minCols, int minRows, int maxCols, int maxRows) {
            this.minCols = side(minCols, "minCols");
            this.minRows = side(minRows, "minRows");
            this.maxCols = side(maxCols, "maxCols");
            this.maxRows = side(maxRows, "maxRows");
            if (this.minCols > this.maxCols || this.minRows > this.maxRows) {
                throw new IllegalArgumentException("A wall cannot be smaller than " + this.minCols + "x"
                        + this.minRows + " and bigger than " + this.maxCols + "x" + this.maxRows
                );
            }
            return this;
        }

        /**
         * One size and nothing else - what a picture drawn for exactly 128x128 wants.
         *
         * <p>The preview stays at this size however far the corner is dragged, and says so.
         */
        public Builder fixedSize(int cols, int rows) {
            return sizeBetween(cols, rows, cols, rows);
        }

        /**
         * Keeps the wall in proportion, snapping to whole maps.
         *
         * <p>{@code aspect(2, 1)} allows 2x1, 4x2 and 6x3, and picks whichever is nearest the size asked for.
         * A map is the unit, so this is coarse - a six-map side has a handful of steps, and 16:9 has none.
         *
         * <p>Composes with {@link #sizeBetween}: only multiples inside those bounds are considered.
         *
         * @throws IllegalArgumentException if either side is not at least 1
         */
        public Builder aspect(int cols, int rows) {
            if (cols < 1 || rows < 1) {
                throw new IllegalArgumentException("An aspect ratio needs both sides, not " + cols + ":" + rows);
            }

            this.aspectCols = cols;
            this.aspectRows = rows;
            return this;
        }

        private static int side(int value, String name) {
            if (value < 1 || value > WallLayout.MAX_SIDE) {
                throw new IllegalArgumentException(name + " is maps a side, so 1 to " + WallLayout.MAX_SIDE + ", not " + value);
            }
            return value;
        }

        /**
         * The wall this would put up, sized as its content allows rather than as {@link #size} asked.
         *
         * <p>Ask before building when you are the one offering the sizing: this is what to show and to save.
         */
        public WallLayout layout() {
            if (layout == null) throw new IllegalStateException("A wall needs at(..)");

            return allowed(layout);
        }

        /**
         * Narrows a requested size to one the content works at.
         *
         * <p>The ratio offers the multiple nearest the request, then the bounds clamp each side. A ratio with
         * no multiple inside the bounds offers nothing, so the request falls through to the bounds alone.
         */
        private WallLayout allowed(WallLayout requested) {
            int cols = requested.cols();
            int rows = requested.rows();

            if (aspectCols > 0) {
                int best = 0;
                double closest = Double.MAX_VALUE;
                for (int step = 1; step * aspectCols <= maxCols && step * aspectRows <= maxRows; step++) {
                    if (step * aspectCols < minCols || step * aspectRows < minRows) continue;

                    double off = Math.abs(step * aspectCols - cols) + Math.abs(step * aspectRows - rows);
                    if (off >= closest) continue;

                    closest = off;
                    best = step;
                }
                if (best > 0) {
                    cols = best * aspectCols;
                    rows = best * aspectRows;
                }
            }
            return requested.resized(Math.clamp(cols, minCols, maxCols), Math.clamp(rows, minRows, maxRows));
        }

        /** Raw pixels, for something with no state to speak of. {@link WallContent#video} for a video. */
        public Builder content(WallContent value) {
            this.content = value;
            return showing(null, null);
        }

        /**
         * One menu that everybody sees and shares - a notice board, a jukebox queue, a vote.
         *
         * <p>Everyone still gets their own cursor; what they share is the state, so one press changes the
         * wall for the room.
         *
         * <p>Two things follow from there being one screen. {@link Session#player()} answers only inside a
         * click handler, since while painting there is no single player it could mean - use
         * {@link #screenPerPlayer} for a screen that needs to know who is looking. And hover highlights are
         * pixels, so they follow whoever moved last.
         */
        public Builder screenForEveryone(Screen screen) {
            return showing(screen, null);
        }

        /**
         * A menu each, built fresh for every viewer - a shop, a song picker, anything personal.
         *
         * <p>Behaves like a held menu: {@code player()} always answers, state is private, hover is per viewer.
         *
         * <p>The factory runs when someone comes into range and their screen closes when they leave, so state
         * held in the screen starts again on their way back. That keeps a wall from hoarding a screen for
         * everyone who walked past, but it means per-player state that should outlast walking away belongs in
         * a {@link SharedModel} of yours, keyed by player.
         *
         * <p>Costs an audience multiplier <i>on the server</i>: a surface pair and a paint pass each, and a
         * terrain scan each. What reaches one client is the same either way - map packets are per player
         * whichever mode a wall is in.
         *
         * <p>Takes the viewer, the same shape as {@link GuiCatalog#registerOpenable}. A screen that only needs
         * the private drawing state and not the identity ignores it: {@code _ -> new DrawScreen(shared)}.
         */
        public Builder screenPerPlayer(Function<Player, Screen> factory) {
            return showing(null, factory);
        }

        /** Exactly one source at a time, so asking for a screen forgets whatever was set before. */
        private Builder showing(@Nullable Screen screen, @Nullable Function<Player, Screen> factory) {
            this.sharedScreen = screen;
            this.screenPerPlayer = factory;
            return this;
        }

        /**
         * Something painted on top of whatever the wall shows, video or menu.
         *
         * <p>Unlike {@link #content} it does not replace the source, so it stacks with a screen - which is how
         * the placement preview draws its grid over a live menu. Transparent pixels show what is underneath.
         */
        public Builder overlay(WallContent value) {
            this.overlay = value;
            return this;
        }

        /**
         * How many pixels outside the picture still count as pointing at its edge. None by default.
         *
         * <p>The last row of pixels is a strip a fraction of a block wide, so a margin lets a viewer overshoot
         * and keeps the cursor pinned to the edge instead of sliding off the wall.
         *
         * <p>Around 20 suits drawing. Leave it at nought for a menu, where overshooting a button should miss.
         */
        public Builder aimMargin(int pixels) {
            this.aimMargin = Math.max(0, pixels);
            return this;
        }

        /** Draw the other viewers' pointers as well as your own. Off by default, and only means anything on a shared wall. */
        public Builder showOtherCursors(boolean value) {
            this.showOthers = value;
            return this;
        }

        /**
         * How often the content is redrawn. Zero redraws every tick.
         *
         * <p>The setting that decides what a wall costs, since every map is 16 KB per frame that changes it.
         * Something still - a painting, a sign - wants zero or one, and is then sent once and never again.
         */
        public Builder fps(int value) {
            this.fps = value;
            return this;
        }

        /**
         * Sends a repeating animation once instead of streaming it, and plays it by pointing the maps at the
         * copies already sitting in the client.
         *
         * <p>For a short loop that never varies - an animated sign, a logo, a few seconds of clip - it is the
         * difference between paying for the animation forever and paying for it once: a 2x2 wall at 10 fps costs
         * around 640 KB a second streamed and under a kilobyte a second flipped.
         *
         * <p>The trade is memory. Every step is a complete copy of the wall, held here and in each viewer's client -
         * twelve steps of a 3x3 wall is 1.7 MB per client, sent in one go when somebody walks into range. Capped at
         * {@value #MAX_PRERENDER_STEPS} steps, above which streaming is cheaper anyway.
         *
         * <p>Only for {@link #content}, and only for content that repeats <i>exactly</i>: the steps are painted when
         * the wall opens and never again, so anything reading the world, the clock or the viewer is frozen as it was.
         * A menu cannot be prerendered at all, since it has to answer clicks.
         *
         * <p>For a video, the natural call is its own frame count and duration:
         * {@code prerender(video.frames().count(), video.frames().durationMs())}.
         *
         * @param steps  how many frames the loop is cut into
         * @param periodMs how long one time round takes
         */
        public Builder prerender(int steps, long periodMs) {
            if (steps < 1 || periodMs < 1) {
                throw new IllegalArgumentException("A prerendered loop needs at least one step and some length, not "
                        + steps + " steps over " + periodMs + "ms"
                );
            }

            this.prerenderSteps = steps;
            this.prerenderPeriodMs = periodMs;
            return this;
        }

        /** How close a player has to be to be a viewer at all. Keep it inside the server's view distance. */
        public Builder range(int value) {
            this.range = value;
            return this;
        }

        /**
         * Stop sending pixels to a viewer who cannot see the wall - because they are behind it, have turned far
         * enough away from it, or have something solid in the way. On by default, and worth leaving on.
         *
         * <p>They stay a viewer throughout: their maps, their frames and their own screen are untouched, so
         * this pauses the stream rather than taking the wall down, and turning back costs one frame at most.
         * It costs nothing at all if nothing changed while they were away, which is what makes it safe on a
         * menu or a still picture - those pay only when they actually animate.
         *
         * <p>The view it assumes is deliberately wider than anyone's, since the server is not told the
         * client's field of view or aspect ratio, so this saves on somebody facing away rather than trimming
         * the edges of what they can see. It also keeps sending for half a second after they look away,
         * because heads turn quickly and a frame costs more than the glance saves.
         *
         * <p>Whether something is in the way is traced to nine points across the picture, so a wall showing
         * round the side of a pillar goes on being sent. That is a sample rather than a proof: one visible only
         * through a slit narrower than a third of it can still be missed. Glass is not what hides a wall, nor
         * are panes, bars, ice or barriers - a wall behind a window is being watched, whatever a click aimed
         * through it would do - but nor does one excuse whatever stands behind it, since a view that meets
         * something it can see through carries on looking.
         *
         * <p>The trace is remembered per viewer and taken again when they move, though never more than every
         * few ticks, so a crowd walking past a row of screens does not retrace every one of them every tick.
         * Standing still costs almost nothing, at the price of up to a second to notice somebody walling a
         * screen off - that delay and the grace period stack. All of them err towards sending.
         *
         * <p>What it does not save is drawing: the wall is painted every frame for as long as anybody is in
         * range, whether or not any of them is looking.
         *
         * <p>Ignored by {@link #prerender}, which has no stream to pause and would have to re-send every
         * layer it had already placed in the client.
         */
        public Builder cullOffScreen(boolean value) {
            this.cullOffScreen = value;
            return this;
        }

        /**
         * Whether the picture is lit by the block behind it or drawn at full brightness. Glowing by default,
         * which is what keeps a wall readable at night.
         *
         * <p>Turn it off for something that should belong to the room it hangs in - a painting, a mural, a
         * window - since a glowing frame is a rectangle of daylight in a dark hall, and the outline of it
         * gives the grid away. The cost is that an unlit wall is as unreadable as anything else unlit.
         *
         * <p>Free either way: the frames are packets rather than entities, so this is one bit in the metadata
         * that already goes out when a viewer arrives.
         */
        public Builder glowing(boolean value) {
            this.frames = new FrameStyle(value, frames.invisible(), frames.itemRotation());
            return this;
        }

        /**
         * Whether the frame's own model is hidden, leaving only the picture. Hidden by default, and the
         * reason a grid of maps reads as one image rather than nine framed ones.
         *
         * <p>Worth knowing it is a choice: an invisible frame makes a wall look painted straight onto the
         * blocks, with no edge and no border eating the outside of the picture. Show them again for something
         * that is meant to look like framed pictures on a wall.
         */
        public Builder invisible(boolean value) {
            this.frames = new FrameStyle(frames.glowing(), value, frames.itemRotation());
            return this;
        }

        /**
         * How far the map is turned inside its frame, in eighths of a full turn. None by default.
         *
         * <p>Turns the <i>picture</i> and nothing else. Where the wall is, which way it faces and where a
         * click lands are all untouched, so unlike a rotated frame this cannot put the cursor anywhere the
         * player is not pointing - but it also means a quarter turn on a wall that is not square shows the
         * picture across the short side.
         *
         * @throws IllegalArgumentException if the rotation is outside 0..{@link FrameStyle#ROTATIONS} - 1
         */
        public Builder itemRotation(int eighths) {
            this.frames = new FrameStyle(frames.glowing(), frames.invisible(), eighths);
            return this;
        }

        /**
         * How far a viewer can stand and still point at this wall, in blocks.
         * {@value #DEFAULT_REACH} by default.
         *
         * <p>Only about pointing. Who is a viewer at all is {@link #range}, and this should stay inside it -
         * a reach beyond the range only lets somebody aim at a wall they are being sent no pixels for.
         *
         * <p>Shorten it for something meant to be used from arm's length, so a player across the room aiming
         * past it does not take the clicks of whoever is standing at it. Lengthen it for a scoreboard on the
         * far wall of a stadium.
         */
        public Builder reach(double blocks) {
            this.reach = blocks;
            return this;
        }

        /**
         * Who is shown the wall at all. Everybody by default.
         *
         * <p>Failing the test is the same as standing too far away: no frames, no pixels, no cursor, and
         * nothing in their client to give the wall away. Somebody who stops passing it has the wall taken
         * away exactly as walking out of range does, and a per-player screen of theirs closes with it.
         *
         * <p>A predicate rather than a flag, so a permission is the whole of it:
         * {@code visibleTo(player -> player.hasPermission("shop.vip"))}. It is asked once a tick for every
         * player in range, so keep it to a permission or a lookup and not a database.
         */
        public Builder visibleTo(Predicate<Player> test) {
            this.visibleTo = test;
            return this;
        }

        /**
         * Who may actually work the menu on it. Everybody by default.
         *
         * <p>The other half of {@link #visibleTo}, and the pair is what makes "everyone reads it, staff
         * operate it" sayable at all. Without it a wall either carries a screen and answers to everybody in
         * front of it, or carries none and answers to nobody.
         *
         * <p>Somebody who fails it gets no cursor and their clicks are not taken, which also means their
         * right-click reaches the world as usual rather than being swallowed by a menu they cannot use.
         * Pointless without a screen: a wall showing a video has nothing to work.
         */
        public Builder controlledBy(Predicate<Player> test) {
            this.controlledBy = test;
            return this;
        }

        /**
         * Puts the wall up for everyone in range, and keeps it there until {@link WallDisplay#close}.
         *
         * @return the wall, or null if a listener cancelled
         *         {@link de.flog99.mapgui.event.MapGuiWallPlaceEvent} - in which case nothing was put up and
         *         there is nothing to close
         */
        @Nullable
        public WallDisplay open() {
            WallDisplay wall = build();
            if (!MapGuiWallPlaceEvent.allows(wall)) return null;

            onOpen.accept(wall);
            return wall;
        }

        /**
         * Puts the wall up for one player only, painted once and never again.
         *
         * <p>For showing someone where a wall would go: one send rather than a stream, and nothing to undo.
         */
        public WallDisplay preview(Player player, long now) {
            WallDisplay wall = build();
            wall.previewOnly = true;
            onOpen.accept(wall);

            wall.viewers.add(player.getUniqueId());
            WallView view = wall.viewOf(player);
            view.startedAt(now);
            view.paint(now, wall.intervalMs);
            wall.tiles.show(player);
            services.transport().bundled(player, () -> wall.tiles.sendAll(player, view.surface(), new TileRegions()));
            view.surface().clearDirty();
            return wall;
        }

        /**
         * Shares this wall's picture with every other wall on the same channel, so the pixels cross the wire
         * once rather than once per wall.
         *
         * <pre>{@code
         * MapGui.get().wall().at(block, facing).size(4, 3)
         *         .content(WallContent.live(source))
         *         .channel("lobby-tv")
         *         .open();
         * }</pre>
         *
         * <p>Six televisions playing one clip cost what one costs. A client keeps a picture per map id and a map
         * id is not tied to a place, so the walls hang the same ids: one of them paints and sends, and joining a
         * channel that is already running costs a mount packet and nothing per frame after it. The decode and the
         * paint are shared too, since the other walls never do either.
         *
         * <p><b>Every wall on a channel shows exactly the same thing</b>, including how far through a clip it is.
         * That is the point rather than a limitation - two walls of one clip a second apart look like a fault -
         * but it does mean a channel is for content, not for a menu: a screen answers clicks and reads who is
         * looking, so it cannot be shared and this refuses one.
         *
         * <p>All the walls on a channel must be the same size, since one picture is one size. Everything else
         * stays each wall's own: where it is, who can see it, how far it reaches, and whether a given viewer is
         * close enough to be streamed to.
         *
         * @param name what to call it. Any string; walls naming the same one share a picture
         */
        public Builder channel(String name) {
            this.channel = name == null || name.isBlank() ? null : name;
            return this;
        }

        private WallDisplay build() {
            if (world == null || layout == null) throw new IllegalStateException("A wall needs at(..)");
            if (prerenderSteps > 0 && (sharedScreen != null || screenPerPlayer != null)) {
                throw new IllegalStateException("A menu cannot be prerendered - it has to answer clicks. Use content(..) for a wall that only plays something.");
            }
            if (channel != null && prerenderSteps > 0) {
                throw new IllegalStateException("A prerendered wall cannot share a channel yet - it places whole copies of itself in each client and plays them by repointing frames, which is a second way of sending nothing and has not been measured against this one. Pick one.");
            }
            if (channel != null && (sharedScreen != null || screenPerPlayer != null)) {
                throw new IllegalStateException("A menu cannot share a channel - it answers clicks and reads who is looking, so its picture is its own. Use content(..) for a wall that only plays something.");
            }

            this.layout = allowed(layout);
            return new WallDisplay(this);
        }
    }
}
