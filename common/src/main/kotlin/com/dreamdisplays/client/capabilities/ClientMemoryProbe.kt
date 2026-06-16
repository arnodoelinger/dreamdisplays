package com.dreamdisplays.client.capabilities

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.lang.management.ManagementFactory
import kotlin.math.ceil

/** Process-stable memory facts used by client capability reporting and warm-display budgeting. */
data class ClientMemoryInfo(
    val systemRamBytes: Long,
    val maxJvmMemoryBytes: Long,
    val dedicatedVramBytes: Long,
) {
    val systemRamMb: Int get() = bytesToMb(systemRamBytes)
    val maxJvmMemoryMb: Int get() = bytesToMb(maxJvmMemoryBytes)
    val dedicatedVramMb: Int get() = bytesToMb(dedicatedVramBytes)

    companion object {
        private fun bytesToMb(bytes: Long): Int =
            if (bytes <= 0L) 0 else ceil(bytes / (1024.0 * 1024.0)).toInt()
    }
}

/** Best-effort RAM / VRAM probe. VRAM is 0 when the driver does not expose a reliable GL memory extension. */
object ClientMemoryProbe {
    private const val GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048

    val detected: ClientMemoryInfo by lazy {
        ClientMemoryInfo(
            systemRamBytes = detectSystemRamBytes(),
            maxJvmMemoryBytes = Runtime.getRuntime().maxMemory().takeIf { it != Long.MAX_VALUE } ?: 0L,
            dedicatedVramBytes = detectDedicatedVramBytes(),
        )
    }

    private fun detectSystemRamBytes(): Long {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        val sunBean = runCatching { Class.forName("com.sun.management.OperatingSystemMXBean") }.getOrNull()
        if (sunBean?.isInstance(bean) == true) {
            val value = firstLongMethod(bean, sunBean, "getTotalMemorySize", "getTotalPhysicalMemorySize")
            if (value > 0L) return value
        }
        return firstLongMethod(bean, bean.javaClass, "getTotalMemorySize", "getTotalPhysicalMemorySize")
    }

    private fun firstLongMethod(target: Any, methodOwner: Class<*>, vararg names: String): Long {
        for (name in names) {
            val value = runCatching {
                val method = methodOwner.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: return@runCatching 0L
                (method.invoke(target) as? Number)?.toLong() ?: 0L
            }.getOrDefault(0L)
            if (value > 0L) return value
        }
        return 0L
    }

    private fun detectDedicatedVramBytes(): Long = runCatching {
        val caps = GL.getCapabilities()
        when {
            caps.GL_NVX_gpu_memory_info ->
                GL11.glGetInteger(GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX).toLong() * 1024L
            else -> 0L
        }.coerceAtLeast(0L)
    }.getOrDefault(0L)
}
