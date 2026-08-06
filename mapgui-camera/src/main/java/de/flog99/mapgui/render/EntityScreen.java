package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each entity lands on screen, so a pixel only tests the ones that could cover it.
 *
 * <p>This is what keeps entities affordable without a spatial tree: cost tracks projected area, which the
 * framebuffer bounds, so twenty mobs in view are a few thousand box tests rather than twenty per ray. A tree
 * over the entities would only start to pay at a few hundred of them.
 */
final class EntityScreen {

    private static final int[] NONE = new int[0];

    private final List<EntitySnapshot> entities;
    private final int[] minX;
    private final int[] maxX;
    private final int[] minY;
    private final int[] maxY;
    private final int[][] rows;

    EntityScreen(List<EntitySnapshot> entities, CameraView view, int width, int height) {
        this.entities = entities;

        int count = entities.size();
        minX = new int[count];
        maxX = new int[count];
        minY = new int[count];
        maxY = new int[count];
        rows = new int[height][];

        List<List<Integer>> perRow = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            perRow.add(null);
        }

        double[] forward = new double[3];
        double[] right = new double[3];
        double[] up = new double[3];
        view.basis(forward, right, up);
        double tanHalf = Math.tan(Math.toRadians(view.fov()) / 2);

        for (int i = 0; i < count; i++) {
            EntitySnapshot entity = entities.get(i);
            if (!project(entity, view, forward, right, up, tanHalf, width, height, i)) {
                minX[i] = 1;
                maxX[i] = 0;
                continue;
            }

            for (int y = Math.max(0, minY[i]); y <= Math.min(height - 1, maxY[i]); y++) {
                if (perRow.get(y) == null) {
                    perRow.set(y, new ArrayList<>(2));
                }
                perRow.get(y).add(i);
            }
        }

        for (int y = 0; y < height; y++) {
            List<Integer> list = perRow.get(y);
            if (list == null) {
                rows[y] = NONE;
                continue;
            }

            int[] packed = new int[list.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = list.get(i);
            }
            rows[y] = packed;
        }
    }

    int[] row(int y) {
        return rows[y];
    }

    EntitySnapshot entity(int index) {
        return entities.get(index);
    }

    boolean covers(int index, int x, int y) {
        return x >= minX[index] && x <= maxX[index] && y >= minY[index] && y <= maxY[index];
    }

    /**
     * The pixel rect of the entity's bounding sphere, padded by a pixel.
     *
     * <p>A sphere rather than the eight corners of a rotated box: it cannot be too small, which is the only
     * error that matters here - a rect that is too tight clips an arm off, one that is slightly loose only costs
     * a few tests that miss.
     */
    private boolean project(EntitySnapshot entity, CameraView view, double[] forward, double[] right, double[] up,
                            double tanHalf, int width, int height, int index) {

        double reach = entity.reach();
        double centerX = entity.x();
        double centerY = entity.y() + entity.model().height() / 32.0 * entity.scale();
        double centerZ = entity.z();

        double toX = centerX - view.x();
        double toY = centerY - view.y();
        double toZ = centerZ - view.z();

        double depth = toX * forward[0] + toY * forward[1] + toZ * forward[2];
        if (depth <= 0.05) {
            // Behind the camera, or so close that the projection blows up.
            return depth > -reach && insideEverything(width, height, index);
        }

        double acrossAxis = toX * right[0] + toY * right[1] + toZ * right[2];
        double upAxis = toX * up[0] + toY * up[1] + toZ * up[2];

        double halfExtentAcross = reach / depth / tanHalf * (width / 2.0);
        double halfExtentUp = reach / depth / tanHalf * (height / 2.0);
        double screenX = (acrossAxis / depth / tanHalf + 1) * (width / 2.0);
        double screenY = (1 - upAxis / depth / tanHalf) * (height / 2.0);

        minX[index] = (int) Math.floor(screenX - halfExtentAcross) - 1;
        maxX[index] = (int) Math.ceil(screenX + halfExtentAcross) + 1;
        minY[index] = (int) Math.floor(screenY - halfExtentUp) - 1;
        maxY[index] = (int) Math.ceil(screenY + halfExtentUp) + 1;

        return maxX[index] >= 0 && minX[index] < width && maxY[index] >= 0 && minY[index] < height;
    }

    /** Standing inside the camera: no useful rect, so it is tested everywhere rather than dropped. */
    private boolean insideEverything(int width, int height, int index) {
        minX[index] = 0;
        maxX[index] = width - 1;
        minY[index] = 0;
        maxY[index] = height - 1;
        return true;
    }
}
