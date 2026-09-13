package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public class ClientCommonNetworkHandlerMixin {

    @Inject(
            method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dummy$guardPacketDuringReconfiguration(Packet<?> packet, CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof ClientPlayNetworkHandler)) {
            return;
        }

        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) this;
        ClientConnection connection = accessor.getConnection();

        if (connection == null) {
            return;
        }

        if (!DummyManager.isDummyConnection(connection)) {
            return;
        }

        if (!connection.isOpen()
                || DummyManager.isDummyReconfiguring()
                || !(connection.getPacketListener() instanceof ClientPlayNetworkHandler)) {
            ci.cancel();
        }
    }

    @Inject(method = "onResourcePackSend", at = @At("HEAD"))
    private void onResourcePackSendHead(ResourcePackSendS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) return;

        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) this;
        ClientConnection connection = accessor.getConnection();
        if (DummyManager.isDummyConnection(connection)) {
            client.getServerResourcePackProvider().init(
                    connection,
                    ServerResourcePackManager.AcceptanceStatus.ALLOWED
            );
        }
    }

    @Inject(method = "onResourcePackSend", at = @At("RETURN"))
    private void onResourcePackSendReturn(ResourcePackSendS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) return;

        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) this;
        ClientConnection connection = accessor.getConnection();
        if (DummyManager.isDummyConnection(connection)) {
            client.getServerResourcePackProvider()
                    .getPackLoadFuture(packet.id())
                    .whenComplete((ignored, error) -> client.execute(DummyManager::restoreMainResourcePackConnection));
        }
    }

    @Inject(method = "onServerTransfer", at = @At("HEAD"), cancellable = true)
    private void onServerTransferHead(ServerTransferS2CPacket packet, CallbackInfo ci) {
        ClientCommonNetworkHandler handler = (ClientCommonNetworkHandler) (Object) this;
        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) handler;
        if (DummyManager.isDummyConnection(accessor.getConnection())) {
            ci.cancel();
            DummyManager.transfer(packet.host(), packet.port());
        }
    }

    @Inject(method = "onDisconnected", at = @At("HEAD"), cancellable = true)
    private void onDisconnectedHead(DisconnectionInfo info, CallbackInfo ci) {
        ClientCommonNetworkHandler handler = (ClientCommonNetworkHandler) (Object) this;
        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) handler;
        if (DummyManager.isDummyConnection(accessor.getConnection())) {
            ci.cancel(); // Prevent disconnecting the main Minecraft client
            if (DummyManager.consumeExpectedDisconnect(accessor.getConnection())) {
                return;
            }
            DummyManager.onConnectionClosed(accessor.getConnection());
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                String reason = info.reason() != null ? info.reason().getString() : "отключен";
                mc.player.sendMessage(Text.literal("§6[DummyMod] Дамми отключен: §7" + reason), false);
            }
        } else if (DummyManager.mainSession.networkHandler == handler) {
            DummyManager.disconnect();
            DummyManager.mainSession.clear();
        }
    }
}
