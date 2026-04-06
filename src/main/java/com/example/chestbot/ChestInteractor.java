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
import java.util.concurrent.CompletableFuture;

/**
 * 플레이어가 등록된 상자를 열고 닫을 때 인벤토리 변화를 감지한다.
 *
 * <p>등록된 chest 목록은 {@link BotBridge#findByPos(BlockPos)} 를 통해 조회한다.</p>
 *
 * <h3>라이선스 재검증 흐름</h3>
 * <ol>
 *   <li>등록된 상자 열림 감지 → 즉시 {@link BotBridge#reloadAsync()} 비동기 호출</li>
 *   <li>{@code SNAPSHOT_DELAY_TICKS} 동안 서버 응답 대기
 *       (이미 존재하는 딜레이이므로 게임 성능 영향 없음)</li>
 *   <li>응답 도달 시:
 *       <ul>
 *         <li>성공 → 스냅샷 확정, 정상 기록</li>
 *         <li>실패 (라이선스 비활성화 등) → 스냅샷 취소, 플레이어에게 메시지 표시</li>
 *       </ul>
 *   </li>
 *   <li>응답 전에 상자가 닫히면 → 대기 상태 전체 초기화</li>
 * </ol>
 */
public class ChestInteractor {

    private static Screen prevScreen    = null;
    private static String openChestName = null;
    private static Map<String, Integer> beforeSnapshot = null;

    // 상자 열린 직후 서버에서 인벤토리 데이터를 받기까지 대기하는 틱 수
    private static final int SNAPSHOT_DELAY_TICKS = 10;
    private static int ticksSinceOpen = 0;
    private static String pendingChestName = null;
    private static GenericContainerScreenHandler pendingHandler = null;

    // 등록 상자 열림 시 서버에 라이선스 재검증 요청
    private static CompletableFuture<Boolean> pendingValidation = null;

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

        String doubleChestSuffix = secondary != null ? " §7(더블체스트: " + secondary.toShortString() + ")" : "";
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§7[창고지기] '" + name + "' 저장 중..."), false);
        }
        bridge.finalizeAdminModeAsync().whenComplete((saved, err) -> {
            if (client.player == null) return;
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                if (err != null || !Boolean.TRUE.equals(saved)) {
                    client.player.sendMessage(Text.literal(
                            "§c[창고지기] ❌ '" + name + "' 저장 실패 @ " + pos.toShortString() + doubleChestSuffix), false);
                    client.player.sendMessage(Text.literal(
                            "§c[창고지기] 관리자 코드가 만료되었거나 서버 저장에 실패했습니다. 새 관리자 코드를 발급받아 다시 시도하세요."), false);
                } else {
                    client.player.sendMessage(Text.literal(
                            "§a[창고지기] ✅ '" + name + "' 저장됨 @ " + pos.toShortString() + doubleChestSuffix), false);
                    client.player.sendMessage(Text.literal(
                            "§7즉시 저장 완료 | 활성 chest " + bridge.getChestMap().size() + "개"), false);
                }
            });
        });
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
                    pendingChestName = name;
                    pendingHandler   = container;
                    ticksSinceOpen   = 0;
                    // 등록 상자가 열리는 순간 라이선스 재검증 비동기 시작
                    // reloadAsync()는 별도 스레드에서 HTTP 호출 → 게임 스레드 영향 없음
                    pendingValidation = ChestBotMod.getBridge().reloadAsync();
                    System.out.printf("[ChestBot] '%s' 열림 — 라이선스 재검증 중, %d틱 후 스냅샷 예정 @ %s%n",
                            name, SNAPSHOT_DELAY_TICKS, pos.toShortString());
                } else {
                    System.out.printf("[ChestBot] chest 열림 감지됨, 하지만 등록되지 않은 위치입니다: %s%n",
                            pos.toShortString());
                }
            }
        }

        // ① - a) 대기 중인데 상자가 닫힌 경우: 전체 초기화 (응답 전 닫기 대응)
        if (pendingChestName != null && !(current instanceof HandledScreen<?>)) {
            System.out.printf("[ChestBot] '%s' — 검증 대기 중 상자가 닫혔습니다. 초기화.%n", pendingChestName);
            cancelPending();
        }

        // ① - b) 지연 스냅샷: 대기 틱이 지나면 검증 결과 확인 후 beforeSnapshot 확정
        if (pendingChestName != null && current instanceof HandledScreen<?>) {
            ticksSinceOpen++;
            if (ticksSinceOpen >= SNAPSHOT_DELAY_TICKS) {
                if (pendingValidation != null && !pendingValidation.isDone()) {
                    // 검증 응답 아직 미도착 — 이번 틱은 대기 (prevScreen 업데이트는 정상 진행)
                } else {
                    // 검증 완료 (또는 검증 없음) — 결과 처리
                    boolean valid = true;
                    if (pendingValidation != null) {
                        try {
                            valid = Boolean.TRUE.equals(pendingValidation.get());
                        } catch (Exception ignored) {
                            valid = false;
                        }
                        pendingValidation = null;
                    }

                    if (!valid) {
                        if (client.player != null) {
                            boolean stillConnected = ChestBotMod.getBridge().isConnected();
                            String msg = stillConnected
                                    ? "§c[창고지기] 라이선스 검증 실패 — 창고 기록이 중단됩니다."
                                    : "§c[창고지기] 라이선스가 비활성화되었습니다. 연결 정보가 초기화되었으니 새 라이선스를 입력하세요.";
                            client.player.sendMessage(Text.literal(msg), false);
                        }
                        System.out.printf("[ChestBot] '%s' — 라이선스 검증 실패, 스냅샷 취소.%n", pendingChestName);
                        cancelPending();
                    } else {
                        openChestName  = pendingChestName;
                        beforeSnapshot = takeSnapshot(pendingHandler);
                        System.out.printf("[ChestBot] '%s' beforeSnapshot 확정 — %d종%n",
                                openChestName, beforeSnapshot.size());
                        pendingChestName = null;
                        pendingHandler   = null;
                        ticksSinceOpen   = 0;
                    }
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

            openChestName    = null;
            beforeSnapshot   = null;
            pendingChestName = null;
            pendingHandler   = null;
            ticksSinceOpen   = 0;
            pendingValidation = null;
        }

        prevScreen = current;
    }

    private static void cancelPending() {
        if (pendingValidation != null) {
            pendingValidation.cancel(false);
            pendingValidation = null;
        }
        pendingChestName = null;
        pendingHandler   = null;
        ticksSinceOpen   = 0;
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
