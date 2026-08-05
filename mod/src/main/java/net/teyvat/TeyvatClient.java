package net.teyvat;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.text.Text;

public class TeyvatClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Прорезные блоки (chiseled/арка/ворота): настоящие окна-прорези через CUTOUT-слой
        BlockRenderLayerMap.putBlocks(BlockRenderLayer.CUTOUT,
                TeyvatBlocks.CHISELED_MARBLE, TeyvatBlocks.MARBLE_ARCH, TeyvatBlocks.MARBLE_GATE);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(
                        "§e[Teyvat 0.7.1] §7Вкладка «Teyvat» в креативе, команда /give @p teyvat:marble_door"),
                        false);
            }
        });
    }
}
