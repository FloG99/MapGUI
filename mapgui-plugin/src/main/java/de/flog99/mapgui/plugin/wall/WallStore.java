package de.flog99.mapgui.plugin.wall;

import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where the walls from {@code /mapgui wall place} are written down.
 *
 * <p>Why a player who joins next week sees one: nothing about a wall lived on anyone's client, so the file
 * is enough to send it to whoever comes into range.
 *
 * <p>Saved on every change rather than on shutdown, since a wall that survives a clean stop but not a crash
 * is worse than one never saved - you would not find out until the crash.
 */
final class WallStore {

    /** Everything needed to bring a wall back, and nothing that changes while it is up. */
    record Placed(UUID world, int x, int y, int z, BlockFace facing,
                  int cols, int rows, String content) {
    }

    private final Plugin plugin;

    /** In insertion order, so listing them twice gives the same answer twice. */
    private final Map<String, Placed> walls = new LinkedHashMap<>();

    WallStore(Plugin plugin) {
        this.plugin = plugin;
    }

    /** One bad entry costs a wall and a warning rather than every wall after it. */
    void load() {
        YamlConfiguration file = YamlConfiguration.loadConfiguration(storage());
        ConfigurationSection section = file.getConfigurationSection("walls");
        if (section == null) return;

        for (String name : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(name);
            if (entry == null) continue;

            try {
                walls.put(name, new Placed(
                        UUID.fromString(entry.getString("world", "")),
                        entry.getInt("x"), entry.getInt("y"), entry.getInt("z"),
                        BlockFace.valueOf(entry.getString("facing", "NORTH")),
                        entry.getInt("cols", 1), entry.getInt("rows", 1),
                        entry.getString("content", ""))
                );
            } catch (IllegalArgumentException e) {
                plugin.getSLF4JLogger().warn("Skipping wall '{}': {}", name, e.getMessage());
            }
        }
        plugin.getSLF4JLogger().info("Loaded {} map wall(s)", walls.size());
    }

    Map<String, Placed> all() {
        return Map.copyOf(walls);
    }

    Set<String> names() {
        return walls.keySet();
    }

    boolean has(String name) {
        return walls.containsKey(name);
    }

    void put(String name, Placed wall) {
        walls.put(name, wall);
        save();
    }

    boolean remove(String name) {
        if (walls.remove(name) == null) return false;

        save();
        return true;
    }

    private void save() {
        YamlConfiguration file = new YamlConfiguration();
        walls.forEach((name, wall) -> {
            ConfigurationSection entry = file.createSection("walls." + name);
            entry.set("world", wall.world().toString());
            entry.set("x", wall.x());
            entry.set("y", wall.y());
            entry.set("z", wall.z());
            entry.set("facing", wall.facing().name());
            entry.set("cols", wall.cols());
            entry.set("rows", wall.rows());
            entry.set("content", wall.content());
        });

        try {
            file.save(storage());
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("Could not save walls.yml", e);
        }
    }

    private File storage() {
        return new File(plugin.getDataFolder(), "walls.yml");
    }
}
