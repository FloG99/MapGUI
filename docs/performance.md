# Performance

**Bandwidth is the budget, not compute.** Nothing here is compute-bound and it probably never will be.

A map is 128x128 = 16,384 bytes, which is the entire render target. A full repaint is a tree walk over a few
dozen nodes plus a memoized palette lookup per pixel - well under a millisecond. The packet is the expensive
part:

```
16,384 B x 20 fps = 328 KB/s ~ 2.6 Mbit/s     per map, per viewer
```

A 4x4 video wall is 16 maps, so roughly 21 Mbit/s per viewer at 10 fps. Ten people watching is ~210 Mbit/s,
which is where a rented box gives up.

Sharing one map id between viewers saves the decode, the quantize and the packet construction, but **not the
bytes** - every player has their own connection, so egress multiplies by viewer count and nothing avoids
that.

## The three levers

| | |
|---|---|
| **Frame rate** | `animations.fps`, `animations.loop-fps`, `walls.fps`. Halving the rate halves the bytes |
| **Area** | the cost is the rectangle that *changed*, so a small animation is cheap and a full-bleed one is not |
| **Audience** | `walls.view-distance` decides who is sent anything at all, and a wall stops sending to anyone in range who is not looking at it |

## What the wire actually carries

Two things narrow it further than the frame rate does.

**Only the maps that changed.** A wall tracks what moved per map, not per wall, so a clock in one corner and a
caption in the other send two small rectangles rather than the box around both - which on a 6x6 wall is the
difference between two updates and thirty-six.

**One frame arrives at once.** Every map that changed in a frame goes out in a single bundle, so a client
applies all of them in the same tick or none. Without it a large wall visibly tears, the top showing the new
frame while the bottom still shows the old.

**A repeating loop can be sent once.** See [prerendering](walls.md#a-loop-sent-once-instead-of-forever): a
short animation sent as a copy per frame is then played by telling clients which copy to show. Two walls next
to each other, one streamed and one prerendered, measured about 3 Mbit/s against nothing.

## What is free

- **A screen nobody is looking at.** Nothing is sent while it is put away.
- **A wall in an empty room.** The viewer set is checked before anything is painted.
- **A wall nobody is facing.** A viewer behind it, turned away from it, or with something solid in the way
  stops being sent pixels until they look back - see
  [nobody is looking at it](walls.md#nobody-is-looking-at-it). It pauses the stream rather than the paint, so
  it is bandwidth this saves and not main-thread time.
- **A still picture.** Nothing goes dirty, so it is sent once and then never again. Give it `fps(1)` or
  `fps(0)` and it costs one send for its whole life.
- **Hover, clicks and cursor movement on a wall.** Cursors are map markers rather than pixels, so a pointer
  moving is a few bytes rather than a frame.
- **Frames between steps of a loop limit.** The clock is quantized rather than the paints skipped, so a
  looping value is *identical* between steps - identical pixels, no dirty rectangle, nothing sent.

## Several walls, one picture

`WallDisplay.Builder#channel("lobby-tv")` puts several walls behind one picture. A client keeps a picture per map
id and a map id is not tied to a place, so the walls hang the same ids: one of them paints and sends, the rest
hang frames and do neither. Six televisions playing one clip cost what one costs, in bytes and in decoding, and a
wall joining a channel that is already running costs a mount packet and nothing per frame after it.

The drawing wall sends to every viewer of every wall on the channel, because somebody standing at the far
television needs those ids in their client and no other wall is going to put them there. Everything else stays
each wall's own: where it is, who may see it, how far it reaches, and whether a given viewer is close enough to
be streamed to at all.

Every wall on a channel shows the same thing at the same moment, which is the point rather than a limitation -
two walls of one clip a second apart look like a fault. It also means a channel is for content and not for a
menu: a screen answers clicks and reads who is looking, so `channel` refuses one. All the walls must be the same
size, and one of another size is refused rather than quietly given a picture of its own.

## Video holds still when nothing is happening

`media.steady` is on by default, and it is why a video of a static shot settles instead of shimmering. Real
footage wobbles a little between frames, and rounded to this palette that wobble becomes a pixel flipping between
two entries every frame - which is a change, so it is sent, so the changed part of the map becomes the whole map.

Measured on noisy footage, in pixels that differed from the frame before, over thirty frames of 64x64:

| | without | with |
|---|---|---|
| a still shot with sensor noise | 11,493 | **303** |
| the same under Floyd-Steinberg | 33,896 | **889** |
| a moving picture | 11,904 | 11,904 |

So the noise stops being sent and the movement is untouched, at 2.9% more colour error. A held pixel is never
more than a fixed amount further from its true colour than it would have been - the comparison is against the
colour wanted rather than against the other entry, which is what stops it drifting further behind as a pan goes
on. See `Steady`.

## What is not

- `phase(...)` and `Text.scroll()` never settle. They send for as long as they are on screen.
- Dithered gradients and video are close to the raw figures, because a dither pattern is poor material for the
  packet's own compression. Flat UI colors compress well.

  Measured on one map of a ramp the palette has no hues for, as deflated payload against the 16,384 raw bytes
  it always is: `NONE` 58 B, `ORDERED` 225 B, `ORDERED_FINE` 263 B, `BLUE_NOISE` 688 B, `ATKINSON` 2,575 B,
  `FLOYD_STEINBERG` 3,233 B. So **an error diffusion mode costs roughly eleven times an ordered one on the
  wire.** On a still that is nothing, because a still is sent once. On a 4x4 wall at 10 fps with ten people
  watching it is the difference between about 3 Mbit/s and about 33 Mbit/s, which is the cliff at the top of
  this page.

  Compute is the smaller half but not nothing: about 0.7 ms per map's worth of pixels for a diffusing mode
  against about 0.2 ms for an ordered one. For a GIF that is paid **once, at decode**, since frames are palette
  indices from then on. For a live source it is once per frame - though once per frame in total, not per
  viewer, and off the main thread.
- `screenPerPlayer` multiplies the *drawing* - a surface pair, a paint pass and a terrain scan per viewer. It
  does not multiply the bandwidth, because a wall is sent to each client separately in either mode. It suits
  something walked up to rather than something a crowd gathers round.
- **Hover on a *shared* wall**, unlike the cursor itself. A highlight is pixels on one surface everybody is
  sent, so with a crowd each viewer receives everyone else's hovering. This is the one place `screenPerPlayer`
  sends *less* per viewer, since then you only receive your own.
- Terrain in the hand re-scans as its owner walks. `terrain.min-ticks-between-refresh` caps how often that
  can happen. On a wall it is scanned once and kept, because a wall does not move.

## Finding out what it is costing

```
/mapgui performance
```

Two views of the same bytes, worst offender first: per wall says which wall to turn down, per player says who
is expensive. Both come with coordinates you can click to go and look.

The figures are **map payload before compression**, measured at the one place bytes actually leave. Minecraft
deflates packets over its threshold, so the real number on the wire is lower. Treat it as a ceiling.

`/mapgui reload` applies a new `config.yml` to walls that are already up, which is how you throttle a
struggling server without taking anything apart.

## Memory, which is a separate question

Bandwidth is the budget for everything except one thing: **decoded video**. A frame is a byte per pixel once
decoded, so at `walls.video-size: 256` it is 64 KB - *whatever the GIF compressed to on disk*. A 20 second clip
at 10 fps is 200 frames, so about 13 MB of heap. File size predicts nothing here; frame count does.

One decode is shared by every wall showing that file, and the frames are let go once no wall does. So the bill
is what is currently up, not everything an admin has ever previewed - placing six videos to look at them and
keeping one costs you the one. The trade is that placing it again re-reads the file, which is about a second.

## Zooming terrain out

`blocksPerPixel()` widens the view, and the cost is quadratic in area: 1:8 covers 1024 blocks across, which
is 4096 chunks. Only chunks the server already has loaded are read and the rest is left blank, so a wide zoom
is cheap but mostly empty rather than expensive.
