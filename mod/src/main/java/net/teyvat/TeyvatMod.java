package net.teyvat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.teyvat.command.TeyvatCommand;
import net.teyvat.network.NotesOpenPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TeyvatMod implements ModInitializer {
    public static final String MOD_ID = "teyvat";
    public static final Logger LOGGER = LoggerFactory.getLogger("Teyvat");

    @Override
    public void onInitialize() {
        TeyvatBlocks.register();
        TeyvatBlocks.registerItemGroup();
        PayloadTypeRegistry.playS2C().register(NotesOpenPayload.ID, NotesOpenPayload.CODEC);
        CommandRegistrationCallback.EVENT.register(TeyvatCommand::register);
        LOGGER.info("Teyvat mod initialized: {} blocks registered", TeyvatBlocks.ALL_BLOCKS.size());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal(
                    "§b[Teyvat] §fМод загружен. Заметки путешественника: клавиша §eN§f или §e/teyvat notes§f. "
                    + "Блоки: вкладка §e«Блоки Тейвата»§f в креативе."), false);
        });
    }
}
