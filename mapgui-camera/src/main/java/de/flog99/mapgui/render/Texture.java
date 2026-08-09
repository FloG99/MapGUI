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
     * Samples at model coordinates - sixteenths of a block, as the json states them.
     *
     * <p>Wrapped rather than clamped, because several vanilla models state uv outside 0 to 16 and rely on it
     * repeating.
     *
     * <p>A face's own {@code rotation} does not appear here: it turns which corner of the stated rect lands on which
     * corner of the face, which {@link BlockModels} bakes into the rect itself.
     */
    int sample(float u, float v) {
        int x = Math.floorMod((int) (u * width / 16f), width);
        int y = Math.floorMod((int) (v * height / 16f), height);
        return argb[y * width + x];
    }
}
