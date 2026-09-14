package com.dummymod.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {
    @Accessor("lastPlayerInput")
    void dummymod$setLastPlayerInput(PlayerInput input);

    @Accessor("lastSprinting")
    void dummymod$setLastSprinting(boolean sprinting);

    @Accessor("ticksLeftToDoubleTapSprint")
    void dummymod$setTicksLeftToDoubleTapSprint(int ticks);
}
