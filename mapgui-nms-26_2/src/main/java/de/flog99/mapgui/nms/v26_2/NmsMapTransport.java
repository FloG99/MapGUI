package de.flog99.mapgui.nms.v26_2;

import de.flog99.mapgui.Bandwidth;
import de.flog99.mapgui.CursorHotspot;
import de.flog99.mapgui.FramedMap;
import de.flog99.mapgui.MapMount;
import de.flog99.mapgui.MapSlots;
import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.Marker;
import de.flog99.mapgui.ui.Rect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.map.CraftMapCursor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sends map pixels and the map item straight down the connection, with nothing behind either on the
 * server.
 *
 * <p>It works because the client caches map pixels by id and creates an entry the first time it is sent data
 * for one it has never seen. So an id needs no allocating, registering or saving - only to be one the client
 * is not already using for a real map.
 */
public final class NmsMapTransport implements MapTransport {

    /** Map id, scale, locked flag, the patch rectangle and two list lengths. Near enough. */
    private static final int PACKET_OVERHEAD = 12;

    private final Set<UUID> faked = new HashSet<>();
    private final Bandwidth total = new Bandwidth();
    private final Map<UUID, Bandwidth> perPlayer = new HashMap<>();

    /** Who an open bundle is for, and what has gone into it so far. */
    private static final class Bundle {

        private final UUID player;
        private final List<Packet<? super ClientGamePacketListener>> held = new ArrayList<>();

        Bundle(UUID player) {
            this.player = player;
        }
    }

    /**
     * The bundle {@link #bundled} is filling, or null when nothing is being held back.
     *
     * <p>Unannotated because a {@code @Nullable} on a type that mentions {@code Packet} makes every module
     * compiling against this one resolve a server class it has no business knowing.
     */
    private Bundle bundle;

    @Override
    public void bundled(Player player, Runnable sends) {
        // The outermost call owns the bundle, so a wall inside a wall does not cut one short.
        if (bundle != null) {
            sends.run();
            return;
        }

        bundle = new Bundle(player.getUniqueId());
        try {
            sends.run();
        } finally {
            Bundle finished = bundle;
            bundle = null;
            deliver(player, finished.held);
        }
    }

    private static void deliver(Player player, List<Packet<? super ClientGamePacketListener>> held) {
        if (held.isEmpty()) return;

        // Nothing to keep together, and a bundle around it would only be two more packets on the wire.
        if (held.size() == 1) {
            send(player, held.getFirst());
            return;
        }
        send(player, new ClientboundBundlePacket(held));
    }

    /**
     * Into the open bundle, or straight down the connection when there is none.
     *
     * <p>Only what the bundle was opened for is held: anything for another player goes now, since holding it
     * would deliver it to the wrong client.
     */
    private void queue(Player player, Packet<? super ClientGamePacketListener> packet) {
        if (bundle != null && bundle.player.equals(player.getUniqueId())) {
            bundle.held.add(packet);
            return;
        }
        send(player, packet);
    }

    @Override
    public void sendMap(Player player, int mapId, MapSurface surface, List<Marker> markers) {
        List<MapDecoration> decorations = new ArrayList<>(markers.size());
        for (Marker marker : markers) decorations.add(decoration(marker, surface.width(), surface.height()));

        MapItemSavedData.MapPatch patch = patch(surface);
        // A marker-only update carries no pixels at all, which is most of what a moving cursor costs.
        count(player, (patch == null ? 0 : patch.width() * patch.height()) + markers.size() * 4);

        queue(player, new ClientboundMapItemDataPacket(new MapId(mapId), (byte) 0, false, decorations, patch));
    }

    @Override
    public void sendMap(Player player, int mapId, int x, int y, int width, int height, byte[] pixels) {
        count(player, pixels.length);
        queue(player, new ClientboundMapItemDataPacket(new MapId(mapId), (byte) 0, false, List.of(),
                new MapItemSavedData.MapPatch(x, y, width, height, pixels))
        );
    }

    @Override
    public void sendMarkers(Player player, int mapId, List<Marker> markers) {
        List<MapDecoration> decorations = new ArrayList<>(markers.size());
        // A marker's coordinates on a wall tile are measured against one map, not the whole wall.
        for (Marker marker : markers) decorations.add(decoration(marker, MapSurface.TILE, MapSurface.TILE));

        count(player, markers.size() * 4);
        // A null patch is an update carrying markers and no pixels, which the client is happy with.
        queue(player, new ClientboundMapItemDataPacket(new MapId(mapId), (byte) 0, false, decorations, null));
    }

    private void count(Player player, int payload) {
        int bytes = payload + PACKET_OVERHEAD;
        total.add(bytes);
        bandwidth(player).add(bytes);
    }

    @Override
    public Bandwidth bandwidth() {
        return total;
    }

    @Override
    public Bandwidth bandwidth(Player player) {
        return perPlayer.computeIfAbsent(player.getUniqueId(), id -> new Bandwidth());
    }

    @Override
    public MapMount framedMaps(World world, List<FramedMap> maps) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        List<ItemFrame> frames = new ArrayList<>(maps.size());
        for (FramedMap map : maps) frames.add(frame(level, map));
        return new Frames(this, frames);
    }

    /**
     * A real item frame never added to the level, used only as something to build packets from.
     *
     * <p>Its data accessors are private, so the entity is configured through its own methods and asked to
     * serialize itself rather than having field indices guessed at. Constructing it also takes a genuine
     * entity id off the server's counter, so unlike map ids there is nothing to invent.
     *
     * <p>Glowing draws the map at full brightness, and invisibility hides only the frame's own model, so a
     * grid reads as one picture and stays readable in the dark.
     */
    private static ItemFrame frame(ServerLevel level, FramedMap map) {
        Direction facing = Direction.valueOf(map.facing().name());
        BlockPos pos = new BlockPos(map.blockX(), map.blockY(), map.blockZ()).relative(facing);

        ItemFrame frame = new GlowItemFrame(level, pos, facing);
        frame.setInvisible(true);

        ItemStack item = new ItemStack(Items.FILLED_MAP);
        item.set(DataComponents.MAP_ID, new MapId(map.mapId()));
        // Neither flag set: no pickup sound, and no update that would reach into the level.
        frame.setItem(item, false, false);
        return frame;
    }

    /** Spawn plus data, per viewer. Held together so the ids stay the same for everyone. */
    private record Frames(NmsMapTransport owner, List<ItemFrame> frames) implements MapMount {

        /** The map id and the data entry around it; the packet's own is added on top. A map's worth is 16 KB. */
        private static final int SWITCH_PAYLOAD = 4;

        @Override
        public void show(Player player) {
            for (ItemFrame frame : frames) {
                // The facing rides in the data int, not the rotation - that is where the client reads it.
                owner.queue(player, new ClientboundAddEntityPacket(frame, frame.getDirection().get3DDataValue(), frame.getPos()));
                owner.queue(player, new ClientboundSetEntityDataPacket(frame.getId(), frame.getEntityData().getNonDefaultValues()));
            }
        }

        @Override
        public void hide(Player player) {
            int[] ids = new int[frames.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = frames.get(i).getId();
            owner.queue(player, new ClientboundRemoveEntitiesPacket(ids));
        }

        /**
         * Repoints each frame at another map id, which the client already has the pixels for.
         *
         * <p>The frames are shared between viewers, so the item is set only long enough to serialize the
         * packet from it. Everyone is looking at the same wall - what differs is which id they are pointed at,
         * and that lives in the packet rather than in the entity.
         */
        @Override
        public boolean repoints() {
            return true;
        }

        @Override
        public void showMaps(Player player, int[] mapIds) {
            for (int i = 0; i < mapIds.length && i < frames.size(); i++) {
                ItemFrame frame = frames.get(i);

                ItemStack item = new ItemStack(Items.FILLED_MAP);
                item.set(DataComponents.MAP_ID, new MapId(mapIds[i]));
                frame.setItem(item, false, false);

                owner.count(player, SWITCH_PAYLOAD);
                owner.queue(player, new ClientboundSetEntityDataPacket(frame.getId(), frame.getEntityData().getNonDefaultValues()));
            }
        }
    }

    @Override
    public void showMapItem(Player player, org.bukkit.inventory.ItemStack item, int mapId, MapSlots slots) {
        ItemStack copy = CraftItemStack.asNMSCopy(item);
        copy.set(DataComponents.MAP_ID, new MapId(mapId));

        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        faked.add(player.getUniqueId());

        // Always built from the player's own synchronizer rather than whatever is installed, so calling
        // this again for a new title, or to move the map to another slot, replaces the wrapper instead of
        // wrapping it. Installing also resends the inventory, which is how the item goes out.
        handle.inventoryMenu.setSynchronizer(new FakeSlots(handle.containerSynchronizer, copy, slots));
    }

    @Override
    public void hideMapItem(Player player) {
        if (!faked.remove(player.getUniqueId())) return;

        // Putting the real synchronizer back resends the inventory, which is what reveals the truth.
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        handle.inventoryMenu.setSynchronizer(handle.containerSynchronizer);
    }

    /**
     * The changed rectangle, or null when nothing moved - which the packet reads as an update carrying
     * markers and no pixels, and is the common case while only the pointer is moving.
     */
    @Nullable
    private static MapItemSavedData.MapPatch patch(MapSurface surface) {
        Rect changed = surface.dirtyBounds();
        if (changed == null) return null;

        return new MapItemSavedData.MapPatch(changed.x(), changed.y(), changed.width(), changed.height(),
                surface.region(changed)
        );
    }

    /**
     * Surface pixels to the map's own -128..127 icon space, twice as fine as the pixels and centered rather
     * than corner-based. Shifted by the icon's hotspot, so its point lands on the given position.
     */
    private static MapDecoration decoration(Marker marker, int width, int height) {
        Optional<Component> name = marker.label() == null
                ? Optional.empty()
                : Optional.of(Component.literal(marker.label()));

        return new MapDecoration(
                CraftMapCursor.CraftType.bukkitToMinecraftHolder(marker.type()),
                (byte) clamp(marker.x() * 2 - width),
                (byte) clamp(marker.y() * 2 - height + CursorHotspot.above(marker.type())),
                marker.rotation(),
                name
        );
    }

    private static int clamp(int value) {
        return Math.max(-128, Math.min(127, value));
    }

    private static void send(Player player, Packet<?> packet) {
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }

    /**
     * Swaps the named slots for one item on the way to the client, and lets everything else past.
     *
     * <p>Why the fake survives. Every path that resyncs inventory - a canceled interaction, a respawn, an
     * item landing in a slot, a plugin calling {@code updateInventory} - ends up in one of these methods, so
     * wrapping them is the single place that can keep the pretence up. Before this, right-clicking a block
     * made the map disappear, because refusing that click is exactly what resends the slot.
     */
    private static final class FakeSlots implements ContainerSynchronizer {

        private final ContainerSynchronizer delegate;
        private final ItemStack item;
        private final MapSlots slots;

        FakeSlots(ContainerSynchronizer delegate, ItemStack item, MapSlots slots) {
            this.delegate = delegate;
            this.item = item;
            this.slots = slots;
        }

        /** What the client should be told a slot holds, or null to let the real thing through. */
        private ItemStack replacement(int slotIndex) {
            if (slotIndex == InventoryMenu.SHIELD_SLOT) {
                return switch (slots.offhand()) {
                    case MAP -> item;
                    case EMPTY -> ItemStack.EMPTY;
                    case REAL -> null;
                };
            }

            int hotbar = slotIndex - InventoryMenu.USE_ROW_SLOT_START;
            if (hotbar < 0 || slotIndex >= InventoryMenu.USE_ROW_SLOT_END) return null;

            return slots.shows(hotbar) ? item : null;
        }

        @Override
        public void sendInitialData(AbstractContainerMenu container, List<ItemStack> slotItems,
                                    ItemStack carried, int[] dataSlots) {
            List<ItemStack> patched = new ArrayList<>(slotItems);
            for (int slot = 0; slot < patched.size(); slot++) {
                ItemStack replacement = replacement(slot);
                if (replacement != null) {
                    patched.set(slot, replacement);
                }
            }
            delegate.sendInitialData(container, patched, carried, dataSlots);
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
            ItemStack replacement = replacement(slotIndex);
            delegate.sendSlotChange(container, slotIndex, replacement != null ? replacement : itemStack);
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu container, ItemStack itemStack) {
            delegate.sendCarriedChange(container, itemStack);
        }

        @Override
        public void sendDataChange(AbstractContainerMenu container, int id, int value) {
            delegate.sendDataChange(container, id, value);
        }

        @Override
        public RemoteSlot createSlot() {
            return delegate.createSlot();
        }

        /**
         * Swallowed whenever the offhand is ours, and passed on when it is not.
         *
         * <p>It needs its own decision because Paper's version reads the real offhand out of the menu and hands
         * it to the delegate directly, so it never passes through {@link #sendSlotChange} and would undo the
         * substitution rather than being covered by it.
         */
        @Override
        public void sendOffHandSlotChange() {
            if (slots.offhand() == MapSlots.Offhand.REAL) {
                delegate.sendOffHandSlotChange();
            }
        }
    }
}
