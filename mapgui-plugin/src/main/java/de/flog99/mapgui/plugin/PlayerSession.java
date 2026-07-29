package de.flog99.mapgui.plugin;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTextFont;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.RotationController;
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
import java.util.function.Consumer;

/**
 * Drives one player's menu.
 *
 * <p>Head rotation is the mouse: yaw accumulates as a delta so the player can turn forever, while pitch
 * maps absolutely onto the vertical axis and is pushed back inside its range when it runs out.
 */
final class PlayerSession implements Session {

    /** Ticks never land exactly on time, so a strict 20fps comparison would miss by a fraction and halve itself. */
    private static final int FRAME_SLACK_MS = 10;

    private final MapGuiPlugin plugin;
    private final Player player;
    private final HeldMapDisplay display;
    private final MapSurface surface;
    private final Painter painter;

    private final Deque<Screen> screens = new ArrayDeque<>();

    private double cursorX;
    private int cursorY;
    private float lastYaw;
    private boolean suspended;
    private boolean needsPaint = true;

    /** Markers are client-drawn icons rather than pixels, so they change without dirtying the surface. */
    private List<Marker> sentMarkers = List.of();

    private Location lastLocation;
    private ScheduledTask task;
    private PromptProvider activePrompt;
    private long lastFrame;

    /** The catalog entry an admin opened this from, or null when a plugin opened it itself. */
    @Nullable
    private String openedFrom;

    private final Map<String, MapCursor.Type> cursorTypes = new HashMap<>();

    /** Terrain is expensive to scan, so it is kept in its own buffer and only redrawn on demand. */
    private MapSurface terrain;
    private boolean terrainValid;
    private int terrainScale = -1;
    private int ticksSinceTerrain;

    PlayerSession(MapGuiPlugin plugin, Player player, HeldMapDisplay display, Screen screen) {
        this.plugin = plugin;
        this.player = player;
        this.display = display;
        this.surface = new MapSurface(width(), height());
        this.painter = new Painter(surface, MapColors.INSTANCE, MapTextFont.INSTANCE);

        this.cursorX = width() / 2.0;
        this.cursorY = height() / 2;
        this.lastYaw = player.getLocation().getYaw();
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
        return cursorY;
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
        send(markers());
    }

    @Override
    public void resume() {
        if (!suspended) return;

        suspended = false;
        // Re-anchor the mouse, otherwise the rotation drift while suspended lands in one jump.
        lastYaw = player.getLocation().getYaw();
        applyPitch(player.getLocation().getPitch());
        needsPaint = true;
        // A prompt with an inventory of its own will have wiped the client's idea of the map item.
        display.reassert(player);
    }

    @Override
    public boolean suspended() {
        return suspended;
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

    void start() {
        display.open(this);
        player.sendActionBar(Component.text(controls()));

        // Right-click and Q reach us only as packets - the events behind both are gated on the player
        // really holding something, and our map is not in their inventory.
        // An open menu takes everything: the whole point is that a click means "press this" and not
        // whatever the player is really holding.
        plugin.router().claim(player, gestures);

        // Centring the cursor means moving their head, so it only happens if we are allowed to.
        if (clampPitch()) {
            RotationController rotation = plugin.rotation();
            rotation.setPitchKeepingYaw(player, midPitch());
        }

        task = player.getScheduler().runAtFixedRate(plugin, scheduled -> tick(), null, 1L, 1L);
    }

    private String controls() {
        String hint = switch (screen().activateOn()) {
            case RIGHT -> "Right-click to select";
            case LEFT -> "Left-click to select";
            case BOTH -> "Click to select";
        };
        return hint + ", Q to close";
    }

    /** A field rather than inline, since releasing the claim needs the same instance back. */
    private final PacketInput.Handler gestures = new PacketInput.Handler() {
        @Override
        public boolean drop() {
            onMainThread(PlayerSession.this::close);
            return true;
        }

        @Override
        public boolean rightClick() {
            onMainThread(PlayerSession.this::rightClick);
            return true;
        }

        @Override
        public boolean leftClick() {
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

        if (now.getYaw() != lastYaw) {
            double perDegree = width() / (plugin.config().maxPitch() - plugin.config().minPitch());
            cursorX = clamp(cursorX + yawDelta(now.getYaw()) * perDegree, 0, width() - 1);
            lastYaw = now.getYaw();
        }

        applyPitch(now.getPitch());

        ticksSinceTerrain++;
        if (screen().terrain() && movedBlock(now)
                && ticksSinceTerrain >= plugin.config().terrainRefreshTicks()) {
            ticksSinceTerrain = 0;
            lastLocation = now;
            terrainValid = false;
            needsPaint = true;
        }

        if (screen().cursor() && screen().cursorMoved(cursorX(), cursorY)) {
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
     * Maps pitch onto the vertical axis, pushing the head back into range on the way.
     *
     * <p>With the clamp off nothing touches the player's rotation and the cursor stops at the edge instead.
     */
    private void applyPitch(float pitch) {
        float min = plugin.config().minPitch();
        float max = plugin.config().maxPitch();

        if (clampPitch()) {
            if (pitch < min) {
                plugin.rotation().setPitchKeepingYaw(player, min);
                pitch = min;
            } else if (pitch > max) {
                plugin.rotation().setPitchKeepingYaw(player, max);
                pitch = max;
            }
        }

        cursorY = (int) clamp((pitch - min) / (max - min) * (height() - 1), 0, height() - 1);
    }

    /** The screen decides if it has an opinion, otherwise the server does. */
    private boolean clampPitch() {
        if (!screen().cursor()) return false;

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

    void rightClick() {
        if (screen().activateOn().accepts(Click.RIGHT)) {
            activate(Click.RIGHT);
        }
    }

    private void activate(Click with) {
        if (suspended || !screen().cursor()) return;

        // Read from the screen that was clicked - handling it may well have pushed another one.
        Screen clicked = screen();
        if (!clicked.click(cursorX(), cursorY, with)) return;

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
        if (!suspended && screen().cursor()) {
            screen().scroll(cursorX(), cursorY, direction);
        }
    }

    // ---- rendering ----

    private void paint() {
        Screen screen = screen();
        lastFrame = System.currentTimeMillis();
        screen.animator().clock(lastFrame);

        // Animations are resolved during layout, so an in-flight one needs a fresh pass each frame.
        if (screen.isDirty() || screen.animating()) {
            screen.layout(MapTextFont.INSTANCE, surface.bounds());
            screen.cursorMoved(cursorX(), cursorY);
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

    /** The screen's own markers, plus the pointer - which is just another marker. */
    private List<Marker> markers() {
        List<Marker> markers = new ArrayList<>(screen().markers());
        if (!suspended && screen().cursor()) {
            markers.add(new Marker(cursorType(), cursorX(), cursorY, (byte) 8, screen().cursorCaption()));
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
