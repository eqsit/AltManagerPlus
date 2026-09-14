package com.dummymod.dummy;

import baritone.api.IBaritone;
import baritone.api.utils.IBaritoneClientContext;
import com.dummymod.bot.BotController;
import com.dummymod.config.DummyConfig;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.ClientConnection;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerSession {
    public final long id;
    public String name;
    /** Nick requested at spawn; never overwritten by an empty server profile. */
    private final String requestedName;
    /** Last non-blank name confirmed by the server profile. */
    private volatile String profileName;
    public final boolean main;
    public volatile ClientPlayerEntity player;
    public volatile ClientWorld world;
    public volatile ClientPlayerInteractionManager interactionManager;
    public volatile ClientPlayNetworkHandler networkHandler;
    public volatile ClientConnection pendingConnection;
    public volatile ClientConnection activeConnection;
    public volatile boolean connecting;
    public volatile boolean reconfiguring;
    public volatile boolean resumeControlAfterReconfiguration;
    public volatile long connectionAttempt;
    public volatile DummyConfig.SocksProxy proxy;
    public final AutoclickerState autoclicker = new AutoclickerState();
    public final BotController botController = new BotController(this);
    public volatile IBaritone baritone;
    /** Stable bound context used by this session's Baritone instance. */
    public volatile IBaritoneClientContext baritoneContext;
    public final Map<Identifier, byte[]> cookies = new ConcurrentHashMap<>();
    public ChatHud.ChatState chatState = emptyChatState();
    /** Session-owned inventory/container/creative screen. Ordinary menus are never stored here. */
    public volatile Screen handledScreen;
    /** Latched state of the global sprint StickyKeyBinding while this session is foreground. */
    public volatile boolean sprintKeyLatched = false;

    public PlayerSession(long id, String name, boolean main) {
        this.id = id;
        String initial = name == null ? "" : name.trim();
        this.requestedName = initial;
        this.name = initial;
        this.main = main;
    }


    public String requestedName() {
        return requestedName;
    }

    public void setConfirmedProfileName(String confirmed) {
        if (confirmed == null) return;
        String clean = confirmed.trim();
        if (clean.isEmpty()) return;
        profileName = clean;
        name = clean;
    }

    /** Deterministic non-empty name for every UI/notification surface. */
    public String displayName() {
        String confirmed = profileName;
        if (confirmed != null && !confirmed.isBlank()) return confirmed;
        if (requestedName != null && !requestedName.isBlank()) return requestedName;
        if (name != null && !name.isBlank()) return name;
        return main ? "Main" : "Dummy #" + id;
    }

    public boolean isValid() {
        ClientConnection c = activeConnection != null ? activeConnection : networkHandler != null ? networkHandler.getConnection() : null;
        return player != null && world != null && networkHandler != null && c != null && c.isOpen();
    }

    public String proxyLabel() {
        return proxy == null ? "Direct" : "SOCKS5: " + proxy.label();
    }

    public void clearWorld() {
        player = null;
        world = null;
        interactionManager = null;
        networkHandler = null;
        handledScreen = null;
    }

    public void clear() {
        clearWorld();
        pendingConnection = null;
        activeConnection = null;
        connecting = false;
        reconfiguring = false;
        resumeControlAfterReconfiguration = false;
        baritone = null;
        baritoneContext = null;
        botController.stop();
        cookies.clear();
        chatState = emptyChatState();
        handledScreen = null;
    }

    private static ChatHud.ChatState emptyChatState() {
        return new ChatHud.ChatState(List.of(), List.of(), List.of());
    }
}
