package de.flog99.mapgui;

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
import java.util.function.Consumer;
import java.util.function.Function;

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
 * since a wall is sent to each client separately either way. The viewer set and the dirty rectangle hold it
 * down: an empty room costs nothing, and a wall that mostly sits still pays only for the part that moves. Per
 * player costs a paint pass and a surface pair each on top, so it suits something walked up to rather than
 * something a crowd gathers round.
 */
public final class WallDisplay {

    /**
     * The most steps {@link Builder#prerender} will take, so a caller can tell in advance whether an animation
     * is short enough to be worth sending once rather than streaming.
     */
    public static final int MAX_PRERENDER_STEPS = WallLoop.MAX_STEPS;

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

    /** Painted over whatever the wall shows, for every viewer. Null unless one was asked for. */
    @Nullable
    private final WallContent overlay;

    /** Set when the wall was asked to prerender and the transport can repoint its maps. */
    @Nullable
    private final WallLoop loop;

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

        this.tiles = new WallTiles(services.transport(), world, layout);
        this.cursors = new WallCursors(layout, tiles, builder.showOthers, builder.aimMargin);
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
    }

    // ---- lifecycle ----

    /**
     * Brings the viewer set up to date, paints, and pushes whatever changed.
     *
     * <p>Called by MapGUI once a tick for as long as the wall is open. Not something to call yourself.
     */
    public void tick(long now) {
        if (previewOnly || closed) return;

        List<Player> arrived = admitAndEvict(now);
        List<Player> watching = online(viewers);
        List<WallView> allViews = views();

        if (loop != null) {
            playLoop(arrived, watching, now);
            return;
        }

        for (WallView view : allViews) view.paint(now, intervalMs);

        if (!watching.isEmpty()) {
            // Everyone watching a shared wall is sent the same bytes, so they are cut out of the surface
            // once. Allocated only when there is somebody to send to; a wall with no audience never
            // builds the extraction map.
            TileRegions frame = new TileRegions();

            for (Player player : watching) {
                WallView view = viewOf(player);
                // One frame is one packet per map that changed, and a wall that goes up in pieces tears.
                services.transport().bundled(player, () -> {
                    if (arrived.contains(player)) {
                        tiles.sendAll(player, view.surface(), frame);
                    } else if (view.surface().isDirty()) {
                        tiles.sendChanged(player, view.surface(), frame);
                    }
                    if (interactive) {
                        cursors.send(player, watching, markersOf(view));
                    }
                });
            }
        }

        for (WallView view : allViews) view.surface().clearDirty();
    }

    /** A prerendered wall: everything on arrival, and a nudge per frame after that. */
    private void playLoop(List<Player> arrived, List<Player> watching, long now) {
        WallLoop playing = loop;
        TileRegions frame = new TileRegions();

        for (Player player : watching) {
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
     */
    public void close() {
        if (closed) return;

        closed = true;
        for (Player player : online(viewers)) tiles.hide(player);
        for (WallView view : views()) view.stop();
        viewers.clear();
        cursors.clear();
        owned.clear();
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

    // ---- input ----

    /**
     * Delivers a click, if the player is pointing at the wall and the screen wants that button.
     *
     * <p>Returns whether it was taken, so a wall can stay claimed on a nearby player without eating clicks
     * aimed at anything else.
     */
    public boolean click(Player player, Click with) {
        WallLayout.Aim aim = cursors.aimOf(player);
        if (aim == null || closed) return false;

        WallSession session = viewOf(player).session();
        if (session == null) return false;

        Screen screen = session.screen();
        if (!screen.activateOn().accepts(with) || !screen.cursor()) return false;

        session.cursorAt(aim.x(), aim.y());
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
        return closed ? -1 : cursors.measure(player);
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
     * Everyone in range gets the wall; everyone who left gets it taken away.
     *
     * <p>Walking out and back re-sends everything, since a client throws away entities whose chunk unloads.
     */
    private List<Player> admitAndEvict(long now) {
        List<Player> arrived = new ArrayList<>();
        Set<UUID> present = new HashSet<>();

        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) > rangeSquared) continue;

            present.add(player.getUniqueId());
            if (!viewers.add(player.getUniqueId())) continue;

            tiles.show(player);
            viewOf(player).startedAt(now);
            arrived.add(player);
        }

        viewers.removeIf(id -> {
            if (present.contains(id)) return false;

            // Nobody to tell if they left the world or the server, and their client has already forgotten.
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getWorld().equals(world)) {
                tiles.hide(player);
            }
            cursors.forget(id);
            if (loop != null) {
                loop.forget(id);
            }

            // Their own screen goes with them, so anything it registered itself with hears about it.
            WallView view = owned.remove(id);
            if (view != null) {
                view.stop();
            }
            return true;
        });
        return arrived;
    }

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

        /** How close a player has to be to be sent anything. Keep it inside the server's view distance. */
        public Builder range(int value) {
            this.range = value;
            return this;
        }

        /** Puts the wall up for everyone in range, and keeps it there until {@link WallDisplay#close}. */
        public WallDisplay open() {
            WallDisplay wall = build();
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

        private WallDisplay build() {
            if (world == null || layout == null) throw new IllegalStateException("A wall needs at(..)");
            if (prerenderSteps > 0 && (sharedScreen != null || screenPerPlayer != null)) {
                throw new IllegalStateException("A menu cannot be prerendered - it has to answer clicks. Use content(..) for a wall that only plays something.");
            }

            this.layout = allowed(layout);
            return new WallDisplay(this);
        }
    }
}
