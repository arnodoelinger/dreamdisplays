# Platform client

Aggregator for client platform modules. Almost no code should live here.

## Contents

- `platform:client:common` is shared client logic
- `platform:client:fabric` is `Fabric` entrypoint, wiring, and build setup
- `platform:client:neoforge` is `NeoForge` entrypoint, wiring, and build setup

## Boundaries

- Shared client code belongs in `common`
- Loader-specific lifecycle, registration, metadata, and packaging belong in `fabric` or `neoforge`
- Do not move core / media product logic here
