package de.flog99.mapgui.plugin;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTextFont;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.Session;
import de.flog99.mapgui.TerrainRenderer;
import de.flog99.mapgui.prompt.PromptProvider;
import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.Animator;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.TextField;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCursor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Drives one player's menu.
 *
 * <p>Head rotation is the mouse. Yaw always accumulates as a delta, so the player can turn forever. Pitch does one
 * of two things: clamped, it maps absolutely onto the vertical axis and the head is pushed back into range when it
 * runs out; unclamped, it accumulates a delta like the yaw does, since a head free to leave the range would
 * otherwise pin the cursor to an edge and ignore the way back.
 *
 * <p>Either way the cursor moves only while it is on screen, and starts from the middle every time it appears, so a
 * pointer nobody can see never drifts. Clamped, where a row is a pitch, that means putting the head at mid range.
 */
final class PlayerSession implements Session {

    /** Ticks never land exactly on time, so a strict 20fps comparison would miss by a fraction and halve itself. */
    private static final int FRAME_SLACK_MS = 10;

    private final MapGuiPlugin plugin;
    private final Player player;
    private final HeldMapDisplay display;
    private final MapSurface surface;
    private final Painter painter;
    private final HandOptions hand;

    private final Deque<Screen> screens = new ArrayDeque<>();

    private double cursorX;
    private double cursorY;
    private float lastYaw;

    /** Only read while the pitch is unclamped, where the cursor follows a delta the way the yaw one does. */
    private float lastPitch;

    /**
     * Where the head is being put back to on regaining the mouse, or null. Kept across ticks because setting a pitch
     * only sends a packet, so a one-shot move reads back stale and the cursor snaps to where the head is not yet.
     */
    private Float restoringPitch;

    /** The pitch reported when that started, so the first move of any kind - ours landing or the player's - ends it. */
    private float restoringFrom;

    private boolean suspended;
    private boolean needsPaint = true;

    /**
     * Whether the screen has the player's mouse. Held rather than computed on demand so the moment it changes can
     * be noticed: gaining it has to re-anchor the cursor, and losing it has to send the pointer away.
     */
    private boolean focused;

    /** Whether the cursor is on screen, which is {@link #focused} and the screen wanting one. It moves only then. */
    private boolean aiming;

    /** Set by a toggling gesture, and only read by the focus modes that toggle. */
    private boolean focusToggled;

    /** Last seen sneak, so the screen hears about the change rather than the state. */
    private boolean sneaking;

    /**
     * Whether the map is what the player has in their main hand, which is the only place the drop key can reach it.
     *
     * <p>Kept as a field because the gesture handler reads it from the network thread, where the inventory may not
     * be touched. A tick out of date at worst, which is the same tolerance {@code focused} carries.
     */
    private boolean mapInMainHand;

    /** What {@link #focus} was told, or null for nobody having overruled the carry mode. */
    @Nullable
    private Boolean forcedFocus;

    /** Markers are client-drawn icons rather than pixels, so they change without dirtying the surface. */
    private List<Marker> sentMarkers = List.of();

    private Location lastLocation;
    private ScheduledTask task;
    private PromptProvider activePrompt;
    private long lastFrame;

    /** The catalog entry an admin opened this from, or null when a plugin opened it itself. */
    @Nullable
    private String openedFrom;

    /** Marks the bare item handed over for this session, so it is known from every other map in the inventory. */
    private final UUID own = UUID.randomUUID();

    private final Map<String, MapCursor.Type> cursorTypes = new HashMap<>();

    /** Terrain is expensive to scan, so it is kept in its own buffer and only redrawn on demand. */
    private MapSurface terrain;
    private boolean terrainValid;
    private int terrainScale = -1;
    private int ticksSinceTerrain;

    PlayerSession(MapGuiPlugin plugin, Player player, HeldMapDisplay display, Screen screen, HandOptions hand) {
        this.plugin = plugin;
        this.player = player;
        this.display = display;
        this.hand = hand;
        this.surface = new MapSurface(width(), height());
        this.painter = new Painter(surface, MapColors.INSTANCE, MapTextFont.INSTANCE);

        this.cursorX = width() / 2.0;
        this.cursorY = height() / 2.0;
        this.lastYaw = player.getLocation().getYaw();
        this.lastPitch = player.getLocation().getPitch();
        this.lastLocation = player.getLocation();

        adopt(screen);
    }

    private void adopt(Screen screen) {
        screens.push(screen);

        Animator animator = screen.animator();
        animator.enabled(plugin.config().animations());
        animator.loopFps(loopFps());

        screen.attach(this);
    }

    // ---- Session ----

    @Override
    public Player player() {
        return player;
    }

    void openedFrom(@Nullable String entry) {
        this.openedFrom = entry;
    }

    @Nullable
    String openedFrom() {
        return openedFrom;
    }

    @Override
    public Screen screen() {
        return screens.peek();
    }

    @Override
    public int width() {
        return HeldMapDisplay.SIZE;
    }

    @Override
    public int height() {
        return HeldMapDisplay.SIZE;
    }

    @Override
    public void push(Screen screen) {
        adopt(screen);
        display.refresh(this);
        needsPaint = true;
    }

    @Override
    public void pop() {
        if (screens.size() <= 1) {
            close();
            return;
        }

        screens.pop().detach();
        screen().invalidate();
        display.refresh(this);
        needsPaint = true;
    }

    @Override
    public void close() {
        plugin.sessions().close(player, true);
    }

    @Override
    public int cursorX() {
        return (int) cursorX;
    }

    @Override
    public int cursorY() {
        return (int) cursorY;
    }

    @Override
    public void invalidate() {
        screen().invalidate();
        needsPaint = true;
    }

    @Override
    public void suspend() {
        suspended = true;
        // Ticking stops here, so the pointer has to be sent away explicitly or it stays on screen
        // hovering over a menu nobody can reach.
        focused = false;
        aiming = false;
        send(markers());
    }

    @Override
    public void resume() {
        if (!suspended) return;

        suspended = false;
        // Re-anchor the mouse, otherwise the rotation drift while suspended lands in one jump.
        lastYaw = player.getLocation().getYaw();
        lastPitch = player.getLocation().getPitch();
        applyPitch(player.getLocation().getPitch());
        needsPaint = true;
        refocus();
        // A prompt with an inventory of its own will have wiped the client's idea of the map item.
        display.reassert(player);
    }

    @Override
    public boolean suspended() {
        return suspended;
    }

    @Override
    public boolean focused() {
        return focused;
    }

    @Override
    public void focus(boolean value) {
        forcedFocus = value;
        refocus();
    }

    @Override
    public HandOptions hand() {
        return hand;
    }

    /**
     * Works out whether the screen has the mouse, and reacts if that has just changed.
     *
     * <p>Called every tick rather than only on the events that could change it, because the answer depends on
     * things that raise no event worth listening for - which slot is selected, whether sneak is held. A tick of
     * latency on picking the map up is not something a player can see.
     */
    private void refocus() {
        EquipmentSlot holding = display.holding(player);
        // Before the early return below, since the drop key cares where the map is whether or not focus changed.
        mapInMainHand = holding == EquipmentSlot.HAND;

        boolean wanted = !suspended && holding != null
                && (forcedFocus != null ? forcedFocus : hand.focused(holding, player.isSneaking(), focusToggled));
        if (wanted == focused) return;

        focused = wanted;
        needsPaint = true;
        // Under a toggling mode the line says something different either side of the key, and the key is the
        // player's own - so say it again rather than leaving the one from opening standing. Never before
        // start() has sent the first, or opening would send two.
        if (task != null && hand.togglesFocus()) {
            player.sendActionBar(Component.text(controls()));
        }
        reaim();
    }

    /**
     * Picks up the cursor being drawn or not, which is focus and the screen wanting one.
     *
     * <p>Both, and not focus alone: a screen may keep the mouse and hide its pointer - the camera's viewfinder shows
     * one only while sneaking. A hidden cursor does not follow the head, and comes back in the middle rather than
     * wherever it was, so where it appears never depends on what the player was doing without it.
     */
    private void reaim() {
        boolean wanted = focused && screen().cursor();
        if (wanted == aiming) return;

        aiming = wanted;
        if (aiming) {
            // Re-anchor, or the head movement since the pointer went away arrives as one jump.
            lastYaw = player.getLocation().getYaw();
            lastPitch = player.getLocation().getPitch();
            centreCursor();
            // Clamped, the middle row is the middle of the pitch range, so the head has to go there to match.
            if (clampPitch()) {
                restore(midPitch());
            }
            applyPitch(player.getLocation().getPitch());
        } else {
            restoringPitch = null;
            // Nowhere rather than wherever it was, or the row the player was hovering stays lit on a map they are
            // no longer pointing at. Repainted next tick rather than here, since a screen may drop the mouse from
            // inside its own click handler and painting from there would be painting twice over.
            if (screen().cursorMoved(-1, -1)) {
                needsPaint = true;
            }
            // The pointer is a marker, and markers only leave when something says so.
            send(markers());
        }
    }

    /**
     * A gesture asking for the mouse, or asking to give it back.
     *
     * <p>The player's own gesture drops whatever {@link #focus} was told, since a screen deciding it should have
     * the mouse should not be able to stop the player disagreeing.
     *
     * @return whether the gesture was ours to take, so a caller reading a packet knows whether to swallow it
     */
    boolean toggleFocus() {
        if (!hand.togglesFocus() || display.holding(player) == null) return false;

        forcedFocus = null;
        focusToggled = !focusToggled;
        refocus();
        return true;
    }

    @Override
    public void promptText(TextPrompt prompt, String providerKey, Consumer<Optional<String>> callback) {
        PromptProvider provider = providerKey == null
                ? plugin.prompts().getDefault()
                : plugin.prompts().get(providerKey);
        if (provider == null) {
            provider = plugin.prompts().getDefault();
        }

        suspend();
        activePrompt = provider;
        provider.promptText(player, prompt).whenComplete((result, error) -> onMainThread(() -> {
            activePrompt = null;
            resume();
            if (error != null) {
                plugin.getSLF4JLogger().warn("Prompt failed for {}", player.getName(), error);
                callback.accept(Optional.empty());
            } else {
                callback.accept(result == null ? Optional.empty() : result);
            }
        }));
    }

    @Override
    public void edit(TextField field) {
        TextPrompt prompt = TextPrompt.of(field.title())
                .initial(field.value())
                .maxLength(field.maxLength());

        promptText(prompt, field.promptKey(), result -> result.ifPresent(value -> {
            field.accept(value);
            invalidate();
        }));
    }

    /** A provider may answer on any thread, so everything reconvenes here. */
    private void onMainThread(Runnable action) {
        if (plugin.getServer().isPrimaryThread()) {
            action.run();
        } else {
            player.getScheduler().run(plugin, task -> action.run(), null);
        }
    }

    // ---- lifecycle ----

    void start(int mapId) {
        ItemStack carried = hand.carry() == HandOptions.Carry.ITEM ? plugin.handItems().blank(mapId, own) : null;
        display.open(this, hand, mapId, mine(), carried);
        refocus();
        player.sendActionBar(Component.text(controls()));

        // Right-click and Q reach us only as packets - the events behind both are gated on the player
        // really holding something, and our map is not in their inventory.
        // A focused screen takes everything: the whole point is that a click means "press this" and not
        // whatever the player is really holding.
        plugin.router().claim(player, gestures);
        // Opening with the cursor already up is the same move as it appearing later, and reaim() has made it.
        task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(), null, 1L, 1L);
    }

    /**
     * Which stack in the inventory this session's screen belongs to, for the carry modes where that is a real item.
     *
     * <p>Either the player's own copy, by the GUI's registered name, or the bare one MapGUI handed them, by its
     * token. Never the map id: a pinned one is worn by every copy of a screen and could name the wrong hand.
     */
    private Predicate<ItemStack> mine() {
        HandItems items = plugin.handItems();
        String gui = openedFrom;
        return stack -> own.equals(items.ownOf(stack)) || (gui != null && gui.equals(items.guiOf(stack)));
    }

    /**
     * What to tell the player they can do, which depends on how the map got into their hands - and on whether they
     * have picked it up yet.
     *
     * <p>A toggling mode opens with the map down, where nothing takes a click at all. Saying what a click does there,
     * and calling the toggle a way to put the map <i>down</i>, described the state after the key rather than the one
     * the line is read in - so the only true thing to say is how to pick the map up.
     */
    private String controls() {
        if (!focused && hand.togglesFocus()) return pickUp();

        String hint = switch (screen().activateOn()) {
            case RIGHT -> "Right-click to select";
            case LEFT -> "Left-click to select";
            case BOTH -> "Click to select";
        };

        return hint + switch (hand.carry()) {
            case POPUP -> ", Q to close";
            case ITEM -> ", scroll away to put it down";
            // Q reaches a pinned map in the main hand, where it is the thing being held.
            case PINNED -> hand.reachesMainHand() ? focusHint() + ", Q to close" : focusHint();
            case OFFHAND -> focusHint();
        };
    }

    /** The other half of a toggle, for the map the player is carrying but has not raised. */
    private String pickUp() {
        return switch (hand.focus()) {
            case SWAP_HANDS -> "Swap hands to use it";
            case RIGHT_CLICK -> "Right-click the air to use it";
            // Nothing else toggles, so nothing else reaches here.
            default -> "";
        };
    }

    private String focusHint() {
        return switch (hand.focus()) {
            case SWAP_HANDS -> ", swap hands to put it down";
            case RIGHT_CLICK -> ", right-click the air to put it down";
            case SNEAK -> ", hold sneak to use it";
            // Only worth saying where scrolling away is possible. On a map that lives in the offhand it is not.
            case MAIN_HAND -> hand.reachesMainHand() ? ", scroll away to put it down" : "";
            case ALWAYS, NEVER -> "";
        };
    }

    /**
     * A field rather than inline, since releasing the claim needs the same instance back.
     *
     * <p>Everything here runs on the network thread and so may only read fields, never the world. {@code focused}
     * is a plain boolean and a tick out of date at worst, which is exactly the tolerance
     * {@link PacketInput.Handler} asks for.
     */
    private final PacketInput.Handler gestures = new PacketInput.Handler() {

        /**
         * Q closes a faked map the player is using: a popup, which has no other way out, and one in the main hand,
         * where the key would otherwise throw away whatever real item the map is covering.
         *
         * <p>Swallowed rather than closing for a screen with no mouse, which is carried rather than used - there is
         * still nothing of ours to drop, but ending it is not what its player meant.
         *
         * <p>Anywhere else the key is theirs. That covers an offhand map, where Q always drops from the other hand -
         * swallowing it there left a player holding a real item unable to drop it, for a map the key was never going
         * to reach. A real item is left alone too, since dropping it is how the player puts that screen away.
         */
        @Override
        public boolean drop() {
            if (!hand.fillsHotbar() && !(hand.faked() && mapInMainHand)) return false;

            if (hand.fillsHotbar() || focused) {
                onMainThread(PlayerSession.this::close);
            }
            return true;
        }

        @Override
        public boolean rightClick() {
            if (!focused) return false;

            onMainThread(PlayerSession.this::rightClick);
            return true;
        }

        /**
         * The toggle for {@link HandOptions.Focus#RIGHT_CLICK}, in both directions, and an ordinary click
         * otherwise.
         *
         * <p>Both directions is what "toggle" means, so a screen using this mode should activate on the left
         * button - the right one is spoken for. Air only, so a door is still openable while the map is down.
         */
        @Override
        public boolean rightClickAir() {
            if (hand.focus() == HandOptions.Focus.RIGHT_CLICK && hand.togglesFocus()) {
                onMainThread(PlayerSession.this::toggleFocus);
                return true;
            }

            return rightClick();
        }

        @Override
        public boolean leftClick() {
            if (!focused) return false;

            onMainThread(PlayerSession.this::leftClick);
            return true;
        }
    };

    /** Restoring is skipped for a player already gone or dead: there is no item to give back, only a slot they have lost anyway. */
    void stop(boolean restore) {
        if (task != null) {
            task.cancel();
        }
        plugin.router().release(player, gestures);
        if (activePrompt != null) {
            activePrompt.cancel(player);
        }
        while (!screens.isEmpty()) screens.pop().detach();

        if (restore) {
            display.close(this);
        } else {
            display.forget(player);
        }
    }

    // ---- input ----

    private void tick() {
        if (!player.isOnline()) {
            plugin.sessions().close(player, false);
            return;
        }

        Location now = player.getLocation();
        if (suspended) {
            lastYaw = now.getYaw();
            lastLocation = now;
            return;
        }

        refocus();
        // Every tick, since a screen may start and stop wanting a cursor without focus changing at all.
        reaim();
        trackSneak();

        if (aiming) {
            if (now.getYaw() != lastYaw) {
                double perDegree = width() / (plugin.config().maxPitch() - plugin.config().minPitch());
                cursorX = clamp(cursorX + yawDelta(now.getYaw()) * perDegree, 0, width() - 1);
                lastYaw = now.getYaw();
            }

            applyPitch(now.getPitch());
        } else {
            // Still followed while the pointer is away, so drawing it again does not arrive as a jump.
            lastYaw = now.getYaw();
            lastPitch = now.getPitch();
        }

        ticksSinceTerrain++;
        if (screen().terrain() && movedBlock(now)
                && ticksSinceTerrain >= plugin.config().terrainRefreshTicks()) {
            ticksSinceTerrain = 0;
            lastLocation = now;
            terrainValid = false;
            needsPaint = true;
        }

        if (aiming && screen().cursorMoved(cursorX(), cursorY())) {
            needsPaint = true;
        }
        if (screen().isDirty()) {
            needsPaint = true;
        }
        // Something still easing means another frame, but only as often as the limit allows.
        if (screen().animating() && frameDue()) {
            needsPaint = true;
        }

        if (needsPaint) {
            paint();
        }

        // Read after painting, since a fresh layout is what decides the hovered node's caption.
        List<Marker> markers = markers();
        if (needsPaint || !markers.equals(sentMarkers)) {
            send(markers);
        }
        needsPaint = false;
    }

    /**
     * Tells the screen when sneak goes down or comes up, and only then.
     *
     * <p>Polled rather than listened for because {@code PlayerToggleSneakEvent} fires for every player on the server
     * and this only cares about the few with a map open - and the tick is already here. Nothing is sent for a screen
     * that does not override the hook.
     */
    private void trackSneak() {
        boolean now = player.isSneaking();
        if (now == sneaking) return;

        sneaking = now;
        screen().sneakChanged(now);
    }

    /** Whether an animation may have another frame yet. Only animation asks, so a low limit costs responsiveness nothing. */
    private boolean frameDue() {
        Animator animator = screen().animator();
        long interval = 1000L / fps();

        // Nothing easing means only loops are left, and those get the slower of the two limits.
        if (!animator.transitioning()) {
            interval = Math.max(interval, animator.loopIntervalMs());
        }

        return System.currentTimeMillis() - lastFrame >= interval - FRAME_SLACK_MS;
    }

    private float yawDelta(float yaw) {
        float delta = yaw - lastYaw;
        if (delta > 180f) {
            delta -= 360f;
        }
        if (delta < -180f) {
            delta += 360f;
        }
        return delta;
    }

    /**
     * Moves the cursor's vertical axis, one of two ways.
     *
     * <p><b>Clamped</b>, pitch maps absolutely: the head is pushed back into range, so a given pitch is always the
     * same row and the range is the whole screen.
     *
     * <p><b>Unclamped</b>, it accumulates a delta exactly as the yaw one does, because the head is free to leave the
     * range and an absolute mapping then reads as stuck - look right up and the cursor pins to the top, and looking
     * a little back down does nothing at all until the pitch re-enters the range. Following the delta instead means
     * down is always down, which is what the horizontal axis has always done.
     */
    private void applyPitch(float pitch) {
        float min = plugin.config().minPitch();
        float max = plugin.config().maxPitch();

        if (clampPitch()) {
            pitch = restored(pitch);
            if (pitch < min) {
                plugin.rotation().setPitchKeepingYaw(player, min);
                pitch = min;
            } else if (pitch > max) {
                plugin.rotation().setPitchKeepingYaw(player, max);
                pitch = max;
            }
            cursorY = clamp((pitch - min) / (max - min) * (height() - 1), 0, height() - 1);
        } else {
            restoringPitch = null;
            // The same degrees-per-pixel the yaw axis uses, so both axes move at one speed.
            double perDegree = height() / (max - min);
            cursorY = clamp(cursorY + (pitch - lastPitch) * perDegree, 0, height() - 1);
        }

        lastPitch = pitch;
    }

    /** Starts putting the head back, unless it is already there and there is nothing to put back. */
    private void restore(float pitch) {
        restoringFrom = player.getLocation().getPitch();
        restoringPitch = Math.abs(restoringFrom - pitch) <= PITCH_SETTLED ? null : pitch;
    }

    /**
     * The pitch to read the cursor's row off while the head is being put back. The first move of any kind ends it:
     * ours lands on the target, and theirs is the player aiming, which a screen must not hold a head against.
     */
    private float restored(float pitch) {
        if (restoringPitch == null) return pitch;

        if (Math.abs(pitch - restoringFrom) > PITCH_SETTLED) {
            restoringPitch = null;
            return pitch;
        }

        plugin.rotation().setPitchKeepingYaw(player, restoringPitch);
        return restoringPitch;
    }

    /** Degrees within which two pitches are the same one, since these arrive as floats off a packet. */
    private static final float PITCH_SETTLED = 0.05f;

    private void centreCursor() {
        cursorX = width() / 2.0;
        cursorY = startRow();
    }

    /**
     * The row the cursor appears at: the middle, moved toward an edge if the head no longer has the travel to reach
     * the other one. Looking straight down is the case it is for - the pitch stops at 90, so a cursor starting mid
     * map could never be brought any lower and half the screen would be out of reach.
     *
     * <p>Only unclamped. Clamped, the head is put mid range with the cursor, so every row is reachable by construction.
     */
    private double startRow() {
        if (clampPitch()) return height() / 2.0;

        return startRow(height(), plugin.config().minPitch(), plugin.config().maxPitch(),
                player.getLocation().getPitch());
    }

    /** Package-private for {@code CursorStartTest}, which is what pins the reach at either end of the pitch. */
    static double startRow(int height, float minPitch, float maxPitch, float pitch) {
        double middle = height / 2.0;
        double perDegree = height / (double) (maxPitch - minPitch);
        double lowest = (height - 1) - (PITCH_LIMIT - pitch) * perDegree;
        double highest = (pitch + PITCH_LIMIT) * perDegree;

        return clamp(middle, Math.max(0, lowest), Math.min(height - 1, highest));
    }

    /** How far the client lets a head tip either way, which is what bounds the cursor travel left in each direction. */
    private static final float PITCH_LIMIT = 90;

    /** The screen decides if it has an opinion, otherwise the server does. Never while the mouse is elsewhere. */
    private boolean clampPitch() {
        if (!focused || !screen().cursor()) return false;

        // Only for a map the player is holding up in front of them. An offhand map is something they glance at
        // while looking at the world - a viewfinder, a quest log, a minimap - so pushing their head into its pitch
        // range takes over the aim they are still using. The cursor stops at the edge there instead.
        if (!mapInMainHand) return false;

        Boolean wanted = screen().clampPitch();
        return wanted != null ? wanted : plugin.config().clampPitch();
    }

    /** The server's number is a ceiling rather than a default, so a screen asking for more loses. Asking for less always works. */
    private int fps() {
        int wanted = screen().fps();
        return wanted > 0 ? Math.min(wanted, plugin.config().fps()) : plugin.config().fps();
    }

    private int loopFps() {
        int wanted = screen().loopFps();
        return wanted > 0 ? Math.min(wanted, plugin.config().loopFps()) : plugin.config().loopFps();
    }

    private float midPitch() {
        return (plugin.config().minPitch() + plugin.config().maxPitch()) / 2f;
    }

    private boolean movedBlock(Location now) {
        return now.getBlockX() != lastLocation.getBlockX() || now.getBlockZ() != lastLocation.getBlockZ();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    void leftClick() {
        if (screen().activateOn().accepts(Click.LEFT)) {
            activate(Click.LEFT);
        }
    }

    /**
     * A right-click the menu took, and the slot it has to put back.
     *
     * <p>The packet is swallowed before the server ever sees it, which is what stops the held item being used.
     * The client predicted that use the instant it clicked, though, and a refusal it is never told about is
     * not a refusal it can undo - so an item that predicts being eaten, scoped or drawn stays that way on
     * screen until something unrelated happens to resend the slot.
     *
     * <p>It bites hardest on {@link de.flog99.mapgui.MapGui#openWhileHolding}, where the main hand holds the
     * caller's own item by design: a camera made from a knowledge book vanished on every right-click, and the
     * spyglass this API is documented with would scope and stay scoped.
     *
     * <p>Safe to resend while a map is faked because {@code FakeSlots} wraps the synchronizer for exactly
     * this - and only sent when the main hand holds something real, since a faked map is a filled map to the
     * client and predicts nothing. So a popup being clicked through sends no inventories at all.
     */
    void rightClick() {
        if (screen().activateOn().accepts(Click.RIGHT)) {
            activate(Click.RIGHT);
        }

        if (!mapInMainHand) {
            player.updateInventory();
        }
    }

    private void activate(Click with) {
        if (suspended || !focused) return;

        // Read from the screen that was clicked - handling it may well have pushed another one.
        Screen clicked = screen();
        // A cursorless screen still hears the click, but only through Screen#clickedAnywhere: -1 means there is
        // no position, so nothing is hit-tested.
        boolean pointed = clicked.cursor();
        if (!clicked.click(pointed ? cursorX() : -1, pointed ? cursorY() : -1, with)) return;

        Sound sound = clicked.clickSound();
        if (sound != null) {
            player.playSound(player, sound, 0.4f, 1.7f);
        }
    }

    /** Restates the faked slots next tick: a container closing or the creative inventory opening is still mid-flight now, and would undo it. */
    void reassertSoon() {
        player.getScheduler().run(plugin, scheduled -> display.reassert(player), null);
    }

    void scroll(int direction) {
        if (focused && !suspended && screen().cursor()) {
            screen().scroll(cursorX(), cursorY(), direction);
        }
    }

    // ---- rendering ----

    private void paint() {
        Screen screen = screen();
        lastFrame = System.currentTimeMillis();
        screen.animator().clock(lastFrame);

        // Measured and drawn with the same font, which is the screen's to choose and can differ between the
        // screens one session has stacked - so it is set per frame rather than when the painter was built.
        painter.font(screen.font());

        // Animations are resolved during layout, so an in-flight one needs a fresh pass each frame.
        if (screen.isDirty() || screen.animating()) {
            screen.layout(screen.font(), surface.bounds());
            screen.cursorMoved(cursorX(), cursorY());
        }

        if (screen.terrain()) {
            drawTerrain(screen.blocksPerPixel());
        } else {
            surface.fill(MapColors.INSTANCE.index(screen.background()));
        }

        screen.paint(painter);
    }

    /** Hands the finished frame to the display, which is what actually reaches the client. */
    private void send(List<Marker> markers) {
        display.show(this, surface, markers);
        surface.clearDirty();
        sentMarkers = markers;
    }

    /** The screen's own markers, plus the pointer - which is just another marker, and only while we have the mouse. */
    private List<Marker> markers() {
        List<Marker> markers = new ArrayList<>(screen().markers());
        if (focused && !suspended && screen().cursor()) {
            markers.add(new Marker(cursorType(), cursorX(), cursorY(), (byte) 8, screen().cursorCaption()));
        }
        return markers;
    }

    private void drawTerrain(int blocksPerPixel) {
        if (terrain == null) {
            terrain = new MapSurface(width(), height());
        }

        if (!terrainValid || terrainScale != blocksPerPixel) {
            terrainScale = blocksPerPixel;
            TerrainRenderer.render(terrain, player, blocksPerPixel);
            terrainValid = true;
        }

        for (int y = 0; y < surface.height(); y++) {
            for (int x = 0; x < surface.width(); x++) {
                surface.set(x, y, terrain.get(x, y));
            }
        }
    }

    /**
     * The hovered node can ask for a different cursor; an unknown name falls back to the default.
     *
     * <p>Looked up in the registry rather than by enum name, since these stopped being an enum. Either
     * {@code RED_X} or {@code red_x} works.
     */
    private MapCursor.Type cursorType() {
        String requested = screen().cursorIcon();
        if (requested == null) return MapCursor.Type.RED_MARKER;

        return cursorTypes.computeIfAbsent(requested, name -> {
            MapCursor.Type found = Registry.MAP_DECORATION_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            if (found != null) return found;

            plugin.getSLF4JLogger().warn("Unknown cursor icon \"{}\"", name);
            return MapCursor.Type.RED_MARKER;
        });
    }
}
