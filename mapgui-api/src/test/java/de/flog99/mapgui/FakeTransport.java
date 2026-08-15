package de.flog99.mapgui;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A transport that records what it was asked to send instead of sending it.
 *
 * <p>Which is the only way to test the things MapGUI actually promises about bandwidth - that a wall sends
 * only the maps that changed, that a frame goes out in one piece, that a prerendered loop sends no pixels
 * after the first go. None of that is visible from a surface, and all of it happens where a server would
 * normally be.
 */
final class FakeTransport implements MapTransport {

    /** One map update, as the client would receive it. */
    record Sent(int mapId, int x, int y, int width, int height, int bundle, byte[] pixels) {

        int area() {
            return width * height;
        }
    }

    private final List<Sent> sent = new ArrayList<>();
    private final Bandwidth bandwidth = new Bandwidth();
    private int markerSends;

    /** Which bundle the current sends belong to, or -1 when nothing is open. Counts up so they can be told apart. */
    private int bundle = -1;
    private int bundles;

    /** Set when this transport pretends its mount cannot be repointed, the way a plain one would be. */
    private boolean repoints = true;

    /** Ids the maps are currently pointed at, per player, which is what a prerendered wall changes. */
    private final List<int[]> pointedAt = new ArrayList<>();

    FakeTransport cannotRepoint() {
        this.repoints = false;
        return this;
    }

    List<Sent> sent() {
        return sent;
    }

    List<int[]> pointedAt() {
        return pointedAt;
    }

    void clear() {
        sent.clear();
        pointedAt.clear();
    }

    /** How many map updates carried pixels, which is the number that matters. */
    int updates() {
        return sent.size();
    }

    int pixelsSent() {
        int total = 0;
        for (Sent one : sent) total += one.area();
        return total;
    }

    /** How many separate arrays were handed over, which says whether the same bytes were shared or copied. */
    int distinctArrays() {
        return (int) sent.stream().map(one -> System.identityHashCode(one.pixels())).distinct().count();
    }

    /** Distinct bundles the recorded sends fall into, so "one frame, one bundle" can be asserted. */
    long bundleCount() {
        return sent.stream().map(Sent::bundle).distinct().count();
    }

    @Override
    public void bundled(Player player, Runnable sends) {
        if (bundle >= 0) {
            sends.run();
            return;
        }

        bundle = bundles++;
        try {
            sends.run();
        } finally {
            bundle = -1;
        }
    }

    @Override
    public void sendMap(Player player, int mapId, MapSurface surface, List<Marker> markers) {
        sent.add(new Sent(mapId, 0, 0, surface.width(), surface.height(), bundle, surface.pixels()));
    }

    @Override
    public void sendMap(Player player, int mapId, int x, int y, int width, int height, byte[] pixels) {
        sent.add(new Sent(mapId, x, y, width, height, bundle, pixels));
    }

    @Override
    public void sendMarkers(Player player, int mapId, List<Marker> markers) {
        markerSends++;
    }

    int markerSends() {
        return markerSends;
    }

    @Override
    public MapMount framedMaps(World world, List<FramedMap> maps) {
        return new FakeMount(maps.size());
    }

    @Override
    public Bandwidth bandwidth() {
        return bandwidth;
    }

    @Override
    public Bandwidth bandwidth(Player player) {
        return bandwidth;
    }

    @Override
    public void showMapItem(Player player, ItemStack item, int mapId, MapSlots slots) {
    }

    @Override
    public void hideMapItem(Player player) {
    }

    private final class FakeMount implements MapMount {

        private final int maps;

        private FakeMount(int maps) {
            this.maps = maps;
        }

        @Override
        public void show(Player player) {
        }

        @Override
        public void hide(Player player) {
        }

        @Override
        public boolean repoints() {
            return repoints;
        }

        @Override
        public void showMaps(Player player, int[] mapIds) {
            if (mapIds.length != maps) throw new IllegalStateException("a mount was pointed at the wrong number of maps");

            pointedAt.add(Arrays.copyOf(mapIds, mapIds.length));
        }
    }
}
