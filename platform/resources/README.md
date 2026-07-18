# Platform resources

Shared, platform-agnostic asset source of truth for Dream Displays. No code, no build logic — a
plain `src/main/resources` tree that other platform modules pull files out of via
`project(":platform:resources").file(...)` instead of duplicating assets per loader.

## Contents

- Mixin config (`dreamdisplays.mixins.json`), mod icon, `version.txt` template
- Shaders (fog, YUV / NV12 color conversion)
- GUI sprite textures
- Client and server translations, plus the default `config.toml`

## Boundaries

- Assets shared by 2+ platform modules belong here, not duplicated per loader
- Code does not belong here — this module has none
- `lang/client/**` and `lang/server/**` are pulled selectively; don't merge them

## Note

`platform/server` and the client loader modules each add this module's resources as an extra source
dir and copy out only the `lang/` subtree they own.
