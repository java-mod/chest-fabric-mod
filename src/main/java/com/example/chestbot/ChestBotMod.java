package com.example.chestbot;

import com.example.chestbot.command.ChestBotCommand;
import com.example.chestbot.hud.ChestBotHudRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChestBotMod implements ClientModInitializer {

    public static final String MOD_ID = "chestbot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static BotBridge bridge;

    @Override
    public void onInitializeClient() {
        bridge = new BotBridge();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                bridge.startAsync());

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                bridge.stop());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                ChestInteractor.tick(client);
                bridge.tickHudRefresh();
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient()) {
                return ActionResult.PASS;
            }

            ChestInteractor.onBlockInteracted(net.minecraft.client.MinecraftClient.getInstance(), hitResult.getBlockPos());
            return ActionResult.PASS;
        });

        ClientReceiveMessageEvents.GAME.register(BankDepositWatcher::onGameMessage);
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> ChestBotHudRenderer.render(drawContext));

        // 영어/한글 명령어 등록
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                ChestBotCommand.register(dispatcher));
    }

    public static BotBridge getBridge() { return bridge; }
}
