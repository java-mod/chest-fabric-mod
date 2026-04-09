package com.example.chestbot.compat;

import io.netty.buffer.Unpooled;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;

import java.util.Base64;

public final class ItemStackVisualCompat {
    private ItemStackVisualCompat() {
    }

    public static String serialize(MinecraftClient client, ItemStack stack) {
        if (client == null || client.world == null || stack == null || stack.isEmpty()) {
            return null;
        }
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(), client.world.getRegistryManager());
        ItemStack.OPTIONAL_PACKET_CODEC.encode(buf, stack.copyWithCount(1));
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(0, bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static ItemStack deserialize(MinecraftClient client, String encoded) {
        if (client == null || client.world == null || encoded == null || encoded.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            RegistryByteBuf buf = new RegistryByteBuf(Unpooled.wrappedBuffer(bytes), client.world.getRegistryManager());
            ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }
}
