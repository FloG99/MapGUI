package de.flog99.mapgui;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One picture behind several walls, sent once instead of once per wall.
 *
 * <p>A client keeps a picture for every map id it has been sent, and a map id is not tied to a place - so six
 * televisions showing one clip can be six sets of item frames pointing at the <b>same</b> ids. The pixels then
 * cross the wire once however many walls are up, and a wall added to a channel that is already running costs a
 * mount packet and nothing else.
 *
 * <p><b>One member paints and sends; the rest only hang frames.</b> Sharing the ids alone would not do: each
 * wall's view runs on its own clock, so two walls opened a second apart would be a second apart in the clip and
 * would take turns overwriting each other's pixels. So the channel picks a member to be the one drawing, and
 * the others skip painting entirely - which makes the decode and the paint shared too, not only the bytes.
 *
 * <p>The drawing member sends to <b>every</b> viewer of <b>any</b> member, because a viewer who can only see the
 * far wall still needs those ids in their client. Which viewer is near which wall stays each wall's own
 * business, and so does showing and hiding the frames.
 *
 * <p>Only for content that is the same for everyone: a screen answers clicks and reads who is looking, so it can
 * never be shared, and {@code Builder.channel} refuses one. What is left is a picture, a video or a live source,
 * which is what a wall in a channel is for.
 */
final class WallChannel {

    /** Channels by name, for as long as something is in them. Walls open and close on the main thread. */
    private static final Map<String, WallChannel> OPEN = new HashMap<>();

    private final String name;
    private final int cols;
    private final int rows;

    /**
     * The map ids every member hangs, minted by the first member and handed to the rest.
     *
     * <p>A list of layers rather than one set, matching {@link WallTiles} - a prerendered loop mints more.
     */
    private final List<int[]> layers = new ArrayList<>();

    /** In join order, so the oldest is the one drawing and the next in line takes over when it goes. */
    private final Set<WallDisplay> members = new LinkedHashSet<>();

    /** Everyone any member wants streaming to, rebuilt each tick from what the members report. */
    private final Set<UUID> wanted = new HashSet<>();
    private final Set<UUID> gathering = new HashSet<>();
    private long gatheringFor = Long.MIN_VALUE;

    private WallChannel(String name, int cols, int rows) {
        this.name = name;
        this.cols = cols;
        this.rows = rows;
    }

    /**
     * The channel by this name, made if it is not there.
     *
     * @throws IllegalStateException if a channel by this name is already up at another size. Two walls of
     *                               different shapes cannot share one picture, and quietly giving the second one
     *                               its own would make a setting that looks like it worked
     */
    static WallChannel join(String name, WallLayout layout, @Nullable WallDisplay member) {
        WallChannel channel = OPEN.computeIfAbsent(name, key -> new WallChannel(key, layout.cols(), layout.rows()));
        if (channel.cols != layout.cols() || channel.rows != layout.rows()) {
            throw new IllegalStateException("The channel \"" + name + "\" is already showing a " + channel.cols
                    + "x" + channel.rows + " picture, so a " + layout.cols() + "x" + layout.rows()
                    + " wall cannot join it - one picture is one size");
        }
        if (member != null) channel.members.add(member);
        return channel;
    }

    /** Takes a wall out, and the channel with it when it was the last one. */
    void leave(@Nullable WallDisplay member) {
        members.remove(member);
        WallDisplay next = drawing();
        if (next == null) {
            OPEN.remove(name, this);
            return;
        }
        // Whoever is now first has never painted, and the ids hold the old member's last frame - so everybody
        // watching needs the whole picture again rather than the next changed rectangle of it.
        next.resendEverything();
    }

    /** Ids for one layer, minted once for the channel rather than once per wall. */
    int[] ids(int layer, int count) {
        while (layers.size() <= layer) {
            int[] ids = new int[count];
            for (int i = 0; i < ids.length; i++) ids[i] = MapIds.next();
            layers.add(ids);
        }
        return layers.get(layer);
    }

    /** The member that paints and sends, which is the one that has been in longest. */
    @Nullable
    private WallDisplay drawing() {
        return members.isEmpty() ? null : members.iterator().next();
    }

    boolean isDrawing(WallDisplay member) {
        return drawing() == member;
    }

    /**
     * Takes this member's report of who it would stream to this tick.
     *
     * <p>Gathered into a set for the tick being reported and swapped in when that tick moves on, so the drawing
     * member sends to what every member asked for rather than to whatever the last one happened to say. One tick
     * behind for a viewer who has only just arrived, which costs them one frame - and they are owed the whole
     * picture anyway, which is what {@link WallDisplay} already tracks.
     */
    void reportViewers(long tick, java.util.Collection<UUID> watching) {
        if (tick != gatheringFor) {
            wanted.clear();
            wanted.addAll(gathering);
            gathering.clear();
            gatheringFor = tick;
        }
        gathering.addAll(watching);
    }

    /** Whether this viewer is wanted by any wall in the channel, and so has to be sent to. */
    boolean wants(UUID viewer) {
        return wanted.contains(viewer) || gathering.contains(viewer);
    }

    /** Every viewer any member reported, for the drawing member to send to. */
    Set<UUID> viewers() {
        Set<UUID> all = new HashSet<>(wanted);
        all.addAll(gathering);
        return all;
    }

    /** How many walls are sharing this picture, for {@code /mapgui performance} to be able to say so. */
    int size() {
        return members.size();
    }

    String name() {
        return name;
    }

    /** Only for tests, which would otherwise see channels from whatever ran before them. */
    static void forgetAll() {
        OPEN.clear();
    }
}
