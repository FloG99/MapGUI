package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arm an iron golem holds out while it is offering a poppy.
 *
 * <p>The flower hangs off this arm, so the turn's sign decides whether it is drawn in front of the golem or behind.
 */
class GolemOfferTest {

    /** Roughly where the golem's own shoulder is, in entity pixels off the feet. Only the pivot matters here. */
    private static final float SHOULDER = 31;

    /** Down the arm from the shoulder, which is where the fist and so the flower is. */
    private static final float DOWN_THE_ARM = -25;

    private static final MeshCube ARM = MeshCube.box(-2, -30, -3, 4, 30, 6, 0, 0, 128, 128, 0);

    private static EntityModel arms() {
        return EntityModel.of(List.of(
                MeshPart.at("right_arm", 0, SHOULDER, 0, List.of(ARM), List.of()),
                MeshPart.at("left_arm", 0, SHOULDER, 0, List.of(ARM), List.of())
        ));
    }

    @Test
    void theRightArmComesUpAndTheLeftStaysWhereItWas() {
        EntityModel offering = arms().offering();

        float[] right = Turns.angles(offering.joint("right_arm").turn());
        float[] left = Turns.angles(offering.joint("left_arm").turn());

        // Vanilla's own -0.8 about X, which is +0.8 in this frame, and a flat zero on the arm holding nothing.
        assertEquals(0.8f, right[0], 1e-4, "the arm with the flower in it");
        assertEquals(0, left[0], 1e-4, "the arm holding nothing");
        assertEquals(0, right[1], 1e-4, "no turn about Y");
        assertEquals(0, right[2], 1e-4, "no roll");
    }

    /** The sign that matters: -Z is the way a mob faces, so the fist has to end up at a negative Z. */
    @Test
    void theFistEndsUpInFrontOfTheShoulderRatherThanBehindIt() {
        EntityModel.Joint arm = arms().offering().joint("right_arm");
        float[] fist = Turns.apply(arm.turn(), 0, DOWN_THE_ARM, 0);

        assertTrue(fist[2] < -15, "held out in front, was " + fist[2]);
        assertTrue(fist[1] < 0 && fist[1] > DOWN_THE_ARM, "lifted, but not above the shoulder, was " + fist[1]);
    }

    /** An arm nobody has posed hangs straight down, so the same point stays under the shoulder. */
    @Test
    void anArmLeftAloneKeepsHangingDown() {
        EntityModel.Joint arm = arms().joint("right_arm");
        float[] fist = Turns.apply(arm.turn(), 0, DOWN_THE_ARM, 0);

        assertEquals(0, fist[2], 1e-4, "nothing forward");
        assertEquals(DOWN_THE_ARM, fist[1], 1e-4, "straight down");
    }
}
