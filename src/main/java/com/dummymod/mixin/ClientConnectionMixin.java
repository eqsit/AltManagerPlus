package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.network.packet.PlayPackets;
import net.minecraft.network.packet.c2s.play.AcknowledgeReconfigurationC2SPacket;
import net.minecraft.network.state.NetworkState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    private static final Set<PacketType<?>> PLAY_PACKET_TYPES = collectPlayPacketTypes();

    @Inject(
            method = "transitionInbound",
            at = @At("HEAD")
    )
    private <T extends PacketListener> void onTransitionInbound(
            NetworkState<T> state,
            T listener,
            CallbackInfo ci
    ) {
        ClientConnection connection = (ClientConnection) (Object) this;

        if (listener instanceof ClientPlayNetworkHandler playHandler) {
            DummyManager.registerPlayHandler(connection, playHandler);
        }
    }

    @Inject(
            method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void guardDirectPacketSend(
            Packet<?> packet,
            ChannelFutureListener listener,
            boolean flush,
            CallbackInfo ci
    ) {
        ClientConnection connection = (ClientConnection) (Object) this;

        // AcknowledgeReconfigurationC2SPacket must always pass through before outbound transition
        if (packet instanceof AcknowledgeReconfigurationC2SPacket) {
            return;
        }

        if (!connection.isOpen()) {
            ci.cancel();
            return;
        }

        // If the packet belongs to the PLAY protocol, make sure the connection is actually in PLAY state
        if (isPlayPacket(packet)) {
            if (DummyManager.isDummyConnection(connection)) {
                if (DummyManager.isDummyReconfiguring() || !(connection.getPacketListener() instanceof ClientPlayNetworkHandler)) {
                    ci.cancel();
                }
            } else {
                if (!(connection.getPacketListener() instanceof ClientPlayNetworkHandler)) {
                    ci.cancel();
                }
            }
        }
    }

    private static boolean isPlayPacket(Packet<?> packet) {
        return packet != null && PLAY_PACKET_TYPES.contains(packet.getPacketType());
    }

    private static Set<PacketType<?>> collectPlayPacketTypes() {
        Set<PacketType<?>> packetTypes = new HashSet<>();

        try {
            for (Field field : PlayPackets.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                if (!PacketType.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                if (!field.canAccess(null)) {
                    field.setAccessible(true);
                }

                Object value = field.get(null);

                if (value instanceof PacketType<?> packetType) {
                    packetTypes.add(packetType);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }

        return Set.copyOf(packetTypes);
    }
}
