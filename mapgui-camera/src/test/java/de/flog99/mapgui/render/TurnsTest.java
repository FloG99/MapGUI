package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rotation arithmetic two conventions meet in.
 *
 * <p>Worth its own tests because nothing downstream of it fails loudly. A rotation that comes back a quarter turn out
 * draws an item pointing somewhere else, which looks like a modelling mistake rather than an arithmetic one, and there
 * is no assertion anywhere in a picture.
 */
class TurnsTest {

    /**
     * Angles in, the same angles out.
     *
     * <p>Which is the whole of what {@link Turns#angles} promises. Not a fixed expected matrix - that would pin the
     * arithmetic to whatever it happens to do today - but the property that makes it usable: a part turned by three
     * angles and read back reports the three it was turned by.
     */
    @Test
    void aRotationSurvivesBeingTakenApartIntoAngles() {
        float[][] triples = {
                {0, 0, 0},
                {0.4f, 0, 0},
                {0, -0.9f, 0},
                {0, 0, 2.1f},
                {0.3f, -0.7f, 1.2f},
                {-1.4f, 0.2f, -0.5f},
                {(float) Math.PI / 2, 0.1f, -0.2f}
        };

        for (float[] triple : triples) {
            float[] back = Turns.angles(Turns.part(triple[0], triple[1], triple[2]));
            assertArrayEquals(triple, back, 1e-4f,
                    "turned by " + triple[0] + ", " + triple[1] + ", " + triple[2] + " and read back as "
                            + back[0] + ", " + back[1] + ", " + back[2]);
        }
    }

    /**
     * Looking straight along Y, where the X and Z turns are the same turn and only their sum is determined.
     *
     * <p>The angles cannot come back as they went in there, and asking for that would be asking for the impossible.
     * What has to hold is that the rotation does: whatever split it reports has to turn a vector to the same place.
     */
    @Test
    void aRotationLookingStraightUpIsStillTheSameRotation() {
        float[] straightUp = Turns.part(0.5f, (float) Math.PI / 2, 0.25f);
        float[] back = Turns.angles(straightUp);

        assertArrayEquals(
                Turns.apply(straightUp, 1, 2, 3),
                Turns.apply(Turns.part(back[0], back[1], back[2]), 1, 2, 3),
                1e-4f, "the reported angles turn a point somewhere else");
    }

    /** Between the two spaces and back, which has to be the way it came. */
    @Test
    void mirroringTwiceIsTheSameRotation() {
        float[] turn = Turns.part(0.3f, -0.7f, 1.2f);

        assertArrayEquals(turn, Turns.mirrored(Turns.mirrored(turn)), 1e-6f);
    }

    /**
     * The half circle a block model arrives with is a conjugation, not a mirror.
     *
     * <p>Which is what makes it safe to apply to an angle: a rotation carried through it is still a rotation of the
     * same amount, about the axis that half circle moved. So a turn about Y survives untouched and the other two
     * change sign, and doing it twice is doing nothing.
     */
    @Test
    void aBlockModelsHalfCircleTurnsTheAxesAndNotTheAmount() {
        float[] turn = Turns.part(0.3f, -0.7f, 1.2f);

        assertArrayEquals(turn, Turns.halfTurned(Turns.halfTurned(turn)), 1e-6f);
        assertArrayEquals(Turns.y(0.4), Turns.halfTurned(Turns.y(0.4)), 1e-6f, "a turn about Y is the axis itself");
        assertArrayEquals(Turns.x(-0.4), Turns.halfTurned(Turns.x(0.4)), 1e-6f, "one about X runs the other way");
        assertArrayEquals(Turns.z(-0.4), Turns.halfTurned(Turns.z(0.4)), 1e-6f, "and so does one about Z");

        assertArrayEquals(new float[]{-1, 2, -3}, Turns.halfTurned(1, 2, 3), 1e-6f, "an offset mirrors X and Z");
    }

    /**
     * The two conventions really are different, so the conversion is not a no-op dressed up.
     *
     * <p>Same three angles, applied in the two orders the client uses in its two places - a model part and an item's
     * display transform - land a point somewhere else. If they did not, none of this would be needed.
     */
    @Test
    void thePartAndDisplayOrdersAreNotTheSameRotation() {
        float[] asPart = Turns.part((float) Math.toRadians(30), (float) Math.toRadians(60), (float) Math.toRadians(45));
        float[] asDisplay = Turns.display(30, 60, 45);

        float[] onePlace = Turns.apply(asPart, 1, 0, 0);
        float[] another = Turns.apply(asDisplay, 1, 0, 0);
        double apart = Math.abs(onePlace[0] - another[0]) + Math.abs(onePlace[1] - another[1]) + Math.abs(onePlace[2] - another[2]);

        assertTrue(apart > 0.1, "the two orders came out the same, so one of them is being built wrong");
    }
}
