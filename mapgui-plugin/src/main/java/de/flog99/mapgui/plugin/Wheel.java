package de.flog99.mapgui.plugin;

/**
 * How far the wheel turned, for a map that is put straight back in the slot it came from.
 *
 * <p>The wheel is only ever heard as "this client has slot N selected now", <b>once a client tick</b> however many
 * notches went by inside it. A pinned map answers by putting the selection back, so the next report is measured
 * from the map's own slot again - and only once that answer has arrived. Until it does, the client keeps counting
 * from where it already was, so what arrives is how far it has <i>drifted</i> rather than how far it has turned.
 *
 * <p>Taking each report at face value therefore counts a flick several times over, and taking one notch per report
 * - which is what this replaces - throws away everything past the first notch of every tick. That capped a flick at
 * twenty rows a second whatever the player did with the wheel.
 *
 * <p>The two cases are told apart by the drift itself: further from the slot in the same direction is the same
 * turn carrying on, and anything else is a fresh one from a selection that has been put back.
 *
 * <p>Beyond four notches inside one tick the hotbar's own wrap makes the direction ambiguous - nine slots in a ring
 * cannot say five one way from four the other - and that is a limit of what the client sends rather than of this.
 * It is about eighty notches a second.
 */
final class Wheel {

    /** How far the selection sat from the map's slot when we last heard, as notches. */
    private int drift;

    /**
     * Forgets the drift, for a selection that has certainly been put back since it was measured.
     *
     * <p>Told rather than timed, because how long "certainly" is depends on the player's own round trip. Without
     * it a pause and then a flick would be read against a drift from before the pause, and the notches the two
     * have in common would go missing.
     */
    void settled() {
        drift = 0;
    }

    /**
     * @param offset how far the selection now sits from the slot the map is kept in
     * @return how many notches the wheel turned to get there
     */
    int turned(int offset) {
        int turned = onward(offset) ? offset - drift : offset;
        drift = offset;
        return turned;
    }

    /** Whether this is the last drift carried further, rather than a fresh turn from the map's own slot. */
    private boolean onward(int offset) {
        return drift != 0 && Integer.signum(offset) == Integer.signum(drift) && Math.abs(offset) > Math.abs(drift);
    }
}
