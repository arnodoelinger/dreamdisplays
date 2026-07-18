package com.dreamdisplays.platform.server.scheduler

import com.dreamdisplays.platform.server.utils.PlatformUtil.isFolia
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.jspecify.annotations.NullMarked

/**
 * A provider object that selects the appropriate `AdapterScheduler` implementation
 * based on the server environment (`Folia` or `Paper`).
 */
@PaperOnly
@NullMarked
object ProviderScheduler {
    val adapter: AdapterScheduler by lazy {
        if (isFolia) FoliaScheduler else PaperScheduler
    }
}
