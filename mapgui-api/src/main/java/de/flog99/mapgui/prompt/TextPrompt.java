package de.flog99.mapgui.prompt;

import net.kyori.adventure.text.Component;

import java.util.function.Predicate;

/**
 * What to ask the player for.
 *
 * <p>Providers that lack a capability ignore the fields they can't honour - an anvil has no title,
 * for instance - so a screen can always fill in everything and let the provider decide.
 */
public record TextPrompt(
        Component title,
        String initial,
        int maxLength,
        Predicate<String> valid) {

    public static TextPrompt of(String title) {
        return new TextPrompt(Component.text(title), "", 64, value -> true);
    }

    public TextPrompt initial(String value) {
        return new TextPrompt(title, value == null ? "" : value, maxLength, valid);
    }

    public TextPrompt maxLength(int value) {
        return new TextPrompt(title, initial, Math.max(1, value), valid);
    }

    public TextPrompt valid(Predicate<String> predicate) {
        return new TextPrompt(title, initial, maxLength, predicate);
    }

    public boolean accepts(String value) {
        return value != null && value.length() <= maxLength && valid.test(value);
    }
}
