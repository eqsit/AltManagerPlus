package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
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
        DummyManager.beginDummyReconfiguration(connection);
        ClientConnectionState state = new ClientConnectionState(
                new ClientChunkLoadProgress(),
                handler.getProfile(),
                client.getTelemetryManager().createWorldSession(false, Duration.ZERO, null),
                handler.getRegistryManager(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES,
                handler.getBrand(),
                client.getCurrentServerEntry(),
                null,
                DummyManager.getCookies(),
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
    }

    @Inject(method = "onGameJoin", at = @At("HEAD"), cancellable = true)
    private void onGameJoinHead(GameJoinS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) {
            // Must let forceMainThread queue the packet to the Render Thread first!
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        if (DummyManager.dummySession.networkHandler == handler) {
            DummyManager.onDummyGameJoin(handler, packet);
            ci.cancel();
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
        if (DummyManager.dummySession.networkHandler == handler) {
            DummyManager.onDummyPlayerRespawn(handler, packet);
            ci.cancel();
        } else if (DummyManager.mainSession.networkHandler == handler && DummyManager.isControllingDummy()) {
            DummyManager.onMainPlayerRespawn(handler, packet);
            ci.cancel();
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
        if (DummyManager.dummySession.networkHandler == handler) {
            if (!DummyManager.isControllingDummy()) {
                ci.cancel();
                if (DummyManager.dummySession.player != null) {
                    DummyManager.dummySession.player.requestRespawn();
                }
                if (client.player != null) {
                    String msg = packet.message() != null ? packet.message().getString() : "погиб";
                    client.player.sendMessage(
                            Text.literal("§c[DummyMod] Дамми погиб (" + msg + ")! Авто-возрождение..."),
                            false
                    );
                }
            }
        } else if (DummyManager.mainSession.networkHandler == handler) {
            if (DummyManager.isControllingDummy()) {
                ci.cancel();
                if (DummyManager.mainSession.player != null) {
                    DummyManager.mainSession.player.requestRespawn();
                }
                if (client.player != null) {
                    String msg = packet.message() != null ? packet.message().getString() : "погиб";
                    client.player.sendMessage(
                            Text.literal("§c[DummyMod] Основа погибла (" + msg + ")! Авто-возрождение..."),
                            false
                    );
                }
            }
        }
    }
}
