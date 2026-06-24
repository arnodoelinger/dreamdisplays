# Media

Base media types without runtime implementation. This is a neutral layer used by `api`, `core`, and the media player /
runtime / source modules.

## Contents

- `DreamMediaException`
- `MediaFailureKind`
- `FramePixelFormat`
- `VideoQuality`

## Boundaries

- No processes, `FFmpeg`, `yt-dlp`, file cache, HTTP, Minecraft, or core services
- Types here should be small, stable, and suitable for public APIs

## Dependents

`api`, `core`, `media:runtime`, `media:player`, `media:source`, and `platform:*`.
