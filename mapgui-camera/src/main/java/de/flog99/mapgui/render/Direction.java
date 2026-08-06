package de.flog99.mapgui.render;

/**
 * The six block faces, in Minecraft's axes: +X east, +Y up, +Z south, so north is -Z.
 *
 * <p>Ordered to match the {@code down, up, north, south, west, east} that model json uses, so a face array
 * can be indexed by {@link #ordinal()} without a lookup.
 */
public enum Direction {

    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0);

    private static final Direction[] VALUES = values();

    private final int dx;
    private final int dy;
    private final int dz;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    int dx() {
        return dx;
    }

    int dy() {
        return dy;
    }

    int dz() {
        return dz;
    }

    /** The name model json uses, which is the lowercase one. */
    String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    static Direction byKey(String key) {
        for (Direction direction : VALUES) {
            if (direction.key().equals(key)) return direction;
        }
        return null;
    }

    Direction opposite() {
        return switch (this) {
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
        };
    }

    /**
     * Where this face ends up after a blockstate's {@code x} and {@code y} rotation, in degrees.
     *
     * <p>{@code x} first and then {@code y}, and both as rotations of <i>minus</i> the stated angle in a
     * right-handed system. That is not a guess: {@code furnace} leaves {@code facing=north} unrotated and
     * gives {@code facing=east} a {@code y} of 90, so {@code y=90} has to carry the front face from north to
     * east, and only this sign does that. It then predicts the {@code oak_log} case without being told -
     * {@code axis=z} is {@code x=90} and lands the column ends on north and south, {@code axis=x} adds
     * {@code y=90} and moves them to east and west.
     *
     * <p>Getting this backwards mirrors every asymmetric block in a scene and is invisible in the code, which
     * is what {@code DirectionTest} is for.
     */
    Direction rotate(int x, int y) {
        Direction rotated = rotateX(this, Math.floorMod(x, 360) / 90);
        return rotateY(rotated, Math.floorMod(y, 360) / 90);
    }

    /** Undoes {@link #rotate}: y first, then x, each the other way round. */
    Direction unrotate(int x, int y) {
        Direction back = rotateY(this, 4 - Math.floorMod(y, 360) / 90);
        return rotateX(back, 4 - Math.floorMod(x, 360) / 90);
    }

    /** One quarter turn at a time, so only the four-step cycles have to be right. */
    private static Direction rotateX(Direction direction, int quarters) {
        Direction result = direction;
        for (int i = 0; i < quarters; i++) {
            // UP -> NORTH -> DOWN -> SOUTH -> UP, and east and west are the axis so they stay.
            result = switch (result) {
                case UP -> NORTH;
                case NORTH -> DOWN;
                case DOWN -> SOUTH;
                case SOUTH -> UP;
                case EAST, WEST -> result;
            };
        }
        return result;
    }

    private static Direction rotateY(Direction direction, int quarters) {
        Direction result = direction;
        for (int i = 0; i < quarters; i++) {
            // NORTH -> EAST -> SOUTH -> WEST -> NORTH, with up and down on the axis.
            result = switch (result) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                case UP, DOWN -> result;
            };
        }
        return result;
    }
}
