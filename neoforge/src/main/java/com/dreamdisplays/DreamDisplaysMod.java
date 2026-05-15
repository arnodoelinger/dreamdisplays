package com.dreamdisplays;

import com.dreamdisplays.net.Packets.*;
import com.dreamdisplays.render.ScreenRenderer;
import com.dreamdisplays.display.DisplayManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jspecify.annotations.NullMarked;

@NullMarked
@Mod(value = DreamDisplaysMod.MOD_ID, dist = Dist.CLIENT)
public class DreamDisplaysMod implements com.dreamdisplays.Mod {

    public static final String MOD_ID = "dreamdisplays";

    public DreamDisplaysMod(IEventBus modEventBus) {
        Initializer.onModInit(this);
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.register(this);
    }

    public void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event
                .registrar(MOD_ID)
                .optional()
                .versioned("1");
        registrar.playBidirectional(
                Delete.PACKET_ID,
                Delete.PACKET_CODEC,
                (serverPayload, ctx) -> {
                },
                (clientPayload, ctx) -> Initializer.onDeletePacket(clientPayload)
        );
        registrar.playToClient(
                Info.PACKET_ID,
                Info.PACKET_CODEC,
                (payload, ctx) -> Initializer.onDisplayInfoPacket(payload)
        );

        registrar.playToClient(
                Premium.PACKET_ID,
                Premium.PACKET_CODEC,
                (payload, ctx) -> Initializer.onPremiumPacket(payload)
        );

        registrar.playToClient(
                DisplayEnabled.PACKET_ID,
                DisplayEnabled.PACKET_CODEC,
                (payload, ctx) -> Initializer.onDisplayEnabledPacket(payload)
        );

        registrar.playToClient(
                ReportEnabled.PACKET_ID,
                ReportEnabled.PACKET_CODEC,
                (payload, ctx) -> Initializer.onReportEnabledPacket(payload)
        );

        registrar.playToClient(
                ClearCache.PACKET_ID,
                ClearCache.PACKET_CODEC,
                (payload, ctx) -> Initializer.onClearCachePacket(payload)
        );

        registrar.playBidirectional(
                Sync.PACKET_ID,
                Sync.PACKET_CODEC,
                (serverPayload, ctx) -> {
                },
                (clientPayload, ctx) -> Initializer.onSyncPacket(clientPayload)
        );

        registrar.playToServer(
                RequestSync.PACKET_ID,
                RequestSync.PACKET_CODEC,
                (p, c) -> {
                }
        );
        registrar.playToServer(
                Report.PACKET_ID,
                Report.PACKET_CODEC,
                (p, c) -> {
                }
        );
        registrar.playToServer(
                Version.PACKET_ID,
                Version.PACKET_CODEC,
                (p, c) -> {
                }
        );
        registrar.playToServer(
                SetVideo.PACKET_ID,
                SetVideo.PACKET_CODEC,
                (p, c) -> {
                }
        );
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Initializer.onEndTick(Minecraft.getInstance());
    }

    @SubscribeEvent
    public void onClientStop(ClientPlayerNetworkEvent.LoggingOut event) {
        DisplayManager.saveAllScreens();
        DisplayManager.unloadAll();
    }

    @SubscribeEvent
    public void onClientStopping(ClientPlayerNetworkEvent.LoggingOut event) {
        Initializer.onStop();
    }

    @SubscribeEvent
    public void onRenderLevelAfterEntities(
            RenderLevelStageEvent.AfterEntities event
    ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        PoseStack poseStack = event.getPoseStack();
        Camera camera = mc.gameRenderer.getMainCamera();
        ScreenRenderer.render(poseStack, camera);
    }

    @SubscribeEvent
    public void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            String serverId = mc.hasSingleplayerServer()
                    ? "singleplayer"
                    : (mc.getCurrentServer() != null
                       ? mc.getCurrentServer().ip
                       : "unknown");
            DisplayManager.loadScreensForServer(serverId);
        }
    }

    @Override
    public void sendPacket(CustomPacketPayload packet) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(packet);
        }
    }
}
