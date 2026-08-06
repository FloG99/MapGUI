package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntityVariants;
import de.flog99.mapgui.render.EquipmentAssets;
import de.flog99.mapgui.render.ItemModels;
import de.flog99.mapgui.render.ItemPoses;
import de.flog99.mapgui.render.TextureAtlas;

/**
 * Everything a mob has to be looked up in to be drawn, bundled rather than threaded through six signatures.
 *
 * @param atlas     the textures, and the answer to whether a given one exists at all
 * @param poses     how an item sits in a hand
 * @param items     what an item is drawn as, held or dropped
 * @param equipment which texture a piece of armor wears, on which layer, in how many passes
 * @param variants  which texture a mob's own coat wears
 */
record MobAssets(TextureAtlas atlas, ItemPoses poses, ItemModels items, EquipmentAssets equipment,
                 EntityVariants variants) {
}
