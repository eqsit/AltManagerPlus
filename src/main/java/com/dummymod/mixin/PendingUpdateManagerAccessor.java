package com.dummymod.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.network.PendingUpdateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PendingUpdateManager.class)
public interface PendingUpdateManagerAccessor {
    @Accessor("blockPosToPendingUpdate")
    Long2ObjectOpenHashMap<?> dummymod$blocks();
}
