package de.flog99.mapgui.plugin.camera;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a capture is allowed to draw has to agree with what the photographer was sent, and the server is the only
 * one who knows that. These are about the reading rather than the drawing.
 */
class TrackingRangesTest {

    /** The ceiling is what an admin will pay for; these tests raise it out of the way unless they are about it. */
    private static TrackingRanges shipped() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("players", 128);
        config.set("animals", 96);
        config.set("monsters", 96);
        config.set("misc", 96);
        config.set("display", 128);
        config.set("other", 64);

        return TrackingRanges.of(config, 512);
    }

    /**
     * The search has to cover the furthest of them, or a player at 115 blocks is never even looked at - and players
     * are tracked furthest of all, which is exactly the case somebody photographing a group cares about.
     */
    @Test
    void theSearchCoversTheFurthestTrackedCategory() {
        assertEquals(128 * TrackingRanges.MARGIN, shipped().widest(), 0.001);
    }

    /**
     * A margin off each, since an entity exactly at the edge is one the client may or may not hold depending on
     * which side of a step it was on. Leaving out a pixel beats drawing something nobody can see.
     */
    @Test
    void eachRangeIsTrimmedByTheMargin() {
        assertEquals(96 * 0.9, TrackingRanges.MARGIN * 96, 0.001);
        assertEquals(115.2, shipped().widest(), 0.001);
    }

    /** No spigot.yml, a fork that keeps its ranges elsewhere, or a category the file does not name. */
    @Test
    void anythingUnreadableLeavesTheCeilingStandingAlone() {
        assertEquals(TrackingRanges.DEFAULT_MAX, TrackingRanges.of((org.bukkit.configuration.ConfigurationSection) null, TrackingRanges.DEFAULT_MAX).widest(), 0.001);

        YamlConfiguration empty = new YamlConfiguration();
        assertEquals(TrackingRanges.DEFAULT_MAX, TrackingRanges.of(empty, TrackingRanges.DEFAULT_MAX).widest(), 0.001);
    }

    /** A zero or a negative is a server saying nothing useful, not a server asking for a camera that draws nothing. */
    @Test
    void aNonsenseRangeIsIgnoredRatherThanObeyed() {
        YamlConfiguration nonsense = new YamlConfiguration();
        nonsense.set("players", 0);
        nonsense.set("animals", -5);

        assertEquals(TrackingRanges.DEFAULT_MAX, TrackingRanges.of(nonsense, TrackingRanges.DEFAULT_MAX).widest(), 0.001);
    }

    /**
     * The whole of it: the two limits meet at whichever is nearer. The shipped ceiling is 64 and the shipped
     * tracking is 96, so a default server draws to 64 - and a server that tracks less than that pulls it in.
     */
    @Test
    void theNearerOfTheCeilingAndTheTrackedRangeWins() {
        assertEquals(64, TrackingRanges.of(all(96), 64).widest(), 0.001, "the ceiling is nearer, so it holds");
        assertEquals(32 * TrackingRanges.MARGIN, TrackingRanges.of(all(32), 64).widest(), 0.001,
                "a server that sends less than the ceiling pulls the camera in with it");
    }

    /**
     * A category the file does not name falls back to the ceiling rather than to nothing, so the search stays wide
     * enough for it - which is why these fixtures set every one of them.
     */
    private static YamlConfiguration all(int blocks) {
        YamlConfiguration config = new YamlConfiguration();
        for (String category : new String[]{"players", "animals", "monsters", "misc", "display", "other"}) {
            config.set(category, blocks);
        }
        return config;
    }

    /** A wider server means a wider search, since the whole point is to agree with it rather than to cap it. */
    @Test
    void aServerThatTracksFurtherIsFollowed() {
        assertEquals(256 * TrackingRanges.MARGIN, TrackingRanges.of(all(256), 512).widest(), 0.001);
    }
}
