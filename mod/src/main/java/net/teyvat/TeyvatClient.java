package net.teyvat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.text.Text;

import net.teyvat.block.entity.MarbleDoorRenderer;

public class TeyvatClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRendererRegistry.register(TeyvatBlockEntities.MARBLE_DOOR, MarbleDoorRenderer::new);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§e[Teyvat 0.6.2] §7Вкладка «Teyvat» в креативе, команда /give @p teyvat:marble_door"),
                        false);
            }
        });
    }
}
