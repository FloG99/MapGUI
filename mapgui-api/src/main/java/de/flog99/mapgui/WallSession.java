package de.flog99.mapgui;

import de.flog99.mapgui.prompt.PromptProvider;
import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.TextField;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One screen stack on a wall, and the {@link Session} it thinks it is running in.
 *
 * <p>There is one of these per viewer for a per-player wall, and exactly one for a shared wall.
 *
 * <p><b>{@link #player()} is the interesting difference.</b> A per-player wall has an obvious answer; a
 * shared one answers with whoever is interacting, and throws otherwise. A shared screen asking who its
 * player is while <i>painting</i> is a bug - the pixels go to everybody - and failing loudly beats quietly
 * drawing one person's data onto the room's wall.
 */
final class WallSession implements Session {

    private final WallServices services;
    private final WallLayout layout;

    /** The owner of a per-player wall, or null when the wall is shared. */
    @Nullable
    private final Player owner;

    /** Who is being served right now. Only ever set on a shared wall, and only during input. */
    @Nullable
    private Player acting;

    private final Deque<Screen> screens = new ArrayDeque<>();
    private int cursorX = -1;
    private int cursorY = -1;
    private final Set<UUID> prompting = new HashSet<>();
    private boolean dirty = true;

    /** A wall is opened by an admin or a plugin rather than by a caller with an opinion, so it starts unstated. */
    private OpenOptions presentation = OpenOptions.of();

    WallSession(WallServices services, WallLayout layout, Screen base, @Nullable Player owner) {
        this.services = services;
        this.layout = layout;
        this.owner = owner;
        push(base);
    }

    /** Runs something with a player attached, so a shared screen's handlers know who pressed the button. */
    void asActing(Player player, Runnable action) {
        Player previous = acting;
        acting = player;
        try {
            action.run();
        } finally {
            acting = previous;
        }
    }

    void cursorAt(int x, int y) {
        cursorX = x;
        cursorY = y;
    }

    boolean takeDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    // ---- Session ----

    /**
     * The viewer this belongs to, or on a shared wall whoever is interacting - null when nobody is.
     *
     * @throws IllegalStateException on a shared wall outside an input handler, where there is no answer
     */
    @Override
    public Player player() {
        if (owner != null) return owner;
        if (acting != null) return acting;

        throw new IllegalStateException("This wall is shared, so it has no single player. "
                + "player() only answers inside a click or hover handler - use screenPerPlayer for a "
                + "screen that needs to know who is looking at it."
        );
    }

    @Override
    public Screen screen() {
        return screens.peek();
    }

    @Override
    public int width() {
        return layout.pixelWidth();
    }

    @Override
    public int height() {
        return layout.pixelHeight();
    }

    @Override
    public void push(Screen screen) {
        screen.attach(this);
        screens.push(screen);
        invalidate();
    }

    /** Closing the last screen leaves the base one up - a wall is furniture, it does not go away. */
    @Override
    public void pop() {
        if (screens.size() <= 1) return;

        screens.pop().detach();
        invalidate();
    }

    @Override
    public void close() {
        while (screens.size() > 1) pop();
    }

    /**
     * Detaches every screen, including the base one, because the wall itself is going.
     *
     * <p>Not {@link #close()}, which leaves the base screen up - furniture does not vanish when you back out
     * of a submenu. Here the view is thrown away, so {@code onClose} has to run or a screen that registered
     * itself with something shared stays registered for good.
     */
    void stop() {
        while (!screens.isEmpty()) screens.pop().detach();
    }

    @Override
    public int cursorX() {
        return cursorX;
    }

    @Override
    public int cursorY() {
        return cursorY;
    }

    @Override
    public void invalidate() {
        dirty = true;
    }

    @Override
    public OpenOptions presentation() {
        return presentation;
    }

    /**
     * Restyles what is on the wall, for everyone looking at it on a shared one.
     *
     * <p>The pixels go to every viewer of a shared wall, so this is a change to the room rather than to one
     * person's view of it - which is what a wall is.
     */
    @Override
    public void presentation(UnaryOperator<OpenOptions> change) {
        presentation = change.apply(presentation);
        invalidate();
    }

    /** Per player rather than per wall: one person typing must not stop everyone else pressing buttons. */
    @Override
    public void suspend() {
        prompting.add(player().getUniqueId());
    }

    @Override
    public void resume() {
        prompting.remove(player().getUniqueId());
    }

    @Override
    public boolean suspended() {
        return owner != null ? prompting.contains(owner.getUniqueId())
                : acting != null && prompting.contains(acting.getUniqueId());
    }

    /**
     * Always. A wall is operated by walking up to it and clicking, so there is no holding it and nothing to take
     * the mouse away from - the cursor a viewer gets is the one they are pointing at the wall, not one on loan.
     */
    @Override
    public boolean focused() {
        return !suspended();
    }

    /** Nothing to give or take, so nothing happens. Kept rather than thrown, since a shared screen may ask blindly. */
    @Override
    public void focus(boolean focused) {
    }

    /** Null, and honestly so: a wall hangs on a block and nobody is carrying it. */
    @Override
    @Nullable
    public HandOptions hand() {
        return null;
    }

    /**
     * Asks for text, from whoever is doing the asking.
     *
     * <p>The player is captured now rather than looked up in the callback, because a prompt comes back long
     * after the click that opened it - by which time a shared wall has no idea who was interacting.
     */
    @Override
    public void promptText(TextPrompt prompt, String providerKey, Consumer<Optional<String>> callback) {
        Player target = player();
        PromptProvider provider = providerKey == null ? null : services.prompts().get(providerKey);
        if (provider == null) {
            provider = services.prompts().getDefault();
        }

        prompting.add(target.getUniqueId());
        provider.promptText(target, prompt).whenComplete((result, error) -> services.mainThread().execute(() -> {
            prompting.remove(target.getUniqueId());
            callback.accept(error != null || result == null ? Optional.empty() : result);
            invalidate();
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
}
