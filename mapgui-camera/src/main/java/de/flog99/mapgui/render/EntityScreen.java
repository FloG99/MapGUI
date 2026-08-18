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

        // Nearest first, so that every row's candidates come out in depth order - see nearestFirst.
        int[] order = nearestFirst(entities, view, forward);

        for (int slot = 0; slot < count; slot++) {
            int i = order[slot];
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

    /**
     * The entities in depth order, nearest first, so that the rows built from them are in that order too.
     *
     * <p>What it is for is the <b>limit</b>: once a ray has met an opaque texel, everything further along it can be
     * turned away by a bounding test instead of having its mesh walked, and that only works if the near thing is
     * reached first. Handed over in an arbitrary order - which is what {@code EntityCapture} produces, mobs then chests
     * then walls - the near one is found last and every far one has already been walked at the full distance.
     *
     * <p>Worth doing because of what a mirror's frame is. Five degrees across, everything in the tube overlaps
     * everything else, so this is the difference between one mesh walk a pixel and all of them: measured at 256x256
     * with twenty players down the axis, 0.8 us per ray nearest first against 2.4 us furthest first.
     *
     * <p>Costs one dot product each and an insertion sort over a list that is tens long, once per frame - against a
     * mesh walk per pixel. Insertion because that list is nearly sorted already: a capture gathers by chunk, outward
     * from the camera.
     *
     * <p>Nothing about the picture depends on it. {@link Fragments} sorts what it is given by depth however it arrives,
     * and only an opaque texel shortens the limit, so a translucent thing in front of a mob still gets both.
     */
    private static int[] nearestFirst(List<EntitySnapshot> entities, CameraView view, double[] forward) {
        int count = entities.size();
        int[] order = new int[count];
        double[] depths = new double[count];

        for (int i = 0; i < count; i++) {
            EntitySnapshot entity = entities.get(i);
            depths[i] = (entity.x() - view.x()) * forward[0]
                    + (entity.y() + entity.model().height() / 32.0 * entity.scale() - view.y()) * forward[1]
                    + (entity.z() - view.z()) * forward[2];
            order[i] = i;
        }

        for (int at = 1; at < count; at++) {
            int moving = order[at];
            double depth = depths[moving];
            int into = at - 1;
            while (into >= 0 && depths[order[into]] > depth) {
                order[into + 1] = order[into];
                into--;
            }
            order[into + 1] = moving;
        }
        return order;
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

        double halfExtentAcross;
        double halfExtentUp;
        double screenX;
        double screenY;

        CameraView.Lens lens = view.lens();
        if (lens.symmetric()) {
            // Untouched for every capture but a reflection, since these rects are floored into pixel bounds and a last
            // bit of difference can move an edge by one - which several tests here hold exactly.
            halfExtentAcross = reach / depth / tanHalf * (width / 2.0);
            halfExtentUp = reach / depth / tanHalf * (height / 2.0);
            screenX = (acrossAxis / depth / tanHalf + 1) * (width / 2.0);
            screenY = (1 - upAxis / depth / tanHalf) * (height / 2.0);
        } else {
            // The same mapping a ray is built with, inverted: sx runs from left to right across the picture and sy from
            // top to bottom, so a point at sx lands at (sx - left) / (right - left) of the way across.
            double across = lens.right() - lens.left();
            double down = lens.top() - lens.bottom();

            halfExtentAcross = reach / depth / across * width;
            halfExtentUp = reach / depth / down * height;
            screenX = (acrossAxis / depth - lens.left()) / across * width;
            screenY = (lens.top() - upAxis / depth) / down * height;
        }

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
