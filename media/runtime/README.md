# Media runtime

Runtime support for media sessions and system helpers needed by the media pipeline, but not part of the public domain
model.

## Contents

- Session runtime: `MediaSession`, `MediaSessionManager`, `DefaultMediaSessionManager`, `DisplayMediaSession`
- Runtime metadata / state / event models for internal sessions
- OS/process helpers: `OsInfo`, `Processes`

## Boundaries

- May depend on `api` and `media`
- Must not pull Minecraft / `Fabric` / `NeoForge` / `Paper` classes
- Do not put stream resolving, `yt-dlp`, `FFmpeg` process management, or rendering here

## Why separate from media

`media` must stay a clean type module. `media:runtime` connects media sessions to display/playback services from `api`.
