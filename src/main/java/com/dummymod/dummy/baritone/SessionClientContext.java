package com.dummymod.dummy.baritone;

import baritone.api.utils.IBaritoneClientContext;
import com.dummymod.dummy.PlayerSession;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;

/**
 * Stable, non-mutating Baritone view of one AltManager session.
 *
 * <p>This class deliberately never installs the session into MinecraftClient.
 * Baritone reads the session fields directly, so background pathing cannot
 * replace the visible player/world/controller/camera.</p>
 */
public final class SessionClientContext implements IBaritoneClientContext {
    private final PlayerSession session;
    private final MinecraftClient client;

    public SessionClientContext(PlayerSession session) {
        this.session = session;
        this.client = MinecraftClient.getInstance();
    }

    public PlayerSession session() {
        return session;
    }

    @Override
    public MinecraftClient minecraft() {
        return client;
    }

    @Override
    public ClientPlayerEntity player() {
        return session.player;
    }

    @Override
    public ClientWorld world() {
        return session.world;
    }

    @Override
    public ClientPlayerInteractionManager interactionManager() {
        return session.interactionManager;
    }

    @Override
    public ClientPlayNetworkHandler networkHandler() {
        return session.networkHandler;
    }

    @Override
    public Entity cameraEntity() {
        // Baritone path calculations should follow the owning account, not the
        // visible Minecraft camera when this session is in the background.
        return session.player;
    }

    @Override
    public boolean keepBaritoneInputWhenIdle() {
        return false;
    }
}
