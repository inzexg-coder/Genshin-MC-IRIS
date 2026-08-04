package net.teyvat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.text.Text;

public class TeyvatClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§a[Teyvat-Клиент] §fМод активен на клиенте: текстуры и вкладка «Блоки Тейвата» загружены."),
                        false);
            }
        });
    }
}
