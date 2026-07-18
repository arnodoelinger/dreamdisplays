package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.runtime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DefaultDreamDisplaysRuntimeTest {
    private interface TestService {
        val name: String
    }

    private data class TestServiceImpl(override val name: String) : TestService

    @Test
    fun serviceRegistrySupportsClassAndExplicitKeys() {
        val registry = DefaultServiceRegistry()
        val defaultService = TestServiceImpl("default")
        val alternateService = TestServiceImpl("alternate")
        val alternateKey = serviceKey<TestService>("test:alternate")

        registry.register<TestService>(defaultService)
        registry.register(alternateKey, alternateService)

        assertSame(defaultService, registry.get<TestService>())
        assertSame(alternateService, registry.get(alternateKey))
    }

    @Test
    fun runtimeInstallsDependenciesBeforeDependentsAndUninstallsInReverseOrder() {
        val runtime = DefaultDreamDisplaysRuntime()
        val events = mutableListOf<String>()

        runtime.registerModule(recordingModule("test:feature", dependencies = listOf("test:base"), events))
        runtime.registerModule(recordingModule("test:base", events = events))

        runtime.start()
        runtime.stop()

        assertEquals(
            listOf(
                "install:test:base",
                "install:test:feature",
                "uninstall:test:feature",
                "uninstall:test:base",
            ),
            events,
        )
    }

    @Test
    fun runtimeRejectsMissingDependencies() {
        val runtime = DefaultDreamDisplaysRuntime()
        runtime.registerModule(
            recordingModule(
                "test:feature",
                dependencies = listOf("test:missing"),
                events = mutableListOf()
            )
        )

        val error = runCatching { runtime.start() }.exceptionOrNull()

        assertEquals("Module 'test:feature' depends on unregistered module 'test:missing'.", error?.message)
    }

    @Test
    fun runtimeUninstallsAlreadyInstalledModulesWhenStartFails() {
        val runtime = DefaultDreamDisplaysRuntime()
        val events = mutableListOf<String>()

        runtime.registerModule(recordingModule("test:base", events = events))
        runtime.registerModule(failingModule("test:feature", dependencies = listOf("test:base"), events))

        val error = runCatching { runtime.start() }.exceptionOrNull()

        assertEquals("boom:test:feature", error?.message)
        assertEquals(
            listOf(
                "install:test:base",
                "install:test:feature",
                "uninstall:test:base",
            ),
            events,
        )
        assertEquals(emptySet(), runtime.installedModuleIds)
    }

    private fun recordingModule(
        id: String,
        dependencies: List<String> = emptyList(),
        events: MutableList<String>,
    ): DreamDisplaysModule = object : DreamDisplaysModule {
        override val id: String = id
        override val dependencies: List<String> = dependencies

        override fun install(context: ModuleContext) {
            events += "install:$id"
        }

        override fun uninstall(context: ModuleContext) {
            events += "uninstall:$id"
        }
    }

    private fun failingModule(
        id: String,
        dependencies: List<String> = emptyList(),
        events: MutableList<String>,
    ): DreamDisplaysModule = object : DreamDisplaysModule {
        override val id: String = id
        override val dependencies: List<String> = dependencies

        override fun install(context: ModuleContext) {
            events += "install:$id"
            error("boom:$id")
        }
    }
}
