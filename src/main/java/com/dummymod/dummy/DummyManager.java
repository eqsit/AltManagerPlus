package com.dummymod.dummy;

import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.type.EventState;
import com.dummymod.DummyMod;
import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.baritone.BaritoneBridge;
import com.dummymod.dummy.network.DummyLoginHandler;
import com.dummymod.dummy.proxy.ProxyBridge;
import com.dummymod.mixin.ClientCommonNetworkHandlerAccessor;
import com.dummymod.mixin.ClientPlayNetworkHandlerAccessor;
import com.dummymod.mixin.ClientPlayerEntityAccessor;
import com.dummymod.mixin.KeyBindingAccessor;
import com.dummymod.mixin.MinecraftClientAccessor;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.network.*;
import net.minecraft.client.recipebook.ClientRecipeBook;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
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
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DummyManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("DummyMod-Manager");
    private static final AtomicLong IDS = new AtomicLong(1);
    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicInteger PROXY_CURSOR = new AtomicInteger();

    public static final PlayerSession mainSession = new PlayerSession(0, "Main", true);
    public static final CopyOnWriteArrayList<PlayerSession> dummySessions = new CopyOnWriteArrayList<>();
    private static final Map<ClientConnection, PlayerSession> SESSION_BY_CONNECTION = new ConcurrentHashMap<>();
    private static final Set<ClientConnection> EXPECTED_DISCONNECTS = ConcurrentHashMap.newKeySet();
    private record PacketContext(
            PlayerSession owner,
            PlayerSession effectiveSession,
            ClientPlayerEntity previousPlayer,
            ClientWorld previousWorld,
            ClientPlayerInteractionManager previousInteractionManager,
            Screen previousScreen,
            ChatHud.ChatState previousChatState,
            ClientPlayerEntity contextPlayer,
            ClientWorld contextWorld,
            ClientPlayerInteractionManager contextInteractionManager,
            Screen contextScreen,
            boolean switched
    ) {}
    private static final ThreadLocal<Deque<PacketContext>> PACKET_CONTEXT = ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile PlayerSession activeSession = mainSession;
    private static volatile long lastBackgroundTickFailureLogMillis;

    private DummyManager() {}

    public static List<PlayerSession> getSessions() {
        ArrayList<PlayerSession> list = new ArrayList<>(dummySessions.size() + 1);
        list.add(mainSession); list.addAll(dummySessions); return Collections.unmodifiableList(list);
    }
    public static PlayerSession getActiveSession() { return activeSession; }
    public static int getActiveSessionIndex() { return getSessions().indexOf(activeSession); }
    public static PlayerSession getNextSession() {
        List<PlayerSession> list = getSessions();
        if (list.isEmpty()) return null;
        int idx = list.indexOf(activeSession);
        if (idx < 0) idx = 0;
        for (int i = 1; i <= list.size(); i++) {
            PlayerSession candidate = list.get(Math.floorMod(idx + i, list.size()));
            if (candidate == mainSession || candidate.isValid()) return candidate;
        }
        return activeSession;
    }
    public static String getRoleLabel(PlayerSession session) {
        if (session == null || session.main) return "[ОСНОВА]";
        int index = dummySessions.indexOf(session);
        return "[ДАММИ #" + (index >= 0 ? index + 1 : "?") + "]";
    }
    public static PlayerSession getSession(ClientConnection connection) { return connection == null ? null : SESSION_BY_CONNECTION.get(connection); }
    public static PlayerSession getSession(ClientPlayNetworkHandler handler) { return handler == null ? null : getSession(handler.getConnection()); }
    public static PlayerSession getSession(PacketListener listener) {
        if (listener == null) return null;
        if (listener instanceof ClientPlayNetworkHandler h) {
            PlayerSession session = getSession(h);
            if (session != null) return session;
        }
        if (listener instanceof ClientCommonNetworkHandler commonHandler) {
            PlayerSession session = getSession(((ClientCommonNetworkHandlerAccessor) commonHandler).getConnection());
            if (session != null) return session;
        }
        for (Map.Entry<ClientConnection, PlayerSession> entry : SESSION_BY_CONNECTION.entrySet()) {
            if (entry.getKey().getPacketListener() == listener) return entry.getValue();
        }
        for (PlayerSession s : dummySessions) if (s.networkHandler == listener) return s;
        return mainSession.networkHandler == listener ? mainSession : null;
    }
    public static boolean isSessionPlayer(Entity e) {
        if (e == mainSession.player) return true;
        for (PlayerSession s : dummySessions) if (e == s.player) return true;
        return false;
    }
    public static boolean isDummyConnection(ClientConnection c) { PlayerSession s = getSession(c); return s != null && !s.main; }
    public static boolean isDummyReconfiguring(ClientConnection c) { PlayerSession s = getSession(c); return s != null && s.reconfiguring; }
    public static boolean isDummyReconfiguring() { for (PlayerSession s : dummySessions) if (s.reconfiguring) return true; return false; }
    public static boolean isConnected() { for (PlayerSession s : dummySessions) if (s.isValid()) return true; return false; }
    public static boolean isConnecting() { for (PlayerSession s : dummySessions) if (s.connecting) return true; return false; }
    public static boolean isControllingDummy() { return activeSession != mainSession; }

    public static void onPlayerMovementComplete(ClientPlayerEntity player, boolean sprintingBefore) {
        ControlDiagnostics.afterMovement(player, sprintingBefore);
    }

    public static Map<Identifier, byte[]> getCookies(ClientConnection c) { PlayerSession s = getSession(c); return s == null ? Map.of() : s.cookies; }
    public static byte[] getCookie(ClientConnection c, Identifier key) { PlayerSession s = getSession(c); return s == null ? null : s.cookies.get(key); }
    public static void storeCookie(ClientConnection c, Identifier key, byte[] payload) { PlayerSession s = getSession(c); if (s != null) s.cookies.put(key, payload); }
    public static boolean consumeExpectedDisconnect(ClientConnection c) { return EXPECTED_DISCONNECTS.remove(c); }

    public static void captureMain(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || activeSession != mainSession) return;
        mainSession.player = client.player; mainSession.world = client.world; mainSession.interactionManager = client.interactionManager;
        mainSession.networkHandler = client.getNetworkHandler();
        if (mainSession.networkHandler != null) {
            mainSession.activeConnection = mainSession.networkHandler.getConnection();
            SESSION_BY_CONNECTION.put(mainSession.activeConnection, mainSession);
        }
        if (client.getSession() != null) mainSession.setConfirmedProfileName(client.getSession().getUsername());
        BaritoneBridge.resolve(mainSession);
    }

    public static String randomNickname() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Random random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(7);
        for (int i = 0; i < 7; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }
    public static PlayerSession spawnDummy(String nick) { return connectCurrentServer(nick); }
    public static PlayerSession spawnRandomDummy() { return spawnDummy(randomNickname()); }
    public static List<PlayerSession> spawnBatchRandomDummies(int count) {
        int n = Math.max(0, count); ArrayList<PlayerSession> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(spawnRandomDummy()); return out;
    }

    public static PlayerSession connectCurrentServer(String nick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null || client.isInSingleplayer()) {
            notifyUser("§c[AltManager+] Подключение дамми возможно только на мультиплеер-сервере."); return null;
        }
        captureMain(client);
        String cleanNick = nick == null ? "" : nick.trim();
        if (cleanNick.isEmpty()) cleanNick = randomNickname();
        if (cleanNick.length() > 16) cleanNick = cleanNick.substring(0, 16);
        PlayerSession session = new PlayerSession(IDS.getAndIncrement(), cleanNick, false);
        session.proxy = chooseProxy(); session.connecting = true; session.connectionAttempt = ATTEMPTS.incrementAndGet();
        dummySessions.add(session);
        DummyConfig.getInstance().setDummyNick(cleanNick); DummyConfig.getInstance().save();

        Target target = resolveCurrentTarget(client);
        if (target == null) { session.connecting = false; dummySessions.remove(session); notifyUser("§c[AltManager+] Не удалось определить адрес сервера."); return null; }
        final String finalNick = cleanNick;
        Thread t = new Thread(() -> connectSession(session, target.host, target.address, finalNick), "AltManager-Connect-" + session.id);
        t.setDaemon(true); t.start();
        return session;
    }

    private record Target(String host, InetSocketAddress address) {}
    private static Target resolveCurrentTarget(MinecraftClient client) {
        InetSocketAddress target = null; String host = null;
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null && info.address != null && !info.address.isEmpty()) {
            ServerAddress sa = ServerAddress.parse(info.address); host = sa.getAddress();
            Optional<Address> resolved = AllowedAddressResolver.DEFAULT.resolve(sa);
            target = resolved.map(Address::getInetSocketAddress).orElseGet(() -> new InetSocketAddress(sa.getAddress(), sa.getPort()));
        }
        if (target == null && client.getNetworkHandler() != null) {
            SocketAddress a = client.getNetworkHandler().getConnection().getAddress();
            if (a instanceof InetSocketAddress inet) { target = inet; host = inet.getHostString(); }
        }
        if (target == null) return null;
        if ((target.getAddress() != null && target.getAddress().isAnyLocalAddress()) || "0.0.0.0".equals(target.getHostString())) {
            target = new InetSocketAddress("127.0.0.1", target.getPort()); host = "127.0.0.1";
        }
        return new Target(host == null ? target.getHostString() : host, target);
    }

    private static DummyConfig.SocksProxy chooseProxy() {
        DummyConfig cfg = DummyConfig.getInstance();
        if (cfg.getProxyDistributionMode() == DummyConfig.ProxyDistributionMode.DIRECT_ONLY) return null;
        List<DummyConfig.SocksProxy> enabled = cfg.getProxies().stream().filter(p -> p != null && p.enabled && p.host != null && !p.host.isBlank() && p.port > 0).toList();
        if (enabled.isEmpty()) return null;
        if (cfg.getProxyDistributionMode() == DummyConfig.ProxyDistributionMode.PROXIES_ONLY)
            return enabled.get(Math.floorMod(PROXY_CURSOR.getAndIncrement(), enabled.size()));
        int slot = Math.floorMod(PROXY_CURSOR.getAndIncrement(), enabled.size() + 1);
        return slot == 0 ? null : enabled.get(slot - 1);
    }

    private static void connectSession(PlayerSession session, String host, InetSocketAddress target, String nick) {
        try {
            if (!isAttemptCurrent(session)) return;
            ClientConnection connection = new ClientConnection(NetworkSide.CLIENTBOUND);
            session.pendingConnection = connection; session.activeConnection = connection; SESSION_BY_CONNECTION.put(connection, session);
            boolean nativeTransport = MinecraftClient.getInstance().options.shouldUseNativeTransport();
            InetSocketAddress socketTarget;
            if (session.proxy != null && session.proxy.enabled && session.proxy.host != null && !session.proxy.host.isBlank()) {
                socketTarget = new InetSocketAddress(session.proxy.host, session.proxy.port);
            } else {
                socketTarget = target;
            }
            ChannelFuture future = ProxyBridge.withContext(session.proxy, host, target.getPort(), () -> {
                ChannelFuture f = ClientConnection.connect(socketTarget, NetworkingBackend.remote(nativeTransport), connection);
                if (!f.awaitUninterruptibly(20, TimeUnit.SECONDS)) throw new IllegalStateException("Connection timeout");
                return f;
            });
            if (!future.isSuccess()) throw new IllegalStateException("Connection failed", future.cause());
            if (!isAttemptCurrent(session)) { closeExpected(connection, "Dummy connection cancelled"); return; }
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
            DummyLoginHandler login = new DummyLoginHandler(session, connection);
            connection.connect(host, target.getPort(), LoginStates.C2S, LoginStates.S2C, login, false);
            connection.send(new LoginHelloC2SPacket(nick, uuid));
            notifyUser("§a[AltManager+] " + nick + " подключается через " + session.proxyLabel());
        } catch (Throwable e) {
            LOGGER.error("Failed to connect dummy {}", nick, e);
            MinecraftClient.getInstance().execute(() -> {
                if (!dummySessions.contains(session)) return;
                session.connecting = false; cleanupConnectionMapping(session); dummySessions.remove(session);
                notifyUser("§c[AltManager+] Ошибка подключения " + nick + ": " + e.getMessage());
            });
        }
    }

    private static boolean isAttemptCurrent(PlayerSession s) { return dummySessions.contains(s) && s.connecting; }

    public static void registerPlayHandler(ClientConnection connection, ClientPlayNetworkHandler handler) {
        PlayerSession s = getSession(connection); if (s == null || s.main) return;
        s.activeConnection = connection; s.pendingConnection = null; s.networkHandler = handler; s.setConfirmedProfileName(handler.getProfile().name());
    }

    public static void beginDummyReconfiguration(ClientConnection connection) {
        PlayerSession s = getSession(connection); if (s == null || s.main) return;
        s.reconfiguring = true; s.connecting = true; s.resumeControlAfterReconfiguration = activeSession == s;
        if (activeSession == s) switchToSession(mainSession); s.clearWorld(); s.activeConnection = connection;
    }

    public static void onConnectionClosed(ClientConnection connection) {
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable r = () -> { PlayerSession s = getSession(connection); if (s != null && !s.main && !consumeExpectedDisconnect(connection)) disconnectSession(s); };
        if (client != null && !client.isOnThread()) client.execute(r); else r.run();
    }

    public static void restoreMainResourcePackConnection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || mainSession.networkHandler == null) return;
        ClientConnection c = mainSession.networkHandler.getConnection();
        if (c != null && c.isOpen()) client.getServerResourcePackProvider().init(c, ServerResourcePackManager.AcceptanceStatus.ALLOWED);
    }

    public static boolean switchToSession(PlayerSession target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || target == null || !getSessions().contains(target)) return false;
        if (target != mainSession && !target.isValid()) { notifyUser("§e[AltManager+] Сессия ещё не готова."); return false; }
        if (target == mainSession && (mainSession.player == null || mainSession.world == null)) return false;
        if (target == activeSession) return true;

        PlayerSession old = activeSession;
        saveToggleSprintState(old, client);
        ControlDiagnostics.onSwitchAway(old, client);
        saveVisibleChat(old, client);
        captureSessionScreen(old, client);
        clearInactiveInput(old);
        if (target.player != null && target.player.isDead()) target.player.requestRespawn();
        activeSession = target;
        restoreToggleSprintState(target, client);
        installForegroundSession(client, target);
        syncClientWorld(client, target.world, true);
        if (client.particleManager != null) client.particleManager.setWorld(target.world);
        focusForegroundCamera(client, target); restoreVisibleChat(target, client);
        restoreSessionScreen(target, client);
        ControlDiagnostics.onSwitchTo(target, client);
        if (client.currentScreen instanceof DeathScreen || client.currentScreen instanceof LevelLoadingScreen) client.setScreen(null);
        client.mouse.lockCursor();
        notifySwitch(target);
        if (client.player != null) client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f, target.main ? 0.9f : 1.2f);
        return true;
    }

    public static boolean switchToSession(int index) { List<PlayerSession> list = getSessions(); return index >= 0 && index < list.size() && switchToSession(list.get(index)); }
    public static void switchNextSession() {
        List<PlayerSession> list = getSessions(); if (list.size() <= 1) return;
        int idx = list.indexOf(activeSession); for (int i = 1; i <= list.size(); i++) {
            PlayerSession candidate = list.get(Math.floorMod(idx + i, list.size()));
            if (candidate == mainSession || candidate.isValid()) { switchToSession(candidate); return; }
        }
    }
    public static void toggleControl() { switchNextSession(); }


    private static boolean isSessionOwnedScreen(Screen screen) {
        return screen instanceof HandledScreen<?>;
    }

    private static void captureSessionScreen(PlayerSession session, MinecraftClient client) {
        if (session == null || client == null) return;
        captureSessionScreen(session, client.currentScreen);
    }

    private static void captureSessionScreen(PlayerSession session, Screen screen) {
        if (session == null) return;
        if (isSessionOwnedScreen(screen)) session.handledScreen = screen;
        else if (screen == null) session.handledScreen = null;
    }

    private static void restoreSessionScreen(PlayerSession session, MinecraftClient client) {
        if (session == null || client == null) return;
        Screen current = client.currentScreen;
        if (isSessionOwnedScreen(current) || session.handledScreen != null) {
            if (current != session.handledScreen) client.setScreen(session.handledScreen);
        }
    }

    private static boolean isToggleSprintEnabled(MinecraftClient client) {
        return client != null && Boolean.TRUE.equals(client.options.getSprintToggled().getValue());
    }

    private static void saveToggleSprintState(PlayerSession session, MinecraftClient client) {
        if (session == null || !isToggleSprintEnabled(client)) return;
        session.sprintKeyLatched = client.options.sprintKey.isPressed();
    }

    private static void restoreToggleSprintState(PlayerSession session, MinecraftClient client) {
        if (session == null || !isToggleSprintEnabled(client)) return;

        // sprintKey is one global StickyKeyBinding. Calling setPressed here would
        // run StickyKeyBinding's toggle semantics and invert the requested latch,
        // so write KeyBinding.pressed directly through the narrowly-scoped accessor.
        ((KeyBindingAccessor) client.options.sprintKey).dummymod$setPressedDirect(session.sprintKeyLatched);
    }

    private static void restoreKeyboardInput(PlayerSession session, MinecraftClient client) {
        if (session == null || session.player == null || client == null) return;

        // Foreground control must start from vanilla keyboard state, not from the
        // neutral Input used while this account was ticking in the background.
        // Tick immediately so keys that are already held (including sprint) are
        // visible on the very first foreground client tick after a switch.
        KeyboardInput keyboardInput = new KeyboardInput(client.options);
        keyboardInput.tick();
        session.player.input = keyboardInput;

        // ClientPlayerEntity keeps a few edge/packet fields outside Input. Reset
        // only this foreground player's transient edges; never touch global key
        // bindings or another session's Baritone input overrides.
        ClientPlayerEntityAccessor accessor = (ClientPlayerEntityAccessor) session.player;
        accessor.dummymod$setLastPlayerInput(PlayerInput.DEFAULT);
        accessor.dummymod$setLastSprinting(session.player.isSprinting());
        accessor.dummymod$setTicksLeftToDoubleTapSprint(0);
    }

    private static void focusForegroundCamera(MinecraftClient client, PlayerSession session) {
        if (client == null || session == null || session.player == null || session.world == null) return;

        // setCameraEntity is identity-based, but the renderer's Camera can still
        // carry interpolation/focus from the previous account. Reset and update
        // it against the selected world/entity so the first switch is correct
        // even when both accounts spawned at exactly the same coordinates.
        if (client.gameRenderer != null && client.gameRenderer.getCamera() != null) {
            client.gameRenderer.getCamera().reset();
        }
        client.setCameraEntity(session.player);
        if (client.gameRenderer != null && client.gameRenderer.getCamera() != null) {
            Perspective perspective = client.options.getPerspective();
            client.gameRenderer.getCamera().update(
                    session.world,
                    session.player,
                    !perspective.isFirstPerson(),
                    perspective.isFrontView(),
                    1.0f
            );
        }
    }

    private static void clearInactiveInput(PlayerSession session) {
        if (session == null || session.player == null) return;
        if (!BaritoneBridge.ownsInput(session)) session.player.input = new Input();
    }

    private static void installForegroundSession(MinecraftClient client, PlayerSession session) {
        if (client == null || session == null) return;
        client.player = session.player;
        client.world = session.world;
        client.interactionManager = session.interactionManager;
        restoreKeyboardInput(session, client);
    }

    private static void saveVisibleChat(PlayerSession s, MinecraftClient c) { if (s != null && c.inGameHud != null) s.chatState = c.inGameHud.getChatHud().toChatState(); }
    private static void restoreVisibleChat(PlayerSession s, MinecraftClient c) { if (s != null && s.chatState != null && c.inGameHud != null) c.inGameHud.getChatHud().restoreChatState(s.chatState); }
    private static void syncClientWorld(MinecraftClient client, ClientWorld world, boolean stopSounds) {
        client.world = world; ((MinecraftClientAccessor) client).dummymod$setWorld(world, stopSounds);
        if (client.worldRenderer != null) { client.worldRenderer.reload(); client.worldRenderer.scheduleTerrainUpdate(); }
    }

    /**
     * Install the packet listener's owning logical session into only the
     * MinecraftClient globals that vanilla packet handlers are allowed to use
     * as session-local state. This is intentionally a direct field swap: it
     * must not call setWorld, touch WorldRenderer/ParticleManager, move the
     * camera, or replace a player's Input while a background packet is being
     * applied.
     *
     * <p>The stack stores exact values from the enclosing context. That makes
     * nested packet application safe: an inner packet restores the outer
     * packet context, and the outer packet restores the real foreground.</p>
     */
    public static void beforePacketApply(PacketListener listener) {
        MinecraftClient client = MinecraftClient.getInstance();
        Deque<PacketContext> stack = PACKET_CONTEXT.get();
        PlayerSession owner = getSession(listener);
        PlayerSession previousEffective = stack.isEmpty() ? activeSession : stack.peek().effectiveSession();

        ClientPlayerEntity previousPlayer = client != null ? client.player : null;
        ClientWorld previousWorld = client != null ? client.world : null;
        ClientPlayerInteractionManager previousInteractionManager = client != null ? client.interactionManager : null;
        Screen previousScreen = client != null ? client.currentScreen : null;
        ChatHud.ChatState previousChatState = client != null && client.inGameHud != null
                ? client.inGameHud.getChatHud().toChatState()
                : null;

        boolean switched = client != null && owner != null && owner != previousEffective;
        PlayerSession effective = switched ? owner : previousEffective;

        if (switched) {
            // Direct assignments are deliberate. Calling setWorld/setScreen here
            // would mutate renderer/camera/cursor state belonging to the visible
            // account. Packet handlers may now safely resolve client.player/world/
            // interactionManager against their own connection instead.
            client.player = owner.player;
            client.world = owner.world;
            client.interactionManager = owner.interactionManager;
            client.currentScreen = owner.handledScreen;
            if (client.inGameHud != null && owner.chatState != null) {
                client.inGameHud.getChatHud().restoreChatState(owner.chatState);
            }
        }

        stack.push(new PacketContext(
                owner,
                effective,
                previousPlayer,
                previousWorld,
                previousInteractionManager,
                previousScreen,
                previousChatState,
                client != null ? client.player : null,
                client != null ? client.world : null,
                client != null ? client.interactionManager : null,
                client != null ? client.currentScreen : null,
                switched
        ));
    }

    public static void afterPacketApply(PacketListener listener) {
        Deque<PacketContext> stack = PACKET_CONTEXT.get();
        if (stack.isEmpty()) {
            LOGGER.warn("afterPacketApply called without matching beforePacketApply for {}", listener);
            return;
        }

        PacketContext context = stack.pop();
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerSession owner = context.owner();

        try {
            if (client != null && owner != null) {
                // Most packets mutate the installed player/world objects in place.
                // Only copy a singleton reference back when vanilla actually
                // replaced that reference. Custom dummy join/respawn handlers
                // update PlayerSession directly, so unchanged temporary globals
                // must not overwrite their newly-created session objects.
                if (client.player != context.contextPlayer()) owner.player = client.player;
                if (client.world != context.contextWorld()) owner.world = client.world;
                if (client.interactionManager != context.contextInteractionManager()) {
                    owner.interactionManager = client.interactionManager;
                }

                if (client.currentScreen != context.contextScreen()) {
                    captureSessionScreen(owner, client.currentScreen);
                }
                if (client.inGameHud != null) {
                    owner.chatState = client.inGameHud.getChatHud().toChatState();
                }
            }
        } finally {
            try {
                if (client != null && context.switched()) {
                    // Restore byte-for-byte object identity for the enclosing/visible
                    // session. Do not derive this from activeSession: nested packet
                    // application may have an outer temporary owner.
                    client.player = context.previousPlayer();
                    client.world = context.previousWorld();
                    client.interactionManager = context.previousInteractionManager();
                    client.currentScreen = context.previousScreen();
                    if (client.inGameHud != null && context.previousChatState() != null) {
                        client.inGameHud.getChatHud().restoreChatState(context.previousChatState());
                    }
                }
            } finally {
                if (stack.isEmpty()) PACKET_CONTEXT.remove();
            }
        }
    }

    public static void onDummyGameJoin(ClientPlayNetworkHandler handler, GameJoinS2CPacket packet) {
        PlayerSession session = getSession(handler); MinecraftClient client = MinecraftClient.getInstance(); if (session == null || client == null) return;
        session.connecting = false; session.reconfiguring = false;
        CommonPlayerSpawnInfo spawn = packet.commonPlayerSpawnInfo();
        ClientPlayNetworkHandlerAccessor a = (ClientPlayNetworkHandlerAccessor) handler;
        a.setWorldKeys(Sets.newLinkedHashSet(Lists.newArrayList(packet.dimensionIds()))); a.setChunkLoadDistance(packet.viewDistance()); a.setSimulationDistance(packet.simulationDistance());
        ClientWorld.Properties props = new ClientWorld.Properties(Difficulty.NORMAL, packet.hardcore(), spawn.isFlat()); a.setWorldProperties(props);
        ClientWorld world = new ClientWorld(handler, props, spawn.dimension(), spawn.dimensionType(), packet.viewDistance(), packet.simulationDistance(), client.worldRenderer, spawn.isDebug(), spawn.seed(), spawn.seaLevel()); a.setWorld(world);
        ClientPlayerInteractionManager im = new ClientPlayerInteractionManager(client, handler);
        ClientPlayerEntity player = im.createPlayer(world, new StatHandler(), new ClientRecipeBook()); player.setId(packet.playerEntityId()); player.init(); player.input = new Input(); world.addEntity(player);
        session.player = player; session.world = world; session.interactionManager = im;
        client.player = player; client.world = world; client.interactionManager = im;
        im.setGameModes(spawn.gameMode(), spawn.lastGameMode()); im.copyAbilities(player);
        player.setReducedDebugInfo(packet.reducedDebugInfo()); player.setShowsDeathScreen(packet.showDeathScreen()); player.setLimitedCraftingEnabled(packet.doLimitedCrafting());
        player.setLastDeathPos(spawn.lastDeathLocation()); player.setPortalCooldown(spawn.portalCooldown());
        session.networkHandler = handler; session.setConfirmedProfileName(handler.getProfile().name()); session.activeConnection = handler.getConnection();
        a.setLoaded(true); if (handler.getConnection() != null && handler.getConnection().isOpen()) handler.getConnection().send(new PlayerLoadedC2SPacket()); a.setSecureChatEnforced(packet.enforcesSecureChat());
        BaritoneBridge.resolve(session);
        if (activeSession == session) restoreKeyboardInput(session, client); else clearInactiveInput(session);
        if (session.resumeControlAfterReconfiguration) { session.resumeControlAfterReconfiguration = false; switchToSession(session); }
        notifyUser("§a[AltManager+] " + session.displayName() + " в сети (" + session.proxyLabel() + ")");
    }

    public static void onSessionPlayerRespawn(ClientPlayNetworkHandler handler, PlayerRespawnS2CPacket packet) {
        PlayerSession session = getSession(handler); MinecraftClient client = MinecraftClient.getInstance(); if (session == null || client == null) return;
        CommonPlayerSpawnInfo spawn = packet.commonPlayerSpawnInfo(); RegistryKey<World> dimension = spawn.dimension();
        ClientPlayerEntity oldPlayer = session.player; ClientWorld oldWorld = session.world; boolean changed = oldWorld == null || !oldWorld.getRegistryKey().equals(dimension);
        ClientPlayNetworkHandlerAccessor a = (ClientPlayNetworkHandlerAccessor) handler; ClientWorld world;
        if (changed) {
            Difficulty difficulty = oldWorld != null ? oldWorld.getLevelProperties().getDifficulty() : Difficulty.NORMAL; boolean hardcore = oldWorld != null && oldWorld.getLevelProperties().isHardcore();
            ClientWorld.Properties props = new ClientWorld.Properties(difficulty, hardcore, spawn.isFlat()); a.setWorldProperties(props);
            world = new ClientWorld(handler, props, dimension, spawn.dimensionType(), a.getChunkLoadDistance(), a.getSimulationDistance(), client.worldRenderer, spawn.isDebug(), spawn.seed(), spawn.seaLevel()); a.setWorld(world); session.world = world;
        } else world = oldWorld;
        ClientPlayerInteractionManager im = session.interactionManager != null ? session.interactionManager : new ClientPlayerInteractionManager(client, handler);
        boolean wasFlying = oldPlayer != null && oldPlayer.getAbilities().flying;
        StatHandler stats = oldPlayer != null ? oldPlayer.getStatHandler() : new StatHandler(); ClientRecipeBook recipes = oldPlayer != null ? oldPlayer.getRecipeBook() : new ClientRecipeBook();
        ClientPlayerEntity player = oldPlayer != null && packet.hasFlag(PlayerRespawnS2CPacket.KEEP_TRACKED_DATA) ? im.createPlayer(world, stats, recipes, oldPlayer.getLastPlayerInput(), oldPlayer.isSprinting()) : im.createPlayer(world, stats, recipes);
        if (oldPlayer != null) player.setId(oldPlayer.getId()); player.init();
        if (oldPlayer != null) { if (packet.hasFlag(PlayerRespawnS2CPacket.KEEP_ATTRIBUTES)) player.getAttributes().setFrom(oldPlayer.getAttributes()); else player.getAttributes().setBaseFrom(oldPlayer.getAttributes()); player.setReducedDebugInfo(oldPlayer.hasReducedDebugInfo()); player.setShowsDeathScreen(oldPlayer.showsDeathScreen()); }
        player.setLastDeathPos(spawn.lastDeathLocation()); player.setPortalCooldown(spawn.portalCooldown()); world.addEntity(player);
        im.setGameModes(spawn.gameMode(), spawn.lastGameMode()); im.copyAbilities(player);
        if (wasFlying && player.getAbilities().allowFlying) player.getAbilities().flying = true;
        a.setLoaded(true);
        if (handler.getConnection() != null && handler.getConnection().isOpen()) handler.getConnection().send(new PlayerLoadedC2SPacket());
        session.player = player; session.interactionManager = im; session.handledScreen = null; BaritoneBridge.resolve(session);
        if (activeSession == session) { installForegroundSession(client, session); syncClientWorld(client, world, changed); focusForegroundCamera(client, session); restoreSessionScreen(session, client); }
        else clearInactiveInput(session);
    }

    public static void onMainPlayerRespawn(ClientPlayNetworkHandler handler, PlayerRespawnS2CPacket packet) { onSessionPlayerRespawn(handler, packet); }
    public static void onDummyPlayerRespawn(ClientPlayNetworkHandler handler, PlayerRespawnS2CPacket packet) { onSessionPlayerRespawn(handler, packet); }

    public static void transfer(ClientConnection connection, String host, int port) {
        PlayerSession session = getSession(connection); if (session == null || session.main) return;
        MinecraftClient.getInstance().execute(() -> {
            String nick = session.displayName(); closeSessionConnection(session, true); session.connecting = true; session.connectionAttempt = ATTEMPTS.incrementAndGet();
            ServerAddress sa = new ServerAddress(host, port); Optional<Address> resolved = AllowedAddressResolver.DEFAULT.resolve(sa);
            InetSocketAddress target = resolved.map(Address::getInetSocketAddress).orElseGet(() -> new InetSocketAddress(host, port));
            Thread t = new Thread(() -> connectSession(session, host, target, nick), "AltManager-Transfer-" + session.id); t.setDaemon(true); t.start();
        });
    }
    public static void transfer(String host, int port) { PlayerSession s = activeSession != mainSession ? activeSession : dummySessions.stream().findFirst().orElse(null); if (s != null) transfer(s.activeConnection, host, port); }

    public static void disconnectSession(PlayerSession session) {
        if (session == null || session.main) return;
        MinecraftClient client = MinecraftClient.getInstance(); if (client != null && !client.isOnThread()) { client.execute(() -> disconnectSession(session)); return; }
        if (activeSession == session) switchToSession(mainSession); closeSessionConnection(session, false); dummySessions.remove(session); cleanupConnectionMapping(session);
    }
    public static void disconnectAll() { for (PlayerSession s : List.copyOf(dummySessions)) disconnectSession(s); }
    public static void disconnect() { disconnectAll(); }

    private static void closeSessionConnection(PlayerSession s, boolean transferring) {
        s.connecting = false; s.reconfiguring = false; s.resumeControlAfterReconfiguration = false; s.connectionAttempt = ATTEMPTS.incrementAndGet();
        ClientConnection p = s.pendingConnection; ClientConnection a = s.activeConnection != null ? s.activeConnection : s.networkHandler != null ? s.networkHandler.getConnection() : null;
        s.pendingConnection = null; s.activeConnection = null; closeExpected(a, transferring ? "Dummy transfer" : "Dummy disconnected"); if (p != a) closeExpected(p, transferring ? "Dummy transfer" : "Dummy disconnected");
        if (transferring) {
            s.clearWorld();
        } else {
            BaritoneBridge.destroy(s);
            s.clear();
        }
    }
    private static void cleanupConnectionMapping(PlayerSession s) { SESSION_BY_CONNECTION.entrySet().removeIf(e -> e.getValue() == s); }
    private static void closeExpected(ClientConnection c, String reason) { if (c == null) return; EXPECTED_DISCONNECTS.add(c); if (c.isOpen()) c.disconnect(Text.literal(reason)); c.handleDisconnection(); SESSION_BY_CONNECTION.remove(c); }

    public static boolean executeBaritone(PlayerSession s, String command) {
        if (s == null || !s.isValid()) return false;
        return BaritoneBridge.execute(s, command);
    }

    public static int executeBaritoneAllDummies(String command) {
        int executed = 0;
        for (PlayerSession s : dummySessions) {
            if (s.isValid() && BaritoneBridge.execute(s, command)) executed++;
        }
        return executed;
    }

    public static void toggleAutoclicker(PlayerSession s) {
        if (s != null) s.autoclicker.enabled = !s.autoclicker.enabled;
    }

    public static boolean handleLocalChatCommand(String message) {
        if (message == null || !message.startsWith("#")) return false;
        String raw = message.trim();
        if (raw.length() <= 1) {
            notifyUser("§c[AltManager+] Пустая #-команда. Используйте #help.");
            return true;
        }

        String body = raw.substring(1).trim();
        if (body.regionMatches(true, 0, "all", 0, 3)
                && (body.length() == 3 || Character.isWhitespace(body.charAt(3)))) {
            String nested = body.length() == 3 ? "" : body.substring(3).trim();
            if (nested.isEmpty()) {
                notifyUser("§c[AltManager+] Использование: #all <baritone-команда>.");
                return true;
            }
            int connected = 0;
            int handled = 0;
            for (PlayerSession session : dummySessions) {
                if (!session.isValid()) continue;
                connected++;
                if (BaritoneBridge.execute(session, nested)) handled++;
            }
            notifyUser("§6[AltManager+] §fBaritone #all §e" + nested
                    + " §8| §aOK: §f" + handled + "§7/§f" + connected);
            return true;
        }

        PlayerSession target = activeSession;
        if (target == null || !target.isValid()) {
            notifyUser("§c[AltManager+] Активная сессия недоступна для Baritone: " + raw);
            return true;
        }

        boolean handled = BaritoneBridge.execute(target, body);
        notifyUser("§6[AltManager+] §fBaritone §7→ §e" + target.displayName() + " §7" + getRoleLabel(target)
                + " §8| §f#" + body + (handled ? " §8| §aOK" : " §8| §cне выполнено"));
        return true; // Never leak any #-prefixed text to public server chat.
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;
        if (activeSession == mainSession) captureMain(client);
        PlayerSession foreground = activeSession;
        if (foreground != null && foreground.isValid()) {
            if (!(foreground.player.input instanceof KeyboardInput) && !BaritoneBridge.ownsInput(foreground)) {
                restoreKeyboardInput(foreground, client);
            }
            // Foreground state may be repaired here, but background work below
            // never changes these singleton fields.
            if (client.player != foreground.player || client.world != foreground.world || client.interactionManager != foreground.interactionManager) {
                client.player = foreground.player;
                client.world = foreground.world;
                client.interactionManager = foreground.interactionManager;
            }
            if (client.getCameraEntity() != foreground.player
                    || (client.gameRenderer != null
                    && client.gameRenderer.getCamera() != null
                    && client.gameRenderer.getCamera().getFocusedEntity() != foreground.player)) {
                focusForegroundCamera(client, foreground);
            }
            foreground.botController.tick(client);
            tickAutoclicker(foreground, client);
            ControlDiagnostics.pollForeground(client, "client-tick-end");
        }
        for (PlayerSession s : getSessions()) {
            if (s != foreground && s.isValid()) tickBackgroundSession(s, client);
        }
    }

    /**
     * Advance an inactive account without ever installing it into the global
     * MinecraftClient. Patched Baritone receives its normal PRE/POST tick from
     * Baritone's Minecraft tick hook using the session-bound context. We only
     * tick this account's connection and player entity here so forced movement
     * is applied and transmitted on this account's own network handler.
     */
    private static void tickBackgroundSession(PlayerSession s, MinecraftClient client) {
        try {
            if (s.networkHandler != null && s.networkHandler.getConnection() != null && s.networkHandler.getConnection().isOpen()) {
                s.networkHandler.getConnection().tick();
                s.networkHandler.tick();
            }

            boolean baritoneActive = BaritoneBridge.isAutomationActive(s);
            if (s.player != null && !baritoneActive) {
                // Never carry stale keyboard/Baritone state into an idle
                // background account.
                s.player.input = new Input();
            }

            s.botController.tick(client);
            if (s.player != null) {
                s.player.tick();
                // Baritone's PRE player-update hook is injected in LocalPlayer.tick.
                // Vanilla emits POST only for the visible player's world tick, so
                // background sessions need their own exact-owner POST event.
                if (s.baritone != null) {
                    s.baritone.getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));
                }
            }
            tickAutoclicker(s, client);
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastBackgroundTickFailureLogMillis > 5000) {
                lastBackgroundTickFailureLogMillis = now;
                LOGGER.warn("Background tick failed for {}", s.displayName(), t);
            }
        }
    }

    private static void tickAutoclicker(PlayerSession s, MinecraftClient client) {
        if (s == null || !s.isValid() || s.player == null || s.interactionManager == null) return;
        int clicks = s.autoclicker.consumeClicksThisTick();
        if (clicks <= 0) return;

        double reach = 4.5;
        HitResult hit = activeSession == s ? client.crosshairTarget : null;
        if (hit == null) {
            Vec3d start = s.player.getCameraPosVec(1.0f);
            Vec3d look = s.player.getRotationVec(1.0f);
            Vec3d end = start.add(look.multiply(reach));
            Box box = s.player.getBoundingBox().stretch(look.multiply(reach)).expand(1.0);
            EntityHitResult entityHit = ProjectileUtil.raycast(s.player, start, end, box,
                    e -> !e.isSpectator() && e.canHit(), reach * reach);
            hit = entityHit != null ? entityHit : s.player.raycast(reach, 1.0f, false);
        }

        try {
            for (int i = 0; i < clicks; i++) {
                if (hit instanceof EntityHitResult ehr) {
                    s.interactionManager.attackEntity(s.player, ehr.getEntity());
                } else if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
                    s.interactionManager.attackBlock(bhr.getBlockPos(), bhr.getSide());
                }
                s.player.swingHand(Hand.MAIN_HAND);
            }
        } catch (Throwable t) {
            LOGGER.debug("Autoclicker action failed for {}", s.displayName(), t);
        }
    }

    private static void notifySwitch(PlayerSession current) {
        PlayerSession next = getNextSession();
        String nextNick = next != null ? next.displayName() : current.displayName();
        String message = "§6[AltManager+] §fУправление: §e" + current.displayName() + " §7(" + getRoleLabel(current) + ") §8| §7Следующий [V]: §b" + nextNick;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null) return;
        Runnable r = () -> {
            if (c.player != null) {
                c.player.sendMessage(Text.literal(message), true);
                c.player.sendMessage(Text.literal(message), false);
            }
        };
        if (c.isOnThread()) r.run(); else c.execute(r);
    }

    private static void notifyUser(String message) {
        MinecraftClient c = MinecraftClient.getInstance(); if (c == null) return;
        Runnable r = () -> { if (c.player != null) c.player.sendMessage(Text.literal(message), false); };
        if (c.isOnThread()) r.run(); else c.execute(r);
    }
}
