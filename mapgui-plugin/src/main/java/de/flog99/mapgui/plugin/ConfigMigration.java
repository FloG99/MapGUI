package de.flog99.mapgui.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings an older config.yml up to date in place, and says in one line what it changed.
 *
 * <p>Renaming a setting silently is the worst kind of break: {@code video.ffmpeg: true} stops taking effect,
 * nothing complains, and the first sign of it is a video that will not play weeks later. So a rename is paired
 * with a migration, and {@code config-version} at the top of the file is how a file that has not had one yet is
 * recognised. A file with no such key is version 0, which is every config.yml written before this existed.
 *
 * <p><b>The file is edited as text rather than loaded and re-saved.</b> Bukkit's YAML writer can carry comments
 * across a round trip, but not the blank lines and the indented, commented-out examples this config.yml is
 * mostly made of - and an admin's own notes are not ours to reflow. A rename is one line changed and one line
 * added, so doing it by hand is both smaller and honest about what it touched. It also makes the whole thing a
 * pure function of the file's text, which is what {@link #migrate} is, and testable without a server.
 *
 * <p>Failing to write is not fatal: {@link MapGuiConfig} and {@link de.flog99.mapgui.plugin.video.VideoLibraryLoader}
 * both still read the old key when the new one is absent, so a read-only config.yml keeps working and simply gets
 * offered the migration again next start.
 */
final class ConfigMigration {

    /** The version this MapGUI writes. Bump it, and add the arm to {@link #migrate}, whenever a key moves. */
    static final int CURRENT = 1;

    private static final String VERSION_KEY = "config-version";

    private static final List<String> VERSION_COMMENT = List.of(
            "# Which set of settings this file was written for. MapGUI keeps it up to date and moves anything it",
            "# renames, so an old file keeps working - there is nothing here to edit by hand.",
            "");

    /**
     * What a migrated file looks like.
     *
     * @param yaml    the file's new text, identical to the old one when there was nothing to do
     * @param from    the version the file was written for
     * @param changes what moved, in words an admin can match against their own file. Empty when only the
     *                version line was stamped on - which is the case for a file that never used the old keys
     */
    record Result(String yaml, int from, List<String> changes) {

        boolean changed(String before) {
            return !yaml.equals(before);
        }
    }

    /**
     * Migrates the text of a config.yml. Pure: no file is read and none is written.
     *
     * <p>Every arm is guarded by the version the file came from rather than by whether the old key is present,
     * so a file already at {@link #CURRENT} is never rewritten - an admin who deliberately keeps a
     * {@code video:} section of their own is left alone once their file has been through this.
     */
    static Result migrate(String yaml) {
        int from = versionOf(yaml);
        List<String> lines = new ArrayList<>(List.of(yaml.split("\n", -1)));
        List<String> changes = new ArrayList<>();

        if (from < 1) {
            // Both keys under it move together, which is why the section is renamed rather than each key: it
            // keeps the admin's comments attached to the settings they describe.
            if (rename(lines, "video", "media")) {
                changes.add("the video: section is now media:, so video.ffmpeg and video.streams are"
                        + " media.ffmpeg and media.streams");
            }
        }

        stamp(lines, CURRENT);
        return new Result(String.join("\n", lines), from, List.copyOf(changes));
    }

    /** The version at the top of the file, or 0 for a file written before there was one. */
    static int versionOf(String yaml) {
        for (String line : yaml.split("\n", -1)) {
            if (!line.startsWith(VERSION_KEY + ":")) continue;

            try {
                return Integer.parseInt(line.substring(VERSION_KEY.length() + 1).trim());
            } catch (NumberFormatException e) {
                // A hand-edited version line reads as ancient, so every migration is offered again. Each one
                // is a no-op on a file that already has the new keys, so that is the safe way round.
                return 0;
            }
        }
        return 0;
    }

    /**
     * Renames one top-level section or key, keeping whatever followed the colon.
     *
     * <p>Only column zero, so {@code walls.video-size} is not caught by a rename of {@code video} - it is a
     * different setting under a different parent and it stays exactly where it is.
     *
     * @return whether anything was renamed. False when the file has no such section, and also when it already
     *         has one by the new name: two sections of the same name is a YAML file that loads as one of them,
     *         and guessing which the admin meant is worse than leaving both alone
     */
    private static boolean rename(List<String> lines, String from, String to) {
        int found = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(to + ":")) return false;
            if (line.startsWith(from + ":") && found < 0) {
                found = i;
            }
        }
        if (found < 0) return false;

        String rest = lines.get(found).substring(from.length() + 1);
        lines.set(found, to + ":" + rest);
        return true;
    }

    /** Writes the version line, adding it with its comment at the top of a file that has none. */
    private static void stamp(List<String> lines, int version) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith(VERSION_KEY + ":")) continue;

            // The carriage return of a CRLF file lives at the end of the line, so it is kept rather than
            // rewritten - a file that was CRLF stays CRLF and does not come back as a diff in every line.
            String carriage = lines.get(i).endsWith("\r") ? "\r" : "";
            lines.set(i, VERSION_KEY + ": " + version + carriage);
            return;
        }
        lines.addAll(0, VERSION_COMMENT);
        lines.add(VERSION_COMMENT.size() - 1, VERSION_KEY + ": " + version);
    }

    /**
     * Migrates the file on disk if it needs it.
     *
     * <p>Written to a temporary file beside it and moved into place, so an interrupted start cannot leave half a
     * config.yml where the whole one was.
     *
     * @return what changed, for the caller to log, or null when the file was already current or unreadable
     */
    static Result apply(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return null;

        String before = Files.readString(file, StandardCharsets.UTF_8);
        Result result = migrate(before);
        if (!result.changed(before)) return null;

        Path temp = file.resolveSibling(file.getFileName() + ".migrating");
        try {
            Files.writeString(temp, result.yaml(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return result;
    }

    private ConfigMigration() {
    }
}
