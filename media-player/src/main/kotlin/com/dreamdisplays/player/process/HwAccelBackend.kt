package com.dreamdisplays.player.process

/**
 * Hardware-accelerated video decoder backends supported by `FFmpeg`.
 *
 * The intent is to offload H.264 / HEVC / VP9 / AV1 decoding from the CPU to the GPU's dedicated
 * video decode block (`NVDEC` on NVIDIA, `VCE` on AMD, `QuickSync` on Intel, the Apple VT block on macOS).
 */
enum class HwAccelBackend(val ffmpegName: String?, val hwOutputFormat: String?, val lavCode: Int) {
    /** Apple platforms. Handles h264, hevc, vp9, prores. */
    VIDEOTOOLBOX("videotoolbox", "videotoolbox_vld", 2),

    /** Windows `Direct3D 11` Video Acceleration. */
    D3D11VA("d3d11va", "d3d11", 3),

    /** Linux `Video Acceleration API` works on AMD / Intel; NVIDIA needs the nouveau or proprietary `VAAPI` bridge. */
    VAAPI("vaapi", "vaapi", 4),

    /** NVIDIA CUDA / NVDEC are fastest on NVIDIA, but limited to NVIDIA cards. */
    CUDA("cuda", "cuda", 5),

    /** Software decoding only. */
    NONE(null, null, 0);

    companion object {
        /**
         * Picks a sensible default backend for the host OS. We deliberately pick the most broadly
         * compatible option per-platform rather than the absolute fastest: a stream that fails to
         * decode is worse than a stream that decodes a bit slower.
         */
        fun detectDefault(): HwAccelBackend {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            return when {
                os.contains("mac") || os.contains("darwin") -> VIDEOTOOLBOX
                os.contains("win") -> D3D11VA
                os.contains("nux") || os.contains("nix") -> VAAPI
                else -> NONE
            }
        }

        /**
         * Returns true if [stderr] looks like an `FFmpeg` startup failure caused specifically by the
         * hardware decoder (codec not supported on this hardware, driver missing, device init
         * failed, etc.). We use this to detect when a hwaccel pick was wrong for the current stream
         * and silently retry with software decoding.
         *
         * False positives just mean a one-off slower restart, false negatives mean
         * the stream stays broken until the user toggles the config flag.
         */
        fun looksLikeHwAccelFailure(stderr: String): Boolean {
            if (stderr.isEmpty()) return false
            val s = stderr.lowercase()
            return HWACCEL_FAIL_MARKERS.any { s.contains(it) }
        }

        private val HWACCEL_FAIL_MARKERS = listOf(
            "hwaccel",
            "videotoolbox",
            "d3d11va",
            "vaapi",
            "cuda",
            "cuvid",
            "nvdec",
            "hardware acceleration",
            "failed setup for format",
            "no device available",
            "device creation failed",
            "no usable hwaccel",
            "decoder does not support",
            "scale_vt",
            "no such filter",
        )
    }
}
