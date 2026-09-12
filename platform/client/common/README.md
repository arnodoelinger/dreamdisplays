# Platform client: common

Shared client-side Minecraft integration. This module wires the public API, core display system, media player / source,
and Minecraft client UI / rendering.

## Contents

- Client lifecycle and service registry
- Display registry, screen state, UI / menu / input
- Minecraft render / upload adapters
- Playback host / environment adapters for `media:player`
- Shared client protocol / network adapters

## Boundaries

- Minecraft client APIs are allowed here, but `Fabric` / `NeoForge` entrypoint APIs are not
- Loader-specific registration stays in `platform:client:fabric` and `platform:client:neoforge`
- Media decoding / resolution is implemented by `media:player` and `media:source`, not here
- Public API types belong in `api`, not here

## Dependencies

Depends on `api`, `core`, `media:*`, `util`, and Minecraft client libraries.
