package de.flog99.mapgui.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.flog99.mapgui.GuiCatalog;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /mapgui hand} - GUIs held in a player's hand.
 *
 * <pre>
 *   hand open &lt;gui&gt; [players]     hand close &lt;players&gt;     hand list
 * </pre>
 *
 * <p>The same three verbs as {@code /mapgui wall}, so learning one teaches the other. {@code list} is what
 * exists right now, and {@code open} with no name is the catalog of what it could take - the answer you want
 * at the moment you notice you are missing an argument.
 */
final class HandCommand {

    private static final List<Listing.Choice> VERBS = List.of(
            new Listing.Choice("/mapgui hand open", "put a GUI in someone's hand"),
            new Listing.Choice("/mapgui hand give", "hand out the item that opens one"),
            new Listing.Choice("/mapgui hand close", "take it away again"),
            new Listing.Choice("/mapgui hand list", "who has one open")
    );

    private HandCommand() {
    }

    static ArgumentBuilder<CommandSourceStack, ?> hand(SessionManager sessions, java.util.function.Predicate<CommandSourceStack> allowed) {
        return Commands.literal("hand")
                .requires(allowed)
                .executes(context -> {
                    Listing.choices(context.getSource().getSender(), "GUIs in a hand", VERBS);
                    return Command.SINGLE_SUCCESS;
                })
                .then(open(sessions))
                .then(item(sessions))
                .then(close(sessions))
                .then(list(sessions));
    }

    /**
     * {@code give <gui> [players]} - the real item that opens a GUI for whoever ends up holding it.
     *
     * <p>Next to {@code open} rather than hidden behind an API call, because the whole point of the item is that it
     * changes hands, and that is not something you can test by writing a plugin and reloading it.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> item(SessionManager sessions) {
        return Commands.literal("give")
                .then(Commands.argument("gui", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            for (GuiCatalog.Entry entry : sessions.guis().openable()) {
                                builder.suggest(entry.name());
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            Player self = self(context);
                            return self == null ? Command.SINGLE_SUCCESS : hand(context, sessions, List.of(self));
                        })
                        .then(Commands.argument("players", ArgumentTypes.players())
                                .executes(context -> hand(context, sessions, resolve(context)))
                        )
                );
    }

    private static int hand(CommandContext<CommandSourceStack> context, SessionManager sessions, List<Player> targets) {
        String name = StringArgumentType.getString(context, "gui");
        CommandSender sender = context.getSource().getSender();

        for (Player target : targets) {
            try {
                target.getInventory().addItem(sessions.item(name));
            } catch (IllegalArgumentException e) {
                sender.sendRichMessage("<red>" + e.getMessage() + " - see <white>/mapgui hand open</white>.");
                return Command.SINGLE_SUCCESS;
            }
        }
        sender.sendRichMessage("<green>Gave the <white>" + name + "</white> item to "
                + (targets.size() == 1 ? targets.getFirst().getName() : targets.size() + " players") + ".");
        return Command.SINGLE_SUCCESS;
    }

    /** {@code open <gui> [players]}, or with no name the catalog of what there is. */
    private static ArgumentBuilder<CommandSourceStack, ?> open(SessionManager sessions) {
        return Commands.literal("open")
                .executes(context -> {
                    Listing.send(context.getSource().getSender(), "GUIs you can open",
                            "click one to open it", offered(sessions),
                            "<gray>No plugin has registered a GUI to open. A plugin does that with "
                                    + "<white>MapGui.get().guis().registerOpenable(..)</white>."
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("gui", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            for (GuiCatalog.Entry entry : sessions.guis().openable()) {
                                builder.suggest(entry.name());
                            }
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            Player self = self(context);
                            return self == null ? Command.SINGLE_SUCCESS : give(context, sessions, List.of(self));
                        })
                        .then(Commands.argument("players", ArgumentTypes.players())
                                .executes(context -> give(context, sessions, resolve(context)))
                        )
                );
    }

    /** Whoever it lands on, which is the sender unless a selector said otherwise. */
    private static int give(CommandContext<CommandSourceStack> context, SessionManager sessions, List<Player> targets) {
        String name = StringArgumentType.getString(context, "gui");
        GuiCatalog.Entry entry = sessions.guis().get(name);
        CommandSender sender = context.getSource().getSender();

        if (entry == null || !entry.openable()) {
            sender.sendRichMessage("<red>Nothing called '" + name + "' can be opened - see <white>/mapgui hand open</white>.");
            return Command.SINGLE_SUCCESS;
        }

        for (Player target : targets) {
            sessions.from(target, entry.open().apply(target), null, entry.name());
        }
        sender.sendRichMessage(targets.size() == 1
                ? "<green>Opened <white>" + name + "</white> for " + targets.getFirst().getName() + "."
                : "<green>Opened <white>" + name + "</white> for " + targets.size() + " players."
        );
        return Command.SINGLE_SUCCESS;
    }

    /** The way out of a GUI that a buggy plugin left somebody stuck in. */
    private static ArgumentBuilder<CommandSourceStack, ?> close(SessionManager sessions) {
        return Commands.literal("close")
                .then(Commands.argument("players", ArgumentTypes.players())
                        .executes(context -> {
                            int closed = 0;
                            for (Player target : resolve(context)) {
                                if (sessions.session(target) == null) continue;

                                sessions.close(target);
                                closed++;
                            }
                            context.getSource().getSender().sendRichMessage(closed == 0
                                    ? "<gray>Nothing to close."
                                    : "<green>Closed " + closed + " GUI" + (closed == 1 ? "" : "s") + "."
                            );
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> list(SessionManager sessions) {
        return Commands.literal("list")
                .executes(context -> {
                    int count = sessions.sessions().size();
                    Listing.send(context.getSource().getSender(), count + " GUI" + (count == 1 ? "" : "s") + " open",
                            "click a name to close it", inHands(sessions), "<gray>Nobody has a GUI open."
                    );
                    return Command.SINGLE_SUCCESS;
                });
    }

    private static List<Player> resolve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return context.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(context.getSource());
    }

    /** One line per openable GUI, clickable to open it on yourself. */
    private static List<Component> offered(SessionManager sessions) {
        List<Component> lines = new ArrayList<>();
        for (GuiCatalog.Entry entry : sessions.guis().openable()) {
            lines.add(Component.text("  " + entry.name() + "  ", NamedTextColor.WHITE)
                    .append(Component.text(entry.description(), NamedTextColor.DARK_GRAY))
                    .clickEvent(ClickEvent.runCommand("/mapgui hand open " + entry.name()))
                    .hoverEvent(Component.text("Click to open " + entry.name(), NamedTextColor.GRAY))
            );
        }
        return lines;
    }

    /**
     * One line per open GUI: who has it, and what it is.
     *
     * <p>The title rather than the class name, because that is what the player is looking at - with the catalog
     * name beside it when an admin opened it, since that is what would open it again.
     */
    private static List<Component> inHands(SessionManager sessions) {
        List<Component> lines = new ArrayList<>();
        for (PlayerSession session : sessions.open()) {
            String title = PlainTextComponentSerializer.plainText().serialize(session.screen().title());
            String from = session.openedFrom();

            lines.add(Component.text("  " + session.player().getName() + "  ", NamedTextColor.WHITE)
                    .append(Component.text(title + (from == null ? "" : "  (" + from + ")"), NamedTextColor.DARK_GRAY))
                    .clickEvent(ClickEvent.runCommand("/mapgui hand close " + session.player().getName()))
                    .hoverEvent(Component.text("Click to close it", NamedTextColor.GRAY))
            );
        }
        return lines;
    }

    /** Opening on yourself needs you to be somebody. */
    @Nullable
    private static Player self(CommandContext<CommandSourceStack> context) {
        if (context.getSource().getSender() instanceof Player player) return player;

        context.getSource().getSender().sendRichMessage("<red>Name a player to open it for.");
        return null;
    }
}
