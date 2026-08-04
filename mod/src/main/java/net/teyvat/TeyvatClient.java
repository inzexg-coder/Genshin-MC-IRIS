package net.teyvat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.text.Text;

import net.teyvat.block.entity.MarbleTallDoorRenderer;

public class TeyvatClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BlockEntityRendererRegistry.register(TeyvatBlockEntities.MARBLE_TALL_DOOR, MarbleTallDoorRenderer::new);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§a[Teyvat-Клиент] §fМод активен на клиенте: текстуры и вкладка «Блоки Тейвата» загружены."),
                        false);
            }
        });
    }
}
