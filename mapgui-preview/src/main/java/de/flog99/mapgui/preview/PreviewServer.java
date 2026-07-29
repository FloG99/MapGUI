package de.flog99.mapgui.preview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.flog99.mapgui.MapColors;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves the current frame and feeds browser input back into the real screen.
 *
 * <p>JDK only. The page polls a version number and swaps the image when it changes - plenty for a
 * local dev loop, and a dropped connection heals itself.
 */
public final class PreviewServer {

    private static final int POLL_MS = 8;
    private static final int HEARTBEAT_MS = 10_000;

    private final PreviewState state;

    public PreviewServer(PreviewState state) {
        this.state = state;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        // Without an executor the JDK server handles everything on one thread, so a render in
        // progress blocks the next request behind it.
        // A streaming connection holds a thread for its lifetime, hence the headroom.
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/", exchange -> {
            if (!"/".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            respond(exchange, "text/html; charset=utf-8", page());
        });
        server.createContext("/frame.png", exchange -> respondCacheable(exchange, "image/png", state.frame()));
        server.createContext("/state", exchange -> reply(exchange, parseQuery(exchange.getRequestURI().getRawQuery())));
        server.createContext("/palette", exchange -> respondCacheable(exchange, "application/json; charset=utf-8", bytes(paletteJson())));
        server.createContext("/events", this::stream);
        server.createContext("/input", this::handleInput);
        // So stopping this never means hunting for a process id.
        server.createContext("/shutdown", exchange -> {
            respond(exchange, "text/plain; charset=utf-8", bytes("stopping"));
            new Thread(() -> {
                server.stop(0);
                System.exit(0);
            }, "mapgui-preview-shutdown").start();
        });

        server.start();
    }

    private void handleInput(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        switch (query.getOrDefault("type", "")) {
            case "move" -> state.moveCursor(number(query, "x"), number(query, "y"));
            case "click" -> state.click(number(query, "x"), number(query, "y"));
            case "scroll" -> state.scroll(number(query, "x"), number(query, "y"), number(query, "d"));
            case "prompt" -> state.answerPrompt(query.get("value"));
            case "cancel" -> state.answerPrompt(null);
            default -> {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
        }
        reply(exchange, query);
    }

    /**
     * State and the tree under the cursor in one response, so a mouse move costs one request and
     * the image is only refetched when its version actually moved.
     */
    private void reply(HttpExchange exchange, Map<String, String> query) throws IOException {
        int[] cursor = query.containsKey("x") && query.containsKey("y")
                ? new int[]{number(query, "x"), number(query, "y")}
                : null;

        respond(exchange, "application/json; charset=utf-8", bytes(state.responseJson(cursor)));
    }

    /**
     * Pushes frames as they are produced, rather than the page asking on a timer.
     *
     * <p>Only the rectangle that changed goes out, as raw palette indices - the browser writes them
     * straight into a canvas. No PNG is encoded or decoded either side, which is what a hover change
     * and an animation frame were both paying for before.
     */
    private void stream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        byte[] baseline = null;
        int sent = -1;
        long quiet = 0;

        try (OutputStream out = exchange.getResponseBody()) {
            while (true) {
                if (state.version() != sent) {
                    sent = state.version();
                    PreviewState.Delta delta = state.deltaAgainst(baseline);
                    baseline = state.pixelsCopy();
                    out.write(bytes("data: " + state.eventJson(delta) + "\n\n"));
                    out.flush();
                    quiet = 0;
                } else {
                    Thread.sleep(POLL_MS);
                    quiet += POLL_MS;
                }

                // A page that went away without closing is only noticed on a write, and this
                // connection is holding a thread until then.
                if (quiet >= HEARTBEAT_MS) {
                    out.write(bytes(": ping\n\n"));
                    out.flush();
                    quiet = 0;
                }
            }
        } catch (IOException | InterruptedException e) {
            // The page navigated away or reloaded; nothing to clean up.
        }
    }

    private static String paletteJson() {
        StringBuilder json = new StringBuilder(2048).append('[');
        for (int i = 0; i < 256; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(MapColors.INSTANCE.color((byte) i).getRGB() & 0xFFFFFF);
        }
        return json.append(']').toString();
    }

    private static int number(Map<String, String> query, String key) {
        try {
            return Integer.parseInt(query.getOrDefault(key, "-1"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;

        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;

            values.put(
                    URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8)
            );
        }
        return values;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] page() throws IOException {
        try (var stream = PreviewServer.class.getResourceAsStream("preview.html")) {
            if (stream == null) throw new IOException("preview.html is missing from the jar");
            return stream.readAllBytes();
        }
    }

    /** Frames are requested with a version in the query, so each one is safe to cache forever. */
    private static void respondCacheable(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "max-age=31536000, immutable");
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Rendering is input-driven, so an animation would stop after its first frame. This nudges it
     * along at whatever rate the screen's frame limit allows, and idles as soon as nothing is moving.
     */
    private static void startAnimationStepper(PreviewState state) {
        Thread stepper = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(state.animating() ? state.frameIntervalMs() : 150);
                } catch (InterruptedException e) {
                    return;
                }
                state.step();
            }
        }, "mapgui-preview-animator"
        );
        stepper.setDaemon(true);
        stepper.start();
    }

    /** args: class name, compiled-output directory, port, [backdrop png] */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PreviewServer <class> <classesDir> <port> [backdrop.png]");
            System.exit(2);
        }

        Path classes = Path.of(args[1]);
        PreviewState state = new PreviewState(
                new ScreenLoader(args[0], classes),
                Preview.readOrNull(args.length > 3 && !args[3].isBlank() ? Path.of(args[3]) : null),
                Preview.MAP_SIZE, Preview.MAP_SIZE
        );

        int port = Integer.parseInt(args[2]);
        state.reload();

        try {
            new PreviewServer(state).start(port);
        } catch (BindException e) {
            // Almost always a preview left running from last time, which is worth saying plainly
            // rather than dumping a stack trace at someone mid-loop.
            System.err.println("Port " + port + " is already in use."
                    + " Another preview is probably still running - stop it, or pass -Pport=" + (port + 1) + "."
            );
            System.exit(1);
        }

        ClassWatcher watcher = new ClassWatcher(classes, state::reload);
        watcher.start();
        startAnimationStepper(state);

        // Printed last, so it is the final thing on screen once the build output has scrolled by.
        String url = "http://127.0.0.1:" + port;
        String rule = "-".repeat(Math.max(34, url.length() + 18));
        System.out.println("\n" + rule
                + "\n  MapGUI preview   " + url
                + "\n  watching         " + classes
                + "\n  pid " + ProcessHandle.current().pid()
                + " - Ctrl+C, or GET " + url + "/shutdown"
                + "\n" + rule + "\n"
        );

        Thread.currentThread().join();
    }
}
