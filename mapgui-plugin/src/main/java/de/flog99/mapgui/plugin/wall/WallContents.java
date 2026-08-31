package de.flog99.mapgui.plugin.wall;

import de.flog99.mapgui.GuiCatalog;
import de.flog99.mapgui.WallContent;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.plugin.video.VideoNatives;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything {@code /mapgui wall place} can put up, from the two places it comes from: the screens plugins have
 * registered as placeable, and the videos folder.
 *
 * <p>Both end up as a change to a half-built wall, so nothing downstream knows which it got - which is what
 * lets one command place a video and another plugin's screen with the same preview and the same saved line.
 */
final class WallContents {

    private final GuiCatalog screens;
    private final VideoLibrary videos;

    WallContents(GuiCatalog screens, VideoLibrary videos) {
        this.screens = screens;
        this.videos = videos;
    }

    /** For tab completion. Registered screens first, since those are the ones nobody can guess. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        for (GuiCatalog.Entry entry : screens.placeable()) names.add(entry.name());
        names.addAll(videos.names());
        return names;
    }

    /** How to fill a wall with {@code name}, or null if nothing goes by it. */
    @Nullable
    Consumer<WallDisplay.Builder> find(String name) {
        GuiCatalog.Entry entry = screens.get(name);
        if (entry != null && entry.placeable()) return entry.place();

        return videos.place(name);
    }

    /**
     * One line each, clickable to start placing it.
     *
     * <p>Grouped, because the two halves are not the same kind of thing to an admin: the media are theirs to add
     * by dropping a file in, and the screens arrive with whatever plugins are installed.
     */
    List<Component> describe() {
        List<Component> lines = new ArrayList<>();

        List<Component> registered = new ArrayList<>();
        for (GuiCatalog.Entry entry : screens.placeable()) {
            registered.add(line(entry.name(), entry.description()));
        }
        if (!registered.isEmpty()) {
            lines.add(heading("Screens", "registered by plugins"));
            lines.addAll(registered);
        }

        List<String> files = videos.names();
        if (!files.isEmpty()) {
            lines.add(heading("Media", "plugins/MapGUI/videos"));
            for (String name : files) lines.add(line(name, "video"));
        }
        if (videos.needsFfmpeg() && !VideoNatives.available()) {
            lines.add(Component.text("    Video files and streams need ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("media.ffmpeg: true", NamedTextColor.GRAY))
                    .append(Component.text(" in config.yml and a restart.", NamedTextColor.DARK_GRAY)));
        }
        return lines;
    }

    private static Component heading(String what, String where) {
        return Component.text("  " + what + "  ", NamedTextColor.GRAY)
                .append(Component.text(where, NamedTextColor.DARK_GRAY));
    }

    private static Component line(String name, String description) {
        return Component.text("    " + name + "  ", NamedTextColor.WHITE)
                .append(Component.text(description, NamedTextColor.DARK_GRAY))
                .clickEvent(ClickEvent.runCommand("/mapgui wall place " + name))
                .hoverEvent(Component.text("Click to place " + name, NamedTextColor.GRAY));
    }

    /** Asking for something by name is a fresh chance: the file may have been dropped in since. */
    void forget(String name) {
        videos.forget(name);
    }

    /** Why a name that looked like media did not play, for a command to pass on rather than saying "no such thing". */
    @Nullable
    String problemWith(String name) {
        return videos.problemWith(name);
    }

    /** Lets go of the decoded frames of anything no longer wanted. Names that are screens are simply not videos. */
    int retainOnly(Set<String> wanted) {
        return videos.retainOnly(wanted);
    }

    /** Stops every decoder, for shutdown. */
    void close() {
        videos.close();
    }
}
