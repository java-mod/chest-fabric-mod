package com.example.chestbot;

import com.example.chestbot.compat.ItemStackVisualCompat;
import com.example.chestbot.hud.ChestHudItemVisuals;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerListener;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ChestInteractor {

    private static final long ACTION_SETTLE_MILLIS = 120L;
    private static final long ACTION_TIMEOUT_MILLIS = 500L;

    private static Screen prevScreen = null;
    private static ChestSession session = null;
    private static CompletableFuture<Boolean> pendingValidation = null;
    private static PendingLocalAction pendingLocalAction = null;

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
            MinecraftClient.getInstance().execute(() -> {
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

        if ((prevScreen == null || !(prevScreen instanceof HandledScreen<?>))
                && current instanceof HandledScreen<?> hs
                && hs.getScreenHandler() instanceof GenericContainerScreenHandler container) {

            BlockPos pos = ChestBotMod.getBridge().consumeRecentChestInteractionPos();
            if (pos != null) {
                String name = ChestBotMod.getBridge().findByPos(pos);
                if (name != null) {
                    startSession(client, name, pos, container);
                } else {
                    ChestBotMod.LOGGER.info("[ChestBot] chest 열림 감지됨, 하지만 등록되지 않은 위치입니다: {}",
                            pos.toShortString());
                }
            }
        }

        if (session != null) {
            maybeFlushPendingLocalAction(client);
            if (!(current instanceof HandledScreen<?>)) {
                finishSession(client, true);
            } else if (!(session.handler() == ((HandledScreen<?>) current).getScreenHandler())) {
                finishSession(client, true);
            } else {
                if (pendingValidation != null && pendingValidation.isDone()) {
                    boolean valid = true;
                    try {
                        valid = Boolean.TRUE.equals(pendingValidation.get());
                    } catch (Exception ignored) {
                        valid = false;
                    }
                    pendingValidation = null;

                    if (!valid) {
                        if (client.player != null) {
                            boolean stillConnected = ChestBotMod.getBridge().isConnected();
                            String msg = stillConnected
                                    ? "§c[창고지기] 연결 재동기화 실패 — 창고 기록이 중단됩니다."
                                    : "§c[창고지기] 서버 연결이 해제되었습니다. /창고봇 연결 로 다시 연결하세요.";
                            client.player.sendMessage(Text.literal(msg), false);
                        }
                        ChestBotMod.LOGGER.info("[ChestBot] '{}' — 연결 재동기화 실패, 세션 취소.", session.chestName());
                        finishSession(client, false);
                    }
                }
            }
        }

        prevScreen = current;
    }

    private static void startSession(MinecraftClient client, String chestName, BlockPos pos, GenericContainerScreenHandler handler) {
        finishSession(client, false);

        ChestSession nextSession = new ChestSession(chestName, handler, handler.syncId, handler.getRows() * 9);
        nextSession.seedFromHandler();
        nextSession.attach();
        session = nextSession;
        pendingValidation = ChestBotMod.getBridge().reloadAsync();

        ChestBotMod.LOGGER.info("[ChestBot] '{}' 열림 — baseline 즉시 시드 완료 ({}종) @ {}",
                chestName, nextSession.baselineSnapshot().size(), pos.toShortString());
    }

    private static void finishSession(MinecraftClient client, boolean sendDiff) {
        ChestSession currentSession = session;
        if (currentSession == null) {
            return;
        }

        currentSession.detach();

        if (sendDiff && currentSession.baselineReady()) {
            ChestBotMod.LOGGER.info("[ChestBot] '{}' 닫힘 — close-time diff 귀속은 비활성화됨", currentSession.chestName());
        } else if (sendDiff) {
            ChestBotMod.LOGGER.info("[ChestBot] '{}' 닫힘 — baseline 미완성으로 diff 생략", currentSession.chestName());
        }

        session = null;
        if (pendingValidation != null) {
            pendingValidation.cancel(false);
            pendingValidation = null;
        }
        pendingLocalAction = null;
    }

    public static void onLocalSlotActionStart(int syncId) {
        maybeFlushPendingLocalAction(MinecraftClient.getInstance());
        ChestSession currentSession = session;
        if (currentSession == null || !currentSession.baselineReady() || currentSession.syncId() != syncId) {
            pendingLocalAction = null;
            return;
        }

        pendingLocalAction = new PendingLocalAction(syncId, currentSession.chestName(), takeSnapshot(currentSession.handler()), takeVisualSnapshot(MinecraftClient.getInstance(), currentSession.handler()));
    }

    public static void onLocalSlotActionEnd(int syncId) {
        PendingLocalAction action = pendingLocalAction;
        if (action == null || action.syncId != syncId) {
            pendingLocalAction = null;
            return;
        }
        action.markSent();
    }

    private static void maybeFlushPendingLocalAction(MinecraftClient client) {
        PendingLocalAction action = pendingLocalAction;
        ChestSession currentSession = session;
        if (action == null || client == null || currentSession == null) {
            return;
        }
        if (!action.sent || currentSession.syncId() != action.syncId) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean timedOut = now - action.startedAtMillis >= ACTION_TIMEOUT_MILLIS;
        boolean settled = action.lastServerUpdateAtMillis > 0L && now - action.lastServerUpdateAtMillis >= ACTION_SETTLE_MILLIS;
        if (!timedOut && !settled) {
            return;
        }

        String playerName = client.player != null ? client.player.getName().getString() : "Unknown";
        Map<String, Integer> afterSnapshot = takeSnapshot(currentSession.handler());
        Map<String, String> afterVisuals = takeVisualSnapshot(client, currentSession.handler());
        sendDiff(playerName, action.chestName, action.beforeSnapshot, action.beforeVisuals, afterSnapshot, afterVisuals);
        pendingLocalAction = null;
    }

    private static Map<String, Integer> takeSnapshot(GenericContainerScreenHandler handler) {
        Map<String, Integer> snap = new LinkedHashMap<>();
        int rows = handler.getRows();
        for (int i = 0; i < rows * 9; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String displayName = ChestHudItemVisuals.formatItemName(stack.getName().getString());
            snap.merge(displayName, stack.getCount(), Integer::sum);
        }
        return snap;
    }

    private static Map<String, String> takeVisualSnapshot(MinecraftClient client, GenericContainerScreenHandler handler) {
        Map<String, String> snap = new LinkedHashMap<>();
        int rows = handler.getRows();
        for (int i = 0; i < rows * 9; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            String displayName = ChestHudItemVisuals.formatItemName(stack.getName().getString());
            snap.putIfAbsent(displayName, ItemStackVisualCompat.serialize(client, stack));
        }
        return snap;
    }

    private static void sendDiff(String player, String chestName,
                                 Map<String, Integer> before,
                                 Map<String, String> beforeVisuals,
                                 Map<String, Integer> after,
                                 Map<String, String> afterVisuals) {
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

        if (taken.isEmpty() && added.isEmpty()) {
            return;
        }

        ChestBotMod.LOGGER.info("[ChestBot] '{}' 닫힘 — {}: 꺼냄 {}종, 넣음 {}종",
                chestName, player, taken.size(), added.size());

        Map<String, String> takenVisuals = filterVisuals(taken, beforeVisuals);
        Map<String, String> addedVisuals = filterVisuals(added, afterVisuals);
        String takenVisual = firstVisualFor(taken, beforeVisuals);
        String addedVisual = firstVisualFor(added, afterVisuals);
        ChestBotMod.getBridge().rememberRecentChestHudEntry(player, chestName, taken, added, takenVisuals, addedVisuals);
        ChestBotMod.getBridge().sendChestLog(player, chestName, taken, added, takenVisual, addedVisual);
    }

    private static Map<String, String> filterVisuals(Map<String, Integer> diff, Map<String, String> visuals) {
        Map<String, String> filtered = new LinkedHashMap<>();
        if (diff == null || diff.isEmpty() || visuals == null || visuals.isEmpty()) {
            return filtered;
        }
        for (String key : diff.keySet()) {
            String visual = visuals.get(key);
            if (visual != null && !visual.isBlank()) {
                filtered.put(key, visual);
            }
        }
        return filtered;
    }

    private static String firstVisualFor(Map<String, Integer> diff, Map<String, String> visuals) {
        if (diff == null || diff.isEmpty() || visuals == null || visuals.isEmpty()) {
            return null;
        }
        for (String key : diff.keySet()) {
            String visual = visuals.get(key);
            if (visual != null && !visual.isBlank()) {
                return visual;
            }
        }
        return null;
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

    private static final class ChestSession implements ScreenHandlerListener {
        private final String chestName;
        private final GenericContainerScreenHandler handler;
        private final int syncId;
        private final int containerSlotCount;
        private final ItemStack[] baselineSlots;
        private final ItemStack[] currentSlots;
        private final boolean[] seenSlots;
        private boolean attached;
        private boolean baselineReady;

        private ChestSession(String chestName, GenericContainerScreenHandler handler, int syncId, int containerSlotCount) {
            this.chestName = chestName;
            this.handler = handler;
            this.syncId = syncId;
            this.containerSlotCount = containerSlotCount;
            this.baselineSlots = new ItemStack[containerSlotCount];
            this.currentSlots = new ItemStack[containerSlotCount];
            this.seenSlots = new boolean[containerSlotCount];
            Arrays.fill(this.baselineSlots, ItemStack.EMPTY);
            Arrays.fill(this.currentSlots, ItemStack.EMPTY);
        }

        private void attach() {
            if (!attached) {
                handler.addListener(this);
                attached = true;
            }
        }

        private void seedFromHandler() {
            for (int slotId = 0; slotId < containerSlotCount; slotId++) {
                ItemStack stack = handler.getSlot(slotId).getStack();
                ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
                baselineSlots[slotId] = copy.copy();
                currentSlots[slotId] = copy;
                seenSlots[slotId] = true;
            }
            baselineReady = true;
        }

        private void detach() {
            if (attached) {
                handler.removeListener(this);
                attached = false;
            }
        }

        private String chestName() {
            return chestName;
        }

        private int syncId() {
            return syncId;
        }

        private GenericContainerScreenHandler handler() {
            return handler;
        }

        private boolean baselineReady() {
            return baselineReady;
        }

        private Map<String, Integer> baselineSnapshot() {
            return snapshotFromStacks(baselineSlots);
        }

        private Map<String, String> baselineVisuals() {
            return visualSnapshotFromStacks(baselineSlots);
        }

        @Override
        public void onSlotUpdate(ScreenHandler screenHandler, int slotId, ItemStack stack) {
            if (screenHandler != handler || handler.syncId != syncId) {
                return;
            }
            if (slotId < 0 || slotId >= containerSlotCount) {
                return;
            }

            ItemStack copy = stack == null ? ItemStack.EMPTY : stack.copy();
            currentSlots[slotId] = copy;
            if (pendingLocalAction != null && pendingLocalAction.syncId == syncId && pendingLocalAction.sent) {
                pendingLocalAction.markServerUpdate();
            }
            if (!seenSlots[slotId]) {
                baselineSlots[slotId] = copy.copy();
                seenSlots[slotId] = true;
                if (!baselineReady && allSlotsSeen()) {
                    baselineReady = true;
                    ChestBotMod.LOGGER.info("[ChestBot] '{}' baseline 확정 — {}슬롯 초기 동기화 완료",
                            chestName, containerSlotCount);
                }
            }
        }

        @Override
        public void onPropertyUpdate(ScreenHandler handler, int property, int value) {
        }

        private boolean allSlotsSeen() {
            for (boolean seen : seenSlots) {
                if (!seen) {
                    return false;
                }
            }
            return true;
        }

        private Map<String, Integer> snapshotFromStacks(ItemStack[] stacks) {
            Map<String, Integer> snap = new LinkedHashMap<>();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) continue;
                String displayName = ChestHudItemVisuals.formatItemName(stack.getName().getString());
                snap.merge(displayName, stack.getCount(), Integer::sum);
            }
            return snap;
        }

        private Map<String, String> visualSnapshotFromStacks(ItemStack[] stacks) {
            Map<String, String> snap = new LinkedHashMap<>();
            MinecraftClient client = MinecraftClient.getInstance();
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) continue;
                String displayName = ChestHudItemVisuals.formatItemName(stack.getName().getString());
                snap.putIfAbsent(displayName, ItemStackVisualCompat.serialize(client, stack));
            }
            return snap;
        }
    }

    private static final class PendingLocalAction {
        private final int syncId;
        private final String chestName;
        private final Map<String, Integer> beforeSnapshot;
        private final Map<String, String> beforeVisuals;
        private final long startedAtMillis;
        private long lastServerUpdateAtMillis;
        private boolean sent;

        private PendingLocalAction(int syncId, String chestName, Map<String, Integer> beforeSnapshot, Map<String, String> beforeVisuals) {
            this.syncId = syncId;
            this.chestName = chestName;
            this.beforeSnapshot = beforeSnapshot;
            this.beforeVisuals = beforeVisuals;
            this.startedAtMillis = System.currentTimeMillis();
            this.lastServerUpdateAtMillis = 0L;
            this.sent = false;
        }

        private void markSent() {
            this.sent = true;
        }

        private void markServerUpdate() {
            this.lastServerUpdateAtMillis = System.currentTimeMillis();
        }
    }
}
