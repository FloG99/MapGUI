# Video

Animated GIF plays with no dependencies at all. The JDK decodes it, `VideoPlayer` scales it to whatever box
the layout gave it and matches it to the map palette:

```java
VideoPlayer video = new VideoPlayer(GifFrames.read(stream, MapColors.INSTANCE));

Draw(ctx -> video.paint(ctx.painter(), ctx.bounds(), millis)).size(64, 64)
```

Drive it from `phase(durationMs)` and the frame follows the same limit as every other looping effect, so it
plays at the server's `animations.loop-fps` instead of as fast as it can, and costs nothing while scrolled
out of sight.

For a whole wall there is a shortcut that handles the looping for you:

```java
MapGui.get().wall().at(block, face).size(2, 2).content(WallContent.video(video)).open();
```

## Memory

Frames are matched to the palette once, while reading, and kept as one byte per pixel - so painting is a
scale and a copy rather than a color lookup per pixel, and an animation costs a quarter of what packed RGB
would.

A minute at 10 fps is about 9 MB held for as long as it is loaded, so **load it once at startup and share it
between screens** rather than per player.

## Transparency

Transparent GIFs composite rather than filling: see-through pixels are skipped, so whatever you drew
underneath shows through, and on a wall the block behind does.

That also means a picture can be smaller than its map, or a shape other than a rectangle - useful for a
round clock face or a sign with no border.

## Fit

`Fit.CONTAIN` is the default: the whole picture, with the box showing through where it does not reach. On a
128 pixel canvas losing content to a crop hurts more than a row of edge pixels, and the gap is not painted
rather than painted black - so give the node a background and the letterbox matches your UI.

`Fit.COVER` crops to fill. `Fit.STRETCH` distorts.

## Why not MP4

**GIF is the format, and MP4 is deliberately unsupported.**

Java SE ships no video decoder at all, and JCodec - the only pure-Java H.264 decoder - decodes High profile
measurably wrong. That is what phones and ffmpeg produce by default. Measured against ffmpeg on the
gallery's own sample, frame 100 came back at 22.8/255 mean absolute error where a correct decoder lands
under 2, and it is a reconstruction bug rather than anything a caller can work around - see
[design notes](design-notes.md#mp4-is-closed-not-pending) for how that was pinned down.

Anything else means bundling or downloading ffmpeg, which would end "no runtime dependencies".

Converting costs you very little, because a map is a small target. The palette is a fixed set of about 250
colors, so GIF's 256 per frame is already more than the canvas can show - MP4 has no color advantage here. A
minute at 128x128 and 10 fps is roughly 3 MB of GIF, against 10 MB of map packets sent to every viewer each
time it plays. The file is not the expensive part.
