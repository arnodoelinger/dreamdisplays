# Core

Internal domain implementation for Dream Displays. `core` knows product rules, but it does not know Minecraft loader-specific APIs.

## Contents

- Service implementations of the public `api` contracts: `DefaultDisplayService`, `DefaultPlaybackService`, `DefaultWatchPartyService`
- Internal display ports: `DisplaySystem` / `DefaultDisplaySystem`, `DisplayLookup`, `DisplayMutationPort`, `DisplayCommandExecutor`
- Playback / watch-party ports and logic: `PlaybackPort`, `WatchPartyPort`, `PlaybackPermissions`, `Timeline`
- Protocol v2 wire layer: `DreamPacket`, `Packets`, `PacketRegistry`, `ProtocolVersion`, `TimelineWire`, `UuidSerializer`
- Storage models: `DisplayStorage`, `FullDisplayData`
- Security policies / guards: `MediaUrlPolicy`, `MediaHostGuard`

## Boundaries

- `core` depends on `api`, not the other way around
- Do not put `Fabric` / `NeoForge` / `Paper` APIs, Minecraft classes, UI, rendering, or media decoder logic here
- Do not add public DTOs or service interfaces here. They belong in `api`
- Platform code talks to `core` through public API and internal ports only where it is actually doing wiring/adapters

## Dependencies

Allowed: `api`, `media`, serialization, compile-only `slf4j`
Forbidden: `platform:*`, `media:player`, `media:source`, loader-specific libraries
