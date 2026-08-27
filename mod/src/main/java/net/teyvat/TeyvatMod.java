package net.teyvat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.teyvat.client.paimon.PaimonEntity;
import net.teyvat.entity.HydroSlimeEntity;
import net.teyvat.entity.HydroSlimeProjectileEntity;
import net.teyvat.item.TeyvatItems;
import net.teyvat.particle.TeyvatParticles;
import net.teyvat.command.TeyvatCommand;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.network.AttackResultPayload;
import net.teyvat.network.DamageNumberPayload;
import net.teyvat.network.ExpGainPayload;
import net.teyvat.network.MobLevelSyncPayload;
import net.teyvat.network.AdminNotesRequestPayload;
import net.teyvat.network.NotesOpenPayload;
import net.teyvat.server.BeachBoundary;
import net.teyvat.server.BeachGuard;
import net.teyvat.server.ItemPickup;
import net.teyvat.server.PickupSelfTest;
import net.teyvat.server.PlayerCombat;
import net.teyvat.server.SlimeTraining;
import net.teyvat.server.TeyvatQuests;
import net.teyvat.server.TeyvatSpawn;
import net.teyvat.worldgen.DragonRidgeTrailFeature;
import net.teyvat.worldgen.TeyvatBeachRadius;
import net.teyvat.worldgen.TeyvatDragonRidge;
import net.teyvat.worldgen.TeyvatOceanEdge;
import net.teyvat.worldgen.TeyvatXEdge;
import net.teyvat.network.TravelerChoiceOpenPayload;
import net.teyvat.network.TravelerChoicePayload;
import net.teyvat.network.QuestEventPayload;
import net.teyvat.network.QuestCompletePayload;
import net.teyvat.network.ResourceGainPayload;
import net.teyvat.network.SlimeTrainingSpawnPayload;
import net.teyvat.network.PlayerAttackPayload;
import net.teyvat.network.ProgressionSyncPayload;
import net.teyvat.network.QuestStatePayload;
import net.teyvat.network.PickupRequestPayload;
import net.teyvat.network.WikiStatePayload;
import net.teyvat.network.WikiDiscoveryPayload;
import net.teyvat.network.TeleportActivatePayload;
import net.teyvat.network.TeleportStatePayload;
import net.teyvat.network.SkipTrainingPayload;
import net.teyvat.network.MinimapSyncPayload;
import net.teyvat.network.MinimapExplorePayload;
import net.teyvat.server.TeleportActivationManager;
import net.teyvat.progression.MobLevels;
import net.teyvat.progression.MobXp;
import net.teyvat.progression.ProgressionStore;
import net.teyvat.player.TravelerProfile;
import net.teyvat.quest.Quests;
import net.teyvat.network.TravelerChoiceSyncPayload;
import net.teyvat.server.WikiDiscoveries;
import net.teyvat.wiki.TeyvatWiki;
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

    /** Мирные мобы: домашние/пассивные животные и водные создания. */
    private static boolean isPeacefulMob(LivingEntity entity) {
        return entity instanceof PassiveEntity
                || entity instanceof FishEntity
                || entity instanceof SquidEntity;
    }

    /** Деревянный меч героя всегда в первом слоте (как в Genshin — меч при себе).
     *  Кладём только если слот пуст, чтобы не перезаписывать вещи игрока. */
    private static void ensureStarterSword(ServerPlayerEntity player) {
        if (player.getInventory().getStack(0).isEmpty()) {
            player.getInventory().setStack(0, new ItemStack(TeyvatItems.DULL_BLADE));
        }
        // Открыть запись о мече в Энциклопедии при первом входе.
        WikiDiscoveries.discover(player, TeyvatWiki.ID_DULL_BLADE);
    }

    @Override
    public void onInitialize() {
        TeyvatOceanEdge.register();
        TeyvatXEdge.register();
        TeyvatBeachRadius.register();
        TeyvatDragonRidge.register();
        DragonRidgeTrailFeature.register();
        BeachGuard.register();
        BeachBoundary.register();
        PaimonEntity.register();
        HydroSlimeEntity.register();
        HydroSlimeProjectileEntity.register();
        TeyvatParticles.register();
        TeyvatItems.register();
        FabricDefaultAttributeRegistry.register(HydroSlimeEntity.TYPE, HydroSlimeEntity.createAttributes());
        TeyvatBlocks.register();
        TeyvatBlocks.registerItemGroup();
        PayloadTypeRegistry.playS2C().register(NotesOpenPayload.ID, NotesOpenPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TravelerChoiceOpenPayload.ID, TravelerChoiceOpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TravelerChoicePayload.ID, TravelerChoicePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(QuestEventPayload.ID, QuestEventPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SlimeTrainingSpawnPayload.ID, SlimeTrainingSpawnPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PlayerAttackPayload.ID, PlayerAttackPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PickupRequestPayload.ID, PickupRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AdminNotesRequestPayload.ID, AdminNotesRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QuestCompletePayload.ID, QuestCompletePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TravelerChoiceSyncPayload.ID, TravelerChoiceSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(QuestStatePayload.ID, QuestStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WikiStatePayload.ID, WikiStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(WikiDiscoveryPayload.ID, WikiDiscoveryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ProgressionSyncPayload.ID, ProgressionSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ExpGainPayload.ID, ExpGainPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DamageNumberPayload.ID, DamageNumberPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ResourceGainPayload.ID, ResourceGainPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AttackResultPayload.ID, AttackResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MobLevelSyncPayload.ID, MobLevelSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TeleportActivatePayload.ID, TeleportActivatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SkipTrainingPayload.ID, SkipTrainingPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(TeleportStatePayload.ID, TeleportStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MinimapSyncPayload.ID, MinimapSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MinimapExplorePayload.ID, MinimapExplorePayload.CODEC);
        // Shift+N: «О сборке» — только для администраторов мира/сервера.
        ServerPlayNetworking.registerGlobalReceiver(AdminNotesRequestPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player == null) {
                return;
            }
            if (player.hasPermissionLevel(2)) {
                ServerPlayNetworking.send(player, new NotesOpenPayload());
            } else {
                player.sendMessage(Text.literal("§e[Teyvat] §f«О сборке» доступно только администраторам мира."), false);
            }
        });

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
            TravelerProfile.fromChoice(choice).applyAttributes(player);
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

        // Паймон объявила задание про слаймов: сервер призывает трёх тренировочных
        // Гидро слаймов вокруг игрока (бьются только владельцем).
        ServerPlayNetworking.registerGlobalReceiver(SlimeTrainingSpawnPayload.ID, (payload, context) -> {
            if (context.player() != null) {
                SlimeTraining.spawnAround(context.player());
            }
        });

        // Удар комбо путешественника: сервер ищет цели в конусе перед игроком
        // и наносит урон с множителем текущего удара (как размах мечом в Genshin).
        ServerPlayNetworking.registerGlobalReceiver(PlayerAttackPayload.ID, (payload, context) -> {
            if (context.player() != null) {
                PlayerCombat.onAttack(context.player(), payload.hitIndex(), payload.chargeLevel());
            }
        });

        // F: игрок подбирает ближайший предмет с земли (автоподбор отключён).
        ServerPlayNetworking.registerGlobalReceiver(PickupRequestPayload.ID, (payload, context) -> {
            if (context.player() != null) {
                ItemPickup.onPickupRequest(context.player());
            }
        });

        // Q: активация точки телепортации — клиент нажал Q рядом с красной плитой.
        ServerPlayNetworking.registerGlobalReceiver(TeleportActivatePayload.ID, (payload, context) -> {
            if (context.player() != null) {
                TeleportActivationManager.tryActivate(context.player(), payload.pos());
            }
        });

        // Миникарта: клиент прислал новые исследованные чанки — сохраняем в теги.
        ServerPlayNetworking.registerGlobalReceiver(MinimapExplorePayload.ID, (payload, context) -> {
            if (context.player() != null) {
                ServerPlayerEntity p = context.player();
                for (long[] cz : payload.chunks()) {
                    String tag = "teyvat:map_" + cz[0] + "_" + cz[1];
                    if (!p.getCommandTags().contains(tag)) {
                        p.addCommandTag(tag);
                    }
                }
            }
        });

        // X: пропуск обучения — сервер отмечает все задания выполненными.
        ServerPlayNetworking.registerGlobalReceiver(SkipTrainingPayload.ID, (payload, context) -> {
            if (context.player() != null) {
                ServerPlayerEntity p = context.player();
                BeachBoundary.markSkipped(p.getUuid());
                TeyvatQuests.complete(p, Quests.MEET_PAIMON);
                TeyvatQuests.complete(p, Quests.TRY_SCROLL);
                TeyvatQuests.complete(p, Quests.TRY_ZOOM);
                TeyvatQuests.complete(p, Quests.TRY_SPRINT);
                TeyvatQuests.complete(p, Quests.TRY_DASH);
                TeyvatQuests.complete(p, Quests.TRY_ATTACK);
                TeyvatQuests.complete(p, Quests.TRY_PICKUP);
                // Отправляем обновлённое состояние квестов клиенту
                ServerPlayNetworking.send(p, new QuestStatePayload(
                        TeyvatQuests.isCompleted(p, Quests.MEET_PAIMON),
                        TeyvatQuests.isCompleted(p, Quests.TRY_SCROLL),
                        TeyvatQuests.isCompleted(p, Quests.TRY_ZOOM),
                        TeyvatQuests.isCompleted(p, Quests.TRY_SPRINT),
                        TeyvatQuests.isCompleted(p, Quests.TRY_DASH),
                        TeyvatQuests.isCompleted(p, Quests.TRY_ATTACK),
                        TeyvatQuests.isCompleted(p, Quests.TRY_PICKUP)));
                p.sendMessage(Text.literal("§b[Teyvat] §fОбучение пропущено! Добро пожаловать в Тейват!"), false);
            }
        });

        // Запрет атаковать мирных мобов (по флагу world.no_attack_peaceful):
        // отменяем урон, если его источник — игрок (включая стрелы и т.п.).
        // ALLOW_DAMAGE вызывается внутри damage() — не должен бросать исключений.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            try {
                if (!TeyvatConfig.get().world.no_attack_peaceful) {
                    return true;
                }
                if (source.getAttacker() instanceof PlayerEntity || source.getSource() instanceof PlayerEntity) {
                    return !isPeacefulMob(entity);
                }
                return true;
            } catch (Exception e) {
                return true;
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
                // Первый опыт — открывает запись «Опыт и Ранг Приключений».
                WikiDiscoveries.discover(player, TeyvatWiki.ID_EXP);
            } catch (Exception e) {
                LOGGER.error("Ошибка начисления опыта за убийство {}: {}", entity.getType(), e.toString());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            ensureStarterSword(player);
            // Здоровье героя как в Genshin: 912 HP (атрибут сохраняется между смертями).
            var healthAttr = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
            if (healthAttr != null) {
                healthAttr.setBaseValue(TeyvatConfig.get().health.max_health);
            }
            // Базовая атака как в Genshin: 17.81 (Aether Lv1).
            var attackAttr = player.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
            if (attackAttr != null) {
                attackAttr.setBaseValue(17.81);
            }
            player.setHealth(player.getMaxHealth());
            String version = "?";
            try {
                version = FabricLoader.getInstance().getModContainer(MOD_ID)
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
            } catch (Exception ignored) {
                // версия в сообщении — для удобства, ошибка не должна ломать вход
            }
            player.sendMessage(Text.literal(
                    "§b[Teyvat] §fМод загружен §7(v" + version + ")§f · автоподбор §aВЫКЛ§f (подбор на §eF§f). "
                            + "Блоки: вкладка §e«Блоки Тейвата»§f в креативе."), false);

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
                TravelerProfile.fromChoice(existing).applyAttributes(player);
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
                    TeyvatQuests.isCompleted(player, Quests.TRY_DASH),
                    TeyvatQuests.isCompleted(player, Quests.TRY_ATTACK),
                    TeyvatQuests.isCompleted(player, Quests.TRY_PICKUP)));
            // Вики: пляж открыт с первого входа, остальные записи — по встречам.
            WikiDiscoveries.discover(player, TeyvatWiki.ID_BEACH);
            ServerPlayNetworking.send(player, new WikiStatePayload(WikiDiscoveries.discoveredIds(player)));
            // Прогрессия: ранг, опыт, примогемы, ростера персонажей.
            ProgressionStore.sync(player);
            // Активированные точки телепортации: клиент заменит красные блоки на синие.
            TeleportActivationManager.syncToClient(player);
            // Миникарта: отправляем список исследованных чанков
            for (String tag : player.getCommandTags()) {
                if (tag.startsWith("teyvat:map_")) {
                    // Теги уже есть — клиент загрузит их
                }
            }
            ServerPlayNetworking.send(player, new net.teyvat.network.MinimapSyncPayload(
                    new java.util.ArrayList<>(player.getCommandTags().stream()
                            .filter(t -> t.startsWith("teyvat:map_"))
                            .toList())));
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

        // После смерти/респавна меч снова оказывается в первом слоте.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            ensureStarterSword(newPlayer);
            TravelerProfile.fromPlayer(newPlayer).applyAttributes(newPlayer);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            TRAVELER_CHOICES.remove(handler.getPlayer().getUuid());
            ProgressionStore.onDisconnect(handler.getPlayer());
            MobXp.onDisconnect(handler.getPlayer());
            PlayerCombat.onDisconnect(handler.getPlayer());
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MobXp.resetSession());

        // Автотест автоподбора (/teyvat selftest): тикает обратный отсчёт.
        ServerTickEvents.END_SERVER_TICK.register(TeyvatSpawn::serverTickMaybeBuildTeleport);
                ServerTickEvents.END_SERVER_TICK.register(server -> PickupSelfTest.tick(server));
        // Хит-стоп врагов: замирание на ударе, затем отброс (реакция на удар).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerWorld world : server.getWorlds()) {
                PlayerCombat.tick(world);
            }
        });
    }
}
