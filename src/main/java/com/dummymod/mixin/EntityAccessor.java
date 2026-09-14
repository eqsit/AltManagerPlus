package com.dummymod.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to collision flags used by privacy-safe control diagnostics. */
@Mixin(Entity.class)
public interface EntityAccessor {
    @Accessor("horizontalCollision")
    boolean dummymod$getHorizontalCollision();

    @Accessor("collidedSoftly")
    boolean dummymod$getCollidedSoftly();
}
