package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.Canopy;

/**
 * The numbers under {@code camera:} in config.yml, in one piece.
 *
 * <p>Together rather than five parameters, because they were five parameters: the constructor they feed had grown to
 * ten, three of which were literal zeroes at one call site, and the next setting would have made it eleven. A caller
 * passing five numbers in a row is a caller who can silently swap two of them, and {@code (70, 96)} and
 * {@code (96, 70)} both compile.
 *
 * <p>Read once at startup and again on a reload, which builds a new service rather than pushing values into the old
 * one - so nothing here has to be changeable after the fact.
 *
 * @param fov                 vertical field of view for a capture, in degrees
 * @param maxDistance         how far a capture traces, in blocks, before the server's own view distance caps it
 * @param maxEntityDistance   how far entities are drawn, before the server's own tracking range caps it
 * @param liveMaxMillisPerTick main-thread time a tick may spend on live views, or 0 for no budget
 * @param liveMaxFps          the most frames a second any one live view may take, or 0 for no ceiling
 * @param canopy              how far out the gaps in leaves close up
 * @param shadowLift          how far off black an unlit block is drawn, 0 being exactly what the client does
 * @param reuse               how long each of the three caches may serve what it holds
 * @param limits              the caps that stop one busy scene turning a capture into thousands of models
 */
public record CameraTuning(float fov, int maxDistance, double maxEntityDistance,
                           double liveMaxMillisPerTick, int liveMaxFps,
                           Canopy canopy, float shadowLift, Reuse reuse, Limits limits) {

    /**
     * What may be served from an earlier capture, and for how long. The only part of the camera that is not exact:
     * these are timers because there is nothing to invalidate on - block events miss pistons, fluid and every other
     * plugin, and nothing at all reports that a mob has drawn its sword.
     *
     * <p>Hence the asymmetry: stills reuse nothing unless asked, since a photograph is never corrected by a frame
     * that follows, where a live view is wrong only until the next one.
     *
     * @param stillChunksMillis what a <i>still</i> may reuse a copied column for, or 0 to copy it again every time
     * @param chunks            what a live view may, in chunks from the camera
     * @param blockEntities     the same for chests, signs and pots, in blocks
     * @param mobs              and for what a mob <i>looks like</i> - never where it stands, see {@link MobCache}
     */
    public record Reuse(int stillChunksMillis, ReuseWindow chunks, ReuseWindow blockEntities, ReuseWindow mobs) {

        /** Out to twelve chunks, where nearly all the copying is - the columns a frustum wants grow with distance. */
        public static final ReuseWindow CHUNKS = ReuseWindow.ofMillis(500, 2000, 2, 12);

        /** The same, over the blocks a block entity is drawn within. */
        public static final ReuseWindow BLOCK_ENTITIES = ReuseWindow.ofMillis(500, 2000, 16, 64);

        /** Tighter up close: a block changing late is one texel, a mob drawing a sword late is what you are looking at. */
        public static final ReuseWindow MOBS = ReuseWindow.ofMillis(100, 2000, 16, 64);

        public static Reuse defaults() {
            return new Reuse(0, CHUNKS, BLOCK_ENTITIES, MOBS);
        }
    }

    /**
     * What one capture may draw at most, so a mob farm or a storage room in shot cannot turn a frame into thousands
     * of models. Both take the nearest first, so what is dropped is what was furthest away.
     *
     * @param blockEntityDistance how far chests and signs are gathered from, in blocks
     */
    public record Limits(int mobs, int blockEntities, double blockEntityDistance) {

        public static Limits defaults() {
            return new Limits(48, 512, 64);
        }
    }
}
