# Releasing

There are two artifacts with two audiences, and they go to different places.

| | `mapgui-api` + `mapgui-layout` | `MapGUI.jar` |
|---|---|---|
| For | plugin **developers** | **server owners** |
| Goes to | Maven Central | a GitHub release, then Hangar and Modrinth |
| Obtained by | one line in a build file | downloading it into `plugins/` |
| Contains | only the classes you compile against | everything - api, layout, nms, runtime |
| Used at | compile time | runtime |

They are separate because each audience wants the opposite thing. A server owner has no build tool, so Central
is useless to them. A developer must never ship MapGUI inside their own jar, so publishing the fat jar would be
actively harmful - it would put NMS internals and Mojang-mapped classes on their compile classpath and drag
`paperweight` into their build.

`compileOnly` plus the `paper-plugin.yml` dependency is what joins them up: a plugin compiles against the API
and the implementation arrives at runtime from whatever the server owner installed. So there is only ever one
copy of MapGUI on a server, and it is theirs. It is the same split Paper uses for `paper-api` and the server
jar, for the same reason.

Central was chosen over the alternatives on the advice of Paper's own maintainers: it needs no third-party
repository in a consumer's dependency tree, which is the thing that makes a library annoying to depend on. Only
the two API modules are published there - see the `published` set in `build.gradle.kts`.

## One-time setup

### 1. Claim the namespace

Sign in to [central.sonatype.com](https://central.sonatype.com) **with GitHub**. The `io.github.flog99`
namespace is then offered for verification and confirmed automatically, because the account proves you own
the name. No domain and no DNS record.

That is also why the group is `io.github.flog99` while the packages stay `de.flog99.mapgui.*` - nothing
requires the two to match.

### 2. Generate a publishing token

In the Portal, **Account → Generate User Token**. It gives you a username and a password, neither of which is
your login. Those become `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.

### 3. Make a signing key

Central requires every artifact to be signed.

```
gpg --quick-generate-key "FloG99 <you@example.com>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long          # note the key id
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>           # the whole block, BEGIN and END lines included
```

Publishing the public key to a keyserver is not optional - Central checks it can find it.

### 4. Add four repository secrets

**Settings → Secrets and variables → Actions**:

| Secret | |
|---|---|
| `MAVEN_GPG_PRIVATE_KEY` | the whole armored block from the export above |
| `MAVEN_GPG_PASSPHRASE` | the passphrase for that key |
| `MAVEN_CENTRAL_USERNAME` | the token username, not your login |
| `MAVEN_CENTRAL_PASSWORD` | the token password |

## Releasing

```
git tag v1.0.0
git push origin v1.0.0
```

That is it. The workflow builds and tests, signs the bundle, refuses to continue if no signatures came out,
uploads to the Portal, and drafts a GitHub release.

Attached to that release:

| Asset | For |
|---|---|
| `MapGUI-<version>.jar` | every server. The only required download |
| `MapGUI-examples-<version>.zip` | a test server. Unpacks into `plugins/` - five demo plugins and a sample video. Delete a jar to drop that demo |
| `mapgui-api` + `mapgui-layout` jars, with sources | developers who would rather drag a jar in than add a dependency |

The examples are deliberately not inside `MapGUI.jar`. A jar holds one plugin, so bundling them would mean the
plugin loading its own demos instead of them being real third-party consumers of the API - and that property is
the reason they are worth shipping at all. It would also need a config flag to switch off something an admin
can simply not install.

The upload is `USER_MANAGED`, so the release then **waits in the Portal** until you look at it and press
publish. Validation takes a few minutes; once published it reaches `repo1.maven.org` within the hour, and
search indexing follows later the same day. Nobody reviews it by hand - the wait is automated checks, not a
queue.

Switch `publishingType` to `AUTOMATIC` in `release.yml` once you trust the pipeline and want the tag to be
the only step.

**A version can never be replaced.** If 1.0.0 is wrong, 1.0.1 is the fix. Which is what the draft release and
`USER_MANAGED` are guarding against.

## Doing it by hand

```
./gradlew centralBundle -Pversion=1.0.0
```

Needs `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` in the environment, or the bundle comes out unsigned
and the Portal rejects it. The result is `build/central-bundle.zip`, which you can drop into the Portal's
upload form directly.

Without a key set, signing is skipped entirely - which is what keeps an ordinary `./gradlew build` and
`publishToMavenLocal` working with no GPG installed.

## Snapshots

Central takes releases only, and `centralBundle` refuses a `-SNAPSHOT` version rather than failing later at
upload. To let someone try an unreleased change:

```
./gradlew publishToMavenLocal
```

They then add `mavenLocal()` and depend on `io.github.flog99:mapgui-api:1.0.0-SNAPSHOT`.

## Where the plugin jar goes

Maven Central is for developers. Server owners want [Hangar](https://hangar.papermc.io) and
[Modrinth](https://modrinth.com), which is where Paper points them - both are manual uploads today, and both
take the same `MapGUI.jar` the release workflow already produces.

Publish both from the same tag, so the version a developer compiled against and the version a server is
running are the same number. The API a plugin compiles against must not be *newer* than the plugin installed -
that is the one direction that breaks, with a `NoSuchMethodError` on whatever was added in between.
