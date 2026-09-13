package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.state.NetworkState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
    @Inject(method = "transitionInbound", at = @At("HEAD"))
    private <T extends PacketListener> void onTransitionInbound(NetworkState<T> state, T listener, CallbackInfo ci) {
        ClientConnection connection = (ClientConnection) (Object) this;
        if (listener instanceof ClientPlayNetworkHandler playHandler) {
            DummyManager.registerPlayHandler(connection, playHandler);
        }
    }
}
