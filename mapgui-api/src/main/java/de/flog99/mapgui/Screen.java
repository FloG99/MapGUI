package de.flog99.mapgui;

import de.flog99.mapgui.ui.AbstractNode;
import de.flog99.mapgui.ui.Animator;
import de.flog99.mapgui.ui.Easing;
import de.flog99.mapgui.ui.LayoutContext;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.Nodes;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Scroll;
import de.flog99.mapgui.ui.State;
import de.flog99.mapgui.ui.TextField;
import de.flog99.mapgui.ui.TextFont;
import de.flog99.mapgui.ui.Theme;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A menu. Subclasses describe what it looks like in {@link #build()} and the framework decides
 * when to run it again.
 *
 * <p>{@code build()} is called when state changes, not every tick. Hover is a paint-time style
 * variant and never triggers a rebuild.
 */
public abstract class Screen {

    private static final int SCROLL_STEP = 12;

    /** How long a click keeps a node looking pressed. Three ticks - long enough to see, short enough
     * not to lag behind the finger. */
    private static final int PRESS_MS = 150;

    /** Layout passes a node can be missing for before its state goes. Matches the animator's rule. */
    private static final int FORGET_AFTER_PASSES = 2;

    private Session session;
    private Node root;
    private Node hovered;
    private boolean dirty = true;
    private int cursorX = -1;
    private int cursorY = -1;

    /** What this screen is watching, so it can be let go of when the screen closes. */
    private final Set<SharedModel> watching = new LinkedHashSet<>();

    /**
     * State that belongs to a node but has to outlive it, since the tree is thrown away and rebuilt on
     * every change. Keyed by {@code identity()} - a node's {@code key} if it set one, otherwise its
     * position in the tree.
     */
    private final Map<String, NodeState> nodeStates = new HashMap<>();

    /** Layout passes so far, which is how long a node has to be absent before its state is dropped. */
    private long pass;

    /** Clocks are milliseconds since the epoch, so zero is safely "never happened". */
    private static final long NEVER = 0;

    private static final class NodeState {
        int scrollOffset;
        long pressedAt = NEVER;
        long seen;
    }

    /** Animation state also outlives the nodes it belongs to, for the same reason. */
    private final Animator animator = new Animator();

    /** Describe the screen. Called again whenever it is invalidated. */
    protected abstract Node build();

    /** Name shown on the map item. */
    public Component title() {
        return Component.text("Map");
    }

    /** Draw the surrounding world underneath the layout. */
    public boolean terrain() {
        return false;
    }

    /** Palette this screen builds against. Override to restyle without touching the layout. */
    public Theme theme() {
        return Theme.DARK;
    }

    /**
     * The font this screen's text is measured and drawn with. The vanilla map font unless overridden.
     *
     * <p>Override with an {@link de.flog99.mapgui.ui.AwtFont} to use a TrueType file your plugin ships, at
     * whatever size the design wants. Load it once and hand back the same instance - it caches a rasterized
     * glyph per character, so a font built per call would rasterize the alphabet again every frame:
     *
     * <pre>{@code
     * private static final TextFont TITLE = AwtFont.load(MyPlugin.font(), 16f, true);
     *
     * @Override
     * public TextFont font() {
     *     return TITLE;
     * }
     * }</pre>
     *
     * <p>One font per screen rather than per label, because measuring and painting have to agree about what
     * text is: a layout sized with one font and drawn with another puts the words in the wrong place. For a
     * heading in a different face, draw it with {@link ComponentText} inside a {@code Draw} node.
     */
    public TextFont font() {
        return MapTextFont.INSTANCE;
    }

    /** Fill color used when {@link #terrain()} is false. */
    public Color background() {
        return theme().background();
    }

    /** How many blocks each pixel covers when drawing terrain. */
    public int blocksPerPixel() {
        return 1;
    }

    /**
     * Whether this screen has a cursor at all.
     *
     * <p>Off means no pointer is drawn and no node is interactive - no hover, no scrolling - and the player's
     * head is left alone, since there is nothing to aim at. For anything meant to be looked at rather than
     * operated: a photo, a video, a minimap that is all terrain and no buttons.
     *
     * <p>Clicks still arrive, but only at {@link #clickedAnywhere}, which is how a cursorless screen can have a
     * shutter without asking the player to give up their aim. Q still closes either way.
     */
    public boolean cursor() {
        return true;
    }

    /**
     * Which mouse button presses things on this screen. Right-click alone by default, since it is the
     * one that does not jog the map - see {@link Click}.
     */
    public Click activateOn() {
        return Click.RIGHT;
    }

    /**
     * How this screen wants to be carried - a popup, an item, something worn in the offhand - or null to
     * follow the server's setting.
     *
     * <p>A screen that is a menu should leave this alone: the default is a popup, and a popup is what a menu
     * wants. Override it for a screen that is meant to be carried about rather than opened, and read
     * {@link HandOptions} first, because the choice decides more than where the map sits - a popup owns the
     * player's clicks and their scroll wheel, and nothing else does.
     */
    @Nullable
    public HandOptions hand() {
        return null;
    }

    /**
     * Whether to hold the player's head inside the cursor's pitch range, or null to follow the
     * server's setting.
     *
     * <p>Worth turning off for anything meant to be used while moving about - a HUD or a minimap has no
     * business taking over someone's aim. The cursor then simply stops at the edge instead, and
     * everything is still reachable by looking inside the range.
     *
     * <p>Ignored when {@link #cursor()} is off, since there is nothing to aim at either way, and <b>ignored in the
     * offhand</b>: a map held there is glanced at while the player looks at the world, so their aim is left alone
     * whatever this says.
     */
    @Nullable
    public Boolean clampPitch() {
        return null;
    }

    /**
     * Ceiling on frames driven by animation, or 0 for the server's setting.
     *
     * <p>Input never comes through here - a click or a hover repaints at once whatever this says - so
     * lowering it cannot make a menu feel less responsive, only cheaper to run. 20 is the most a map
     * can do.
     *
     * <p>The server's own setting is a ceiling, so asking for more than it allows gets you its number
     * instead. Asking for less always works.
     */
    public int fps() {
        return 0;
    }

    /**
     * The same for looping effects only - {@link #phase} and sliding text - which is where the
     * bandwidth actually goes, since they never stop. 0 for the server's setting, which again caps you.
     */
    public int loopFps() {
        return 0;
    }

    /**
     * Played to the player when a click lands on something, or null for silence.
     *
     * <p>Only when it actually hits an interactive node - clicking the background stays quiet, so the
     * sound means the same thing as the flash.
     */
    @Nullable
    public Sound clickSound() {
        return Sound.UI_BUTTON_CLICK;
    }

    /** Client-drawn icons on top of the pixels. */
    public List<Marker> markers() {
        return List.of();
    }

    protected void onOpen() {
    }

    protected void onClose() {
    }

    /**
     * Whether to keep drawing frames even though nothing has changed.
     *
     * <p>For an animation running off its own clock: a sequence with stages, a countdown, anything reading the time
     * while it paints. {@link #animate} and {@link #phase} already ask for their own frames, so this is for what they
     * cannot express - and without it the only way to get frames is a repeating task calling {@link #invalidate()}.
     *
     * <p>Frames still respect {@link #fps()} and the server's ceiling. Return false the moment the animation is over:
     * while it is true the screen is laid out and painted again every frame, forever.
     */
    protected boolean keepDrawing() {
        return false;
    }

    /**
     * Called when the player starts or stops holding sneak, and not otherwise.
     *
     * <p>Sneak is the one gesture that costs a screen nothing, since it moves no part of the aim - so it is what a map
     * ends up using as its modifier: a cursor that appears only while it is held, a wheel that means zoom.
     * {@link #sneaking()} is the same thing as a value, for a build or a paint to read.
     *
     * <p>Override it to {@link #invalidate()} if what is drawn depends on it. Nothing happens by default, since
     * repainting every open screen whenever anybody crouches would be a frame for nothing on most of them.
     */
    protected void onSneak(boolean sneaking) {
    }

    /** Whether the player is holding sneak right now. */
    protected final boolean sneaking() {
        return session != null && session.player().isSneaking();
    }

    /**
     * Redraws this screen whenever {@code model} changes, for as long as the screen is open.
     *
     * <p>What makes one thing shared by several screens actually look shared - a claim map that updates while
     * someone else is holding theirs, two jukeboxes agreeing on the track. Call it from {@link #onOpen()}.
     *
     * <p>There is deliberately no way to stop: closing does it, and doing it by hand is where the leak was.
     */
    protected final void watch(SharedModel model) {
        if (watching.add(model)) {
            model.watchedBy(this);
        }
    }

    /** State that repaints the screen when it changes. */
    protected final <T> State<T> state(T initial) {
        State<T> value = new State<>(initial);
        value.onChange(this::invalidate);
        return value;
    }

    public final void invalidate() {
        dirty = true;
    }

    /** Eases values across rebuilds. Turn it off with {@code animator().enabled(false)}. */
    public final Animator animator() {
        return animator;
    }

    /**
     * Eases a number of your own toward {@code target}, for anything the widgets don't cover -
     * a bar that fills, a value that counts up. Safe to call while painting.
     */
    protected final double animate(String key, double target) {
        return animate(key, target, Animator.DEFAULT_DURATION_MS, Easing.EASE_OUT);
    }

    protected final double animate(String key, double target, int millis, Easing easing) {
        return animator.value(key, target, millis, easing);
    }

    /**
     * A 0..1 value that loops every {@code periodMillis}, for animations that never arrive.
     *
     * <p><b>Using this keeps the screen repainting for as long as it is called, which in game means a
     * map update every tick, forever.</b> A full-canvas effect sends 16 KB a frame - about 320 KB/s,
     * or 2.6 Mbit/s, per player - because that is how big the changed rectangle is. Confine it to a
     * small node and the same effect costs a twentieth of that. Fine for a showpiece either way, but
     * worth a thought for a menu people leave open, and worth doing the multiplication before putting
     * one on a shared wall.
     */
    protected final double phase(int periodMillis) {
        return animator.phase(periodMillis);
    }

    /** The same for a color, so a {@code Draw} node can ease its own palette. */
    protected final Color animateColor(String key, Color target) {
        return animateColor(key, target, Animator.DEFAULT_DURATION_MS, Easing.EASE_OUT);
    }

    protected final Color animateColor(String key, Color target, int millis, Easing easing) {
        return animator.color(key, target, millis, easing);
    }

    public final Session session() {
        return session;
    }

    /**
     * How big the canvas is, in pixels.
     *
     * <p>Not a constant, which is the point: 128 square in someone's hand, but a wall is as big as the grid
     * an admin sized it to. Anything centering itself or drawing to the edges wants these rather than 128.
     */
    protected final int width() {
        return session.width();
    }

    protected final int height() {
        return session.height();
    }

    protected final Player player() {
        return session.player();
    }

    protected final void close() {
        session.close();
    }

    // ---- framework hooks ----

    @ApiStatus.Internal
    public final void attach(Session value) {
        this.session = value;
        onOpen();
    }

    /** Models go first, so nothing can call back into a screen that is halfway through closing. */
    @ApiStatus.Internal
    public final void detach() {
        for (SharedModel model : watching) model.forgottenBy(this);
        watching.clear();
        onClose();
    }

    @ApiStatus.Internal
    public final boolean isDirty() {
        return dirty;
    }

    @ApiStatus.Internal
    public final void layout(TextFont font, Rect viewport) {
        root = build();
        assignPaths(root, "0");
        Nodes.walk(root, node -> {
            if (node instanceof TextField field) {
                field.attachEditor(session::edit);
            }

            NodeState state = stateOf(node.identity());
            if (node instanceof Scroll scroll) {
                scroll.offset(state.scrollOffset);
            }
            if (node instanceof AbstractNode<?> concrete) {
                concrete.pressed(pressAmount(state));
            }
        });

        pass++;
        nodeStates.values().removeIf(state -> pass - state.seen > FORGET_AFTER_PASSES);
        animator.beginLayout();
        LayoutContext context = new LayoutContext(font, animator);
        root.measure(context, viewport.width(), viewport.height());
        root.arrange(context, viewport);
        hovered = null;
        dirty = false;
    }

    /**
     * Names each node by where it sits in the tree, so its animations can be found again after a
     * rebuild. A node with a {@code key} uses that instead, which is what keeps its animation
     * attached when the tree changes shape around it.
     *
     * <p>Iterative so layout stays off the call stack however deep a screen builds its tree, and each
     * path string is built exactly as the recursive version did - {@code path + '.' + i} at every step.
     *
     * <p>Package-private so an A/B test can compare the name assigned by the iterative rewrite with the
     * recursive one; not part of the public API.
     */
    @ApiStatus.Internal
    static void assignPaths(Node node, String path) {
        Deque<PathFrame> stack = new ArrayDeque<>();
        stack.push(new PathFrame(node, path));

        while (!stack.isEmpty()) {
            PathFrame frame = stack.pop();
            Node current = frame.node;
            String currentPath = frame.path;

            if (current instanceof AbstractNode<?> concrete) {
                concrete.path(currentPath);
            }

            // Pushed in reverse so assignment order matches the recursive walk exactly.
            List<Node> children = current.children();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(new PathFrame(children.get(i), currentPath + '.' + i));
            }
        }
    }

    /** One node and the path assigned to it, for {@link #assignPaths}. */
    private record PathFrame(Node node, String path) {
    }

    /** True while something is still easing, which is the cue to keep drawing frames. */
    @ApiStatus.Internal
    public final boolean animating() {
        return animator.animating() || pressing() || keepDrawing();
    }

    @ApiStatus.Internal
    public final void sneakChanged(boolean sneaking) {
        onSneak(sneaking);
    }

    /** Kept alive for as long as the node keeps being built, and a couple of passes after. */
    private NodeState stateOf(String identity) {
        NodeState state = nodeStates.computeIfAbsent(identity, ignored -> new NodeState());
        state.seen = pass;
        return state;
    }

    /** 1 immediately after a click on this node, fading to 0 over {@link #PRESS_MS}. */
    private Click clicking = Click.RIGHT;

    private double pressAmount(NodeState state) {
        if (state.pressedAt == NEVER) return 0;

        double elapsed = animator.now() - state.pressedAt;
        return elapsed >= PRESS_MS ? 0 : 1 - elapsed / PRESS_MS;
    }

    /** Whether any press is still fading, which is the cue to keep drawing frames for it. */
    private boolean pressing() {
        for (NodeState state : nodeStates.values()) {
            if (state.pressedAt != NEVER && animator.now() - state.pressedAt < PRESS_MS) return true;
        }
        return false;
    }

    /**
     * The node tree on its own, for rendering this screen outside a server. Screens that read
     * live player state can't be built this way.
     */
    @ApiStatus.Internal
    public final Node previewTree() {
        return build();
    }

    /** The laid-out tree, for tooling that wants to inspect what ended up where. */
    @ApiStatus.Internal
    public final Node root() {
        return root;
    }

    @ApiStatus.Internal
    public final void paint(Painter painter) {
        if (root != null) {
            root.paint(painter);
        }
    }

    /** Returns true if the hovered node changed, i.e. a repaint is needed. */
    @ApiStatus.Internal
    public final boolean cursorMoved(int x, int y) {
        Node hit = root == null ? null : root.hitTest(x, y);
        boolean moved = x != cursorX || y != cursorY;
        cursorX = x;
        cursorY = y;

        // Still the same node, so nothing has changed unless it draws at the cursor itself.
        if (hit == hovered) return moved && hit != null && hit.tracksCursor();

        if (hovered != null) {
            hovered.hoverChanged(false);
        }
        hovered = hit;
        if (hit != null) {
            hit.hoverChanged(true);
        }
        return true;
    }

    /** Where the cursor is, for screens that draw something at it. */
    protected final int cursorX() {
        return cursorX;
    }

    protected final int cursorY() {
        return cursorY;
    }

    /** Cursor the hovered node asked for, or null for the default. */
    @ApiStatus.Internal
    public final String cursorIcon() {
        return hovered == null ? null : hovered.cursorIcon();
    }

    /** Text the hovered node wants under the cursor, or null. */
    @ApiStatus.Internal
    public final String cursorCaption() {
        return hovered == null ? null : hovered.caption();
    }

    /**
     * A click before any node sees it, and the only kind a screen with no cursor gets at all.
     *
     * <p>For a press that should not depend on aim: a shutter, a confirm, a dismiss. Aiming a cursor costs the
     * player their pitch - the vertical axis <i>is</i> their head - so a screen whose whole job is pointing at
     * the world cannot also ask them to point at a button.
     *
     * @param x -1 when the screen has no cursor, since then there is no position to report
     * @return true to keep the click, which stops it reaching a node
     */
    protected boolean clickedAnywhere(int x, int y, Click with) {
        return false;
    }

    @ApiStatus.Internal
    public final boolean click(int x, int y, Click with) {
        clicking = with;
        if (clickedAnywhere(x, y, with)) return true;
        if (x < 0 || y < 0) return false;

        Node hit = root == null ? null : root.hitTest(x, y);
        if (hit == null) return false;

        stateOf(hit.identity()).pressedAt = animator.now();
        hit.click(x - hit.bounds().x(), y - hit.bounds().y());
        return true;
    }

    /**
     * Which button is being delivered, for a handler that wants to do two things.
     *
     * <p>Only meaningful during a click, and only ever anything but {@link Click#RIGHT} if
     * {@link #activateOn()} was widened to accept more. Bear in mind that a left-click swings the arm,
     * which jogs the map down - fine for an occasional action, wrong for the main one.
     */
    protected final Click clickedWith() {
        return clicking;
    }

    @ApiStatus.Internal
    public final boolean scroll(int x, int y, int direction) {
        Scroll scroll = Nodes.findAt(root, Scroll.class, x, y);
        if (scroll == null) return onScroll(direction);
        if (!scroll.scrollBy(direction * SCROLL_STEP)) return false;

        stateOf(scroll.identity()).scrollOffset = scroll.offset();
        invalidate();
        return true;
    }

    /**
     * The wheel, when it was not over anything scrollable.
     *
     * <p>For screens where the wheel means something of its own - stepping through a palette, zooming a
     * map. A {@link de.flog99.mapgui.ui.Scroll} under the cursor always wins, so this only ever sees turns
     * that had nowhere else to go.
     *
     * <p>Return true if you used it. On a wall that also decides whether the player's hotbar selection is
     * left alone, so saying you used a turn you ignored would stop them changing items.
     */
    protected boolean onScroll(int notches) {
        return false;
    }

    @ApiStatus.Internal
    public final void swapHands() {
        onSwapHands();
    }

    /**
     * The swap-hands key, when MapGUI had no use for it: a press that costs no aim, unlike a button on the map.
     *
     * <p>Only for a map with nothing to swap, and never under {@link HandOptions.Focus#SWAP_HANDS}, where the key
     * is the focus toggle.
     */
    protected void onSwapHands() {
    }
}
