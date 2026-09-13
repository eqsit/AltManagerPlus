package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConfigurationNetworkHandler.class)
public class ClientConfigurationNetworkHandlerMixin {
    @Inject(method = "onDisconnected", at = @At("HEAD"), cancellable = true)
    private void onDisconnectedHead(DisconnectionInfo info, CallbackInfo ci) {
        ClientCommonNetworkHandlerAccessor accessor = (ClientCommonNetworkHandlerAccessor) this;
        ClientConnection connection = accessor.getConnection();
        if (!DummyManager.isDummyConnection(connection)) return;

        ci.cancel();
        if (DummyManager.consumeExpectedDisconnect(connection)) return;

        DummyManager.onConnectionClosed(connection);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            String reason = info.reason() != null ? info.reason().getString() : "отключен";
            client.player.sendMessage(Text.literal("§6[DummyMod] Дамми отключен: §7" + reason), false);
        }
    }
}
