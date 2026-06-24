# Platform client: Fabric

`Fabric` adapter for the client mod.

## Contents

- `Fabric` entrypoint.
- `Fabric` / `Quilt` metadata and resources
- Loader-specific packet / channel registration
- Shadow / relocation setup for the `Fabric` .jar

## Boundaries

- Shared client logic belongs in `platform:client:common`
- Never copy media / core / api code here
- Do not mix `NeoForge`-specific shit into this adapter

## Purpose

Build and connect `platform:client:common` to the `Fabric` runtime.
