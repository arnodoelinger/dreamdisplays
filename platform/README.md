# Platform

Aggregator for platform modules. It should not contain product logic by itself.

## Contents

- `platform:client` are client-side Minecraft integrations
- `platform:server` is server-side Paper/Fabric code

## Boundaries

- Do not add shared domain logic here. Use `api`, `core`, or `media`
- Loader-specific code belongs in the concrete adapter module
- If code is needed by both client and server, first check whether it belongs in `api` or `core`
