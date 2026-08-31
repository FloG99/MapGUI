package de.flog99.mapgui.plugin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import de.flog99.mapgui.plugin.camera.CameraCommand;
import de.flog99.mapgui.plugin.camera.CameraReport;
import de.flog99.mapgui.plugin.wall.WallCommand;
import de.flog99.mapgui.plugin.wall.WallManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /mapgui} - administration, and nothing a normal player runs.
 *
 * <p>Grouped by <i>where</i> the GUI is, then what to do to it. The two places a map can be are the two
 * groups, and each takes the same three verbs:
 *
 * <pre>
 *   hand open &lt;gui&gt; [players]     hand close &lt;players&gt;     hand list
 *   wall place &lt;content&gt;          wall remove [name]       wall list
 * </pre>
 *
 * <p>Noun first because it is the axis that never grows: a fourth verb costs nothing, where a flat tree spends
 * a top-level word on every one. Within a group, {@code list} is what exists right now and the verb that puts
 * one somewhere lists what it could take when given no argument.
 *
 * <p>What an admin can offer comes from {@link de.flog99.mapgui.GuiCatalog}, so this works for any plugin's
 * GUIs without that plugin writing a command - reaching ordinary players stays the plugin's own job, through
 * {@code MapGui#open}.
 *
 * <p>Registered once, as one tree. Registering the same literal twice instead - which this used to do, from
 * here and from a bootstrapper - makes Brigadier merge them and keep only the first one's permission check, so
 * whichever happened to register first silently decided who could run the other.
 */
final class MapGuiCommand {

    /** Named once, since the root has to know what it can offer and the help has to list the same set. */
    private record Sub(String name, String permission, String description) {
    }

    private static final Sub HAND = new Sub("hand", "mapgui.command.hand", "GUIs in a player's hand");
    private static final Sub WALL = new Sub("wall", "mapgui.command.wall", "GUIs and pictures on blocks");
    private static final Sub CAMERA = new Sub("camera", "mapgui.command.camera", "the textures a capture draws with, and what captures cost");
    private static final Sub STATUS = new Sub("status", "mapgui.command.status", "what is happening right now");
    private static final Sub PERFORMANCE = new Sub("performance", "mapgui.command.performance", "what it is costing in bandwidth");
    private static final Sub RELOAD = new Sub("reload", "mapgui.command.reload", "re-read config.yml");

    private static final List<Sub> ALL = List.of(HAND, WALL, CAMERA, STATUS, PERFORMANCE, RELOAD);

    private MapGuiCommand() {
    }

    static LiteralCommandNode<CommandSourceStack> build(MapGuiPlugin plugin, SessionManager sessions,
                                                        WallManager walls, CommandSurface surface,
                                                        Supplier<List<Component>> performance) {
        return Commands.literal("mapgui")
                // Hidden from anyone who could not run a single thing under it, rather than gated on one
                // permission that would then have to be granted to reach any of the others.
                .requires(source -> ALL.stream().anyMatch(sub -> shows(source.getSender(), sub, surface)))
                .executes(context -> {
                    help(context.getSource().getSender(), surface);
                    return Command.SINGLE_SUCCESS;
                })
                .then(HandCommand.hand(sessions, source -> shows(source.getSender(), HAND, surface)))
                .then(WallCommand.wall(walls, source -> shows(source.getSender(), WALL, surface)))
                // The plugin rather than the two objects: a reload replaces the service, and a command tree that
                // captured the old one would quietly go on driving something nothing else can see.
                .then(CameraCommand.camera(plugin, source -> shows(source.getSender(), CAMERA, surface)))
                .then(status(plugin, sessions, walls))
                .then(performance(performance))
                .then(reload(plugin))
                .build();
    }

    private static boolean allowed(CommandSender sender, Sub sub) {
        return sender.hasPermission(sub.permission());
    }

    /**
     * Permission, and then whether this server has anything for the branch to administer.
     *
     * <p>Two different questions with the same answer for a reader - the command is not there - and they are kept
     * apart on purpose: no permission means somebody decided you may not, and nothing to administer means there is
     * nothing to decide about. Only the second one can change while the server is up.
     */
    private static boolean shows(CommandSender sender, Sub sub, CommandSurface surface) {
        if (!allowed(sender, sub)) return false;

        if (sub == HAND) return surface.hand();
        if (sub == WALL) return surface.wall();
        if (sub == CAMERA) return surface.camera();

        return true;
    }

    /** Only what the reader can actually run, since a list of things that answer "no permission" helps nobody. */
    private static void help(CommandSender sender, CommandSurface surface) {
        List<Listing.Choice> choices = new ArrayList<>();
        for (Sub sub : ALL) {
            if (shows(sender, sub, surface)) {
                choices.add(new Listing.Choice("/mapgui " + sub.name(), sub.description()));
            }
        }

        Listing.choices(sender, "MapGUI", choices);
    }

    /**
     * What is happening right now, and nothing that is merely configured - config.yml already says that.
     *
     * <p>Walls are counted up against saved rather than just up, because the gap is the whole point: a wall
     * that is saved and not showing means its content is missing, which is the one thing here worth chasing.
     *
     * <p>The backend always earns its line. A server MapGUI has no module for fails to enable and says so, but a
     * successful load says nothing about <i>which</i> version's internals won - and that is the first thing worth
     * knowing when a report names a Minecraft version.
     *
     * <p>The camera earns a line only when it is failing. Captures belong to whichever plugin asks for them, so a
     * working one is that plugin's business - but a failing one is invisible everywhere else, since from outside it
     * looks exactly like a camera nothing is using.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> status(MapGuiPlugin plugin, SessionManager sessions, WallManager walls) {
        return Commands.literal(STATUS.name())
                .requires(source -> allowed(source.getSender(), STATUS))
                .executes(context -> {
                    int held = sessions.sessions().size();
                    int up = walls.showing().size();
                    int saved = walls.names().size();

                    Component line = Component.text("MapGUI  ", NamedTextColor.GOLD)
                            .append(Component.text(held + " in hand  ", NamedTextColor.WHITE))
                            .append(Component.text(up + " of " + saved + " wall" + (saved == 1 ? "" : "s") + " up", up == saved ? NamedTextColor.WHITE : NamedTextColor.YELLOW));

                    if (up < saved) {
                        line = line.append(Component.text("  - see /mapgui wall list", NamedTextColor.DARK_GRAY));
                    }
                    context.getSource().getSender().sendMessage(line);

                    String version = Bukkit.getMinecraftVersion();
                    context.getSource().getSender().sendMessage(Component.text("Backend  ", NamedTextColor.GOLD)
                            .append(Component.text(Backends.family(version) + " family, server " + version + "  ", NamedTextColor.WHITE))
                            .append(Component.text(plugin.backend().getClass().getName(), NamedTextColor.DARK_GRAY))
                    );

                    Component camera = CameraReport.trouble(plugin.camera().stats());
                    if (camera != null) {
                        context.getSource().getSender().sendMessage(camera);
                    }
                    return Command.SINGLE_SUCCESS;
                });
    }

    /** What the plugin is costing right now, with coordinates you can click to go and look. */
    private static ArgumentBuilder<CommandSourceStack, ?> performance(Supplier<List<Component>> report) {
        return Commands.literal(PERFORMANCE.name())
                .requires(source -> allowed(source.getSender(), PERFORMANCE))
                .executes(context -> {
                    for (Component line : report.get()) context.getSource().getSender().sendMessage(line);
                    return Command.SINGLE_SUCCESS;
                });
    }

    /**
     * Re-reads config.yml and applies it, including to walls that are already up.
     *
     * <p>Which is how frame rates get lowered on a server that is struggling: edit the file, run this, and
     * every wall slows down without being taken apart. Menus already open keep the rate they started with.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> reload(MapGuiPlugin plugin) {
        return Commands.literal(RELOAD.name())
                .requires(source -> allowed(source.getSender(), RELOAD))
                .executes(context -> {
                    plugin.reload();
                    context.getSource().getSender().sendRichMessage("<green>Reloaded config.yml.");
                    return Command.SINGLE_SUCCESS;
                });
    }
}
