package de.flog99.mapgui;

import de.flog99.mapgui.ui.Palette;
import de.flog99.mapgui.ui.PaletteLut;
import de.flog99.mapgui.ui.PerceptualPalette;
import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Bukkit's map palette, answered from a lookup table.
 *
 * <p>{@code MapPalette.matchColor} walks the whole palette on every call, which is brutal per pixel, so it is
 * called 32768 times on first use to fill a {@link PaletteLut} and never again. That makes matching a shift and
 * an array read, with nothing allocated and nothing to synchronize - which is what lets a camera or a video
 * frame be quantized off the main thread.
 *
 * <p>The table is built on first use rather than at startup, so nothing pays for it that never draws.
 */
@SuppressWarnings("removal") // Color matching is deprecated for removal with no replacement offered.
public final class MapColors implements Palette {

    public static final MapColors INSTANCE = new MapColors();

    /**
     * Which formula decides the nearest entry to a colour.
     *
     * <p><b>Neither is better, and the measurements that said otherwise were measuring themselves.</b> Scored in
     * CIELAB - a perceptual space, like Oklab - the perceptual matcher wins by 4%. Scored in vanilla's own
     * weighting it loses by 39%. Each wins under its own family of metric, which is what a referee chosen from
     * one side of an argument gets you. On the one measure neither has a term for - whether a ramp that only
     * moves one way is drawn only moving one way - they tie on how often it goes backwards, and vanilla is
     * ahead on how far. See {@code PerceptualMatcherAbTest}.
     *
     * <p>So this is a choice between two roundings and not an upgrade, vanilla stays the default, and anybody
     * picking between them should look at {@code docs/images/colour-matching.png} rather than at a number.
     */
    public enum Matching {

        /** Vanilla's own weighting: green counted four times, blue let off lightly. The default. */
        VANILLA,

        /**
         * Nearest in a perceptual space - see {@link PerceptualPalette}, which is where the rule lives.
         *
         * <p>A different rounding rather than a better one - see the note on {@link Matching} for why the
         * numbers that said better were not measuring what they looked like they were. Worth trying and looking
         * at; it does draw some ramps differently.
         *
         * <p>The dark range is unaffected either way: below 64 a finer table with its own rule already applies,
         * for a reason that is about crowding rather than about metrics. See {@code PaletteLut}.
         */
        PERCEPTUAL
    }

    private static volatile Matching matching = Matching.VANILLA;

    /**
     * Whether the table has been filled, held out here rather than inside the holder.
     *
     * <p>Asking the holder would build it, which is the one thing a check for "has it been built" must not do.
     */
    private static volatile boolean tableBuilt;

    private static final Color UNDEFINED = new Color(0, 0, 0);

    private final Color[] toColor = new Color[256];
    private byte[] entries;

    private MapColors() {
    }

    /**
     * Which formula fills the table.
     *
     * <p>Read once, when the table is built, so this has to be set before anything draws - which for the plugin
     * means before {@link #warmUp()}. Setting it afterwards changes nothing and says so, rather than appearing to
     * work and leaving half the server on one formula.
     */
    public static void matching(Matching wanted) {
        if (wanted == null || wanted == matching) return;
        if (tableBuilt) {
            throw new IllegalStateException("The colour table is already built, so " + wanted
                    + " would not take effect - set the matching before anything draws");
        }
        matching = wanted;
    }

    public static Matching matching() {
        return matching;
    }

    /** Built when something first asks for a color, which is well after {@link #INSTANCE} exists. */
    private static final class Table {

        static final PaletteLut LUT = build();

        private static PaletteLut build() {
            tableBuilt = true;
            return new PaletteLut(matching == Matching.PERCEPTUAL ? new PerceptualPalette(INSTANCE) : new Matcher());
        }
    }

    /** Bukkit's own matching, used only to fill the table. */
    private static final class Matcher implements Palette {

        @Override
        public byte index(Color color) {
            return MapPalette.matchColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
        }

        @Override
        public Color color(byte index) {
            return INSTANCE.color(index);
        }

        @Override
        public byte[] entries() {
            return INSTANCE.entries();
        }
    }

    @Override
    public byte index(Color color) {
        return Table.LUT.index(color);
    }

    /** The same from a packed int, for anything holding pixels rather than colors. */
    public byte index(int argb) {
        return Table.LUT.index(argb);
    }

    @Override
    public byte index(int argb, int x, int y) {
        return Table.LUT.index(argb);
    }

    /**
     * Fills the table now rather than the first time something draws.
     *
     * <p>Worth doing at startup for two reasons: it is 32768 searches through Bukkit's palette, which is a
     * visible hitch mid-frame, and Bukkit's own colour cache is not built to be raced - so the first caller
     * should not be a decoder thread that got there before the server finished starting.
     */
    public static void warmUp() {
        INSTANCE.index(Color.BLACK);
    }

    /** A whole frame at once, into indices somebody else owns. */
    public void quantize(int[] argb, byte[] out) {
        Table.LUT.quantize(argb, out);
    }

    /**
     * The palette doesn't define every one of the 256 indices, and Bukkit throws for the ones it
     * doesn't. Undefined slots come back transparent-black rather than taking the caller down.
     */
    @Override
    public Color color(byte index) {
        int slot = index & 0xFF;
        Color cached = toColor[slot];
        if (cached == null) {
            try {
                cached = MapPalette.getColor(index);
            } catch (RuntimeException e) {
                cached = UNDEFINED;
            }
            toColor[slot] = cached;
        }
        return cached;
    }

    /**
     * Only the slots the palette actually defines. Anything a dithering palette picks gets drawn, so
     * an undefined index must never make the list.
     */
    @Override
    public synchronized byte[] entries() {
        if (entries != null) return entries;

        List<Byte> usable = new ArrayList<>();
        for (int i = 4; i < 256; i++) {
            try {
                Color color = MapPalette.getColor((byte) i);
                if (color != null && color.getAlpha() == 255) {
                    usable.add((byte) i);
                }
            } catch (RuntimeException e) {
                // Not a color this palette defines.
            }
        }

        entries = new byte[usable.size()];
        for (int i = 0; i < entries.length; i++) entries[i] = usable.get(i);
        return entries;
    }

    /** Convenience for screens that want a palette index up front. */
    public static byte of(Color color) {
        return INSTANCE.index(color);
    }
}
