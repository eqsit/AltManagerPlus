package com.dummymod.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleManager.class)
public class ParticleManagerMixin {
    @Shadow
    protected ClientWorld world;

    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void dummymod$guardAddParticle(Particle particle, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.world == null || client == null || client.world == null || this.world != client.world) {
            ci.cancel();
        }
    }
}
