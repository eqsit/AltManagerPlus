package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import com.dummymod.dummy.PlayerSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ensures interaction operations (breaking, placing, attacking, syncing held item)
 * executed on a session's ClientPlayerInteractionManager target that specific
 * session's player and world, rather than the global foreground MinecraftClient player/world.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @Shadow
    @Final
    private ClientPlayNetworkHandler networkHandler;

    @Redirect(
            method = "*",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/MinecraftClient;player:Lnet/minecraft/client/network/ClientPlayerEntity;"
            )
    )
    private ClientPlayerEntity dummymod$resolveSessionPlayer(MinecraftClient client) {
        PlayerSession session = DummyManager.getSession(this.networkHandler);
        if (session != null && session.player != null) {
            return session.player;
        }
        return client.player;
    }

    @Redirect(
            method = "*",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/MinecraftClient;world:Lnet/minecraft/client/world/ClientWorld;"
            )
    )
    private ClientWorld dummymod$resolveSessionWorld(MinecraftClient client) {
        PlayerSession session = DummyManager.getSession(this.networkHandler);
        if (session != null && session.world != null) {
            return session.world;
        }
        return client.world;
    }
}
