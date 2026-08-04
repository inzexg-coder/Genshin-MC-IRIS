package net.teyvat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeyvatMod implements ModInitializer {
    public static final String MOD_ID = "teyvat";
    public static final Logger LOGGER = LoggerFactory.getLogger("Teyvat");

    @Override
    public void onInitialize() {
        TeyvatBlocks.register();
        TeyvatBlocks.registerItemGroup();
        TeyvatBlockEntities.register();
        LOGGER.info("Teyvat mod initialized: {} blocks registered", TeyvatBlocks.ALL_BLOCKS.size());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal(
                    "§b[Teyvat] §fМод загружен. Блоки: вкладка §e«Блоки Тейвата»§f в креативе. "
                    + "Проверка: §e/give @s teyvat:marble"), false);
        });
    }
}
