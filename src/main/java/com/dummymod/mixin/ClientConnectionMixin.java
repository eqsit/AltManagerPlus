package com.dummymod.mixin;

import com.dummymod.dummy.DummyManager;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.network.packet.PlayPackets;
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

    private static final Set<PacketType<?>> DUMMYMOD_PLAY_PACKET_TYPES =
            dummymod();

    @Inject(
            method = "transitionInbound",
            at = @At("HEAD")
    )
    private <T extends PacketListener> void onTransitionInbound(
            NetworkState<T> state,
            T listener,
            CallbackInfo ci
    ) {
        ClientConnection connection =
                (ClientConnection) (Object) this;

        if (listener instanceof ClientPlayNetworkHandler playHandler) {
            DummyManager.registerPlayHandler(
                    connection,
                    playHandler
            );
        }
    }

    @Inject(
            method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dummymod(
            Packet<?> packet,
            ChannelFutureListener listener,
            boolean flush,
            CallbackInfo ci
    ) {
        ClientConnection connection =
                (ClientConnection) (Object) this;

        if (!DummyManager.isDummyConnection(connection)) {
            return;
        }

        if (!connection.isOpen()) {
            ci.cancel();
            return;
        }

        if ((DummyManager.isDummyReconfiguring()
                || !(connection.getPacketListener()
                        instanceof ClientPlayNetworkHandler))
                && dummymod(packet)) {
            ci.cancel();
        }
    }

    private static boolean dummymod(Packet<?> packet) {
        return packet != null
                && DUMMYMOD_PLAY_PACKET_TYPES.contains(
                        packet.getPacketType()
                );
    }

    private static Set<PacketType<?>> dummymod() {
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
