package com.dreamdisplays.media.source.direct

import com.dreamdisplays.api.media.DreamMediaException
import com.dreamdisplays.api.media.source.*
import com.dreamdisplays.api.media.stream.MediaStream
import com.dreamdisplays.api.media.stream.MediaStreamType
import com.dreamdisplays.media.runtime.MediaHostGuard
import com.dreamdisplays.util.net.DreamHttpClient
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Plays a URL that already is the media: a video file, an HLS playlist, or a DASH manifest.
 *
 * This is the resolver behind custom videos. Where the platform resolvers turn a page into
 * streams, this one confirms that a link is media and hands it to the player unchanged — no
 * subprocess, no extractor, one HTTP probe. That makes a pasted file play about as fast as the
 * decoder can open it, instead of paying a `yt-dlp` spawn for a URL that needed no extraction.
 *
 * It also claims [MediaSource.Remote], which is what makes "paste anything" work: a URL with no
 * recognizable extension is probed first, played directly when the server says it is media, and
 * otherwise refused so the chain falls through to `yt-dlp` exactly as before. The probe costs one
 * round trip on a path that was about to spawn a subprocess anyway.
 */
object DirectStreamResolver : MediaResolver {
    /** Logger. */
    private val logger = LoggerFactory.getLogger("DreamDisplays/DirectStreamResolver")

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

    /** Warms the probe cache; the registry already calls this off the caller's thread. */
    override fun prefetch(source: MediaSource) {
        runCatching { resolve(source) }
    }

    /**
     * Probes [source] and builds its streams.
     *
     * @throws DreamMediaException.NotFound when the URL is reachable but is not playable video -
     * a web page, or an audio-only file - which is the common "that is not the link you think it
     * is" mistake and deserves to be said out loud rather than surfaced as a decode failure.
     * @throws DreamMediaException.Network when the URL could not be reached at all.
     */
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
                throw DreamMediaException.Network("Could not reach this link. Check that it is public and still valid.", e)
            }
            notDirect.put(url, true)
            throw DreamMediaException.NotFound("Not a direct media URL: $url.", e)
        }

        val probe = DirectMediaProbe.probe(safeUrl)

        if (probe == null) {
            // A direct link that cannot even be probed is worth reporting as such; an unrecognized
            // remote URL just moves on to the extractors, which may well know how to open it.
            if (declaredKind.isDirect) {
                throw DreamMediaException.Network("Could not reach this link. Check that it is public and still valid.")
            }
            notDirect.put(url, true)
            throw DreamMediaException.NotFound("Not a direct media URL: $url.")
        }

        // A refusal on a speculative Remote probe is a permanent fact about that URL, so record it
        // once: re-probing on every quality switch would tax every extractor video in the game.
        runCatching { rejectNonVideo(probe, declaredKind) }.onFailure {
            if (!declaredKind.isDirect) notDirect.put(url, true)
            throw it
        }

        val effectiveKind = effectiveKind(declaredKind, probe)
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
    private fun rejectNonVideo(probe: DirectMediaProbe.Result, kind: CustomMediaKind) {
        if (probe.isHtml) {
            throw DreamMediaException.NotFound(
                "This link opens a web page, not a video file. Use the link to the file itself.",
            )
        }
        if (kind == CustomMediaKind.AUDIO_ONLY || probe.isAudioType) {
            throw DreamMediaException.NotFound(
                "This link is an audio file. A display needs a video to show.",
            )
        }
        if (!kind.isDirect && !probe.isMediaType) {
            // Speculative Remote probe that came back as something else entirely: nothing
            // user-facing to say, just step aside for the extractor chain.
            throw DreamMediaException.NotFound("Not direct media.")
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
            isSeekable = probe.acceptsRanges && kind != CustomMediaKind.DASH,
        )
    }

    /**
     * Fetches the playlist and, when it is a master, exposes every rendition as its own stream so
     * the quality slider works on a custom HLS link exactly as it does on a platform stream. A
     * media playlist resolves to itself, live unless it declares an end.
     */
    private fun resolveHls(playlistUrl: String): ResolvedMedia {
        val text = runCatching {
            DreamHttpClient.executeLimited(
                playlistUrl,
                maxBytes = MAX_PLAYLIST_BYTES,
                options = DreamHttpClient.RequestOptions(readTimeoutMs = 10_000L, callTimeoutMs = 12_000L),
            ).bodyString()
        }.getOrElse {
            throw DreamMediaException.Network("Could not read this playlist: ${it.message}", it)
        }

        if (!DirectHlsPlaylist.looksLikePlaylist(text)) {
            throw DreamMediaException.NotFound("This link is not a valid HLS playlist.")
        }

        val parsed = DirectHlsPlaylist.parse(text, playlistUrl)
        val streams = if (parsed.isMaster) {
            parsed.variants.map { variant ->
                MediaStream(
                    url = variant.url,
                    type = MediaStreamType.VIDEO_AUDIO,
                    codec = variant.codecs,
                    width = variant.width,
                    height = variant.height,
                    fps = variant.fps,
                    bitrate = variant.bandwidthBps,
                    audioTrackName = null,
                    audioTrackLang = null,
                )
            }
        } else {
            listOf(muxedStream(playlistUrl))
        }

        logger.debug("Direct HLS {}: {} renditions, live={}.", playlistUrl.take(120), streams.size, parsed.isLive)
        return ResolvedMedia(
            streams = streams,
            metadata = metadataFor(playlistUrl, null, fileName = null),
            isLive = parsed.isLive,
            isSeekable = !parsed.isLive,
        )
    }

    /** One muxed stream for [url]; dimensions stay unknown until the decoder opens it. */
    private fun muxedStream(url: String): MediaStream = MediaStream(
        url = url,
        type = MediaStreamType.VIDEO_AUDIO,
        codec = null,
        width = null,
        height = null,
        fps = null,
        bitrate = null,
        audioTrackName = null,
        audioTrackLang = null,
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
