package de.flog99.mapgui.plugin.wall;

import de.flog99.mapgui.WallContent;
import de.flog99.mapgui.WallDisplay;
import de.flog99.mapgui.WallLayout;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Putting a wall up: click a block, look at the opposite corner, click again.
 *
 * <p>The preview is a real wall whose viewer set is one player, so what you see while sizing is what
 * everyone else will - and abandoning it costs nothing, since none of it was in the world.
 */
final class WallPlacement {

    /** How far a corner can be picked from. Generous, so a big wall can be sized from a distance. */
    private static final int REACH = 48;

    private static final Color GRID_LINE = new Color(0, 0, 0, 90);
    private static final Color BLOCKED = new Color(200, 40, 40, 160);

    private final Player player;
    private final Supplier<WallDisplay.Builder> walls;
    private final Consumer<WallDisplay.Builder> content;
    private final String contentName;
    private final int range;

    /** The one-by-one wall at the block first clicked. Every size is measured from this, never from the
     * last one - measuring from a wall whose anchor has already moved makes sizing drift. */
    @Nullable
    private WallLayout origin;

    /**
     * What the drag is asking for, and what the content agreed to.
     *
     * <p>Two of them because content can pin its own size: comparing the next drag against the agreed size
     * would tear down and re-send a fixed wall on every step of a drag that changes nothing.
     */
    @Nullable
    private WallLayout requested;
    @Nullable
    private WallLayout layout;
    @Nullable
    private WallDisplay preview;
    private boolean[] usable = new boolean[0];

    WallPlacement(Player player, Supplier<WallDisplay.Builder> walls,
                  Consumer<WallDisplay.Builder> content, String contentName, int range) {
        this.player = player;
        this.walls = walls;
        this.content = content;
        this.contentName = contentName;
        this.range = range;
    }

    boolean anchored() {
        return origin != null;
    }

    String contentName() {
        return contentName;
    }

    /** The first click: the block and the face it was hit on are all the anchor needs. */
    void anchor(Block block, BlockFace face, long now) {
        origin = WallLayout.anchoredAt(block.getX(), block.getY(), block.getZ(), face);
        rebuild(origin, now);
    }

    /** Follows where the player is looking, rebuilding when that lands on a different set of blocks. */
    void aim(long now) {
        if (origin == null) return;

        Block target = lookingAt();
        if (target == null) return;

        WallLayout next = origin.stretchedTo(target.getX(), target.getY(), target.getZ());
        if (next.equals(requested)) return;

        rebuild(next, now);
    }

    /** Null when nothing valid is selected, which is when the player is told why. */
    @Nullable
    WallLayout confirm() {
        if (layout == null) return null;

        for (boolean cell : usable) {
            if (cell) continue;

            player.sendActionBar(Component.text("Some blocks are covered or missing - move the corner", NamedTextColor.RED));
            return null;
        }
        return layout;
    }

    void cancel() {
        if (preview != null) {
            preview.close();
        }
        preview = null;
        origin = null;
        requested = null;
        layout = null;
    }

    // ---- preview ----

    /**
     * Puts up the preview at whatever size the content will accept.
     *
     * <p>The content is applied last and then asked what it settled on, so the preview, the blocks that get
     * checked and the size that gets saved are all the agreed one rather than the one dragged to.
     */
    private void rebuild(WallLayout wanted, long now) {
        if (preview != null) {
            preview.close();
        }
        requested = wanted;

        WallDisplay.Builder wall = walls.get()
                .at(player.getWorld(), wanted.anchorX(), wanted.anchorY(), wanted.anchorZ(), wanted.facing())
                .size(wanted.cols(), wanted.rows())
                .range(range);
        content.accept(wall);

        layout = wall.layout();
        usable = usableCells(layout);

        // An overlay rather than part of the content, so the grid goes over a menu the same as over a video.
        preview = wall.overlay(ghost()).preview(player, now);
        describe();
    }

    /** Says when the size is the content's choice, so dragging a wall that will not grow is not a mystery. */
    private void describe() {
        String pinned = layout.cols() == requested.cols() && layout.rows() == requested.rows()
                ? "" : "  (this content only fits " + layout.cols() + " x " + layout.rows() + ")";

        player.sendActionBar(Component.text(
                contentName + "  " + layout.cols() + " x " + layout.rows()
                        + "  (" + layout.pixelWidth() + " x " + layout.pixelHeight() + ")" + pinned
                        + "  -  left-click to place, right-click to cancel")
        );
    }

    /**
     * The tile seams, and anything unplaceable painted over.
     *
     * <p>Drawn over the real content, held still: a preview is painted once, so a video sits on frame nought
     * rather than streaming while the player decides where to put it.
     */
    private WallContent ghost() {
        WallLayout frozen = layout;
        boolean[] cells = usable;

        return (painter, bounds, millis) -> {
            for (int col = 1; col < frozen.cols(); col++) {
                painter.fill(new Rect(col * WallLayout.TILE - 1, 0, 2, bounds.height()), GRID_LINE);
            }
            for (int row = 1; row < frozen.rows(); row++) {
                painter.fill(new Rect(0, row * WallLayout.TILE - 1, bounds.width(), 2), GRID_LINE);
            }
            markUnusable(painter, frozen, cells);
        };
    }

    private static void markUnusable(Painter painter, WallLayout layout, boolean[] cells) {
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                if (cells[row * layout.cols() + col]) continue;

                painter.fill(new Rect(layout.surfaceX(col), layout.surfaceY(row), WallLayout.TILE, WallLayout.TILE), BLOCKED);
            }
        }
    }

    // ---- the world ----

    /**
     * A cell works if there is something to hang on and nothing in the way.
     *
     * <p>Nothing stops us drawing in mid-air, but a map floating with no wall behind it, or buried inside a
     * block, is a mistake rather than a feature.
     */
    private boolean[] usableCells(WallLayout layout) {
        boolean[] cells = new boolean[layout.count()];
        for (int row = 0; row < layout.rows(); row++) {
            for (int col = 0; col < layout.cols(); col++) {
                Block block = player.getWorld().getBlockAt(layout.blockX(col, row), layout.blockY(col, row), layout.blockZ(col, row));
                cells[row * layout.cols() + col] =
                        block.getType().isSolid() && block.getRelative(layout.facing()).getType().isAir();
            }
        }
        return cells;
    }

    @Nullable
    private Block lookingAt() {
        RayTraceResult hit = player.rayTraceBlocks(REACH);
        return hit == null ? null : hit.getHitBlock();
    }
}
