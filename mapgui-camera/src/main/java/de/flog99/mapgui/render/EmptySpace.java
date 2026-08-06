package de.flog99.mapgui.render;

/**
 * Which parts of the world hold nothing a ray could draw, in cells of 16 blocks and upward.
 *
 * <p>Crossing open air a block at a time is what long range costs: a 128x128 frame asked the world for a column
 * height nearly one and a half million times, and most of those answers were "nothing here". This says it once for a
 * whole cell, from a single byte read.
 *
 * <p>Hierarchical in the way it is built and flat in the way it is read. Each cell carries the size of the largest
 * <i>aligned</i> empty cell around it, so a ray in open sky is handed a 256 block box from one lookup rather than
 * climbing a tree; the levels only exist during {@link Builder#build()}.
 *
 * <p>Occupancy is blunt on purpose, since being exact matters more than being tight: a cell is empty only when every
 * block in it is, and anything the tracer might draw counts. Space that was never captured is empty deliberately -
 * {@link VoxelSource#stateAt} answers {@link BakedState#EMPTY} outside the capture and for unloaded chunks, so a ray
 * already draws sky through them. Entities are not in here at all; they are traced in a pass of their own.
 *
 * <p>Immutable once built, which is what makes it safe for every band of a frame to read at once.
 */
public final class EmptySpace {

    /** The finest cell, as a shift: 16 blocks, which is the granularity a chunk already tracks emptiness at. */
    public static final int CELL = 4;

    /** The coarsest cell, 256 blocks. Wider than any capture's far plane, so there is nothing above it left to win. */
    private static final int COARSEST = 8;

    /** Nothing is ever empty, which is how the walk is put back the way it was for the test that compares them. */
    public static final EmptySpace NONE = new EmptySpace(0, 0, 0, 0, 0, 0, new byte[0]);

    private final int minCellX;
    private final int minCellY;
    private final int minCellZ;
    private final int cellsX;
    private final int cellsY;
    private final int cellsZ;

    /** Per cell, the shift of the largest aligned empty cell containing it, or 0 for a cell with something in it. */
    private final byte[] shifts;

    private EmptySpace(int minCellX, int minCellY, int minCellZ, int cellsX, int cellsY, int cellsZ, byte[] shifts) {
        this.minCellX = minCellX;
        this.minCellY = minCellY;
        this.minCellZ = minCellZ;
        this.cellsX = cellsX;
        this.cellsY = cellsY;
        this.cellsZ = cellsZ;
        this.shifts = shifts;
    }

    /**
     * How large an aligned cube of empty space this block sits in, as a shift, or 0 for anything not known empty.
     *
     * <p>Zero for a block outside what was measured as well. That space is empty and could be skipped, but it is
     * only ever a handful of blocks between a camera above the world ceiling and the capture, and reporting "walk
     * it" keeps the one answer this returns unambiguous.
     */
    public int shiftAt(int x, int y, int z) {
        int cellX = (x >> CELL) - minCellX;
        if (cellX < 0 || cellX >= cellsX) return 0;

        int cellY = (y >> CELL) - minCellY;
        if (cellY < 0 || cellY >= cellsY) return 0;

        int cellZ = (z >> CELL) - minCellZ;
        if (cellZ < 0 || cellZ >= cellsZ) return 0;

        return shifts[(cellY * cellsZ + cellZ) * cellsX + cellX];
    }

    /** Over the block box that was captured, inclusive at both ends. */
    public static Builder over(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return new Builder(fromX, fromY, fromZ, toX, toY, toZ);
    }

    /**
     * Collects what is occupied and works out the cell sizes from it.
     *
     * <p>Not thread safe and not meant to be: a capture builds one of these while it is copying the world and then
     * hands over something immutable.
     */
    public static final class Builder {

        private final int minCellX;
        private final int minCellY;
        private final int minCellZ;
        private final int cellsX;
        private final int cellsY;
        private final int cellsZ;
        private final boolean[] occupied;

        private Builder(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
            this.minCellX = fromX >> CELL;
            this.minCellY = fromY >> CELL;
            this.minCellZ = fromZ >> CELL;
            this.cellsX = Math.max(0, (toX >> CELL) - minCellX + 1);
            this.cellsY = Math.max(0, (toY >> CELL) - minCellY + 1);
            this.cellsZ = Math.max(0, (toZ >> CELL) - minCellZ + 1);
            this.occupied = new boolean[cellsX * cellsY * cellsZ];
        }

        /** Marks the cell holding this block as holding something. One block in it is enough, which is the point. */
        public Builder occupied(int x, int y, int z) {
            int index = index(x >> CELL, y >> CELL, z >> CELL);
            if (index >= 0) {
                occupied[index] = true;
            }
            return this;
        }

        public EmptySpace build() {
            if (occupied.length == 0) return NONE;

            byte[] shifts = new byte[occupied.length];
            for (int cell = 0; cell < shifts.length; cell++) {
                shifts[cell] = occupied[cell] ? 0 : (byte) CELL;
            }

            // One level at a time, each cell claiming the coarser size once its whole aligned parent is clear. An
            // empty parent means every cell under it is empty too, so climbing in order leaves the largest size in
            // place. A parent hanging over the edge of the capture still counts, because unmeasured space is space
            // the tracer draws sky through.
            for (int shift = CELL + 1; shift <= COARSEST; shift++) {
                promote(shifts, shift);
            }

            return new EmptySpace(minCellX, minCellY, minCellZ, cellsX, cellsY, cellsZ, shifts);
        }

        private void promote(byte[] shifts, int shift) {
            Parents parents = new Parents(shift - CELL);

            for (int y = 0; y < cellsY; y++) {
                for (int z = 0; z < cellsZ; z++) {
                    for (int x = 0; x < cellsX; x++) {
                        if (occupied[(y * cellsZ + z) * cellsX + x]) {
                            parents.mark(x, y, z);
                        }
                    }
                }
            }

            for (int y = 0; y < cellsY; y++) {
                for (int z = 0; z < cellsZ; z++) {
                    for (int x = 0; x < cellsX; x++) {
                        int cell = (y * cellsZ + z) * cellsX + x;
                        if (shifts[cell] != 0 && !parents.holds(x, y, z)) {
                            shifts[cell] = (byte) shift;
                        }
                    }
                }
            }
        }

        /** One level up: a flag per aligned block of {@code 1 << step} cells, set when anything under it is set. */
        private final class Parents {

            private final int step;
            private final int baseX;
            private final int baseY;
            private final int baseZ;
            private final int spanX;
            private final int spanZ;
            private final boolean[] flags;

            private Parents(int step) {
                this.step = step;
                this.baseX = minCellX >> step;
                this.baseY = minCellY >> step;
                this.baseZ = minCellZ >> step;
                this.spanX = ((minCellX + cellsX - 1) >> step) - baseX + 1;
                this.spanZ = ((minCellZ + cellsZ - 1) >> step) - baseZ + 1;
                int spanY = ((minCellY + cellsY - 1) >> step) - baseY + 1;
                this.flags = new boolean[spanX * spanY * spanZ];
            }

            private void mark(int x, int y, int z) {
                flags[index(x, y, z)] = true;
            }

            private boolean holds(int x, int y, int z) {
                return flags[index(x, y, z)];
            }

            private int index(int x, int y, int z) {
                int parentX = ((minCellX + x) >> step) - baseX;
                int parentY = ((minCellY + y) >> step) - baseY;
                int parentZ = ((minCellZ + z) >> step) - baseZ;
                return (parentY * spanZ + parentZ) * spanX + parentX;
            }
        }

        private int index(int cellX, int cellY, int cellZ) {
            int x = cellX - minCellX;
            int y = cellY - minCellY;
            int z = cellZ - minCellZ;
            if (x < 0 || y < 0 || z < 0 || x >= cellsX || y >= cellsY || z >= cellsZ) return -1;

            return (y * cellsZ + z) * cellsX + x;
        }
    }
}
