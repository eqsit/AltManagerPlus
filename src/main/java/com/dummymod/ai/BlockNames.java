package com.dummymod.ai;

import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Resolve older model vocabulary against the actual game registry. */
final class BlockNames {
    private BlockNames(){}
    static Identifier resolve(String name) {
        Identifier id=Identifier.tryParse(name);
        if(id==null || Registries.BLOCK.containsId(id))return id;
        if(id.equals(Identifier.ofVanilla("chain"))) {
            Identifier renamed=Identifier.ofVanilla("iron_chain");
            if(Registries.BLOCK.containsId(renamed))return renamed;
        }
        return id;
    }
}
