package de.flog99.mapgui.media;

import org.jetbrains.annotations.Nullable;

/**
 * A picture that keeps changing on its own - a video being played, a camera, a live stream.
 *
 * <p>The difference from {@link Frames} is where time lives. Frames are decoded up front and asked for the one
 * belonging to a moment, which suits a short animation and nothing else - a feature film would not fit in memory and
 * a stream has no length to index into. A source runs at its own pace somewhere else and always has a latest
 * picture, so decoding and painting never wait for each other and a stall leaves the last picture up.
 *
 * <p>Implementations must be safe to read from the main thread while they write from their own.
 */
public interface LiveSource extends AutoCloseable {

    int width();

    int height();

    /**
     * The most recent picture as map palette indices, {@code width() * height()} long, or null if none has
     * arrived yet.
     *
     * <p>Do not write to it: the array may be the one the decoder is about to replace, and is shared by every
     * wall showing this source.
     */
    byte @Nullable [] frame();

    /** Whether pictures are still coming. False once the file ends, the stream drops or it is closed. */
    boolean running();

    /** Why it stopped, if it stopped badly. Null while it is fine and when it simply ended. */
    @Nullable
    String error();

    /** Stops decoding and lets go of whatever was open. Safe to call twice. */
    @Override
    void close();
}
