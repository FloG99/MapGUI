package de.flog99.mapgui;

import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.map.MapPrinter;
import de.flog99.mapgui.media.MediaService;
import de.flog99.mapgui.prompt.PromptRegistry;
import org.jetbrains.annotations.ApiStatus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.function.Function;
import java.util.function.Predicate;

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

    /**
     * Opens a screen, replacing whatever the player had open.
     *
     * @return the session, or null if a listener cancelled
     *         {@link de.flog99.mapgui.event.MapGuiScreenOpenEvent} - in which case nothing was opened and
     *         whatever the player already had is untouched
     */
    @Nullable
    Session open(Player player, Screen screen);

    /**
     * The same, carried the way you say rather than the way the screen or the server would have it.
     *
     * <p>Read {@link HandOptions} before reaching for this: the choice decides whether the player can move about
     * and click on the world while the screen is up, not only where the map appears.
     */
    @Nullable
    Session open(Player player, Screen screen, HandOptions hand);

    /**
     * A real map item that opens a registered GUI for whoever is holding it.
     *
     * <p>The item is a key rather than a screen. Every holder gets their own, built by the factory the GUI was
     * registered with - so a phone left in a chest shows its finder <i>their</i> phone, and a television remote
     * handed to a friend works for the friend rather than replaying yours. Nothing about it is shared and nothing
     * is owned: MapGUI opens a screen while somebody carries it and closes that screen when they put it down.
     *
     * <p>A genuine {@code ItemStack}, so it can be dropped, traded, stored and destroyed like any other item, and
     * MapGUI defends none of that. It costs no map id the world has to keep, because the id is invented rather
     * than allocated - unlike {@link #printer()}, which is the other way round.
     *
     * @param gui  a name registered with {@link GuiCatalog#registerOpenable}. The name is what lets a later holder
     *             be given a screen at all, which is why an instance will not do here
     * @param hand how it is carried once open. {@link HandOptions.Carry#ITEM} is implied
     * @throws IllegalArgumentException if no GUI by that name is openable
     */
    ItemStack item(String gui, HandOptions hand);

    /** The same, carried the way the server's config says - which is where a server owner can retune every GUI at once. */
    ItemStack item(String gui);

    /**
     * Opens a screen while a player holds a matching item in their <b>main hand</b>, and closes it when they stop.
     *
     * <p>{@link #item} is the other shape of this: there the map <i>is</i> the item, here the item is yours and the
     * map sits somewhere else - a camera whose viewfinder is in the offhand, a wand with its own panel.
     *
     * <pre>{@code
     * MapGui.get().openWhileHolding(
     *         stack -> stack.getType() == Material.SPYGLASS,
     *         HandOptions.Focus.ALWAYS,
     *         CameraScreen::new);
     * }</pre>
     *
     * <p>The main hand and not either hand: a trigger found in the offhand would be a screen drawn over the very item
     * that opened it. Swept once a tick rather than listened for, so the predicate runs for one stack per player per
     * tick - keep it to a material or a tag.
     *
     * <p>A screen that closes itself stays closed until the item is put down and picked up again, and nothing opens
     * over a screen the player already has up.
     *
     * <p><b>The screen is always carried in the offhand</b>, which is why this takes a {@link HandOptions.Focus}
     * rather than a whole {@link HandOptions}. Nothing else composes: every other carry mode puts the map in the
     * hotbar, where it counts as held only while its own slot is selected, so reaching for it means letting go of
     * the trigger item and closing the screen being reached for. The rest of {@code HandOptions} is meaningless for
     * an offhand map anyway - {@code slot} and {@code movable} are ignored and {@code offhandAllowed} is implied.
     *
     * @param focus whether the screen takes the player's mouse, and on what gesture
     * @param factory called once per open, with whoever is holding it. Returning null opens nothing
     */
    HeldTrigger openWhileHolding(Predicate<ItemStack> item, HandOptions.Focus focus, Function<Player, Screen> factory);

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

    /**
     * Captures what a player is looking at, onto a map.
     *
     * <p>Needs Minecraft's textures, which are not ours to ship, so check {@link Camera#assets()} before
     * offering it - see {@code docs/camera.md}.
     */
    Camera camera();

    /**
     * Plays a url your plugin was handed - a file, a stream, a YouTube or Twitch page.
     *
     * <p>The way to put media on a wall that is not named in {@code config.yml}, which is what a
     * {@code /stream <url>} command needs. Read {@link MediaService} first: whether to stream or to download is a
     * real choice, and permission-gating a url a player typed is the calling plugin's job rather than MapGUI's.
     */
    @ApiStatus.Experimental
    MediaService media();

    /**
     * Prints pixels onto real, placeable maps, for a picture that hangs in an item frame and stays there.
     *
     * <p>The one part of MapGUI that is not virtual, so read {@link MapPrinter} first: these are genuine vanilla
     * maps, taking genuine ids the world keeps forever, several of them per capture. They outlive MapGUI itself.
     */
    MapPrinter printer();

    PromptRegistry prompts();
}
