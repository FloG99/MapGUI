package de.flog99.mapgui.examples.claims;

import de.flog99.mapgui.MapGui;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A full-screen claim map: one {@code Draw} node standing in for a grid, and cursor tracking.
 *
 * <p>The {@link Claims} model is shared by every screen, so a chunk one player takes appears on everyone else's
 * open map - see {@link de.flog99.mapgui.SharedModel}.
 */
public final class ClaimPlugin extends JavaPlugin {

    private static final String NAME = "claims";

    /** Shared by every screen, so two players see each other's claims. */
    private final Claims claims = new Claims();

    @Override
    public void onEnable() {
        MapGui.get().guis().registerOpenable(NAME, "A claim map - shared between everyone looking", player -> new ClaimScreen(claims));
    }

    @Override
    public void onDisable() {
        MapGui.get().guis().unregister(NAME);
    }
}
