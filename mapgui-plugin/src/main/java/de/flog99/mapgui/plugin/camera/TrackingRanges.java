package de.flog99.mapgui.plugin.camera;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;

/**
 * How far this server sends entities to a client, so that a capture draws the same ones the photographer can see.
 *
 * <p>A ceiling and a truth, meeting at whichever is nearer. {@code camera.max-entity-distance} is what an admin will
 * pay to draw, since every entity in range is a part tree to build; the server's own tracking range is what the
 * photographer's client was actually sent. The smaller of the two means a capture can never hold a mob that is not
 * on their screen, and never costs more than the ceiling allows however far the server tracks.
 *
 * <p>It used to be the ceiling alone, at a flat 64, under a comment guessing that was "roughly where the client stops
 * sending them". It is not - the shipped ranges are 96 for mobs and 128 for players - so the guess was right about
 * what it should cost and wrong about why.
 *
 * <p>Trimmed by {@link #MARGIN} rather than taken flat. An entity exactly at the tracking edge is one the client may
 * or may not have been sent depending on which side of a step it was on when the packet went out, and a photograph
 * that includes something the photographer cannot see is a worse failure than one that leaves out something at the
 * very limit of visibility - at that distance it is a pixel either way.
 *
 * <p>Per category, because the server is: a player at a hundred blocks is visible and a cow at a hundred blocks is
 * not, and drawing them by one rule would get one of the two wrong.
 */
public final class TrackingRanges {

    /** How much is taken off each configured range, so nothing is drawn that the client might not have. */
    static final double MARGIN = 0.9;

    /**
     * The shipped ceiling, and what a category falls back to when the server will not say.
     *
     * <p>Sixty-four blocks. Past that an entity is a couple of pixels on a map and every one of them is a part tree
     * to build, so this is a cost decision rather than a fidelity one - which is why it is a ceiling the server can
     * only lower and never raise.
     */
    public static final double DEFAULT_MAX = 64;

    /** Spigot's own names for the buckets it tracks by. */
    private static final String PLAYERS = "players";
    private static final String ANIMALS = "animals";
    private static final String MONSTERS = "monsters";
    private static final String MISC = "misc";
    private static final String DISPLAY = "display";
    private static final String OTHER = "other";

    private final ConfigurationSection ranges;
    private final double maxDistance;

    private TrackingRanges(ConfigurationSection ranges, double maxDistance) {
        this.ranges = ranges;
        this.maxDistance = maxDistance > 0 ? maxDistance : DEFAULT_MAX;
    }

    /**
     * The ranges for one world, or the defaults where it has none of its own.
     *
     * <p>Read out of {@code spigot.yml} because there is no API - the tracking ranges are not on {@link World}, and
     * the accessor that reaches the file is itself marked for removal with nothing to replace it. Suppressed rather
     * than worked around: the alternative is loading the file ourselves, which trades a compile warning for an
     * assumption about the server's working directory. When it does go, that is the fallback.
     *
     * <p>Everything here survives the section being absent, and then every category is the ceiling alone.
     */
    @SuppressWarnings("removal")
    static TrackingRanges of(World world, double maxDistance) {
        try {
            YamlConfiguration spigot = Bukkit.spigot().getSpigotConfig();
            ConfigurationSection perWorld = spigot.getConfigurationSection("world-settings." + world.getName() + ".entity-tracking-range");

            return new TrackingRanges(perWorld != null ? perWorld
                    : spigot.getConfigurationSection("world-settings.default.entity-tracking-range"), maxDistance);
        } catch (RuntimeException e) {
            return new TrackingRanges(null, maxDistance);
        }
    }

    /** Wound by hand in tests, and by anything that would rather state the ranges than have them found. */
    static TrackingRanges of(ConfigurationSection ranges, double maxDistance) {
        return new TrackingRanges(ranges, maxDistance);
    }

    /**
     * The furthest anything is tracked, which is how wide the search around the camera has to be.
     *
     * <p>One box query at the widest and then each entity judged by its own category, rather than a query per
     * category: the query is the cheap half and doing it five times to narrow it would cost more than it saves.
     */
    double widest() {
        double widest = 0;
        for (String category : new String[]{PLAYERS, ANIMALS, MONSTERS, MISC, DISPLAY, OTHER}) {
            widest = Math.max(widest, range(category));
        }
        return widest;
    }

    /** How far this particular entity is tracked, past which the photographer cannot see it either. */
    double forEntity(Entity entity) {
        return range(categoryOf(entity));
    }

    /**
     * Which bucket an entity falls in.
     *
     * <p>Coarser than the server's own sorting, which walks a class hierarchy. The three that differ in the shipped
     * configuration are players, mobs and displays, and those are the three worth being right about; everything else
     * lands in {@code misc} where the shipped value matches {@code animals} anyway.
     */
    private String categoryOf(Entity entity) {
        if (entity instanceof Player) return PLAYERS;
        if (entity instanceof Monster) return MONSTERS;
        if (entity instanceof Animals || entity instanceof WaterMob) return ANIMALS;
        if (entity instanceof Display) return DISPLAY;

        return MISC;
    }

    /**
     * The nearer of the two limits: what an admin will pay for, and what the server will send.
     *
     * <p>One is a cost and the other is the truth, so they meet at whichever is smaller. A server tracking mobs
     * further than the ceiling changes nothing, because the ceiling is what it costs to draw them. A server tracking
     * them <i>closer</i> pulls the camera in with it, which is the case worth having: drawing something the
     * photographer was never sent puts a mob in a photograph that is not on their screen.
     *
     * <p>A server that will not answer leaves the ceiling standing alone. That is the admin's own number, and
     * overriding it with a guess would be worse than taking it at its word.
     */
    private double range(String category) {
        if (ranges == null) return maxDistance;

        int configured = ranges.getInt(category, 0);
        return configured <= 0 ? maxDistance : Math.min(maxDistance, configured * MARGIN);
    }
}
