package com.dummymod.ai;

import com.dummymod.dummy.PlayerSession;
import net.minecraft.block.BlockState;
import net.minecraft.screen.slot.SlotActionType;

final class Tools {
    private Tools(){}
    static void select(PlayerSession session,BlockState state) {
        var inventory=session.player.getInventory();int best=inventory.getSelectedSlot();
        float speed=inventory.getStack(best).getMiningSpeedMultiplier(state);
        for(int i=0;i<36;i++) {
            var stack=inventory.getStack(i);if(stack.isEmpty())continue;
            float candidate=stack.getMiningSpeedMultiplier(state);
            if(candidate>speed){speed=candidate;best=i;}
        }
        if(best>=9) {
            session.interactionManager.clickSlot(session.player.playerScreenHandler.syncId,best,7,SlotActionType.SWAP,session.player);
            best=7;
        }
        inventory.setSelectedSlot(best);
    }
}
