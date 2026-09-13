package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.network.PacketApplyBatcher$Entry")
public class PacketApplyBatcherEntryMixin {

    @Shadow
    @Final
    private PacketListener listener;

    @Redirect(
            method = "apply",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/packet/Packet;apply(Lnet/minecraft/network/listener/PacketListener;)V"
            )
    )
    private void applyWithSessionContext(Packet<PacketListener> packet, PacketListener listener) {
        DummyManager.beforePacketApply(this.listener);
        try {
            packet.apply(listener);
        } finally {
            DummyManager.afterPacketApply(this.listener);
        }
    }
}
