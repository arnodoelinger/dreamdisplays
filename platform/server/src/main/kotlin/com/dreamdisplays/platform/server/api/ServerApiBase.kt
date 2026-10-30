package com.dreamdisplays.platform.server.api

import com.dreamdisplays.api.CommandResult
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.api.display.data.DisplayCustomDataLimits
import com.dreamdisplays.api.display.data.MutableDisplayCustomData
import com.dreamdisplays.api.display.model.property.DisplayFacing
import com.dreamdisplays.api.internal.ServerBackend
import com.dreamdisplays.api.internal.ServerHolder
import com.dreamdisplays.api.playback.model.DisplayAccess
import com.dreamdisplays.api.playback.model.PlaybackMode
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplays.api.server.ServerEvents
import com.dreamdisplays.api.server.ServerDisplays
import com.dreamdisplays.core.addon.AddonManager
import com.dreamdisplays.core.addon.ServerAddonBinder
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.playback.TimelineManager
import com.dreamdisplays.platform.server.utils.net.CustomMediaGate
import com.dreamdisplays.shared.policy.CustomMediaPolicy
import com.dreamdisplays.shared.policy.MediaUrlPolicy
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Where the rest of the server reaches the public API without knowing which platform it runs on.
 *
 * Shared code calls `ServerApiHost.current?.removed(...)`; there is no `@PaperOnly` or loader-only
 * type in the call, so it is safe on every platform.
 */
internal object ServerApiHost {
    /** The running backend, or null before the plugin/mod has finished loading. */
    @Volatile
    var current: BaseServerBackend? = null
}

/** Event bus, addon host and the display wrapping shared by the `Paper` and the `Fabric` / `NeoForge` backends. */
abstract class BaseServerBackend : ServerBackend {
    private val logger = LoggerFactory.getLogger(BaseServerBackend::class.java)
    private val listeners = CopyOnWriteArrayList<Consumer<ServerDisplayEvent>>()
    private val addons = AddonManager(AddonSide.SERVER, [ServerAddonBinder { listener -> on(listener) }])

    internal abstract fun wrap(data: DisplayData): ServerDisplay

    override fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun registerAddon(addon: Any) = addons.register(addon)

    /**
     * Makes the API available and enables registered addons.
     *
     * Called once the plugin / mod has finished loading.
     */
    fun start() {
        ServerApiHost.current = this
        ServerHolder.attach(this)
        addons.start()
    }

    /**
     * Disables addons and withdraws the API.
     *
     * Called when the plugin / mod shuts down.
     */
    fun stop() {
        addons.stop()
        ServerHolder.detach()
        ServerApiHost.current = null
    }

    internal fun created(data: DisplayData) = publish(data) { ServerDisplayEvent.Created(wrap(data)) }
    internal fun removed(data: DisplayData) = publish(data) { ServerDisplayEvent.Removed(wrap(data)) }
    internal fun urlChanged(data: DisplayData) = publish(data) { ServerDisplayEvent.UrlChanged(wrap(data), data.url) }
    internal fun moved(data: DisplayData) = publish(data) { ServerDisplayEvent.Moved(wrap(data)) }
    internal fun dataChanged(data: DisplayData, keys: Set<String>) =
        publish(data) { ServerDisplayEvent.DataChanged(wrap(data), keys) }

    private inline fun publish(data: DisplayData, event: () -> ServerDisplayEvent) {
        if (listeners.isEmpty() || data.virtual) return
        val built = event()
        listeners.forEach { listener ->
            runCatching { listener.accept(built) }.onFailure { logger.error("A display event subscriber threw.", it) }
        }
    }
}

/** Everything a [ServerDisplay] does that does not depend on the platform; the platform fills in the rest. */
abstract class ServerDisplayBase internal constructor(
    protected val data: DisplayData,
    private val backend: BaseServerBackend,
) : ServerDisplay {
    override val id: UUID get() = data.id
    override val ownerId: UUID get() = data.ownerId
    override val width: Int get() = data.width
    override val height: Int get() = data.height
    override val name: String? get() = data.name
    override val url: String get() = data.url
    override val lang: String get() = data.lang
    override val mode: PlaybackMode get() = data.mode
    override val access: DisplayAccess get() = data.access

    protected abstract fun customMediaSettings(): CustomMediaPolicy.Settings
    protected abstract fun save()
    protected abstract fun broadcast()
    protected abstract fun doDelete()

    protected abstract fun doMove(
        world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing,
    ): CommandResult

    override fun setUrl(url: String, lang: String): CommandResult {
        if (!isAlive) return CommandResult.STALE
        if (!MediaUrlPolicy.isAllowed(url)) return CommandResult.REFUSED
        if (CustomMediaGate.refusalKey(url, customMediaSettings(), hasPermission = true) != null) {
            return CommandResult.REFUSED
        }
        data.url = url
        data.lang = MediaUrlPolicy.sanitizeLang(lang)
        save()
        broadcast()
        TimelineManager.onVideoChanged(data)
        backend.urlChanged(data)
        return CommandResult.OK
    }

    override fun setAccess(access: DisplayAccess): CommandResult {
        if (!isAlive) return CommandResult.STALE
        data.access = access
        save()
        broadcast()
        return CommandResult.OK
    }

    override fun delete(): CommandResult {
        if (!isAlive) return CommandResult.STALE
        doDelete()
        return CommandResult.OK
    }

    override fun moveTo(
        world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing,
    ): CommandResult {
        if (!isAlive) return CommandResult.STALE
        val result = doMove(world, x1, y1, z1, x2, y2, z2, facing)
        if (result == CommandResult.OK) backend.moved(data)
        return result
    }

    override fun moveTo(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, facing: DisplayFacing): CommandResult =
        moveTo(world, x1, y1, z1, x2, y2, z2, facing)

    override val events: ServerEvents = object : ServerEvents {
        override fun <T : ServerDisplayEvent> on(type: Class<T>, listener: Consumer<T>): AutoCloseable {
            val closed = AtomicBoolean(false)
            lateinit var upstream: AutoCloseable
            upstream = backend.on { event ->
                if (closed.get() || event.display.id != id) return@on
                if (type.isInstance(event)) listener.accept(type.cast(event))
                if (event is ServerDisplayEvent.Removed && closed.compareAndSet(false, true)) upstream.close()
            }
            return AutoCloseable { if (closed.compareAndSet(false, true)) upstream.close() }
        }
    }

    override val customData: MutableDisplayCustomData = object : MutableDisplayCustomData {
        override val keys: Set<String> get() = data.customData.keys.toSet()
        override val isEmpty: Boolean get() = data.customData.isEmpty()
        override fun get(key: String): ByteArray? = data.customData[key]?.copyOf()

        override fun set(key: String, value: ByteArray): CommandResult {
            if (!isAlive) return CommandResult.STALE
            if (!DisplayCustomDataLimits.accepts(data.customData, key, value)) return CommandResult.REFUSED
            val copy = value.copyOf()
            if (data.customData[key]?.contentEquals(copy) == true) return CommandResult.OK
            data.customData[key] = copy
            changed(setOf(key))
            return CommandResult.OK
        }

        override fun remove(key: String): CommandResult {
            if (!isAlive) return CommandResult.STALE
            if (data.customData.remove(key) != null) changed(setOf(key))
            return CommandResult.OK
        }

        override fun clear(): CommandResult {
            if (!isAlive) return CommandResult.STALE
            val keys = data.customData.keys.toSet()
            if (keys.isNotEmpty()) {
                data.customData.clear()
                changed(keys)
            }
            return CommandResult.OK
        }

        private fun changed(keys: Set<String>) {
            save()
            broadcast()
            backend.dataChanged(data, keys)
        }
    }

    override fun equals(other: Any?): Boolean = other is ServerDisplayBase && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "ServerDisplay($id)"
}

/** Lazy view over the displays of one platform; [filters] are applied in order on every iteration. */
abstract class ServerDisplaysBase<D : DisplayData> internal constructor(
    private val filters: List<(D) -> Boolean>,
) : ServerDisplays {
    protected abstract fun all(): List<D>

    protected abstract fun wrap(data: D): ServerDisplay

    protected abstract fun worldOf(data: D): String

    protected abstract fun centerOf(data: D): Triple<Double, Double, Double>

    protected abstract fun withFilters(filters: List<(D) -> Boolean>): ServerDisplays

    private fun matching(): List<D> = all().filter { d -> !d.virtual && filters.all { it(d) } }

    override val size: Int get() = matching().size
    override fun iterator(): Iterator<ServerDisplay> = matching().map { wrap(it) }.iterator()

    override fun get(id: UUID): ServerDisplay? = matching().firstOrNull { it.id == id }?.let { wrap(it) }

    override fun byName(name: String): ServerDisplay? =
        matching().firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { wrap(it) }

    override fun ownedBy(owner: UUID): ServerDisplays = withFilters(filters + { d -> d.ownerId == owner })

    override fun near(world: String, x: Double, y: Double, z: Double, radius: Double): ServerDisplays {
        val limit = radius * radius
        return withFilters(
            filters + { d ->
                val (cx, cy, cz) = centerOf(d)
                val dx = cx - x
                val dy = cy - y
                val dz = cz - z
                worldOf(d) == world && dx * dx + dy * dy + dz * dz <= limit
            },
        )
    }

    override fun filter(predicate: Predicate<ServerDisplay>): ServerDisplays =
        withFilters(filters + { d -> predicate.test(wrap(d)) })
}
