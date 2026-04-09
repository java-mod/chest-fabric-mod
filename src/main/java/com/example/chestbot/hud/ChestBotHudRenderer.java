package com.example.chestbot.hud;

import com.example.chestbot.BotBridge;
import com.example.chestbot.ChestBotMod;
import com.example.chestbot.compat.HudMatricesCompat;
import com.example.chestbot.compat.ItemStackVisualCompat;
import com.example.chestbot.compat.PlayerSkinRenderCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

public final class ChestBotHudRenderer {

    private static final long ITEM_ROTATION_INTERVAL_MILLIS = 1500L;

    private static final int BACKGROUND_COLOR = 0xA0101010;
    private static final int BORDER_COLOR = 0x90FFFFFF;
    private static final int TITLE_COLOR = 0xFFFFD54F;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFBDBDBD;
    private static final int ROW_HEAD_SIZE = 16;
    private static final int ROW_ITEM_SIZE = 16;
    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 24;
    private static final int TITLE_HEIGHT = 12;

    private ChestBotHudRenderer() {
    }

    public static HudBox getHudBox(BotBridge bridge, boolean preview, MinecraftClient client) {
        if (bridge == null) {
            return new HudBox(0, 0, 0, 0);
        }
        return getHudBox(bridge, preview, client, bridge.getHudX(), bridge.getHudY(), bridge.getHudScale());
    }

    public static HudBox getHudBox(BotBridge bridge, boolean preview, MinecraftClient client, int x, int y, float scale) {
        TextRenderer textRenderer = client == null ? null : client.textRenderer;
        if (bridge == null || textRenderer == null) {
            return new HudBox(0, 0, 0, 0);
        }

        List<BotBridge.MemberHudEntry> entries = getRenderableEntries(bridge, preview);
        String title = preview ? "[창고지기] HUD 수정 모드" : buildHudTitle(bridge);
        int textWidth = textRenderer.getWidth(title);
        for (BotBridge.MemberHudEntry entry : entries) {
            BotBridge.HudRotationItem displayItem = currentRotationItem(entry);
            String playerLine = playerLine(entry);
            String detailLine = detailLine(displayItem);
            textWidth = Math.max(textWidth, Math.max(textRenderer.getWidth(playerLine), textRenderer.getWidth(detailLine)));
            for (BotBridge.HudRotationItem item : rotationItems(entry)) {
                textWidth = Math.max(textWidth, textRenderer.getWidth(detailLine(item)));
            }
        }

        int width = PADDING * 2 + ROW_HEAD_SIZE + 4 + ROW_ITEM_SIZE + 8 + textWidth;
        int height = PADDING * 2 + TITLE_HEIGHT + (entries.size() * ROW_HEIGHT) + (preview ? 12 : 0);
        return new HudBox(x, y, width, height, scale);
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = ChestBotMod.getBridge();
        if (client == null || client.player == null || bridge == null || !bridge.isHudEnabled()) {
            return;
        }

        Screen currentScreen = client.currentScreen;
        if (currentScreen instanceof ChestBotHudEditScreen) {
            return;
        }

        boolean preview = bridge.isHudEditMode();
        List<BotBridge.MemberHudEntry> entries = getRenderableEntries(bridge, preview);
        if (entries.isEmpty()) {
            return;
        }

        TextRenderer textRenderer = client.textRenderer;
        if (textRenderer == null) {
            return;
        }

        int x = bridge.getHudX();
        int y = bridge.getHudY();
        float scale = bridge.getHudScale();

        renderEntries(context, client, entries, preview, x, y, scale);
    }

    public static void renderPreview(DrawContext context, int x, int y, float scale) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = ChestBotMod.getBridge();
        if (client == null || bridge == null) {
            return;
        }
        renderEntries(context, client, getRenderableEntries(bridge, true), true, x, y, scale);
    }

    private static List<BotBridge.MemberHudEntry> getRenderableEntries(BotBridge bridge, boolean preview) {
        List<BotBridge.MemberHudEntry> entries = bridge.getHudEntries();
        if (!entries.isEmpty()) {
            return entries;
        }

        if (!preview && bridge.isConnected()) {
            return List.of(
                    new BotBridge.MemberHudEntry(
                            "대기 중",
                            "최근 가져감 없음",
                            0,
                            0,
                            0,
                            null,
                            List.of(new BotBridge.HudRotationItem("최근 가져감 없음", 0, null)),
                            "섬원 기록을 기다리는 중",
                            null,
                            null,
                            System.currentTimeMillis())
            );
        }

        if (!preview) {
            return List.of();
        }

        return List.of(
                new BotBridge.MemberHudEntry(
                        "Steve",
                        "diamond",
                        3,
                        2,
                        7,
                        null,
                        List.of(
                                new BotBridge.HudRotationItem("diamond", 3, null),
                                new BotBridge.HudRotationItem("gold_ingot", 4, null)
                        ),
                        "광물 창고",
                        null,
                        null,
                        System.currentTimeMillis()),
                new BotBridge.MemberHudEntry(
                        "Alex",
                        "iron_ingot",
                        16,
                        2,
                        24,
                        null,
                        List.of(
                                new BotBridge.HudRotationItem("iron_ingot", 16, null),
                                new BotBridge.HudRotationItem("coal", 8, null)
                        ),
                        "재료 창고",
                        null,
                        null,
                        System.currentTimeMillis())
        );
    }

    private static void renderEntries(DrawContext context,
                                      MinecraftClient client,
                                      List<BotBridge.MemberHudEntry> entries,
                                      boolean preview,
                                      int x,
                                      int y,
                                      float scale) {
        TextRenderer textRenderer = client.textRenderer;
        if (textRenderer == null || entries.isEmpty()) {
            return;
        }

        String title = preview ? "[창고지기] HUD 수정 모드" : buildHudTitle(ChestBotMod.getBridge());
        HudBox hudBox = getHudBox(ChestBotMod.getBridge(), preview, client, x, y, scale);
        int width = hudBox.width();
        int height = hudBox.height();

        HudMatricesCompat.push(context);
        HudMatricesCompat.translate(context, x, y);
        HudMatricesCompat.scale(context, scale);

        context.fill(0, 0, width, height, BACKGROUND_COLOR);
        context.drawHorizontalLine(0, width - 1, 0, BORDER_COLOR);
        context.drawHorizontalLine(0, width - 1, height - 1, BORDER_COLOR);
        context.drawVerticalLine(0, 0, height - 1, BORDER_COLOR);
        context.drawVerticalLine(width - 1, 0, height - 1, BORDER_COLOR);

        context.drawTextWithShadow(textRenderer, title, PADDING, PADDING, TITLE_COLOR);

        int rowY = PADDING + TITLE_HEIGHT;
        for (BotBridge.MemberHudEntry entry : entries) {
            BotBridge.HudRotationItem displayItem = currentRotationItem(entry);
            int headX = PADDING;
            int headY = rowY + 2;
            int itemX = headX + ROW_HEAD_SIZE + 4;
            int itemY = rowY + 2;
            int textX = itemX + ROW_ITEM_SIZE + 8;

            if (!PlayerSkinRenderCompat.drawPlayerHead(context, client, entry.playerUuid(), entry.playerName(), headX, headY, ROW_HEAD_SIZE)) {
                context.drawItem(createPlayerHead(entry), headX, headY);
            }
            context.drawItem(resolveVisualStack(client, displayItem), itemX, itemY);
            context.drawText(textRenderer,
                    Text.literal(playerLine(entry)),
                    textX,
                    rowY + 1,
                    TEXT_COLOR,
                    false);
            context.drawText(textRenderer,
                    Text.literal(detailLine(displayItem)),
                    textX,
                    rowY + 11,
                    SUBTEXT_COLOR,
                    false);
            rowY += ROW_HEIGHT;
        }

        if (preview) {
            context.drawText(textRenderer,
                    Text.literal("드래그 이동 | +/- 크기 | ESC 종료"),
                    PADDING,
                    height - PADDING - 8,
                    TITLE_COLOR,
                    false);
        }

        HudMatricesCompat.pop(context);
    }

    private static String detailLine(BotBridge.HudRotationItem item) {
        return ChestHudItemVisuals.stripActionPrefix(item.itemName()) + " x" + item.itemCount();
    }

    private static String buildHudTitle(BotBridge bridge) {
        if (bridge == null || bridge.getIslandName() == null || bridge.getIslandName().isBlank()) {
            return "[창고지기] 섬원 가져감";
        }
        return "[창고지기] " + bridge.getIslandName();
    }

    private static ItemStack createPlayerHead(BotBridge.MemberHudEntry entry) {
        return new ItemStack(Items.PLAYER_HEAD);
    }

    private static ItemStack resolveVisualStack(MinecraftClient client, BotBridge.HudRotationItem item) {
        ItemStack stack = ItemStackVisualCompat.deserialize(client, item.itemVisualData());
        return stack.isEmpty() ? new ItemStack(Items.CHEST) : stack;
    }

    private static List<BotBridge.HudRotationItem> rotationItems(BotBridge.MemberHudEntry entry) {
        List<BotBridge.HudRotationItem> items = entry.rotationItems();
        if (items != null && !items.isEmpty()) {
            return items;
        }
        return List.of(new BotBridge.HudRotationItem(entry.itemName(), entry.itemCount(), entry.itemVisualData()));
    }

    private static BotBridge.HudRotationItem currentRotationItem(BotBridge.MemberHudEntry entry) {
        List<BotBridge.HudRotationItem> items = rotationItems(entry);
        int index = 0;
        if (items.size() > 1) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - entry.updatedAtMillis());
            index = (int) ((elapsed / ITEM_ROTATION_INTERVAL_MILLIS) % items.size());
        }
        return items.get(index);
    }

    private static String playerLine(BotBridge.MemberHudEntry entry) {
        return entry.playerName();
    }

    public record HudBox(int x, int y, int width, int height, float scale) {
        public HudBox(int x, int y, int width, int height) {
            this(x, y, width, height, 1.0F);
        }

        public boolean contains(int mouseX, int mouseY) {
            int scaledWidth = Math.round(width * scale);
            int scaledHeight = Math.round(height * scale);
            return mouseX >= x && mouseX <= x + scaledWidth && mouseY >= y && mouseY <= y + scaledHeight;
        }
    }
}
