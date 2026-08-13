package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * A ring of dots with a bright one travelling round it, for work that is happening but cannot say how far along.
 *
 * <p>Which is most work, honestly. A progress bar needs a total, and the things a screen waits on - a download whose
 * length the server has not been told, a capture, a query - usually cannot give one. A percentage that sits at zero
 * for twenty seconds reads as broken; a spinner reads as busy, which is the truth.
 *
 * <p>Stepped from dot to dot rather than faded smoothly round, because this is drawn on a map: 128 pixels of a
 * 61-colour palette, where a gentle gradient of alpha lands on the same few indices anyway. Snapping is crisper to
 * look at <i>and</i> cheaper to send - the pixels only change {@link #dots} times a turn.
 *
 * <p>It never finishes by itself, so it costs frames for as long as it is on screen. That is cheap here and only here:
 * a spinner is a dozen pixels square, where the same effect across a whole canvas is 16 KB a frame. Take it off screen
 * when the thing it is waiting for arrives.
 */
public final class Spinner extends AbstractNode<Spinner> {

    /** Eight dots sit apart at this size, and the ring fills it exactly - thirteen would leave a spare row. */
    public static final int DEFAULT_SIZE = 14;

    /** One turn a second reads as working without reading as frantic. */
    public static final int DEFAULT_PERIOD_MS = 1000;

    private static final int DEFAULT_DOTS = 8;

    /** What the dimmest dot keeps, so the ring stays a ring rather than a lone dot flying round it. */
    private static final int TRAIL_ALPHA = 55;

    /** Below this there is no room for a ring, and a smaller one is a flickering pixel rather than a spinner. */
    private static final int TOO_SMALL = 7;

    private int size = DEFAULT_SIZE;
    private int dots = DEFAULT_DOTS;
    private int periodMs = DEFAULT_PERIOD_MS;
    private Color color = Color.WHITE;

    /**
     * Both edges, since a ring in a rectangle is a ring in the square inside it.
     *
     * <p>Taken as a limit rather than an order: a size whose ring would not land on whole pixels gives the largest
     * one that does, a pixel under. The node is then exactly the ring it draws, with nothing spare down one side
     * for a caption underneath to line up against.
     */
    public Spinner size(int pixels) {
        this.size = pixels;
        return this;
    }

    public Spinner dots(int count) {
        this.dots = Math.max(3, count);
        return this;
    }

    /** How long one turn takes. Slower is calmer, and costs proportionally less. */
    public Spinner period(int millis) {
        this.periodMs = millis;
        return this;
    }

    public Spinner color(Color value) {
        this.color = value;
        return this;
    }

    @Override
    protected Measured measureContent(LayoutContext context, int availableWidth, int availableHeight) {
        int edge = spanOf(Math.min(size, Math.min(availableWidth, availableHeight)));
        return new Measured(edge, edge);
    }

    private static int dotOf(int edge) {
        return Math.max(2, edge / 6);
    }

    /**
     * The largest ring that lands on whole pixels, which is the edge or a pixel less of it.
     *
     * <p>Two facing dots are {@code edge - dot} apart, so an odd gap puts their middle between two pixels and every
     * dot rounds to the same side of it - a ring that leans. Half a pixel for a ring symmetric under a quarter turn.
     */
    private static int spanOf(int edge) {
        return (edge - dotOf(edge)) % 2 == 0 ? edge : edge - 1;
    }

    @Override
    protected void paintContent(Painter target) {
        Rect box = contentBounds();
        int edge = Math.min(box.width(), box.height());
        if (edge < TOO_SMALL || color == null) return;

        int dot = dotOf(edge);

        // Normally the box itself, since this node measures itself at exactly the ring it can draw. A parent that
        // stretches it past that is the case this centres for.
        int span = spanOf(edge);

        // Whole pixels, so nothing below is a tie to break.
        int radius = (span - dot) / 2;
        int left = box.x() + (box.width() - span) / 2;
        int top = box.y() + (box.height() - span) / 2;
        double head = step();

        for (int i = 0; i < dots; i++) {
            double at = i / (double) dots;
            // How far round the ring behind the bright one this dot sits: 0 for the bright one itself, approaching
            // 1 for the one just in front of it, which is the one that was bright a whole turn ago.
            double behind = at > head ? head - at + 1 : head - at;
            int alpha = TRAIL_ALPHA + (int) Math.round((255 - TRAIL_ALPHA) * (1 - behind));

            double angle = Math.PI * 2 * at;
            int x = left + radius + reach(Math.sin(angle) * radius);
            int y = top + radius - reach(Math.cos(angle) * radius);
            target.fill(new Rect(x, y, dot, dot), Colors.alpha(color, alpha));
        }
    }

    /**
     * How far out along one axis a dot sits, rounded away from the middle rather than upwards.
     *
     * <p>{@link Math#round} breaks a tie upwards, sending 3.5 out to 4 and -3.5 in to -3 - a facing pair at
     * different distances.
     */
    private static int reach(double along) {
        int whole = (int) Math.round(Math.abs(along));
        return along < 0 ? -whole : whole;
    }

    /**
     * Which dot is the bright one, as a fraction of the way round.
     *
     * <p>Asking the animator is also what keeps the frames coming, so a spinner with no animator behind it stands
     * still rather than throwing - a screen with animation turned off is a screen that asked for no movement.
     */
    private double step() {
        Animator animator = animator();
        if (animator == null) return 0;

        return Math.floor(animator.phase(periodMs) * dots) / dots;
    }
}
