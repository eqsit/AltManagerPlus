package com.dummymod.mixin;

import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.DummyManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    private TextFieldWidget dummyNickField;
    private ButtonWidget connectButton;
    private ButtonWidget switchButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        int totalWidth = 380;
        int startX = (this.width - totalWidth) / 2;
        int y = 14;

        int fieldWidth = 110;
        int connectWidth = 125;
        int switchWidth = 135;
        int height = 20;

        // Nickname text field
        this.dummyNickField = new TextFieldWidget(this.textRenderer, startX, y, fieldWidth, height, Text.literal("Ник"));
        this.dummyNickField.setMaxLength(16);
        this.dummyNickField.setText(DummyConfig.getInstance().getDummyNick());
        this.dummyNickField.setPlaceholder(Text.literal("Ник дамми"));
        this.dummyNickField.setChangedListener(text -> {
            DummyConfig.getInstance().setDummyNick(text);
            DummyConfig.getInstance().save();
        });
        this.addDrawableChild(this.dummyNickField);

        // Connect / Disconnect button
        this.connectButton = ButtonWidget.builder(getConnectButtonText(), btn -> {
            if (DummyManager.isConnected() || DummyManager.isConnecting()) {
                DummyManager.disconnect();
            } else {
                String nick = this.dummyNickField.getText().trim();
                if (nick.isEmpty()) {
                    nick = "Dummy_" + (this.client != null && this.client.getSession() != null ? this.client.getSession().getUsername() : "Player");
                    this.dummyNickField.setText(nick);
                }
                DummyManager.connectCurrentServer(nick);
            }
            updateButtonStates();
        }).dimensions(startX + fieldWidth + 5, y, connectWidth, height).build();
        this.addDrawableChild(this.connectButton);

        // Switch control button (Основа / Дамми)
        this.switchButton = ButtonWidget.builder(getSwitchButtonText(), btn -> {
            if (DummyManager.isConnected()) {
                DummyManager.toggleControl();
            } else {
                if (this.client != null && this.client.player != null) {
                    this.client.player.sendMessage(Text.literal("§c[DummyMod] Сначала подключите дамми!"), true);
                }
            }
            updateButtonStates();
        }).dimensions(startX + fieldWidth + 5 + connectWidth + 5, y, switchWidth, height).build();
        this.addDrawableChild(this.switchButton);
    }

    private void updateButtonStates() {
        if (this.connectButton != null) {
            this.connectButton.setMessage(getConnectButtonText());
        }
        if (this.switchButton != null) {
            this.switchButton.setMessage(getSwitchButtonText());
        }
    }

    private Text getConnectButtonText() {
        if (DummyManager.isConnecting()) {
            return Text.literal("§eОтменить");
        }
        return DummyManager.isConnected()
                ? Text.literal("§cОтключить дамми")
                : Text.literal("§aПодключить дамми");
    }

    private Text getSwitchButtonText() {
        if (!DummyManager.isConnected()) {
            return Text.literal("§7[Дамми выкл]");
        }
        return DummyManager.isControllingDummy()
                ? Text.literal("§6Управление: §aДАММИ")
                : Text.literal("§6Управление: §bОСНОВА");
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        updateButtonStates();
    }
}
