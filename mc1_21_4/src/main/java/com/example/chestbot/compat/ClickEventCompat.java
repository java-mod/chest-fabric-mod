package com.example.chestbot.compat;

import net.minecraft.text.ClickEvent;

/**
 * MC 1.21.4 — ClickEvent(Action, String) 생성자를 사용합니다.
 */
public final class ClickEventCompat {
    private ClickEventCompat() {}

    public static ClickEvent suggestCommand(String command) {
        return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command);
    }
}
