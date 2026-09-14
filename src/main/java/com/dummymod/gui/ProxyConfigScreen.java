package com.dummymod.gui;

import com.dummymod.config.DummyConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class ProxyConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget hostField;
    private TextFieldWidget portField;
    private TextFieldWidget userField;
    private TextFieldWidget passField;

    public ProxyConfigScreen(Screen parent) {
        super(Text.literal("AltManager+ — Настройки SOCKS5 Прокси"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int topY = 32;
        DummyConfig cfg = DummyConfig.getInstance();

        // Mode switch button
        String modeName = switch (cfg.getProxyDistributionMode()) {
            case ROUND_ROBIN -> "Равномерно (Direct + SOCKS5)";
            case PROXIES_ONLY -> "Только SOCKS5 прокси";
            case DIRECT_ONLY -> "Только прямое (Direct)";
        };
        addDrawableChild(ButtonWidget.builder(Text.literal("§6Режим прокси: §f" + modeName), b -> {
            var modes = DummyConfig.ProxyDistributionMode.values();
            int next = (cfg.getProxyDistributionMode().ordinal() + 1) % modes.length;
            cfg.setProxyDistributionMode(modes[next]);
            cfg.save();
            clearAndInit();
        }).dimensions(centerX - 160, topY, 320, 20).build());

        // Proxy add fields
        int addY = topY + 28;
        int fieldX = centerX - 180;

        hostField = new TextFieldWidget(textRenderer, fieldX, addY, 110, 20, Text.literal("Host"));
        hostField.setPlaceholder(Text.literal("IP / Host"));
        addDrawableChild(hostField);

        portField = new TextFieldWidget(textRenderer, fieldX + 114, addY, 50, 20, Text.literal("Port"));
        portField.setPlaceholder(Text.literal("1080"));
        addDrawableChild(portField);

        userField = new TextFieldWidget(textRenderer, fieldX + 168, addY, 70, 20, Text.literal("User"));
        userField.setPlaceholder(Text.literal("User (опц.)"));
        addDrawableChild(userField);

        passField = new TextFieldWidget(textRenderer, fieldX + 242, addY, 70, 20, Text.literal("Pass"));
        passField.setPlaceholder(Text.literal("Pass (опц.)"));
        addDrawableChild(passField);

        addDrawableChild(ButtonWidget.builder(Text.literal("➕"), b -> {
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();
            if (!host.isEmpty()) {
                int port = 1080;
                if (!portStr.isEmpty()) {
                    try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}
                }
                cfg.getProxies().add(new DummyConfig.SocksProxy(host, port, userField.getText().trim(), passField.getText().trim(), true));
                cfg.save();
                clearAndInit();
            }
        }).dimensions(fieldX + 316, addY, 44, 20).build());

        // Existing proxies list
        int listY = addY + 28;
        for (int i = 0; i < cfg.getProxies().size() && i < 8; i++) {
            var p = cfg.getProxies().get(i);
            int idx = i;
            int curY = listY + i * 24;

            String toggleText = p.enabled ? "§a✔ SOCKS5: " + p.label() : "§7✕ SOCKS5: " + p.label();
            addDrawableChild(ButtonWidget.builder(Text.literal(toggleText), b -> {
                p.enabled = !p.enabled;
                cfg.save();
                clearAndInit();
            }).dimensions(centerX - 160, curY, 240, 20).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("§cУдалить"), b -> {
                cfg.getProxies().remove(idx);
                cfg.save();
                clearAndInit();
            }).dimensions(centerX + 85, curY, 75, 20).build());
        }

        // Back button
        addDrawableChild(ButtonWidget.builder(Text.literal("⬅ Назад"), b -> close())
                .dimensions(centerX - 100, height - 28, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
