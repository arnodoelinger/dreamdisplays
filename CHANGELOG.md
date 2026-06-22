# Version 1.8.3

## Client

### Improvements

- Improved experimental API
- Hardened background maintenance tasks against hanging the game on exit
- Reworked background networking, thumbnail, and cache work onto a unified coroutine scheduler for cleaner shutdown and fewer idle threads
- Improved Dream Displays security

### Fixes

- Fixed 360p quality lock in some cases

## Server

### Improvements

- Improved experimental API
- Improved display data saving
- Improved version parsing
- Moved webhook reports and `Fabric` database saves off the main server thread
- Improved Dream Displays security

### Fixes

- Fixed periodic display / player update ticks running on an async scheduler on `Paper` servers
- Fixed unsafe async `Bukkit` / `Paper` API usage
- Fixed displays not being saved until the server shuts down cleanly, so a crash could lose newly created or edited displays
- Fixed display owners on `Paper` servers needing extra permission to delete their own display, unlike `Fabric`
- Fixed a malformed legacy network packet being able to crash decoding instead of being safely rejected
- Fixed broadcast displays briefly losing their quality clamp right after reconnecting until the server resent it
- Fixed the display cache file being able to get corrupted if the game / server crashed mid-save
- Fixed a race that let concurrent reports slip past the report cooldown
- Fixed default permissions; (local), synced and broadcast are for all players, no only for OPs 

# Version 1.8.2

## Client

### Improvements

- YouTube videos now load a bit faster
- Smoothed out a brief stutter that could happen right when a video changed
- Tightened how video links are handled, with length limits and network-only access to keep them from being abused
- Enhanced error screen when video loading fails
- Enhanced video loading animation
- Added 26.2 version to Paper building system
- Improved Dream Displays security

### Fixes

- Fixed audio cutting out after about 10 seconds ([#107](https://github.com/arsmotorin/dreamdisplays/pull/107))
- Fixed repeating video playback in local playback mode

## Server

### Improvements

- Players can no longer spam the report system
- Improved Dream Displays security

# Version 1.8.1

## Client

### Features

- Added experimental support of native optimizations for 1.21.11

### Improvements

- Improved translations for Russian and Ukrainian languages
- Improved `FFmpeg` download logging and unpacking flow
- Adopted Rust 2024 edition for natives and enhanced log handling

### Fixes

- Fixed vertex format crash on `Fabric` 1.21.11
- Reduced log spam

## Server

### Improvements

- Improved translations for Russian and Ukrainian languages

### Fixes

- Single-player displays are now stored per-world instead of the global database
- Replaced hardcoded max dimensions with placeholders

# Version 1.8.0

## Client

### Features
- Added support for Minecraft 26.2
- Brought back Minecraft 1.21.11 support ([#91](https://github.com/arsmotorin/dreamdisplays/pull/91))
- Added a new packet protocol v2
- Added fallback support for protocol v1, but v1 is now deprecated and will be removed in the future
- Introduced an unstable client-side API that will be scaled in the future
- Switched the multiversion system to `Stonecutter`, so old versions will be supported too
- Added stable `Vulkan` support for display rendering (`OpenGL` rendering is still supported)
- Replaced the old synchronization mode with new playback modes (server 1.8.0+ required)
- Added local, synced, broadcast playback modes (server 1.8.0+ required)
- Support vertical displays (server 1.8.0+ required)
- Added a native Rust media pipeline
- Integrated `FFmpeg` into the native media pipeline
- Added in-process LAV backend for video decoding
- Added GPU YUV / NV12 rendering path
- Added planar display textures for native video frames
- Added dynamic frame format support for native video frames
- Added improved cursor handling in the display menu
- Increased the default render distance to 96 blocks
- Switched display visibility logic from block-based checks to chunk-based checks
- Increased the effective display rendering range from 2 chunks to 12 chunks
- Reduced CPU usage by up to 50× on tested mid-range hardware scenarios (Java 25 required)
- Reduced CPU usage by up to 70× on tested low-end hardware scenarios (Java 25 required)
- Improved video stream resolving speed by up to 10–12× in supported cases
- Added seamless and faster video quality changes
- Improved shader compatibility
- Added more anonymous telemetry data to improve development, compatibility, and stability
- Added a fresher mod icon
- Improved several menu icons

### Improvements

- Improved media player performance thanks to the native media pipeline
- Improved video frame processing stability
- Improved brightness handling in the video frame pipeline
- Added a more efficient native video frame path
- Reduced expensive CPU-side frame conversion work
- Improved GPU upload behavior for video frames
- Improved realtime-safe stream selection
- 60 FPS stream selection is now opt-in
- Improved `yt-dlp` quality fallback logic
- Improved `yt-dlp` resolver failure handling
- Improved video startup behavior when stream resolving fails
- Improved detection of DRM-protected videos
- DRM-protected videos now fail faster and more gracefully
- Improved cookie handling
- Improved process management for external media tools
- Improved display rendering stability on larger displays
- Improved display rendering stability at longer distances
- Improved compatibility with shader mods
- Improved compatibility with `VulkanMod`
- Improved Picture-in-Picture display sizing logic
- Improved display menu behavior on different GUI scales
- Improved display menu icon behavior
- Improved locked display handling
- Improved temporary focus mute behavior
- Improved unsafe filename handling for server display cache files
- Improved client texture creation validation
- Replaced the old `AbstractConfig` usage with the default config implementation
- Replaced custom logging usage with LoggerFactory
- Reorganized the project structure
- Improved Gradle configuration
- Improved workflows
- Improved the publishing system
- Removed old Gradle cache configuration
- Removed INotSleep's utils
- Simplified multiple internal code paths
- Cleaned up old compatibility code
- Updated dependencies
- Added many small internal cleanups, simplifications, and stability improvements

### Fixes

- Fixed a critical crash on `Fabric` 1.21.11
- Fixed a critical `Quilt` entry point crash
- Fixed an ancient `NeoForge` and IntelliJ IDEA compatibility issue
- Fixed `NeoForge` client shutdown on normal server disconnect
- Fixed FFmpeg extraction on Linux ([#93](https://github.com/arsmotorin/dreamdisplays/issues/93))
- Fixed incompatibility between the popout window and `Vivecraft`
- Fixed GUI scale handling in the display menu
- Fixed several shader compatibility issues
- Fixed `VulkanMod` compatibility issues
- Fixed strange red and green screen blinking while loading videos
- Fixed quality fallback to 360p when `yt-dlp` fails
- Fixed incorrect waiting behavior for DRM-protected videos
- Fixed Picture-in-Picture mode display size calculation
- Fixed render distance localization
- Fixed locked display abuses
- Fixed the false locked display icon in the display menu
- Fixed temporary focus mute overwriting the user's mute setting
- Fixed unsafe server display cache filenames breaking on some systems
- Fixed invalid display sizes creating broken client textures
- Fixed several display menu edge cases
- Fixed several native frame pipeline edge cases
- Fixed several video resolver edge cases
- Fixed several display rendering edge cases
- Fixed multiple small stability issues

## Server

### Features

- Added support for Minecraft 26.2 `Fabric` servers
- Implemented Minecraft 1.21.11 support for `Fabric` servers
- Added support for the new playback modes
- Added Java 21 support for Minecraft 1.21.11 servers
- Added a new packet protocol v2
- Added fallback support for protocol v1, but v1 is now deprecated and will be removed in the future
- Added `dreamdisplays.local`, `dreamdisplays.synced`, `dreamdisplays.broadcast`, `dreamdisplays.lock`, `dreamdisplays.delete.others`, and `dreamdisplays.create.bypass` permissions
- Added more anonymous telemetry data to improve development, compatibility, and stability

### Improvements

- Simplified server-side display storage updates
- Removed the old display validator flow
- Improved server-side handling of display-enabled state updates
- Removed the useless report button in single-player
- Improved Gradle configuration
- Improved server module structure
- Updated dependencies
- Added multiple small server-side cleanups and simplifications

### Fixes

- Fixed `MariaDB` compatibility issue ([#88](https://github.com/arsmotorin/dreamdisplays/pull/88))
- Fixed sending display enabled packets to clients
- Fixed several `Fabric` server compatibility issues
- Fixed several small server-side stability issues

# Version 1.8.0-SNAPSHOT.2

## Client

### Features

- 10-12× faster video downloading

### Improvements

- Enhance the client codebase with managers
- Faster DRM-protected videos handling
- Remove Gradle cache configuration

### Fixes

- Fix critical crash on the `Fabric` 1.21.11 version
- Fix incompatibility between the popout window and `Vivecraft` mod
- Fix DRM waiting issue
- Less log spam

## Server

- No changes

# Version 1.8.0-SNAPSHOT.1

## Client

### Features

- Support 26.2-pre4 version
- Back 1.21.11 support ([#91](https://github.com/arsmotorin/dreamdisplays/pull/91))
- Use `Stonecutter` for the multiversion system
- Support `Vulcan` for display rendering (`OpenGL` still supported)
- Use default config implementation instead of `AbstractConfig`
- Use `LoggerFactory` for logging
- A bit fresher mod icon

### Improvements

- Reorganize the project structure
- Video frame pipeline stability and brightness handling
- Enhance cookie handling and process management
- Improve `Gradle` configuration
- Workflow improvements
- Improve the publishing system
- Remove INotSleep's utils
- Update dependencies

### Fixes

- Critical `Quilt` entry point crash
- Ancient bug between `NeoForge` and IntelliJ IDEA
- `NeoForge` client shutdown on normal server disconnect
- `FFmpeg` extraction on Linux ([#93](https://github.com/arsmotorin/dreamdisplays/issues/93))
- Picture-in-Picture mode displays size calculation
- Temporary focus mute no longer overwrites the user's mute setting
- Unsafe server display cache filenames on some systems
- Invalid display sizes no longer create broken client textures

## Server

### Features

- Support 26.2-pre4 `Fabric` servers
- Implement 1.21.11 support for `Fabric` servers
- Back Java 21 support (if you're running on 1.21.11, you can still use Java 21 with this version instead of being forced to update to Java 25, as it was in previous versions)

### Improvements

- Remove the useless report button in single-player
- Improve `Gradle` configuration
- Update dependencies

### Fixes

- `MariaDB` compatibility issue ([#88](https://github.com/arsmotorin/dreamdisplays/pull/88))
- Allow sending display enabled packets to clients

# Version 1.7.1

## Client

### Features

- A bit fresher mod icon

### Improvements

- Better version publishing on Modrinth
- Reduce JAR size by ~50%

### Fixes

- Fabric config parsing error
- NeoForge `set_locked` packet error

## Server

### Improvements

- Some code refactoring
- Reduce JAR size by ~50%

### Fixes

- `FabricDisplayData` error when server shutdowns

# Version 1.7.0

## Client

### Features
- Support 26.1.2 version and Java 25
- Support `Fabric` servers
- Support YouTube shorts
- Windowed and Picture-in-Picture mode
- Hardware-accelerated `FFmpeg` video decoding
- Show max 72 recommended videos based on the current video instead of 24

### Improvements
- Switch from RGBA to RGB24 for improved rendering performance
- Videos now stop rendering (but still play) when Minecraft is minimized
- Enhance watchdog logic for low-connection networks and stability
- Enhance YouTube's cache for stability
- Skip restoring saved time if sync is active
- Preserve sync mode when switching videos
- Reduce maximum brightness from 200% to 100%
- Deprecate `/display` command (will be replaced by direct interaction with displays in future versions)
- Add dynamic material messages
- Update dependencies and replace some of them with better alternatives

### Fixes
- Fix cropping at display edges
- Fix mute logic and allow players to mute displays in sync mode
- Fix admins can't delete displays through the menu
- Fix the "You have to look at the display block" error when there is actually display ([#79](https://github.com/arsmotorin/dreamdisplays/issues/79))
- Fix a strange version number in the menu ([#81](https://github.com/arsmotorin/dreamdisplays/issues/81))
- Fix version semantic versioning parsing for mod updates
- Fix tiled thumbnail rendering in the menu
- Fix texture race crash in some rare cases
- Fix a locked quality bug ([#80](https://github.com/arsmotorin/dreamdisplays/issues/80))
- Fix seek time overwriting the current playback time
- Fix hanging `yt-dlp` when cookies are unavailable

## Server

### Features
- Support `Fabric` servers
- Follow client's feature of lock / unlock displays
- Deprecate `/display` command (will be replaced by direct interaction with displays in future versions)

### Improvements
- Preserve sync mode when switching videos
- Broadcast synced display state every 2 seconds
- Add dynamic material messages
- Update dependencies and replace some of them with better alternatives

# Version 1.7.0-SNAPSHOT.4

## Mod

- Now you can decide whether to lock or unlock a display from modifying by other players in the menu (works only on servers with the new plugin version)
- New async texture uploader with a triple-buffered PBO ring
- Hardware-accelerated `FFmpeg` video decoding
- Reimplement fix of OpenGL `GL_INVALID_VALUE` error in all modes
- Skip restoring saved time if sync is active
- Fix cropping at display edges
- Fix tiled thumbnail rendering in the menu
- Fix mute logic and allow players to mute displays in sync mode
- Fix admins can't delete displays through the menu
- Fix the "You have to look at the display block" error when there is actually display ([#79](https://github.com/arsmotorin/dreamdisplays/issues/79))
- Fix a locked quality bug ([#80](https://github.com/arsmotorin/dreamdisplays/issues/80))
- Fix a strange version number in the menu ([#81](https://github.com/arsmotorin/dreamdisplays/issues/81))
- Fix texture race crash in some rare cases
- Fix seek time overwriting the current playback time
- Fix hanging `yt-dlp` when cookies are unavailable
- Fix no display territories translations (you need to use axe, not pickaxe)

## Server

- Follow the client's feature of lock / unlock displays
- Preserve sync mode when switching videos
- Broadcast synced display state every 2 seconds
- Update Paper API to the 65-stable build

# Version 1.7.0-SNAPSHOT.3

## Mod

- Use RGB format instead of RGBA for Picture-in-Picture mode
- Fix OpenGL `GL_INVALID_VALUE` error in windowed mode
- Fix `EXCEPTION_ACCESS_VIOLATION` crash because of `GL_INVALID_VALUE` errors

## Server

- No changes

# Version 1.7.0-SNAPSHOT.2

## Mod

- Windowed and Picture-in-Picture mode
- Enhance YouTube's cache for stability
- Enhance watchdog logic for low connection networks and stability
- Fix OpenGL `GL_INVALID_VALUE` error

## Server

- No changes

# Version 1.7.0-SNAPSHOT.1

## Mod

- Support 26.1.2 version and Java 25
- Support Fabric servers
- Switch from RGBA to RGB24 for improved rendering performance
- Reduce maximum brightness from 200% to 100%
- Videos now stop rendering (but still plays) when Minecraft is minimized

## Server

- Some documentation standardization

# Version 1.6.3

## Mod

- Faster YouTube web operations and video loading
- Show max 24 recommended videos based on the current video instead of 12
- Load 3 displays simultaneously instead of 4 to avoid `yt-dlp` overloading
- Don't prefetch suggestions videos to avoid unnecessary `yt-dlp` calls
- Use different browser list for macOS for better compatibility
- Add `yt-dlp` proxy option in config
- Fix critical bug where displays prefetching even far away from the player
- Standardize logs, warnings and errors
- Reformat codebase

## Server

- Standardize logs, warnings and errors
- Reformat codebase

# Version 1.6.2

## Mod

- Switch from `GStreamer` to `FFmpeg` which is more reliable and performant library for video playback
- Rewrite mod in Kotlin for better maintainability
- Huge mod optimizations and stability improvements
- Reduced CPU / GPU resource usage and improved performance significantly
- Allow seeking to any position on the progress slider
- Add `FFmpeg` automatic HTTP reconnection flags for resilient streaming over unstable networks
- Add watchdog timer that detects stalled `FFmpeg` processes and restarts streams automatically
- Retry on all transient errors (403, 404, 429, 5xx, connection resets, timeouts)
- Add error handling for expired YouTube URLs
- Fix brightness not saving properly
- Fix client null error in window focus handling
- Fix list of available qualities
- Fix `BufferOverflow` in specific edge cases
- Fix some edge cases of audio desynchronization after long playback
- Fix suggestion scroller not showing up when in large menu mode
- Fix language selector ([#73](https://github.com/arsmotorin/dreamdisplays/issues/73))
- Fix volume reset after leaving active display distance ([#76](https://github.com/arsmotorin/dreamdisplays/issues/76))
- Enhance project structure and code quality in some places

## Server

- Rate-limit sync packet broadcasting to prevent flooding when owner seeks rapidly
- Batch display info packets on player join to prevent client overload on servers with many displays
- Validate sync packet time values to reject out-of-range data

# Version 1.6.1

## Mod

- Correct suggestion translations
- Fix video playback failing with a 403 Forbidden error when cached YouTube URLs expire – the player now automatically invalidates the stale cache entry and re-fetches fresh URLs from `yt-dlp` instead of permanently marking the screen as errored
- Reduce format URL cache TTL from 5 hours to 2 hours to avoid serving near-expired YouTube CDN links
- Improve error handling and timeout management in `yt-dlp` process execution

## Server

- No changes

# Version 1.6.0

## Mod

- Switch mod channel from Beta to Release
- Support YouTube livestreams (live, première, and regular streams)
- Direct searching and playback of YouTube videos without leaving the game
- Suggested videos based on current video
- Progress slider with seeking support
- Mute and unmute buttons
- Improved display configuration UI
- Better UI icons in configuration
- Improved video quality and format detection
- Faster video loading and seeking with improved buffering and caching
- Rewrite seek and quality-change to use a single reliable pipeline rebuild
- Single unified pipeline for all content (merged video + audio)
- Better synchronization for video playback
- Video metadata caching system
- Some stability improvements
- Various optimizations and some small bug fixes
- Update dependencies

## Server

- Switch to Paper plugin
- Drop Bukkit and Spigot support
- Inform player about a display if they don't have the mod installed when they try to touch it
- Various optimizations and some small bug fixes

# Version 1.5.0

## Mod

- Switch YouTube playback to `yt-dlp`
- Improve video playback stability and reduce some lags
- Improve seeking, synchronization and buffering behavior
- Improve video quality detection
- Better detection of system GStreamer library path on macOS and Linux
- Update Gradle to 9.4.0

## Server

- No changes

# Version 1.4.4

## Mod

- Add Spanish, French and Italian translations

## Server

- Add `/display info` command for quick display information
- Add `/display list` filters (`mine`, `world <name>`, `owner <name>`, `sync`)
- Add translation for `/display list` command
- Improve `/display video` error feedback (separate invalid URL/not owner/wrong target block)
- Add total value output to `/display stats`
- Add admin target mode for `/display on|off <player>`
- Improve `/display reload` output with what was reloaded

# Version 1.4.3

## Mod

- Update concurrency settings in build workflow
- Update dependencies
- Improve media player initialization handling and quality parsing
- Use thread-safe `ConcurrentHashMap` for display management
- Improved display sync stability

## Server

- Improved `/display video` URL parsing: now accepts direct video IDs and more YouTube link formats (
  watch/shorts/embed/live/youtu.be).
- Add paginated display listing with improved formatting
- Improved tab-completion: now it's case-insensitive
- Language suggestions for `/display video` when typing language parameter
- Add permission and validation checks for display deletion
- Better config mapping
- Improved display sync stability
- Player-only `/display` subcommands now return a clear console message instead of failing silently
- Fixed scheduler timing mismatch between Bukkit and Folia

# Version 1.4.2

## Mod

- Update dependencies
- Fix remaining displays when world resets
- Fix floating displays without base material
- Remove unnecessary warnings and logs

## Server

- Fix remaining displays when world resets
- Fix floating displays without base material
- Handle failed config gracefully
- Remove unnecessary warnings and logs

# Version 1.4.1

## Mod

- Fix releasing snapshots when pull requesting
- Add Kolyakot33 as a contributor
- Cleanup codebase

## Server

- Fix Bukkit/Spigot server support
- Fix selection visualizer for Folia servers
- Temporary disabled mod detection for Folia servers due to Folia scheduler problems
- Fix releasing snapshots when pull requesting
- Add Kolyakot33 as a contributor

# Version 1.4.0

## Mod

- Support Quilt
- Update dependencies
- Improve building workflow
- Cleanup codebase

## Server

- Fix display directions not being created properly in some cases
- Cleanup codebase

# Version 1.3.2

## Mod

- Fix display deletion not working properly

## Server

- No changes

# Version 1.3.1

## Mod

- Fix displays disappearing permanently when player walks out of render distance
- Displays now load immediately when entering render distance
- Fewer logs
- Updated dependencies

## Server

- Detect snapshot versions correctly

# Version 1.3.0

## Mod

- We've created [Discord server](https://discord.gg/uwMMZ2KWk6)!
- Smoother video playback and some optimizations
- Video brightness control
- Store paused state of display
- Change maximum of render distance to 128 blocks ([#59](https://github.com/arsmotorin/dreamdisplays/issues/59))
- Change maximum volume to 200% ([#60](https://github.com/arsmotorin/dreamdisplays/issues/60))
- Fix playing videos after changing quality
- Support CurseForge releases
- Documentation in codebase of the mod

## Server

- Refactors and small improvements
- Documentation in codebase of the plugin
- Improve update logic and fix ignoring mod versions ([#63](https://github.com/arsmotorin/dreamdisplays/issues/63))

# Version 1.2.0

## Mod

- New, refreshed logo
- All messages from plugin are in client's language now
- Add missing messages for some commands
- Remove client command `/displays` and move its functionality to plugin's `/display` command
- Improve README and wiki
- Show report button only if server has configured webhook URL
- Fix an issue when after re-enabling displays they don't load until relog

## Server

- New languages: Belarusian, Czech, German and Hebrew for plugin messages
- Improve permissions handling for `/display create` and `/display video`
- Add permission message when player lacks permission
- Improve `/display list` command output
- Add `/display help` and `/display stats` commands
- Add links to some messages
- Fix reporting message not showing correctly
- Fix wrong command usage message logic

# Version 1.1.3

## Mod

- Fix sync packet registration issues
- Fix video playback time saving for non-synced displays
- Fix texture errors when changing video quality
- Fix NeoForge screen loading on server join

## Server

- No changes

# Version 1.1.2

## Mod

- Fix missing translations
- Fix snapshot version detection as stable
- Better releases system of mod
- Update mappings

## Server

- Add message when client doesn't have the mod installed
- Better releases system of mod

# Version 1.1.1

## Mod

- Fix display desynchronization with server and client
- A bit improved screen rendering
- Less logging
- Code cleanup

## Server

- Fix display desynchronization with server and client

# Version 1.1.0

## Mod

- Support 1.21.11 version
- Support NeoForge
- Huge reduction of CPU usage, more stable and optimized
- Store all displays from the servers
- Support more YouTube links
- Don’t mute displays on alt-tab by default
- Better volume UI
- Switched to Mojang mappings
- Improved overall code quality
- Enhanced logging
- Improved wiki

## Server

- Fixed repeated update notifications when switching dimensions
- Refined, new configuration
- Enhanced particle effects for selections
- Created messages for empty report, display deletion, etc.
- Separated update logic between mod and plugin
- Plugin rewritten in Kotlin
- Improved overall code quality
- Corrected premium permission name
- Removed hourly update notifications from the console
- bStats

# Version 1.0.8

## Mod

- Expanded max quality from 1080p to 4K
- Tips for removing and reporting display
- Warn player when switching to 1080p+

## Server

- Support Spigot and Bukkit servers
- New commands: /display list and /display reload
- More languages for plugin configuration
- .toml format for configuration files

# Version 1.0.7

## Mod

- Discontinue FrogDisplays channel support

## Server

- Folia support
- Better comments in plugin configuration
- Discontinue FrogDisplays channel support

# Version 1.0.6

## Mod

- Added Hebrew, Czech and Belarussian languages support
- Disabled volume relativity to Minecraft's volume
- Vanilla language system
- Improved volume configuration options
- Default video quality is now 720p instead of 480p
- Fixed GStreamer dead link

## Server

- Bump version

# Version 1.0.5

## Mod

- Added multi-language support for Russian, Ukrainian, Polish and German

## Server

- Bump version

# Version 1.0.4

## Mod

- Release channel is now Beta for Fabric
- Project is now pen-source with LGPL-3.0 license
- English is now the default language instead of Russian
- New documentation with proper project information
- Cleaned up redundant code and improved code quality
- Added support for old mod versions
- Added mod information
- New icon

## Server

- Release channel is now Release
- English as the default language
- New configuration
- New mod name Dream Displays
- Added support for old mod clients
- Added plugin information

# Version 1.0.3

## Mod

- Ignore GStreamer library if macOS

## Server

- First public version

# Version 1.0.2

## Mod

- Added other languages for videos

## Server

- Bump version (not public)

# Version 1.0.1

## Mod

- Fix client crash

## Server

- Bump version (not public)

# Version 1.0.0

## Mod

- First version

## Server

- First version (not public)
