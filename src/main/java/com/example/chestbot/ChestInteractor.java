package com.example.chestbot;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 플레이어가 등록된 상자를 열고 닫을 때 인벤토리 변화를 감지한다.
 *
 * <p>등록된 chest 목록은 {@link BotBridge#findByPos(BlockPos)} 를 통해 조회한다.</p>
 */
public class ChestInteractor {

    private static Screen prevScreen    = null;
    private static String openChestName = null;
    private static Map<String, Integer> beforeSnapshot = null;

    public static void onBlockInteracted(MinecraftClient client, BlockPos pos) {
        if (client == null || pos == null || client.world == null) {
            return;
        }

        Block block = client.world.getBlockState(pos).getBlock();
        if (!(block instanceof ChestBlock)) {
            return;
        }

        BotBridge bridge = ChestBotMod.getBridge();
        bridge.recordChestInteraction(pos);

        if (!bridge.isAdminMode() || !bridge.hasPendingChestSelection()) {
            return;
        }

        String name = bridge.getPendingChestSelectionName();
        BlockPos secondary = findAdjacentChest(client, pos);
        bridge.addAdminChest(name, pos, secondary);
        bridge.clearPendingChestSelection();
        boolean saved = bridge.finalizeAdminMode();

        if (client.player != null) {
            String msg = saved
                    ? "§a[창고지기] ✅ '" + name + "' 저장됨 @ " + pos.toShortString()
                    : "§c[창고지기] ❌ '" + name + "' 저장 실패 @ " + pos.toShortString();
            if (secondary != null) {
                msg += " §7(더블체스트: " + secondary.toShortString() + ")";
            }
            client.player.sendMessage(Text.literal(msg), false);
            if (saved) {
                client.player.sendMessage(Text.literal(
                        "§7즉시 저장 완료 | 활성 chest " + bridge.getChestMap().size() + "개"), false);
            } else {
                client.player.sendMessage(Text.literal(
                        "§c[창고지기] 관리자 코드가 만료되었거나 서버 저장에 실패했습니다. 새 관리자 코드를 발급받아 다시 시도하세요."), false);
            }
        }
    }

    public static void tick(MinecraftClient client) {
        Screen current = client.currentScreen;

        // ① 상자가 방금 열렸는지 감지
        if ((prevScreen == null || !(prevScreen instanceof HandledScreen<?>))
                && current instanceof HandledScreen<?> hs
                && hs.getScreenHandler() instanceof GenericContainerScreenHandler container) {

            BlockPos pos = ChestBotMod.getBridge().consumeRecentChestInteractionPos();
            if (pos != null) {
                String name = ChestBotMod.getBridge().findByPos(pos);
                if (name != null) {
                    openChestName  = name;
                    beforeSnapshot = takeSnapshot(container);
                    System.out.printf("[ChestBot] '%s' 열림 — 스냅샷 %d종 @ %s%n",
                            name, beforeSnapshot.size(), pos.toShortString());
                } else {
                    System.out.printf("[ChestBot] chest 열림 감지됨, 하지만 등록되지 않은 위치입니다: %s%n",
                            pos.toShortString());
                }
            }
        }

        // ② 등록 상자가 방금 닫혔는지 감지
        if (openChestName != null
                && !(current instanceof HandledScreen<?>)
                && prevScreen instanceof HandledScreen<?> ph
                && ph.getScreenHandler() instanceof GenericContainerScreenHandler prevContainer) {

            String playerName = (client.player != null)
                    ? client.player.getName().getString() : "Unknown";

            Map<String, Integer> afterSnapshot = takeSnapshot(prevContainer);
            sendDiff(playerName, openChestName, beforeSnapshot, afterSnapshot);

            openChestName  = null;
            beforeSnapshot = null;
        }

        prevScreen = current;
    }

    private static Map<String, Integer> takeSnapshot(GenericContainerScreenHandler handler) {
        Map<String, Integer> snap = new LinkedHashMap<>();
        int rows = handler.getRows();
        for (int i = 0; i < rows * 9; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String displayName = stack.getName().getString();
            snap.merge(displayName, stack.getCount(), Integer::sum);
        }
        return snap;
    }

    private static void sendDiff(String player, String chestName,
                                 Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> taken = new LinkedHashMap<>();
        Map<String, Integer> added = new LinkedHashMap<>();

        Set<String> all = new LinkedHashSet<>();
        all.addAll(before.keySet());
        all.addAll(after.keySet());

        for (String item : all) {
            int diff = before.getOrDefault(item, 0) - after.getOrDefault(item, 0);
            if (diff > 0) taken.put(item, diff);
            else if (diff < 0) added.put(item, -diff);
        }

        System.out.printf("[ChestBot] '%s' 닫힘 — %s: 꺼냄 %d종, 넣음 %d종%n",
                chestName, player, taken.size(), added.size());

        ChestBotMod.getBridge().sendChestLog(player, chestName, taken, added);
    }

    private static BlockPos findAdjacentChest(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return null;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos adjacent = pos.offset(dir);
            Block adjacentBlock = client.world.getBlockState(adjacent).getBlock();
            if (adjacentBlock instanceof ChestBlock) {
                return adjacent;
            }
        }
        return null;
    }
}
