package de.flog99.mapgui.render;

/**
 * Which color multiplies a face, as an index the world resolves - or, for a few blocks, does not have to.
 *
 * <p>A model states only {@code tintindex: 0}, and the client decides what that means from the block: grass_block
 * gets the grass color, oak leaves get the foliage one, spruce leaves get neither and take a fixed green. So the
 * number in the json is not enough on its own, and {@link BlockModels} rewrites it into one of these while it has
 * the block id in hand. Without that step leaves are drawn in the grass color, which is a different green in every
 * biome and the wrong one in all of them.
 *
 * <p>The indices past {@link #WATER} are ours rather than the format's. They start well clear of anything a model
 * can state, because vanilla models do use index 1 - a flowerbed's leaves - and an overlap would have quietly
 * tinted petals with pond water.
 */
public final class Tints {

    /** Drawn as the texture is. */
    public static final int NONE = -1;

    /** What a model means by index 0 most of the time, and what an unrecognized tinted block falls back to. */
    public static final int GRASS = 0;

    /** Fluids, which have no model to state anything. */
    public static final int WATER = 16;

    public static final int FOLIAGE = 17;

    /** The pale, dead greens: leaf litter and the pale garden. */
    public static final int DRY_FOLIAGE = 18;

    /** Below this the world answers; at or above it the answer is the same everywhere. */
    private static final int FIRST_FIXED = 32;

    public static final int EVERGREEN = 32;
    public static final int BIRCH = 33;
    public static final int LILY_PAD = 34;
    public static final int REDSTONE = 35;

    /** In vanilla order from {@link #FIRST_FIXED}. */
    private static final int[] FIXED = {
            0xFF619961,
            0xFF80A755,
            0xFF208030,
            0xFF8F0000
    };

    private Tints() {
    }

    /**
     * The color for an index that does not depend on where the block is, or 0 for one that does.
     *
     * <p>Zero rather than white as the "ask the world" answer, since a real tint always has full alpha and so can
     * never be mistaken for it.
     */
    public static int fixed(int index) {
        int at = index - FIRST_FIXED;
        return at >= 0 && at < FIXED.length ? FIXED[at] : 0;
    }

    /**
     * What a sheep's fleece is multiplied by for a dye, or 0 for a name that is not one.
     *
     * <p>Here because there is one wool texture in the assets and it is white: vanilla colors the fleece per animal
     * rather than shipping sixteen textures, so this is the only place the color can come from.
     *
     * <p>These are the client's own numbers and not the dye's own color, which is the trap. A dye has three colors
     * in vanilla - a map color, a firework color and a texture color - and the client's sheep table takes the third
     * and then multiplies it by 0.75, flooring each channel; white is special-cased to a flat {@code E6E6E6} rather
     * than being darkened at all. Read a dye's plain color instead and every sheep comes out a shade too bright,
     * white worst of all.
     */
    public static int wool(String dye) {
        return switch (dye) {
            case "white" -> 0xFFE6E6E6;
            case "orange" -> 0xFFBA6015;
            case "magenta" -> 0xFF953A8D;
            case "light_blue" -> 0xFF2B86A3;
            case "yellow" -> 0xFFBEA22D;
            case "lime" -> 0xFF609517;
            case "pink" -> 0xFFB6687F;
            case "gray" -> 0xFF353B3D;
            case "light_gray" -> 0xFF757571;
            case "cyan" -> 0xFF107575;
            case "purple" -> 0xFF66258A;
            case "blue" -> 0xFF2D337F;
            case "brown" -> 0xFF623F25;
            case "green" -> 0xFF465D10;
            case "red" -> 0xFF84221C;
            case "black" -> 0xFF151518;
            default -> 0;
        };
    }
}
