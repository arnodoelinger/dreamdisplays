package com.dreamdisplays.platform.client.ui.config

import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.platform.client.Config
import com.dreamdisplays.platform.client.core.modules.ClientAudioModule
import com.dreamdisplays.platform.client.managers.ClientStateManager
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * The mod's settings screen, built with `Cloth Config`. Only ever loaded when `Cloth Config` is
 * installed.
 */
object ClothConfigScreen {
    private val QUALITIES = arrayOf("auto", "360", "480", "720", "1080", "1440", "2160")

    /** Builds the settings screen, returning to [parent] when it closes. */
    fun create(parent: Screen?): Screen {
        val config = ClientStateManager.config
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(text("title"))
        val entries = builder.entryBuilder()
        val general = builder.getOrCreateCategory(text("category.general"))

        general.addEntry(
            entries.startIntSlider(text("new_display_volume"), percent(config.newDisplayVolume), 0, 200)
                .setDefaultValue(percent(Config.DEFAULT_NEW_DISPLAY_VOLUME))
                .setTextGetter { Component.literal("$it%") }
                .setTooltip(text("new_display_volume.tooltip"))
                .setSaveConsumer { config.newDisplayVolume = it / 200.0 }
                .build()
        )
        general.addEntry(
            entries.startSelector(text("new_display_quality"), QUALITIES, qualityChoice(config.newDisplayQuality))
                .setDefaultValue(qualityChoice(Config.DEFAULT_NEW_DISPLAY_QUALITY))
                .setNameProvider { if (it == VideoQuality.AUTO_LABEL) text("value.auto") else Component.literal("${it}p") }
                .setTooltip(text("new_display_quality.tooltip"))
                .setSaveConsumer { config.newDisplayQuality = it }
                .build()
        )
        general.addEntry(
            entries.startIntSlider(text("display_distance"), config.displayDistanceChunks, 0, Config.MAX_DISTANCE_CHUNKS)
                .setDefaultValue(0)
                .setTextGetter { if (it == 0) text("value.auto") else text("value.chunks", it) }
                .setTooltip(text("display_distance.tooltip"))
                .setSaveConsumer { config.displayDistanceChunks = it }
                .build()
        )
        general.addEntry(
            entries.startIntSlider(text("max_active_displays"), config.maxActiveDisplays, 0, Config.MAX_ACTIVE_DISPLAYS)
                .setDefaultValue(0)
                .setTextGetter { if (it == 0) text("value.unlimited") else Component.literal(it.toString()) }
                .setTooltip(text("max_active_displays.tooltip"))
                .setSaveConsumer { config.maxActiveDisplays = it }
                .build()
        )
        general.addEntry(
            entries.startBooleanToggle(text("hardware_decoding"), config.useHwAccel)
                .setDefaultValue(true)
                .setTooltip(text("hardware_decoding.tooltip"))
                .setSaveConsumer { config.useHwAccel = it }
                .build()
        )
        general.addEntry(
            entries.startBooleanToggle(text("mute_unfocused"), config.muteOnAltTab)
                .setDefaultValue(false)
                .setTooltip(text("mute_unfocused.tooltip"))
                .setSaveConsumer { config.muteOnAltTab = it }
                .build()
        )
        general.addEntry(
            entries.startBooleanToggle(text("volume_normalization"), config.audioNormalization)
                .setDefaultValue(true)
                .setTooltip(text("volume_normalization.tooltip"))
                .setSaveConsumer { config.audioNormalization = it }
                .build()
        )

        builder.setSavingRunnable {
            config.save()
            ClientAudioModule.setLoudnessNormalization(config.audioNormalization)
        }
        return builder.build()
    }

    private fun text(key: String, vararg args: Any): Component =
        Component.translatable("dreamdisplays.config.$key", *args)

    private fun percent(volume: Double): Int = (volume * 200).roundToInt().coerceIn(0, 200)

    private fun qualityChoice(stored: String): String {
        val height = VideoQuality.parse(stored).targetHeight ?: return VideoQuality.AUTO_LABEL
        return QUALITIES.drop(1).lastOrNull { it.toInt() <= height } ?: QUALITIES[1]
    }
}
