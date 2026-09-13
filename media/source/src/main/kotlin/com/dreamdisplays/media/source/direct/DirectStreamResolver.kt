package com.dreamdisplays.media.source.direct

import com.dreamdisplays.api.media.model.DreamMediaException
import com.dreamdisplays.api.media.source.model.CustomMediaKind
import com.dreamdisplays.api.media.source.model.MediaMetadata
import com.dreamdisplays.api.media.source.model.MediaSource
import com.dreamdisplays.api.media.source.model.ResolvedMedia
import com.dreamdisplays.api.media.source.service.MediaResolverService
import com.dreamdisplays.api.media.source.url.CustomMediaUrls
import com.dreamdisplays.api.media.stream.model.MediaStream
import com.dreamdisplays.api.media.stream.model.MediaStreamType
import com.dreamdisplays.api.security.policy.MediaHosts
import com.dreamdisplays.media.runtime.security.MediaHostGuard
import com.dreamdisplays.util.net.DreamHttpClient
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Plays a URL that already is the media: a video file, an HLS playlist, or a DASH manifest.
 */
object DirectStreamResolver : MediaResolverService {
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Above the extractor resolvers: when a URL really is media, no extractor should see it. */
    override val priority: Int = 20

    /** Cap on a fetched playlist, which is text and never legitimately larger than this. */
    private const val MAX_PLAYLIST_BYTES = 1 * 1024 * 1024

    /** Files and VOD manifests are stable; the cache exists mostly to absorb prefetch -> resolve. */
    private const val CACHE_MINUTES = 10L

    /**
     * Resolutions are cached by URL. Signed CDN links do expire, but well within [CACHE_MINUTES] a
     * re-resolve would return the same URL anyway — the player re-enters this resolver on quality
     * switches and stall recovery, and a fresh probe per restart is pure latency.
     */
    private val cache: Cache<String, ResolvedMedia> = Caffeine.newBuilder()
        .maximumSize(64)
        .expireAfterWrite(CACHE_MINUTES, TimeUnit.MINUTES)
        .build()

    /**
     * URLs a probe already proved are not direct media, so the speculative attempt on a page URL is
     * paid once rather than on every re-entry. Without it a normal extractor video would probe
     * again on every quality switch and stall recovery, adding a round trip each time to a path
     * that was always going to end up at `yt-dlp`.
     */
    private val notDirect: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(256)
        .expireAfterWrite(CACHE_MINUTES, TimeUnit.MINUTES)
        .build()

    /**
     * Claims direct sources outright, and unrecognized remote URLs speculatively - for those the
     * probe in [resolve] is the real test, and failing it hands the URL back to the chain.
     */
    override fun canResolve(source: MediaSource): Boolean =
        source is MediaSource.DirectStream || source is MediaSource.Remote

    /**
     * Warms the probe cache; the registry already calls this off the caller's thread. Returns false
     * for a speculative [MediaSource.Remote] the probe rejects — that URL belongs to an extractor,
     * so the registry should carry on warming the resolver that will actually serve it.
     */
    override fun prefetch(source: MediaSource): Boolean = runCatching { resolve(source) }.isSuccess

    /** Probes [source] and builds its streams. */
    override fun resolve(source: MediaSource): ResolvedMedia {
        val url = source.toResolvableUrl()
            ?: throw UnsupportedOperationException("$source has no resolvable URL.")
        cache.getIfPresent(url)?.let { return it }

        val declaredKind = (source as? MediaSource.DirectStream)?.kind
            ?: CustomMediaUrls.classify(url)
        if (!declaredKind.isDirect && notDirect.getIfPresent(url) != null) {
            throw DreamMediaException.NotFound("Not a direct media URL: $url.")
        }

        // Walk the redirect chain through the SSRF guard first, so the probe (which follows no
        // redirects itself) only ever talks to a host the guard has cleared - closing the blind-SSRF
        // hole where a public URL 302s to an internal address.
        val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(url) }.getOrElse { e ->
            if (declaredKind.isDirect) {
                throw DreamMediaException.Network(
                    "Could not reach this link. Check that it is public and still valid.",
                    e
                )
            }
            notDirect.put(url, true)
            throw DreamMediaException.NotFound("Not a direct media URL: $url.", e)
        }

        // A host nobody vouched for has to prove what it serves: its headers are whatever it chose
        // to write, so the probe reads the leading bytes and names the container itself.
        val firstParty = MediaHosts.isFirstParty(safeUrl)
        val probe = DirectMediaProbe.probe(safeUrl, requireBytes = !firstParty)

        if (probe == null) {
            // A direct link that cannot even be probed is worth reporting as such; an unrecognized
            // remote URL just moves on to the extractors, which may well know how to open it.
            if (declaredKind.isDirect) {
                throw DreamMediaException.Network("Could not reach this link. Check that it is public and still valid.")
            }
            notDirect.put(url, true)
            throw DreamMediaException.NotFound("Not a direct media URL: $url.")
        }

        val effectiveKind = effectiveKind(declaredKind, probe)

        // A refusal on a speculative Remote probe is a permanent fact about that URL, so record it
        // once: re-probing on every quality switch would tax every extractor video in the game.
        runCatching { rejectNonVideo(probe, declaredKind, effectiveKind, firstParty) }.onFailure {
            if (!declaredKind.isDirect) notDirect.put(url, true)
            throw it
        }

        val resolved = when (effectiveKind) {
            CustomMediaKind.HLS -> resolveHls(probe.finalUrl)
            else -> resolveFile(probe, url, effectiveKind)
        }
        cache.put(url, resolved)
        return resolved
    }

    /** Drops [url]'s cached verdicts, so the next open re-probes instead of re-serving a dead link. */
    fun invalidate(url: String) {
        cache.invalidate(url)
        notDirect.invalidate(url)
    }

    /**
     * Refuses everything that is reachable but cannot become a picture on a display, with the
     * reason spelled out. Each of these is a mistake a player can act on, unlike a decode error.
     */
    private fun rejectNonVideo(
        probe: DirectMediaProbe.Result, declared: CustomMediaKind, effective: CustomMediaKind,
        firstParty: Boolean,
    ) {
        if (probe.isHtml) {
            throw DreamMediaException.NotFound(
                "This link opens a web page, not a video file. Use the link to the file itself.",
            )
        }
        // audio/... means an audio file only when the response really is the media. A manifest is
        // just a text index of renditions, and HLS's own legacy content type lives in that namespace.
        if (declared == CustomMediaKind.AUDIO_ONLY || (probe.isAudioType && !effective.isManifest)) {
            throw DreamMediaException.NotFound(
                "This link is an audio file. A display needs a video to show.",
            )
        }
        if (!declared.isDirect && !probe.isMediaType) {
            // Speculative Remote probe that came back as something else entirely: nothing
            // user-facing to say, just step aside for the extractor chain.
            throw DreamMediaException.NotFound("Not direct media.")
        }
        // The link looking like a file is not evidence that it is one: a URL ending in .mp4 can
        // serve anything at all, and until now nothing checked. Everything a display will decode
        // off a pasted host must have been recognized from its own leading bytes.
        if (!firstParty && !probe.verifiedByBytes) {
            throw DreamMediaException.NotFound(
                "This link does not serve a video file. Check that it points straight at the media.",
            )
        }
    }

    /**
     * Reconciles what the URL looked like with what the server actually served. The `Content-Type`
     * wins for manifests, because a playlist behind an extension-less or signed URL is common and
     * must be parsed as a playlist rather than opened as a file.
     */
    private fun effectiveKind(declared: CustomMediaKind, probe: DirectMediaProbe.Result): CustomMediaKind {
        val type = probe.contentType
        return when {
            type == "application/vnd.apple.mpegurl" || type == "application/x-mpegurl" ||
                    type == "audio/mpegurl" -> CustomMediaKind.HLS

            type == "application/dash+xml" -> CustomMediaKind.DASH
            declared.isDirect -> declared
            else -> CustomMediaKind.PROGRESSIVE
        }
    }

    /**
     * Builds the single muxed stream of a plain file, seekable when the server supports byte ranges
     * (no ranges means no seeking, whatever the container says) and with the duration read straight
     * out of the container header so the seek bar and scrub preview have a timeline to work with.
     */
    private fun resolveFile(
        probe: DirectMediaProbe.Result,
        originalUrl: String,
        kind: CustomMediaKind,
    ): ResolvedMedia {
        // The bundled FFmpeg is built without libxml2, so it has no DASH demuxer at all
        if (kind == CustomMediaKind.DASH) {
            throw DreamMediaException.NotFound("DASH manifests need the extractor chain, not the direct player.")
        }
        val durationNanos = if (probe.acceptsRanges) {
            DirectMediaDuration.probe(probe.finalUrl, CustomMediaUrls.extensionOf(originalUrl), probe.contentLength)
        } else {
            null
        }
        logger.debug(
            "Direct file {}: type={} length={} ranges={} duration={}ns.",
            originalUrl.take(120), probe.contentType, probe.contentLength, probe.acceptsRanges, durationNanos,
        )
        return ResolvedMedia(
            streams = listOf(muxedStream(probe.finalUrl)),
            metadata = metadataFor(originalUrl, durationNanos, probe.fileName),
            // A file the server will not range-seek behaves like a stream: it can only play forward
            isLive = false,
            isSeekable = probe.acceptsRanges,
        )
    }

    /**
     * Fetches the playlist and, when it is a master, exposes every rendition as its own stream so
     * the quality slider works on a custom HLS link exactly as it does on a platform stream. A
     * media playlist resolves to itself, live unless it declares an end.
     */
    private fun resolveHls(playlistUrl: String): ResolvedMedia {
        val text = fetchPlaylist(playlistUrl)
            ?: throw DreamMediaException.Network("Could not read this playlist.")

        if (!DirectHlsPlaylist.looksLikePlaylist(text)) {
            throw DreamMediaException.NotFound("This link is not a valid HLS playlist.")
        }

        val parsed = DirectHlsPlaylist.parse(text, playlistUrl)
        // A master says nothing about liveness or container on its own, so one of its renditions is
        // read too. That answers both questions for the price of a few kilobytes, and both change
        // what the player may do: whether a seek bar is offered at all, and whether a seek may be
        // handed to the demuxer.
        val rendition = if (parsed.isMaster) probeRendition(parsed) else parsed
        val isLive = rendition?.isLive ?: parsed.isLive
        val seekByDecoding = rendition?.hasInitSegment == true && !isLive

        val streams =
            if (parsed.isMaster) masterStreams(parsed, seekByDecoding)
            else listOf(muxedStream(playlistUrl, seekByDecoding))

        // Summed from the rendition's own segment list. Without it the player has no timeline, and a
        // VOD with no timeline has no seek bar — the stream plays, but it cannot be scrubbed at all.
        val durationNanos = rendition?.totalDurationNanos?.takeIf { it > 0L && !isLive }

        logger.debug(
            "Direct HLS {}: {} stream(s), live={}, fragmentedMp4={}, duration={}ns.",
            playlistUrl.take(120), streams.size, isLive, rendition?.hasInitSegment, durationNanos,
        )
        return ResolvedMedia(
            streams = streams,
            metadata = metadataFor(playlistUrl, durationNanos, fileName = null),
            isLive = isLive,
            isSeekable = !isLive,
        )
    }

    /**
     * Reads one of [master]'s media playlists — the default audio rendition when the sound is
     * separate, else the first variant. Null when it cannot be read, which is not fatal: the caller
     * falls back to what the master alone implies, exactly as before this probe existed.
     */
    private fun probeRendition(master: DirectHlsPlaylist.Parsed): DirectHlsPlaylist.Parsed? {
        val url = master.audioRenditions.firstOrNull()?.url
            ?: master.variants.firstOrNull()?.url
            ?: return null
        val safeUrl = runCatching { MediaHostGuard.resolveSafeUrl(url) }.getOrNull() ?: return null
        val text = fetchPlaylist(safeUrl)?.takeIf(DirectHlsPlaylist::looksLikePlaylist) ?: return null
        return DirectHlsPlaylist.parse(text, safeUrl)
    }

    /** Fetches a playlist body, capped at [MAX_PLAYLIST_BYTES]; null when it could not be read. */
    private fun fetchPlaylist(url: String): String? = runCatching {
        DreamHttpClient.executeLimited(
            url,
            maxBytes = MAX_PLAYLIST_BYTES,
            options = DreamHttpClient.RequestOptions(readTimeoutMs = 10_000L, callTimeoutMs = 12_000L),
        ).bodyString()
    }.getOrNull()

    /** * Turns a master playlist into the stream list the selector works on. */
    private fun masterStreams(parsed: DirectHlsPlaylist.Parsed, seekByDecoding: Boolean): List<MediaStream> {
        // Every rendition with its own playlist, not just the group of the variant that survived
        // de-duplication — otherwise a master that lists its languages as separate groups would keep
        // only the first one, and the rest would silently vanish from the audio-track picker.
        val separateAudio = parsed.audioRenditions.distinctBy { it.url }
        val videoOnly = separateAudio.isNotEmpty() && parsed.variants.any { it.audioGroupId != null }
        val video = parsed.variants.map { variant ->
            MediaStream(
                url = variant.url,
                type = if (videoOnly) MediaStreamType.VIDEO else MediaStreamType.VIDEO_AUDIO,
                codec = variant.codecs,
                width = variant.width,
                height = variant.height,
                fps = variant.fps,
                bitrate = variant.bandwidthBps,
                audioTrackName = null,
                audioTrackLang = null,
                seekByDecoding = seekByDecoding,
            )
        }
        if (!videoOnly) return video
        val audio = separateAudio.map { rendition ->
            MediaStream(
                url = rendition.url,
                type = MediaStreamType.AUDIO,
                codec = null,
                width = null,
                height = null,
                fps = null,
                bitrate = null,
                audioTrackName = rendition.name,
                audioTrackLang = rendition.language,
                isDefault = rendition.isDefault,
                seekByDecoding = seekByDecoding,
            )
        }
        logger.debug("Direct HLS master carries {} separate audio rendition(s).", audio.size)
        return video + audio
    }

    /** One muxed stream for [url]; dimensions stay unknown until the decoder opens it. */
    private fun muxedStream(url: String, seekByDecoding: Boolean = false): MediaStream = MediaStream(
        url = url,
        type = MediaStreamType.VIDEO_AUDIO,
        codec = null,
        width = null,
        height = null,
        fps = null,
        bitrate = null,
        audioTrackName = null,
        audioTrackLang = null,
        seekByDecoding = seekByDecoding,
    )

    /**
     * The only metadata a bare file can offer: a title (the server's `Content-Disposition` filename
     * when it gave one, else the name derived from the URL) and its host as the "uploader".
     */
    private fun metadataFor(url: String, durationNanos: Long?, fileName: String?): MediaMetadata =
        MediaMetadata.UNKNOWN.copy(
            title = fileName?.let(CustomMediaUrls::cleanFileName)?.takeIf { it.isNotBlank() }
                ?: CustomMediaUrls.displayName(url),
            uploader = CustomMediaUrls.hostOf(url),
            duration = durationNanos?.takeIf { it > 0L }?.nanoseconds,
        )
}
