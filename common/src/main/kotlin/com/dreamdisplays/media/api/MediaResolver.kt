package com.dreamdisplays.media.api

interface MediaResolver {
    val priority: Int get() = 0
    fun canResolve(source: MediaSource): Boolean
    fun resolve(source: MediaSource): ResolvedMedia

    /** Pre-warms format caches for [source] before playback starts. No-op by default. */
    fun prefetch(source: MediaSource) {}
}
