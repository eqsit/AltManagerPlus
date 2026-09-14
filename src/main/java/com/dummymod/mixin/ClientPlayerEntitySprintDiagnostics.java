package com.dummymod.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Read-only access to vanilla sprint decision predicates for privacy-safe diagnostics.
 * No state is mutated by these invokers.
 */
@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntitySprintDiagnostics {
    @Invoker("canStartSprinting")
    boolean dummymod$canStartSprinting();

    @Invoker("canSprint")
    boolean dummymod$canSprint(boolean allowTouchingWater);

    @Invoker("shouldStopSprinting")
    boolean dummymod$shouldStopSprinting();

    @Invoker("isBlockedFromSprinting")
    boolean dummymod$isBlockedFromSprinting();

    @Invoker("hasMovementInput")
    boolean dummymod$hasMovementInput();

    @Invoker("canVehicleSprint")
    boolean dummymod$canVehicleSprint(Entity vehicle);
}
