package com.dummymod.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Particle.class)
public abstract class ParticleMixin {
    @Shadow
    @Final
    protected ClientWorld world;

    @Shadow
    public abstract void markDead();

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void dummymod$guardParticleTick(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.world == null || client == null || client.world == null || this.world != client.world) {
            this.markDead();
            ci.cancel();
        }
    }

    @Inject(method = "move(DDD)V", at = @At("HEAD"), cancellable = true)
    private void dummymod$guardParticleMove(double dx, double dy, double dz, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.world == null || client == null || client.world == null || this.world != client.world) {
            this.markDead();
            ci.cancel();
        }
    }
}
