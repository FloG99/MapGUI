package de.flog99.mapgui.plugin;

import de.flog99.mapgui.Screen;
import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.WallDisplay;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * What other plugins have offered an admin, in the order they offered it.
 *
 * <p>Synchronized because registering is public API and nothing stops a plugin doing it from an async task,
 * where an unguarded {@link LinkedHashMap} would not merely race but corrupt. Contention is beside the point -
 * these calls happen a handful of times at startup and once per command.
 */
final class GuiCatalogImpl implements GuiCatalog {

    /** No dots, so a name can never be mistaken for a video file - which is what lets one argument take either. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Closes whatever was showing an entry that has just gone, before its plugin finishes unloading. */
    private final Consumer<String> onRemoved;

    GuiCatalogImpl(Consumer<String> onRemoved) {
        this.onRemoved = onRemoved;
    }

    @Override
    public synchronized void registerOpenable(String name, String description, Function<Player, Screen> factory) {
        Entry existing = validated(name, description);
        if (existing != null && existing.openable()) {
            throw new IllegalArgumentException("Screen '" + name + "' is already openable");
        }
        entries.put(name, new Entry(name, description, factory, existing == null ? null : existing.place()));
    }

    @Override
    public synchronized void registerPlaceable(String name, String description, Consumer<WallDisplay.Builder> onto) {
        Entry existing = validated(name, description);
        if (existing != null && existing.placeable()) {
            throw new IllegalArgumentException("Screen '" + name + "' is already placeable");
        }
        entries.put(name, new Entry(name, description, existing == null ? null : existing.open(), onto));
    }

    /**
     * The entry this name already has, or null for a new one.
     *
     * <p>An existing name is not a clash - adding the other surface to it is how a screen says it works in
     * both places. Only the same surface twice is.
     */
    @Nullable
    private Entry validated(String name, String description) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Screen name '" + name + "' must be lowercase letters, digits, - or _");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Screen '" + name + "' needs a description - it is what the command lists");
        }
        return entries.get(name);
    }

    @Override
    public boolean unregister(String name) {
        synchronized (this) {
            if (entries.remove(name) == null) return false;
        }

        // Outside the lock on purpose: this closes screens and walls, which is a good deal of other people's
        // code, and holding a lock across it is how two subsystems taking their locks in the other order
        // deadlock. The entry is already gone, so nothing can find it while this runs.
        onRemoved.accept(name);
        return true;
    }

    @Override
    @Nullable
    public synchronized Entry get(String name) {
        return entries.get(name);
    }

    @Override
    public Collection<Entry> openable() {
        return matching(Entry::openable);
    }

    @Override
    public Collection<Entry> placeable() {
        return matching(Entry::placeable);
    }

    private synchronized List<Entry> matching(Predicate<Entry> surface) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (surface.test(entry)) {
                found.add(entry);
            }
        }
        return found;
    }
}
