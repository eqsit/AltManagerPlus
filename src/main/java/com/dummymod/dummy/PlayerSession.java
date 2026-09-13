package com.dummymod.dummy;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.world.ClientWorld;

import java.util.List;

public class PlayerSession {
    public String name;
    public ClientPlayerEntity player;
    public ClientWorld world;
    public ClientPlayerInteractionManager interactionManager;
    public ClientPlayNetworkHandler networkHandler;
    public ChatHud.ChatState chatState = emptyChatState();

    public PlayerSession(String name) {
        this.name = name;
    }

    public boolean isValid() {
        return player != null
                && world != null
                && networkHandler != null
                && networkHandler.getConnection() != null
                && networkHandler.getConnection().isOpen();
    }

    public void clear() {
        this.player = null;
        this.world = null;
        this.interactionManager = null;
        this.networkHandler = null;
        this.chatState = emptyChatState();
    }

    public void clearWorld() {
        this.player = null;
        this.world = null;
        this.interactionManager = null;
        this.networkHandler = null;
    }

    private static ChatHud.ChatState emptyChatState() {
        return new ChatHud.ChatState(List.of(), List.of(), List.of());
    }
}
