package com.example.chestbot.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;

import java.util.UUID;

public final class PlayerSkinRenderCompat {
    private PlayerSkinRenderCompat() {
    }

    public static boolean drawPlayerHead(DrawContext context, MinecraftClient client, String playerUuid, String playerName, int x, int y, int size) {
        SkinTextures skinTextures = resolveSkinTextures(client, playerUuid, playerName);
        if (skinTextures == null) {
            return false;
        }
        PlayerSkinDrawer.draw(context, skinTextures, x, y, size);
        return true;
    }

    private static SkinTextures resolveSkinTextures(MinecraftClient client, String playerUuid, String playerName) {
        if (client == null) {
            return null;
        }

        PlayerListEntry entry = resolvePlayerEntry(client.getNetworkHandler(), playerUuid, playerName);
        return entry == null ? null : entry.getSkinTextures();
    }

    private static PlayerListEntry resolvePlayerEntry(ClientPlayNetworkHandler networkHandler, String playerUuid, String playerName) {
        if (networkHandler == null) {
            return null;
        }

        if (playerUuid != null && !playerUuid.isBlank()) {
            try {
                PlayerListEntry entry = networkHandler.getPlayerListEntry(UUID.fromString(playerUuid));
                if (entry != null) {
                    return entry;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (playerName != null && !playerName.isBlank()) {
            return networkHandler.getPlayerListEntry(playerName);
        }

        return null;
    }
}
