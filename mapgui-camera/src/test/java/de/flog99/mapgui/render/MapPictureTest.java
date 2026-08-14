package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which way up a map is drawn, for the two pictures a capture paints itself: one hung in an item frame and one of a
 * MapGUI wall.
 *
 * <p>Worth a test of its own because there is nothing to compare against. Everything else in a capture reads a
 * texture out of the assets, where being upside down would show at once on a picture somebody drew; these two are
 * handed 16 KB of palette index whose only rule is the one a map update follows - {@code x + y * 128}, x to the
 * viewer's right and y down. Four differently coloured quadrants are the whole test: a picture mirrored or turned
 * puts one of them where another belongs, and each of the four wrong answers is a different failure.
 *
 * <p>Compared by which quadrant a pixel came from rather than by its colour, since what arrives at the pixel has
 * been through the light.
 */
class MapPictureTest {

    private static final int SIZE = 128;

    /** The four quadrants, as colours no two of which share a channel. */
    private static final int[] QUADRANTS = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00};

    private static final int TOP_LEFT = 0;
    private static final int TOP_RIGHT = 1;
    private static final int BOTTOM_LEFT = 2;
    private static final int BOTTOM_RIGHT = 3;

    /** The block the picture hangs on, well clear of anything the empty test world has in it. */
    private static final int BLOCK_X = 10;
    private static final int BLOCK_Y = 64;
    private static final int BLOCK_Z = 10;

    /** How wide the rendered probe is. Small, since four quadrants need four samples. */
    private static final int PROBE = 16;

    /** Well inside each quadrant rather than at the edge of the frame, which one block does not quite fill. */
    private static final int NEAR = PROBE / 4;

    private static final int FAR = PROBE - 1 - PROBE / 4;

    /**
     * A wall map read from the south, which is the plainest of the six faces: the viewer's right is world east and
     * the picture's own top is world up.
     */
    @Test
    void aWallMapIsDrawnTheWayItsPixelsAreRead() {
        int[] probe = render();

        assertEquals(TOP_LEFT, quadrantAt(probe, NEAR, NEAR), "the top left of the map is the top left of the picture");
        assertEquals(TOP_RIGHT, quadrantAt(probe, FAR, NEAR), "and its top right is the top right");
        assertEquals(BOTTOM_LEFT, quadrantAt(probe, NEAR, FAR), "its bottom left the bottom left");
        assertEquals(BOTTOM_RIGHT, quadrantAt(probe, FAR, FAR), "and its bottom right the bottom right");
    }

    /**
     * And the same for a map hanging in a real item frame, which is the same picture placed by a different chain -
     * the frame is an entity standing in front of the wall rather than a face of the block itself.
     */
    @Test
    void aFramedMapIsDrawnTheWayItsPixelsAreRead() {
        int[] probe = render(EntitySnapshot.framedMap(BLOCK_X + 0.5, BLOCK_Y + 0.5, BLOCK_Z + 0.5, SOUTH_FACE, NAME, 0));

        assertEquals(TOP_LEFT, quadrantAt(probe, NEAR, NEAR), "the top left of the map is the top left of the picture");
        assertEquals(TOP_RIGHT, quadrantAt(probe, FAR, NEAR), "and its top right is the top right");
        assertEquals(BOTTOM_LEFT, quadrantAt(probe, NEAR, FAR), "its bottom left the bottom left");
        assertEquals(BOTTOM_RIGHT, quadrantAt(probe, FAR, FAR), "and its bottom right the bottom right");
    }

    /** The whole probe, rendered once. */
    private static int[] render() {
        return render(EntitySnapshot.wallMap(BLOCK_X + 0.5, BLOCK_Y + 0.5, BLOCK_Z + 0.5, SOUTH_FACE, NAME));
    }

    private static int[] render(EntitySnapshot picture) {
        TestWorld world = new TestWorld().texture(NAME, Texture.opaqueOf(SIZE, SIZE, quartered()));

        List<EntitySnapshot> drawn = List.of(picture);

        // Square on to the face and near enough that the map is most of the frame.
        CameraView view = new CameraView(BLOCK_X + 0.5, BLOCK_Y + 0.5, BLOCK_Z + 2, 180, 0, 60, PROBE);

        int[] out = new int[PROBE * PROBE];
        new RayCaster(world).render(world, view, drawn, PROBE, PROBE, out);
        return out;
    }

    /** Which of the four quadrants a rendered pixel came from, or -1 for anything that is not one of them. */
    private static int quadrantAt(int[] probe, int x, int y) {
        int argb = probe[x + y * PROBE];
        boolean red = (argb >> 16 & 0xFF) > 0;
        boolean green = (argb >> 8 & 0xFF) > 0;
        boolean blue = (argb & 0xFF) > 0;

        if (red && !green && !blue) return TOP_LEFT;
        if (green && !red && !blue) return TOP_RIGHT;
        if (blue && !red && !green) return BOTTOM_LEFT;
        if (red && green && !blue) return BOTTOM_RIGHT;
        return -1;
    }

    /** A wall hung on the south face of its block, in the yaw convention {@code WallCapture} states one. */
    private static final float SOUTH_FACE = -180;

    private static final String NAME = "mapgui/test/quartered";

    private static int[] quartered() {
        int[] pixels = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean right = x >= SIZE / 2;
                boolean low = y >= SIZE / 2;
                pixels[x + y * SIZE] = QUADRANTS[low ? (right ? BOTTOM_RIGHT : BOTTOM_LEFT) : (right ? TOP_RIGHT : TOP_LEFT)];
            }
        }
        return pixels;
    }
}
