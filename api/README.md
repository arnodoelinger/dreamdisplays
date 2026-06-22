# API

Public, experimental Dream Displays API. This module is the boundary external code, platform integrations, and sibling
modules can use without depending on `core` internals. Everything here is annotated as unstable via `DreamDisplaysUnstableApi`.

## Contents

- `display` — domain models (`DisplayId`, `Display`, `DisplayBounds`, `DisplayFacing`, `DisplaySettings`,
  `DisplayRuntimeState`, `DisplayEvent`) and the `DisplayService` contract
- `playback` — `PlaybackService`, plus `PlaybackMode` / `PlaybackAction` in `PlaybackTypes`
- `watchparty` — `WatchPartyService`, `WatchPartySession`, `WatchPartyAction`, `WatchPartySessionState`
- `media.source` — resolver contracts: `MediaSource`, `MediaResolver`, `MediaResolverRegistry`, `ResolvedMedia`, `MediaMetadata`
- `media.stream` — `MediaStream`, `MediaStreamType`
- `media.session` — `MediaSession`, `MediaSessionState`, `MediaSessionEvent`
- `media.search` — `MediaSearchService`, `MediaSearchResult`, `YouTubeUrls`
- `media.sink` — decoder output contracts: `VideoFrameSink`, `DecodedVideoFrame`, `AudioSink`
- `media.player` — playback host hooks: `PlaybackHost`, `FrameUploader`, `GpuTextureRef`, `RenderThreadExecutor`
- `render` — render/upload contracts: `DisplayRenderer`, `RenderContext`, `RenderSurface`, `RenderStats`,
  `TextureUploader` / `TextureUploaderFactory`, `TextureHandle`, `UploadBudget`, `FrameDropPolicy`
- `platform` — loader-neutral platform hooks: `Platform`, `PlatformSide`, `PlatformLogger`, `PlatformPaths`,
  `PlatformScheduler`, `TaskHandle`

## Boundaries

- `api` must not depend on `core` or import `com.dreamdisplays.core.*`
- Do not put implementations, caches, file IO, network IO, Minecraft / `Paper` / `Fabric` / `NeoForge` classes here
- If a type is meant for public consumers, it belongs here or in a small independent module such as `media`, not in `core`

## Dependents

`core`, `media:*`, and `platform:*` may depend on `api`. The reverse dependency is forbidden.
