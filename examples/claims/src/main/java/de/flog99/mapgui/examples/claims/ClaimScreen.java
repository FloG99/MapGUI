package de.flog99.mapgui.examples.claims;

import de.flog99.mapgui.Click;
import de.flog99.mapgui.HandOptions;
import de.flog99.mapgui.Screen;
import de.flog99.mapgui.ui.Align;
import de.flog99.mapgui.ui.Colors;
import de.flog99.mapgui.ui.Justify;
import de.flog99.mapgui.ui.Node;
import de.flog99.mapgui.ui.PaintContext;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.List;

import static de.flog99.mapgui.ui.Ui.Box;
import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Overlay;
import static de.flog99.mapgui.ui.Ui.Row;
import static de.flog99.mapgui.ui.Ui.Spacer;
import static de.flog99.mapgui.ui.Ui.each;

/**
 * A claim map: the world underneath, claims tinted over it, and a chunk claimed by clicking it.
 *
 * <p>The map is one node. Rather than a widget per chunk, a single {@code Draw} covers the canvas and works
 * out which chunk a pixel belongs to - so the grid costs one node, and the hover highlight follows the
 * cursor pixel by pixel because that node {@code tracksCursor()}.
 */
public final class ClaimScreen extends Screen {

    /** One block per pixel, so the 128 pixel canvas is exactly eight chunks across. */
    private static final int CHUNK_PIXELS = 16;

    /** How much of the terrain still shows through a claim. Enough to keep the map readable. */
    private static final double TINT = 0.45;

    private final Claims claims;
    private final State<Team> picked = state(Team.RED);

    public ClaimScreen(Claims claims) {
        this.claims = claims;
    }

    /** So a claim taken by somebody else shows up on this map while it is being held, not eventually. */
    @Override
    protected void onOpen() {
        watch(claims);
    }

    @Override
    public Component title() {
        return Component.text("Claims", NamedTextColor.AQUA);
    }

    @Override
    public boolean terrain() {
        return true;
    }

    /** Stated rather than left to config, so the demo is the same whatever a server sets for everything else. */
    @Override
    public HandOptions hand() {
        return HandOptions.popup();
    }

    @Override
    protected Node build() {
        return Overlay(
                map(),
                Column(
                        Spacer(),
                        Row(each(List.of(Team.values()), Team::name, this::swatch))
                                .gap(2).justify(Justify.CENTER)
                                .padding(2).background(Colors.alpha(Color.BLACK, 170)).radius(3)
                ).align(Align.STRETCH).padding(3).fill()
        ).fill();
    }

    /**
     * The whole canvas as one node: claim tints, then a box round whatever chunk the cursor is over.
     *
     * <p>The caption is a supplier so it answers for the chunk under the cursor now, not when the tree was built.
     */
    private Node map() {
        return Draw(this::paintClaims)
                .tracksCursor(true)
                .caption(this::hoveredLabel)
                .onClick(this::claimAt)
                .fill();
    }

    private Node swatch(Team team) {
        return Box(team.color()).size(9, 9).radius(2)
                .border(1, picked.get() == team ? Color.WHITE : Colors.alpha(Color.BLACK, 120))
                .caption(team.label())
                .onClick(() -> picked.set(team));
    }

    // ---- painting ----

    private void paintClaims(PaintContext context) {
        Rect bounds = context.bounds();
        int originX = chunkAt(topLeftBlockX());
        int originZ = chunkAt(topLeftBlockZ());

        // Eight across and eight down, plus one in case the player is not stood on a chunk boundary.
        for (int column = 0; column <= bounds.width() / CHUNK_PIXELS; column++) {
            for (int row = 0; row <= bounds.height() / CHUNK_PIXELS; row++) {
                int chunkX = originX + column;
                int chunkZ = originZ + row;
                Team owner = claims.at(chunkX, chunkZ);
                if (owner == null) continue;

                Rect rect = chunkRect(bounds, chunkX, chunkZ);
                tint(context.painter(), rect, owner.color());
                outline(context.painter(), rect, chunkX, chunkZ, owner);
            }
        }

        highlight(context.painter(), bounds);
    }

    /**
     * A hard edge on each side where the neighbour is not part of the same claim, which makes a block of
     * chunks read as one territory rather than a grid. Four lookups a chunk, no flood fill.
     */
    private void outline(Painter painter, Rect rect, int chunkX, int chunkZ, Team owner) {
        Color edge = Colors.mix(owner.color(), Color.WHITE, 0.35);

        if (!sameTeam(owner, chunkX, chunkZ - 1)) {
            line(painter, rect.x(), rect.y(), 1, 0, edge);
        }
        if (!sameTeam(owner, chunkX, chunkZ + 1)) {
            line(painter, rect.x(), rect.y() + rect.height() - 1, 1, 0, edge);
        }
        if (!sameTeam(owner, chunkX - 1, chunkZ)) {
            line(painter, rect.x(), rect.y(), 0, 1, edge);
        }
        if (!sameTeam(owner, chunkX + 1, chunkZ)) {
            line(painter, rect.x() + rect.width() - 1, rect.y(), 0, 1, edge);
        }
    }

    /** The same team, so two teams side by side each keep their own outline. */
    private boolean sameTeam(Team owner, int chunkX, int chunkZ) {
        return claims.at(chunkX, chunkZ) == owner;
    }

    private static void line(Painter painter, int x, int y, int stepX, int stepY, Color color) {
        for (int i = 0; i < CHUNK_PIXELS; i++) {
            painter.pixel(x + stepX * i, y + stepY * i, color);
        }
    }

    /** Blends over what the terrain already drew, which is what makes the overlay read as translucent. */
    private static void tint(Painter painter, Rect rect, Color color) {
        for (int y = rect.y(); y < rect.y() + rect.height(); y++) {
            for (int x = rect.x(); x < rect.x() + rect.width(); x++) {
                Color under = painter.palette().color(painter.surface().get(x, y));
                painter.pixel(x, y, Colors.mix(under, color, TINT));
            }
        }
    }

    /** A box round the hovered chunk, claimed or not, in the color that clicking it would use. */
    private void highlight(Painter painter, Rect bounds) {
        if (cursorX() < 0) return;

        Rect rect = chunkRect(bounds, hoveredChunkX(), hoveredChunkZ());
        Team owner = claims.at(hoveredChunkX(), hoveredChunkZ());
        Color outline = owner == null ? picked.get().color() : Color.WHITE;

        painter.rect(rect, null, 1, outline, 0);
    }

    /** Where a chunk lands on the canvas. Off-canvas edges are fine - the node clips its own drawing. */
    private Rect chunkRect(Rect bounds, int chunkX, int chunkZ) {
        int x = bounds.x() + (chunkX * CHUNK_PIXELS - topLeftBlockX());
        int y = bounds.y() + (chunkZ * CHUNK_PIXELS - topLeftBlockZ());
        return new Rect(x, y, CHUNK_PIXELS, CHUNK_PIXELS);
    }

    // ---- clicking ----

    /**
     * Told where in the map it was clicked, so the chunk comes from the click rather than the cursor.
     *
     * <p>Two rules, both about the selected team rather than about you: unclaimed ground can be taken, and
     * ground that team holds can be given back. Nothing takes a chunk off another team.
     */
    private void claimAt(int x, int y) {
        int chunkX = chunkAt(topLeftBlockX() + x);
        int chunkZ = chunkAt(topLeftBlockZ() + y);
        Team owner = claims.at(chunkX, chunkZ);
        Team picked = this.picked.get();

        if (clickedWith() == Click.LEFT) {
            release(chunkX, chunkZ, owner, picked);
            return;
        }

        if (owner == picked) {
            hint(Component.text(picked.label() + " already holds this - left-click to release", NamedTextColor.YELLOW));
            return;
        }
        if (owner != null) {
            hint(Component.text(owner.label() + " holds this", NamedTextColor.RED));
            return;
        }

        claims.claim(chunkX, chunkZ, picked);
        hint(Component.text("Claimed " + chunkX + ", " + chunkZ + " for " + picked.label(), NamedTextColor.GREEN));
        invalidate();
    }

    /** Only the team that holds it can give it up, which is what "your color must match" means here. */
    private void release(int chunkX, int chunkZ, @Nullable Team owner, Team picked) {
        if (owner == null) {
            hint(Component.text("Nothing claimed here", NamedTextColor.GRAY));
            return;
        }
        if (owner != picked) {
            hint(Component.text(owner.label() + " holds this - select them to release it", NamedTextColor.RED));
            return;
        }

        claims.release(chunkX, chunkZ);
        hint(Component.text("Released " + chunkX + ", " + chunkZ, NamedTextColor.YELLOW));
        invalidate();
    }

    /** Both buttons, since releasing needs one of its own. Left-click swings the arm and jogs the map a
     * little, which is the right way round - claiming is the common action and keeps the steady one. */
    @Override
    public Click activateOn() {
        return Click.BOTH;
    }

    /** The action bar, since a chat message would be buried behind the map the player is looking at. */
    private void hint(Component message) {
        player().sendActionBar(message);
    }

    // ---- where things are ----

    private String hoveredLabel() {
        Team owner = claims.at(hoveredChunkX(), hoveredChunkZ());
        return owner == null ? "Unclaimed" : owner.label();
    }

    private int hoveredChunkX() {
        return chunkAt(topLeftBlockX() + cursorX());
    }

    private int hoveredChunkZ() {
        return chunkAt(topLeftBlockZ() + cursorY());
    }

    /**
     * Terrain is centered on the player, so the top left pixel is half a canvas north-west of them.
     * Everything else is worked out from there.
     */
    private int topLeftBlockX() {
        Location location = player().getLocation();
        return location.getBlockX() - width() / 2;
    }

    private int topLeftBlockZ() {
        Location location = player().getLocation();
        return location.getBlockZ() - height() / 2;
    }

    private static int chunkAt(int block) {
        return block >> 4;
    }
}
