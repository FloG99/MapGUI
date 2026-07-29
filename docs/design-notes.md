# Design notes

Why some of the awkward decisions are the way they are, and what was ruled out on the way. Nothing here is a
commitment about the future - see [the roadmap](roadmap.md) for that.

## Bandwidth is the budget, not compute

The single most useful thing to internalise, because it points optimisation effort in a non-obvious
direction: nothing here is compute-bound and it probably never will be.

A map is 128x128 = 16,384 bytes, which is the entire render target. A full repaint is a tree walk over a few
dozen nodes plus a memoized palette lookup per pixel - well under a millisecond. The packet is the expensive
part:

```
16,384 B x 20 fps = 328 KB/s ~ 2.6 Mbit/s     per map, per viewer
```

A 4x4 video wall is 16 maps, so roughly 21 Mbit/s per viewer at 10 fps. Ten people watching is ~210 Mbit/s,
which is where a rented box gives up.

Sharing one map id between viewers saves the decode, the quantize and the packet construction, but **not the
bytes** - every player has their own connection, so egress multiplies by viewer count and nothing avoids that.
The only real levers are frame rate, resolution, and how good the dirty rectangle is. Which is what makes the
dirty rectangle the most valuable thing to get right.

### So no GPU

At this size a kernel launch and a round trip cost more than the work does, servers are rented CPU and RAM
with no GPU to find, and any JNI or OpenCL binding means shipping natives per platform - a worse cost for
something people install on a VPS than the speed is a benefit. Video *decode* would genuinely use hardware,
but that is fixed-function silicon rather than shaders and would be ffmpeg's problem.

If a pixel loop ever does profile hot, the answer is the Vector API: a solid multiple on the quantize loop
with no native dependency and no "requires a GPU" line in the README.

## Ordered dithering fights the dirty rectangle

The 4x4 pattern is right for a UI and wrong for video, and not for the obvious reason.

It is stable frame to frame, which is exactly what a static gradient wants. But on moving footage a small
color shift flips individual Bayer cells, so a region that barely changed comes out as a completely different
run of bytes. The dirty rectangle stops shrinking and the packet's own compression loses the long runs it was
living on - full frames for content that looks near-identical.

The fix is temporal rather than a better pattern: quantize against the previous frame's indices and keep a
pixel's existing entry while its new color is within some tolerance. Hysteresis. Likely worth more than
switching to error diffusion, and the two are independent.

## MP4 is closed, not pending

JCodec, the only pure-Java H.264 decoder, mis-decodes High profile - which is what phones and ffmpeg produce
by default.

Measured against ffmpeg on the gallery's own sample, frame 100 came back at 22.8/255 mean absolute error where
a correct decoder lands under 2. Not a seek mismatch (identical error at every offset), not a color matrix
(luma alone 22.7), not a range mismatch (best linear fit r = 0.82, residual unchanged), not frame reordering
(the best of 21 neighboring display frames was still 22.4). Strongly correlated but consistently wrong, which
is a reconstruction bug rather than a parsing one - most likely the 8x8 transform or the scaling matrices, both
High-profile-only and both enabled by x264 out of the box. Tested on one file against JCodec 0.2.5, but that
file is a stock x264 encode, so it is the case that matters.

Worth knowing *why* rather than just that: JCodec targets Baseline and Main, and its mistake is not the gap but
that it reads a High profile header and decodes anyway instead of refusing. A decoder that threw would have
taken five minutes to rule out instead of an evening.

Every other route - a bundled binary, a first-use download, bytedeco natives through Paper's library loader -
ends "no runtime dependencies", and buys less than it looks like. The map palette is ~250 fixed colors, so
MP4's color depth is moot; a minute at 128x128 and 10 fps is ~3 MB of GIF against ~1.3 MB of H.264, both
trivial next to the ~10 MB of map packets each viewer receives to watch it once. Converting is the author's
job, once, on their own machine.

## Never reimplement the layout engine in JavaScript

The DSL is imperative Java with closures - `each` walks a runtime list, `Text(() -> ...)` is a live supplier,
`onClick` is arbitrary code. Evaluating that in a browser means being a JVM, and two engines would drift until
the preview started lying.

The browser stays a display and input device. All logic stays in the one engine.

## What made the preview responsive

Four things mattered, and they were not all on the server:

- **One request in flight, newest position only.** Firing one per pixel crossed builds a queue the server can
  never catch up with. Clicks and scrolls are never coalesced, since each has to land.
- **State and the tree in one response.** It used to take three round trips per pixel of movement.
- **No work when nothing changed.** The frame is only re-encoded when its pixels differ from a copy of the last
  one, and the page only touches the DOM when the tree, log or text actually changed - rebuilding it per mouse
  move was costing more than the requests were.
- **No PNG anywhere, and no polling.** Frames go out as raw palette indices for the rectangle that changed,
  pushed over server-sent events, and the page writes them into a canvas with `putImageData`. A hover change is
  about 500 bytes.

  Two dead ends worth remembering. Shipping a PNG per frame meant an image fetch plus a main-thread decode for
  every hover; inlining it as a base64 data URI removed the fetch but made the decode worse, because the main
  thread is the one that has to stay free for the next mouse move. And polling caps an animation at the poll
  interval however fast frames are produced.

The animation stepper deliberately runs at 40ms rather than as fast as it can: in game a frame goes out once
per 50ms tick, and a preview smoother than the real thing would be lying.

## Standing in for the player, headless

Screens that touch `player()` cannot render headless. Three separate problems, three answers:

- **Reads** (`getName`, `getLocation`, `getWorld`) - `Player` and `World` are interfaces, so a `Proxy` with
  defaults plus a dozen curated values covers it. Better than a real player, because the web panel can make
  them editable: try a 24-character name and watch the header overflow.
- **Writes** (`sendMessage`, `teleport`, `giveItem`) - intercept and log to an action panel. *"click →
  sendMessage("Claimed!"), closeInventory()"* is more useful for design work than the action happening.
- **Terrain** - do not proxy a `World`. Synthetic terrain does not help you place a HUD, and the backdrop image
  already covers it.

## Not a designer

A drag-and-drop editor is a different thing, and pulls toward a config file as the source of truth rather than
type-safe Java. If it ever happens it should emit DSL code as a starting point, not become the format screens
are stored in.

**Mirror mode is not for iteration.** Streaming a real session to the browser sounds appealing, but getting new
code into a running server means reloading a plugin, which is unreliable in Minecraft. Keep it filed as a "does
this look right in my actual world" tool, nothing more.
