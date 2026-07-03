# Native

Optional Rust native layer for hot media paths.

## Contents

- `dreamdisplays_native` (`src/`) — low-level helpers for Kotlin media code: pixel-format `convert` and the `session`
  bridge.
- `dreamdisplays_lav` (`lav/src/`) — optional in-process video decode path through `FFmpeg` / `libav`:
  `session`, `surface`, and a rolling packet `cache`.
- C ABI declarations consumed from Kotlin through `Project Panama`.

## Boundaries

- Kotlin orchestration stays in `media:player` and `platform:client:common`
- No Minecraft, `Fabric`, `NeoForge`, or `Paper` code belongs here
- Native code must expose a small stable C ABI and keep ownership / lifetime rules explicit

## Build

```sh
./gradlew :native:buildHostNatives   # cargo build --release -> native/target/release
./gradlew :native:testHostNatives    # cargo test
```

The auto-build needs a Rust toolchain (`cargo` on `PATH`, or `~/.cargo/bin/cargo`). Machines without
Rust — or CI using the `native/build/ci-bundle/` artifacts — skip it automatically; force-disable with
`-Pdreamdisplays.autoBuildNatives=false`.

> [WARNING]
> `cargo test` builds a separate debug test binary — it does **not** refresh the release
> `.dylib` / `.so` / `.dll` the game loads. The client build uses the release build, so just run the
> client to verify a change in-game.
