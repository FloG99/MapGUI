package de.flog99.mapgui.plugin.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the camera has been doing lately, kept whether anybody is watching or not.
 *
 * <p>Pull rather than push. The report it feeds used to be a message sent to a player after each of their own
 * captures, which only works for the one shape of camera the sample plugin has - somebody aims and clicks. A plugin
 * that captures on a timer, for a live view, or for a player who is not the one asking, either floods a chat or
 * reports nothing at all. Counting them here answers the question whoever triggered them.
 *
 * <p>Split per plugin because the useful end of "the camera is expensive" is which plugin to turn down.
 */
final class CaptureLoad {

    private final CaptureWindow total = new CaptureWindow();
    private final Map<String, CaptureWindow> byPlugin = new ConcurrentHashMap<>();

    private volatile Failure last;

    /**
     * The last capture that threw, kept past the window.
     *
     * @param at when, in wall-clock millis, since this is read minutes later and a nanosecond reading means nothing
     *           across that
     */
    record Failure(String plugin, String reason, long at) {
    }

    /** A plugin and what it is asking for, for the line that says who to turn down. */
    record Share(String plugin, double perSecond) {
    }

    void captured(String plugin, long mainNanos) {
        total.captured(mainNanos);
        window(plugin).captured(mainNanos);
    }

    void traced(String plugin, long nanos) {
        total.traced(nanos);
        window(plugin).traced(nanos);
    }

    void failed(String plugin, Throwable cause) {
        total.failed();
        window(plugin).failed();
        last = new Failure(plugin, cause.toString(), System.currentTimeMillis());
    }

    CaptureWindow.Load read() {
        return total.read();
    }

    /** Busiest first, and only the ones capturing now - a plugin that took one an hour ago is not the answer. */
    List<Share> shares() {
        List<Share> shares = new ArrayList<>();
        for (Map.Entry<String, CaptureWindow> entry : byPlugin.entrySet()) {
            CaptureWindow.Load load = entry.getValue().read();
            if (load.captures() > 0) {
                shares.add(new Share(entry.getKey(), load.perSecond()));
            }
        }

        shares.sort(Comparator.comparingDouble(Share::perSecond).reversed());
        return shares;
    }

    Failure lastFailure() {
        return last;
    }

    /** Bounded by the number of plugins on the server, so nothing has to empty this. */
    private CaptureWindow window(String plugin) {
        return byPlugin.computeIfAbsent(plugin, ignored -> new CaptureWindow());
    }
}
