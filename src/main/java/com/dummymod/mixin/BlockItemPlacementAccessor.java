package com.dummymod.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses the item rule, including floor/wall variants and its native checks. */
@Mixin(BlockItem.class)
public interface BlockItemPlacementAccessor {
    @Invoker("getPlacementState")
    BlockState dummymod$placementState(ItemPlacementContext context);
}
