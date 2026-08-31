package de.flog99.mapgui.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rename from {@code video.*} to {@code media.*}, which is the first thing {@code config-version} was built
 * for. What is being held here is that an admin's setting keeps taking effect - a silently renamed key is the
 * break this whole mechanism exists to prevent.
 */
class ConfigMigrationTest {

    private static final String OLD = """
            walls:
              # Longest edge videos are decoded at.
              video-size: 256

            video:
              # Play mp4 and live streams as well as GIFs.
              ffmpeg: true

              streams:
                lobby-cam: rtsp://10.0.0.5:554/stream1
            """;

    @Test
    void renamesTheSectionAndStampsTheVersion() {
        ConfigMigration.Result result = ConfigMigration.migrate(OLD);

        assertEquals(0, result.from(), "a file with no config-version is version 0");
        assertEquals(1, result.changes().size(), "one change, said once");
        assertTrue(result.yaml().startsWith("# Which set of settings"), "the version line arrives with its comment");
        assertEquals(1, ConfigMigration.versionOf(result.yaml()));
        assertTrue(result.yaml().contains("\nmedia:\n"));
        assertFalse(result.yaml().contains("\nvideo:\n"));
    }

    @Test
    void keepsCommentsAndEverythingElseAsItWas() {
        String migrated = ConfigMigration.migrate(OLD).yaml();

        assertTrue(migrated.contains("  # Play mp4 and live streams as well as GIFs."), "the admin's comments stay");
        assertTrue(migrated.contains("    lobby-cam: rtsp://10.0.0.5:554/stream1"), "and so do their values");
        // Under walls, and genuinely about video. A rename of the video section must not reach it.
        assertTrue(migrated.contains("  video-size: 256"));
        assertTrue(migrated.contains("  # Longest edge videos are decoded at."));
    }

    @Test
    void theSettingStillTakesEffectAfterwards() {
        YamlConfiguration migrated = load(ConfigMigration.migrate(OLD).yaml());

        MapGuiConfig config = MapGuiConfig.from(migrated);
        assertTrue(config.mediaFfmpeg(), "video.ffmpeg: true has to survive as media.ffmpeg: true");
        assertEquals("rtsp://10.0.0.5:554/stream1", config.streams().get("lobby-cam"));
    }

    @Test
    void theOldKeyIsStillReadWhenTheFileCouldNotBeMigrated() {
        // What a read-only config.yml looks like from the inside: the jar's config.yml is registered as the
        // defaults, so media.ffmpeg is always *present* - which is why MapGuiConfig asks isSet and not contains.
        YamlConfiguration admin = load(OLD);
        admin.setDefaults(load("media:\n  ffmpeg: false\n  streams: {}\n"));

        MapGuiConfig config = MapGuiConfig.from(admin);
        assertTrue(config.mediaFfmpeg(), "an unmigrated file must not quietly lose its setting");
        assertEquals("rtsp://10.0.0.5:554/stream1", config.streams().get("lobby-cam"));
    }

    @Test
    void theNewKeyWinsWhereBothAreSet() {
        YamlConfiguration both = load("media:\n  ffmpeg: false\nvideo:\n  ffmpeg: true\n");

        assertFalse(MapGuiConfig.from(both).mediaFfmpeg());
    }

    @Test
    void leavesAFileThatHasAlreadyBeenThroughItAlone() {
        String current = "config-version: 1\n\nvideo:\n  ffmpeg: true\n";

        ConfigMigration.Result result = ConfigMigration.migrate(current);
        assertEquals(1, result.from());
        assertFalse(result.changed(current), "an admin's own video: section is theirs once the file is current");
        assertEquals(current, result.yaml());
    }

    @Test
    void refusesToMakeTwoSectionsOfTheSameName() {
        String awkward = "media:\n  ffmpeg: false\nvideo:\n  ffmpeg: true\n";

        ConfigMigration.Result result = ConfigMigration.migrate(awkward);
        assertTrue(result.changes().isEmpty(), "renaming here would leave two media: keys, and YAML keeps one");
        assertTrue(result.yaml().contains("\nvideo:\n"));
        assertEquals(1, ConfigMigration.versionOf(result.yaml()), "still stamped, so it is not offered again");
    }

    @Test
    void stampsAFileThatUsedNeitherKey() {
        ConfigMigration.Result result = ConfigMigration.migrate("walls:\n  fps: 10\n");

        assertTrue(result.changes().isEmpty(), "nothing moved, so nothing is claimed");
        assertEquals(1, ConfigMigration.versionOf(result.yaml()));
        assertTrue(result.yaml().contains("walls:\n  fps: 10\n"));
    }

    @Test
    void aHandEditedVersionLineReadsAsAncient() {
        assertEquals(0, ConfigMigration.versionOf("config-version: soon\nvideo:\n  ffmpeg: true\n"));
    }

    @Test
    void leavesTheLineEndingsItFound() {
        String crlf = "config-version: 0\r\nvideo:\r\n  ffmpeg: true\r\n";

        String migrated = ConfigMigration.migrate(crlf).yaml();
        assertFalse(migrated.contains("\n\r"), "a CRLF file stays CRLF rather than coming back mixed");
        assertTrue(migrated.contains("config-version: 1\r\n"));
        assertTrue(migrated.contains("media:\r\n"));
    }

    private static YamlConfiguration load(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(new StringReader(yaml));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return config;
    }
}
