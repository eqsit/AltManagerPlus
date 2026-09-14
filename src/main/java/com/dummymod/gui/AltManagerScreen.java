package com.dummymod.gui;

import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.DummyManager;
import com.dummymod.dummy.PlayerSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class AltManagerScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget nickField;
    private int scrollOffset = 0;

    public AltManagerScreen(Screen parent) {
        super(Text.literal("AltManager+ — Управление дамми и твинками"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int topY = 20;
        int startX = 16;
        int usableWidth = width - 32;

        int fieldWidth = Math.min(130, usableWidth / 5);
        int btnHeight = 20;

        // Nick input
        nickField = new TextFieldWidget(textRenderer, startX, topY, fieldWidth, btnHeight, Text.literal("Ник"));
        nickField.setMaxLength(16);
        nickField.setPlaceholder(Text.literal("Ник (или пусто)"));
        PlayerSession active = DummyManager.getActiveSession();
        String shownNick = active != null && !active.main && !active.displayName().isBlank()
                ? active.displayName() : DummyConfig.getInstance().getDummyNick();
        nickField.setText(shownNick == null ? "" : shownNick);
        addDrawableChild(nickField);

        int curX = startX + fieldWidth + 4;
        int btnW1 = 95;
        addDrawableChild(ButtonWidget.builder(Text.literal("➕ Спавн"), b -> {
            String name = nickField.getText().trim();
            DummyManager.spawnDummy(name.isEmpty() ? null : name);
            clearAndInit();
        }).dimensions(curX, topY, btnW1, btnHeight).build());
        curX += btnW1 + 4;

        int btnW2 = 95;
        addDrawableChild(ButtonWidget.builder(Text.literal("🎲 Рандом 7"), b -> {
            DummyManager.spawnRandomDummy();
            clearAndInit();
        }).dimensions(curX, topY, btnW2, btnHeight).build());
        curX += btnW2 + 4;

        int btnW3 = 75;
        addDrawableChild(ButtonWidget.builder(Text.literal("⚡ Спавн x3"), b -> {
            DummyManager.spawnBatchRandomDummies(3);
            clearAndInit();
        }).dimensions(curX, topY, btnW3, btnHeight).build());
        curX += btnW3 + 4;

        int btnW4 = 85;
        addDrawableChild(ButtonWidget.builder(Text.literal("🌐 Прокси"), b -> {
            if (client != null) client.setScreen(new ProxyConfigScreen(this));
        }).dimensions(curX, topY, btnW4, btnHeight).build());
        curX += btnW4 + 4;

        int btnW5 = 110;
        if (curX + btnW5 <= width - 16) {
            addDrawableChild(ButtonWidget.builder(Text.literal("❌ Отключить всех"), b -> {
                DummyManager.disconnectAll();
                clearAndInit();
            }).dimensions(curX, topY, btnW5, btnHeight).build());
        }

        rebuildCards();
    }

    private void rebuildCards() {
        List<PlayerSession> sessions = DummyManager.getSessions();
        int cardHeight = 56;
        int startY = 48 - scrollOffset;

        for (int i = 0; i < sessions.size(); i++) {
            PlayerSession s = sessions.get(i);
            int y = startY + i * (cardHeight + 6);
            if (y + cardHeight < 45 || y > height - 30) continue;

            final PlayerSession fs = s;
            boolean isActive = (DummyManager.getActiveSession() == s);

            int btnWidth = 85;
            int rightX = width - 24;

            // Delete button (for dummies only)
            if (!s.main) {
                int delW = 24;
                rightX -= delW;
                addDrawableChild(ButtonWidget.builder(Text.literal("§c✕"), b -> {
                    DummyManager.disconnectSession(fs);
                    clearAndInit();
                }).dimensions(rightX, y + 16, delW, 22).build());
                rightX -= 4;
            }

            // Baritone button
            int baritoneW = 80;
            rightX -= baritoneW;
            addDrawableChild(ButtonWidget.builder(Text.literal("🤖 Baritone"), b -> {
                if (client != null) client.setScreen(new BaritoneDialogScreen(this, fs));
            }).dimensions(rightX, y + 16, baritoneW, 22).build());
            rightX -= 4;

            // Exact manual CPS controls: every value from 1 to 50 is selectable.
            int plusW = 24;
            rightX -= plusW;
            addDrawableChild(ButtonWidget.builder(Text.literal("§a+"), b -> {
                fs.autoclicker.setCps(fs.autoclicker.getCps() + 1);
                clearAndInit();
            }).dimensions(rightX, y + 16, plusW, 22).build());
            rightX -= 2;

            int clickerW = 82;
            rightX -= clickerW;
            String clickerText = fs.autoclicker.enabled
                    ? "§a⚔ " + fs.autoclicker.getCps() + " CPS"
                    : "§7⚔ " + fs.autoclicker.getCps() + " CPS";
            addDrawableChild(ButtonWidget.builder(Text.literal(clickerText), b -> {
                fs.autoclicker.enabled = !fs.autoclicker.enabled;
                clearAndInit();
            }).dimensions(rightX, y + 16, clickerW, 22).build());
            rightX -= 2;

            int minusW = 24;
            rightX -= minusW;
            addDrawableChild(ButtonWidget.builder(Text.literal("§c−"), b -> {
                fs.autoclicker.setCps(fs.autoclicker.getCps() - 1);
                clearAndInit();
            }).dimensions(rightX, y + 16, minusW, 22).build());
            rightX -= 4;

            // Control button
            int ctrlW = 90;
            rightX -= ctrlW;
            Text ctrlLabel = isActive ? Text.literal("§a✔ УПРАВЛЕНИЕ") : Text.literal("🎮 Управлять");
            addDrawableChild(ButtonWidget.builder(ctrlLabel, b -> {
                DummyManager.switchToSession(fs);
                clearAndInit();
            }).dimensions(rightX, y + 16, ctrlW, 22).build());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int cardHeight = 62;
        int totalHeight = DummyManager.getSessions().size() * cardHeight;
        int maxScroll = Math.max(0, totalHeight - (height - 90));
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int)(verticalAmount * 24)));
        clearAndInit();
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        // Header title
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 6, 0xFFFFFF);

        List<PlayerSession> sessions = DummyManager.getSessions();
        int cardHeight = 56;
        int startY = 48 - scrollOffset;

        for (int i = 0; i < sessions.size(); i++) {
            PlayerSession s = sessions.get(i);
            int y = startY + i * (cardHeight + 6);
            if (y + cardHeight < 45 || y > height - 30) continue;

            boolean isActive = (DummyManager.getActiveSession() == s);
            int cardBg = isActive ? 0x991B3A1B : 0x881E1E1E;
            int cardBorder = isActive ? 0xFF55FF55 : 0xFF555555;

            // Card background & border
            context.fill(16, y, width - 16, y + cardHeight, cardBg);
            context.drawStrokedRectangle(16, y, width - 32, cardHeight, cardBorder);

            // Role & status
            Formatting roleColor = s.main ? Formatting.AQUA : Formatting.GOLD;
            Text statusText;
            if (s.isValid()) statusText = Text.literal("🟢 В сети").formatted(Formatting.GREEN);
            else if (s.connecting) statusText = Text.literal("🟡 Подключение...").formatted(Formatting.YELLOW);
            else statusText = Text.literal("🔴 Отключен").formatted(Formatting.RED);

            Text headerLine = Text.empty()
                    .append(Text.literal(DummyManager.getRoleLabel(s)).formatted(roleColor))
                    .append(Text.literal(" " + s.displayName()).formatted(Formatting.WHITE))
                    .append(Text.literal(" — ").formatted(Formatting.DARK_GRAY))
                    .append(statusText);
            context.drawTextWithShadow(textRenderer, headerLine, 24, y + 8, 0xFFFFFF);

            // Stats / Position
            if (s.player != null && s.world != null) {
                float hp = s.player.getHealth();
                float maxHp = s.player.getMaxHealth();
                String dim = s.world.getRegistryKey().getValue().getPath();
                String posLine = String.format("§c❤ %.0f/%.0f  §7Поз: §f%.1f, %.1f, %.1f §8(%s)",
                        hp, maxHp, s.player.getX(), s.player.getY(), s.player.getZ(), dim);
                context.drawTextWithShadow(textRenderer, Text.literal(posLine), 24, y + 23, 0xCCCCCC);
            } else {
                context.drawTextWithShadow(textRenderer, Text.literal("§7Ожидание появления игрока в мире..."), 24, y + 23, 0x888888);
            }

            // Proxy & Baritone info
            String proxyText = "§7Прокси: §b" + s.proxyLabel();
            context.drawTextWithShadow(textRenderer, Text.literal(proxyText), 24, y + 38, 0xAAAAAA);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
