package de.flog99.mapgui;

import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.TextField;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

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
     * Whether the screen has the player's mouse: a cursor is drawn, their head moves it, and their clicks are
     * swallowed rather than reaching the world.
     *
     * <p>Always true for a popup. Everything else is focused in the main hand and follows
     * {@link HandOptions#focus()} in the offhand, so an unfocused screen is still painting and still being
     * looked at - it just is not being operated. A screen that only reads {@link Screen#cursor()} will see the
     * cursor go away, which is usually all it needs to know.
     */
    boolean focused();

    /**
     * Takes the mouse, or gives it back, whatever the carry mode would have decided.
     *
     * <p>For a screen that wants a focus rule of its own - a button on the map that hands control back, a quest
     * log that grabs it when something arrives. Holding sneak or swapping hands afterwards still works and
     * overrules this, since the player's own gesture should always win.
     */
    void focus(boolean focused);

    /**
     * How this session is being carried, which is what decided the answer to {@link #focused()} - or null for a
     * screen on a wall, which is furniture and is not carried at all.
     */
    @Nullable
    HandOptions hand();

    /**
     * Ask the player for text. The callback always runs on the main thread with the session
     * already resumed. {@code providerKey} may be null to use the server default.
     */
    void promptText(TextPrompt prompt, String providerKey, Consumer<Optional<String>> callback);

    /** Wired into every {@link TextField} the screen builds. */
    void edit(TextField field);
}
