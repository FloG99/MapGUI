package de.flog99.mapgui;

import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.TextField;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.Consumer;

/** One player's open menu: the screen stack, the cursor, and the display it renders to. */
public interface Session {

    Player player();

    Screen screen();

    /**
     * The canvas, in pixels - 128 square in the hand, and as big as its grid on a wall.
     *
     * <p>{@link Screen#width()} is the same numbers, and is what a screen should reach for.
     */
    int width();

    int height();

    /** Open a screen on top of the current one; closing it returns here. */
    void push(Screen screen);

    /** Close the top screen, or the whole session if it was the last one. */
    void pop();

    void close();

    /** Cursor position in surface pixels. */
    int cursorX();

    int cursorY();

    /** Repaint on the next tick. */
    void invalidate();

    /**
     * Stop feeding input to the screen. Used while a prompt is open so head movement and clicks
     * don't leak through to the menu behind it.
     */
    void suspend();

    void resume();

    boolean suspended();

    /**
     * Ask the player for text. The callback always runs on the main thread with the session
     * already resumed. {@code providerKey} may be null to use the server default.
     */
    void promptText(TextPrompt prompt, String providerKey, Consumer<Optional<String>> callback);

    /** Wired into every {@link TextField} the screen builds. */
    void edit(TextField field);
}
