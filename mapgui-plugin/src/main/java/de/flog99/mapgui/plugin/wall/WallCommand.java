package de.flog99.mapgui.plugin.wall;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.flog99.mapgui.plugin.Listing;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@code /mapgui wall} - GUIs and pictures on blocks.
 *
 * <pre>
 *   wall place &lt;content&gt;     wall remove [name]     wall list
 * </pre>
 *
 * <p>Three verbs like {@code /mapgui hand}, and they differ from that half on purpose: a held GUI is opened for
 * a player and gone when they put it down, while a wall is placed in the world and outlives everyone looking at
 * it. {@code list} is the one that means the same thing in both.
 *
 * <p>Placing has no {@code cancel}: right-click and Q both already end it, and they are what a player reaches
 * for anyway, since the preview is in front of them rather than in the chat.
 *
 * <p>One argument takes both a GUI and a video file, deliberately. They arrive from different places but end
 * up as the same thing, so making an admin know which kind something is before they can place it would be a
 * distinction the code went out of its way to erase.
 */
public final class WallCommand {

    private static final List<Listing.Choice> VERBS = List.of(
            new Listing.Choice("/mapgui wall place", "put a GUI or picture on blocks"),
            new Listing.Choice("/mapgui wall remove", "take one down"),
            new Listing.Choice("/mapgui wall list", "what is up, with coordinates")
    );

    private WallCommand() {
    }

    public static ArgumentBuilder<CommandSourceStack, ?> wall(WallManager walls, java.util.function.Predicate<CommandSourceStack> allowed) {
        return Commands.literal("wall")
                .requires(allowed)
                .executes(context -> {
                    Listing.choices(context.getSource().getSender(), "GUIs on blocks", VERBS);
                    return Command.SINGLE_SUCCESS;
                })
                .then(place(walls))
                .then(remove(walls))
                .then(list(walls));
    }

    /** {@code place <content>}, or with no argument the catalog of what there is. */
    private static ArgumentBuilder<CommandSourceStack, ?> place(WallManager walls) {
        return Commands.literal("place")
                .executes(context -> {
                    Listing.send(context.getSource().getSender(), "Things you can put on a wall",
                            "click one to start placing it", walls.describeContents(),
                            "<gray>Nothing to place yet - drop a <white>.gif</white> in "
                                    + "<white>plugins/MapGUI/videos</white>, or install a plugin that "
                                    + "registers a GUI."
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("content", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            for (String name : walls.contentNames()) builder.suggest(name);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            Player player = player(context, "<red>Run this in game - placing needs somewhere to stand.");
                            if (player == null) return Command.SINGLE_SUCCESS;

                            String failure = walls.startPlacing(player, StringArgumentType.getString(context, "content"));
                            player.sendRichMessage(failure != null ? "<red>" + failure
                                    : "<gray>Left-click the block for the <white>bottom left</white> corner, "
                                            + "then look at the far corner. Right-click or Q to cancel."
                            );
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    /** Named, or the nearest one when a player runs it with nothing to go on. */
    private static ArgumentBuilder<CommandSourceStack, ?> remove(WallManager walls) {
        return Commands.literal("remove")
                .executes(context -> {
                    Player player = player(context, "<red>Run this in game, or name the wall to remove.");
                    if (player == null) return Command.SINGLE_SUCCESS;

                    String removed = walls.removeNearest(player);
                    player.sendRichMessage(removed != null
                            ? "<green>Removed " + removed + "."
                            : "<red>No wall within " + WallManager.NEAREST_RANGE + " blocks - name one to "
                                    + "reach further."
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            for (String name : walls.names()) builder.suggest(name);
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            context.getSource().getSender().sendRichMessage(walls.remove(name)
                                    ? "<green>Removed " + name + "."
                                    : "<red>No wall called " + name + "."
                            );
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> list(WallManager walls) {
        return Commands.literal("list")
                .executes(context -> {
                    List<Component> lines = walls.describe();
                    Listing.send(context.getSource().getSender(),
                            lines.size() + " wall" + (lines.size() == 1 ? "" : "s") + " up",
                            "click coordinates to teleport", lines,
                            "<gray>Nothing up yet - see <white>/mapgui wall place</white> for what you can put up."
                    );
                    return Command.SINGLE_SUCCESS;
                });
    }

    /**
     * Placing and finding the nearest both need somebody standing somewhere.
     *
     * <p>The message is the caller's because what to do instead differs: a console can remove a wall by naming
     * it, but there is no way at all to place one without being somewhere.
     */
    @Nullable
    private static Player player(CommandContext<CommandSourceStack> context, String whenNotAPlayer) {
        if (context.getSource().getSender() instanceof Player player) return player;

        context.getSource().getSender().sendRichMessage(whenNotAPlayer);
        return null;
    }
}
