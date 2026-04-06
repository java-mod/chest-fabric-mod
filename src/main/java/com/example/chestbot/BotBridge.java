package com.example.chestbot;

import com.google.gson.*;
import net.minecraft.util.math.BlockPos;

import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 서버와의 HTTP 통신 및 island 설정을 관리한다.
 *
 * <p>설정 파일: {@code config/chestbot.json}</p>
 * <ul>
 *   <li>{@code server_url} — 백엔드 서버 주소</li>
 *   <li>{@code island_code} — 참여 코드 (인게임 /창고봇 연결로 저장)</li>
 * </ul>
 */
public class BotBridge {

    private static final String DEFAULT_URL = "https://chestbot.kro.kr";

    // ── 설정 (파일에서 로드) ────────────────────────────────────
    private String serverUrl;
    private String islandCode;
    private String licenseKey;

    // ── 런타임 상태 ─────────────────────────────────────────────
    private String islandName;
    private long configVersion;
    private final Map<String, BlockPos> chestMap = new LinkedHashMap<>();
    private String lastConnectError;

    // ── 관리자 모드 상태 ────────────────────────────────────────
    private boolean adminMode;
    private String adminIslandName;
    private String pendingAdminCode;
    private String pendingJoinCode;
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
        if (licenseKey != null && !licenseKey.isBlank()) {
            connectWithLicense(licenseKey);
        } else if (islandCode != null && !islandCode.isBlank()) {
            connectWithCode(islandCode);
        } else {
            log("서버에 연결되지 않음. /창고봇 라이선스 <키> 또는 /창고봇 연결 <코드> 를 입력하세요.");
        }
    }

    public void startAsync() {
        if (licenseKey != null && !licenseKey.isBlank()) {
            connectWithLicenseAsync(licenseKey);
        } else if (islandCode != null && !islandCode.isBlank()) {
            connectWithCodeAsync(islandCode);
        } else {
            log("서버에 연결되지 않음. /창고봇 라이선스 <키> 또는 /창고봇 연결 <코드> 를 입력하세요.");
        }
    }

    public void stop() {
        chestMap.clear();
        islandName = null;
        adminMode = false;
        adminIslandName = null;
        pendingAdminCode = null;
        pendingJoinCode = null;
        pendingChests.clear();
        lastInteractedChestPos = null;
        lastInteractedChestAtMillis = 0L;
        pendingChestSelectionName = null;
        pendingBankTransaction = null;
    }

    // ── 참여 코드로 섬 연결 ──────────────────────────────────────

    public boolean connectWithCode(String code) {
        String normalizedCode = normalizeJoinCode(code);
        JsonObject body = new JsonObject();
        body.addProperty("joinCode", normalizedCode);

        HttpResult result = postJson("/api/v1/client/connect", body);
        if (!result.success()) {
            log(result.describeFailure());
            if (result.statusCode() == 403) {
                clearAndSaveConfig();
                log("라이선스 비활성화: 연결 정보를 초기화했습니다.");
            }
            return false;
        }

        try {
            JsonObject json = JsonParser.parseString(result.body()).getAsJsonObject();
            islandName    = json.get("islandName").getAsString();
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

            islandCode = normalizedCode;
            saveConfig();
            log("연결 성공! 섬: " + islandName + " | chest " + chestMap.size() + "개 (버전 " + configVersion + ")");
            return true;
        } catch (Exception e) {
            log("연결 응답 파싱 오류: " + e.getMessage());
            return false;
        }
    }

    /** 403 수신 시 호출 — 모든 연결 정보를 초기화하고 config 파일을 저장한다. */
    private void clearAndSaveConfig() {
        licenseKey    = null;
        islandCode    = null;
        islandName    = null;
        configVersion = 0L;
        chestMap.clear();
        saveConfig();
    }

    public CompletableFuture<Boolean> connectWithCodeAsync(String code) {
        return CompletableFuture.supplyAsync(() -> connectWithCode(code));
    }

    // ── 라이선스 키로 섬 연결 (섬장 전용) ────────────────────────

    public boolean connectWithLicense(String key) {
        lastConnectError = null;
        JsonObject body = new JsonObject();
        body.addProperty("licenseKey", key.trim().toUpperCase());

        HttpResult result = postJson("/api/v1/client/connect/license", body);
        if (!result.success()) {
            log(result.describeFailure());
            if (result.statusCode() == 401) {
                lastConnectError = "등록되지 않은 라이선스 키입니다. 키를 다시 확인하세요.";
            } else if (result.statusCode() == 403) {
                String serverMsg = parseServerMessage(result.body());
                lastConnectError = serverMsg + "\n§e관리자에게 문의하세요.";
                clearAndSaveConfig();
                log("라이선스 인증 실패(403): 연결 정보를 초기화했습니다.");
            } else if (result.statusCode() == -1) {
                lastConnectError = "서버에 연결할 수 없습니다. 잠시 후 다시 시도하세요.";
            } else {
                lastConnectError = "라이선스 인증 실패 (코드 " + result.statusCode() + "). 관리자에게 문의하세요.";
            }
            return false;
        }

        try {
            JsonObject json = JsonParser.parseString(result.body()).getAsJsonObject();
            islandName    = json.get("islandName").getAsString();
            islandCode    = json.get("joinCode").getAsString();
            configVersion = json.get("configVersion").getAsLong();
            licenseKey    = key.trim().toUpperCase();

            chestMap.clear();
            JsonArray chests = json.getAsJsonArray("chests");
            for (JsonElement el : chests) {
                JsonObject c = el.getAsJsonObject();
                chestMap.put(
                        c.get("chestKey").getAsString(),
                        new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt())
                );
            }

            saveConfig();
            log("라이선스 연결 성공! 섬: " + islandName + " | 조인코드: " + islandCode
                    + " | chest " + chestMap.size() + "개 (버전 " + configVersion + ")");
            return true;
        } catch (Exception e) {
            log("라이선스 연결 응답 파싱 오류: " + e.getMessage());
            lastConnectError = "서버 응답 처리 중 오류가 발생했습니다.";
            return false;
        }
    }

    /** 서버 에러 응답 JSON의 {@code message} 필드를 추출한다. 파싱 실패 시 기본 메시지를 반환한다. */
    private static String parseServerMessage(String body) {
        if (body == null || body.isBlank()) return "라이선스 인증에 실패했습니다.";
        try {
            JsonObject err = JsonParser.parseString(body).getAsJsonObject();
            if (err.has("message") && !err.get("message").isJsonNull()) {
                return err.get("message").getAsString();
            }
        } catch (Exception ignored) {}
        return "라이선스 인증에 실패했습니다.";
    }

    public CompletableFuture<Boolean> connectWithLicenseAsync(String key) {
        return CompletableFuture.supplyAsync(() -> connectWithLicense(key));
    }

    // ── 관리자 모드 ──────────────────────────────────────────────

    public boolean enterAdminMode(String joinCode, String adminCode) {
        String normalizedJoinCode = normalizeJoinCode(joinCode);
        JsonObject body = new JsonObject();
        body.addProperty("joinCode", normalizedJoinCode);
        body.addProperty("adminCode", adminCode);

        String response = postSync("/api/v1/client/admin/connect", body);
        if (response == null) return false;

        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            adminIslandName  = json.get("islandName").getAsString();
            pendingAdminCode = adminCode;
            pendingJoinCode  = normalizedJoinCode;
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

    public CompletableFuture<Boolean> enterAdminModeAsync(String joinCode, String adminCode) {
        return CompletableFuture.supplyAsync(() -> enterAdminMode(joinCode, adminCode));
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
        body.addProperty("joinCode", pendingJoinCode);
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
            log("완료! chest " + chestMap.size() + "개 저장됨 (버전 " + configVersion + ")");
        } catch (Exception ignored) {}

        adminMode        = false;
        pendingAdminCode = null;
        pendingJoinCode  = null;
        pendingChestSelectionName = null;
        pendingChests.clear();
        return true;
    }

    // ── chest 이벤트 전송 ────────────────────────────────────────

    public void sendChestLog(String player, String chestName,
                             Map<String, Integer> taken, Map<String, Integer> added) {
        if (islandCode == null || islandCode.isBlank()) {
            log("chest 로그 스킵: 섬 연결 정보가 없습니다.");
            return;
        }

        JsonObject body = new JsonObject();
        body.addProperty("joinCode",      islandCode);
        body.addProperty("configVersion", configVersion);
        body.addProperty("playerName",    player);
        body.addProperty("chestKey",      chestName);
        body.add("taken", toJsonObject(taken));
        body.add("added", toJsonObject(added));

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
        if (islandCode == null || islandCode.isBlank()) {
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
        body.addProperty("joinCode", islandCode);
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

    // ── 상태 정보 ────────────────────────────────────────────────

    public boolean isConnected()    { return islandCode != null && islandName != null; }
    public boolean isAdminMode()    { return adminMode; }
    public String  getIslandCode()  { return islandCode; }
    public String  getIslandName()  { return islandName; }
    public String  getLicenseKey()      { return licenseKey; }
    public String  getLastConnectError() { return lastConnectError; }
    public String  getAdminIslandName() { return adminIslandName; }
    public long    getConfigVersion() { return configVersion; }
    public List<ChestDef> getPendingChests() { return Collections.unmodifiableList(pendingChests); }

    // ── 설정 파일 ────────────────────────────────────────────────

    public boolean reload() {
        if (licenseKey != null && !licenseKey.isBlank()) {
            return connectWithLicense(licenseKey);
        }
        return islandCode != null && connectWithCode(islandCode);
    }

    public CompletableFuture<Boolean> reloadAsync() {
        return CompletableFuture.supplyAsync(this::reload);
    }

    private void loadConfig() {
        Path path = Path.of("config", "chestbot.json");
        serverUrl  = DEFAULT_URL;
        islandCode = null;
        licenseKey = null;

        if (Files.exists(path)) {
            try {
                JsonObject cfg = JsonParser.parseReader(new FileReader(path.toFile())).getAsJsonObject();
                if (cfg.has("server_url")) {
                    serverUrl = cfg.get("server_url").getAsString().trim();
                }
                if (cfg.has("island_code") && !cfg.get("island_code").isJsonNull()) {
                    islandCode = cfg.get("island_code").getAsString();
                }
                if (cfg.has("license_key") && !cfg.get("license_key").isJsonNull()) {
                    licenseKey = cfg.get("license_key").getAsString();
                }
            } catch (Exception e) {
                log("config 로드 오류: " + e.getMessage());
            }
        } else {
            saveConfig();
            log("config/chestbot.json 생성됨. /창고봇 라이선스 <키> 로 연결하거나 /창고봇 연결 <코드> 를 사용하세요.");
        }
    }

    private void saveConfig() {
        Path path = Path.of("config", "chestbot.json");
        try {
            Files.createDirectories(path.getParent());
            JsonObject cfg = new JsonObject();
            cfg.addProperty("server_url", serverUrl);
            if (licenseKey != null) cfg.addProperty("license_key", licenseKey);
            else cfg.add("license_key", JsonNull.INSTANCE);
            if (islandCode != null) cfg.addProperty("island_code", islandCode);
            else cfg.add("island_code", JsonNull.INSTANCE);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(cfg));
        } catch (IOException e) {
            log("config 저장 오류: " + e.getMessage());
        }
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

                // 403: 라이선스 비활성화 — 재시도 없이 즉시 초기화
                if (result.statusCode() == 403) {
                    log(context + " 전송 실패: 라이선스가 비활성화되었습니다. 연결 정보를 초기화합니다.");
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

    private static String normalizeJoinCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static void log(String msg) {
        System.out.println("[ChestBot] " + msg);
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
}
