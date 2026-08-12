package de.flog99.mapgui.examples.todo;

import com.mojang.brigadier.Command;
import de.flog99.mapgui.MapGui;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * State, scrolling, text prompts and per-row closures - and the one example that reaches players itself.
 *
 * <p>Two routes, on purpose. The {@code register} call makes it {@code /mapgui hand open todo} for an admin, which is
 * all the other examples do. The {@code /todo} command is the half that matters for a real plugin:
 * {@link MapGui#open} is how <i>your</i> users get to a menu, and MapGUI has no opinion about whether that is a
 * command, an item, an NPC or a click on a block.
 */
public final class TodoDemo {

    private static final String NAME = "todo";

    public void register(JavaPlugin plugin) {
        MapGui.get().guis().registerOpenable(NAME, "A to-do list - scrolling, prompts, per-row state", TodoScreen::new);

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(Commands.literal(NAME)
                        .executes(context -> {
                            if (context.getSource().getSender() instanceof Player player) {
                                MapGui.get().open(player, new TodoScreen(player));
                            } else {
                                context.getSource().getSender().sendMessage(Component.text("Players only."));
                            }
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(), "Open your to-do list")
        );
    }

    public void unregister() {
        MapGui.get().guis().unregister(NAME);
    }
}
