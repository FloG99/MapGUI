package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a frame across threads has to be invisible in the result, and that is the only thing worth asserting
 * here - not the speed, which depends on the machine, but the byte-for-byte equality that makes the speed free.
 */
class FrameTracerTest {

    private static final int SIZE = 24;

    /** Terrain, plants and water, so that the bands differ from each other and from the sky. */
    private static TestWorld scene() {
        TestWorld world = new TestWorld()
                .texture("stone", TestWorld.solid(0xFF808080))
                .texture("grass", TestWorld.solid(0xFF60A040))
                .texture("leaf", TestWorld.halfClear(0xFF208020))
                .texture("water", TestWorld.solid(0x802040C0));

        for (int x = -12; x <= 12; x++) {
            for (int z = 2; z <= 24; z++) {
                int height = (x + z) % 5 == 0 ? 1 : 0;
                world.cube(x, height, z, "grass", BakedState.Alpha.OPAQUE);
                world.cube(x, height - 1, z, "stone", BakedState.Alpha.OPAQUE);
                if ((x * 7 + z) % 11 == 0) {
                    world.turnedPlane(x, height + 1, z, "leaf", 45);
                }
                if ((x * 3 + z * 5) % 13 == 0) {
                    world.fluid(x, height + 1, z, "water");
                }
            }
        }
        return world;
    }

    private static CameraView view() {
        return new CameraView(0.5, 3, 0.5, 0, 10, CameraView.DEFAULT_FOV, 48, true);
    }

    private static int[] rendered(TestWorld world, int threads, List<EntitySnapshot> entities) {
        int[] out = new int[SIZE * SIZE];
        try (FrameTracer tracer = new FrameTracer(world, threads)) {
            tracer.render(world, view(), entities, SIZE, SIZE, out);
        }
        return out;
    }

    @Test
    void everyThreadCountDrawsTheSameFrame() {
        TestWorld world = scene();
        int[] single = rendered(world, 1, List.of());

        for (int threads : new int[]{2, 3, 5, 8, 16}) {
            assertArrayEquals(single, rendered(world, threads, List.of()),
                    "a frame traced on " + threads + " threads must match the one traced on one");
        }
    }

    /** Entities are found by a second pass with its own depth bookkeeping, so they get their own check. */
    @Test
    void entitiesSurviveBeingSplitAcrossBands() {
        TestWorld world = scene().texture("skin", Texture.opaqueOf(64, 64, filledSkin()));
        List<EntitySnapshot> mobs = List.of(
                EntitySnapshot.player(0.5, 1, 6.5, 0, 0, 0, false, SkinLayers.ALL, "skin"),
                EntitySnapshot.player(-3.5, 1, 9.5, 90, 90, 0, false, SkinLayers.ALL, "skin"));

        assertArrayEquals(rendered(world, 1, mobs), rendered(world, 6, mobs));
    }

    /** A frame shorter than the pool must not hand a thread an empty band or the same row twice. */
    @Test
    void moreThreadsThanRowsIsStillOneFrame() {
        TestWorld world = scene();
        int[] out = new int[4 * 4];
        try (FrameTracer tracer = new FrameTracer(world, 16)) {
            tracer.render(world, view(), List.of(), 4, 4, out);
        }

        for (int pixel : out) {
            assertEquals(0xFF, pixel >>> 24, "every pixel of the frame should have been written");
        }
    }

    @Test
    void theThreadCountLeavesRoomForTheServer() {
        assertEquals(1, FrameTracer.threadsFor(1), "a single core still has to run the game");
        assertEquals(1, FrameTracer.threadsFor(2));
        assertEquals(2, FrameTracer.threadsFor(4));
        assertEquals(6, FrameTracer.threadsFor(8), "capped, so a big machine is not taken over");
        assertTrue(FrameTracer.threadsFor(64) <= 6);
    }

    private static int[] filledSkin() {
        int[] argb = new int[64 * 64];
        java.util.Arrays.fill(argb, 0xFFC08040);
        return argb;
    }
}
