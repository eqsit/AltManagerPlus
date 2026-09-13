package com.dummymod.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.client.world.ClientWorld;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WorldRenderer.class, priority = 100)
public abstract class WorldRendererMixin {
    @Shadow
    private ClientWorld world;

    /**
     * WorldRenderer.tick(Camera) immediately dereferences this.world via
     * this.world.getTickManager().
     *
     * During dummy/world transitions WorldRenderer.world may temporarily be
     * null while GameRenderer.renderWorld() invokes worldRenderer.tick().
     * Cancel the tick until both renderer and client world references are valid.
     */
    @Inject(
            method = "tick(Lnet/minecraft/client/render/Camera;)V",
            at = @At("HEAD"),
            cancellable = true,
            order = 900
    )
    private void dummymod$skipTickWhileWorldIsTransitioning(
            Camera camera,
            CallbackInfo ci
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.world == null || client.world == null) {
            ci.cancel();
        }
    }

    /**
     * Iris injects at the head of WorldRenderer#render and assumes the renderer's
     * world is non-null. During a world hand-off Minecraft can legitimately pass
     * through a short renderer transition where WorldRenderer.world is null.
     *
     * Cancel the frame before Iris can dereference the null world.
     */
    @Inject(
            method = "render(Lnet/minecraft/client/util/memory/ObjectAllocator;Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V",
            at = @At("HEAD"),
            cancellable = true,
            order = 900
    )
    private void dummymod$skipRenderWhileWorldIsTransitioning(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f basicProjectionMatrix,
            Matrix4f projectionMatrix,
            GpuBufferSlice fogBuffer,
            Vector4f fogColor,
            boolean renderSky,
            CallbackInfo ci
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.world == null || client.world == null) {
            ci.cancel();
        }
    }
}
