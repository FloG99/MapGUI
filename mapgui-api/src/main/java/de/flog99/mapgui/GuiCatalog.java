package de.flog99.mapgui;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The GUIs an admin can reach by name, with {@code /mapgui hand open} and {@code /mapgui wall place}.
 *
 * <p>A GUI rather than a screen, because one entry can be several {@link Screen}s deep: a shop that pushes a
 * category and then an item is three screens and one GUI. What is registered is where it starts.
 *
 * <p>Registering here gets your GUI an admin-facing command for nothing. It is not how ordinary players reach
 * one - that stays yours, through a command, an item, an NPC, whatever suits, calling {@link MapGui#open}. This
 * is administration: looking at a GUI on a test server, and hanging one on a wall without you having written
 * the placing, sizing and saving of it.
 *
 * <p>One name is one GUI, which may work in one place or both. Registering the same name for the other surface
 * adds to the same entry rather than making a second, so {@link #unregister} takes it out of everywhere in one
 * call - which matters because that call lives in {@code onDisable}, and a missed entry points at classes that
 * are about to be unloaded. The second call's <b>description wins</b> for both surfaces, since an entry has one
 * of them: pass the same string twice unless you mean to replace it.
 *
 * <p>Only names are held, so entries are registered again on every startup.
 *
 * <p>Registering and reading are safe from any thread. {@link #unregister} is not - see its own note.
 */
public interface GuiCatalog {

    /**
     * One name, and whichever surfaces it works on.
     *
     * <p>At least one of the two is set. Both are when the GUI suits either place.
     */
    record Entry(String name, String description,
                 @Nullable Function<Player, Screen> open,
                 @Nullable Consumer<WallDisplay.Builder> place) {

        public boolean openable() {
            return open != null;
        }

        public boolean placeable() {
            return place != null;
        }
    }

    /**
     * Offers this GUI to {@code /mapgui hand open}, in a player's hand.
     *
     * <p>A factory rather than a screen, because state lives on the screen: one instance handed to two players
     * would show each of them the other's scroll position. It is called once per open, with whoever is being
     * given it - ignore the player if the GUI does not care. What it returns is the screen to start at.
     *
     * @throws IllegalArgumentException if the name is already openable, or is not lowercase letters, digits,
     *         - or _
     */
    void registerOpenable(String name, String description, Function<Player, Screen> factory);

    /**
     * Offers this GUI to {@code /mapgui wall place}, on a grid of maps hung on blocks.
     *
     * <p>{@code onto} gets a builder already positioned, sized and tuned from MapGUI's config, and decides what
     * it shows - {@link WallDisplay.Builder#screenForEveryone}, {@link WallDisplay.Builder#screenPerPlayer} or
     * {@link WallDisplay.Builder#content}. It may also narrow the sizes it works at, and override {@code fps}
     * and {@code range}.
     *
     * <p>Called once per wall and again on every step of a resize, so keep anything expensive outside it. A
     * screen built inside gets fresh state per wall; one captured from a field is shared by all of them.
     *
     * @throws IllegalArgumentException if the name is already placeable, or is not lowercase letters, digits,
     *         - or _
     */
    void registerPlaceable(String name, String description, Consumer<WallDisplay.Builder> onto);

    /**
     * Takes a name out of both surfaces, and closes whatever was showing it.
     *
     * <p>Call it from {@code onDisable}: GUIs open in a hand are closed and walls showing one come down, while
     * your classes are still loaded. A placed wall stays in {@code walls.yml} and comes back when your plugin
     * does.
     *
     * <p><b>Main thread only.</b> Closing those GUIs touches the server, and it has to finish before this
     * returns - deferring it to the next tick would be no use to a caller that is about to be unloaded, which
     * is the whole reason to call this at all.
     */
    boolean unregister(String name);

    @Nullable
    Entry get(String name);

    /** What {@code /mapgui hand open} lists, in registration order. */
    Collection<Entry> openable();

    /** What {@code /mapgui wall place} lists, alongside the video files in MapGUI's own folder. */
    Collection<Entry> placeable();
}
