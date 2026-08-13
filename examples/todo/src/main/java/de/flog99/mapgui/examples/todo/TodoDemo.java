package de.flog99.mapgui.examples.todo;

import de.flog99.mapgui.MapGui;

/**
 * State, scrolling, text prompts and per-row closures.
 *
 * <p>The {@code register} call is the whole integration. For a real plugin the other half is {@link MapGui#open},
 * which is how <i>your</i> users get to a menu - from a command, an item, an NPC or a click on a block.
 */
public final class TodoDemo {

    private static final String NAME = "todo";

    public void register() {
        MapGui.get().guis().registerOpenable(NAME, "A to-do list - scrolling, prompts, per-row state", TodoScreen::new);
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
