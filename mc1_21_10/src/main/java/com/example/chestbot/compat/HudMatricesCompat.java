package com.example.chestbot.compat;

import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2fStack;

public final class HudMatricesCompat {
    private HudMatricesCompat() {
    }

    public static void push(DrawContext context) {
        context.getMatrices().pushMatrix();
    }

    public static void pop(DrawContext context) {
        context.getMatrices().popMatrix();
    }

    public static void translate(DrawContext context, int x, int y) {
        Matrix3x2fStack matrices = context.getMatrices();
        matrices.translate((float) x, (float) y);
    }

    public static void scale(DrawContext context, float scale) {
        context.getMatrices().scale(scale, scale);
    }
}
