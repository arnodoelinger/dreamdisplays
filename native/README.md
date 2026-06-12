# Dream Displays Native

Optional Rust library that takes over the hottest CPU paths of the media pipeline. Consumed from Kotlin via the Project
Panama (yes, we would better drop support for natives on Java 21 or earlier than use silly JNI).

## Build

```sh
cd native
cargo build --release
cargo test
```

If `native/target/release/` contains the library when the mod jars are built, Gradle bundles it into the jar at
`dreamdisplays-natives/<os>-<arch>/`. At runtime `NativeMedia` extracts it to `./dreamdisplays/native/<os>-<arch>/` in the
game directory.

## ABI

C ABI, declared in `src/lib.rs`.

## TODO

[ ] In-process decoding via `libav\***`
[ ] YUV on the GPU — upload NV12 planes as textures and decode them in the shader (or use a shader-based decoder)
[ ] Audio optimizations?
[ ] More tests
