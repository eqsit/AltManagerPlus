package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import com.dummymod.dummy.PlayerSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.c2s.play.AcknowledgeReconfigurationC2SPacket;
import net.minecraft.network.packet.s2c.play.EnterReconfigurationS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.ServerLinks;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void interceptAltManagerHashCommand(String message, CallbackInfo ci) {
        if (message != null && message.startsWith("#")) {
            ci.cancel();
            DummyManager.handleLocalChatCommand(message);
        }
    }

    @Inject(method = "onEnterReconfiguration", at = @At("HEAD"), cancellable = true)
    private void onEnterReconfigurationHead(EnterReconfigurationS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) handler;
        ClientConnection connection = accessor.getConnection();
        if (!DummyManager.isDummyConnection(connection)) {
            return;
        }

        ci.cancel();
        ClientConnectionState state = new ClientConnectionState(
                new ClientChunkLoadProgress(),
                handler.getProfile(),
                client.getTelemetryManager().createWorldSession(false, Duration.ZERO, null),
                handler.getRegistryManager(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES,
                handler.getBrand(),
                client.getCurrentServerEntry(),
                null,
                DummyManager.getCookies(connection),
                new ChatHud.ChatState(List.of(), List.of(), List.of()),
                Collections.emptyMap(),
                ServerLinks.EMPTY,
                Collections.emptyMap(),
                false
        );
        ClientConfigurationNetworkHandler configHandler = new ClientConfigurationNetworkHandler(client, connection, state);
        connection.transitionInbound(ConfigurationStates.S2C, configHandler);
        connection.send(AcknowledgeReconfigurationC2SPacket.INSTANCE);
        connection.transitionOutbound(ConfigurationStates.C2S);
        DummyManager.beginDummyReconfiguration(connection);
    }

    @Inject(method = "onGameJoin", at = @At("HEAD"), cancellable = true)
    private void onGameJoinHead(GameJoinS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            // Must let forceMainThread queue the packet to the Render Thread first!
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        PlayerSession session = DummyManager.getSession(handler);
        if (session != null && !session.main) { DummyManager.onDummyGameJoin(handler, packet); ci.cancel(); }
    }

    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void onGameJoinTail(GameJoinS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        if (DummyManager.getSession(handler) == null && DummyManager.getActiveSession() == DummyManager.mainSession) {
            DummyManager.mainSession.player = client.player;
            DummyManager.mainSession.world = client.world;
            DummyManager.mainSession.interactionManager = client.interactionManager;
            DummyManager.mainSession.networkHandler = handler;
        }
    }

    @Inject(method = "onPlayerRespawn", at = @At("HEAD"), cancellable = true)
    private void onPlayerRespawnHead(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            // Must let forceMainThread queue the packet to the Render Thread first!
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        PlayerSession session = DummyManager.getSession(handler);
        if (session != null && session != DummyManager.getActiveSession()) { DummyManager.onSessionPlayerRespawn(handler, packet); ci.cancel(); }
    }

    @Inject(method = "onPlayerRespawn", at = @At("TAIL"))
    private void onPlayerRespawnTail(PlayerRespawnS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        if (DummyManager.getSession(handler) == null && DummyManager.getActiveSession() == DummyManager.mainSession) {
            DummyManager.mainSession.player = client.player;
            DummyManager.mainSession.world = client.world;
            DummyManager.mainSession.interactionManager = client.interactionManager;
            DummyManager.mainSession.networkHandler = handler;
        }
    }

    @Inject(method = "onDeathMessage", at = @At("HEAD"), cancellable = true)
    private void onDeathMessageHead(DeathMessageS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            // Must let forceMainThread queue the packet to the Render Thread first!
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        PlayerSession session = DummyManager.getSession(handler);
        if (session != null && session != DummyManager.getActiveSession()) {
            ci.cancel();
            if (session.player != null) session.player.requestRespawn();
            if (client.player != null) {
                String msg = packet.message() != null ? packet.message().getString() : "погиб";
                client.player.sendMessage(Text.literal("§c[AltManager+] " + session.displayName() + " погиб (" + msg + ")! Авто-возрождение..."), false);
            }
        }
    }
}
