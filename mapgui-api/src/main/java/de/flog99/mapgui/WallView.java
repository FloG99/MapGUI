package de.flog99.mapgui;

import de.flog99.mapgui.media.Frames;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.TextFont;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * One surface and whatever draws into it - a video, or a screen stack.
 *
 * <p>A shared wall has one of these; a per-player wall has one each. That is the <i>only</i> difference
 * between the two modes, which is why neither painting nor sending has to know which it is.
 *
 * <p>Two surfaces because a wall has nothing underneath it: anything the content does not cover would keep
 * the last frame's pixels forever. Wiping the surface that gets <i>sent</i> would mark all of it dirty and
 * push a full frame every time, so the wipe happens on the canvas and only real changes cross over.
 */
final class WallView {

    private final MapSurface surface;
    private final MapSurface canvas;
    private final Painter painter;

    @Nullable
    private final WallSession session;
    @Nullable
    private final WallContent content;

    /** Painted last, over whatever the wall shows. Null unless somebody asked for one. */
    @Nullable
    private WallContent overlay;

    private long startedAt;
    private long lastPainted;

    /** Where the wall is, for terrain. Null when whatever it shows has no interest in the world. */
    @Nullable
    private Location center;
    @Nullable
    private MapSurface terrain;
    private int terrainScale;

    private WallView(WallLayout layout, @Nullable WallSession session, @Nullable WallContent content) {
        this.surface = new MapSurface(layout.pixelWidth(), layout.pixelHeight());
        this.canvas = new MapSurface(layout.pixelWidth(), layout.pixelHeight());
        this.painter = new Painter(canvas, MapColors.INSTANCE, MapTextFont.INSTANCE);
        this.session = session;
        this.content = content;
    }

    static WallView showing(WallLayout layout, WallContent content) {
        return new WallView(layout, null, content);
    }

    /** {@code owner} is null on a shared wall, which is what makes its {@code player()} conditional. */
    static WallView running(WallServices services, WallLayout layout, Screen screen, @Nullable Player owner) {
        return new WallView(layout, new WallSession(services, layout, screen, owner), null);
    }

    MapSurface surface() {
        return surface;
    }

    @Nullable
    WallSession session() {
        return session;
    }

    void startedAt(long now) {
        this.startedAt = now;
    }

    void overlay(@Nullable WallContent value) {
        this.overlay = value;
    }

    /** Lets go of whatever the screen was holding. Nothing draws from here afterwards. */
    void stop() {
        if (session != null) {
            session.stop();
        }
    }

    /** Told once, since a wall does not move. */
    void center(Location value) {
        this.center = value;
    }

    /**
     * Draws the next frame, if one is due.
     *
     * <p>Quantizing the clock rather than skipping sends is what makes a frame limit free: between steps the
     * content gets the same time, draws the same pixels, and nothing goes dirty.
     */
    void paint(long now, int intervalMs) {
        long step = intervalMs <= 0 ? now : now - Math.floorMod(now, intervalMs);
        if (step == lastPainted) return;

        lastPainted = step;
        if (session == null) {
            canvas.fill(Frames.TRANSPARENT);
            content.paint(painter, canvas.bounds(), step - startedAt);
        } else {
            paintScreen(now);
        }
        if (overlay != null) {
            overlay.paint(painter, canvas.bounds(), step - startedAt);
        }
        // Only pixels that really changed reach the sent surface, so the dirty rectangle stays honest.
        surface.copyFrom(canvas);
    }

    private void paintScreen(long now) {
        Screen screen = session.screen();
        screen.animator().clock(now);

        // Measured and drawn with the same font, which is the screen's to choose and can differ between the
        // screens of one session - so it is set per paint rather than when the painter was built.
        TextFont font = screen.font();
        painter.font(font);

        if (screen.isDirty() || screen.animating() || session.takeDirty()) {
            screen.layout(font, canvas.bounds());
        }
        screen.cursorMoved(session.cursorX(), session.cursorY());

        if (screen.terrain()) {
            drawTerrain(screen.blocksPerPixel());
        } else {
            // Filled rather than wiped clear: a menu wants its own background, not the blocks behind it.
            canvas.fill(MapColors.INSTANCE.index(screen.background()));
        }
        screen.paint(painter);
    }

    /**
     * The world around the wall, drawn once and kept.
     *
     * <p>A wall is bolted to the world, so the ground it shows never moves - one scan for the life of the
     * wall rather than one every few ticks, at the cost of not noticing someone rebuilding underneath it.
     */
    private void drawTerrain(int blocksPerPixel) {
        if (terrain == null || terrainScale != blocksPerPixel) {
            terrain = new MapSurface(canvas.width(), canvas.height());
            terrainScale = blocksPerPixel;
            TerrainRenderer.render(terrain, center, blocksPerPixel);
        }

        canvas.copyFrom(terrain);
    }
}
