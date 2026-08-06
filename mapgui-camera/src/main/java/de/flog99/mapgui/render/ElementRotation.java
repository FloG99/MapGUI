package de.flog99.mapgui.render;

/**
 * A turn applied to one box inside a model, which is the part of the format that stops boxes being boxes.
 *
 * <p>Distinct from a blockstate rotation, which is a quarter turn of the whole model and so can be baked into new
 * corners. This one is 22.5 or 45 degrees about a point of the author's choosing, and no baking makes the result
 * axis-aligned - it is what turns the crossed planes of {@code cross.json} into the X that grass and flowers are
 * drawn as. So it is kept as a transform and the ray is bent into it, which being linear leaves the distance along
 * the ray alone and lets the hit keep its place in the depth order.
 *
 * @param axis    0 for x, 1 for y, 2 for z
 * @param angle   degrees, already turned to match whatever the blockstate did to the model around it
 * @param rescale widen the box to keep its corners where they were, which is how a turned cross still spans the
 *                whole block instead of shrinking to fit inside it
 */
record ElementRotation(float originX, float originY, float originZ, int axis, float angle, boolean rescale) {

    /** How much the two axes across the turn grow, and so how much to shrink them coming back. */
    double shrink() {
        return rescale ? Math.cos(Math.toRadians(Math.abs(angle))) : 1;
    }
}
