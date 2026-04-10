package com.example.chestbot;

import com.example.chestbot.command.ChestBotCommand;
import com.example.chestbot.compat.KeyBindingCompat;
import com.example.chestbot.hud.ChestBotHudRenderer;
import com.example.chestbot.hud.FarmingHudRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

public class ChestBotMod implements ClientModInitializer {

    public static final String MOD_ID = "chestbot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static KeyBinding nextChestHudPageKey;
    private static KeyBinding prevChestHudPageKey;
    private static KeyBinding nextFarmingHudPageKey;
    private static KeyBinding prevFarmingHudPageKey;

    private static BotBridge bridge;

    @Override
    public void onInitializeClient() {
        bridge = new BotBridge();
        registerKeyBindings();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                bridge.startAsync());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                bridge.stop());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                ChestInteractor.tick(client);
                bridge.tickHudRefresh();
                handleHudPageKeys();
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }

            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            ChestInteractor.onBlockInteracted(client, hitResult.getBlockPos());
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }

            FarmingActivityWatcher.onBlockAttacked(net.minecraft.client.MinecraftClient.getInstance(), pos);
            return ActionResult.PASS;
        });

        ClientReceiveMessageEvents.GAME.register(BankDepositWatcher::onGameMessage);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> ChestBotHudRenderer.render(drawContext));

        // 영어/한글 명령어 등록
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                ChestBotCommand.register(dispatcher));
    }

    public static BotBridge getBridge() { return bridge; }

    private static void registerKeyBindings() {
        nextChestHudPageKey = KeyBindingHelper.registerKeyBinding(
                KeyBindingCompat.create("key.chestbot.hud.chest.next", GLFW.GLFW_KEY_RIGHT_BRACKET, "category.chestbot.hud")
        );
        prevChestHudPageKey = KeyBindingHelper.registerKeyBinding(
                KeyBindingCompat.create("key.chestbot.hud.chest.prev", GLFW.GLFW_KEY_LEFT_BRACKET, "category.chestbot.hud")
        );
        nextFarmingHudPageKey = KeyBindingHelper.registerKeyBinding(
                KeyBindingCompat.create("key.chestbot.hud.farming.next", GLFW.GLFW_KEY_APOSTROPHE, "category.chestbot.hud")
        );
        prevFarmingHudPageKey = KeyBindingHelper.registerKeyBinding(
                KeyBindingCompat.create("key.chestbot.hud.farming.prev", GLFW.GLFW_KEY_SEMICOLON, "category.chestbot.hud")
        );
    }

    private static void handleHudPageKeys() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (bridge == null || client == null) {
            return;
        }
        if (client.currentScreen != null) {
            while (nextChestHudPageKey.wasPressed()) {}
            while (prevChestHudPageKey.wasPressed()) {}
            while (nextFarmingHudPageKey.wasPressed()) {}
            while (prevFarmingHudPageKey.wasPressed()) {}
            return;
        }
        while (nextChestHudPageKey.wasPressed()) {
            bridge.nextChestHudPage(ChestBotHudRenderer.getRenderableEntryCount(bridge, false));
            bridge.persistHudLayout();
        }
        while (prevChestHudPageKey.wasPressed()) {
            bridge.previousChestHudPage(ChestBotHudRenderer.getRenderableEntryCount(bridge, false));
            bridge.persistHudLayout();
        }
        while (nextFarmingHudPageKey.wasPressed()) {
            bridge.nextFarmingHudPage(FarmingHudRenderer.getRenderableEntryCount(bridge, false));
            bridge.persistHudLayout();
        }
        while (prevFarmingHudPageKey.wasPressed()) {
            bridge.previousFarmingHudPage(FarmingHudRenderer.getRenderableEntryCount(bridge, false));
            bridge.persistHudLayout();
        }
    }
}
