package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
public class ClientPlayerEntityMixin {
    private boolean dummymod$sprintingBeforeMovement;

    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void dummymod$captureSprintBeforeMovement(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        dummymod$sprintingBeforeMovement = player.isSprinting();
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void dummymod$logControlAfterMovement(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        DummyManager.onPlayerMovementComplete(player, dummymod$sprintingBeforeMovement);
    }

    @Inject(
            method = "isCamera",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dummymod$treatSessionPlayersAsLocal(
            CallbackInfoReturnable<Boolean> cir
    ) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        if (DummyManager.isSessionPlayer(player)) {
            cir.setReturnValue(true);
        }
    }
}
