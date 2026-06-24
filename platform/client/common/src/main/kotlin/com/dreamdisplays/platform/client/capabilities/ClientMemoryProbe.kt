package com.dreamdisplays.platform.client.capabilities

import com.sun.management.OperatingSystemMXBean
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.lang.management.ManagementFactory
import kotlin.math.ceil

/** Process-stable memory facts used by client capability reporting and warm-display budgeting. */
data class ClientMemoryInfo(
    /** Total physical system RAM in bytes, or `0` if unknown. */
    val systemRamBytes: Long,
    /** JVM maximum heap size in bytes (`-Xmx`), or `0` if unbounded or unknown. */
    val maxJvmMemoryBytes: Long,
    /** Dedicated GPU VRAM in bytes, or `0` if not detectable. */
    val dedicatedVramBytes: Long,
) {
    /** [systemRamBytes] rounded up to whole megabytes. */
    val systemRamMb: Int get() = bytesToMb(systemRamBytes)

    /** [maxJvmMemoryBytes] rounded up to whole megabytes. */
    val maxJvmMemoryMb: Int get() = bytesToMb(maxJvmMemoryBytes)

    /** [dedicatedVramBytes] rounded up to whole megabytes. */
    val dedicatedVramMb: Int get() = bytesToMb(dedicatedVramBytes)

    /** Bytes to MB conversion. */
    private fun bytesToMb(bytes: Long): Int = if (bytes <= 0L) 0 else ceil(bytes / (1024.0 * 1024.0)).toInt()
}

/** Best-effort RAM / VRAM probe. VRAM is 0 when the driver does not expose the NVIDIA GL memory extension. */
object ClientMemoryProbe {
    /** `GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX`; value is in KiB. */
    private const val GL_NVX_TOTAL_AVAILABLE_KB = 0x9048

    /** Cached probe results. */
    val detected: ClientMemoryInfo by lazy {
        ClientMemoryInfo(
            systemRamBytes = runCatching {
                (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize
            }.getOrDefault(0L),
            maxJvmMemoryBytes = Runtime.getRuntime().maxMemory().takeIf { it != Long.MAX_VALUE } ?: 0L,
            dedicatedVramBytes = runCatching {
                if (GL.getCapabilities().GL_NVX_gpu_memory_info)
                    GL11.glGetInteger(GL_NVX_TOTAL_AVAILABLE_KB).toLong() * 1024L else 0L
            }.getOrDefault(0L).coerceAtLeast(0L),
        )
    }
}
