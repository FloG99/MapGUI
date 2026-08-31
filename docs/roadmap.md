# Roadmap

Things worth building. Nothing here is a commitment, and some of it is deliberately filed as *not* worth doing -
see [design notes](design-notes.md) for the reasoning behind the closed ones.

## Layout

- **Rich text spans.** One `Label` is one color. Multi-colored runs currently mean several labels, which the
  layout engine handles but reads poorly.
- **Multi-line captions.** A map cursor caption is one short line under the pointer. Anything longer wants
  drawing into the surface as a real tooltip, which needs an overlay layer that can escape its parent's bounds.
- **Marquee as well as slide.** `scroll()` eases back and forth, matching Minecraft. A continuous wrap-around
  marquee suits a ticker better, and is the same machinery with a different offset.
- **Position and size transitions.** `Animator` already eases numbers and colors. Rects are the missing piece:
  remember each node's last rect by identity, and while it differs from the new one, arrange the node at the
  interpolated rect instead. Arranging at the intermediate size - rather than painting the final layout at an
  offset - is what makes children come along correctly. The awkward part is what a *changed* rect means when a
  list reorders: without keys, node identity shifts and things animate to the wrong place.
- **Springs.** Easing with a fixed duration cannot express "keep up with a moving target" as well as a spring
  does. Worth it only if scroll chasing ever feels wrong.
- **Perceptual color interpolation.** Color animation interpolates in RGB and snaps to a single palette entry,
  so an eased color can pass through a visibly different hue - green to yellow was measured passing through
  teal. Gradients no longer suffer from this because they dither; routing animated colors through the same
  dithering, or interpolating in a perceptual space, would fix the rest.
- **Error-diffusion dithering.** The ordered 4x4 pattern is cheap and stable, which is what a UI wants.
  Floyd-Steinberg would look better on photographs and stills - but not obviously on *video*, where stability
  turns out to be a bandwidth concern rather than an aesthetic one.
- **Focus.** There is no keyboard, so focus barely exists - but a "selected row" concept would help
  keyboard-free navigation with the scroll wheel.
- **Golden-image tests.** The layout module has no Bukkit dependency, so screens can be rendered to PNG in tests
  and diffed. Would catch visual regressions the geometry assertions miss.

## Rendering

- **Async terrain.** The terrain scan reads a block column per pixel on the main thread. Chunk snapshots
  off-thread would let it run more often without a hitch.

  It no longer *kills* anything, at least: reading a block in an unloaded chunk loads and generates it, so a
  wide `blocksPerPixel` used to turn one redraw into thousands of chunk generations inside a single tick. It now
  skips unloaded chunks and leaves them blank, so the cost tracks what the server already has rather than how
  far the map is zoomed out.
- **Terrain beyond what is loaded.** Blank is honest but dull for a zoomed-out map. Vanilla map data, or the
  region files read off-thread, would fill it in without generating anything.
- **Markers could carry a `Component`.** The transport builds `MapDecoration` names itself, so a marker label
  could be styled instead of being a plain `String`. It stays a String because that is what
  `Label#revealOnHover` produces, and half-supporting it would be worse than not.
- **Name tags** over a captured entity. Held items and armor are drawn; the label above a head is not.
- **Voxel LOD past a distance.** Marching a 4x4x4 mip of averaged colors beyond about 64 blocks. At map
  resolution a block that far off is well under a pixel, so averaging is antialiasing rather than a compromise.

## Video

- **Temporal quantization.** See "ordered dithering fights the dirty rectangle". Probably the single highest
  value change for video.
- **Seeking, and a position a plugin can set.** A `LiveSource` plays from where it opened. A film on a wall
  wants a scrub bar, which means asking the decoder for a timestamp rather than for the next frame.

## Preview

- **Live property editing.** The inspector reads. Making it write - click a node, change its padding from 4 to
  6, watch it move with no recompile, then copy the number into the code - is the same loop as tweaking CSS in
  devtools before committing it. Needs a property model per node type, which the typed fields make
  straightforward but not free.
- **A better inspector.** Highlight the hovered node's rect as an overlay on the frame, show the whole tree
  rather than the path under the cursor, and flag nodes whose measured size differs from the rect they were
  arranged to - the usual cause of "why is this cut off".

## Prompts

- **Sign provider.** `Player#openSign` is API, but reading back an edited *virtual* sign probably needs to
  intercept `ServerboundSignUpdatePacket`. Four short lines, no title.
- **Book provider.** Many lines, same interception problem.
- **On-screen keyboard.** Letters drawn on the map, clicked with the aim cursor. Slow to type on, but it never
  leaves the UI and it is a good demonstration of the library rendering its own input method. Would make a nice
  example.
- **Dialog dismissal.** Nothing tells us when a player escapes out of a dialog, so `DialogPrompt` leans on a
  timeout. If Paper gains a close event, or `DialogBase` can refuse to close on escape, drop the timeout.
- **Typed prompts.** `Prompt<T>` with a parser and re-prompt on invalid input, layered over the string version.

## Walls

- **Per-side and per-corner borders.** The painter treats a border as one width and one shape.
- **Arbitrary angles.** Not possible: map contents only render in an item frame or a held map, so there is no
  way to hang one off the block grid. What you *can* do is paint the picture rotated and leave the rest
  transparent.
