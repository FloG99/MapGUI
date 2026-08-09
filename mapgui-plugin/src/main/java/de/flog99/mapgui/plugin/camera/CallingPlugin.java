package de.flog99.mapgui.plugin.camera;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Which plugin asked for a capture, read off the stack rather than passed in.
 *
 * <p>The camera is reached through an interface a plugin already holds, so an owner parameter would mean every
 * capture call ever written has to change to get a name in a report. The stack already says who called.
 *
 * <p>It is only ever a label. Getting it wrong costs one wrong name on one line - nothing here decides what a capture
 * does - which is what makes a guess the right shape of answer for it.
 */
final class CallingPlugin {

    /** Deep enough for a caller behind a helper or two of their own, and short enough to be free. */
    private static final int FRAMES = 16;

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    static final String UNKNOWN = "unknown";

    private CallingPlugin() {
    }

    /**
     * The first plugin on the stack that is not MapGUI itself, since the frames between a caller and here are ours.
     *
     * @param self MapGUI, whose own frames are what has to be skipped past
     */
    static String of(Plugin self) {
        return WALKER.walk(frames -> frames
                .limit(FRAMES)
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(CallingPlugin::providerOf)
                .filter(found -> found != null && found != self)
                .findFirst()
                .map(Plugin::getName)
                .orElse(UNKNOWN));
    }

    /**
     * Null for anything the server, the JDK or Brigadier owns, which is most of a stack. Every failure mode of this
     * is "not a plugin", and the answer to all of them is to keep walking.
     */
    private static Plugin providerOf(Class<?> type) {
        try {
            return JavaPlugin.getProvidingPlugin(type);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
