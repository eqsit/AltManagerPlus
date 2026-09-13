package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
    @Inject(method = "isCamera", at = @At("HEAD"), cancellable = true)
    private void dummymod$treatSessionPlayersAsLocal(CallbackInfoReturnable<Boolean> cir) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (player == DummyManager.dummySession.player || player == DummyManager.mainSession.player) {
            cir.setReturnValue(true);
        }
    }
}
