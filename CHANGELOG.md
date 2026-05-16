# Version 1.7.0

Mod:

- [ ] Support 26.1.2 version and Java 25
- [ ] Support YouTube shorts (separate button)
- [ ] Support Fabric servers
- [ ] Support singleplayer mode
- [ ] Remove `/display` command. The new functionality is available by interacting with the display and is more intuitive and user-friendly
- [ ] Improve UI / UX of display configuration and controls

Plugin:

- [ ] Remove `/display` command. The new functionality is available by interacting with the display and is more intuitive and user-friendly

# Version 1.6.3

Mod:

- [x] Faster YouTube web operations and video loading
- [x] Show 24 recommended videos based on the current video instead of 12
- [x] Use different browser list for macOS to avoid false security messages
- [x] Add `yt-dlp` proxy in config

Plugin:

- [x] No changes

# Version 1.6.2

Mod:

- [x] Switch from `GStreamer` to `FFmpeg` which is more reliable and performant library for video playback
- [x] Rewrite mod in Kotlin for better maintainability
- [x] Huge mod optimizations and stability improvements
- [x] Reduced CPU / GPU resource usage and improved performance significantly
- [x] Allow seeking to any position on the progress slider
- [x] Add `FFmpeg` automatic HTTP reconnection flags for resilient streaming over unstable networks
- [x] Add watchdog timer that detects stalled `FFmpeg` processes and restarts streams automatically
- [x] Retry on all transient errors (403, 404, 429, 5xx, connection resets, timeouts)
- [x] Add error handling for expired YouTube URLs
- [x] Fix brightness not saving properly
- [x] Fix client null error in window focus handling
- [x] Fix list of available qualities
- [x] Fix `BufferOverflow` in specific edge cases
- [x] Fix some edge cases of audio desynchronization after long playback
- [x] Fix suggestion scroller not showing up when in large menu mode
- [x] Fix language selector ([#73](https://github.com/arsmotorin/dreamdisplays/issues/73))
- [x] Fix volume reset after leaving active display distance ([#76](https://github.com/arsmotorin/dreamdisplays/issues/76))
- [x] Enhance project structure and code quality in some places

Plugin:

- [x] Rate-limit sync packet broadcasting to prevent flooding when owner seeks rapidly
- [x] Batch display info packets on player join to prevent client overload on servers with many displays
- [x] Validate sync packet time values to reject out-of-range data

# Version 1.6.1

Mod:

- [x] Correct suggestion translations
- [x] Fix video playback failing with a 403 Forbidden error when cached YouTube URLs expire – the player now automatically invalidates the stale cache entry and re-fetches fresh URLs from `yt-dlp` instead of permanently marking the screen as errored
- [x] Reduce format URL cache TTL from 5 hours to 2 hours to avoid serving near-expired YouTube CDN links
- [x] Improve error handling and timeout management in `yt-dlp` process execution

Plugin:

- [x] No changes

# Version 1.6.0

Mod:

- [x] Switch mod channel from Beta to Release
- [x] Support YouTube livestreams (live, première, and regular streams)
- [x] Direct searching and playback of YouTube videos without leaving the game
- [x] Suggested videos based on current video
- [x] Progress slider with seeking support
- [x] Mute and unmute buttons
- [x] Improved display configuration UI
- [x] Better UI icons in configuration
- [x] Improved video quality and format detection
- [x] Faster video loading and seeking with improved buffering and caching
- [x] Rewrite seek and quality-change to use a single reliable pipeline rebuild
- [x] Single unified pipeline for all content (merged video + audio)
- [x] Better synchronization for video playback
- [x] Video metadata caching system
- [x] Some stability improvements
- [x] Various optimizations and some small bug fixes
- [x] Update dependencies

Plugin:

- [x] Switch to Paper plugin
- [x] Drop Bukkit and Spigot support
- [x] Inform player about a display if they don't have the mod installed when they try to touch it
- [x] Various optimizations and some small bug fixes

# Version 1.5.0

Mod:

- [x] Switch YouTube playback to `yt-dlp`
- [x] Improve video playback stability and reduce some lags
- [x] Improve seeking, synchronization and buffering behavior
- [x] Improve video quality detection
- [x] Better detection of system GStreamer library path on macOS and Linux
- [x] Update Gradle to 9.4.0

Plugin:

- [x] No changes

# Version 1.4.4

Mod:

- [x] Add Spanish, French and Italian translations

Plugin:

- [x] Add `/display info` command for quick display information
- [x] Add `/display list` filters (`mine`, `world <name>`, `owner <name>`, `sync`)
- [x] Add translation for `/display list` command
- [x] Improve `/display video` error feedback (separate invalid URL/not owner/wrong target block)
- [x] Add total value output to `/display stats`
- [x] Add admin target mode for `/display on|off <player>`
- [x] Improve `/display reload` output with what was reloaded

# Version 1.4.3

Mod:

- [x] Update concurrency settings in build workflow
- [x] Update dependencies
- [x] Improve media player initialization handling and quality parsing
- [x] Use thread-safe `ConcurrentHashMap` for display management
- [x] Improved display sync stability

Plugin:

- [x] Improved `/display video` URL parsing: now accepts direct video IDs and more YouTube link formats (
  watch/shorts/embed/live/youtu.be).
- [x] Add paginated display listing with improved formatting
- [x] Improved tab-completion: now it's case-insensitive
- [x] Language suggestions for `/display video` when typing language parameter
- [x] Add permission and validation checks for display deletion
- [x] Better config mapping
- [x] Improved display sync stability
- [x] Player-only `/display` subcommands now return a clear console message instead of failing silently
- [x] Fixed scheduler timing mismatch between Bukkit and Folia

# Version 1.4.2

Mod:

- [x] Update dependencies
- [x] Fix remaining displays when world resets
- [x] Fix floating displays without base material
- [x] Remove unnecessary warnings and logs

Plugin:

- [x] Fix remaining displays when world resets
- [x] Fix floating displays without base material
- [x] Handle failed config gracefully
- [x] Remove unnecessary warnings and logs

# Version 1.4.1

Mod:

- [x] Fix releasing snapshots when pull requesting
- [x] Add Kolyakot33 as a contributor
- [x] Cleanup codebase

Plugin:

- [x] Fix Bukkit/Spigot server support
- [x] Fix selection visualizer for Folia servers
- [x] Temporary disabled mod detection for Folia servers due to Folia scheduler problems
- [x] Fix releasing snapshots when pull requesting
- [x] Add Kolyakot33 as a contributor

# Version 1.4.0

Mod:

- [x] Support Quilt
- [x] Update dependencies
- [x] Improve building workflow
- [x] Cleanup codebase

Plugin:

- [x] Fix display directions not being created properly in some cases
- [x] Cleanup codebase

# Version 1.3.2

Mod:

- [x] Fix display deletion not working properly

Plugin:

- [x] No changes

# Version 1.3.1

Mod:

- [x] Fix displays disappearing permanently when player walks out of render distance
- [x] Displays now load immediately when entering render distance
- [x] Fewer logs
- [x] Updated dependencies

Plugin:

- [x] Detect snapshot versions correctly

# Version 1.3.0

Mod:

- [x] We've created [Discord server](https://discord.gg/uwMMZ2KWk6)!
- [x] Smoother video playback and some optimizations
- [x] Video brightness control
- [x] Store paused state of display
- [x] Change maximum of render distance to 128 blocks ([#59](https://github.com/arsmotorin/dreamdisplays/issues/59))
- [x] Change maximum volume to 200% ([#60](https://github.com/arsmotorin/dreamdisplays/issues/60))
- [x] Fix playing videos after changing quality
- [x] Support CurseForge releases
- [x] Documentation in codebase of the mod

Plugin:

- [x] Refactors and small improvements
- [x] Documentation in codebase of the plugin
- [x] Improve update logic and fix ignoring mod versions ([#63](https://github.com/arsmotorin/dreamdisplays/issues/63))

# Version 1.2.0

Mod:

- [x] New, refreshed logo
- [x] All messages from plugin are in client's language now
- [x] Add missing messages for some commands
- [x] Remove client command `/displays` and move its functionality to plugin's `/display` command
- [x] Improve README and wiki
- [x] Show report button only if server has configured webhook URL
- [x] Fix an issue when after re-enabling displays they don't load until relog

Plugin:

- [x] New languages: Belarusian, Czech, German and Hebrew for plugin messages
- [x] Improve permissions handling for `/display create` and `/display video`
- [x] Add permission message when player lacks permission
- [x] Improve `/display list` command output
- [x] Add `/display help` and `/display stats` commands
- [x] Add links to some messages
- [x] Fix reporting message not showing correctly
- [x] Fix wrong command usage message logic

# Version 1.1.3

Mod:

- [x] Fix sync packet registration issues
- [x] Fix video playback time saving for non-synced displays
- [x] Fix texture errors when changing video quality
- [x] Fix NeoForge screen loading on server join

Plugin:

- [x] No changes

# Version 1.1.2

Mod:

- [x] Fix missing translations
- [x] Fix snapshot version detection as stable
- [x] Better releases system of mod
- [x] Update mappings

Plugin:

- [x] Add message when client doesn't have the mod installed
- [x] Better releases system of mod

# Version 1.1.1

Mod:

- [x] Fix display desynchronization with server and client
- [x] A bit improved screen rendering
- [x] Less logging
- [x] Code cleanup

Plugin:

- [x] Fix display desynchronization with server and client

# Version 1.1.0

Mod:

- [x] Support 1.21.11 version
- [x] Support NeoForge
- [x] Huge reduction of CPU usage, more stable and optimized
- [x] Store all displays from the servers
- [x] Support more YouTube links
- [x] Don’t mute displays on alt-tab by default
- [x] Better volume UI
- [x] Switched to Mojang mappings
- [x] Improved overall code quality
- [x] Enhanced logging
- [x] Improved wiki

Plugin:

- [x] Fixed repeated update notifications when switching dimensions
- [x] Refined, new configuration
- [x] Enhanced particle effects for selections
- [x] Created messages for empty report, display deletion, etc.
- [x] Separated update logic between mod and plugin
- [x] Plugin rewritten in Kotlin
- [x] Improved overall code quality
- [x] Corrected premium permission name
- [x] Removed hourly update notifications from the console
- [x] bStats

# Version 1.0.8

Mod:

- [x] Expanded max quality from 1080p to 4K
- [x] Tips for removing and reporting display
- [x] Warn player when switching to 1080p+

Plugin:

- [x] Support Spigot and Bukkit servers
- [x] New commands: /display list and /display reload
- [x] More languages for plugin configuration
- [x] .toml format for configuration files

# Version 1.0.7

Mod:

- [x] Discontinue FrogDisplays channel support

Plugin:

- [x] Folia support
- [x] Better comments in plugin configuration
- [x] Discontinue FrogDisplays channel support

# Version 1.0.6

Mod:

- [x] Added Hebrew, Czech and Belarussian languages support
- [x] Disabled volume relativity to Minecraft's volume
- [x] Vanilla language system
- [x] Improved volume configuration options
- [x] Default video quality is now 720p instead of 480p
- [x] Fixed GStreamer dead link

Plugin:

- [x] Bump version

# Version 1.0.5

Mod:

- [x] Added multi-language support for Russian, Ukrainian, Polish and German

Plugin:

- [x] Bump version

# Version 1.0.4

Mod:

- [x] Release channel is now Beta for Fabric
- [x] Project is now pen-source with LGPL-3.0 license
- [x] English is now the default language instead of Russian
- [x] New documentation with proper project information
- [x] Cleaned up redundant code and improved code quality
- [x] Added support for old mod versions
- [x] Added mod information
- [x] New icon

Plugin:

- [x] Release channel is now Release
- [x] English as the default language
- [x] New configuration
- [x] New mod name Dream Displays
- [x] Added support for old mod clients
- [x] Added plugin information

# Version 1.0.3

Mod:

- [x] Ignore GStreamer library if macOS

Plugin:

- [x] First public version

# Version 1.0.2

Mod:

- [x] Added other languages for videos

Plugin:

- [x] Bump version (not public)

# Version 1.0.1

Mod:

- [x] Fix client crash

Plugin:

- [x] Bump version (not public)

# Version 1.0.0

Mod:

- [x] First version

Plugin:

- [x] First version (not public)
