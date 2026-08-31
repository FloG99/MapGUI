package de.flog99.mapgui.ui;

import org.jetbrains.annotations.ApiStatus;

import java.awt.Color;

/**
 * A bar that fills, for work that <i>can</i> say how far along it is.
 *
 * <p>The other half of {@link Spinner}, and the half to reach for only when there is a real total: a download
 * whose length the server was told, a queue with a length, a job counting rows. Where there is no total a
 * spinner is the honest answer, because a percentage that sits at zero reads as broken.
 *
 * <p>The node's own {@code background} is the track and its {@code border} is the frame around it, since a bar
 * is a box with something drawn inside it and those already exist. What this adds is the something: a
 * {@link Fill} rather than a {@link Color}, so a bar can ramp from green to red across its own length and be
 * dithered on the way - and anchored to the <b>track</b> rather than to the filled part, so filling reveals more
 * of the same gradient instead of squeezing the whole of it into whatever is done so far.
 *
 * <p>With no width of its own it takes the width it is offered, which is what a bar in a column wants. State one
 * with {@code width} and it takes that instead. Six pixels tall unless {@code height} says otherwise.
 *
 * <pre>{@code
 * Progress().value(done / (double) total).bar(theme().accent())
 * Progress().segments(10).bar(Fill.gradient(RED, GREEN, HORIZONTAL))   // hearts
 * Progress().indeterminate().bar(theme().muted())
 * }</pre>
 */
@ApiStatus.Experimental
public final class Progress extends AbstractNode<Progress> {

    /** Tall enough to read as a bar on a 128 pixel canvas, short enough to sit under a line of text. */
    public static final int DEFAULT_HEIGHT = 6;

    /** One sweep in a little over a second, which reads as working rather than frantic. */
    public static final int DEFAULT_PERIOD_MS = 1200;

    /** How much of the bar the travelling block covers while indeterminate. */
    private static final int SWEEP_DIVISOR = 3;

    private double value;
    private boolean indeterminate;
    private int segments;
    private int segmentGap = 1;
    private int periodMs = DEFAULT_PERIOD_MS;
    private Fill bar = Fill.solid(Color.WHITE);

    /** How far along, 0 to 1. Clamped, so a count that overshoots its own total draws a full bar. */
    public Progress value(double fraction) {
        this.value = Math.clamp(fraction, 0, 1);
        this.indeterminate = false;
        return this;
    }

    /**
     * A block travelling along the bar instead of a level, for a wait whose total turned out not to exist.
     *
     * <p>Here so a bar can become one without the layout around it changing shape - a download that reports
     * its length sometimes and not others is one node either way. When there is never a total,
     * {@link Spinner} says the same thing in a dozen pixels square.
     *
     * <p>Like a spinner it never finishes by itself, so it costs frames for as long as it is on screen.
     */
    public Progress indeterminate() {
        this.indeterminate = true;
        return this;
    }

    /**
     * Draws the bar as {@code count} separate pips rather than as one continuous level.
     *
     * <p>Which is how Minecraft itself counts anything a player has to read at a glance - hearts, armour, hunger -
     * and it reads better than a level does at these sizes: ten lit pips out of twelve is a number you can see
     * without measuring the bar against its own track.
     *
     * <p>A pip lights only once it is fully earned, so a bar one pip short of the end never draws a full one.
     * 0 for a continuous bar, which is the default.
     */
    public Progress segments(int count) {
        this.segments = Math.max(0, count);
        return this;
    }

    /** Blank pixels between one pip and the next. Ignored unless {@link #segments(int)} is set. */
    public Progress segmentGap(int pixels) {
        this.segmentGap = Math.max(0, pixels);
        return this;
    }

    /** How long one sweep takes while {@link #indeterminate()}. Slower is calmer, and costs proportionally less. */
    public Progress period(int millis) {
        this.periodMs = millis;
        return this;
    }

    /** What the filled part is drawn with - a gradient across the track, a pattern of your own. */
    public Progress bar(Fill value) {
        this.bar = value;
        return this;
    }

    /** The flat case, which is most of them. */
    public Progress bar(Color color) {
        return bar(color == null ? null : Fill.solid(color));
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        return new Measured(availableWidth, Math.min(DEFAULT_HEIGHT, availableHeight));
    }

    @Override
    protected void paintContent(Painter target) {
        Rect box = contentBounds();
        if (box.width() <= 0 || box.height() <= 0 || bar == null) return;

        if (segments > 0) {
            paintSegments(target, box);
        } else if (indeterminate) {
            paintInto(target, sweep(box), box);
        } else {
            paintInto(target, new Rect(box.x(), box.y(), (int) Math.round(value * box.width()), box.height()), box);
        }
    }

    /**
     * Draws the fill over the whole track and lets the clip decide how much of it shows.
     *
     * <p>Which is the point of taking a {@link Fill}: a gradient asked for the filled rectangle alone would put
     * its far colour at whatever is done so far, so a bar at 10% would already be showing the end of its ramp.
     * Painted against the track, filling walks along the gradient instead.
     */
    private void paintInto(Painter target, Rect shown, Rect track) {
        Rect visible = shown.intersect(track);
        if (visible.width() <= 0 || visible.height() <= 0) return;

        Rect previous = target.pushClip(visible);
        target.box(track, bar, Border.none(), Corner.SQUARE, 0);
        target.popClip(previous);
    }

    /**
     * Pips laid across the track, lit up to the level.
     *
     * <p>Boundaries are worked out from the far edge of each pip rather than from a pip width, so the last one
     * ends exactly at the end of the track and a width that does not divide evenly leaves the spare pixel in a
     * pip rather than in a gap.
     */
    private void paintSegments(Painter target, Rect box) {
        int lit = indeterminate
                ? (int) Math.floor(phase() * segments)
                : (int) Math.floor(value * segments + 1e-9);
        int span = box.width() + segmentGap;

        for (int i = 0; i < segments; i++) {
            boolean on = indeterminate ? i == lit : i < lit;
            if (!on) continue;

            int left = box.x() + i * span / segments;
            int right = box.x() + (i + 1) * span / segments - segmentGap;
            paintInto(target, new Rect(left, box.y(), right - left, box.height()), box);
        }
    }

    /**
     * Where the travelling block is, sliding in at one end and out at the other.
     *
     * <p>Off the ends rather than wrapping round them, so the bar is empty for a moment between sweeps - a block
     * that reappears on the left as it leaves the right reads as two blocks at these sizes.
     */
    private Rect sweep(Rect box) {
        int width = Math.max(2, box.width() / SWEEP_DIVISOR);
        int travel = box.width() + width;
        return new Rect(box.x() + (int) (travel * phase()) - width, box.y(), width, box.height());
    }

    /**
     * How far through a sweep we are, 0 to 1.
     *
     * <p>Asking the animator is also what keeps the frames coming, so an indeterminate bar with no animator
     * behind it stands still rather than throwing - a screen with animation turned off asked for no movement.
     */
    private double phase() {
        Animator animator = animator();
        return animator == null ? 0 : animator.phase(periodMs);
    }
}
