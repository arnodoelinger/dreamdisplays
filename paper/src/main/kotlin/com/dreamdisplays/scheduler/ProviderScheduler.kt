package com.dreamdisplays.scheduler

import com.dreamdisplays.utils.PlatformUtil.isFolia
import org.jspecify.annotations.NullMarked

/**
 * A provider object that selects the appropriate `AdapterScheduler` implementation
 * based on the server environment (Folia or Bukkit).
 */
@NullMarked
object ProviderScheduler {

    val adapter: AdapterScheduler by lazy {
        if (isFolia) FoliaScheduler else BukkitScheduler
    }
}
