package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.List;

/**
 * An item's icon extruded into boxes along its own outline.
 *
 * <p>An item in a hand is a picture a pixel thick, and that pixel shows around the edge of the drawing rather than
 * around the edge of the file. One box wearing the whole 16x16 gets it wrong twice over: a sword's blade only
 * reaches its frame at the tip, and 347 of 26.2's 796 icons never touch their frame at all.
 *
 * <p>The client does the same in {@code ItemModelGenerator}, walking the sprite for runs of opaque texels and
 * emitting a quad per run per side. Here the runs become boxes, since a box is what a mesh is made of and carries
 * its own four rim faces for free.
 *
 * <p>Rows of identical runs are merged, which keeps a median icon to nine boxes and a sword to nineteen. Every face
 * of every box is drawn rather than only the outline ones, because an interior face cannot be reached - a ray has to
 * cross the opaque box in front of it first.
 */
final class SpriteShape {

    /**
     * How opaque a texel has to be to count as part of the picture.
     *
     * <p>The renderer's own threshold rather than a choice here - {@code EntityTracer} draws a texel at or above this
     * and passes through anything below it, so a box built on a different one would either have rim where nothing
     * draws or nothing where it does.
     */
    private static final int OPAQUE = 128;

    /** A run of opaque texels in a row: columns {@code from} up to {@code to}, the end exclusive. */
    private record Run(int from, int to) {
    }

    private SpriteShape() {
    }

    /**
     * The boxes one icon extrudes into, or a single box wearing the whole icon when there is nothing to read.
     *
     * <p>The fallback matters: an icon that is missing, or one whose pixels this cannot make sense of, still has to be
     * drawn as something, and the whole-frame quad is what it was drawn as before.
     *
     * @param size      how far across the picture is drawn, in the model's own sixteenths
     * @param thickness how deep, which is one texel of the sixteen
     */
    static List<MeshCube> of(Texture icon, float size, float thickness, float z) {
        List<Run>[] rows = runs(icon);
        if (rows == null) return List.of(MeshCube.sprite(0, 0, z, size, size, thickness));

        List<MeshCube> boxes = new ArrayList<>();
        int height = rows.length;
        int width = icon.width();

        for (int row = 0; row < height; row++) {
            if (rows[row].isEmpty()) {
                continue;
            }

            // How far down this band of identical rows reaches, so a straight edge is one box and not sixteen.
            int below = row + 1;
            while (below < height && rows[below].equals(rows[row])) {
                below++;
            }

            for (Run run : rows[row]) {
                boxes.add(box(run, row, below, width, height, size, thickness, z));
            }
            row = below - 1;
        }

        return boxes.isEmpty() ? List.of(MeshCube.sprite(0, 0, z, size, size, thickness)) : List.copyOf(boxes);
    }

    /**
     * One run of one band as a box.
     *
     * <p>Both axes run backwards against the texture, which is the frame this module draws in rather than anything
     * about the picture: a sprite is read from {@code -Z} with its u increasing towards {@code -X} and its v
     * downwards, so the icon's left edge is the box's {@code +X} side and its top edge the box's {@code +Y} one.
     */
    private static MeshCube box(Run run, int top, int bottom, int width, int height,
                               float size, float thickness, float z) {

        float u1 = (float) run.from() / width;
        float u2 = (float) run.to() / width;
        float v1 = (float) top / height;
        float v2 = (float) bottom / height;

        float x = size * (1 - u2);
        float y = size * (1 - v2);
        return MeshCube.sprite(x, y, z, size * (u2 - u1), size * (v2 - v1), thickness, u1, v1, u2, v2);
    }

    /**
     * The runs of opaque texels in each row of the icon, or null for a picture there is nothing to read in.
     *
     * <p>Only the first frame of an animated icon is walked, which is all the atlas hands over anyway - it crops a
     * strip to frame zero, so the height here is the frame's and not the file's.
     */
    private static List<Run>[] runs(Texture icon) {
        int width = icon.width();
        int height = icon.height();
        if (width <= 0 || height <= 0 || icon.argb().length < width * height) return null;

        // Raw on the right, since Java will not let a generic array be created any other way.
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<Run>[] rows = new List[height];
        for (int row = 0; row < height; row++) {
            rows[row] = runsIn(icon, row, width);
        }
        return rows;
    }

    private static List<Run> runsIn(Texture icon, int row, int width) {
        List<Run> runs = new ArrayList<>(1);

        int column = 0;
        while (column < width) {
            if (icon.argb()[row * width + column] >>> 24 < OPAQUE) {
                column++;
                continue;
            }

            int from = column;
            while (column < width && icon.argb()[row * width + column] >>> 24 >= OPAQUE) {
                column++;
            }
            runs.add(new Run(from, column));
        }
        return runs;
    }
}
