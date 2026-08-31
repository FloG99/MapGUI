# Video

Animated GIF plays with no dependencies at all. The JDK decodes it, `VideoPlayer` scales it to whatever box
the layout gave it and matches it to the map palette:

```java
VideoPlayer video = new VideoPlayer(GifFrames.read(stream, Quantizer.of(MapColors.INSTANCE)));

Draw(ctx -> video.paint(ctx.painter(), ctx.bounds(), millis)).size(64, 64)
```

Drive it from `phase(durationMs)` and the frame follows the same limit as every other looping effect, so it
plays at the server's `animations.loop-fps` instead of as fast as it can, and costs nothing while scrolled
out of sight.

For a whole wall there is a shortcut that handles the looping for you:

```java
MapGui.get().wall().at(block, face).size(2, 2).content(WallContent.video(video)).open();
```

## Dithering

The `Quantizer` is where an animation's dithering is set, and it is the only place it can be set: frames are
palette indices from the moment they are decoded, so a dither mode on the node drawing them would have nothing
left to work on. That is the better arrangement anyway - it is applied once per frame rather than once per
frame per viewer per repaint.

```java
GifFrames.read(stream, Quantizer.of(MapColors.INSTANCE, Dither.FLOYD_STEINBERG))
```

`Dither.NONE` is the default and is right for flat artwork the palette can nearly say already. For anything
photographic, an error diffusion mode is worth it, and `FLOYD_STEINBERG` is the one to try first: it is the most
faithful of the three, and measurably so - `DitherModesAbTest` has it at less than half Atkinson's error on a
color ramp. Reach for `ATKINSON` if Floyd-Steinberg worms, which it can where the palette has nothing nearby to
absorb the error. The javadoc on `Dither` has the whole of the reasoning.

Prefer error diffusion to an *ordered* mode here. A player scales frames after they are decoded, and resampling
a periodic tile beats against itself as moire. It also costs bandwidth: see
[performance.md](performance.md) - a dither pattern is poor material for the map packet's own compression, and
video is the content that sends the most bytes.

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

## MP4, and live streams

GIF is what works out of the box, and it stays the default for the reason it always was: Java SE ships no
video decoder, so anything else means FFmpeg, and MapGUI is not going to put 80 MB of native code on every
server that only wanted a menu.

So it is asked for instead. Turn it on:

```yaml
video:
  ffmpeg: true
```

and on the next start the server downloads FFmpeg through Paper's own library loader - the build for this
operating system and processor only, once, cached alongside every other plugin library. After that, any file
in `plugins/MapGUI/videos` that FFmpeg can open sits next to the GIFs in `/mapgui wall place`.

Live streams are named in config rather than typed into the command:

```yaml
video:
  ffmpeg: true
  streams:
    lobby-cam: rtsp://10.0.0.5:554/stream1
```

`/mapgui wall place lobby-cam` then puts it up. Named rather than typed on purpose: a url an operator hands to
the server is a url the server connects to, so it is a decision for whoever has access to `config.yml`. One
connection and one decode serve however many walls show it.

### The difference it makes

A GIF is decoded once into memory and drawn from there. A video or a stream is decoded as it plays, on its own
thread, and the wall paints whatever frame is current when it comes round:

```java
MapGui.get().wall().at(block, face).size(2, 2).content(WallContent.live(source)).open();
```

`WallContent.live` takes a `LiveSource`, which is the interface in `mapgui-api`. MapGUI's own FFmpeg
implementation of it lives in the plugin rather than the API, so it is not something your plugin can name at
compile time - a file or a stream in `config.yml` is how you reach that one, and `LiveSource` is there for a
frame source of your own: a capture card, a render, another plugin's output.

That is what makes a two hour film possible where a GIF of it would not fit in memory, and it is why nothing
waits: FFmpeg scales inside the decoder, quantizing is a table lookup per pixel, and a stall in the stream
leaves the last frame up rather than the server. Close the source when you are done with it - it owns a thread
and, for a stream, a connection.

### What it does not change

The bytes on the wire. A frame is a frame however it was decoded, so everything in
[performance](performance.md) applies unchanged: the frame rate, the area that actually moves and the number
of people watching are still what a wall costs. Converting to GIF was never expensive because the map palette
is about 250 colors - MP4 has no color advantage on a canvas this size. What it has is length, seeking and
live input.
