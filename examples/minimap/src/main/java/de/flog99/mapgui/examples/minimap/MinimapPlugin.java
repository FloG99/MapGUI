package de.flog99.mapgui.examples.minimap;

import de.flog99.mapgui.MapGui;
import org.bukkit.plugin.java.JavaPlugin;

/** Terrain rendering, and a screen with no cursor at all. */
public final class MinimapPlugin extends JavaPlugin {

    private static final String NAME = "minimap";

    @Override
    public void onEnable() {
        MapGui.get().guis().registerOpenable(NAME, "The world around you, with no cursor", player -> new MinimapScreen());
    }

    @Override
    public void onDisable() {
        MapGui.get().guis().unregister(NAME);
    }
}
