# Media audio

Platform-agnostic acoustics engine for Dream Displays, implementing `AudioAcousticsService` /
`AudioDspStage` from `:api`. Turns the S16LE stereo PCM block from the media pipeline into an
occlusion/reverb/distance-aware binaural (or speaker-pan) mix, per display, in-process — no OpenAL / native dependency.

## Contents

- `engine/` — `AcousticsEngine` (service impl, one chain per display) and `AudioRenderChain`
  (per-display DSP graph, quality-tier gated)
- `dsp/` — filter/limiter/loudness/reverb/delay primitives
- `spatial/` — emitter geometry, binaural rendering, stereo panning
- `math/` — minimal `Vec3` for source/listener geometry

## Boundaries

- Pure DSP/math only; no platform, rendering, or networking code
- Public models (`AcousticQuality`, `ListenerPose`, `SourceAcousticState`) live in `api`, not here
- Legacy gain fallback must stay bit-identical to `MediaBufferEffects.applyVolumeS16LE`

## Note

Quality tier gates how much of the chain runs. Tests live in `src/test/kotlin`; run with `./gradlew :media:audio:test`.
