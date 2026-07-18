# API

Public, unstable Dream Displays API. This module is the boundary external code, platform integrations, and sibling
modules can use without depending on `core` internals. Public contracts are marked with
`DreamDisplaysUnstableApi`.

## Contents

- `display` — domain models (`DisplayId`, `Display`, `DisplayBounds`, `DisplayFacing`, `DisplaySettings`,
  `DisplayRuntimeState`, `ContentRotation`, `DisplayEvent`) and the `DisplayService` contract /
  `DisplayServices` keys
- `playback` — `PlaybackService`, `PlaybackServices`, plus `PlaybackMode` / `PlaybackAction` in `PlaybackTypes`
- `watchparty` — `WatchPartyService`, `WatchPartyServices`, `WatchPartySession`, `WatchPartyAction`,
  `WatchPartySessionState`
- `capability` — typed server feature names (`ServerFeature`) used around protocol capability negotiation
- `media.source` — resolver contracts: `MediaSource`, `MediaResolver`, `MediaResolverRegistry`, `ResolvedMedia`,
  `MediaMetadata`
- `media.stream` — `MediaStream`, `MediaStreamType`, `SupportedCodec`
- `media.session` — `MediaSession`, `MediaSessionState`, `MediaSessionEvent`
- `media.search` — `MediaSearchService`, `MediaSearchResult`, `YouTubeUrls`
- `media.sink` — decoder output contracts: `VideoFrameSink`, `DecodedVideoFrame`, `AudioSink`
- `media` — shared media service keys (`MediaServices`)
- `media.player` — playback host hooks: `PlaybackHost`, `FrameUploader`, `GpuTextureRef`, `RenderThreadExecutor`
- `render` — render/upload contracts: `DisplayRenderer`, `RenderContext`, `RenderSurface`, `RenderStats`,
  `TextureUploader` / `TextureUploaderFactory`, `TextureHandle`, `UploadBudget`, `FrameDropPolicy`,
  `RenderBackend`, `ShaderBackend`, `TextureUploadPath`, `RenderServices`
- `runtime` — construction contracts: `DreamDisplaysApi`, `DreamDisplaysRuntime`, `DreamDisplaysModule`,
  `ModuleContext`, `ServiceRegistry`, `ServiceKey`
- `platform` — loader-neutral platform hooks: `Platform`, `PlatformSide`, `PlatformLogger`, `PlatformPaths`,
  `PlatformScheduler`, `TaskHandle`, `PlatformId`, `PlatformServices`
- `util` — small shared API helpers such as `WireEnum`
- `media` (top-level, alongside `media.player` / `media.source` / etc.) — foundational media value types with zero
  dependencies: `VideoQuality`, `FramePixelFormat`, `DreamMediaException`, `MediaFailureKind`

## Boundaries

- `api` must not depend on `core` or import `com.dreamdisplays.core.*`
- Do not put implementations, caches, file IO, network IO, Minecraft / `Paper` / `Fabric` / `NeoForge` classes here
- If a type is meant for public consumers, it belongs here, not in `core`
- Keep contracts loader-neutral: expose value objects, service interfaces, sinks, and handles instead of runtime classes

## Dependents

`core`, `media:runtime`, `media:player`, `media:source`, and `platform:*` may depend on `api`. The reverse dependency is
forbidden.
