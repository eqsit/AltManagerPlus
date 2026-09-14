package com.dummymod.gui;

import com.dummymod.dummy.DummyManager;
import com.dummymod.dummy.PlayerSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class BaritoneDialogScreen extends Screen {
    private final Screen parent;
    private final PlayerSession session;
    private TextFieldWidget commandField;

    public BaritoneDialogScreen(Screen parent, PlayerSession session) {
        super(Text.literal("Baritone — Управление: " + session.displayName()));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int topY = height / 2 - 65;

        // Presets Row 1
        int btnW = 100;
        int h = 20;

        addDrawableChild(ButtonWidget.builder(Text.literal("🏃 Follow Me"), b -> {
            DummyManager.executeBaritone(session, "#follow player " + DummyManager.mainSession.displayName());
            close();
        }).dimensions(centerX - 155, topY, btnW, h).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("💎 Алмазы"), b -> {
            DummyManager.executeBaritone(session, "#mine diamond_ore deepslate_diamond_ore");
            close();
        }).dimensions(centerX - 50, topY, btnW, h).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("⛏ Железо"), b -> {
            DummyManager.executeBaritone(session, "#mine iron_ore deepslate_iron_ore");
            close();
        }).dimensions(centerX + 55, topY, btnW, h).build());

        // Presets Row 2
        int row2Y = topY + 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("📍 Goto +10 +10"), b -> {
            DummyManager.executeBaritone(session, "#goto ~10 ~ ~10");
            close();
        }).dimensions(centerX - 155, row2Y, btnW, h).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("⏸ Пауза / Пуск"), b -> {
            DummyManager.executeBaritone(session, "#pause");
            close();
        }).dimensions(centerX - 50, row2Y, btnW, h).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("🛑 Стоп (#stop)"), b -> {
            DummyManager.executeBaritone(session, "#stop");
            close();
        }).dimensions(centerX + 55, row2Y, btnW, h).build());

        // Custom command row
        int cmdY = row2Y + 28;
        commandField = new TextFieldWidget(textRenderer, centerX - 155, cmdY, 220, 20, Text.literal("Command"));
        commandField.setPlaceholder(Text.literal("Команда (например: #goto 100 64 200)"));
        addDrawableChild(commandField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Выполнить"), b -> {
            String cmd = commandField.getText().trim();
            if (!cmd.isEmpty()) {
                DummyManager.executeBaritone(session, cmd);
                close();
            }
        }).dimensions(centerX + 70, cmdY, 85, 20).build());

        // Back button
        addDrawableChild(ButtonWidget.builder(Text.literal("⬅ Назад"), b -> close())
                .dimensions(centerX - 75, cmdY + 30, 150, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 85, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
