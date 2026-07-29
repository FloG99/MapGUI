package de.flog99.mapgui.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * Printing a list of things to whoever asked for it, the same way every time.
 *
 * <p>Every command here answers with a list and every one of them has an empty case, so the shape is worth
 * saying once: a heading, a hint about what you can do with the lines, then the lines - or, when there
 * are none, the one sentence that says what to do about it instead of an empty heading.
 */
public final class Listing {

    private Listing() {
    }

    public static void send(CommandSender to, String heading, String hint, List<Component> lines,
                            String whenEmpty) {
        if (lines.isEmpty()) {
            to.sendRichMessage(whenEmpty);
            return;
        }

        to.sendMessage(Component.text(heading, NamedTextColor.GOLD).append(Component.text("  " + hint, NamedTextColor.DARK_GRAY)));
        for (Component line : lines) to.sendMessage(line);
    }

    /** A command and what it does, for the help each level of the tree prints for the level below it. */
    public record Choice(String command, String description) {
    }

    /**
     * The help one node prints for its children - {@code /mapgui} for its groups, a group for its verbs.
     *
     * <p>Suggested rather than run, since every one of these needs an argument or is worth reading before
     * pressing.
     */
    public static void choices(CommandSender to, String heading, List<Choice> choices) {
        List<Component> lines = new ArrayList<>();
        for (Choice choice : choices) {
            lines.add(Component.text("  " + choice.command() + "  ", NamedTextColor.WHITE)
                    .append(Component.text(choice.description(), NamedTextColor.DARK_GRAY))
                    .clickEvent(ClickEvent.suggestCommand(choice.command()))
            );
        }
        send(to, heading, "click a command to fill it in", lines, "<gray>Nothing you can run.");
    }
}
