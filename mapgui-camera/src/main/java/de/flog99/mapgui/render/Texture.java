package de.flog99.mapgui.render;

/**
 * One decoded texture, as packed ARGB.
 *
 * <p>{@code int[]} rather than a {@code BufferedImage}: sampling happens per pixel per ray, and going through
 * {@code getRGB} for that costs a method call and a color model lookup each time.
 *
 * @param average ARGB averaged over every pixel that is not fully transparent, which is what a block further
 *                away than a pixel is wide should be drawn as. At map resolution a 16x16 texture is below one
 *                pixel by about 15 blocks out, so this is not a compromise so much as the correct answer.
 */
public record Texture(int width, int height, int[] argb, BakedState.Alpha alpha, int average) {

    /** For a texture that arrives already decoded from somewhere other than an asset pack, such as a skin. */
    public static Texture opaqueOf(int width, int height, int[] argb) {
        return new Texture(width, height, argb, BakedState.Alpha.CUTOUT, 0xFF808080);
    }

    /**
     * Samples at model coordinates - sixteenths of a block, as the json states them - with the face rotation
     * applied.
     *
     * <p>Wrapped rather than clamped, because several vanilla models state uv outside 0 to 16 and rely on it
     * repeating.
     */
    int sample(float u, float v, int rotation) {
        int x = Math.floorMod((int) (turnedU(u, v, rotation) * width / 16f), width);
        int y = Math.floorMod((int) (turnedV(u, v, rotation) * height / 16f), height);
        return argb[y * width + x];
    }

    /**
     * Where a face's own {@code rotation} sends a stated coordinate: the texture turns and the face does not.
     *
     * <p>Two scalars rather than a pair, so that {@link #sample} can apply it without allocating - it runs once per
     * ray per face. Public to the package because an entity face carries corner UVs and no rotation to pass on, so
     * whatever bakes one has to apply this itself, and a second copy of it is a second copy that can disagree.
     */
    static float turnedU(float u, float v, int rotation) {
        return switch (rotation) {
            case 90 -> v;
            case 180 -> 16 - u;
            case 270 -> 16 - v;
            default -> u;
        };
    }

    static float turnedV(float u, float v, int rotation) {
        return switch (rotation) {
            case 90 -> 16 - u;
            case 180 -> 16 - v;
            case 270 -> u;
            default -> v;
        };
    }
}
