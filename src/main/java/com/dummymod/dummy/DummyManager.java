package com.dummymod.dummy;

import com.dummymod.DummyMod;
import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.network.DummyLoginHandler;
import com.dummymod.mixin.ClientPlayNetworkHandlerAccessor;
import com.dummymod.mixin.MinecraftClientAccessor;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.network.*;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkingBackend;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket;
import net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.state.LoginStates;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.StatHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DummyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("DummyMod-Manager");
    public static final PlayerSession mainSession = new PlayerSession("Main");
    public static final PlayerSession dummySession = new PlayerSession("Dummy");

    private static volatile boolean controllingDummy = false;
    private static volatile boolean connecting = false;
    private static volatile boolean disconnecting = false;
    private static volatile boolean resumeDummyControlAfterReconfiguration = false;
    private static volatile ClientConnection dummyPendingConnection;
    private static final AtomicLong connectionAttemptCounter = new AtomicLong();
    private static volatile long activeConnectionAttempt;
    private static final Map<Identifier, byte[]> serverCookies = new ConcurrentHashMap<>();
    private static final Set<ClientConnection> expectedDisconnects = ConcurrentHashMap.newKeySet();

    private static PlayerSession activePacketSession = null;
    private static long lastBackgroundTickFailureLogMillis;

    public static boolean isConnected() {
        return dummySession.isValid();
    }

    public static boolean isConnecting() {
        return connecting;
    }

    public static boolean isControllingDummy() {
        return isConnected() && controllingDummy;
    }

    public static Map<Identifier, byte[]> getCookies() {
        return serverCookies;
    }

    public static byte[] getCookie(Identifier key) {
        return serverCookies.get(key);
    }

    public static void storeCookie(Identifier key, byte[] payload) {
        serverCookies.put(key, payload);
    }

    public static boolean consumeExpectedDisconnect(ClientConnection connection) {
        return expectedDisconnects.remove(connection);
    }

    public static boolean isDummyConnection(ClientConnection connection) {
        if (connection == null) return false;
        if (connection == dummyPendingConnection || expectedDisconnects.contains(connection)) return true;
        return dummySession.networkHandler != null
                && dummySession.networkHandler.getConnection() == connection;
    }

    public static void registerPlayHandler(ClientConnection connection, ClientPlayNetworkHandler handler) {
        if (!isDummyConnection(connection)) return;
        dummyPendingConnection = connection;
        dummySession.networkHandler = handler;
        dummySession.name = handler.getProfile().name();
        LOGGER.debug("[DummyMod-Manager] Registered dummy play handler for '{}'", dummySession.name);
    }

    public static void beginDummyReconfiguration(ClientConnection connection) {
        if (!isDummyConnection(connection)) return;

        boolean wasControllingDummy = controllingDummy;
        if (wasControllingDummy) {
            setControllingDummy(false);
        }
        resumeDummyControlAfterReconfiguration = wasControllingDummy;
        connecting = true;
        dummySession.clearWorld();
        LOGGER.info("[DummyMod-Manager] Dummy entered server reconfiguration (resumeControl={})",
                resumeDummyControlAfterReconfiguration);
    }

    public static void onConnectionClosed(ClientConnection connection) {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable handle = () -> {
            if (!isDummyConnection(connection)) return;
            if (!consumeExpectedDisconnect(connection)) {
                disconnect();
            }
        };
        if (client != null && !client.isOnThread()) {
            client.execute(handle);
        } else {
            handle.run();
        }
    }

    public static void restoreMainResourcePackConnection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (mainSession.networkHandler == null) return;
        ClientConnection mainConnection = mainSession.networkHandler.getConnection();
        if (mainConnection != null && mainConnection.isOpen()) {
            client.getServerResourcePackProvider().init(
                    mainConnection,
                    ServerResourcePackManager.AcceptanceStatus.ALLOWED
            );
        }
    }

    public static void setControllingDummy(boolean dummy) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        if (dummy) {
            if (!dummySession.isValid()) {
                LOGGER.warn("[DummyMod-Manager] Cannot switch to dummy: dummy session is not valid");
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§c[DummyMod] Дамми еще загружается или не подключен!"), true);
                }
                return;
            }

            // Save active main session when the live client context is usable. If a
            // renderer/mod transition has already nulled a live field, retain the
            // previously captured main session rather than overwriting it.
            saveVisibleChat(mainSession, client);
            if (client.player != null && client.world != null) {
                mainSession.player = client.player;
                mainSession.world = client.world;
                mainSession.interactionManager = client.interactionManager;
                mainSession.networkHandler = client.getNetworkHandler();
                if (client.getSession() != null) {
                    mainSession.name = client.getSession().getUsername();
                }
            }

            if (mainSession.player == null || mainSession.world == null) {
                LOGGER.warn("[DummyMod-Manager] Cannot switch to dummy: main session is not available");
                return;
            }

            // Put main player in idle input state
            if (mainSession.player != null) {
                mainSession.player.input = new Input();
            }

            // If dummy was dead, request respawn
            if (dummySession.player != null && dummySession.player.isDead()) {
                dummySession.player.requestRespawn();
            }

            // Switch to dummy session
            controllingDummy = true;
            client.player = dummySession.player;
            client.interactionManager = dummySession.interactionManager;

            // Give dummy active keyboard input
            dummySession.player.input = new KeyboardInput(client.options);

            // Use MinecraftClient#setWorld so WorldRenderer, ParticleManager and
            // GameRenderer all switch to the same non-null world atomically.
            syncClientWorld(client, dummySession.world, false);
            client.setCameraEntity(dummySession.player);
            restoreVisibleChat(dummySession, client);

            if (client.currentScreen instanceof DeathScreen || client.currentScreen instanceof LevelLoadingScreen) {
                client.setScreen(null);
            }

            KeyBinding.unpressAll();
            client.mouse.lockCursor();

            LOGGER.info("[DummyMod-Manager] Switched active game context to DUMMY ('{}')", dummySession.name);
            client.player.sendMessage(
                    Text.literal(String.format("§6[DummyMod] §fУправление: §aДАММИ §7(%s)", dummySession.name)),
                    true
            );
            client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f, 1.2f);
        } else {
            if (mainSession.player == null || mainSession.world == null) {
                return;
            }

            // Put dummy player in idle input state
            saveVisibleChat(dummySession, client);
            if (dummySession.player != null) {
                dummySession.player.input = new Input();
            }

            // If main was dead, request respawn
            if (mainSession.player.isDead()) {
                mainSession.player.requestRespawn();
            }

            // Switch to main session
            controllingDummy = false;
            client.player = mainSession.player;
            client.interactionManager = mainSession.interactionManager;

            // Give main active keyboard input
            mainSession.player.input = new KeyboardInput(client.options);

            // Use MinecraftClient#setWorld so WorldRenderer, ParticleManager and
            // GameRenderer all switch to the same non-null world atomically.
            syncClientWorld(client, mainSession.world, false);
            client.setCameraEntity(mainSession.player);
            restoreVisibleChat(mainSession, client);

            if (client.currentScreen instanceof DeathScreen || client.currentScreen instanceof LevelLoadingScreen) {
                client.setScreen(null);
            }

            KeyBinding.unpressAll();
            client.mouse.lockCursor();

            LOGGER.info("[DummyMod-Manager] Switched active game context to MAIN ('{}')", mainSession.name);
            client.player.sendMessage(
                    Text.literal(String.format("§6[DummyMod] §fУправление: §bОСНОВА §7(%s)", mainSession.name)),
                    true
            );
            client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8f, 0.8f);
        }
    }

    public static void toggleControl() {
        if (!isConnected()) {
            LOGGER.warn("[DummyMod-Manager] Cannot toggle control: Dummy is not connected!");
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                client.player.sendMessage(Text.literal("§c[DummyMod] Дамми не подключен!"), true);
            }
            return;
        }
        setControllingDummy(!controllingDummy);
    }

    public static void beforePacketApply(PacketListener listener) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        if (listener == dummySession.networkHandler) {
            if (!controllingDummy && dummySession.player != null && dummySession.world != null) {
                activePacketSession = dummySession;
                saveVisibleChat(mainSession, client);
                restoreVisibleChat(dummySession, client);
                client.player = dummySession.player;
                client.world = dummySession.world;
                client.interactionManager = dummySession.interactionManager;
            }
        } else if (listener == mainSession.networkHandler) {
            if (controllingDummy && mainSession.player != null && mainSession.world != null) {
                activePacketSession = mainSession;
                saveVisibleChat(dummySession, client);
                restoreVisibleChat(mainSession, client);
                client.player = mainSession.player;
                client.world = mainSession.world;
                client.interactionManager = mainSession.interactionManager;
            }
        }
    }

    public static void afterPacketApply(PacketListener listener) {
        if (activePacketSession == null) return;
        PlayerSession background = activePacketSession;
        activePacketSession = null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        PlayerSession fg = controllingDummy ? dummySession : mainSession;
        saveVisibleChat(background, client);
        restoreVisibleChat(fg, client);
        if (fg.player != null && fg.world != null) {
            client.player = fg.player;
            client.world = fg.world;
            client.interactionManager = fg.interactionManager;
        }
    }

    private static void saveVisibleChat(PlayerSession session, MinecraftClient client) {
        if (client.inGameHud != null) {
            session.chatState = client.inGameHud.getChatHud().toChatState();
        }
    }

    private static void restoreVisibleChat(PlayerSession session, MinecraftClient client) {
        if (client.inGameHud != null && session.chatState != null) {
            client.inGameHud.getChatHud().restoreChatState(session.chatState);
        }
    }

    private static void syncClientWorld(MinecraftClient client, ClientWorld world, boolean stopSounds) {
        if (client == null || world == null) {
            LOGGER.warn("[DummyMod-Manager] Refusing to synchronize a null client world");
            return;
        }

        // Do not call WorldRenderer#setWorld directly here. MinecraftClient#setWorld
        // is the authoritative transition point and keeps WorldRenderer,
        // ParticleManager and GameRenderer synchronized with MinecraftClient.world.
        ((MinecraftClientAccessor) client).dummymod$setWorld(world, stopSounds);

        // Keep the public field explicit as a defensive invariant for modded clients
        // whose injected setWorld path may be altered by renderer mods.
        client.world = world;
    }

    public static void onDummyGameJoin(ClientPlayNetworkHandler handler, GameJoinS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        connecting = false;

        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        List<RegistryKey<World>> dimensionList = Lists.newArrayList(packet.dimensionIds());
        Set<RegistryKey<World>> worldKeys = Sets.newLinkedHashSet(dimensionList);

        ClientPlayNetworkHandlerAccessor accessor = (ClientPlayNetworkHandlerAccessor) handler;
        accessor.setWorldKeys(worldKeys);
        accessor.setChunkLoadDistance(packet.viewDistance());
        accessor.setSimulationDistance(packet.simulationDistance());

        ClientWorld.Properties properties = new ClientWorld.Properties(
                Difficulty.NORMAL,
                packet.hardcore(),
                spawnInfo.isFlat()
        );
        accessor.setWorldProperties(properties);

        ClientWorld dummyWorld = new ClientWorld(
                handler,
                properties,
                spawnInfo.dimension(),
                spawnInfo.dimensionType(),
                packet.viewDistance(),
                packet.simulationDistance(),
                client.worldRenderer,
                spawnInfo.isDebug(),
                spawnInfo.seed(),
                spawnInfo.seaLevel()
        );

        accessor.setWorld(dummyWorld);

        ClientPlayerInteractionManager interactionManager = new ClientPlayerInteractionManager(client, handler);
        ClientPlayerEntity dummyPlayer = interactionManager.createPlayer(
                dummyWorld,
                new StatHandler(),
                new ClientRecipeBook()
        );
        dummyPlayer.setId(packet.playerEntityId());
        dummyPlayer.init();
        dummyPlayer.input = new Input(); // Idle input
        dummyWorld.addEntity(dummyPlayer);
        interactionManager.copyAbilities(dummyPlayer);
        dummyPlayer.setReducedDebugInfo(packet.reducedDebugInfo());
        dummyPlayer.setShowsDeathScreen(packet.showDeathScreen());
        dummyPlayer.setLimitedCraftingEnabled(packet.doLimitedCrafting());
        dummyPlayer.setLastDeathPos(spawnInfo.lastDeathLocation());
        dummyPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        interactionManager.setGameModes(spawnInfo.gameMode(), spawnInfo.lastGameMode());

        dummySession.player = dummyPlayer;
        dummySession.world = dummyWorld;
        dummySession.interactionManager = interactionManager;
        dummySession.networkHandler = handler;
        dummySession.name = handler.getProfile().name();

        // Tell the server the player finished loading chunks and can move/spawn
        accessor.setLoaded(true);
        if (handler.getConnection() != null && handler.getConnection().isOpen()) {
            handler.getConnection().send(new PlayerLoadedC2SPacket());
        }
        accessor.setSecureChatEnforced(packet.enforcesSecureChat());

        if (controllingDummy) {
            dummyPlayer.input = new KeyboardInput(client.options);
            client.player = dummyPlayer;
            client.interactionManager = interactionManager;
            syncClientWorld(client, dummyWorld, true);
            client.setCameraEntity(dummyPlayer);
            if (client.currentScreen instanceof DeathScreen || client.currentScreen instanceof LevelLoadingScreen) {
                client.setScreen(null);
            }
        }

        LOGGER.info(
                "[DummyMod-Manager] Dummy joined successfully as '{}' (entityId={}) on thread {}",
                dummySession.name,
                dummyPlayer.getId(),
                Thread.currentThread().getName()
        );

        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal(String.format(
                            "§a[DummyMod] Дамми '%s' успешно подключен! Переключение: §e[%s]",
                            dummySession.name,
                            DummyMod.SWITCH_KEY.getBoundKeyLocalizedText().getString()
                    )),
                    false
            );
            client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        }

        if (resumeDummyControlAfterReconfiguration) {
            resumeDummyControlAfterReconfiguration = false;
            setControllingDummy(true);
        }
    }

    public static void onDummyPlayerRespawn(
            ClientPlayNetworkHandler handler,
            PlayerRespawnS2CPacket packet
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        RegistryKey<World> newDimension = spawnInfo.dimension();
        ClientPlayerEntity oldPlayer = dummySession.player;
        ClientWorld oldWorld = dummySession.world;

        boolean dimensionChanged =
                oldWorld == null || !oldWorld.getRegistryKey().equals(newDimension);
        ClientWorld newWorld;

        ClientPlayNetworkHandlerAccessor accessor =
                (ClientPlayNetworkHandlerAccessor) handler;

        if (dimensionChanged) {
            Difficulty difficulty = oldWorld != null
                    ? oldWorld.getLevelProperties().getDifficulty()
                    : Difficulty.NORMAL;

            boolean isHardcore =
                    oldWorld != null && oldWorld.getLevelProperties().isHardcore();

            ClientWorld.Properties properties = new ClientWorld.Properties(
                    difficulty,
                    isHardcore,
                    spawnInfo.isFlat()
            );

            accessor.setWorldProperties(properties);

            newWorld = new ClientWorld(
                    handler,
                    properties,
                    spawnInfo.dimension(),
                    spawnInfo.dimensionType(),
                    accessor.getChunkLoadDistance(),
                    accessor.getSimulationDistance(),
                    client.worldRenderer,
                    spawnInfo.isDebug(),
                    spawnInfo.seed(),
                    spawnInfo.seaLevel()
            );

            accessor.setWorld(newWorld);
            dummySession.world = newWorld;
        } else {
            newWorld = oldWorld;
        }

        ClientPlayerInteractionManager interactionManager =
                dummySession.interactionManager != null
                        ? dummySession.interactionManager
                        : new ClientPlayerInteractionManager(client, handler);

        StatHandler statHandler =
                oldPlayer != null ? oldPlayer.getStatHandler() : new StatHandler();

        ClientRecipeBook recipeBook =
                oldPlayer != null ? oldPlayer.getRecipeBook() : new ClientRecipeBook();

        ClientPlayerEntity newPlayer =
                oldPlayer != null
                                && packet.hasFlag(PlayerRespawnS2CPacket.KEEP_TRACKED_DATA)
                        ? interactionManager.createPlayer(
                                newWorld,
                                statHandler,
                                recipeBook,
                                oldPlayer.getLastPlayerInput(),
                                oldPlayer.isSprinting()
                        )
                        : interactionManager.createPlayer(
                                newWorld,
                                statHandler,
                                recipeBook
                        );

        if (oldPlayer != null) {
            newPlayer.setId(oldPlayer.getId());
        }

        newPlayer.init();

        if (oldPlayer != null) {
            if (packet.hasFlag(PlayerRespawnS2CPacket.KEEP_ATTRIBUTES)) {
                newPlayer.getAttributes().setFrom(oldPlayer.getAttributes());
            } else {
                newPlayer.getAttributes().setBaseFrom(oldPlayer.getAttributes());
            }

            newPlayer.setReducedDebugInfo(oldPlayer.hasReducedDebugInfo());
            newPlayer.setShowsDeathScreen(oldPlayer.showsDeathScreen());
        }

        newPlayer.setLastDeathPos(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        newWorld.addEntity(newPlayer);

        interactionManager.copyAbilities(newPlayer);
        interactionManager.setGameModes(
                spawnInfo.gameMode(),
                spawnInfo.lastGameMode()
        );

        accessor.setLoaded(true);
        if (handler.getConnection() != null && handler.getConnection().isOpen()) {
            handler.getConnection().send(new PlayerLoadedC2SPacket());
        }

        dummySession.player = newPlayer;
        dummySession.interactionManager = interactionManager;

        if (controllingDummy) {
            newPlayer.input = new KeyboardInput(client.options);

            client.player = newPlayer;
            client.interactionManager = interactionManager;

            syncClientWorld(client, newWorld, dimensionChanged);
            client.setCameraEntity(newPlayer);

            if (client.currentScreen instanceof DeathScreen
                    || client.currentScreen instanceof LevelLoadingScreen) {
                client.setScreen(null);
            }

            KeyBinding.unpressAll();
            client.mouse.lockCursor();
        } else {
            newPlayer.input = new Input(); // Idle input
        }

        LOGGER.info(
                "[DummyMod-Manager] Dummy player respawned/changed dimension to {} (dimensionChanged={})",
                newDimension.getValue(),
                dimensionChanged
        );
    }

    public static void onMainPlayerRespawn(
            ClientPlayNetworkHandler handler,
            PlayerRespawnS2CPacket packet
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        RegistryKey<World> newDimension = spawnInfo.dimension();

        ClientPlayerEntity oldPlayer =
                mainSession.player != null ? mainSession.player : client.player;

        ClientWorld oldWorld =
                mainSession.world != null ? mainSession.world : client.world;

        boolean dimensionChanged =
                oldWorld == null || !oldWorld.getRegistryKey().equals(newDimension);
        ClientWorld newWorld;

        ClientPlayNetworkHandlerAccessor accessor =
                (ClientPlayNetworkHandlerAccessor) handler;

        if (dimensionChanged) {
            Difficulty difficulty = oldWorld != null
                    ? oldWorld.getLevelProperties().getDifficulty()
                    : Difficulty.NORMAL;

            boolean isHardcore =
                    oldWorld != null && oldWorld.getLevelProperties().isHardcore();

            ClientWorld.Properties properties = new ClientWorld.Properties(
                    difficulty,
                    isHardcore,
                    spawnInfo.isFlat()
            );

            accessor.setWorldProperties(properties);

            newWorld = new ClientWorld(
                    handler,
                    properties,
                    spawnInfo.dimension(),
                    spawnInfo.dimensionType(),
                    accessor.getChunkLoadDistance(),
                    accessor.getSimulationDistance(),
                    client.worldRenderer,
                    spawnInfo.isDebug(),
                    spawnInfo.seed(),
                    spawnInfo.seaLevel()
            );

            accessor.setWorld(newWorld);
            mainSession.world = newWorld;
        } else {
            newWorld = oldWorld;
        }

        ClientPlayerInteractionManager interactionManager =
                mainSession.interactionManager != null
                        ? mainSession.interactionManager
                        : new ClientPlayerInteractionManager(client, handler);

        StatHandler statHandler =
                oldPlayer != null ? oldPlayer.getStatHandler() : new StatHandler();

        ClientRecipeBook recipeBook =
                oldPlayer != null ? oldPlayer.getRecipeBook() : new ClientRecipeBook();

        ClientPlayerEntity newPlayer =
                oldPlayer != null
                                && packet.hasFlag(PlayerRespawnS2CPacket.KEEP_TRACKED_DATA)
                        ? interactionManager.createPlayer(
                                newWorld,
                                statHandler,
                                recipeBook,
                                oldPlayer.getLastPlayerInput(),
                                oldPlayer.isSprinting()
                        )
                        : interactionManager.createPlayer(
                                newWorld,
                                statHandler,
                                recipeBook
                        );

        if (oldPlayer != null) {
            newPlayer.setId(oldPlayer.getId());
        }

        newPlayer.init();

        if (oldPlayer != null) {
            if (packet.hasFlag(PlayerRespawnS2CPacket.KEEP_ATTRIBUTES)) {
                newPlayer.getAttributes().setFrom(oldPlayer.getAttributes());
            } else {
                newPlayer.getAttributes().setBaseFrom(oldPlayer.getAttributes());
            }

            newPlayer.setReducedDebugInfo(oldPlayer.hasReducedDebugInfo());
            newPlayer.setShowsDeathScreen(oldPlayer.showsDeathScreen());
        }

        newPlayer.setLastDeathPos(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());

        newWorld.addEntity(newPlayer);

        interactionManager.copyAbilities(newPlayer);
        interactionManager.setGameModes(
                spawnInfo.gameMode(),
                spawnInfo.lastGameMode()
        );

        accessor.setLoaded(true);
        if (handler.getConnection() != null && handler.getConnection().isOpen()) {
            handler.getConnection().send(new PlayerLoadedC2SPacket());
        }

        mainSession.player = newPlayer;
        mainSession.world = newWorld;
        mainSession.interactionManager = interactionManager;

        if (!controllingDummy) {
            newPlayer.input = new KeyboardInput(client.options);

            client.player = newPlayer;
            client.interactionManager = interactionManager;

            syncClientWorld(client, newWorld, dimensionChanged);
            client.setCameraEntity(newPlayer);

            if (client.currentScreen instanceof DeathScreen
                    || client.currentScreen instanceof LevelLoadingScreen) {
                client.setScreen(null);
            }

            KeyBinding.unpressAll();
            client.mouse.lockCursor();
        } else {
            newPlayer.input = new Input(); // Idle input
        }

        LOGGER.info(
                "[DummyMod-Manager] Main player respawned/changed dimension to {} (dimensionChanged={})",
                newDimension.getValue(),
                dimensionChanged
        );
    }

    public static void connectCurrentServer(String nick) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (connecting || isConnected()) {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal("§e[DummyMod] Дамми уже подключается или подключен."),
                        true
                );
            }
            return;
        }

        if (client.world == null || client.player == null) {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal("§c[DummyMod] Вы должны быть в игре на сервере!"),
                        false
                );
            }
            return;
        }

        // Save current main session
        saveVisibleChat(mainSession, client);
        mainSession.player = client.player;
        mainSession.world = client.world;
        mainSession.interactionManager = client.interactionManager;
        mainSession.networkHandler = client.getNetworkHandler();

        if (client.getSession() != null) {
            mainSession.name = client.getSession().getUsername();
        }

        InetSocketAddress targetSocketAddress = null;
        String hostName = null;

        ServerInfo serverInfo = client.getCurrentServerEntry();

        if (serverInfo != null
                && serverInfo.address != null
                && !serverInfo.address.isEmpty()) {
            LOGGER.info(
                    "[DummyMod-Manager] Resolving server from ServerInfo: {}",
                    serverInfo.address
            );

            ServerAddress serverAddress =
                    ServerAddress.parse(serverInfo.address);

            hostName = serverAddress.getAddress();

            Optional<Address> resolved =
                    AllowedAddressResolver.DEFAULT.resolve(serverAddress);

            if (resolved.isPresent()) {
                targetSocketAddress =
                        resolved.get().getInetSocketAddress();
            } else {
                targetSocketAddress = new InetSocketAddress(
                        serverAddress.getAddress(),
                        serverAddress.getPort()
                );
            }
        }

        if (targetSocketAddress == null
                && client.getNetworkHandler() != null
                && client.getNetworkHandler().getConnection() != null) {
            SocketAddress socketAddress =
                    client.getNetworkHandler().getConnection().getAddress();

            if (socketAddress instanceof InetSocketAddress inet) {
                targetSocketAddress = inet;
                hostName = inet.getHostString();
            }
        }

        if (targetSocketAddress == null || client.isInSingleplayer()) {
            if (client.player != null) {
                client.player.sendMessage(
                        Text.literal(
                                "§c[DummyMod] Дамми работает только на мультиплеер-серверах!"
                        ),
                        false
                );
            }
            return;
        }

        // Fix 0.0.0.0 local bind address
        if (targetSocketAddress.getAddress() != null
                && targetSocketAddress.getAddress().isAnyLocalAddress()) {
            targetSocketAddress =
                    new InetSocketAddress(
                            "127.0.0.1",
                            targetSocketAddress.getPort()
                    );

            if ("0.0.0.0".equals(hostName) || hostName == null) {
                hostName = "127.0.0.1";
            }
        } else if ("0.0.0.0".equals(targetSocketAddress.getHostString())) {
            targetSocketAddress =
                    new InetSocketAddress(
                            "127.0.0.1",
                            targetSocketAddress.getPort()
                    );
            hostName = "127.0.0.1";
        }

        disconnect();
        serverCookies.clear();

        DummyConfig.getInstance().setDummyNick(nick);
        DummyConfig.getInstance().save();

        long attempt = connectionAttemptCounter.incrementAndGet();
        activeConnectionAttempt = attempt;
        connecting = true;

        final InetSocketAddress finalTarget = targetSocketAddress;
        final String finalHost = hostName;

        Thread connectThread = new Thread(() -> {
            try {
                LOGGER.info(
                        "[DummyMod-Manager] Connecting dummy '{}' to {}:{}...",
                        nick,
                        finalHost,
                        finalTarget.getPort()
                );

                if (!isAttemptCurrent(attempt)) return;

                ClientConnection connection =
                        new ClientConnection(NetworkSide.CLIENTBOUND);

                if (!isAttemptCurrent(attempt)) return;

                dummyPendingConnection = connection;

                boolean nativeTransport =
                        MinecraftClient.getInstance()
                                .options
                                .shouldUseNativeTransport();

                NetworkingBackend backend =
                        NetworkingBackend.remote(nativeTransport);

                ChannelFuture future =
                        ClientConnection.connect(
                                finalTarget,
                                backend,
                                connection
                        );

                if (!future.awaitUninterruptibly(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Превышено время ожидания подключения (15 секунд)"
                    );
                }

                if (!future.isSuccess()) {
                    throw new IllegalStateException(
                            "Не удалось открыть соединение",
                            future.cause()
                    );
                }

                if (!isAttemptCurrent(attempt)) {
                    connection.disconnect(
                            Text.literal("Dummy connection cancelled")
                    );
                    return;
                }

                UUID uuid = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + nick)
                                .getBytes(StandardCharsets.UTF_8)
                );

                DummyLoginHandler loginHandler =
                        new DummyLoginHandler(null, connection);

                connection.connect(
                        finalHost,
                        finalTarget.getPort(),
                        LoginStates.C2S,
                        LoginStates.S2C,
                        loginHandler,
                        false
                );

                connection.send(
                        new LoginHelloC2SPacket(nick, uuid)
                );

                MinecraftClient.getInstance().execute(() -> {
                    if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(
                                Text.literal(String.format(
                                        "§a[DummyMod] Дамми '%s' подключается к %s:%d...",
                                        nick,
                                        finalHost,
                                        finalTarget.getPort()
                                )),
                                false
                        );
                    }
                });
            } catch (Exception e) {
                LOGGER.error(
                        "[DummyMod-Manager] Connection exception",
                        e
                );

                MinecraftClient.getInstance().execute(() -> {
                    if (!isAttemptCurrent(attempt)) return;

                    connecting = false;
                    dummyPendingConnection = null;

                    if (MinecraftClient.getInstance().player != null) {
                        MinecraftClient.getInstance().player.sendMessage(
                                Text.literal(
                                        "§c[DummyMod] Ошибка подключения: "
                                                + e.getMessage()
                                ),
                                false
                        );
                    }
                });
            }
        }, "Dummy-Netty-Thread");

        connectThread.setDaemon(true);
        connectThread.start();
    }

    public static void transfer(String host, int port) {
        MinecraftClient client = MinecraftClient.getInstance();

        client.execute(() -> {
            String nick =
                    DummyConfig.getInstance().getDummyNick();

            LOGGER.info(
                    "[DummyMod-Manager] Transferring dummy '{}' to {}:{}",
                    nick,
                    host,
                    port
            );

            closeDummyConnection(true);

            long attempt =
                    connectionAttemptCounter.incrementAndGet();

            activeConnectionAttempt = attempt;
            connecting = true;

            ServerAddress serverAddress =
                    new ServerAddress(host, port);

            Thread transferThread = new Thread(
                    () -> connect(
                            serverAddress,
                            nick,
                            true,
                            attempt
                    ),
                    "Dummy-Transfer-Thread"
            );

            transferThread.setDaemon(true);
            transferThread.start();
        });
    }

    private static void connect(
            ServerAddress serverAddress,
            String nick,
            boolean transfer,
            long attempt
    ) {
        try {
            Optional<Address> resolved =
                    AllowedAddressResolver.DEFAULT.resolve(serverAddress);

            InetSocketAddress target =
                    resolved
                            .map(Address::getInetSocketAddress)
                            .orElseGet(() -> new InetSocketAddress(
                                    serverAddress.getAddress(),
                                    serverAddress.getPort()
                            ));

            String host =
                    serverAddress.getAddress();

            LOGGER.info(
                    "[DummyMod-Manager] {} dummy '{}' to {}:{}...",
                    transfer ? "Transferring" : "Connecting",
                    nick,
                    host,
                    target.getPort()
            );

            if (!isAttemptCurrent(attempt)) return;

            ClientConnection connection =
                    new ClientConnection(NetworkSide.CLIENTBOUND);

            if (!isAttemptCurrent(attempt)) return;

            dummyPendingConnection = connection;

            boolean nativeTransport =
                    MinecraftClient.getInstance()
                            .options
                            .shouldUseNativeTransport();

            ChannelFuture future =
                    ClientConnection.connect(
                            target,
                            NetworkingBackend.remote(nativeTransport),
                            connection
                    );

            if (!future.awaitUninterruptibly(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Превышено время ожидания подключения (15 секунд)"
                );
            }

            if (!future.isSuccess()) {
                throw new IllegalStateException(
                        "Не удалось открыть соединение",
                        future.cause()
                );
            }

            if (!isAttemptCurrent(attempt)) {
                connection.disconnect(
                        Text.literal("Dummy connection cancelled")
                );
                return;
            }

            UUID uuid =
                    UUID.nameUUIDFromBytes(
                            ("OfflinePlayer:" + nick)
                                    .getBytes(StandardCharsets.UTF_8)
                    );

            DummyLoginHandler loginHandler =
                    new DummyLoginHandler(null, connection);

            connection.connect(
                    host,
                    target.getPort(),
                    LoginStates.C2S,
                    LoginStates.S2C,
                    loginHandler,
                    false
            );

            connection.send(
                    new LoginHelloC2SPacket(nick, uuid)
            );
        } catch (Exception e) {
            LOGGER.error(
                    "[DummyMod-Manager] Dummy transfer failed",
                    e
            );

            MinecraftClient.getInstance().execute(() -> {
                if (!isAttemptCurrent(attempt)) return;

                connecting = false;
                dummyPendingConnection = null;

                MinecraftClient mc =
                        MinecraftClient.getInstance();

                if (mc.player != null) {
                    mc.player.sendMessage(
                            Text.literal(
                                    "§c[DummyMod] Ошибка перехода дамми: "
                                            + e.getMessage()
                            ),
                            false
                    );
                }
            });
        }
    }

    public static void disconnect() {
        MinecraftClient client =
                MinecraftClient.getInstance();

        if (client != null && !client.isOnThread()) {
            client.execute(DummyManager::disconnect);
            return;
        }

        if (disconnecting) return;

        disconnecting = true;

        try {
            closeDummyConnection(false);
        } catch (Throwable t) {
            LOGGER.error(
                    "[DummyMod-Manager] Error in disconnect",
                    t
            );
        } finally {
            disconnecting = false;
        }
    }

    private static void closeDummyConnection(boolean transferring) {
        activeConnectionAttempt =
                connectionAttemptCounter.incrementAndGet();

        connecting = false;
        resumeDummyControlAfterReconfiguration = false;

        if (controllingDummy) {
            setControllingDummy(false);
        }

        ClientConnection pending =
                dummyPendingConnection;

        dummyPendingConnection = null;

        ClientConnection active =
                dummySession.networkHandler != null
                        ? dummySession.networkHandler.getConnection()
                        : null;

        closeExpected(
                active,
                transferring
                        ? "Dummy transfer"
                        : "Dummy disconnected"
        );

        if (pending != active) {
            closeExpected(
                    pending,
                    transferring
                            ? "Dummy transfer"
                            : "Dummy disconnected"
            );
        }

        if (transferring) {
            dummySession.clearWorld();
        } else {
            dummySession.clear();
        }
    }

    private static boolean isAttemptCurrent(long attempt) {
        return activeConnectionAttempt == attempt
                && connecting;
    }

    private static void closeExpected(
            ClientConnection connection,
            String reason
    ) {
        if (connection == null) return;

        expectedDisconnects.add(connection);

        if (connection.isOpen()) {
            connection.disconnect(
                    Text.literal(reason)
            );
        }

        connection.handleDisconnection();
    }

    public static void tick(MinecraftClient client) {
        // A reconfiguration/transfer may briefly invalidate the live fields. Recover
        // the foreground context from our session snapshot through the full setWorld
        // path instead of allowing WorldRenderer to remain detached from a world.
        if (client.world == null || client.player == null) {
            PlayerSession fg =
                    controllingDummy
                            ? dummySession
                            : mainSession;

            if (fg.player != null && fg.world != null) {
                client.player = fg.player;
                client.interactionManager =
                        fg.interactionManager;

                syncClientWorld(
                        client,
                        fg.world,
                        false
                );

                client.setCameraEntity(
                        fg.player
                );
            } else {
                return;
            }
        }

        // Update main session reference while controlling main
        if (!controllingDummy
                && client.player != null
                && client.world != null) {
            mainSession.player =
                    client.player;

            mainSession.world =
                    client.world;

            mainSession.interactionManager =
                    client.interactionManager;

            mainSession.networkHandler =
                    client.getNetworkHandler();

            if (client.getSession() != null) {
                mainSession.name =
                        client.getSession().getUsername();
            }
        }

        // Tick the background session cleanly
        if (!controllingDummy) {
            if (dummySession.isValid()) {
                tickBackgroundSession(
                        dummySession,
                        client
                );
            }
        } else {
            if (mainSession.isValid()) {
                tickBackgroundSession(
                        mainSession,
                        client
                );
            }
        }
    }

    private static void tickBackgroundSession(
            PlayerSession session,
            MinecraftClient client
    ) {
        PlayerSession fg =
                controllingDummy
                        ? dummySession
                        : mainSession;

        try {
            // Temporarily expose the background logical context to vanilla tick code.
            // Intentionally do NOT call MinecraftClient#setWorld/WorldRenderer#setWorld
            // here: the renderer must stay attached to the foreground non-null world.
            client.player =
                    session.player;

            client.world =
                    session.world;

            client.interactionManager =
                    session.interactionManager;

            if (session.networkHandler != null
                    && session.networkHandler.getConnection() != null
                    && session.networkHandler.getConnection().isOpen()) {
                session.networkHandler
                        .getConnection()
                        .tick();

                session.networkHandler.tick();
            }

            if (session.player != null) {
                session.player.input =
                        new Input(); // Keep idle input

                session.player.tick();
            }

            if (session.world != null) {
                session.world.tick(
                        () -> true
                );
            }
        } catch (Throwable t) {
            long now =
                    System.currentTimeMillis();

            if (now - lastBackgroundTickFailureLogMillis
                    >= 5_000L) {
                lastBackgroundTickFailureLogMillis =
                        now;

                LOGGER.warn(
                        "[DummyMod-Manager] Background session tick failed; keeping the foreground session active",
                        t
                );
            }
        } finally {
            // Restore the logical foreground context. Renderer-side world state was
            // never changed during the background tick, so no null/stale render world
            // can be introduced by background ticking.
            if (fg.player != null
                    && fg.world != null) {
                client.player =
                        fg.player;

                client.world =
                        fg.world;

                client.interactionManager =
                        fg.interactionManager;
            }
        }
    }
}