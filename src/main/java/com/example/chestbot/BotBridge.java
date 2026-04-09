package com.example.chestbot;

import com.example.chestbot.hud.ChestHudItemVisuals;
import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 서버와의 HTTP 통신 및 island 설정을 관리한다.
 *
 * <p>설정 파일: {@code config/chestbot.json}</p>
 * <ul>
 *   <li>{@code server_url} — 백엔드 서버 주소</li>
 * </ul>
 */
public class BotBridge {

    private static final String DEFAULT_URL = "https://chestbot.kro.kr";
    private static final long HUD_REFRESH_INTERVAL_MILLIS = 10_000L;

    // ── 설정 (파일에서 로드) ────────────────────────────────────
    private String serverUrl;

    // ── 런타임 상태 ─────────────────────────────────────────────
    private String islandName;
    private long configVersion;
    private final Map<String, BlockPos> chestMap = new LinkedHashMap<>();
    private String lastConnectError;
    private boolean hudEnabled = true;
    private int hudX = 8;
    private int hudY = 8;
    private float hudScale = 1.0F;
    private boolean hudEditMode;
    private RecentChestHudEntry recentChestHudEntry;
    private final List<MemberHudEntry> memberHudEntries = new ArrayList<>();
    private final Map<String, LocalHudHolding> localHudHoldings = new LinkedHashMap<>();
    private CompletableFuture<Boolean> pendingReloadFuture;
    private long lastHudRefreshRequestedAtMillis;
    private final AtomicLong connectionVersion = new AtomicLong();

    // ── 관리자 모드 상태 ────────────────────────────────────────
    private boolean adminMode;
    private String adminIslandName;
    private String pendingAdminCode;
    private String pendingChestSelectionName;
    private BlockPos lastInteractedChestPos;
    private long lastInteractedChestAtMillis;
    private final List<ChestDef> pendingChests = new ArrayList<>();
    private PendingBankTransaction pendingBankTransaction;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public BotBridge() {
        loadConfig();
    }

    // ── 서버 연결 시 자동 bootstrap ─────────────────────────────

    public void start() {
        long version = connectionVersion.get();
        connect(version);
    }

    public void startAsync() {
        long version = connectionVersion.get();
        CompletableFuture.supplyAsync(() -> connect(version));
    }

    public void stop() {
        chestMap.clear();
        islandName = null;
        adminMode = false;
        adminIslandName = null;
        pendingAdminCode = null;
        pendingChests.clear();
        lastInteractedChestPos = null;
        lastInteractedChestAtMillis = 0L;
        pendingChestSelectionName = null;
        pendingBankTransaction = null;
        recentChestHudEntry = null;
        memberHudEntries.clear();
        localHudHoldings.clear();
        pendingReloadFuture = null;
        lastHudRefreshRequestedAtMillis = 0L;
    }

    // ── 서버 기준 섬 연결 ────────────────────────────────────────

    public boolean connect() {
        long version = connectionVersion.incrementAndGet();
        return connect(version);
    }

    private boolean connect(long version) {
        HttpResult result = postNoBody("/api/v1/client/connect");
        if (!result.success()) {
            log(result.describeFailure());
            if (result.statusCode() == 403) {
                clearAndSaveConfig();
                log("서버에서 연결이 거부되어 연결 정보를 초기화했습니다.");
            }
            return false;
        }

        try {
            JsonObject json = JsonParser.parseString(result.body()).getAsJsonObject();
            String nextIslandName = json.get("islandName").getAsString();
            long nextConfigVersion = json.get("configVersion").getAsLong();

            LinkedHashMap<String, BlockPos> nextChestMap = new LinkedHashMap<>();
            JsonArray chests = json.getAsJsonArray("chests");
            for (JsonElement el : chests) {
                JsonObject c = el.getAsJsonObject();
                nextChestMap.put(
                        c.get("chestKey").getAsString(),
                        new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt())
                );
            }
            List<MemberHudEntry> nextMemberEntries = parseMemberHudEntries(json);

            if (!commitConnectionState(version,
                    nextIslandName, nextConfigVersion, nextChestMap, nextMemberEntries)) {
                return false;
            }

            log("연결 성공! 섬: " + islandName + " | chest " + chestMap.size() + "개 (버전 " + configVersion + ")");
            return true;
        } catch (Exception e) {
            log("연결 응답 파싱 오류: " + e.getMessage());
            return false;
        }
    }

    /** 403 수신 시 호출 — 모든 연결 정보를 초기화하고 config 파일을 저장한다. */
    private void clearAndSaveConfig() {
        islandName    = null;
        configVersion = 0L;
        chestMap.clear();
        memberHudEntries.clear();
        saveConfig();
    }

    public CompletableFuture<Boolean> connectAsync() {
        long version = connectionVersion.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> connect(version));
    }

    // ── 관리자 모드 ──────────────────────────────────────────────

    public boolean enterAdminMode(String adminCode) {
        String normalizedAdminCode = normalizeAdminCode(adminCode);
        if (normalizedAdminCode == null || normalizedAdminCode.isBlank()) {
            log("관리자 코드가 비어 있습니다.");
            return false;
        }

        JsonObject body = new JsonObject();
        body.addProperty("adminCode", normalizedAdminCode);

        String response = postSync("/api/v1/client/admin/connect", body);
        if (response == null) return false;

        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            adminIslandName  = json.get("islandName").getAsString();
            pendingAdminCode = normalizedAdminCode;
            adminMode        = true;
            pendingChestSelectionName = null;
            pendingChests.clear();
            log("관리자 모드 진입: 섬 '" + adminIslandName + "'");
            return true;
        } catch (Exception e) {
            log("관리자 연결 오류: " + e.getMessage());
            return false;
        }
    }

    public CompletableFuture<Boolean> enterAdminModeAsync(String adminCode) {
        return CompletableFuture.supplyAsync(() -> enterAdminMode(adminCode));
    }

    public CompletableFuture<Boolean> finalizeAdminModeAsync() {
        return CompletableFuture.supplyAsync(this::finalizeAdminMode);
    }

    public void addAdminChest(String name, BlockPos pos, BlockPos secondaryPos) {
        pendingChests.removeIf(c -> c.name.equals(name));
        pendingChests.add(new ChestDef(name, pos, secondaryPos));
        String msg = "추가: " + name + " @ " + pos.toShortString();
        if (secondaryPos != null) msg += " (더블체스트)";
        log(msg);
    }

    public void beginChestSelection(String name) {
        pendingChestSelectionName = name == null ? null : name.trim();
        if (pendingChestSelectionName != null && !pendingChestSelectionName.isBlank()) {
            log("선택 대기: '" + pendingChestSelectionName + "' 상자를 클릭하세요.");
        }
    }

    public String getPendingChestSelectionName() {
        return pendingChestSelectionName;
    }

    public void clearPendingChestSelection() {
        pendingChestSelectionName = null;
    }

    public boolean hasPendingChestSelection() {
        return pendingChestSelectionName != null && !pendingChestSelectionName.isBlank();
    }

    public void recordChestInteraction(BlockPos pos) {
        if (pos == null) {
            return;
        }
        lastInteractedChestPos = pos.toImmutable();
        lastInteractedChestAtMillis = System.currentTimeMillis();
    }

    public BlockPos consumeRecentChestInteractionPos() {
        if (lastInteractedChestPos == null) {
            return null;
        }
        long ageMillis = System.currentTimeMillis() - lastInteractedChestAtMillis;
        if (ageMillis > 5000L) {
            lastInteractedChestPos = null;
            lastInteractedChestAtMillis = 0L;
            return null;
        }
        BlockPos pos = lastInteractedChestPos;
        lastInteractedChestPos = null;
        lastInteractedChestAtMillis = 0L;
        return pos;
    }

    public boolean removeAdminChest(String name) {
        boolean clearedSelection = pendingChestSelectionName != null && pendingChestSelectionName.equals(name);
        if (clearedSelection) {
            pendingChestSelectionName = null;
        }
        int before = pendingChests.size();
        pendingChests.removeIf(c -> c.name.equals(name));
        boolean removed = before != pendingChests.size() || clearedSelection;
        if (removed) {
            log("제거: " + name);
        }
        return removed;
    }

    public boolean finalizeAdminMode() {
        JsonObject body = new JsonObject();
        body.addProperty("adminCode", pendingAdminCode);

        JsonArray chestArray = new JsonArray();
        for (ChestDef c : pendingChests) {
            JsonObject obj = new JsonObject();
            obj.addProperty("chestKey", c.name);
            obj.addProperty("displayName", c.name);
            obj.addProperty("x", c.primary.getX());
            obj.addProperty("y", c.primary.getY());
            obj.addProperty("z", c.primary.getZ());
            chestArray.add(obj);
        }
        body.add("chests", chestArray);

        String response = postSync("/api/v1/client/admin/finalize", body);
        if (response == null) return false;

        // 완료 후 새 설정으로 로컬 업데이트
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            configVersion = json.get("configVersion").getAsLong();
            chestMap.clear();
            JsonArray chests = json.getAsJsonArray("chests");
            for (JsonElement el : chests) {
                JsonObject c = el.getAsJsonObject();
                chestMap.put(
                        c.get("chestKey").getAsString(),
                        new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt())
                );
            }
            replaceMemberHudEntries(json);
            log("완료! chest " + chestMap.size() + "개 저장됨 (버전 " + configVersion + ")");
        } catch (Exception ignored) {}

        adminMode        = false;
        pendingAdminCode = null;
        pendingChestSelectionName = null;
        pendingChests.clear();
        return true;
    }

    // ── chest 이벤트 전송 ────────────────────────────────────────

    public void sendChestLog(String player, String chestName,
                             Map<String, Integer> taken, Map<String, Integer> added,
                             String takenVisualData, String addedVisualData) {
        if (!isConnected()) {
            log("chest 로그 스킵: 섬 연결 정보가 없습니다.");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("configVersion", configVersion);
        body.addProperty("playerName",    player);
        body.addProperty("playerUuid", collectCurrentPlayerProfile(player).has("uuid") ? collectCurrentPlayerProfile(player).get("uuid").getAsString() : "");
        body.addProperty("chestKey",      chestName);
        if (takenVisualData != null) body.addProperty("takenVisualData", takenVisualData);
        if (addedVisualData != null) body.addProperty("addedVisualData", addedVisualData);
        body.add("taken", toJsonObject(taken));
        body.add("added", toJsonObject(added));
        appendCurrentPlayerProfile(body, player);

        postAsync("/api/v1/client/events/chest-log", body,
                "chest-log(" + chestName + ", player=" + player + ")");
    }

    public void rememberPendingBankTransaction(String playerName, String transactionType, String amountText, String sourceMessage) {
        pendingBankTransaction = new PendingBankTransaction(playerName, transactionType, amountText, sourceMessage, System.currentTimeMillis());
    }

    public boolean hasPendingBankTransaction() {
        return pendingBankTransaction != null;
    }

    public String getPendingBankTransactionAmountText() {
        return pendingBankTransaction == null ? null : pendingBankTransaction.amountText();
    }

    public String getPendingBankTransactionType() {
        return pendingBankTransaction == null ? null : pendingBankTransaction.transactionType();
    }

    public void clearPendingBankTransaction() {
        pendingBankTransaction = null;
    }

    public void sendIslandBankLog(String playerName, String transactionType, String amountText, String reason, String sourceMessage, boolean awaitingReason) {
        if (!isConnected()) {
            log("섬 은행 로그 스킵: 섬 연결 정보가 없습니다.");
            return;
        }

        if (awaitingReason) {
            return;
        }

        Long amount = parseAmount(amountText);
        if (amount == null || amount <= 0L) {
            log("입금 로그 스킵: 금액 파싱 실패 (amountText=" + amountText + ")");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("playerName", playerName);
        body.addProperty("transactionType", transactionType);
        body.addProperty("amount", amount);
        body.add("balanceAfter", JsonNull.INSTANCE);
        if (reason != null && !reason.isBlank()) {
            body.addProperty("note", reason.trim());
        } else if (sourceMessage != null && !sourceMessage.isBlank()) {
            body.addProperty("note", sourceMessage.trim());
        } else {
            body.add("note", JsonNull.INSTANCE);
        }
        appendCurrentPlayerProfile(body, playerName);

        postAsync("/api/v1/client/events/island-bank-log", body,
                "bank-log(player=" + playerName + ", type=" + transactionType + ", amount=" + amountText + ")");
    }

    public boolean submitPendingBankTransactionReason(String reason) {
        PendingBankTransaction current = pendingBankTransaction;
        if (current == null) {
            return false;
        }

        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank()) {
            return false;
        }

        sendIslandBankLog(current.playerName(), current.transactionType(), current.amountText(), trimmedReason, current.sourceMessage(), false);
        pendingBankTransaction = null;
        return true;
    }

    // ── chest 조회 (ChestInteractor에서 사용) ────────────────────

    public String findByPos(BlockPos opened) {
        for (Map.Entry<String, BlockPos> e : chestMap.entrySet()) {
            BlockPos reg = e.getValue();
            if (reg.getY() == opened.getY() && reg.getManhattanDistance(opened) <= 1) {
                return e.getKey();
            }
        }
        return null;
    }

    public Map<String, BlockPos> getChestMap() {
        return Collections.unmodifiableMap(chestMap);
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean hudEnabled) {
        this.hudEnabled = hudEnabled;
        saveConfig();
    }

    public int getHudX() {
        return hudX;
    }

    public int getHudY() {
        return hudY;
    }

    public float getHudScale() {
        return hudScale;
    }

    public boolean isHudEditMode() {
        return hudEditMode;
    }

    public void setHudPosition(int x, int y) {
        applyHudLayout(x, y, hudScale);
        saveConfig();
    }

    public void setHudScale(float scale) {
        applyHudLayout(hudX, hudY, scale);
        saveConfig();
    }

    public void applyHudLayout(int x, int y, float scale) {
        this.hudX = Math.max(0, x);
        this.hudY = Math.max(0, y);
        this.hudScale = clamp(scale, 0.5F, 3.0F);
    }

    public void persistHudLayout() {
        saveConfig();
    }

    public void setHudEditMode(boolean hudEditMode) {
        this.hudEditMode = hudEditMode;
    }

    public Optional<RecentChestHudEntry> getRecentChestHudEntry() {
        return Optional.ofNullable(recentChestHudEntry);
    }

    public List<MemberHudEntry> getHudEntries() {
        LinkedHashMap<String, MemberHudEntry> merged = new LinkedHashMap<>();

        for (MemberHudEntry entry : memberHudEntries) {
            if (entry == null || entry.playerName() == null || entry.playerName().isBlank()) {
                continue;
            }
            merged.put(normalizeHudEntryKey(entry.playerName()), entry);
        }

        getRecentChestHudEntry()
                .map(this::toMemberHudEntry)
                .ifPresent(entry -> merged.put(normalizeHudEntryKey(entry.playerName()), entry));

        return List.copyOf(merged.values());
    }

    public void rememberRecentChestHudEntry(String playerName, String chestName, Map<String, Integer> taken, Map<String, Integer> added,
                                            String takenVisualData, String addedVisualData) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        applyLocalHudTaken(chestName, taken, takenVisualData);
        applyLocalHudAdded(added);
        rebuildRecentHudEntry(playerName);
    }

    private void applyLocalHudTaken(String chestName, Map<String, Integer> diff, String visualData) {
        if (diff == null || diff.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Integer> entry : diff.entrySet()) {
            String itemName = ChestHudItemVisuals.formatItemName(entry.getKey());
            int count = Math.max(entry.getValue() == null ? 0 : entry.getValue(), 0);
            if (count <= 0) {
                continue;
            }

            LocalHudHolding holding = localHudHoldings.getOrDefault(itemName, new LocalHudHolding(itemName, 0, visualData, chestName, System.currentTimeMillis()));
            int nextCount = holding.netCount() + count;

            String nextVisual = visualData != null && !visualData.isBlank() ? visualData : holding.itemVisualData();
            String nextChest = chestName != null && !chestName.isBlank() ? chestName : holding.chestName();
            localHudHoldings.put(itemName, new LocalHudHolding(itemName, nextCount, nextVisual, nextChest, System.currentTimeMillis()));
        }
    }

    private void applyLocalHudAdded(Map<String, Integer> diff) {
        if (diff == null || diff.isEmpty() || localHudHoldings.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Integer> entry : diff.entrySet()) {
            String itemName = ChestHudItemVisuals.formatItemName(entry.getKey());
            LocalHudHolding holding = localHudHoldings.get(itemName);
            if (holding == null) {
                continue;
            }

            int count = Math.max(entry.getValue() == null ? 0 : entry.getValue(), 0);
            if (count <= 0) {
                continue;
            }

            int nextCount = holding.netCount() - count;
            if (nextCount <= 0) {
                localHudHoldings.remove(itemName);
                continue;
            }

            localHudHoldings.put(itemName, new LocalHudHolding(
                    holding.itemName(),
                    nextCount,
                    holding.itemVisualData(),
                    holding.chestName(),
                    System.currentTimeMillis()
            ));
        }
    }

    private void rebuildRecentHudEntry(String playerName) {
        if (localHudHoldings.isEmpty()) {
            recentChestHudEntry = null;
            return;
        }

        List<LocalHudHolding> sortedHoldings = localHudHoldings.values().stream()
                .sorted(Comparator.comparingLong(LocalHudHolding::updatedAtMillis).reversed()
                        .thenComparing(Comparator.comparingInt(LocalHudHolding::netCount).reversed())
                        .thenComparing(LocalHudHolding::itemName))
                .toList();
        List<HudRotationItem> rotationItems = sortedHoldings.stream()
                .map(holding -> new HudRotationItem(
                        holding.itemName(),
                        holding.netCount(),
                        holding.itemVisualData()))
                .toList();
        if (sortedHoldings.isEmpty() || rotationItems.isEmpty()) {
            recentChestHudEntry = null;
            return;
        }

        LocalHudHolding representative = sortedHoldings.getFirst();

        int totalKinds = localHudHoldings.size();
        int totalItems = localHudHoldings.values().stream().mapToInt(LocalHudHolding::netCount).sum();
        recentChestHudEntry = new RecentChestHudEntry(
                playerName,
                representative.chestName(),
                representative.itemName(),
                representative.netCount(),
                totalKinds,
                totalItems,
                representative.itemVisualData(),
                rotationItems,
                currentPlayerSkinTexture(),
                System.currentTimeMillis()
        );
    }

    private MemberHudEntry toMemberHudEntry(RecentChestHudEntry entry) {
        return new MemberHudEntry(
                entry.playerName(),
                entry.itemName(),
                entry.itemCount(),
                entry.totalKinds(),
                entry.totalItems(),
                entry.itemVisualData(),
                entry.rotationItems(),
                entry.chestName(),
                entry.skinTexture(),
                null,
                entry.createdAtMillis()
        );
    }

    // ── 상태 정보 ────────────────────────────────────────────────

    public boolean isConnected()    { return islandName != null; }
    public boolean isAdminMode()    { return adminMode; }
    public String  getServerUrl()   { return serverUrl; }
    public String  getIslandName()  { return islandName; }
    public String  getLastConnectError() { return lastConnectError; }
    public String  getAdminIslandName() { return adminIslandName; }
    public long    getConfigVersion() { return configVersion; }
    public List<ChestDef> getPendingChests() { return Collections.unmodifiableList(pendingChests); }

    // ── 설정 파일 ────────────────────────────────────────────────

    public boolean reload() {
        long version = connectionVersion.get();
        return connect(version);
    }

    public boolean setServerUrl(String nextUrl) {
        if (nextUrl == null) {
            return false;
        }

        String normalized = normalizeServerUrl(nextUrl);
        if (normalized == null) {
            return false;
        }

        serverUrl = normalized;
        stop();
        saveConfig();
        return true;
    }

    public void resetServerUrl() {
        serverUrl = DEFAULT_URL;
        stop();
        saveConfig();
    }

    public CompletableFuture<Boolean> reloadAsync() {
        synchronized (this) {
            if (pendingReloadFuture != null && !pendingReloadFuture.isDone()) {
                return pendingReloadFuture;
            }

            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(this::reload);
            pendingReloadFuture = future;
            future.whenComplete((result, error) -> {
                synchronized (BotBridge.this) {
                    if (pendingReloadFuture == future) {
                        pendingReloadFuture = null;
                    }
                }
            });
            return future;
        }
    }

    public void tickHudRefresh() {
        if (!isConnected() || !hudEnabled || adminMode) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastHudRefreshRequestedAtMillis < HUD_REFRESH_INTERVAL_MILLIS) {
            return;
        }

        lastHudRefreshRequestedAtMillis = now;
        reloadAsync();
    }

    private void loadConfig() {
        Path path = Path.of("config", "chestbot.json");
        serverUrl  = DEFAULT_URL;
        hudEnabled = true;
        hudX = 8;
        hudY = 8;
        hudScale = 1.0F;

        if (Files.exists(path)) {
            try {
                JsonObject cfg = JsonParser.parseReader(new FileReader(path.toFile())).getAsJsonObject();
                if (cfg.has("server_url")) {
                    serverUrl = cfg.get("server_url").getAsString().trim();
                }
                if (cfg.has("hud_enabled") && !cfg.get("hud_enabled").isJsonNull()) {
                    hudEnabled = cfg.get("hud_enabled").getAsBoolean();
                }
                if (cfg.has("hud_x") && !cfg.get("hud_x").isJsonNull()) {
                    hudX = Math.max(0, cfg.get("hud_x").getAsInt());
                }
                if (cfg.has("hud_y") && !cfg.get("hud_y").isJsonNull()) {
                    hudY = Math.max(0, cfg.get("hud_y").getAsInt());
                }
                if (cfg.has("hud_scale") && !cfg.get("hud_scale").isJsonNull()) {
                    hudScale = clamp(cfg.get("hud_scale").getAsFloat(), 0.5F, 3.0F);
                }
            } catch (Exception e) {
                log("config 로드 오류: " + e.getMessage());
            }
        } else {
            saveConfig();
            log("config/chestbot.json 생성됨. /창고봇 서버 <주소> 후 /창고봇 연결 을 사용하세요.");
        }
    }

    private void saveConfig() {
        Path path = Path.of("config", "chestbot.json");
        try {
            Files.createDirectories(path.getParent());
            JsonObject cfg = new JsonObject();
            cfg.addProperty("server_url", serverUrl);
            cfg.addProperty("hud_enabled", hudEnabled);
            cfg.addProperty("hud_x", hudX);
            cfg.addProperty("hud_y", hudY);
            cfg.addProperty("hud_scale", hudScale);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(cfg));
        } catch (IOException e) {
            log("config 저장 오류: " + e.getMessage());
        }
    }

    private static String normalizeServerUrl(String rawUrl) {
        String trimmed = rawUrl == null ? null : rawUrl.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }

        String normalized = trimmed;
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            if (!looksLikeHostOrIp(normalized)) {
                return null;
            }
            normalized = "http://" + normalized;
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private static boolean looksLikeHostOrIp(String value) {
        if (value == null || value.isBlank() || value.contains(" ") || value.contains("/")) {
            return false;
        }

        return value.equalsIgnoreCase("localhost")
                || value.matches("\\d{1,3}(\\.\\d{1,3}){3}(:\\d{1,5})?")
                || value.matches("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(:\\d{1,5})?");
    }

    // ── HTTP ─────────────────────────────────────────────────────

    private String postSync(String path, JsonObject body) {
        HttpResult result = postJson(path, body);
        if (!result.success()) {
            log(result.describeFailure());
            return null;
        }
        return result.body();
    }

    private HttpResult postNoBody(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + path))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(path, res.statusCode(), res.body(), null);
        } catch (Exception e) {
            return new HttpResult(path, -1, null, e.getMessage());
        }
    }

    private HttpResult postJson(String path, JsonObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + path))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new HttpResult(path, res.statusCode(), res.body(), null);
        } catch (Exception e) {
            return new HttpResult(path, -1, null, e.getMessage());
        }
    }

    private void postAsync(String path, JsonObject body, String context) {
        CompletableFuture.runAsync(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                HttpResult result = postJson(path, body);
                if (result.success()) {
                    if (attempt > 1) {
                        log(context + " 재시도 성공 (" + attempt + "/3)");
                    }
                    return;
                }

                // 403: 서버에서 연결 거부 — 재시도 없이 즉시 초기화
                if (result.statusCode() == 403) {
                    log(context + " 전송 실패: 서버가 연결을 거부했습니다. 연결 정보를 초기화합니다.");
                    clearAndSaveConfig();
                    return;
                }

                log(context + " 전송 실패 (" + attempt + "/3): " + result.describeFailure());
                if (!result.shouldRetry() || attempt == 3) {
                    return;
                }

                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log(context + " 재시도 대기 중 인터럽트됨: " + e.getMessage());
                    return;
                }
            }
        }).exceptionally(e -> { log("비동기 전송 오류: " + e.getMessage()); return null; });
    }

    private static JsonObject toJsonObject(Map<String, Integer> map) {
        JsonObject obj = new JsonObject();
        map.forEach(obj::addProperty);
        return obj;
    }

    private List<MemberHudEntry> parseMemberHudEntries(JsonObject json) {
        List<MemberHudEntry> parsedEntries = new ArrayList<>();
        JsonArray members = firstArray(json,
                "hudMembers",
                "members",
                "memberHudEntries",
                "hudEntries");
        if (members == null) {
            return parsedEntries;
        }

        long now = System.currentTimeMillis();
        for (JsonElement element : members) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }

            JsonObject member = element.getAsJsonObject();
            JsonObject profile = firstObject(member, "playerProfile", "profile");

            String playerName = firstString(member, "playerName", "name", "nickname");
            if (playerName == null && profile != null) {
                playerName = firstString(profile, "name", "playerName", "nickname");
            }

            String itemName = firstString(member, "itemName", "item", "latestTakenItem", "takenItemName");
            String itemVisualData = firstString(member, "itemVisualData", "visualData", "itemStackVisual", "itemIconData");
            int itemCount = firstInt(member, 1, "itemCount", "count", "takenCount", "latestTakenCount");
            int totalKinds = firstInt(member, itemName == null ? 0 : 1, "totalKinds", "takenKinds", "itemKinds");
            int totalItems = firstInt(member, Math.max(itemCount, 0), "totalItems", "takenItems", "countTotal");
            String chestName = firstString(member, "chestName", "chestKey", "storageName");
            String skinTexture = firstString(member, "skinTexture");
            if (skinTexture == null && profile != null) {
                skinTexture = firstString(profile, "skinTexture");
            }
            String playerUuid = firstString(member, "playerUuid", "uuid");
            if (playerUuid == null && profile != null) {
                playerUuid = firstString(profile, "uuid", "playerUuid");
            }
            long updatedAtMillis = firstLong(member, now,
                    "updatedAtMillis",
                    "updatedAt",
                    "createdAtMillis",
                    "timestamp");

            if (playerName == null || playerName.isBlank() || itemName == null || itemName.isBlank()) {
                continue;
            }

            parsedEntries.add(new MemberHudEntry(
                    playerName,
                    itemName,
                    Math.max(1, itemCount),
                    Math.max(totalKinds, 1),
                    Math.max(totalItems, Math.max(1, itemCount)),
                    itemVisualData,
                    List.of(new HudRotationItem(itemName, Math.max(1, itemCount), itemVisualData)),
                    chestName,
                    skinTexture,
                    playerUuid,
                    updatedAtMillis
            ));
        }

        return parsedEntries;
    }

    private void replaceMemberHudEntries(JsonObject json) {
        memberHudEntries.clear();
        memberHudEntries.addAll(parseMemberHudEntries(json));
    }

    private boolean commitConnectionState(long version,
                                          String nextIslandName,
                                          long nextConfigVersion,
                                          Map<String, BlockPos> nextChestMap,
                                          List<MemberHudEntry> nextMemberEntries) {
        synchronized (this) {
            if (connectionVersion.get() != version) {
                return false;
            }

            islandName = nextIslandName;
            configVersion = nextConfigVersion;
            chestMap.clear();
            chestMap.putAll(nextChestMap);
            memberHudEntries.clear();
            memberHudEntries.addAll(nextMemberEntries);
            saveConfig();
            return true;
        }
    }

    private static JsonArray firstArray(JsonObject json, String... keys) {
        if (json == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (json.has(key) && json.get(key).isJsonArray()) {
                return json.getAsJsonArray(key);
            }
        }
        return null;
    }

    private static JsonObject firstObject(JsonObject json, String... keys) {
        if (json == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (json.has(key) && json.get(key).isJsonObject()) {
                return json.getAsJsonObject(key);
            }
        }
        return null;
    }

    private static String firstString(JsonObject json, String... keys) {
        if (json == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!json.has(key) || json.get(key).isJsonNull()) {
                continue;
            }
            try {
                String value = json.get(key).getAsString();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int firstInt(JsonObject json, int defaultValue, String... keys) {
        if (json == null || keys == null) {
            return defaultValue;
        }
        for (String key : keys) {
            if (!json.has(key) || json.get(key).isJsonNull()) {
                continue;
            }
            try {
                return json.get(key).getAsInt();
            } catch (Exception ignored) {
            }
        }
        return defaultValue;
    }

    private static long firstLong(JsonObject json, long defaultValue, String... keys) {
        if (json == null || keys == null) {
            return defaultValue;
        }
        for (String key : keys) {
            if (!json.has(key) || json.get(key).isJsonNull()) {
                continue;
            }
            try {
                return json.get(key).getAsLong();
            } catch (Exception ignored) {
            }
        }
        return defaultValue;
    }

    private static String normalizeHudEntryKey(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    private static void appendCurrentPlayerProfile(JsonObject body, String fallbackPlayerName) {
        if (body == null) {
            return;
        }

        JsonObject profile = collectCurrentPlayerProfile(fallbackPlayerName);
        if (profile == null) {
            if ((fallbackPlayerName != null && !fallbackPlayerName.isBlank()) && !body.has("playerName")) {
                body.addProperty("playerName", fallbackPlayerName);
            }
            return;
        }

        if (profile.has("name") && !body.has("playerName")) {
            body.addProperty("playerName", profile.get("name").getAsString());
        }
        if (profile.has("uuid")) {
            body.addProperty("playerUuid", profile.get("uuid").getAsString());
        }
        body.add("playerProfile", profile);
    }

    private static JsonObject collectCurrentPlayerProfile(String fallbackPlayerName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return buildFallbackProfile(fallbackPlayerName);
        }

        JsonObject profile = new JsonObject();
        String playerName = firstNonBlank(client.player.getName().getString(), fallbackPlayerName);
        if (playerName != null) {
            profile.addProperty("name", playerName);
        }

        profile.addProperty("uuid", client.player.getUuidAsString());

        var gameProfile = client.player.getGameProfile();
        if (gameProfile != null) {
            String texturesValue = extractTexturesPropertyValue(gameProfile);
            if (texturesValue != null) {
                profile.addProperty("texturesValue", texturesValue);
            }

            String texturesSignature = extractTexturesPropertySignature(gameProfile);
            if (texturesSignature != null) {
                profile.addProperty("texturesSignature", texturesSignature);
            }
        }

        if (client.player instanceof AbstractClientPlayerEntity abstractPlayer) {
            Object skinTextures = firstNonNull(
                    invokeNoArg(abstractPlayer, "getSkinTextures"),
                    invokeNoArg(abstractPlayer, "getSkin")
            );
            String skinTexture = identifierToString(invokeNoArg(skinTextures, "texture"));
            if (skinTexture != null) {
                profile.addProperty("skinTexture", skinTexture);
            }

            String capeTexture = identifierToString(invokeNoArg(skinTextures, "capeTexture"));
            if (capeTexture != null) {
                profile.addProperty("capeTexture", capeTexture);
            }

            String model = stringifySkinModel(invokeNoArg(skinTextures, "model"));
            if (model != null) {
                profile.addProperty("model", model);
            }
        }

        return profile.size() == 0 ? buildFallbackProfile(fallbackPlayerName) : profile;
    }

    private static JsonObject buildFallbackProfile(String fallbackPlayerName) {
        if (fallbackPlayerName == null || fallbackPlayerName.isBlank()) {
            return null;
        }

        JsonObject profile = new JsonObject();
        profile.addProperty("name", fallbackPlayerName);
        return profile;
    }

    private static String extractTexturesPropertyValue(Object gameProfile) {
        Object property = firstTexturesProperty(gameProfile);
        return property == null ? null : firstNonBlank(stringifyValue(invokeNoArg(property, "value")), stringifyValue(invokeNoArg(property, "getValue")));
    }

    private static String extractTexturesPropertySignature(Object gameProfile) {
        Object property = firstTexturesProperty(gameProfile);
        return property == null ? null : firstNonBlank(stringifyValue(invokeNoArg(property, "signature")), stringifyValue(invokeNoArg(property, "getSignature")));
    }

    private static Object firstTexturesProperty(Object gameProfile) {
        if (gameProfile == null) {
            return null;
        }

        Object properties = invokeNoArg(gameProfile, "getProperties");
        if (properties == null) {
            return null;
        }

        Object textures = invokeOneArg(properties, "get", "textures");
        if (textures instanceof Iterable<?> iterable) {
            for (Object entry : iterable) {
                if (entry != null) {
                    return entry;
                }
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object invokeOneArg(Object target, String methodName, Object argument) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) {
                continue;
            }

            try {
                return method.invoke(target, argument);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String identifierToString(Object identifier) {
        return stringifyValue(identifier);
    }

    private static String stringifySkinModel(Object model) {
        if (model == null) {
            return null;
        }

        String asString = stringifyValue(invokeNoArg(model, "asString"));
        if (asString != null) {
            return asString;
        }

        String getName = stringifyValue(invokeNoArg(model, "getName"));
        if (getName != null) {
            return getName;
        }

        return stringifyValue(model);
    }

    private static String stringifyValue(Object value) {
        if (value == null) {
            return null;
        }

        String asString = String.valueOf(value).trim();
        return asString.isBlank() ? null : asString;
    }

    private static String firstNonBlank(String primary, String secondary) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary;
        }
        return null;
    }

    private static Object firstNonNull(Object primary, Object secondary) {
        return primary != null ? primary : secondary;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String currentPlayerSkinTexture() {
        JsonObject profile = collectCurrentPlayerProfile(null);
        if (profile == null || !profile.has("skinTexture")) {
            return null;
        }
        return profile.get("skinTexture").getAsString();
    }

    private static Long parseAmount(String amountText) {
        if (amountText == null) {
            return null;
        }

        String digits = amountText.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return null;
        }

        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeAdminCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static void log(String msg) {
        ChestBotMod.LOGGER.info("[ChestBot] {}", msg);
    }

    // ── 내부 데이터 클래스 ────────────────────────────────────────

    public static class ChestDef {
        public final String name;
        public final BlockPos primary;
        public final BlockPos secondary;

        public ChestDef(String name, BlockPos primary, BlockPos secondary) {
            this.name      = name;
            this.primary   = primary;
            this.secondary = secondary;
        }
    }

    private record HttpResult(String path, int statusCode, String body, String errorMessage) {
        private boolean success() {
            return statusCode >= 200 && statusCode < 300;
        }

        private boolean shouldRetry() {
            return statusCode == -1 || statusCode >= 500;
        }

        private String describeFailure() {
            if (statusCode == -1) {
                return path + " 전송 오류: " + errorMessage;
            }
            return path + " 서버 오류 " + statusCode + ": " + body;
        }
    }

    private record PendingBankTransaction(String playerName, String transactionType, String amountText, String sourceMessage, long createdAtMillis) {
    }

    private record LocalHudHolding(String itemName, int netCount, String itemVisualData, String chestName, long updatedAtMillis) {
    }

    public record HudRotationItem(
            String itemName,
            int itemCount,
            String itemVisualData
    ) {
    }

    public record RecentChestHudEntry(
            String playerName,
            String chestName,
            String itemName,
            int itemCount,
            int totalKinds,
            int totalItems,
            String itemVisualData,
            List<HudRotationItem> rotationItems,
            String skinTexture,
            long createdAtMillis
    ) {
    }

    public record MemberHudEntry(
            String playerName,
            String itemName,
            int itemCount,
            int totalKinds,
            int totalItems,
            String itemVisualData,
            List<HudRotationItem> rotationItems,
            String chestName,
            String skinTexture,
            String playerUuid,
            long updatedAtMillis
    ) {
    }
}
