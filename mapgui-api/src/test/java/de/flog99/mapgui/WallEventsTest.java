package de.flog99.mapgui;

import de.flog99.mapgui.event.MapGuiClickEvent;
import de.flog99.mapgui.event.MapGuiScreenCloseEvent;
import de.flog99.mapgui.event.MapGuiScreenOpenEvent;
import de.flog99.mapgui.event.MapGuiViewerChangeEvent;
import de.flog99.mapgui.event.MapGuiWallPlaceEvent;
import de.flog99.mapgui.event.MapGuiWallRemoveEvent;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Ui;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What MapGUI's events carry and which of them can be refused.
 *
 * <p>No server, so nothing is actually raised here - {@code Bukkit.getPluginManager()} does not exist without
 * one. What is worth pinning down anyway is the shape: which events a listener may cancel, that the ones it
 * may not have no way to, and that the lists handed out cannot be edited from underneath the wall that made
 * them.
 */
class WallEventsTest {

    private final FakeWorld world = new FakeWorld();
    private final FakeTransport transport = new FakeTransport();

    private WallDisplay wall() {
        WallServices services = new WallServices(transport, null, Runnable::run);
        WallDisplay wall = new WallDisplay.Builder(services, ignored -> {}, ignored -> {})
                .at(world.world(), 0, 64, 0, BlockFace.NORTH)
                .content((painter, bounds, millis) -> {})
                .open();
        assertNotNull(wall, "no server means nothing to cancel with, so a wall always opens here");
        return wall;
    }

    private static Screen blank() {
        return new Screen() {
            @Override
            protected Node build() {
                return Ui.Column();
            }
        };
    }

    @Test
    void aClickCarriesTheNodeThePixelsAndTheButton() {
        Player player = FakePlayer.named("clicker");
        Screen screen = blank();
        WallDisplay wall = wall();

        MapGuiClickEvent event = new MapGuiClickEvent(player, screen, wall, 12, 34, "settings/volume", Click.LEFT);

        assertSame(player, event.getPlayer());
        assertSame(screen, event.screen());
        assertSame(wall, event.wall());
        assertEquals(12, event.x());
        assertEquals(34, event.y());
        assertEquals("settings/volume", event.node());
        assertEquals(Click.LEFT, event.button());

        assertFalse(event.isCancelled());
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    /** A held screen has no wall, which is how a listener tells the two surfaces apart. */
    @Test
    void aClickOnAHeldScreenHasNoWall() {
        MapGuiClickEvent event = new MapGuiClickEvent(FakePlayer.named("clicker"), blank(), null, -1, -1, null, Click.RIGHT);

        assertNull(event.wall());
        assertNull(event.node());
    }

    @Test
    void openingAScreenCanBeRefusedAndSaysWhetherItWasPushed() {
        MapGuiScreenOpenEvent first = new MapGuiScreenOpenEvent(FakePlayer.named("viewer"), blank(), false);
        MapGuiScreenOpenEvent pushed = new MapGuiScreenOpenEvent(FakePlayer.named("viewer"), blank(), true);

        assertFalse(first.pushed());
        assertTrue(pushed.pushed());

        first.setCancelled(true);
        assertTrue(first.isCancelled());
    }

    /**
     * Closing is how a player puts a menu down and how a disconnect ends one, so there must be no way to
     * refuse it - a listener that could would be a way to pin a screen on somebody.
     */
    @Test
    void closingAScreenCannotBeRefused() {
        MapGuiScreenCloseEvent event = new MapGuiScreenCloseEvent(FakePlayer.named("viewer"), blank());

        assertNotNull(event.screen());
        assertFalse(Cancellable.class.isAssignableFrom(MapGuiScreenCloseEvent.class));
    }

    @Test
    void placingAndRemovingAWallCanBothBeRefused() {
        WallDisplay wall = wall();

        MapGuiWallPlaceEvent place = new MapGuiWallPlaceEvent(wall);
        MapGuiWallRemoveEvent remove = new MapGuiWallRemoveEvent(wall);

        assertSame(wall, place.wall());
        assertSame(wall.layout(), place.layout());
        assertSame(wall.world(), place.world());
        assertSame(wall, remove.wall());

        place.setCancelled(true);
        remove.setCancelled(true);
        assertTrue(place.isCancelled());
        assertTrue(remove.isCancelled());

        assertInstanceOf(Cancellable.class, place);
        assertInstanceOf(Cancellable.class, remove);
    }

    /**
     * The lists are the wall's own working lists at the moment it raises this, so they are copied on the way
     * out - a listener holding one must not see it refill next tick, and must not be able to edit it.
     */
    @Test
    void aViewerChangeHandsOutCopiesRatherThanTheWallsOwnLists() {
        WallDisplay wall = wall();

        List<Player> arrived = new ArrayList<>(List.of(FakePlayer.named("arriving")));
        List<UUID> left = new ArrayList<>(List.of(UUID.randomUUID()));

        MapGuiViewerChangeEvent event = new MapGuiViewerChangeEvent(wall, arrived, left);
        arrived.clear();
        left.clear();

        assertEquals(1, event.arrived().size());
        assertEquals(1, event.left().size());
        assertThrows(UnsupportedOperationException.class, () -> event.arrived().clear());
        assertFalse(Cancellable.class.isAssignableFrom(MapGuiViewerChangeEvent.class));
    }
}
