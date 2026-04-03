package com.example.chestbot.command;

import com.example.chestbot.BotBridge;
import com.example.chestbot.ChestBotMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * /chestbot 클라이언트 명령어
 *
 * <pre>
 * 플레이어:
 *   /chestbot connect &lt;코드&gt;   — 6자리 참여코드로 섬 연결
 *   /chestbot list             — 등록된 chest 목록
 *   /chestbot reload           — 서버에서 설정 재로드
 *
 * 관리자 (Discord에서 /창고 관리자코드 발급 후):
 *   /chestbot admin &lt;관리자코드&gt;  — 관리자 모드 진입
 *   /chestbot add &lt;창고이름&gt;      — 상자 클릭 시 즉시 저장
 *   /chestbot remove &lt;창고이름&gt;   — 등록 대기 중인 chest 제거
 * </pre>
 */
public class ChestBotCommand {

    private static final SuggestionProvider<FabricClientCommandSource> CHEST_NAME_SUGGESTIONS = (ctx, builder) -> {
        pendingChestNames().stream()
                .filter(name -> name.toLowerCase().startsWith(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(buildRoot("chestbot", false));
        dispatcher.register(buildRoot("창고봇", true));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRoot(String root, boolean korean) {
        String connect = korean ? "연결" : "connect";
        String list = korean ? "목록" : "list";
        String reload = korean ? "새로고침" : "reload";
        String admin = korean ? "관리자" : "admin";
        String add = korean ? "추가" : "add";
        String remove = korean ? "제거" : "remove";
        String depositReason = korean ? "입금사유" : "depositreason";

        return ClientCommandManager.literal(root)

                .then(ClientCommandManager.literal(connect)
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(text("§e[창고지기] 사용법: /chestbot connect <6자리코드>  또는  /창고봇 연결 <코드>"));
                            return 0;
                        })
                        .then(ClientCommandManager.argument("code", StringArgumentType.word())
                                .executes(ctx -> {
                                    String code = StringArgumentType.getString(ctx, "code");
                                    ctx.getSource().sendFeedback(text("§7[창고지기] 연결 시도 중..."));
                                    runAsync(ctx.getSource(), ChestBotMod.getBridge().connectWithCodeAsync(code), ok -> {
                                        if (ok) {
                                            BotBridge bridge = ChestBotMod.getBridge();
                                            ctx.getSource().sendFeedback(text(
                                                    "§a[창고지기] ✅ 연결 성공! 섬: §f" + bridge.getIslandName()
                                                    + " §7| chest §f" + bridge.getChestMap().size() + "§7개"));
                                        } else {
                                            ctx.getSource().sendFeedback(text("§c[창고지기] ❌ 연결 실패. 코드를 확인하세요."));
                                        }
                                    });
                                    return 1;
                                })))

                .then(ClientCommandManager.literal(list)
                        .executes(ctx -> {
                            BotBridge bridge = ChestBotMod.getBridge();
                            var all = bridge.getChestMap();
                            if (all.isEmpty()) {
                                ctx.getSource().sendFeedback(text("§7[창고지기] 등록된 chest 없음"));
                            } else {
                                StringBuilder sb = new StringBuilder("§6[창고지기] 등록된 chest:\n");
                                all.forEach((name, pos) ->
                                        sb.append("  §f").append(name)
                                          .append(" §7@ ").append(pos.toShortString()).append("\n"));
                                ctx.getSource().sendFeedback(text(sb.toString().trim()));
                            }
                            return 1;
                        }))

                .then(ClientCommandManager.literal(reload)
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(text("§7[창고지기] 서버에서 설정 재로드 중..."));
                            runAsync(ctx.getSource(), ChestBotMod.getBridge().reloadAsync(), ok -> {
                                if (ok) {
                                    BotBridge bridge = ChestBotMod.getBridge();
                                    ctx.getSource().sendFeedback(text(
                                            "§a[창고지기] ✅ 재로드 완료! 섬: §f" + bridge.getIslandName()
                                            + " §7| chest §f" + bridge.getChestMap().size() + "§7개"));
                                } else {
                                    ctx.getSource().sendFeedback(text("§c[창고지기] ❌ 재로드 실패. 연결 상태를 확인하세요."));
                                }
                            });
                            return 1;
                        }))

                .then(ClientCommandManager.literal(admin)
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(text("§e[창고지기] 사용법: /chestbot admin <관리자코드>  또는  /창고봇 관리자 <코드>"));
                            return 0;
                        })
                        .then(ClientCommandManager.argument("adminCode", StringArgumentType.word())
                                .executes(ctx -> {
                                    String adminCode  = StringArgumentType.getString(ctx, "adminCode");
                                    BotBridge bridge  = ChestBotMod.getBridge();

                                    if (bridge.getIslandCode() == null) {
                                        ctx.getSource().sendFeedback(text("§c[창고지기] 먼저 /chestbot connect <코드> 로 연결하세요."));
                                        return 0;
                                    }

                                    ctx.getSource().sendFeedback(text("§7[창고지기] 관리자 모드 진입 중..."));
                                    runAsync(ctx.getSource(), bridge.enterAdminModeAsync(bridge.getIslandCode(), adminCode), ok -> {
                                        if (ok) {
                                            ctx.getSource().sendFeedback(text(
                                                    "§a[창고지기] ✅ 관리자 모드! 섬: §f" + bridge.getAdminIslandName() + "\n"
                                                    + "§7/chestbot add <이름> 또는 /창고봇 추가 <이름> 후 상자를 클릭하면 바로 저장됩니다."));
                                        } else {
                                            ctx.getSource().sendFeedback(text("§c[창고지기] ❌ 관리자 코드가 유효하지 않습니다."));
                                        }
                                    });
                                    return 1;
                                })))

                .then(ClientCommandManager.literal(add)
                        .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    BotBridge bridge = ChestBotMod.getBridge();
                                    if (!bridge.isAdminMode()) {
                                        ctx.getSource().sendFeedback(text("§c[창고지기] 관리자 모드가 아닙니다. /chestbot admin 을 먼저 실행하세요."));
                                        return 0;
                                    }

                                    String name = StringArgumentType.getString(ctx, "name").trim();
                                    if (name.isBlank()) {
                                        ctx.getSource().sendFeedback(text("§c[창고지기] 상자 이름을 입력하세요."));
                                        return 0;
                                    }

                                    bridge.beginChestSelection(name);
                                    ctx.getSource().sendFeedback(text(
                                            "§e[창고지기] '" + name + "' 등록 대기 중입니다. 이제 등록할 상자를 우클릭하면 바로 저장됩니다."));
                                    return 1;
                                })))

                .then(ClientCommandManager.literal(remove)
                        .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                .suggests(CHEST_NAME_SUGGESTIONS)
                                .executes(ctx -> {
                                    BotBridge bridge = ChestBotMod.getBridge();
                                    if (!bridge.isAdminMode()) {
                                        ctx.getSource().sendFeedback(text("§c[창고지기] 관리자 모드가 아닙니다."));
                                        return 0;
                                    }
                                    String name = StringArgumentType.getString(ctx, "name");
                                    boolean removed = bridge.removeAdminChest(name);
                                    if (removed) {
                                        ctx.getSource().sendFeedback(text("§e[창고지기] '" + name + "' 대기 목록에서 제거됨"));
                                        return 1;
                                    }
                                     ctx.getSource().sendFeedback(text("§c[창고지기] 대기 목록에서 '" + name + "' 을(를) 찾지 못했습니다."));
                                     return 0;
                                 })))

                .then(ClientCommandManager.literal(depositReason)
                        .executes(ctx -> {
                            BotBridge bridge = ChestBotMod.getBridge();
                            if (!bridge.hasPendingBankTransaction()) {
                                ctx.getSource().sendFeedback(text("§c[창고지기] 입력할 은행 거래 사유가 없습니다. 입출금 알림을 먼저 받으세요."));
                                return 0;
                            }

                            String amountText = bridge.getPendingBankTransactionAmountText();
                            String transactionType = bridge.getPendingBankTransactionType();
                            String label = "WITHDRAW".equals(transactionType) ? "출금" : "입금";
                            ctx.getSource().sendFeedback(text("§e[창고지기] 사용법: /" + root + " " + depositReason + " <사유>  §7(최근 " + label + " 금액: " + amountText + ")"));
                            return 0;
                        })
                        .then(ClientCommandManager.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String reason = StringArgumentType.getString(ctx, "reason");
                                    BotBridge bridge = ChestBotMod.getBridge();
                                    if (!bridge.hasPendingBankTransaction()) {
                                        ctx.getSource().sendFeedback(text("§c[창고지기] 저장할 은행 거래 내역이 없습니다."));
                                        return 0;
                                    }
                                    boolean ok = bridge.submitPendingBankTransactionReason(reason);
                                    if (ok) {
                                        ctx.getSource().sendFeedback(text("§a[창고지기] ✅ 은행 거래 사유가 전송되었습니다."));
                                        return 1;
                                    }
                                    ctx.getSource().sendFeedback(text("§c[창고지기] 은행 거래 사유 전송 실패. 사유를 다시 확인하세요."));
                                    return 0;
                                })))

                ;
    }

    private static void runAsync(FabricClientCommandSource source, CompletableFuture<Boolean> future,
                                 java.util.function.Consumer<Boolean> onComplete) {
        future.whenComplete((ok, error) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                if (error != null) {
                    source.sendFeedback(text("§c[창고지기] ❌ 작업 중 오류가 발생했습니다: " + error.getMessage()));
                    return;
                }
                onComplete.accept(Boolean.TRUE.equals(ok));
            });
        });
    }

    private static List<String> pendingChestNames() {
        Set<String> names = new LinkedHashSet<>();
        ChestBotMod.getBridge().getPendingChests().stream()
                .map(chest -> chest.name)
                .forEach(names::add);
        return new ArrayList<>(names);
    }

    private static Text text(String s) {
        return Text.literal(s);
    }
}
