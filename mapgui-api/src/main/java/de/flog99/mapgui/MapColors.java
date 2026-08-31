package de.flog99.mapgui;

import de.flog99.mapgui.ui.Oklab;
import de.flog99.mapgui.ui.Palette;
import de.flog99.mapgui.ui.PaletteLut;
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
     * <p>Measured rather than argued: {@code PerceptualMatcherAbTest} scores both in CIELAB, which neither uses.
     * Neither wins outright, which is why this is a choice and not a fix - the palette is sparse enough that
     * which formula picks between two entries moves the result less than the sparseness does.
     */
    public enum Matching {

        /**
         * Vanilla's own weighting: green counted four times, blue let off lightly. Better on greys, which is
         * what menus, panels and text are made of, and what most screens are mostly made of.
         */
        VANILLA,

        /**
         * Nearest in {@link Oklab}, a space built so that equal distances look equally different.
         *
         * <p>Measured against vanilla over the bright range: 3.9% closer on saturated colour and a fifth closer
         * at its worst case, 2.1% further on grey. So it suits a server whose maps are mostly photographs -
         * camera captures, video walls, terrain - and not one whose maps are mostly menus.
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
            return new PaletteLut(matching == Matching.PERCEPTUAL ? new Perceptual() : new Matcher());
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

    /**
     * The same job in {@link Oklab}, walking the palette rather than asking Bukkit.
     *
     * <p>Only ever used to fill the table, so the walk costs 32768 searches once and nothing per pixel after -
     * exactly what the vanilla matcher costs, since that walks the palette too.
     */
    private static final class Perceptual implements Palette {

        @Override
        public byte index(Color color) {
            Oklab.Lab wanted = Oklab.of(color);
            byte found = 0;
            double closest = Double.MAX_VALUE;

            for (byte entry : INSTANCE.entries()) {
                Color candidate = INSTANCE.color(entry);
                if (candidate == null) continue;

                double at = Oklab.difference(wanted, Oklab.of(candidate));
                if (at < closest) {
                    closest = at;
                    found = entry;
                }
            }
            return found;
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
