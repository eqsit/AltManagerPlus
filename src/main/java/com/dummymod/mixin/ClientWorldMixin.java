package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prediction and collision rollback must use the player owning this world. */
@Mixin(ClientWorld.class)
public class ClientWorldMixin {
    @Shadow @Final private ClientPlayNetworkHandler networkHandler;
    @Redirect(method={"setBlockState","processPendingUpdate"},at=@At(value="FIELD",target="Lnet/minecraft/client/MinecraftClient;player:Lnet/minecraft/client/network/ClientPlayerEntity;"))
    private ClientPlayerEntity dummymod$predictionPlayer(MinecraftClient client) {
        var owner=DummyManager.getSession(networkHandler);
        return owner!=null && owner.player!=null && owner.world==(Object)this?owner.player:client.player;
    }
}
