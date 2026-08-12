# Animation

Scrolling eases by default. Colors ease if you ask them to:

```java
Button("Click me")
        .background(theme().accent())
        .hoverBackground(Color.WHITE)
        .transition(220)              // or .transition() for the default
```

## Where animation state lives

Nodes are rebuilt from scratch whenever state changes, so animation state cannot live on them. It lives on
the `Screen`, filed under each node's identity - its `key` if it has one, otherwise its position in the
tree.

That fallback is why most things animate without you doing anything, and the key is why you should set one
on rows inside a list: paths shift when rows move, keys do not.

## Your own values

For anything the widgets do not cover - a bar that fills, a value that counts up:

```java
double filled = animate("hp", health / (double) maxHealth);
Color tint = animateColor("bar", health > 20 ? GREEN : RED);
```

Both are safe to call while painting.

## A timeline of your own

Some animations are not a value easing toward another value - a shutter that closes, waits for something, flashes and
opens again is a sequence with stages, read off the wall clock. `keepDrawing()` is how one gets frames:

```java
@Override
protected boolean keepDrawing() {
    return shutter.tick(System.currentTimeMillis());
}
```

Return true while it is running and false the moment it is over. MapGUI asks every tick, so this is also the hook that
carries your own clock forward - which is the whole of what used to need a repeating task calling `invalidate()`.
Frames still respect `fps()` and the server's ceiling, so it cannot ask for more than a map can send.

## Effects that never arrive

`phase(periodMillis)` is a 0..1 value that loops forever, for a spinner, a pulse, a scrolling rainbow.

Map updates go out once a server tick at best, so an animation gets a frame every 50ms. Durations under
about 200ms have too few frames to read as an ease.

> [!WARNING]
> **A looping animation never settles, so it sends map updates for as long as it is on screen.** A map
> update carries raw palette bytes for the rectangle that changed, at up to one per tick:
>
> | what's animating | per frame | per second, one player |
> | --- | --- | --- |
> | the whole canvas (128x128) | 16 KB | ~320 KB/s, 2.6 Mbit/s |
> | a 100x8 label | 800 B | ~16 KB/s, 0.13 Mbit/s |
>
> The cost is the **area that changes**, not the fact that something is animating - a full-bleed rainbow is
> twenty times a scrolling label. Multiply by viewers for a shared wall, and note that dithered gradients
> are close to these raw figures because the 4x4 pattern is poor material for the packet's own compression,
> while flat UI colors compress well.
>
> Effects that *arrive* - `animate`, `animateColor`, eased scrolling - stop sending once they land. Only
> `phase` and `scroll()` run forever. Nothing is sent while a screen is put away.

## Frame limits

Because animation is where the bandwidth goes, there are two ceilings on it - `animations.fps` for
everything, and `animations.loop-fps` just for effects that never settle. They default to 20 and 10.

A screen can override either:

```java
@Override public int loopFps() { return 5; }   // a background effect nobody stares at
```

The server's setting is a ceiling rather than a default, so asking for more than it allows gets you its
number. Asking for less always works.

Two things make this cheap rather than a compromise:

- **Input is never limited.** A click or a hover repaints immediately whatever the setting says, so a lower
  limit costs responsiveness nothing.
- **The loop limit quantizes the clock rather than skipping paints.** Between steps a looping value is
  *identical*, so the pixels are identical, so there is no dirty rectangle and nothing is sent at all.
  Halving `loop-fps` genuinely halves the bytes.

`animations.enabled: false` turns the lot off, and eased values snap to their targets.
