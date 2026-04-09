package com.example.chestbot.hud;

public final class ChestHudItemVisuals {

    private static final String TAKEN_PREFIX = "[꺼냄] ";
    private static final String ADDED_PREFIX = "[넣음] ";

    private ChestHudItemVisuals() {
    }

    public static String formatItemName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "알 수 없는 아이템";
        }
        return rawName.trim();
    }

    public static String markAdded(String rawName) {
        return ADDED_PREFIX + formatItemName(rawName);
    }

    public static String markTaken(String rawName) {
        return TAKEN_PREFIX + formatItemName(rawName);
    }

    public static String stripActionPrefix(String rawName) {
        String formatted = formatItemName(rawName);
        if (formatted.startsWith(TAKEN_PREFIX)) {
            return formatted.substring(TAKEN_PREFIX.length()).trim();
        }
        if (formatted.startsWith(ADDED_PREFIX)) {
            return formatted.substring(ADDED_PREFIX.length()).trim();
        }
        return formatted;
    }

    public static String actionLabel(String rawName) {
        String formatted = formatItemName(rawName);
        if (formatted.startsWith(TAKEN_PREFIX)) {
            return "꺼냄";
        }
        if (formatted.startsWith(ADDED_PREFIX)) {
            return "넣음";
        }
        return "가져감";
    }
}
