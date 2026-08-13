package de.flog99.mapgui.examples.minimap;

import com.mojang.brigadier.Command;
import de.flog99.mapgui.MapGui;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Terrain rendering, a screen with no cursor at all, and one worn in the offhand rather than opened. */
public final class MinimapDemo {

    private static final String NAME = "minimap";

    public void register(JavaPlugin plugin) {
        MapGui.get().guis().registerOpenable(NAME, "The world around you, with no cursor", player -> new MinimapScreen());

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(Commands.literal("minimap")
                        .executes(context -> {
                            if (context.getSource().getSender() instanceof Player player) {
                                toggle(player);
                            } else {
                                context.getSource().getSender().sendMessage(Component.text("Only a player has a world to map."));
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(), "Wear a minimap in your offhand")
        );
    }

    /**
     * The demo's own way in and out, which a carried screen needs.
     *
     * <p>Q closes a popup, because a popup is modal and that key has nothing else to do while it is up. This one is
     * worn in the offhand and deliberately takes nothing from the player - not their clicks, not Q - so there is no
     * key left for it to claim, and closing it has to be something the plugin offers. Same as a wall: it is up until
     * whoever put it up takes it down.
     */
    private void toggle(Player player) {
        if (MapGui.get().isOpen(player)) {
            MapGui.get().close(player);
            player.sendMessage(Component.text("Minimap off.", NamedTextColor.GRAY));
            return;
        }

        MapGui.get().open(player, new MinimapScreen());
        player.sendMessage(Component.text("Minimap on.", NamedTextColor.GRAY)
                .append(Component.text(" Swap hands, or run /minimap again, to put it away.", NamedTextColor.DARK_GRAY)));
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
