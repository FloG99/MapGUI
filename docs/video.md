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

The `Quantizer` is where the dithering of anything decoded is set - an animation, a video, a still - and it is
the only place it can be set: frames are palette indices from the moment they are decoded, so a dither mode on
the node drawing them would have nothing left to work on. That is the better arrangement anyway - it is applied
once per frame rather than once per frame per viewer per repaint.

```java
GifFrames.read(stream, Quantizer.of(MapColors.INSTANCE, Dither.FLOYD_STEINBERG))

media.stream(url, Dither.FLOYD_STEINBERG)              // a video, live
media.download(url, Dither.FLOYD_STEINBERG, progress)  // or downloaded
```

`media.dither` in config.yml is the server's default, used by `stream(url)` and `download(url, progress)` when
nothing names a mode; `media.defaultDither()` reads it back, so a screen offering the modes can show which one
is already in force. A wall placed from `media.streams` has no caller to ask, so it takes the default too.

`Dither.NONE` is the default and is right for flat artwork the palette can nearly say already. For anything
photographic - which is most of what arrives as a JPEG or a WebP - an error diffusion mode is worth it, and `FLOYD_STEINBERG` is the one to try first: it is the most
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

## Still pictures

A picture is an animation of one frame, so it goes through the same machinery and needs no separate anything:

```java
MapGui.get().wall().at(block, face).size(2, 2).content(WallContent.video(picture)).open();
```

Dropped into `plugins/MapGUI/videos` it is placeable by name like everything else, and it is the cheapest thing
a wall can show - one frame, sent once, never sent again.

| Format | Needs |
|---|---|
| PNG, including alpha | nothing |
| JPEG, BMP, WBMP | nothing |
| GIF, animated or not | nothing |
| **WebP, AVIF, HEIC** | `media.ffmpeg` |
| **animated WebP, APNG** | `media.ffmpeg`, and they play as animations |

`ImageIO` is asked first and always, which is why the first three rows work on a server that has never turned
anything on. FFmpeg is asked only about what `ImageIO` refused, and a file neither can read says so, naming
`media.ffmpeg` as the fix - needing a video decoder to draw a picture is surprising enough that the error should
not leave you guessing.

## MP4, and live streams

GIF is what works out of the box, and it stays the default for the reason it always was: Java SE ships no
video decoder, so anything else means FFmpeg, and MapGUI is not going to put 80 MB of native code on every
server that only wanted a menu.

So it is asked for instead. Turn it on:

```yaml
media:
  ffmpeg: true
```

and on the next start the server downloads FFmpeg through Paper's own library loader - the build for this
operating system and processor only, once, cached alongside every other plugin library. After that, any file
in `plugins/MapGUI/videos` that FFmpeg can open sits next to the GIFs in `/mapgui wall place`.

Live streams are named in config rather than typed into the command:

```yaml
media:
  ffmpeg: true
  streams:
    lobby-cam: rtsp://10.0.0.5:554/stream1
```

`/mapgui wall place lobby-cam` then puts it up. A name is a shortcut for the command rather than a list of what
is allowed - a plugin holding [`MediaService`](#playing-a-url-your-plugin-was-handed) may play any url it
likes. One connection and one decode serve however many walls show it.

> The section used to be called `video:`. If your `config.yml` still says `video.ffmpeg`, MapGUI renames it for
> you on the next start and says so in the log; `walls.video-size` is unaffected.

### The difference it makes

A GIF is decoded once into memory and drawn from there. A video or a stream is decoded as it plays, on its own
thread, and the wall paints whatever frame is current when it comes round:

```java
MapGui.get().wall().at(block, face).size(2, 2).content(WallContent.live(source)).open();
```

`WallContent.live` takes a `LiveSource`, which is the interface in `mapgui-api`. MapGUI's own FFmpeg
implementation lives in the plugin rather than the API, so you never name it: `MediaService`
[hands you one for any url](#playing-a-url-your-plugin-was-handed), and `LiveSource` stays open for a frame
source of your own - a capture card, a render, another plugin's output.

That is what makes a two hour film possible where a GIF of it would not fit in memory, and it is why nothing
waits: FFmpeg scales inside the decoder, quantizing is a table lookup per pixel, and a stall in the stream
leaves the last frame up rather than the server. Close the source when you are done with it - it owns a thread
and, for a stream, a connection.

## Playing a url your plugin was handed

`MapGui.get().media()` is the way to play something that is in no config file - which is what a
`/stream <url>` command needs:

```java
MediaService media = MapGui.get().media();

// Live. Starts immediately, and keeps itself connected.
wall.content(WallContent.live(media.stream(url)));

// A clip to show more than once. Downloaded once, then it is a file forever.
media.download(url, percent -> bar.set(percent))
     .thenAccept(frames -> wall.content(WallContent.video(new VideoPlayer(frames))));
```

**Any url is accepted, page urls included.** A plugin calling MapGUI is code already running on the server - it
can read files and open sockets without asking us - so refusing its url argument would protect nobody. What
that means for you is that **permission-gating a url a player typed is your job**, not MapGUI's. What MapGUI
owes you in return is a failure with a reason rather than an exception, and caps that hold whatever url arrives.

**Failure is an end, not an exception.** `stream` always returns a source; one that could not be opened is
simply not running, with `error()` saying why - and "FFmpeg is not loaded" is one of those reasons, because a
server owner may have turned it off. Handle an end, not a guarantee.

### Stream, or download

Both, and the difference matters:

| | Stream from source | Download, then play |
|---|---|---|
| Live content - Twitch, an rtsp camera | **the only option** | impossible, it is ongoing |
| Starts | immediately | when the download finishes |
| Url expiry | handled for you, see below | **never** - once local it is a file |
| Source going down, rate limits | breaks playback | irrelevant after the first fetch |
| Seeking | poor to impossible | proper |
| Disk | none | the file, cached and shared |
| Showing it again | fetched again | fetched once, played forever |
| Memory | one frame | every frame, one byte per pixel |

So: **live means stream; a clip you will show more than once means download.** A lobby trailer wants
downloading - no expiry, no re-resolution and no dependency on YouTube being reachable at the moment somebody
walks past. Something watched once wants streaming.

A download is cached by the hash of the url you asked for, so the second call writes nothing and every wall
showing it shares one file. The url *asked for* rather than the one downloaded from, which matters for a page
url: it resolves to a differently signed url every time, so keying on that would make every call a fresh
download of a video already on disk. `media.download.max-file-mb`, `max-total-mb` and `max-frames` are what stop
that from being a way to fill a disk or the heap; past any of them the future completes with a message naming
the cap.

## YouTube and Twitch

A YouTube or Twitch link is a *page*, not media, and `yt-dlp` is what turns one into the other. It is off:

```yaml
media:
  ffmpeg: true
  resolve-page-urls: true
```

Turning it on fetches yt-dlp and a small JavaScript runtime into `plugins/MapGUI/tools/` and **runs them** as
child processes, keeping yt-dlp updated because YouTube rejects old releases outright. Nothing on the machine is
used or installed to, and deleting that folder undoes all of it. `canResolvePageUrls()` is how a plugin can say
why before trying, and the startup log says what each tool resolved to - which is the first thing worth knowing
about a 403.

**Signed urls expire.** A resolved YouTube url is good for hours, not for a night, so MapGUI reads the deadline
off the url and reconnects a couple of minutes before it, keeping the old connection's picture up until the new
one has one of its own. There is nothing to do about it from a plugin, and it is why a wall left up overnight
keeps playing.

Worth knowing before it is reported as a bug:

- **YouTube and Twitch both restrict access from outside their own players.** Whether that is acceptable is the
  operator's decision, which is why this is off by default and says so in `config.yml`.
- **Live HLS is 10 to 30 seconds behind.** Fine for a wall, and not something MapGUI can shorten.
- **A datacenter IP may be blocked or rate-limited**, YouTube especially. Resolution can simply fail on a rented
  box; the wall keeps its last frame and the reason is logged once.
- **Bandwidth is unchanged.** A 1080p source still becomes a canvas of 128-pixel maps.

### What it does not change

The bytes on the wire. A frame is a frame however it was decoded, so everything in
[performance](performance.md) applies unchanged: the frame rate, the area that actually moves and the number
of people watching are still what a wall costs. Converting to GIF was never expensive because the map palette
is about 250 colors - MP4 has no color advantage on a canvas this size. What it has is length, seeking and
live input.
