package de.flog99.mapgui.examples.minimap;

import de.flog99.mapgui.MapGui;

/** Terrain rendering, a screen with no cursor at all, and one worn in the offhand rather than opened. */
public final class MinimapDemo {

    private static final String NAME = "minimap";

    public void register() {
        MapGui.get().guis().registerOpenable(NAME, "The world around you, with no cursor", player -> new MinimapScreen());
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
