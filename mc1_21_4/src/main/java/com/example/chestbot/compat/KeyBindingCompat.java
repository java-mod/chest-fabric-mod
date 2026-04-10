package com.example.chestbot.compat;

import net.minecraft.client.option.KeyBinding;

public final class KeyBindingCompat {
    private KeyBindingCompat() {
    }

    public static KeyBinding create(String translationKey, int keyCode, String categoryKey) {
        return new KeyBinding(translationKey, keyCode, categoryKey);
    }
}
