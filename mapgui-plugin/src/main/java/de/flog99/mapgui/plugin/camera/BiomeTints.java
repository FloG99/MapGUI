package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.BiomeColors;
import de.flog99.mapgui.render.Tints;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Biome to tint, for the blocks whose textures are grey on disk.
 *
 * <p>The work is all in {@link BiomeColors}, which reads the biome's own definition out of the assets. This class
 * is the bridge from the server's {@code Biome} to the id that names one, and the cache that keeps a capture from
 * re-reading the same json thousands of times.
 *
 * <p>A datapack biome has no definition to read, so its climate is asked of the world instead - the server does
 * expose temperature and humidity, and those are the two numbers the colormap is indexed by, which is enough to
 * come out with the same green the client would. That has to happen on the main thread, so it is done during the
 * capture by {@link #learn} rather than lazily while tracing.
 */
final class BiomeTints {

    /** Only reached when a datapack biome's climate could not be read either, which should not happen. */
    private static final BiomeColors.Tint FALLBACK = new BiomeColors.Tint(0xFF91BD59, 0xFF77AB2F, 0xFFA3754A, 0xFF3F76E4, 0xFF050533, 0xFFC0D8FF, 96f);

    private final BiomeColors colors;

    /** Keyed by the biome object, which the server interns, so this stays as small as the biomes in view. */
    private final Map<Biome, BiomeColors.Tint> resolved = new ConcurrentHashMap<>();

    BiomeTints(BiomeColors colors) {
        this.colors = colors;
    }

    /**
     * Resolves one biome ahead of the trace, on the main thread.
     *
     * <p>Only does anything for a biome the assets do not describe, and only the first time it is seen. The block
     * position is needed because that is the only way to ask a world about a climate.
     */
    void learn(World world, Biome biome, int x, int y, int z) {
        if (biome == null || resolved.containsKey(biome)) return;

        BiomeColors.Tint known = colors.of(biome.getKey().value());
        resolved.put(biome, known != null
                ? known
                : colors.fromClimate((float) world.getTemperature(x, y, z), (float) world.getHumidity(x, y, z)));
    }

    private BiomeColors.Tint tintFor(Biome biome) {
        if (biome == null) return FALLBACK;

        BiomeColors.Tint known = resolved.get(biome);
        if (known != null) return known;

        // Not learned, so this biome was outside every column sampled during the capture. Its own definition is
        // still readable off-thread, and only a datapack biome can get past that.
        BiomeColors.Tint fromAssets = colors.of(biome.getKey().value());
        BiomeColors.Tint answer = fromAssets != null ? fromAssets : FALLBACK;
        resolved.put(biome, answer);
        return answer;
    }

    /**
     * @param index a {@link Tints} index the world has to answer
     * @return packed ARGB to multiply the face by
     */
    /** What water fogs everything to in this biome, for a camera that is under some. */
    int waterFogOf(Biome biome) {
        return tintFor(biome).waterFog();
    }

    /** And what the air itself is, which is the whole background where there is no sky. */
    int fogOf(Biome biome) {
        return tintFor(biome).fog();
    }

    /** How far a camera under this biome's water can see, in blocks. */
    float waterSightOf(Biome biome) {
        return tintFor(biome).waterSight();
    }

    int of(Biome biome, int index) {
        BiomeColors.Tint tint = tintFor(biome);

        return switch (index) {
            case Tints.GRASS -> tint.grass();
            case Tints.FOLIAGE -> tint.foliage();
            case Tints.WATER -> tint.water();
            case Tints.DRY_FOLIAGE -> tint.dryFoliage();
            default -> 0xFFFFFFFF;
        };
    }
}
