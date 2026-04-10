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
    private static final long FARMING_ACTIVE_WINDOW_MILLIS = 5 * 60 * 1000L;

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
        int totalEntries = getRenderableEntryCount(bridge, preview);
        String title = preview ? pageTitle("[창고지기] HUD 수정 모드", bridge.getChestHudPage(), totalEntries) : buildHudTitle(bridge, totalEntries);
        int textWidth = textRenderer.getWidth(title);
        for (BotBridge.MemberHudEntry entry : entries) {
            BotBridge.HudRotationItem displayItem = currentRotationItem(entry);
            String playerLine = playerLine(entry);
            String detailLine = detailLine(entry, displayItem);
            textWidth = Math.max(textWidth, Math.max(textRenderer.getWidth(playerLine), textRenderer.getWidth(detailLine)));
            for (BotBridge.HudRotationItem item : rotationItems(entry)) {
                textWidth = Math.max(textWidth, textRenderer.getWidth(detailLine(entry, item)));
            }
        }

        int width = PADDING * 2 + ROW_HEAD_SIZE + 4 + ROW_ITEM_SIZE + 8 + textWidth;
        int height = PADDING * 2 + TITLE_HEIGHT + (entries.size() * ROW_HEIGHT) + (preview ? 12 : 0);
        return new HudBox(x, y, width, height, scale);
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = ChestBotMod.getBridge();
        if (client == null || client.player == null || bridge == null || (!bridge.isHudEnabled() && !bridge.isFarmingHudEnabled())) {
            return;
        }

        Screen currentScreen = client.currentScreen;
        if (currentScreen instanceof ChestBotHudEditScreen) {
            return;
        }

        if (bridge.isHudEnabled()) {
            List<BotBridge.MemberHudEntry> entries = getRenderableEntries(bridge, false);
            if (!entries.isEmpty()) {
                renderEntries(context, client, entries, false, bridge.getHudX(), bridge.getHudY(), bridge.getHudScale());
            }
        }
        if (bridge.isFarmingHudEnabled()) {
            FarmingHudRenderer.render(context);
        }
    }

    public static void renderPreview(DrawContext context, int x, int y, float scale) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = ChestBotMod.getBridge();
        if (client == null || bridge == null) {
            return;
        }
        renderEntries(context, client, getRenderableEntries(bridge, true), true, x, y, scale);
    }

    public static int getRenderableEntryCount(BotBridge bridge, boolean preview) {
        return fullRenderableEntries(bridge, preview).size();
    }

    public static void renderAllPreviews(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = ChestBotMod.getBridge();
        if (client == null || bridge == null) {
            return;
        }
        renderPreview(context, bridge.getHudX(), bridge.getHudY(), bridge.getHudScale());
        FarmingHudRenderer.renderPreview(context, bridge.getFarmingHudX(), bridge.getFarmingHudY(), bridge.getFarmingHudScale());
    }

    private static List<BotBridge.MemberHudEntry> getRenderableEntries(BotBridge bridge, boolean preview) {
        List<BotBridge.MemberHudEntry> entries = fullRenderableEntries(bridge, preview);
        if (entries.isEmpty()) {
            return entries;
        }
        return paginate(entries, bridge.getChestHudPage());
    }

    private static List<BotBridge.MemberHudEntry> fullRenderableEntries(BotBridge bridge, boolean preview) {
        List<BotBridge.MemberHudEntry> entries = bridge.getHudEntries().stream()
                .filter(entry -> {
                    BotBridge.HudRotationItem item = currentRotationItem(entry);
                    return item != null && item.itemName() != null && !item.itemName().isBlank() && item.itemCount() > 0;
                })
                .toList();
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
                            System.currentTimeMillis(),
                            null,
                            0L)
            );
        }

        return List.of();
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

        int totalEntries = getRenderableEntryCount(ChestBotMod.getBridge(), preview);
        String title = preview ? pageTitle("[창고지기] HUD 수정 모드", ChestBotMod.getBridge().getChestHudPage(), totalEntries) : buildHudTitle(ChestBotMod.getBridge(), totalEntries);
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
            context.drawItem(resolveVisualStack(client, entry, displayItem), itemX, itemY);
            context.drawText(textRenderer,
                    Text.literal(playerLine(entry)),
                    textX,
                    rowY + 1,
                    TEXT_COLOR,
                    false);
            context.drawText(textRenderer,
                    Text.literal(detailLine(entry, displayItem)),
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

    private static String detailLine(BotBridge.MemberHudEntry entry, BotBridge.HudRotationItem item) {
        if (item == null || item.itemName() == null || item.itemName().isBlank() || item.itemCount() <= 0) {
            return "최근 가져감 없음";
        }
        return ChestHudItemVisuals.stripActionPrefix(item.itemName()) + " x" + item.itemCount();
    }

    private static String buildHudTitle(BotBridge bridge, int totalEntries) {
        if (bridge == null || bridge.getIslandName() == null || bridge.getIslandName().isBlank()) {
            return pageTitle("[창고지기] 섬원 가져감", bridge == null ? 0 : bridge.getChestHudPage(), totalEntries);
        }
        return pageTitle("[창고지기] " + bridge.getIslandName(), bridge.getChestHudPage(), totalEntries);
    }

    private static String pageTitle(String base, int page, int total) {
        return total <= 1 ? base : base + " §7(" + (page + 1) + "/" + total + ")";
    }

    private static List<BotBridge.MemberHudEntry> paginate(List<BotBridge.MemberHudEntry> entries, int page) {
        if (entries.isEmpty()) {
            return entries;
        }
        int index = Math.max(0, Math.min(page, entries.size() - 1));
        return List.of(entries.get(index));
    }

    private static ItemStack createPlayerHead(BotBridge.MemberHudEntry entry) {
        return new ItemStack(Items.PLAYER_HEAD);
    }

    private static ItemStack resolveVisualStack(MinecraftClient client, BotBridge.MemberHudEntry entry, BotBridge.HudRotationItem item) {
        if (item != null) {
            ItemStack stack = ItemStackVisualCompat.deserialize(client, item.itemVisualData());
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return new ItemStack(Items.CHEST);
    }

    private static List<BotBridge.HudRotationItem> rotationItems(BotBridge.MemberHudEntry entry) {
        List<BotBridge.HudRotationItem> items = entry.rotationItems();
        if (items != null && !items.isEmpty()) {
            return items;
        }
        if (entry.itemName() == null || entry.itemName().isBlank() || entry.itemCount() <= 0) {
            return List.of();
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
        return items.isEmpty() ? null : items.get(index);
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
