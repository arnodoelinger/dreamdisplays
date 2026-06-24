# Util

Small shared utilities that do not belong to a specific domain.

## Contents

- Coroutine helpers
- JSON / general utility helpers
- Async memoization
- Etc.

## Boundaries

- Do not put display / playback / media business logic here
- Do not put public API models here
- Do not turn `util` into a dumping ground for platform-specific code
- If a helper starts knowing too much about a domain, move it to that module

## Dependencies

Keep this module lightweight. Add new dependencies only when they are actually necessary.
