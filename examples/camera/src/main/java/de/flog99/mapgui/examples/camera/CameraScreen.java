package de.flog99.mapgui.examples.camera;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraAssets;
import de.flog99.mapgui.camera.CameraOptions;
import de.flog99.mapgui.camera.CameraShot;
import de.flog99.mapgui.map.MapPrinter;
import de.flog99.mapgui.media.VideoPlayer;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Colors;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.map.MapCursor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static de.flog99.mapgui.ui.Ui.Button;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.Spinner;
import static de.flog99.mapgui.ui.Ui.Text;

/**
 * Aim with your head, left-click or sneak to capture, right-click for settings, swap hands to put it away.
 *
 * <p>The input model is the point of this example. A cursor's vertical axis <i>is</i> the player's pitch, so a
 * screen that asks them to press a button also decides where they are looking - which is useless for a camera.
 * So aiming mode has no cursor at all and takes its shutter from {@link #clickedAnywhere}, and the settings
 * panel is a second mode that turns the cursor back on because there is something to point at.
 */
public final class CameraScreen extends Screen {

    private static final List<Integer> SIZES = List.of(Camera.MAP_SIZE, 96, 64);
    private static final List<Integer> FOVS = List.of(50, 70, 90, 110);

    /** Maps to a side for the print, which is four maps and the size a wall of them is worth hanging. */
    private static final int PRINT_ACROSS = 2;

    /** Far enough up that the client's label sits on the map rather than off the bottom of it. */
    private static final int HINT_INSET_Y = 8;

    private final State<CameraShot> shot = state(null);
    private final State<Boolean> capturing = state(false);
    private final State<Boolean> settings = state(false);
    private final State<CameraOptions> options = state(CameraOptions.defaults());
    private final State<String> notice = state(null);

    private boolean asked;

    @Override
    public Component title() {
        return Component.text("Camera", NamedTextColor.AQUA);
    }

    /**
     * In the offhand, so the main hand stays the player's while they line a shot up.
     *
     * <p>Up means up: an offhand map's default is for the swap key to toggle focus, which on a viewfinder shows
     * nothing at all - there is no cursor to appear - so the shutter was simply dead until the key had been pressed
     * once. A camera is a mode instead. It has the clicks from the moment it is raised, and the same key puts it away.
     */
    @Override
    public HandOptions hand() {
        return HandOptions.offhand().focus(HandOptions.Focus.ALWAYS);
    }

    /** Swapping hands, which an offhand map has nothing else to spend on - and it costs none of the aim a button would. */
    @Override
    protected void onSwapHands() {
        close();
    }

    /** Only the settings panel has anything to point at, so only it takes the player's aim. */
    @Override
    public boolean cursor() {
        return settings.get();
    }

    @Override
    public Click activateOn() {
        return Click.BOTH;
    }

    /**
     * Nothing over the picture while it is working.
     *
     * <p>No control hints: a viewfinder covered in captions is not a viewfinder, and the two controls are
     * left-click and right-click, which anyone finds in a second. What does earn a marker is the one state where
     * clicking cannot produce anything, since that is not discoverable and an operator can fix it.
     */
    @Override
    public List<Marker> markers() {
        if (settings.get() || !(MapGui.get().camera().assets() instanceof CameraAssets.Unavailable)) return List.of();

        return List.of(Marker.at(MapCursor.Type.RED_X, width() / 2, height() - HINT_INSET_Y)
                .label(player().hasPermission("mapgui.command.camera") ? "/mapgui camera" : "Ask an admin"));
    }

    /**
     * Left-click is the shutter and right-click opens the settings, wherever the player is looking. In settings
     * mode the widgets take right-click themselves, so only the shutter is handled here.
     */
    @Override
    protected boolean clickedAnywhere(int x, int y, Click with) {
        if (with == Click.LEFT) {
            take();
            return true;
        }

        if (!settings.get()) {
            settings.set(true);
            return true;
        }

        return false;
    }

    void sneaked() {
        take();
    }

    @Override
    protected Node build() {
        if (!asked) {
            asked = true;
            MapGui.get().camera().prepare();
        }

        return settings.get() ? settingsPanel() : viewfinder();
    }

    private Node viewfinder() {
        CameraShot taken = shot.get();
        if (taken != null) {
            return Draw(context -> new VideoPlayer(taken).fit(VideoPlayer.Fit.COVER).paint(context.painter(), context.bounds(), 0)).fill();
        }

        List<Node> waiting = new ArrayList<>();
        if (working()) {
            waiting.add(Spinner().color(new Color(190, 190, 190)));
        }
        waiting.add(Text(this::placeholder).color(new Color(190, 190, 190)).shadow());

        return Column(waiting).gap(3).justify(Justify.CENTER).align(Align.CENTER).fill();
    }

    /** Whether something is happening that will finish on its own, which is the only thing worth a spinner. */
    private boolean working() {
        return capturing.get() || MapGui.get().camera().assets() instanceof CameraAssets.Loading;
    }

    private Node settingsPanel() {
        CameraOptions current = options.get();

        return Column(
                Row(Text("Camera").color(Color.WHITE).shadow()).justify(Justify.CENTER).fillWidth(),
                setting("Size", current.size() + "px", () -> options.set(current.size(next(SIZES, current.size())))),
                setting("View", (int) current.fov() + " deg", () -> options.set(current.fov(next(FOVS, (int) current.fov())))),
                setting("Selfie", current.selfie() ? "on" : "off", () -> options.set(current.selfie(!current.selfie()))),
                setting("People", current.entities() ? "on" : "off", () -> options.set(current.entities(!current.entities()))),
                setting("Clouds", current.clouds() ? "on" : "off", () -> options.set(current.clouds(!current.clouds()))),
                setting("Haze", current.fog() ? "on" : "off", () -> options.set(current.fog(!current.fog()))),
                setting("Print", PRINT_ACROSS + " by " + PRINT_ACROSS + " maps", this::print),
                Spacer(),
                // Back and Close, because the viewfinder has no cursor and both of its clicks are the shutter and
                // this panel - so without a button here there is nothing a player can point at to put the map down.
                Row(
                        Button("Back").background(theme().accent()).radius(3).textColor(Color.WHITE)
                                .onClick(() -> settings.set(false)).fillWidth(),
                        Button("Close").background(Colors.alpha(Color.BLACK, 120)).radius(3).textColor(Color.WHITE)
                                .onClick(this::close).fillWidth()
                ).gap(2).fillWidth()
        ).gap(2).padding(4).align(Align.STRETCH).fill();
    }

    private Node setting(String name, String value, Runnable onClick) {
        return Row(
                Text(name).color(new Color(190, 190, 190)),
                Spacer(),
                Text(value).color(Color.WHITE)
        ).padding(1, 2).background(Colors.alpha(Color.BLACK, 120)).radius(2).onClick(onClick).fillWidth();
    }

    private static <T> T next(List<T> values, T current) {
        int at = values.indexOf(current);
        return values.get((at + 1) % values.size());
    }

    /**
     * Kept to about twenty characters: a map line has no more room than that and does not wrap. The full
     * sentence and the fix live in the console.
     */
    private String placeholder() {
        if (notice.get() != null) return notice.get();
        if (capturing.get()) return "Capturing";

        return switch (MapGui.get().camera().assets()) {
            case CameraAssets.Ready ignored -> "Aim and left-click";
            // No percentage. It is a 39 MB download that spends its first stretch at nought, and a number that
            // does not move reads as broken where a spinner reads as busy. The figure is in the console,
            // which is where somebody who wants one goes.
            case CameraAssets.Loading ignored -> "Loading textures";
            case CameraAssets.Unavailable ignored -> "No textures yet";
        };
    }

    /**
     * One capture cut into real maps to hang on a wall, which is what {@link MapPrinter} is for.
     *
     * <p>Asked for at exactly {@code across * 128} pixels so every tile is a whole map at one pixel per pixel: the
     * map is 128 pixels and nothing changes that, so the way to a bigger picture is more maps.
     */
    private void print() {
        if (capturing.get()) return;

        if (!MapGui.get().camera().assets().ready()) {
            notice.set("Textures are not installed yet");
            return;
        }

        capturing.set(true);
        notice.set(null);
        MapGui.get().camera().capture(player(), options.get().size(MapPrinter.sizeFor(PRINT_ACROSS)), taken -> {
            capturing.set(false);
            if (taken == null) {
                notice.set("Capture failed");
                return;
            }

            // Read off the shot rather than reusing the constant, since the cut has to follow the pixels that arrived.
            int grid = MapPrinter.mapsAcross(taken);
            if (grid == 0) {
                notice.set("That would not cut into whole maps");
                return;
            }

            player().sendMessage(Component.text(SnapshotTiles.give(player(), taken, grid) + " maps", NamedTextColor.GREEN)
                    .append(Component.text(" - place them in item frames in a " + grid + " by " + grid
                            + " square, the way their names say.", NamedTextColor.WHITE)));
        });
    }

    private void take() {
        if (capturing.get()) return;

        if (!MapGui.get().camera().assets().ready()) {
            MapGui.get().camera().prepare();
            return;
        }

        capturing.set(true);
        notice.set(null);
        MapGui.get().camera().capture(player(), options.get(), taken -> {
            capturing.set(false);
            if (taken == null) {
                notice.set("Capture failed");
                return;
            }
            shot.set(taken);
        });
    }
}
