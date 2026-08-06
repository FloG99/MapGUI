package de.flog99.mapgui.plugin.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How far a mob's head is allowed to turn from its body.
 *
 * <p>The equines are the ones with a limit, and it is not a rounding detail: the server stores a head yaw that can lead
 * the body by 75 degrees while {@code AbstractEquineModel} clamps what it draws to twenty. Without the clamp a donkey
 * in a photograph stares at the camera while the animal in front of you has barely turned its head, which is exactly
 * how it was reported.
 */
class HeadTurnTest {

    @Test
    void anEquineTurnsItsHeadTwentyDegreesAndNoFurther() {
        assertEquals(20, EntityCapture.headYaw("donkey", 0, 60), 1e-4, "a donkey looking hard right");
        assertEquals(-20, EntityCapture.headYaw("donkey", 0, -60), 1e-4, "and hard left");
        assertEquals(10, EntityCapture.headYaw("horse", 0, 10), 1e-4, "inside the limit it is left alone");
        assertEquals(110, EntityCapture.headYaw("mule", 90, 200), 1e-4, "and the limit is measured from the body");
    }

    /** Everything else draws the head where the server says it is, which is what its own model does. */
    @Test
    void anythingWithoutALimitIsLeftAlone() {
        assertEquals(60, EntityCapture.headYaw("cow", 0, 60), 1e-4);
        assertEquals(-170, EntityCapture.headYaw("zombie", 0, -170), 1e-4);
    }

    /**
     * A head yaw either side of north is a small turn, not most of a circle.
     *
     * <p>Yaw is unbounded on the server - a mob that has turned right four times reads 1440 - so the difference has to
     * be wrapped before it is clamped or a donkey facing a hair east of north is clamped as though it had spun round.
     */
    @Test
    void anAngleAcrossNorthIsASmallTurn() {
        assertEquals(-10, EntityCapture.wrapped(350), 1e-4);
        assertEquals(10, EntityCapture.wrapped(-350), 1e-4);
        assertEquals(0, EntityCapture.wrapped(720), 1e-4);
        assertEquals(-179, EntityCapture.wrapped(181), 1e-4);

        assertEquals(350, EntityCapture.headYaw("donkey", 355, 350), 1e-4, "five degrees apart, either side of north, and left alone");
    }
}
