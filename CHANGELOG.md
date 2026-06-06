# Version 1.7.2

## Client

### Features

- Support 26.2-pre4 version
- Use `LoggerFactory` for logging
- Use default config implementation instead of `AbstractConfig`
- Use `Stonecutter` for the multiversion system
- A bit fresher mod icon

### Improvements

- Update dependencies
- Reorganize the project structure
- Enhance cookie handling and process management
- Improve `Gradle` configuration
- Remove INotSleep's utils
- Improve the publishing system

### Fixes

- Critical `Quilt` entry point crash
- Ancient bug between `NeoForge` and IntelliJ IDEA

## Server

### Improvements

- Update dependencies
- Improve `Gradle` configuration

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
