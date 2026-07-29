package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatorTest {

    private static final int DURATION = 200;

    private Animator at(long millis) {
        Animator animator = new Animator();
        animator.clock(millis);
        return animator;
    }

    @Test
    void firstReadLandsOnTheTargetWithoutAnimating() {
        Animator animator = at(1000);

        assertEquals(50, animator.value("x", 50, DURATION, Easing.LINEAR));
        assertFalse(animator.animating(), "nothing to animate from on the first read");
    }

    @Test
    void aChangedTargetEasesTowardsItAndArrives() {
        Animator animator = at(1000);
        animator.value("x", 0, DURATION, Easing.LINEAR);

        animator.clock(1000);
        animator.value("x", 100, DURATION, Easing.LINEAR);
        assertTrue(animator.animating());

        animator.clock(1100);
        assertEquals(50, animator.value("x", 100, DURATION, Easing.LINEAR), 0.001, "halfway");

        animator.clock(1200);
        assertEquals(100, animator.value("x", 100, DURATION, Easing.LINEAR), 0.001, "arrived");
        assertFalse(animator.animating());
    }

    /** The point of restarting from the current value: a target that keeps moving must not jump. */
    @Test
    void retargetingMidFlightContinuesFromWhereItGotTo() {
        Animator animator = at(0);
        animator.value("x", 0, DURATION, Easing.LINEAR);

        animator.clock(0);
        animator.value("x", 100, DURATION, Easing.LINEAR);
        animator.clock(100);

        double halfway = animator.value("x", 100, DURATION, Easing.LINEAR);
        assertEquals(50, halfway, 0.001);

        // New target while still moving: the next value must start from 50, not snap to 0 or 100.
        animator.value("x", 0, DURATION, Easing.LINEAR);
        assertEquals(50, animator.value("x", 0, DURATION, Easing.LINEAR), 0.001);

        animator.clock(200);
        assertEquals(25, animator.value("x", 0, DURATION, Easing.LINEAR), 0.001);
    }

    @Test
    void colorsInterpolateChannelwise() {
        Animator animator = at(0);
        animator.color("c", Color.BLACK, DURATION, Easing.LINEAR);

        animator.clock(0);
        animator.color("c", Color.WHITE, DURATION, Easing.LINEAR);

        animator.clock(100);
        Color halfway = animator.color("c", Color.WHITE, DURATION, Easing.LINEAR);
        assertEquals(127, halfway.getRed(), 2);
        assertEquals(127, halfway.getBlue(), 2);

        animator.clock(200);
        assertEquals(Color.WHITE, animator.color("c", Color.WHITE, DURATION, Easing.LINEAR));
    }

    @Test
    void separateKeysDoNotInterfere() {
        Animator animator = at(0);
        animator.value("a", 0, DURATION, Easing.LINEAR);
        animator.value("b", 0, DURATION, Easing.LINEAR);

        animator.clock(0);
        animator.value("a", 100, DURATION, Easing.LINEAR);
        animator.value("b", 10, DURATION, Easing.LINEAR);

        animator.clock(1000);
        assertEquals(100, animator.value("a", 100, DURATION, Easing.LINEAR), 0.001);
        assertEquals(10, animator.value("b", 10, DURATION, Easing.LINEAR), 0.001);
    }

    @Test
    void disabledMeansValuesSnapAndNothingIsEverAnimating() {
        Animator animator = at(0);
        animator.value("x", 0, DURATION, Easing.LINEAR);
        animator.clock(0);
        animator.value("x", 100, DURATION, Easing.LINEAR);

        animator.enabled(false);

        assertEquals(100, animator.value("x", 100, DURATION, Easing.LINEAR));
        assertFalse(animator.animating());
    }

    @Test
    void zeroDurationSnaps() {
        Animator animator = at(0);
        animator.value("x", 0, 0, Easing.LINEAR);
        animator.clock(0);

        assertEquals(100, animator.value("x", 100, 0, Easing.LINEAR));
        assertFalse(animator.animating());
    }

    @Test
    void finishedTracksForUnusedKeysAreForgotten() {
        Animator animator = at(0);
        animator.beginLayout();
        animator.value("gone", 0, DURATION, Easing.LINEAR);

        // Several layout passes go by without anyone asking about it.
        animator.clock(DURATION * 2);
        for (int i = 0; i < 4; i++) animator.beginLayout();

        assertEquals(7, animator.value("gone", 7, DURATION, Easing.LINEAR), 0.001, "a forgotten key starts fresh at its target");
    }

    /**
     * An idle screen is not laid out, so expiring on a timer would lose the value the next scroll
     * needs to animate from.
     */
    @Test
    void idlingDoesNotForgetAnything() {
        Animator animator = at(0);
        animator.beginLayout();
        animator.value("x", 0, DURATION, Easing.LINEAR);

        animator.clock(60_000);
        animator.beginLayout();
        animator.value("x", 100, DURATION, Easing.LINEAR);

        assertTrue(animator.animating(), "should ease from 0, not restart at 100");
        assertEquals(0, animator.value("x", 100, DURATION, Easing.LINEAR), 0.001);
    }

    @Test
    void phaseLoopsAndKeepsFramesComing() {
        Animator animator = at(0);
        assertFalse(animator.animating(), "an untouched animator must not report itself busy");

        animator.beginLayout();
        assertEquals(0, animator.phase(1000), 0.001);
        assertTrue(animator.animating(), "asking for a phase is what requests more frames");

        animator.clock(250);
        assertEquals(0.25, animator.phase(1000), 0.001);
        animator.clock(1250);
        assertEquals(0.25, animator.phase(1000), 0.001, "wraps rather than growing");
    }

    @Test
    void aScreenThatStopsLoopingStopsBeingRepainted() {
        Animator animator = at(0);
        animator.beginLayout();
        animator.phase(1000);
        assertTrue(animator.animating());

        // Two passes with nobody asking, and it should go quiet.
        animator.beginLayout();
        animator.beginLayout();
        animator.beginLayout();
        assertFalse(animator.animating());
    }

    /**
     * The whole point of quantizing the clock: between steps the value is identical, so the pixels
     * are identical, so there is no dirty rect and nothing gets sent.
     */
    @Test
    void aLoopLimitHoldsThePhaseStillBetweenSteps() {
        Animator animator = at(0);
        animator.loopFps(10);
        animator.beginLayout();

        double first = animator.phase(1000);
        animator.clock(50);
        assertEquals(first, animator.phase(1000), 0.0001, "still inside the same 100ms step");

        animator.clock(99);
        assertEquals(first, animator.phase(1000), 0.0001);

        animator.clock(100);
        assertEquals(0.1, animator.phase(1000), 0.0001, "next step, so it moves");
    }

    @Test
    void aLoopLimitDoesNotSlowDownTransitions() {
        Animator animator = at(0);
        animator.loopFps(2);
        animator.value("x", 0, DURATION, Easing.LINEAR);

        animator.clock(0);
        animator.value("x", 100, DURATION, Easing.LINEAR);

        animator.clock(100);
        assertEquals(50, animator.value("x", 100, DURATION, Easing.LINEAR), 0.001, "an eased value ignores the loop limit entirely");
    }

    /** The two are limited separately, so the session has to be able to tell them apart. */
    @Test
    void loopingAndTransitioningAreDistinguishable() {
        Animator animator = at(0);
        animator.beginLayout();
        animator.phase(1000);

        assertTrue(animator.looping());
        assertFalse(animator.transitioning(), "a loop is not a transition");

        Animator easing = at(0);
        easing.value("x", 0, DURATION, Easing.LINEAR);
        easing.clock(0);
        easing.value("x", 100, DURATION, Easing.LINEAR);

        assertTrue(easing.transitioning());
        assertFalse(easing.looping(), "an ease is not a loop");
    }

    @Test
    void noLoopLimitLeavesThePhaseAtFullRate() {
        Animator animator = at(0);
        animator.beginLayout();

        animator.clock(1);
        assertEquals(0.001, animator.phase(1000), 0.0001);
    }

    @Test
    void easingShapesProgressButAlwaysSpansZeroToOne() {
        for (Easing easing : new Easing[]{Easing.LINEAR, Easing.EASE_IN, Easing.EASE_OUT, Easing.EASE_IN_OUT}) {
            assertEquals(0, easing.apply(0), 0.001);
            assertEquals(1, easing.apply(1), 0.001);
        }
        assertTrue(Easing.EASE_OUT.apply(0.25) > 0.25, "ease out starts fast");
        assertTrue(Easing.EASE_IN.apply(0.25) < 0.25, "ease in starts slow");
    }
}
