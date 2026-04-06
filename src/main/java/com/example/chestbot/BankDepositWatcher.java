package com.example.chestbot;

import com.example.chestbot.compat.ClickEventCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BankDepositWatcher {

    private static final Pattern BANK_TRANSACTION_PATTERN = Pattern.compile("^\\s*(.+?)\\s*님이\\s*섬 은행(?:에|에서)\\s*(.+?)(?:을|를)\\s*(입금|출금)했어요[.!]?\\s*$");
    private static final Pattern PLAYER_NAME_PATTERN = Pattern.compile("([A-Za-z0-9_]{3,16})");
    private static final String REASON_COMMAND = "/창고봇 입금사유 ";
    private static String lastTransactionFingerprint;
    private static long lastTransactionAtMillis;

    private BankDepositWatcher() {
    }

    public static void onGameMessage(Text message, boolean overlay) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || message == null) {
            return;
        }

        String rawMessage = message.getString();
        BankTransactionEvent transaction = parseBankTransaction(rawMessage);
        if (transaction == null) {
            return;
        }

        String currentPlayerName = normalizePlayerName(client.player.getName().getString());
        if (!Objects.equals(currentPlayerName, transaction.playerName())) {
            return;
        }

        if (isDuplicate(transaction)) {
            return;
        }

        BotBridge bridge = ChestBotMod.getBridge();
        bridge.rememberPendingBankTransaction(transaction.playerName(), transaction.transactionType(), transaction.amountText(), rawMessage);
        bridge.sendIslandBankLog(transaction.playerName(), transaction.transactionType(), transaction.amountText(), null, rawMessage, true);

        if (!overlay) {
            client.player.sendMessage(Text.literal("§6[창고지기] §e섬 은행 " + transactionLabel(transaction.transactionType()) + " 감지: §f" + transaction.amountText()), false);
            client.player.sendMessage(buildReasonPrompt(transaction.transactionType()), false);
        }
    }

    private static BankTransactionEvent parseBankTransaction(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }

        Matcher matcher = BANK_TRANSACTION_PATTERN.matcher(rawMessage.trim());
        if (!matcher.matches()) {
            return null;
        }

        String playerName = normalizePlayerName(matcher.group(1));
        String amountText = matcher.group(2).trim();
        String transactionType = resolveTransactionType(matcher.group(3));
        if (playerName.isBlank() || amountText.isBlank() || transactionType.isBlank()) {
            return null;
        }

        return new BankTransactionEvent(playerName, transactionType, amountText);
    }

    private static boolean isDuplicate(BankTransactionEvent transaction) {
        String fingerprint = transaction.playerName() + "|" + transaction.transactionType() + "|" + transaction.amountText();
        long now = System.currentTimeMillis();
        boolean duplicated = Objects.equals(lastTransactionFingerprint, fingerprint) && (now - lastTransactionAtMillis) < 3000L;
        if (!duplicated) {
            lastTransactionFingerprint = fingerprint;
            lastTransactionAtMillis = now;
        }
        return duplicated;
    }

    private static String resolveTransactionType(String rawTransactionType) {
        if (rawTransactionType == null) {
            return "";
        }

        return switch (rawTransactionType.trim()) {
            case "입금" -> "DEPOSIT";
            case "출금" -> "WITHDRAW";
            default -> "";
        };
    }

    private static String normalizePlayerName(String rawPlayerName) {
        if (rawPlayerName == null) {
            return "";
        }

        String trimmed = rawPlayerName.trim();
        Matcher matcher = PLAYER_NAME_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return trimmed;
    }

    private static MutableText buildReasonPrompt(String transactionType) {
        MutableText prefix = Text.literal("§7(" + transactionLabel(transactionType) + " 사유) ");
        MutableText button = Text.literal("[사유 입력]")
                .styled(style -> style
                        .withColor(Formatting.YELLOW)
                        .withUnderline(true)
                        .withClickEvent(ClickEventCompat.suggestCommand(REASON_COMMAND)));
        MutableText suffix = Text.literal("§7 을 클릭하세요.");
        return prefix.append(button).append(suffix);
    }

    private static String transactionLabel(String transactionType) {
        return "WITHDRAW".equals(transactionType) ? "출금" : "입금";
    }

    private record BankTransactionEvent(String playerName, String transactionType, String amountText) {
    }
}
