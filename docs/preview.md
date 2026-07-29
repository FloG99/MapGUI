# Headless preview

Two terminals, and you get save-and-look in a couple of seconds:

```
./gradlew -t :examples:gallery:classes     # recompile on save
./preview                                  # http://127.0.0.1:7654
```

Edit a color, save, and the browser updates - the server reloads the recompiled class in a fresh classloader
rather than restarting.

Only the previewed module reloads. MapGUI itself sits in the parent classloader, so changing the layout engine
or a widget needs the preview restarted. Stop it with Ctrl+C, or `curl http://127.0.0.1:7654/shutdown` - the
banner prints both, along with the pid.

Use the `preview` script rather than `gradlew previewServe` directly: Gradle's rich console pins a progress bar
to the bottom line, so a task that never finishes sits at "93% EXECUTING" forever and covers the server's own
output. The script just adds `--console=plain`.

## It is interactive

Move the mouse to hover, click to press buttons, wheel to scroll. Your real click handlers run, so state
changes and navigation behave as they will in game - including having no way back except one your own screen
provides.

A text field opens a browser prompt instead of a Minecraft one, and anything a screen would have done to the
player - `sendMessage`, `close` - is listed in an action log rather than performed.

Frames are pushed over server-sent events as raw pixels for the rectangle that changed, and drawn into a
canvas. No images are encoded or decoded, and nothing polls. The cursor readout shows the round-trip time so
you can see it.

## The layout inspector

Hovering prints the node under the cursor with the rect it was arranged to:

```
Toggle 11x11 @ 10,58 ●  ‹  Panel 54x11 @ 10,58  ‹  Scroll#body 116x99 @ 6,23  ‹  Panel 128x128 @ 0,0
```

On a 128 pixel canvas that answers "why is this three pixels off" far faster than reading the code does.

## Your own screen

```
./gradlew previewServe -Pscreen=com.example.MyScreen -Pmodule=my-plugin
```

Or render a single PNG, which is also how you capture screenshots in CI:

```
./gradlew preview -Pscreen=com.example.MyScreen -Pscale=4
```

Both use the real layout engine, the real map font and the real palette, so colors are quantized exactly as
they will be in game.

Two limits: screens that read live player state cannot be built headless, and terrain needs a world - pass
`-Pbackdrop=some.png` to stand in for it.
