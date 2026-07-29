package de.flog99.mapgui.preview;

import de.flog99.mapgui.Screen;
import de.flog99.mapgui.Session;
import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.TextField;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A session with no player behind it. Navigation and prompts are real; {@link #player()} throws,
 * since a screen needing a live world can't be previewed.
 *
 * <p>Anything that would have affected the game is recorded instead, so the page can show what a
 * click would have done.
 */
final class PreviewSession implements Session {

    private final Deque<Screen> screens = new ArrayDeque<>();
    private final List<String> actions = new ArrayList<>();

    private int cursorX = -1;
    private int cursorY = -1;
    private boolean suspended;
    private Prompt prompt;

    record Prompt(String title, String initial, int maxLength, Consumer<Optional<String>> callback) {
    }

    PreviewSession(Screen root) {
        screens.push(root);
        root.attach(this);
    }

    @Override
    public Player player() {
        throw new UnsupportedOperationException("This screen needs a live player, so it can't be rendered in the preview.");
    }

    @Override
    public Screen screen() {
        return screens.peek();
    }

    @Override
    public int width() {
        return Preview.MAP_SIZE;
    }

    @Override
    public int height() {
        return Preview.MAP_SIZE;
    }

    @Override
    public void push(Screen screen) {
        record("push " + screen.getClass().getSimpleName());
        screens.push(screen);
        screen.attach(this);
    }

    @Override
    public void pop() {
        if (screens.size() <= 1) {
            record("close (last screen)");
            return;
        }

        record("pop " + screens.peek().getClass().getSimpleName());
        screens.pop().detach();
        screen().invalidate();
    }

    @Override
    public void close() {
        record("close");
    }

    @Override
    public int cursorX() {
        return cursorX;
    }

    @Override
    public int cursorY() {
        return cursorY;
    }

    void cursor(int x, int y) {
        cursorX = x;
        cursorY = y;
    }

    @Override
    public void invalidate() {
        screen().invalidate();
    }

    @Override
    public void suspend() {
        suspended = true;
    }

    @Override
    public void resume() {
        suspended = false;
    }

    @Override
    public boolean suspended() {
        return suspended;
    }

    @Override
    public void promptText(TextPrompt request, String providerKey, Consumer<Optional<String>> callback) {
        String title = PlainTextComponentSerializer.plainText().serialize(request.title());
        record("prompt \"" + title + "\"" + (providerKey == null ? "" : " via " + providerKey));

        prompt = new Prompt(title, request.initial(), request.maxLength(), callback);
        suspend();
    }

    @Override
    public void edit(TextField field) {
        TextPrompt request = TextPrompt.of(field.title())
                .initial(field.value())
                .maxLength(field.maxLength());

        promptText(request, field.promptKey(), result -> result.ifPresent(value -> {
            field.accept(value);
            invalidate();
        }));
    }

    Prompt pendingPrompt() {
        return prompt;
    }

    /** Answers the open prompt; a null value means the player canceled. */
    void answerPrompt(String value) {
        Prompt waiting = prompt;
        if (waiting == null) return;

        prompt = null;
        resume();
        record(value == null ? "prompt canceled" : "prompt answered \"" + value + '"');
        waiting.callback().accept(Optional.ofNullable(value));
    }

    List<String> actions() {
        return actions;
    }

    private void record(String action) {
        actions.add(action);
        // Only the tail is ever shown, and this outlives many interactions.
        if (actions.size() > 50) {
            actions.remove(0);
        }
    }
}
