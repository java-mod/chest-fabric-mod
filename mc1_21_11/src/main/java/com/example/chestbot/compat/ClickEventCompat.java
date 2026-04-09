package com.example.chestbot.compat;

import net.minecraft.text.ClickEvent;

import java.lang.reflect.Constructor;

/**
 * MC 1.21.11 — ClickEvent.SuggestCommand sealed class를 사용합니다. (1.21.5+)
 */
public final class ClickEventCompat {
    private ClickEventCompat() {}

    public static ClickEvent suggestCommand(String command) {
        try {
            Class<?> nested = Class.forName("net.minecraft.text.ClickEvent$SuggestCommand");
            Constructor<?> ctor = nested.getDeclaredConstructor(String.class);
            return (ClickEvent) ctor.newInstance(command);
        } catch (Exception ignored) {
            throw new IllegalStateException("Failed to create ClickEvent.SuggestCommand for modern versions");
        }
    }
}
