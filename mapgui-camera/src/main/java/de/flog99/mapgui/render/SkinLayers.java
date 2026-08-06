package de.flog99.mapgui.render;

/**
 * Which of a player's skin overlay layers is switched on.
 *
 * <p>A skin's second layer is not decoration the server can assume: the client says which parts of it to draw
 * and everybody else's client honours that, so somebody who has turned their jacket off is wearing the base
 * layer as far as the whole server is concerned. A capture that drew all seven regardless would put clothes on
 * people who took them off.
 *
 * <p>No cape, because a cape is not in the skin texture and nothing here draws one.
 */
public record SkinLayers(
        boolean hat,
        boolean jacket,
        boolean rightSleeve, boolean leftSleeve,
        boolean rightPants, boolean leftPants) {

    /** What a client that has not said otherwise sends, and the right answer for anything that cannot be asked. */
    public static final SkinLayers ALL = new SkinLayers(true, true, true, true, true, true);
}
