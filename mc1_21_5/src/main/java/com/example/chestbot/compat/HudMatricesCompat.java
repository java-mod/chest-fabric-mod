package com.example.chestbot.compat;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;

public final class HudMatricesCompat {
    private HudMatricesCompat() {
    }

    public static void push(DrawContext context) {
        context.getMatrices().push();
    }

    public static void pop(DrawContext context) {
        context.getMatrices().pop();
    }

    public static void translate(DrawContext context, int x, int y) {
        MatrixStack matrices = context.getMatrices();
        matrices.translate((double) x, (double) y, 0.0D);
    }

    public static void scale(DrawContext context, float scale) {
        context.getMatrices().scale(scale, scale, 1.0F);
    }
}
