package com.dummymod.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(ClientPlayNetworkHandler.class)
public interface ClientPlayNetworkHandlerAccessor {
    @Accessor("world")
    void setWorld(ClientWorld world);

    @Accessor("worldProperties")
    void setWorldProperties(ClientWorld.Properties properties);

    @Accessor("worldKeys")
    void setWorldKeys(Set<RegistryKey<World>> keys);

    @Accessor("chunkLoadDistance")
    int getChunkLoadDistance();

    @Accessor("chunkLoadDistance")
    void setChunkLoadDistance(int distance);

    @Accessor("simulationDistance")
    int getSimulationDistance();

    @Accessor("simulationDistance")
    void setSimulationDistance(int distance);

    @Accessor("loaded")
    void setLoaded(boolean loaded);

    @Accessor("secureChatEnforced")
    void setSecureChatEnforced(boolean secureChatEnforced);
}
