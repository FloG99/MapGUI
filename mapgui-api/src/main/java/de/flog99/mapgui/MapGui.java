package de.flog99.mapgui;

import de.flog99.mapgui.prompt.PromptRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Entry point. Registered as a Bukkit service by the MapGUI plugin, so consumers declare it as
 * a dependency and never construct anything themselves.
 */
public interface MapGui {

    static MapGui get() {
        RegisteredServiceProvider<MapGui> provider =
                Bukkit.getServicesManager().getRegistration(MapGui.class);
        if (provider == null) {
            throw new IllegalStateException("MapGUI is not loaded - declare it as a dependency in your paper-plugin.yml");
        }
        return provider.getProvider();
    }

    /** Opens a screen, replacing whatever the player had open. */
    Session open(Player player, Screen screen);

    void close(Player player);

    @Nullable
    Session session(Player player);

    default boolean isOpen(Player player) {
        return session(player) != null;
    }

    Collection<Session> sessions();

    /**
     * Starts building a wall of maps on a block face - a screen, a mural, a television.
     *
     * <p>Nothing is placed in the world and nothing is saved: whatever the wall belongs to is yours to
     * keep track of, and you call {@link WallDisplay#close()} when it goes away. Opening it again on
     * startup is all that a restart needs.
     *
     * <p>Block-aligned, and that is a limit of Minecraft rather than of this: map contents only render in an
     * item frame or a held map, so there is no way to hang one at an arbitrary angle. What you can do is
     * paint the picture rotated and leave the rest transparent. For maps you place yourself - in your own
     * frames, on your own furniture - drive them through {@link #transport()}.
     */
    WallDisplay.Builder wall();

    /**
     * The screens an admin can reach by name, with {@code /mapgui hand open} and {@code /mapgui wall place}.
     *
     * <p>Administration, not how your users reach a screen - that stays yours, through {@link #open}.
     */
    GuiCatalog guis();

    /**
     * The packet layer, for maps this plugin is not putting up for you.
     *
     * <p>The way to drive a map you own - one in a real item frame on a piece of furniture, one you spawned
     * yourself - since it will send pixels to any map id, whether the server allocated it or not. Take ids
     * from {@link MapIds#next()} so they cannot collide with MapGUI's own, and note that nothing here is
     * remembered for you: a viewer who reloads their chunks has to be sent it again.
     *
     * <p>Also where the bandwidth counters live, if you want to report on what your own maps cost.
     */
    MapTransport transport();

    PromptRegistry prompts();
}
