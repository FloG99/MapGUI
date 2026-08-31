package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.Frames;

import java.util.List;

/**
 * {@link Frames} that are simply held: pixels already matched to the palette, and how long each one lasts.
 *
 * <p>{@link de.flog99.mapgui.media.GifFrames} is the same idea with a GIF decoder attached, and this is what
 * everything decoded by FFmpeg lands in - a WebP that turned out to be one frame, an animated one that turned
 * out to be sixty, a downloaded clip sampled down to what a wall can show.
 */
public final class MemoryFrames implements Frames {

    /** What a single still lasts, so a clock mapped onto it divides by something. Nothing can see it. */
    private static final int STILL_MS = 100;

    private final int width;
    private final int height;
    private final List<byte[]> frames;

    /** Cumulative, so {@link #indexAt} is a scan rather than a running total. Last entry is the duration. */
    private final int[] endsAt;

    /**
     * @param frames one entry per frame, each {@code width * height} palette indices
     * @param endsAt when each frame stops showing, in milliseconds from the start, strictly increasing
     */
    public MemoryFrames(int width, int height, List<byte[]> frames, int[] endsAt) {
        if (frames.isEmpty()) throw new IllegalArgumentException("Frames with no frames in them");
        if (frames.size() != endsAt.length) throw new IllegalArgumentException("Every frame needs a duration");

        this.width = width;
        this.height = height;
        this.frames = List.copyOf(frames);
        this.endsAt = endsAt.clone();
    }

    /** One frame, for a still - a PNG, a WebP, a JPEG. */
    public static MemoryFrames single(int width, int height, byte[] pixels) {
        return new MemoryFrames(width, height, List.of(pixels), new int[] {STILL_MS});
    }

    /**
     * Frames with the times a decoder said they start at, which is how FFmpeg reports an animation.
     *
     * <p>Start times rather than durations because that is what a demuxer knows: a frame lasts until the next
     * one, and the last one lasts however long the ones before it did. A variable-rate animated WebP is
     * therefore kept as it was authored rather than averaged into a single frame rate.
     *
     * @param startsMs  when each frame begins, in milliseconds, on whatever clock the decoder was using
     * @param fallbackMs how long a lone frame lasts, since there is no following frame to tell
     */
    public static MemoryFrames timed(int width, int height, List<byte[]> frames, int[] startsMs, int fallbackMs) {
        if (frames.size() != startsMs.length) throw new IllegalArgumentException("Every frame needs a start");

        int count = frames.size();
        int origin = startsMs.length == 0 ? 0 : startsMs[0];
        int average = count > 1 ? Math.max(1, (startsMs[count - 1] - origin) / (count - 1)) : fallbackMs;

        int[] endsAt = new int[count];
        for (int i = 0; i < count; i++) {
            int ends = i + 1 < count ? startsMs[i + 1] - origin : startsMs[count - 1] - origin + average;
            // Strictly increasing, so a decoder that reports the same timestamp twice - or goes backwards over
            // a seek - cannot produce a frame nothing can ever be showing, or a duration of zero to divide by.
            endsAt[i] = Math.max(ends, i == 0 ? 1 : endsAt[i - 1] + 1);
        }
        return new MemoryFrames(width, height, frames, endsAt);
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int count() {
        return frames.size();
    }

    @Override
    public int durationMs() {
        return endsAt[endsAt.length - 1];
    }

    @Override
    public int indexAt(int millis) {
        int at = Math.floorMod(millis, durationMs());
        for (int i = 0; i < endsAt.length; i++) {
            if (at < endsAt[i]) return i;
        }
        return endsAt.length - 1;
    }

    @Override
    public byte[] pixels(int index) {
        return frames.get(index);
    }
}
