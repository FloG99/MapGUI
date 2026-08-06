package de.flog99.mapgui.render;

/**
 * The translucent surfaces one pixel looked through, composited at the end rather than as they arrive.
 *
 * <p>Blending on the fly would be simpler and is enough for blocks alone, since the voxel walk hands them over
 * nearest first. It is not enough for entities: a player standing behind glass is found by a separate pass that
 * does not know its depth until it runs, and has to land in the right place in the chain. Keeping a short list
 * and sorting it once is what makes that work without the two passes knowing about each other.
 *
 * <p>Eight slots because real scenes do not stack more than a few translucent surfaces, and a run of glass or
 * deep water has to stop somewhere - a ray that never terminates is how a glass corridor melts a frame budget.
 * One instance per rendering thread, reset per pixel, so tracing allocates nothing.
 */
final class Fragments {

    static final int MAX = 8;

    private final int[] argb = new int[MAX];
    private final float[] depth = new float[MAX];
    private int count;

    private float opaqueDistance = Float.MAX_VALUE;

    void reset() {
        count = 0;
        opaqueDistance = Float.MAX_VALUE;
    }

    /** Where the ray was stopped, so a later pass can skip anything behind it. */
    float opaqueDistance() {
        return opaqueDistance;
    }

    boolean isFull() {
        return count == MAX;
    }

    int count() {
        return count;
    }

    /**
     * @param distance how far along the ray, used only to order the list
     * @return false when the list is full, which the caller should treat as a reason to stop the ray
     */
    boolean add(int color, float distance) {
        if (count == MAX) return false;

        argb[count] = color;
        depth[count] = distance;
        count++;

        if ((color >>> 24) == 255 && distance < opaqueDistance) {
            opaqueDistance = distance;
        }
        return true;
    }

    /**
     * Everything blended over a background, nearest first.
     *
     * <p>Insertion sort because the list is at most eight long and usually one, which is the case where a
     * comparator-driven sort would cost more than the compositing does.
     */
    int composite(int background) {
        for (int i = 1; i < count; i++) {
            int color = argb[i];
            float distance = depth[i];
            int j = i - 1;
            while (j >= 0 && depth[j] > distance) {
                argb[j + 1] = argb[j];
                depth[j + 1] = depth[j];
                j--;
            }
            argb[j + 1] = color;
            depth[j + 1] = distance;
        }

        float red = 0;
        float green = 0;
        float blue = 0;
        float remaining = 1f;

        for (int i = 0; i < count; i++) {
            int color = argb[i];
            float alpha = (color >>> 24) / 255f;
            float weight = alpha * remaining;

            red += (color >> 16 & 0xFF) * weight;
            green += (color >> 8 & 0xFF) * weight;
            blue += (color & 0xFF) * weight;
            remaining -= weight;

            if (remaining <= 0.001f) {
                remaining = 0;
                break;
            }
        }

        red += (background >> 16 & 0xFF) * remaining;
        green += (background >> 8 & 0xFF) * remaining;
        blue += (background & 0xFF) * remaining;

        return 0xFF000000
                | Math.clamp(Math.round(red), 0, 255) << 16
                | Math.clamp(Math.round(green), 0, 255) << 8
                | Math.clamp(Math.round(blue), 0, 255);
    }
}
