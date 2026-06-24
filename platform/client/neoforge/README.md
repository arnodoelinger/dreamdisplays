# Platform client: NeoForge

`NeoForge` adapter for the client mod.

## Contents

- `NeoForge` entrypoint
- `NeoForge` metadata / resources / access transformers
- Loader-specific registration and wiring
- Shadow / relocation setup for the NeoForge .jar

## Boundaries

- Shared client logic belongs in `platform:client:common`
- `Fabric`-specific code does not belong here
- Do not create domain types or API contracts here

## Purpose

Build and connect `platform:client:common` to the `NeoForge` runtime.
