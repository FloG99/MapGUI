package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memo in front of a frame's world: same answers, fewer questions.
 *
 * <p>Both halves are the test. Reading a block out of a chunk snapshot copies it - a profile of a live server drawing a
 * reflection put {@code CraftBlockData.clone()} at 36.6% of everything the trace did - and a ray asks about the same
 * positions over and over: a fluid surface averages four blocks per corner, {@code above} re-reads the block a ray is
 * about to step into, {@code culled} asks about the neighbour behind every face, and the pixel next door walks the same
 * blocks again. So the answers must not change and the count must come down.
 *
 * <p>That the frames themselves are unchanged is held by every render test in this package, since all of them now trace
 * through this.
 */
class SeenBlocksTest {

    /** A world that answers like any other and counts what it was asked. */
    private static final class Counting implements VoxelSource {

        private final BakedState state;
        private int states;
        private int solids;

        private Counting(BakedState state) {
            this.state = state;
        }

        @Override
        public BakedState stateAt(int x, int y, int z) {
            states++;
            // Something position-dependent, so a memo answering from the wrong slot cannot pass unnoticed.
            return x + y + z == 0 ? state : BakedState.EMPTY;
        }

        @Override
        public boolean solidAt(int x, int y, int z) {
            solids++;
            return x + y + z == 0;
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
            return Sky.flat(0xFF000000);
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return 319;
        }
    }

    private static Counting counting() {
        return new Counting(new TestWorld().cube(0, 0, 0, "stone", BakedState.Alpha.OPAQUE).stateAt(0, 0, 0));
    }

    @Test
    void theSamePositionIsAskedAboutOnce() {
        Counting world = counting();
        VoxelSource seen = new SeenBlocks().over(world);

        BakedState first = seen.stateAt(4, 5, 6);
        for (int again = 0; again < 20; again++) {
            assertSame(first, seen.stateAt(4, 5, 6), "the memo gave a different answer the second time");
        }
        assertEquals(1, world.states, "the world was asked about one position " + world.states + " times");
    }

    /** Solidity is the server's answer rather than the model's, so it is remembered beside the state, not derived. */
    @Test
    void solidityIsRememberedToo() {
        Counting world = counting();
        VoxelSource seen = new SeenBlocks().over(world);

        assertTrue(seen.solidAt(0, 0, 0));
        assertTrue(seen.solidAt(0, 0, 0));
        seen.stateAt(0, 0, 0);
        assertTrue(seen.solidAt(0, 0, 0), "asking for the state cleared what was known about solidity");

        assertEquals(1, world.solids, "solidity was asked for " + world.solids + " times");
        assertEquals(1, world.states);
    }

    /**
     * Every position still gets its own answer, however they land in the table.
     *
     * <p>Direct-mapped, so two positions can want one slot - and the one that lost it must be re-read rather than given
     * the other's answer. Ten thousand positions through a table of sixteen thousand slots collides plenty.
     */
    @Test
    void aCollisionCostsAReadRatherThanTheWrongAnswer() {
        Counting world = counting();
        VoxelSource seen = new SeenBlocks().over(world);

        for (int at = 0; at < 10_000; at++) {
            int x = at % 97 - 48;
            int y = at / 97 % 61 - 30;
            int z = at % 89 - 44;

            assertEquals(world.stateAt(x, y, z), seen.stateAt(x, y, z),
                    "the memo disagreed with the world at " + x + " " + y + " " + z);
        }
    }

    /** A frame's world cannot change under it, so what may be remembered is scoped to exactly one frame. */
    @Test
    void pointingItAtAnotherWorldForgetsTheLast() {
        Counting first = counting();
        Counting second = counting();
        SeenBlocks memo = new SeenBlocks();

        memo.over(first).stateAt(1, 2, 3);
        memo.over(second).stateAt(1, 2, 3);

        assertEquals(1, first.states);
        assertEquals(1, second.states, "the second world was never asked, so the memo answered from the first one's");
    }
}
