package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crossing a patch of columns at a time has to be free, and free means byte for byte.
 *
 * <p>The same argument as {@code EmptySkipTest}: a ray jumps on the strength of {@link VoxelSource#maxTopIn}, so
 * the only thing that settles it is the same scene traced with that answered and with it left to its default,
 * coming out as the same pixels. The scenes are the shapes a jump over the ground could plausibly get wrong -
 * a canopy standing above open ground, a cliff, a ray sinking toward the surface, and a mob in the air.
 *
 * <p>Every case also asserts the jump <i>happened</i>, by watching which patches were asked about. Two identical
 * frames prove nothing if the second one crossed the ground a column at a time.
 */
class HeightJumpTest {

    private static final int SIZE = 48;
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 128;

    /**
     * Ground at a height per column, with whatever else a test stands on top of it.
     *
     * <p>{@code patches} off makes {@code maxTopIn} answer as the interface default does, which is the unjumped
     * side of the comparison.
     */
    private static final class Ground implements VoxelSource, Textures {

        private final int reach;
        private final boolean patches;
        private final BakedState solid;
        private final Set<Long> standing = new HashSet<>();
        private final Set<Integer> asked = new HashSet<>();

        Ground(int reach, boolean patches) {
            this.reach = reach;
            this.patches = patches;
            BakedFace[] faces = new BakedFace[6];
            for (Direction side : Direction.values()) {
                faces[side.ordinal()] = new BakedFace("block", 0, 0, 16, 16, 0, BakedFace.NO_TINT, null);
            }
            this.solid = new BakedState(List.of(new BakedElement(0, 0, 0, 16, 16, 16, faces, true, 0, 0)),
                    false, BakedState.Alpha.OPAQUE);
        }

        /** Something standing clear of the ground, which is what a jump must not skip over. */
        Ground standing(int x, int y, int z) {
            standing.add(key(x, y, z));
            return this;
        }

        private static long key(int x, int y, int z) {
            return (long) (x & 0xFFFF) << 32 | (long) (y & 0xFFFF) << 16 | (z & 0xFFFF);
        }

        /** A slope with a cliff in the middle of it, so the ground is not one flat plane. */
        private int ground(int x, int z) {
            if (Math.abs(x) > reach || Math.abs(z) > reach) return MIN_Y - 1;

            return x > 6 ? 4 : x / 3;
        }

        @Override
        public BakedState stateAt(int x, int y, int z) {
            if (Math.abs(x) > reach || Math.abs(z) > reach || y < MIN_Y || y > MAX_Y) return BakedState.EMPTY;

            return y <= ground(x, z) || standing.contains(key(x, y, z)) ? solid : BakedState.EMPTY;
        }

        @Override
        public int columnTop(int x, int z) {
            int top = ground(x, z);
            for (int y = MAX_Y; y > top; y--) {
                if (standing.contains(key(x, y, z))) return y;
            }
            return top;
        }

        @Override
        public int maxTopIn(int x, int z, int shift) {
            if (!patches) return maxY();
            if (shift <= 0) return columnTop(x, z);
            if (shift > 3) return maxY();

            asked.add(shift);
            int size = 1 << shift;
            int top = MIN_Y - 1;
            for (int px = x & -size; px < (x & -size) + size; px++) {
                for (int pz = z & -size; pz < (z & -size) + size; pz++) {
                    top = Math.max(top, columnTop(px, pz));
                }
            }
            return top;
        }

        @Override
        public int highestBlock() {
            return MAX_Y;
        }

        @Override
        public int lightAt(int x, int y, int z) {
            return 15;
        }

        @Override
        public int tintAt(int x, int y, int z, int index) {
            return 0xFFFFFFFF;
        }

        @Override
        public Sky sky() {
            return Sky.flat(0xFF80A0FF);
        }

        @Override
        public int minY() {
            return MIN_Y;
        }

        @Override
        public int maxY() {
            return MAX_Y;
        }

        @Override
        public Texture get(String name) {
            return TestWorld.solid(0xFFB05030);
        }
    }

    private static int[] frame(Ground world, CameraView view) {
        int[] out = new int[SIZE * SIZE];
        new RayCaster(world).render(world, view, SIZE, SIZE, out);
        return out;
    }

    /** Builds the scene twice, traces it both ways, and insists on the same pixels and on a jump having happened. */
    private static void bothWays(String what, java.util.function.Consumer<Ground> build, CameraView view) {
        Ground walked = new Ground(64, false);
        Ground jumped = new Ground(64, true);
        build.accept(walked);
        build.accept(jumped);

        assertArrayEquals(frame(walked, view), frame(jumped, view), what + " must come out the same either way");
        assertTrue(jumped.asked.contains(3), what + " never reached an eight column patch, so nothing was jumped");
        assertFalse(walked.asked.contains(3), "the unjumped side must not be consulting patches");
    }

    @Test
    void openGroundRendersTheSame() {
        bothWays("open ground", world -> {
        }, new CameraView(0.5, 20, 0.5, 0, 20, CameraView.DEFAULT_FOV, 48));
    }

    /** The case the whole thing turns on: something clear of the ground, inside the air a jump crosses. */
    @Test
    void acanopyOverOpenGroundIsStillDrawn() {
        bothWays("a canopy", world -> {
            for (int x = 20; x <= 24; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = 12; y <= 14; y++) world.standing(x, y, z);
                }
            }
        }, new CameraView(0.5, 13, 0.5, 0, 0, CameraView.DEFAULT_FOV, 48));
    }

    @Test
    void asinglePostIsNotJumpedOver() {
        bothWays("one post", world -> {
            for (int y = 5; y <= 9; y++) world.standing(18, y, 0);
        }, new CameraView(0.5, 8, 0.5, 0, 0, CameraView.DEFAULT_FOV, 48));
    }

    /** Sinking toward the ground is what bounds a patch from below, and the cliff is where it bites. */
    @Test
    void arayDescendingOntoACliffRendersTheSame() {
        bothWays("a descent onto a cliff", world -> {
        }, new CameraView(-30.5, 26, 0.5, 0, 25, CameraView.DEFAULT_FOV, 64));
    }

    @Test
    void lookingAlongTheGroundRendersTheSame() {
        bothWays("a level look", world -> {
        }, new CameraView(-40.5, 6, 0.5, 0, 1, CameraView.DEFAULT_FOV, 64));
    }

    @Test
    void lookingUpRendersTheSame() {
        bothWays("a look upward", world -> {
            for (int x = 10; x <= 14; x++) {
                for (int y = 30; y <= 32; y++) world.standing(x, y, 0);
            }
        }, new CameraView(0.5, 20, 0.5, 0, -30, CameraView.DEFAULT_FOV, 48));
    }
}
