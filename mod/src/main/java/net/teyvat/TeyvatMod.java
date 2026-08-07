package net.teyvat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.teyvat.client.paimon.PaimonEntity;
import net.teyvat.command.TeyvatCommand;
import net.teyvat.network.NotesOpenPayload;
import net.teyvat.server.BeachGuard;
import net.teyvat.server.TeyvatQuests;
import net.teyvat.server.TeyvatSpawn;
import net.teyvat.worldgen.TeyvatBeachRadius;
import net.teyvat.worldgen.TeyvatOceanEdge;
import net.teyvat.worldgen.TeyvatXEdge;
import net.teyvat.network.TravelerChoiceOpenPayload;
import net.teyvat.network.TravelerChoicePayload;
import net.teyvat.network.QuestEventPayload;
import net.teyvat.network.QuestStatePayload;
import net.teyvat.quest.Quests;
import net.teyvat.network.TravelerChoiceSyncPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeyvatMod implements ModInitializer {
    public static final String MOD_ID = "teyvat";
    public static final Logger LOGGER = LoggerFactory.getLogger("Teyvat");

    private static final String CHOICE_TAG_PREFIX = "teyvat:traveler_";
    private static final Set<String> VALID_CHOICES = Set.of("lumine", "aether");
    private static final Map<UUID, String> TRAVELER_CHOICES = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        TeyvatOceanEdge.register();
        TeyvatXEdge.register();
        TeyvatBeachRadius.register();
        BeachGuard.register();
        PaimonEntity.register();
        TeyvatBlocks.register();
        TeyvatBlocks.registerItemGroup();
        PayloadTypeRegistry.playS2C().register(NotesOpenPayload.ID, NotesOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TravelerChoiceOpenPayload.ID, TravelerChoiceOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TravelerChoicePayload.ID, TravelerChoicePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(QuestEventPayload.ID, QuestEventPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TravelerChoiceSyncPayload.ID, TravelerChoiceSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QuestStatePayload.ID, QuestStatePayload.CODEC);
        CommandRegistrationCallback.EVENT.register(TeyvatCommand::register);
        ServerLifecycleEvents.SERVER_STARTED.register(TeyvatSpawn::prepare);
        LOGGER.info("Teyvat mod initialized: {} blocks registered", TeyvatBlocks.ALL_BLOCKS.size());

        ServerPlayNetworking.registerGlobalReceiver(TravelerChoicePayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            String choice = payload.choice();
            if (!VALID_CHOICES.contains(choice) || player == null) {
                return;
            }
            player.addCommandTag(CHOICE_TAG_PREFIX + choice);
            TRAVELER_CHOICES.put(player.getUuid(), choice);
            for (ServerPlayerEntity other : player.getEntityWorld().getServer().getPlayerManager().getPlayerList()) {
                ServerPlayNetworking.send(other, new TravelerChoiceSyncPayload(player.getUuid(), choice));
            }
            player.sendMessage(Text.literal(
                    "§e[Teyvat] §fПутешественник выбран: §b" + (choice.equals("lumine") ? "Люмин" : "Итэр")
                            + "§f. Скин увидят все игроки с модом."), false);
        });

        // События квестов от клиента: сервер отмечает выполнение и пишет золотое уведомление.
        ServerPlayNetworking.registerGlobalReceiver(QuestEventPayload.ID, (payload, context) -> {
            if (context.player() != null) {
                TeyvatQuests.complete(context.player(), payload.questId());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal(
                    "§b[Teyvat] §fМод загружен. Блоки: вкладка §e«Блоки Тейвата»§f в креативе."), false);

            String existing = null;
            for (String tag : player.getCommandTags()) {
                if (tag.startsWith(CHOICE_TAG_PREFIX)) {
                    String candidate = tag.substring(CHOICE_TAG_PREFIX.length());
                    if (VALID_CHOICES.contains(candidate)) {
                        existing = candidate;
                    }
                }
            }
            if (existing != null) {
                TRAVELER_CHOICES.put(player.getUuid(), existing);
                ServerPlayNetworking.send(player, new TravelerChoiceSyncPayload(player.getUuid(), existing));
                for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
                    if (!other.getUuid().equals(player.getUuid())) {
                        ServerPlayNetworking.send(other, new TravelerChoiceSyncPayload(player.getUuid(), existing));
                    }
                }
            } else {
                ServerPlayNetworking.send(player, new TravelerChoiceOpenPayload());
            }
            for (Map.Entry<UUID, String> entry : TRAVELER_CHOICES.entrySet()) {
                if (!entry.getKey().equals(player.getUuid())) {
                    ServerPlayNetworking.send(player, new TravelerChoiceSyncPayload(entry.getKey(), entry.getValue()));
                }
            }
            // Состояние квестов: клиент не повторяет уроки, которые уже пройдены.
            ServerPlayNetworking.send(player, new QuestStatePayload(
                    TeyvatQuests.isCompleted(player, Quests.MEET_PAIMON),
                    TeyvatQuests.isCompleted(player, Quests.TRY_SCROLL)));
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                TeyvatSpawn.welcome(handler.getPlayer(), server));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                TRAVELER_CHOICES.remove(handler.getPlayer().getUuid()));
    }
}
