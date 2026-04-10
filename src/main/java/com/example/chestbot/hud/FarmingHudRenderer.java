package com.example.chestbot.hud;

import com.example.chestbot.BotBridge;
import com.example.chestbot.FarmingActivityWatcher;
import com.example.chestbot.compat.HudMatricesCompat;
import com.example.chestbot.compat.PlayerSkinRenderCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

public final class FarmingHudRenderer {

    private static final long ACTIVE_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int BACKGROUND_COLOR = 0xA0101010;
    private static final int BORDER_COLOR = 0x90FFFFFF;
    private static final int TITLE_COLOR = 0xFF7CFC00;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SUBTEXT_COLOR = 0xFFBDBDBD;
    private static final int ROW_HEAD_SIZE = 16;
    private static final int ROW_ITEM_SIZE = 16;
    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 24;
    private static final int TITLE_HEIGHT = 12;

    private FarmingHudRenderer() {
    }

    public static ChestBotHudRenderer.HudBox getHudBox(BotBridge bridge, boolean preview, MinecraftClient client, int x, int y, float scale) {
        TextRenderer textRenderer = client == null ? null : client.textRenderer;
        if (bridge == null || textRenderer == null) {
            return new ChestBotHudRenderer.HudBox(0, 0, 0, 0);
        }
        List<BotBridge.MemberHudEntry> entries = getRenderableEntries(bridge, preview);
        int totalEntries = getRenderableEntryCount(bridge, preview);
        String title = pageTitle(preview ? "[농작물] HUD 수정 모드" : "[농작물] 수확 현황", bridge == null ? 0 : bridge.getFarmingHudPage(), totalEntries);
        int textWidth = textRenderer.getWidth(title);
        for (BotBridge.MemberHudEntry entry : entries) {
            textWidth = Math.max(textWidth, Math.max(
                    textRenderer.getWidth(playerLine(entry)),
                    textRenderer.getWidth(detailLine(entry))
            ));
        }
        int width = PADDING * 2 + ROW_HEAD_SIZE + 4 + ROW_ITEM_SIZE + 8 + textWidth;
        int height = PADDING * 2 + TITLE_HEIGHT + (entries.size() * ROW_HEIGHT) + (preview ? 12 : 0);
        return new ChestBotHudRenderer.HudBox(x, y, width, height, scale);
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = com.example.chestbot.ChestBotMod.getBridge();
        if (client == null || client.player == null || bridge == null || !bridge.isFarmingHudEnabled()) {
            return;
        }
        renderEntries(context, client, getRenderableEntries(bridge, false), false, bridge.getFarmingHudX(), bridge.getFarmingHudY(), bridge.getFarmingHudScale());
    }

    public static void renderPreview(DrawContext context, int x, int y, float scale) {
        MinecraftClient client = MinecraftClient.getInstance();
        BotBridge bridge = com.example.chestbot.ChestBotMod.getBridge();
        if (client == null || bridge == null) {
            return;
        }
        renderEntries(context, client, getRenderableEntries(bridge, true), true, x, y, scale);
    }

    public static int getRenderableEntryCount(BotBridge bridge, boolean preview) {
        return fullRenderableEntries(bridge, preview).size();
    }

    private static List<BotBridge.MemberHudEntry> getRenderableEntries(BotBridge bridge, boolean preview) {
        List<BotBridge.MemberHudEntry> entries = fullRenderableEntries(bridge, preview);
        if (entries.isEmpty()) {
            return entries;
        }
        return paginate(entries, bridge.getFarmingHudPage());
    }

    private static List<BotBridge.MemberHudEntry> fullRenderableEntries(BotBridge bridge, boolean preview) {
        List<BotBridge.MemberHudEntry> entries = bridge.getHudEntries().stream()
                .filter(FarmingHudRenderer::isActiveFarming)
                .toList();
        if (!entries.isEmpty()) {
            return entries;
        }

        if (!preview && bridge != null && bridge.isConnected()) {
            return List.of(placeholderEntry());
        }

        return List.of();
    }

    private static void renderEntries(DrawContext context, MinecraftClient client, List<BotBridge.MemberHudEntry> entries, boolean preview, int x, int y, float scale) {
        if (entries.isEmpty()) {
            return;
        }
        TextRenderer textRenderer = client.textRenderer;
        if (textRenderer == null) {
            return;
        }
        BotBridge bridge = com.example.chestbot.ChestBotMod.getBridge();
        ChestBotHudRenderer.HudBox hudBox = getHudBox(bridge, preview, client, x, y, scale);
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
        int totalEntries = getRenderableEntryCount(bridge, preview);
        context.drawTextWithShadow(textRenderer, pageTitle(preview ? "[농작물] HUD 수정 모드" : "[농작물] 수확 현황", bridge.getFarmingHudPage(), totalEntries), PADDING, PADDING, TITLE_COLOR);
        int rowY = PADDING + TITLE_HEIGHT;
        for (BotBridge.MemberHudEntry entry : entries) {
            int headX = PADDING;
            int headY = rowY + 2;
            int itemX = headX + ROW_HEAD_SIZE + 4;
            int itemY = rowY + 2;
            int textX = itemX + ROW_ITEM_SIZE + 8;
            if (!PlayerSkinRenderCompat.drawPlayerHead(context, client, entry.playerUuid(), entry.playerName(), headX, headY, ROW_HEAD_SIZE)) {
                context.drawItem(new ItemStack(Items.PLAYER_HEAD), headX, headY);
            }
            ItemStack stack = entry.farmingCropKey() == null ? new ItemStack(Items.WHEAT) : FarmingActivityWatcher.displayItem(entry.farmingCropKey());
            context.drawItem(stack.isEmpty() ? new ItemStack(Items.WHEAT) : stack, itemX, itemY);
            context.drawText(textRenderer, Text.literal(playerLine(entry)), textX, rowY + 1, TEXT_COLOR, false);
            context.drawText(textRenderer, Text.literal(detailLine(entry)), textX, rowY + 11, SUBTEXT_COLOR, false);
            rowY += ROW_HEIGHT;
        }
        if (preview) {
            context.drawText(textRenderer, Text.literal("한 화면에서 같이 편집"), PADDING, height - PADDING - 8, TITLE_COLOR, false);
        }
        HudMatricesCompat.pop(context);
    }

    public static boolean isActiveFarming(BotBridge.MemberHudEntry entry) {
        if (entry == null || entry.farmingCropKey() == null || entry.farmingCropKey().isBlank()) {
            return false;
        }
        long updatedAt = entry.farmingUpdatedAtMillis();
        return updatedAt > 0L && System.currentTimeMillis() - updatedAt <= ACTIVE_WINDOW_MILLIS;
    }

    private static String playerLine(BotBridge.MemberHudEntry entry) {
        if (entry.farmingCropKey() == null || entry.farmingCropKey().isBlank()) {
            return "대기";
        }
        return entry.playerName();
    }

    private static String detailLine(BotBridge.MemberHudEntry entry) {
        if (entry.farmingCropKey() == null || entry.farmingCropKey().isBlank()) {
            return "수확중인 사람이 없습니다";
        }
        return FarmingActivityWatcher.displayName(entry.farmingCropKey()) + " 수확중";
    }

    private static BotBridge.MemberHudEntry placeholderEntry() {
        long now = System.currentTimeMillis();
        return new BotBridge.MemberHudEntry("대기", "", 0, 0, 0, null, List.of(), null, null, null, now, null, 0L);
    }

    private static List<BotBridge.MemberHudEntry> paginate(List<BotBridge.MemberHudEntry> entries, int page) {
        if (entries.isEmpty()) {
            return entries;
        }
        int index = Math.max(0, Math.min(page, entries.size() - 1));
        return List.of(entries.get(index));
    }

    private static String pageTitle(String base, int page, int total) {
        return total <= 1 ? base : base + " §7(" + (page + 1) + "/" + total + ")";
    }

}
