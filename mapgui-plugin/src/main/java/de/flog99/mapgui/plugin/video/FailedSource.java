package de.flog99.mapgui.plugin.video;

import de.flog99.mapgui.media.LiveSource;
import org.jetbrains.annotations.Nullable;

/**
 * A source that has already ended, with the reason.
 *
 * <p>The same shape as any other failure, so a caller has one path to handle rather than two: nothing about
 * "FFmpeg is not loaded" is different, from the outside, from a stream that dropped. Which is why
 * {@link de.flog99.mapgui.media.MediaService#stream} returns one of these instead of throwing - handle an end,
 * not a guarantee.
 */
record FailedSource(String reason) implements LiveSource {

    @Override
    public int width() {
        return 0;
    }

    @Override
    public int height() {
        return 0;
    }

    @Override
    public byte @Nullable [] frame() {
        return null;
    }

    @Override
    public boolean running() {
        return false;
    }

    @Override
    public String error() {
        return reason;
    }

    @Override
    public void close() {
        // Nothing was ever opened.
    }
}
