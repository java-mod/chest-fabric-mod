package com.example.chestbot;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FarmingActivityWatcher {

    private static final long REPORT_THROTTLE_MILLIS = 1500L;
    private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();
    private static long lastReportedAtMillis;
    private static String lastReportedCropKey;

    static {
        DISPLAY_NAMES.put("wheat", "밀");
        DISPLAY_NAMES.put("carrot", "당근");
        DISPLAY_NAMES.put("potato", "감자");
        DISPLAY_NAMES.put("beetroot", "비트루트");
        DISPLAY_NAMES.put("nether_wart", "네더와트");
        DISPLAY_NAMES.put("sugar_cane", "사탕수수");
        DISPLAY_NAMES.put("melon", "수박");
        DISPLAY_NAMES.put("pumpkin", "호박");
        DISPLAY_NAMES.put("cocoa", "코코아" );
        DISPLAY_NAMES.put("sweet_berries", "스위트베리");
    }

    private FarmingActivityWatcher() {
    }

    public static List<String> suggestionLabels() {
        return List.of("밀", "당근", "감자", "비트", "네더와트", "사탕수수", "수박", "호박", "코코아", "스위트베리");
    }

    public static String normalizeCropKey(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (value) {
            case "밀", "wheat" -> "wheat";
            case "당근", "carrot", "carrots" -> "carrot";
            case "감자", "potato", "potatoes" -> "potato";
            case "비트", "비트루트", "beet", "beetroot", "beetroots" -> "beetroot";
            case "네더와트", "nether_wart", "wart" -> "nether_wart";
            case "사탕수수", "sugar_cane", "sugarcane" -> "sugar_cane";
            case "수박", "melon" -> "melon";
            case "호박", "pumpkin" -> "pumpkin";
            case "코코아", "cocoa", "cocoa_beans" -> "cocoa";
            case "스위트베리", "sweet_berries", "sweetberries", "berries" -> "sweet_berries";
            default -> null;
        };
    }

    public static String displayName(String cropKey) {
        String normalized = normalizeCropKey(cropKey);
        return normalized == null ? cropKey : DISPLAY_NAMES.getOrDefault(normalized, normalized);
    }

    public static ItemStack displayItem(String cropKey) {
        String normalized = normalizeCropKey(cropKey);
        if (normalized == null) {
            return ItemStack.EMPTY;
        }
        return switch (normalized) {
            case "wheat" -> new ItemStack(Items.WHEAT);
            case "carrot" -> new ItemStack(Items.CARROT);
            case "potato" -> new ItemStack(Items.POTATO);
            case "beetroot" -> new ItemStack(Items.BEETROOT);
            case "nether_wart" -> new ItemStack(Items.NETHER_WART);
            case "sugar_cane" -> new ItemStack(Items.SUGAR_CANE);
            case "melon" -> new ItemStack(Items.MELON_SLICE);
            case "pumpkin" -> new ItemStack(Items.PUMPKIN);
            case "cocoa" -> new ItemStack(Items.COCOA_BEANS);
            case "sweet_berries" -> new ItemStack(Items.SWEET_BERRIES);
            default -> ItemStack.EMPTY;
        };
    }

    public static void onBlockAttacked(MinecraftClient client, BlockPos pos) {
        reportIfMatches(client, pos);
    }

    private static void reportIfMatches(MinecraftClient client, BlockPos pos) {
        if (client == null || client.world == null || pos == null) {
            return;
        }
        BotBridge bridge = ChestBotMod.getBridge();
        if (bridge == null || !bridge.isConnected()) {
            return;
        }
        String selectedCropKey = bridge.getSelectedCropKey();
        if (selectedCropKey == null || selectedCropKey.isBlank()) {
            return;
        }

        BlockState state = client.world.getBlockState(pos);
        String detected = detectCropKey(state);
        if (detected == null || !detected.equalsIgnoreCase(selectedCropKey)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (detected.equals(lastReportedCropKey) && now - lastReportedAtMillis < REPORT_THROTTLE_MILLIS) {
            return;
        }
        lastReportedCropKey = detected;
        lastReportedAtMillis = now;
        bridge.reportFarmingActivity(detected, now);
    }

    private static String detectCropKey(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.WHEAT) {
            return isMatureCrop(state) ? "wheat" : null;
        }
        if (block == Blocks.CARROTS) {
            return isMatureCrop(state) ? "carrot" : null;
        }
        if (block == Blocks.POTATOES) {
            return isMatureCrop(state) ? "potato" : null;
        }
        if (block == Blocks.BEETROOTS) {
            return isMatureCrop(state) ? "beetroot" : null;
        }
        if (block == Blocks.NETHER_WART) {
            return isMatureNetherWart(state) ? "nether_wart" : null;
        }
        if (block == Blocks.SUGAR_CANE) {
            return "sugar_cane";
        }
        if (block == Blocks.MELON) {
            return "melon";
        }
        if (block == Blocks.PUMPKIN) {
            return "pumpkin";
        }
        if (block == Blocks.COCOA) {
            return "cocoa";
        }
        if (block == Blocks.SWEET_BERRY_BUSH) {
            return "sweet_berries";
        }
        return null;
    }

    private static boolean isMatureCrop(BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop)) {
            return false;
        }
        return crop.isMature(state);
    }

    private static boolean isMatureNetherWart(BlockState state) {
        return state.getBlock() instanceof NetherWartBlock && state.get(NetherWartBlock.AGE) >= 3;
    }
}
