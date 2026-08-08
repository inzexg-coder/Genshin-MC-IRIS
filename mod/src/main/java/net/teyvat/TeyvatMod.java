package net.teyvat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.teyvat.client.paimon.PaimonEntity;
import net.teyvat.command.TeyvatCommand;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.network.DamageNumberPayload;
import net.teyvat.network.ExpGainPayload;
import net.teyvat.network.MobLevelSyncPayload;
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
import net.teyvat.network.ProgressionSyncPayload;
import net.teyvat.network.QuestStatePayload;
import net.teyvat.progression.MobLevels;
import net.teyvat.progression.MobXp;
import net.teyvat.progression.ProgressionStore;
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

    /** Радиус (в блоках), в котором игроку приходят уровни мобов (чуть больше радиуса отрисовки). */
    private static final double MOB_LEVEL_SYNC_RANGE = 64.0;

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
        PayloadTypeRegistry.playS2C().register(ProgressionSyncPayload.ID, ProgressionSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ExpGainPayload.ID, ExpGainPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DamageNumberPayload.ID, DamageNumberPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MobLevelSyncPayload.ID, MobLevelSyncPayload.CODEC);
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
            // Первый персонаж в ростере — выбранный путешественник.
            ProgressionStore.seedTraveler(player, choice);
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

        // Числа урона: когда игрок бьёт живую сущность, сервер шлёт ему урон
        // и уровень моба (для полоски HP над головой). Обработчик не должен
        // бросать исключений — AFTER_DAMAGE вызывается внутри damage() сущности.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, amount, originalAmount, blocked) -> {
            try {
                if (amount <= 0f || !TeyvatConfig.get().health.show_damage_numbers) {
                    return;
                }
                if (source.getAttacker() instanceof ServerPlayerEntity attacker && attacker.isAlive()) {
                    ServerPlayNetworking.send(attacker,
                            new DamageNumberPayload(entity.getId(), amount, MobLevels.getLevel(entity)));
                }
            } catch (Exception e) {
                LOGGER.error("Ошибка отправки числа урона для {}: {}", entity.getType(), e.toString());
            }
        });

        // Уровни мобов: назначаются при загрузке в мир, растут от расстояния до спавна.
        // После назначения шлём уровень игрокам рядом — клиент показывает подпись «Ур. X»
        // над всеми мобами, а не только после удара. Ошибки здесь не должны ломать загрузку
        // сущности в мир (тот же хук, что и назначение уровня).
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            MobLevels.onEntityLoad(entity, world);
            try {
                if (entity instanceof LivingEntity living && !(entity instanceof PlayerEntity)
                        && !(entity instanceof ArmorStandEntity)) {
                    MobLevelSyncPayload payload = new MobLevelSyncPayload(entity.getId(), MobLevels.getLevel(living));
                    double range = MOB_LEVEL_SYNC_RANGE * MOB_LEVEL_SYNC_RANGE;
                    for (ServerPlayerEntity player : world.getPlayers(p -> p.squaredDistanceTo(entity) <= range)) {
                        ServerPlayNetworking.send(player, payload);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Ошибка рассылки уровня моба {}: {}", entity.getType(), e.toString());
            }
        });

        // Опыт за убийства с анти-фармом: прогресс в NBT игрока, тост на экран.
        // AFTER_DEATH вызывается внутри обработки смерти — ошибка здесь не должна
        // ломать смерть и выпадение дропа.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            try {
                if (entity instanceof PlayerEntity || entity instanceof ArmorStandEntity) {
                    return;
                }
                if (!(damageSource.getAttacker() instanceof ServerPlayerEntity player)) {
                    return;
                }
                long xp = MobXp.xpForKill(player, entity);
                if (xp <= 0) {
                    return;
                }
                boolean rankUp = ProgressionStore.addArExp(player, xp);
                ServerPlayNetworking.send(player, new ExpGainPayload(xp, rankUp));
            } catch (Exception e) {
                LOGGER.error("Ошибка начисления опыта за убийство {}: {}", entity.getType(), e.toString());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            // Здоровье героя как в Genshin: 912 HP (атрибут сохраняется между смертями).
            var healthAttr = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
            if (healthAttr != null) {
                healthAttr.setBaseValue(TeyvatConfig.get().health.max_health);
            }
            player.setHealth(player.getMaxHealth());
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
                    TeyvatQuests.isCompleted(player, Quests.TRY_SCROLL),
                    TeyvatQuests.isCompleted(player, Quests.TRY_ZOOM),
                    TeyvatQuests.isCompleted(player, Quests.TRY_SPRINT),
                    TeyvatQuests.isCompleted(player, Quests.TRY_DASH)));
            // Прогрессия: ранг, опыт, примогемы, ростера персонажей.
            ProgressionStore.sync(player);
            // Уровни уже загруженных мобов рядом: ENTITY_LOAD для них уже отработал,
            // поэтому шлём текущие уровни при входе игрока.
            for (ServerWorld world : server.getWorlds()) {
                if (world.getRegistryKey() != player.getEntityWorld().getRegistryKey()) {
                    continue;
                }
                BlockPos pos = player.getBlockPos();
                Box box = new Box(pos.getX() - MOB_LEVEL_SYNC_RANGE, pos.getY() - MOB_LEVEL_SYNC_RANGE, pos.getZ() - MOB_LEVEL_SYNC_RANGE,
                        pos.getX() + MOB_LEVEL_SYNC_RANGE, pos.getY() + MOB_LEVEL_SYNC_RANGE, pos.getZ() + MOB_LEVEL_SYNC_RANGE);
                for (LivingEntity mob : world.getEntitiesByType(TypeFilter.instanceOf(LivingEntity.class), box,
                        e -> !(e instanceof PlayerEntity) && !(e instanceof ArmorStandEntity))) {
                    ServerPlayNetworking.send(player, new MobLevelSyncPayload(mob.getId(), MobLevels.getLevel(mob)));
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                TeyvatSpawn.welcome(handler.getPlayer(), server));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TRAVELER_CHOICES.remove(handler.getPlayer().getUuid());
            ProgressionStore.onDisconnect(handler.getPlayer());
            MobXp.onDisconnect(handler.getPlayer());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MobXp.resetSession());
    }
}
