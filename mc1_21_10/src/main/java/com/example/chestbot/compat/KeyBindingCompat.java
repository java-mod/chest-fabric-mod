package com.example.chestbot.compat;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;

public final class KeyBindingCompat {
    private KeyBindingCompat() {
    }

    public static KeyBinding create(String translationKey, int keyCode, String categoryKey) {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("chestbot", "hud"));
        return new KeyBinding(translationKey, keyCode, category);
    }
}
