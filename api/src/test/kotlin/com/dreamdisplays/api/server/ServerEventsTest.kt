package com.dreamdisplays.api.server

import com.dreamdisplays.api.internal.ServerBackend
import com.dreamdisplays.api.internal.ServerHolder
import java.lang.reflect.Proxy
import java.util.function.Consumer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerEventsTest {
    private val listeners = ArrayList<Consumer<ServerDisplayEvent>>()
    private val display = Proxy.newProxyInstance(
        javaClass.classLoader, [ServerDisplay::class.java],
    ) { _, method, _ -> error("unexpected ${method.name}") } as ServerDisplay

    private val backend = object : ServerBackend {
        override val displays: ServerDisplays get() = error("unused")
        override fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }

        override fun registerAddon(addon: Any) = Unit
    }

    @AfterTest
    fun detach() = ServerHolder.detach()

    @Test
    fun `typed listener hears only its event type and stops when closed`() {
        ServerHolder.attach(backend)
        val urls = mutableListOf<String>()
        val handle = DreamDisplaysServer.events.on<ServerDisplayEvent.UrlChanged> { urls += it.url }

        listeners.toList().forEach {
            it.accept(ServerDisplayEvent.Created(display))
            it.accept(ServerDisplayEvent.UrlChanged(display, "a"))
        }
        handle.close()
        listeners.toList().forEach { it.accept(ServerDisplayEvent.UrlChanged(display, "b")) }

        assertEquals("a", urls.single())
    }
}
