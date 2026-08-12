package de.flog99.mapgui.examples.claims;

import de.flog99.mapgui.MapGui;

/**
 * A full-screen claim map: one {@code Draw} node standing in for a grid, and cursor tracking.
 *
 * <p>The {@link Claims} model is shared by every screen, so a chunk one player takes appears on everyone else's
 * open map - see {@link de.flog99.mapgui.SharedModel}.
 *
 * <p>The one demo here that wants nothing from its plugin: a GUI an admin opens needs the API and no more.
 */
public final class ClaimDemo {

    private static final String NAME = "claims";

    /** Shared by every screen, so two players see each other's claims. */
    private final Claims claims = new Claims();

    public void register() {
        MapGui.get().guis().registerOpenable(NAME, "A claim map - shared between everyone looking", player -> new ClaimScreen(claims));
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
