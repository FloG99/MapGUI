package de.flog99.mapgui.examples.camera;

import de.flog99.mapgui.MapGui;
import de.flog99.mapgui.camera.CameraShot;
import de.flog99.mapgui.map.MapPrinter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;

import java.util.List;

/**
 * A capture handed over as real map items, named for where each piece goes on the wall.
 *
 * <p>The maps themselves come from {@link MapPrinter}, which is where the cost worth knowing is written down: these
 * are genuine vanilla maps, and every one takes a map id the world keeps forever - four of them per {@code x4}.
 */
final class SnapshotTiles {

    private SnapshotTiles() {
    }

    /**
     * Prints the tiles and hands them over, in reading order.
     *
     * @return how many maps were made
     */
    static int give(Player player, CameraShot shot, int across) {
        List<ItemStack> tiles = MapGui.get().printer().print(player.getWorld(), shot);

        for (int piece = 0; piece < tiles.size(); piece++) {
            name(tiles.get(piece), across, piece / across, piece % across);
        }

        // Whatever does not fit goes on the floor rather than nowhere, since a capture cannot be taken again.
        player.getInventory().addItem(tiles.toArray(ItemStack[]::new))
                .values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));

        return tiles.size();
    }

    private static void name(ItemStack tile, int across, int row, int column) {
        tile.editMeta(MapMeta.class, meta -> {
            meta.displayName(Component.text("Snapshot " + where(across, row, column), NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Piece " + (row * across + column + 1) + " of " + (across * across)
                            + ", in a " + across + " by " + across + " wall", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        });
    }

    /** Words for the ordinary two by two, and coordinates for anything larger. */
    private static String where(int across, int row, int column) {
        if (across != 2) {
            return "row " + (row + 1) + ", column " + (column + 1);
        }
        return (row == 0 ? "top " : "bottom ") + (column == 0 ? "left" : "right");
    }
}
