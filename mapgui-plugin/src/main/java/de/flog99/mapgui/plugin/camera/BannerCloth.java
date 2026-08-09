package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.TextureAtlas;
import de.flog99.mapgui.render.Tints;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.DyeColor;
import org.bukkit.NamespacedKey;
import org.bukkit.block.banner.Pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A banner's cloth as the layers it is woven from, which is the same answer whether the banner is a block, an item,
 * or the one a raid captain is wearing on its head.
 *
 * <p>Vanilla ships one white cloth and one white mask per pattern and draws each in the dye it was made with, so the
 * picture is not in the pngs at all - it is in the order and the colours. Sixteen dyes over forty-odd patterns is far
 * too many combinations to hold as files.
 */
final class BannerCloth {

    /** The white cloth every banner starts as, which is the bottom layer and the only one that is not a mask. */
    static final String BASE = "entity/banner/base";

    private BannerCloth() {
    }

    /**
     * The layers bottom first, the base cloth included, ready for {@link TextureAtlas#dyed}.
     *
     * <p>A pattern this version carries no texture for is left out rather than drawn as a checkerboard, since one
     * unknown layer should cost its own stripe and not the whole flag - and a layer painted over the others is
     * exactly where a checkerboard would do the most damage.
     */
    static List<TextureAtlas.Dyed> layersOf(DyeColor base, List<Pattern> patterns, MobAssets assets) {
        List<TextureAtlas.Dyed> layers = new ArrayList<>();
        layers.add(new TextureAtlas.Dyed(BASE, dye(base)));

        for (Pattern pattern : patterns) {
            // Through the registry rather than off the constant, since a datapack may add a pattern and the constants
            // are on their way out.
            NamespacedKey key = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.BANNER_PATTERN).getKey(pattern.getPattern());
            if (key == null) continue;

            String mask = "entity/banner/" + key.getKey();
            if (assets.atlas().has(mask)) {
                layers.add(new TextureAtlas.Dyed(mask, dye(pattern.getColor())));
            }
        }
        return List.copyOf(layers);
    }

    static int dye(DyeColor color) {
        return color == null ? 0 : Tints.dye(color.name().toLowerCase(Locale.ROOT));
    }
}
