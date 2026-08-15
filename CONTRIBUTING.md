# Contributing

## Building

```
./gradlew build
```

Java 25. You do not need it installed - Gradle fetches a matching toolchain itself, which is what the foojay
resolver in `settings.gradle.kts` is for.

The first build downloads a Paper dev bundle for `mapgui-nms`, which takes a couple of minutes. Every other
module builds against `paper-api` alone, in seconds.

```
./gradlew runServer          # a Paper test server with the plugin and every example loaded
./gradlew test               # the unit tests, no server involved
./preview                    # the headless preview, see docs/preview.md
```

## Where things go

See [docs/architecture.md](docs/architecture.md) for the module layout. Two rules matter more than the rest:

- **`mapgui-layout` must not depend on Bukkit.** It is what lets the layout engine be unit tested and rendered
  headlessly. Anything it needs from the server arrives as an interface.
- **`mapgui-nms` is the only module allowed to touch `net.minecraft`.** If something needs server internals, it
  goes behind an interface in `mapgui-api` and gets implemented there. Two things live there today and there
  should not be a third without a reason written down.

Adding to `mapgui-api` adds to what a plugin compiles against, so it is the hardest thing to take back. Prefer
the plugin module unless a consumer genuinely needs it, and mark framework-only entry points
`@ApiStatus.Internal`.

`mapgui-api` is the only published artifact, and it carries `mapgui-layout`'s classes inside it. A published
version can never be replaced - see [RELEASING.md](.github/RELEASING.md).

## Code style

There is no formatter to run. Match what is there:

- **No line-length limit.** Keep a statement on one line when that reads better than wrapping it.
- **Braces on every `if` body except an exit.** A braceless single-line `if` is for `return`, `continue`,
  `break` and `throw` only.
- **A multi-line argument list closes on its own line.** A lambda's closing paren stays on its brace.
- **American English**, and `-` rather than an em dash.
- **Newest Java is fair game.** Records, pattern matching, `switch` expressions, `Math.clamp`.
- **Deprecated Paper API is acceptable when there is no replacement.** Say so in a comment.

## Comments

The bar is "why", not "what". A comment that restates the code is worse than none, because it has to be kept
true for no benefit.

Worth writing down:

- a decision that looks wrong until you know the constraint - most of `mapgui-nms`, and every case where the
  client and the server disagree about something
- a number that came from measuring rather than choosing
- a bug that would come back if the line were simplified

Not worth writing down: what a getter returns, what a well-named method does, or an opinion about the code.

## Tests

The layout engine, the wall geometry, the GIF decoder, the bandwidth counter and the palette are all tested
without a server, and anything that can be should be. Geometry especially: an axis the wrong way round renders
a wall mirrored, which is not visible in the code.

When fixing a bug, write the test that fails first and check it *does* fail - a test that passes against the
old code is not testing the fix.

## Licensing

MapGUI is LGPL-3.0-or-later, and anything you contribute to it is under that licence too. There is no CLA and
no copyright assignment - you keep your copyright, the project keeps the licence.

Files under `examples/` are MIT instead, so that people can copy from them freely. Contributions there are MIT.

Do not paste code in from a source under an incompatible licence, including anything a model generated from
unclear provenance. If a snippet came from somewhere, say where in the PR.

## Pull requests

Fork the repository, branch off `main`, and open the pull request from your branch. Nobody outside needs push
access here and there is no need to ask for any - a fork is the way in, and `main` takes changes through a pull
request either way.

The `Build` workflow runs on every pull request, forks included, and is the same `./gradlew build` you ran
locally. A first pull request from a new contributor can sit waiting for a maintainer to start it, which is
GitHub's own guard against strangers running code in someone else's CI - a check that has not begun is not a
check that failed.

- One concern per PR.
- `./gradlew build` clean, warnings included. The build is warning-free and should stay that way.
- Say what you tested in game, if it needed a server. Half of this cannot be unit tested.
