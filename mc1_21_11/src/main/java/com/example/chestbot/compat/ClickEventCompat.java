package com.example.chestbot.compat;

import net.minecraft.text.ClickEvent;

/**
 * MC 1.21.11 — ClickEvent.SuggestCommand sealed class를 사용합니다. (1.21.5+)
 */
public final class ClickEventCompat {
    private ClickEventCompat() {}

    public static ClickEvent suggestCommand(String command) {
        return new ClickEvent.SuggestCommand(command);
    }
}
