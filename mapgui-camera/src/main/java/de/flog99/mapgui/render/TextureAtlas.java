package de.flog99.mapgui.render;

import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.color.ColorSpace;
import java.awt.image.Raster;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decodes textures out of the asset layers, once each, and says how their pixels behave.
 *
 * <p>The alpha class is read off the png rather than kept as a list of materials: a texture whose pixels are
 * every one opaque stops a ray, one that is only ever fully on or fully off is a cutout the ray passes through
 * where it is off, and anything in between blends. Leaves, bars and tall grass fall out of that with nothing
 * hardcoded, and so does a resource pack's idea of them.
 *
 * <p>Animated textures are a vertical strip of frames with a {@code .mcmeta} beside them saying so - 102 of
 * them in vanilla 26.2, including water and lava. A capture is one instant, so frame zero is the whole of it,
 * and cropping is what stops water being drawn as a very tall smear.
 */
public final class TextureAtlas implements BlockModels.TextureAlpha, Textures {

    /** What the client shows for a texture it cannot find, and it reads as "wrong texture" to any player. */
    private static final int MISSING_MAGENTA = 0xFFF800F8;
    private static final int MISSING_BLACK = 0xFF000000;

    private final AssetStack stack;
    private final Map<String, Texture> textures = new ConcurrentHashMap<>();
    private final Texture missing = checkerboard();

    public TextureAtlas(AssetStack stack) {
        this.stack = stack;
    }

    /**
     * Never null. A texture no layer carries comes back as the checkerboard, because losing one block's face
     * is not a reason to fail a whole capture - and a version mismatch that got past the check should look
     * obviously wrong rather than invisibly wrong.
     */
    @Override
    public Texture get(String name) {
        return textures.computeIfAbsent(name, this::load);
    }

    @Override
    public BakedState.Alpha classify(String texture) {
        return get(texture).alpha();
    }

    /** Adds a texture that did not come from the asset layers, such as a player's skin. */
    public void put(String name, Texture texture) {
        textures.put(name, texture);
    }

    /**
     * One texture with others painted over it, kept under a name of its own.
     *
     * <p>What a villager's clothes are: vanilla paints the biome robe, the trade and the level badge over a bare body
     * as further passes on the same mesh, and since those passes share their geometry and uv exactly, compositing the
     * pngs produces the same picture. It is also the only way here - two snapshots of one mesh would be two surfaces
     * at the same depth, and which of them a ray kept would be arbitrary.
     *
     * <p>Named after everything it is made of and cached under that, so a village of farmers composites once.
     *
     * @param over painted on in order, bottom first. A layer that is not the size of {@code base} is skipped rather
     *             than stretched, which is what keeps a missing one from painting the checkerboard over a face
     * @return the name to draw with, which is {@code base} itself when there is nothing to paint
     */
    public String layered(String base, List<String> over) {
        if (over.isEmpty()) return base;

        String name = base + "+" + String.join("+", over);
        if (textures.containsKey(name)) return name;

        // Every texture this needs is fetched before the map is written to. Composing inside computeIfAbsent would
        // load the layers from inside it, and a reentrant update is something a ConcurrentHashMap refuses outright -
        // intermittently, since it only notices when the nested key lands in the bin the outer one has locked.
        Texture bottom = get(base);
        List<Texture> layers = over.stream().map(this::get).toList();
        textures.putIfAbsent(name, composite(bottom, layers));
        return name;
    }

    /**
     * One layer of {@link #dyed}: a texture and the colour it is multiplied by before being painted on.
     *
     * @param color {@code 0xFFRRGGBB}, or 0 for a layer painted as it is
     */
    public record Dyed(String texture, int color) {
    }

    /**
     * The same, with each layer multiplied by a colour of its own before it goes on.
     *
     * <p>What a banner is. Vanilla ships one white cloth and one white mask per pattern and draws each in the dye the
     * layer was made with, so the picture is not in the pngs at all - it is in the order and the colours. Sixteen dyes
     * over forty-odd patterns is far too many combinations to hold as files, and the same reason it cannot be a tint
     * on the snapshot: a snapshot carries one colour and a banner has as many as it has layers.
     *
     * <p>Cached under everything it is made of, so a room full of the same flag composites once.
     *
     * @param layers bottom first, the base cloth included
     * @return the name to draw with, or null when there is nothing to draw
     */
    public String dyed(List<Dyed> layers) {
        if (layers.isEmpty()) return null;

        StringBuilder name = new StringBuilder();
        for (Dyed layer : layers) {
            name.append(name.isEmpty() ? "" : "+").append(layer.texture()).append('@').append(Integer.toHexString(layer.color()));
        }

        String key = name.toString();
        if (textures.containsKey(key)) return key;

        // Fetched before the map is written to, for the reason {@link #layered} says.
        List<Texture> painted = layers.stream().map(layer -> multiplied(get(layer.texture()), layer.color())).toList();
        textures.putIfAbsent(key, composite(painted.getFirst(), painted.subList(1, painted.size())));
        return key;
    }

    /** Every texel times a colour, keeping its alpha - which is what makes a white mask a coloured stripe. */
    private static Texture multiplied(Texture texture, int color) {
        if (color == 0) return texture;

        int[] argb = new int[texture.argb().length];
        for (int i = 0; i < argb.length; i++) {
            int texel = texture.argb()[i];
            argb[i] = texel & 0xFF000000
                    | (texel >> 16 & 0xFF) * (color >> 16 & 0xFF) / 255 << 16
                    | (texel >> 8 & 0xFF) * (color >> 8 & 0xFF) / 255 << 8
                    | (texel & 0xFF) * (color & 0xFF) / 255;
        }
        return new Texture(texture.width(), texture.height(), argb, alphaOf(argb), average(argb));
    }

    private static Texture composite(Texture bottom, List<Texture> over) {
        int[] argb = bottom.argb().clone();

        for (Texture layer : over) {
            if (layer.width() != bottom.width() || layer.height() != bottom.height()) {
                continue;
            }

            for (int i = 0; i < argb.length; i++) {
                argb[i] = blend(argb[i], layer.argb()[i]);
            }
        }

        return new Texture(bottom.width(), bottom.height(), argb, alphaOf(argb), average(argb));
    }

    /** Source over destination. Vanilla's layers have every pixel either on or off, so this usually just replaces. */
    private static int blend(int under, int top) {
        int alpha = top >>> 24;
        int below = under >>> 24;
        if (alpha == 255 || below == 0) return top;
        if (alpha == 0) return under;

        int out = alpha + below * (255 - alpha) / 255;
        return out << 24
                | channel(under >> 16 & 0xFF, below, top >> 16 & 0xFF, alpha, out) << 16
                | channel(under >> 8 & 0xFF, below, top >> 8 & 0xFF, alpha, out) << 8
                | channel(under & 0xFF, below, top & 0xFF, alpha, out);
    }

    private static int channel(int under, int below, int top, int alpha, int out) {
        return (top * alpha + under * below * (255 - alpha) / 255) / out;
    }

    /** How many decoded successfully, for the ready message. */
    public int count() {
        return (int) textures.values().stream().filter(texture -> texture != missing).count();
    }

    /**
     * Whether a texture exists, without loading it or caching a miss.
     *
     * <p>For a caller that has more than one guess at where something lives and would rather find out than draw a
     * checkerboard. Entity textures need it: their paths are not derivable from the entity's name and several moved
     * in recent versions, so probing beats hardcoding.
     */
    public boolean has(String name) {
        Texture loaded = textures.get(name);
        if (loaded != null) return loaded != missing;

        return stack.has(AssetStack.asset(name, "textures", ".png"));
    }

    private Texture load(String name) {
        try {
            byte[] png = stack.read(AssetStack.asset(name, "textures", ".png"));
            if (png == null) return missing;

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) return missing;

            int width = image.getWidth();
            int height = frameHeight(name, image);
            if (width <= 0 || height <= 0) return missing;

            int[] argb = pixels(image, width, height);
            return new Texture(width, height, argb, alphaOf(argb), average(argb));
        } catch (IOException | RuntimeException e) {
            // A single unreadable png costs that texture and nothing else.
            return missing;
        }
    }

    /**
     * A texture's pixels as packed ARGB, without letting Java reinterpret them.
     *
     * <p>{@code getRGB} is the obvious way and it is wrong for a single-channel png. 581 of Minecraft's textures are
     * stored that way, stone and grass_block_top and spruce_leaves among them, because every pixel in them is a
     * neutral grey and one channel says so in a third of the space. ImageIO hands such a file back in a grey color
     * space, and {@code getRGB} then <i>converts</i> out of it rather than copying: it applies a gamma curve, and
     * stone's 143 comes back as 197. Minecraft's own loader takes the raw sample, so 143 is the value the texture
     * means. The giveaway in game was that stone read as almost white while the stone around a copper ore vein was
     * perfect - the ore texture is an indexed png, which comes back through a palette and is left alone.
     *
     * <p>So a grey image is read straight off the raster. Everything else keeps the ordinary path.
     */
    private static int[] pixels(BufferedImage image, int width, int height) {
        if (image.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_GRAY) {
            return image.getRGB(0, 0, width, height, null, 0, width);
        }

        Raster raster = image.getRaster();
        boolean hasAlpha = raster.getNumBands() > 1;
        int bits = image.getColorModel().getComponentSize(0);
        int full = (1 << bits) - 1;

        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Scaled rather than used as-is, since a grey png may store four bits a pixel as easily as eight.
                int grey = raster.getSample(x, y, 0) * 255 / full;
                int alpha = hasAlpha ? raster.getSample(x, y, 1) * 255 / full : 255;
                argb[y * width + x] = alpha << 24 | grey << 16 | grey << 8 | grey;
            }
        }
        return argb;
    }

    /**
     * The height of frame zero. An animated strip is square frames stacked downward, so the width is the
     * frame height - but only when a {@code .mcmeta} actually declares an animation, since a pack is free to
     * ship a non-square texture that is simply not square.
     */
    private int frameHeight(String name, BufferedImage image) {
        if (image.getHeight() <= image.getWidth() || !isAnimated(name)) {
            return image.getHeight();
        }

        return image.getWidth();
    }

    private boolean isAnimated(String name) {
        try {
            byte[] meta = stack.read("assets/minecraft/textures/" + name + ".png.mcmeta");
            if (meta == null) return false;

            return JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject().has("animation");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Opaque unless the pixels say otherwise, and translucent only for alpha that is neither on nor off. */
    private static BakedState.Alpha alphaOf(int[] argb) {
        boolean anyClear = false;

        for (int pixel : argb) {
            int alpha = pixel >>> 24;
            if (alpha == 0) {
                anyClear = true;
            } else if (alpha != 255) {
                return BakedState.Alpha.TRANSLUCENT;
            }
        }

        return anyClear ? BakedState.Alpha.CUTOUT : BakedState.Alpha.OPAQUE;
    }

    /**
     * Averaged over the pixels that are actually drawn. Including the transparent ones would drag the average
     * of a cutout like leaves toward black, which is exactly the case the distance LOD leans on.
     */
    private static int average(int[] argb) {
        long red = 0;
        long green = 0;
        long blue = 0;
        int counted = 0;

        for (int pixel : argb) {
            if ((pixel >>> 24) == 0) {
                continue;
            }

            red += pixel >> 16 & 0xFF;
            green += pixel >> 8 & 0xFF;
            blue += pixel & 0xFF;
            counted++;
        }

        if (counted == 0) return 0;

        return 0xFF000000 | (int) (red / counted) << 16 | (int) (green / counted) << 8 | (int) (blue / counted);
    }

    private static Texture checkerboard() {
        int size = 16;
        int[] argb = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                argb[y * size + x] = (x / 8 + y / 8) % 2 == 0 ? MISSING_MAGENTA : MISSING_BLACK;
            }
        }

        return new Texture(size, size, argb, BakedState.Alpha.OPAQUE, MISSING_MAGENTA);
    }
}
