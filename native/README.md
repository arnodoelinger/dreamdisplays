# Native

Optional Rust native layer for hot media paths.

## Contents

- `dreamdisplays_native` (`src/`) — low-level helpers for Kotlin media code: pixel-format `convert` and the `session` bridge.
- `dreamdisplays_lav` (`lav/src/`) — optional in-process video decode path through `FFmpeg` / `libav`:
  `session`, `surface`, and a rolling packet `cache`.
- C ABI declarations consumed from Kotlin through `Project Panama`.

## Boundaries

- Kotlin orchestration stays in `media:player` and `platform:client:common`
- No Minecraft, `Fabric`, `NeoForge`, or `Paper` code belongs here
- Native code must expose a small stable C ABI and keep ownership / lifetime rules explicit

## Build

```sh
cd native
cargo build --release
cargo test
```

Gradle bundles local or CI-built native artifacts into mod jars under `dreamdisplays-natives/<os>-<arch>/`.
