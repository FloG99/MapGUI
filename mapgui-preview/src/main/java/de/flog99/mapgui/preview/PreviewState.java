package de.flog99.mapgui.preview;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTextFont;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Animator;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URLClassLoader;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The live screen, its surface, and everything that happens to it. The instance persists between
 * frames so state sticks, and is only replaced when the class is recompiled.
 *
 * <p>Synchronized throughout: HTTP handlers arrive on a pool thread, and a screen is no more
 * thread-safe here than on a server.
 */
final class PreviewState {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int SCROLL_STEP = 1;

    private final ScreenLoader loader;
    private final BufferedImage backdrop;
    private final MapSurface surface;
    private final Painter painter;

    private URLClassLoader activeLoader;
    private PreviewSession session;

    /** Last frame's pixels, so an unchanged repaint doesn't bump the version. */
    private byte[] shown;
    private String error;
    private String note;
    private String renderedAt = "";
    private int version;

    PreviewState(ScreenLoader loader, BufferedImage backdrop, int width, int height) {
        this.loader = loader;
        this.backdrop = backdrop;
        this.surface = new MapSurface(width, height);
        this.painter = new Painter(surface, MapColors.INSTANCE, MapTextFont.INSTANCE);
    }

    // ---- lifecycle ----

    synchronized void reload() {
        ScreenLoader.Loaded loaded = loader.load();
        if (loaded.error() != null) {
            error = loaded.error();
            version++;
            stamp();
            return;
        }

        shown = null;

        URLClassLoader previous = activeLoader;
        activeLoader = loaded.loader();
        session = new PreviewSession(loaded.screen());
        error = null;

        render();

        // Only now that nothing references it: closing earlier would break lazily resolved lambdas.
        if (previous != null) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // A leaked loader in a dev tool is not worth handling.
            }
        }
    }

    // ---- input ----

    synchronized void moveCursor(int x, int y) {
        if (session == null) return;

        session.cursor(x, y);
        if (session.suspended()) return;
        if (session.screen().cursorMoved(x, y)) {
            render();
        }
    }

    synchronized void click(int x, int y) {
        if (session == null || session.suspended()) return;

        session.cursor(x, y);
        session.screen().cursorMoved(x, y);
        session.screen().click(x, y, Click.RIGHT);
        render();
    }

    synchronized void scroll(int x, int y, int direction) {
        if (session == null || session.suspended()) return;

        session.cursor(x, y);
        if (session.screen().scroll(x, y, direction > 0 ? SCROLL_STEP : -SCROLL_STEP)) {
            render();
        }
    }

    synchronized void answerPrompt(String value) {
        if (session == null) return;

        session.answerPrompt(value);
        render();
    }

    // ---- rendering ----

    private void render() {
        render(true);
    }

    private void render(boolean log) {
        Screen screen = session.screen();
        try {
            // Applied per frame rather than once, since a screen can push another one.
            screen.animator().loopFps(screen.loopFps() > 0 ? screen.loopFps() : Animator.DEFAULT_LOOP_FPS);
            screen.animator().clock(System.currentTimeMillis());
            if (screen.isDirty() || screen.animating()) {
                screen.layout(MapTextFont.INSTANCE, surface.bounds());
                // Laying out throws the tree away, so tell it where the cursor already is.
                screen.cursorMoved(session.cursorX(), session.cursorY());
            }

            if (backdrop != null) {
                Preview.paintBackdrop(surface, backdrop);
            } else {
                surface.fill(MapColors.INSTANCE.index(screen.background()));
            }
            screen.paint(painter);

            if (!Arrays.equals(surface.pixels(), shown)) {
                shown = surface.pixels().clone();
                version++;
            }
            error = null;
            note = screen.terrain() && backdrop == null
                    ? "This screen draws terrain - pass -Pbackdrop=<png> to stand in for it."
                    : null;
        } catch (Throwable e) {
            error = ScreenLoader.describe(e);
        }
        stamp(log);
    }

    private void stamp() {
        stamp(true);
    }

    private void stamp(boolean log) {
        renderedAt = LocalTime.now().format(CLOCK);
        if (!log) return;

        System.out.println(error == null
                ? "rendered " + loader.className() + " at " + renderedAt
                : "failed: " + error.lines().findFirst().orElse("")
        );
    }

    /** PNG of the current surface, for the one-shot renderer. Never used per frame. */
    synchronized byte[] frame() {
        try {
            return Preview.toPng(surface.toImage(MapColors.INSTANCE));
        } catch (IOException e) {
            return new byte[0];
        }
    }

    synchronized int version() {
        return version;
    }

    synchronized byte[] pixelsCopy() {
        return surface.pixels().clone();
    }

    /** The rectangle that differs from {@code baseline}, or null if nothing does. */
    record Delta(int x, int y, int width, int height, byte[] pixels) {
    }

    /** Smallest rectangle covering every pixel that differs. Each client keeps its own baseline, so two pages can watch at once. */
    synchronized Delta deltaAgainst(byte[] baseline) {
        byte[] pixels = surface.pixels();
        if (baseline == null) {
            return new Delta(0, 0, surface.width(), surface.height(), pixels.clone());
        }

        int width = surface.width();
        int minX = width;
        int minY = surface.height();
        int maxX = -1;
        int maxY = -1;

        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == baseline[i]) continue;

            int x = i % width;
            int y = i / width;
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
        }
        if (maxX < 0) return null;

        int deltaWidth = maxX - minX + 1;
        int deltaHeight = maxY - minY + 1;
        byte[] region = new byte[deltaWidth * deltaHeight];
        for (int row = 0; row < deltaHeight; row++) {
            System.arraycopy(pixels, (minY + row) * width + minX, region, row * deltaWidth, deltaWidth);
        }
        return new Delta(minX, minY, deltaWidth, deltaHeight, region);
    }

    /** What is under a point, innermost first - which answers "why is this three pixels off" far faster than the source does. */
    synchronized String inspectJson(int x, int y) {
        Node root = session == null ? null : session.screen().root();
        if (root == null) return "{\"path\":[]}";

        List<Node> path = new ArrayList<>();
        collectPath(root, x, y, path);

        // Outermost first, so the page can indent it as a tree.
        StringBuilder json = new StringBuilder("{\"path\":[");
        for (int i = 0; i < path.size(); i++) {
            Node node = path.get(i);
            Rect bounds = node.bounds();
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"type\":\"").append(node.getClass().getSimpleName()).append('"')
                    .append(",\"key\":\"").append(escape(node.key())).append('"')
                    .append(",\"x\":").append(bounds.x())
                    .append(",\"y\":").append(bounds.y())
                    .append(",\"w\":").append(bounds.width())
                    .append(",\"h\":").append(bounds.height())
                    .append(",\"interactive\":").append(node.interactive())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static void collectPath(Node node, int x, int y, List<Node> path) {
        if (node.hidden() || !node.bounds().contains(x, y)) return;

        path.add(node);
        List<Node> children = node.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            int before = path.size();
            collectPath(children.get(i), x, y, path);
            if (path.size() > before) return;
        }
    }

    // ---- state for the page ----

    /** The browser draws its own pointer, so a hover caption has to be reported separately. */
    private String caption() {
        return session == null ? null : session.screen().cursorCaption();
    }

    synchronized boolean animating() {
        return session != null && session.screen().animating();
    }

    /**
     * How long to wait before the next animation frame, resolved the same way the plugin resolves it -
     * a preview running smoother than the game would be lying about what people will see.
     */
    synchronized int frameIntervalMs() {
        if (session == null) return 1000 / Animator.MAX_FPS;

        Screen screen = session.screen();
        int interval = 1000 / (screen.fps() > 0 ? screen.fps() : Animator.MAX_FPS);
        return screen.animator().transitioning()
                ? interval
                : Math.max(interval, screen.animator().loopIntervalMs());
    }

    /** Draws one more frame if anything is still easing. Quiet, since it runs many times a second. */
    synchronized boolean step() {
        if (session == null || !session.screen().animating()) return false;

        render(false);
        return true;
    }

    /** State plus the changed rectangle, for the streaming connection. */
    synchronized String eventJson(Delta delta) {
        StringBuilder json = new StringBuilder(1024).append('{');
        if (delta != null) {
            json.append("\"delta\":{\"x\":").append(delta.x())
                    .append(",\"y\":").append(delta.y())
                    .append(",\"w\":").append(delta.width())
                    .append(",\"h\":").append(delta.height())
                    .append(",\"data\":\"").append(Base64.getEncoder().encodeToString(delta.pixels()))
                    .append("\"},");
        }
        return json.append(stateJson().substring(1)).toString();
    }

    synchronized String responseJson(int[] cursor) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        if (cursor != null) {
            json.append("\"inspect\":").append(inspectJson(cursor[0], cursor[1])).append(',');
        }
        return json.append(stateJson().substring(1)).toString();
    }

    synchronized String stateJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"version\":").append(version)
                .append(",\"target\":\"").append(escape(loader.className())).append('"')
                .append(",\"at\":\"").append(escape(renderedAt)).append('"')
                .append(",\"note\":\"").append(escape(note)).append('"')
                .append(",\"error\":\"").append(escape(error)).append('"')
                .append(",\"animating\":").append(animating())
                .append(",\"caption\":\"").append(escape(caption())).append('"');

        PreviewSession.Prompt prompt = session == null ? null : session.pendingPrompt();
        json.append(",\"prompt\":");
        if (prompt == null) {
            json.append("null");
        } else {
            json.append("{\"title\":\"").append(escape(prompt.title())).append('"')
                    .append(",\"initial\":\"").append(escape(prompt.initial())).append('"')
                    .append(",\"maxLength\":").append(prompt.maxLength()).append('}');
        }

        json.append(",\"actions\":[");
        if (session != null) {
            var actions = session.actions();
            int from = Math.max(0, actions.size() - 12);
            for (int i = from; i < actions.size(); i++) {
                if (i > from) {
                    json.append(',');
                }
                json.append('"').append(escape(actions.get(i))).append('"');
            }
        }
        return json.append("]}").toString();
    }

    private static String escape(String value) {
        if (value == null) return "";

        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> {}
                case '\t' -> out.append("    ");
                default -> out.append(ch < 0x20 ? ' ' : ch);
            }
        }
        return out.toString();
    }
}
