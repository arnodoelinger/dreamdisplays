# Media source

Media source resolution and search. This module turns a user URL or search query into a stream set that `media:player`
can play.

## Contents

- `DefaultMediaResolverChain`
- `DefaultStreamSelector`
- YouTube / `yt-dlp` / `NewPipeExtractor` integration
- Metadata / title / cache helpers for source resolution

## Boundaries

- No rendering, playback loop, or Minecraft shit
- Resolver tooling such as `yt-dlp` is allowed here, but display state management is not
- Results should leave through `com.dreamdisplays.media.api`

## Dependencies

Allowed: `api`, `media`, `media:runtime`, and `media:player` only when a shared stream / player contract is needed.
