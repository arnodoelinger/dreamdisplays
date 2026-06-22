# Platform server

Server platform for Dream Displays. This module adapts core / protocol / storage to `Paper` / `Folia` / `Purpur` and `Fabric` server targets through `Stonecutter` / `OFRAT`.

## Contents

- Plugin / mod entrypoint and lifecycle
- Commands, permissions, player / server integration
- Persistent display storage adapters
- Server networking and protocol handlers
- Server-side playback/watch-party managers

## Boundaries

- Server platform APIs and generated/chiseled source are allowed here
- Public API models must not be added here; they belong in `api`
- Client UI / render / player code does not belong here
- Shared product rules belong in `core`; server-specific orchestration belongs here

## Note

Part of the source is processed through `Stonecutter` / `OFRAT`.

**If compilation shows strange unresolved platform classes, `:platform:server:chiselSource` often needs a rerun.**
