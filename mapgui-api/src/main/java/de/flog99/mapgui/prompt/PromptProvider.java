package de.flog99.mapgui.prompt;

import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Asks a player for a line of text.
 *
 * <p>There is no keyboard on a map, so text is the one thing that has to leave the screen.
 * Anything enumerable - numbers, choices, booleans - belongs in a widget instead, which is why
 * this interface deliberately handles only free text.
 *
 * <p>Implementations need not care about suspending the menu or hopping back to the main
 * thread; the framework does both around the call.
 */
public interface PromptProvider {

    enum Capability {
        /** Can show the prompt's title. */
        TITLE,
        /** Can pre-fill the current value. */
        PREFILL,
        /** Can reject invalid input before the player commits. */
        LIVE_VALIDATION,
        /** Not limited to roughly one short line. */
        LONG_TEXT
    }

    /** Completes empty when the player cancels. May complete on any thread. */
    CompletableFuture<Optional<String>> promptText(Player player, TextPrompt prompt);

    default Set<Capability> capabilities() {
        return EnumSet.noneOf(Capability.class);
    }

    /** Called when the session goes away mid-prompt. */
    default void cancel(Player player) {
    }
}
