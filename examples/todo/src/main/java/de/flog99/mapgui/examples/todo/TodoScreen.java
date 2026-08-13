package de.flog99.mapgui.examples.todo;

import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.prompt.TextPrompt;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.TextAlign;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static de.flog99.mapgui.ui.Ui.Box;
import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Divider;
import static de.flog99.mapgui.ui.Ui.Field;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Scroll;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Text;
import static de.flog99.mapgui.ui.Ui.Toggle;
import static de.flog99.mapgui.ui.Ui.each;

/**
 * A to-do list. Nothing here computes a coordinate - the layout engine places everything, so
 * adding or removing a row can't push anything else out of alignment.
 *
 * <p>Tasks live in memory, so they survive reopening the menu but not a restart.
 */
public final class TodoScreen extends Screen {

    private static final Map<UUID, List<Task>> STORE = new HashMap<>();

    private static final Color BG = new Color(20, 22, 30);
    private static final Color ACCENT = new Color(50, 100, 240);
    private static final Color ACCENT_LIGHT = new Color(96, 148, 255);
    private static final Color TEXT = new Color(238, 240, 245);
    private static final Color MUTED = new Color(150, 158, 175);
    private static final Color DANGER = new Color(206, 51, 51);
    private static final Color WHITE = Color.WHITE;

    /**
     * The id exists only to key the row that draws it.
     *
     * <p>Rows move as tasks are added and deleted, and a node with no key is identified by its position
     * in the tree - so without this, deleting the first task would hand its press flash and its color
     * animation to whatever slid up into its place.
     */
    private static final class Task {
        final String id = UUID.randomUUID().toString();
        String text;
        boolean done;

        Task(String text) {
            this.text = text;
        }
    }

    private final List<Task> tasks;

    public TodoScreen(Player player) {
        this.tasks = STORE.computeIfAbsent(player.getUniqueId(), key -> new ArrayList<>());
    }

    @Override
    public Component title() {
        return Component.text("To-Do", NamedTextColor.AQUA);
    }

    /** A popup, because the list scrolls: the plain wheel is the menu's own only where every slot shows the map. */
    @Override
    public HandOptions hand() {
        return HandOptions.popup();
    }

    @Override
    public Color background() {
        return BG;
    }

    @Override
    protected Node build() {
        return Column(
                header(),
                Divider(ACCENT),
                tasks.isEmpty()
                        ? Text("Nothing to do").color(MUTED).align(TextAlign.CENTER).fill()
                        : Scroll(each(tasks, task -> task.id, this::taskRow))
                                .gap(3).key("tasks").fill(),
                Button("+ New task")
                        .background(ACCENT).border(1, WHITE).radius(5).textColor(WHITE)
                        .hoverBackground(WHITE).hoverTextColor(ACCENT).hoverBorder(ACCENT)
                        .fillWidth()
                        .onClick(this::addTask)
        ).gap(4).padding(6).align(Align.STRETCH);
    }

    private Node header() {
        return Row(
                Box(ACCENT).size(7, 7).radius(2),
                Text("To-Do").color(WHITE).shadow(),
                Spacer(),
                Text(() -> completed() + "/" + tasks.size())
                        .color(WHITE)
                        .padding(1, 4)
                        .background(ACCENT)
                        .border(1, ACCENT_LIGHT)
                        .radius(5)
        ).gap(4).align(Align.CENTER);
    }

    /** The index is captured by the closures, so no ids or event names are needed. */
    private Node taskRow(Task task, int index) {
        return Row(
                Toggle(() -> task.done)
                        .boxSize(11)
                        .onChange(done -> {
                            task.done = done;
                            invalidate();
                        }),
                Field(() -> task.text)
                        .title("Edit task")
                        .maxLength(48)
                        .textColor(task.done ? MUTED : TEXT)
                        .onChange(text -> task.text = text)
                        .fillWidth(),
                Button("x")
                        .size(11, 11)
                        // A button's default padding is meant for a word; at 11x11 it would leave three pixels for the glyph.
                        .padding(0)
                        .radius(5)
                        .textColor(MUTED)
                        .hoverBackground(DANGER)
                        .hoverTextColor(WHITE)
                        .onClick(() -> {
                            tasks.remove(index);
                            invalidate();
                        })
        ).gap(4).align(Align.CENTER).key("task-" + index);
    }

    private void addTask() {
        session().promptText(TextPrompt.of("New task").maxLength(48), null, result -> result.ifPresent(text -> {
            tasks.add(new Task(text));
            invalidate();
        }));
    }

    private long completed() {
        return tasks.stream().filter(task -> task.done).count();
    }
}
