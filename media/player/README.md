# Media player

Client playback engine. It prepares, starts, and controls playback of media streams that have already been resolved.

## Contents

- `MediaPlayer` and playback state.
- `FFmpeg` / native process bridge
- Frame / audio pipeline, buffering, watchdog, and stats
- Stream selection integration through API contracts

## Boundaries

- This module must not know Minecraft UI, blocks, screens, or loader-specific APIs
- The platform layer provides `PlaybackHost`, renderer/upload callbacks, and environment through `api`
- Video search and `yt-dlp` discovery belong in `media:source`, not here
- Public contracts belong in `api`, not here

## Dependencies

Allowed: `api`, `media`, `media:runtime`, selected `core` security / protocol pieces where needed, native helpers.
