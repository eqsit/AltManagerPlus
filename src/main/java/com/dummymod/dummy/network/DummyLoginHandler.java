package com.dummymod.dummy.network;

import com.dummymod.dummy.DummyManager;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientDynamicRegistryType;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.ClientLoginPacketListener;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.login.EnterConfigurationC2SPacket;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.s2c.common.CookieRequestS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginCompressionS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginHelloS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginSuccessS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.ServerLinks;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

public class DummyLoginHandler implements ClientLoginPacketListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("DummyMod-Login");

    private final ClientConnection connection;

    public DummyLoginHandler(
            Object dummyClient,
            ClientConnection connection
    ) {
        this.connection = connection;
    }

    @Override
    public NetworkSide getSide() {
        return NetworkSide.CLIENTBOUND;
    }

    @Override
    public NetworkPhase getPhase() {
        return NetworkPhase.LOGIN;
    }

    @Override
    public boolean isConnectionOpen() {
        return this.connection.isOpen();
    }

    @Override
    public void onHello(LoginHelloS2CPacket packet) {
        LOGGER.warn(
                "[DummyMod-Login] Received LoginHelloS2CPacket "
                        + "(server requires online-mode encryption). "
                        + "Offline dummy cannot authenticate!"
        );

        MinecraftClient client =
                MinecraftClient.getInstance();

        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal(
                                "§c[DummyMod] Вход отклонен: "
                                        + "сервер требует online-mode авторизацию."
                        ),
                        false
                );
            }

            DummyManager.disconnect();
        });
    }

    @Override
    public void onSuccess(LoginSuccessS2CPacket packet) {
        LOGGER.info(
                "[DummyMod-Login] Login success! Name: '{}', UUID: {}",
                packet.profile().name(),
                packet.profile().id()
        );

        LOGGER.info(
                "[DummyMod-Login] Switching to the vanilla CONFIGURATION handler"
        );

        MinecraftClient client =
                MinecraftClient.getInstance();

        ClientConnectionState state =
                new ClientConnectionState(
                        new ClientChunkLoadProgress(),
                        packet.profile(),
                        client.getTelemetryManager()
                                .createWorldSession(
                                        false,
                                        Duration.ZERO,
                                        null
                                ),
                        ClientDynamicRegistryType
                                .createCombinedDynamicRegistries()
                                .getCombinedRegistryManager(),
                        FeatureFlags.DEFAULT_ENABLED_FEATURES,
                        null,
                        client.getCurrentServerEntry(),
                        null,
                        DummyManager.getCookies(),
                        new ChatHud.ChatState(
                                List.of(),
                                List.of(),
                                List.of()
                        ),
                        Collections.emptyMap(),
                        ServerLinks.EMPTY,
                        Collections.emptyMap(),
                        false
                );

        ClientConfigurationNetworkHandler configHandler =
                new ClientConfigurationNetworkHandler(
                        client,
                        this.connection,
                        state
                );

        this.connection.transitionInbound(
                ConfigurationStates.S2C,
                configHandler
        );

        this.connection.send(
                EnterConfigurationC2SPacket.INSTANCE
        );

        this.connection.transitionOutbound(
                ConfigurationStates.C2S
        );

        this.connection.send(
                new CustomPayloadC2SPacket(
                        new BrandCustomPayload(
                                ClientBrandRetriever.getClientModName()
                        )
                )
        );

        this.connection.send(
                new ClientOptionsC2SPacket(
                        client.options.getSyncedOptions()
                )
        );
    }

    @Override
    public void onDisconnect(
            LoginDisconnectS2CPacket packet
    ) {
        String reason =
                packet.reason() != null
                        ? packet.reason().getString()
                        : "Disconnected during login";

        LOGGER.warn(
                "[DummyMod-Login] Server rejected login: {}",
                reason
        );

        MinecraftClient client =
                MinecraftClient.getInstance();

        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal(
                                "§c[DummyMod] Вход отклонен сервером: "
                                        + reason
                        ),
                        false
                );
            }

            DummyManager.disconnect();
        });
    }

    @Override
    public void onCompression(
            LoginCompressionS2CPacket packet
    ) {
        LOGGER.info(
                "[DummyMod-Login] Setting compression threshold: {} bytes",
                packet.getCompressionThreshold()
        );

        if (!this.connection.isLocal()) {
            this.connection.setCompressionThreshold(
                    packet.getCompressionThreshold(),
                    false
            );
        }
    }

    @Override
    public void onQueryRequest(
            LoginQueryRequestS2CPacket packet
    ) {
        LOGGER.info(
                "[DummyMod-Login] Responding to LoginQueryRequest (id: {})",
                packet.queryId()
        );

        this.connection.send(
                new LoginQueryResponseC2SPacket(
                        packet.queryId(),
                        null
                )
        );
    }

    @Override
    public void onCookieRequest(
            CookieRequestS2CPacket packet
    ) {
        LOGGER.info(
                "[DummyMod-Login] Responding to CookieRequest (key: {})",
                packet.key()
        );

        this.connection.send(
                new CookieResponseC2SPacket(
                        packet.key(),
                        DummyManager.getCookie(packet.key())
                )
        );
    }

    @Override
    public void onDisconnected(
            DisconnectionInfo info
    ) {
        String reason =
                info.reason() != null
                        ? info.reason().getString()
                        : "Connection lost";

        LOGGER.info(
                "[DummyMod-Login] Disconnected: {}",
                reason
        );

        DummyManager.onConnectionClosed(
                this.connection
        );
    }
}
