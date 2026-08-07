package net.teyvat.client.paimon;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.TintedParticleEffect;
import net.minecraft.text.Text;
import net.teyvat.client.TravelerChoiceScreen;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.client.ChatFlash;
import net.teyvat.client.TravelerChoiceClient;
import net.teyvat.network.QuestEventPayload;
import net.teyvat.quest.Quests;
import net.teyvat.config.TeyvatConfig;

/**
 * Жизненный цикл Паймон на клиенте: одна сущность на игрока.
 * Появляется после выбора героя (или при повторном входе), сама чинит себя:
 * если сущность пропала или игрок сменил измерение — создаёт заново.
 * Вся клиентская логика (цели полёта, реплики) живёт здесь, чтобы PaimonEntity
 * оставалась сервер-совместимой.
 */
public final class PaimonManager {
    /** Как далеко сбоку (справа) от героя держится Паймон — как в Genshin, всегда в поле зрения.
     *  Боковая позиция не зависит от того, куда герой бежит: она не попадает в лицо и не прячется за спину. */
    private static final double FOLLOW_SIDE = 1.0;
    /** Высота полёта: на уровне головы героя. */
    private static final double FOLLOW_UP = 1.6;
    /** Сглаживание угла позиции: быстрые повороты мыши не дёргают Паймон. */
    private static final float REF_YAW_LERP = 0.05f;
    /** Плавность полёта к цели (доля оставшегося пути за тик) — без рывков и тряски. */
    private static final double FOLLOW_EASE = 0.16;
    /** Если Паймон отстала дальше этого расстояния — телепорт к игроку. */
    private static final double TELEPORT_DIST = 16.0;
    /** Фразы Паймон: короткое введение в мир, представление Паймон и призыв в путь. */
    private static final String PHRASE_1 = "Ты наконец очнулся, путешественник! Это берега Тейвата — отсюда начинается твоя история.";
    private static final String PHRASE_2 = "Меня зовут Паймон! Лучший гид Тейвата... и теперь твой спутник.";
    private static final String PHRASE_3 = "Ну что, давай в путь! Впереди Мондштадт и большие приключения.";
    /** Тик первой фразы знакомства. */
    private static final int PHRASE_1_TICK = 50;
    /** Тик второй фразы знакомства. */
    private static final int PHRASE_2_TICK = 200;
    /** Задержка отчёта о выполненном задании после последней фразы Паймон (тики). */
    private static final int QUEST_REPORT_TICKS = 80;
    /** Дистанция до игрока во время знакомства. */
    private static final double INTRO_DIST = 2.4;
    /** Высота над игроком во время знакомства (чуть выше линии взгляда). */
    private static final double INTRO_UP = 1.4;

    private static PaimonEntity paimon;
    /** Сглаженный угол, от которого зависит позиция Паймон (не дёргается от взгляда). */
    private static float refYaw;
    private static boolean refYawReady;
    /** Счётчик для шлейфа: порция частиц каждый 2-й тик. */
    private static int trailTimer;
    /** Высота середины тела Паймон над ногами — отсюда идёт шлейф. */
    private static final double TRAIL_UP = 0.45;
    /** Абсолютная точка знакомства: Паймон не сдвигается с неё, пока говорит. */
    private static Vec3d introPos;
    /** Таймер отчёта о квесте: -1 = не идёт, 0..QUEST_REPORT_TICKS = отсчёт после фразы. */
    private static int questReportTimer = -1;

    private PaimonManager() {}

    /** Вызывается каждый клиентский тик. */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            remove();
            return;
        }
        // Пока открыт экран выбора героя, Паймон не появляется: знакомство
        // начнётся заново после выбора, а не проиграется незаметно за меню.
        // Если экран уже закрывается вспышкой — Паймон остаётся: сцена идёт за ней.
        if (client.currentScreen instanceof TravelerChoiceScreen screen && !screen.isClosing()) {
            remove();
            return;
        }
        if (paimon != null && !paimon.isRemoved()) {
            if (paimon.getEntityWorld() != client.world) {
                remove();
                return;
            }
            updatePaimon(client, paimon);
            return;
        }
        // Паймон живёт с игроком, у которого уже выбран путешественник.
        if (TeyvatConfig.get().paimon.enabled
                && TravelerChoiceClient.get(client.player.getUuid()) != null) {
            startIntro();
        }
    }

    /** Появление Паймон перед игроком с коротким знакомством с миром. */
    public static void startIntro() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || !(client.world instanceof ClientWorld world)) {
            return;
        }
        if (paimon != null && !paimon.isRemoved()) {
            return;
        }
        if (!TeyvatConfig.get().paimon.enabled) {
            return;
        }
        PaimonEntity entity = new PaimonEntity(PaimonEntity.TYPE, world);
        entity.setOwner(client.player.getUuid());
        refYaw = client.player.getYaw();
        refYawReady = true;
        questReportTimer = -1;
        // Точка знакомства фиксируется абсолютно: Паймон стоит на месте, пока говорит.
        introPos = playerPos(client.player).add(forwardDeg(refYaw, INTRO_DIST)).add(0.0, INTRO_UP, 0.0);
        entity.setPosition(introPos.x, introPos.y, introPos.z);
        entity.setYaw(client.player.getYaw());
        world.addEntity(entity);
        paimon = entity;
    }

    private static void updatePaimon(MinecraftClient client, PaimonEntity entity) {
        AbstractClientPlayerEntity player = client.player;
        if (player == null || !player.getUuid().equals(entity.getOwnerUuid())) {
            remove();
            return;
        }

        // Отчёт о выполненном задании приходит через несколько секунд
        // после последней фразы Паймон, а не сразу.
        if (questReportTimer >= 0) {
            questReportTimer++;
            if (questReportTimer >= QUEST_REPORT_TICKS) {
                questReportTimer = -1;
                reportQuestMeetPaimon();
            }
        }

        if (!refYawReady) {
            refYaw = player.getYaw();
            refYawReady = true;
        }

        if (!entity.isFollowing()) {
            int ticks = entity.getIntroTicks() + 1;
            entity.setIntroTicks(ticks);
            // Реплики в чат, неспешно: сначала игрок приходит в себя, потом Паймон говорит.
            if (ticks == PHRASE_1_TICK) {
                say(player, PHRASE_1);
            } else if (ticks == PHRASE_2_TICK) {
                say(player, PHRASE_2);
            } else if (ticks >= entity.getIntroTicksLimit()) {
                entity.setFollowing(true);
                say(player, PHRASE_3);
                questReportTimer = 0;
            }
        } else {
            // Плавно доводим угол: быстрые повороты мыши почти не сдвигают позицию Паймон.
            refYaw = MathHelper.lerpAngleDegrees(REF_YAW_LERP, refYaw, player.getYaw());
        }

        Vec3d target;
        if (entity.isFollowing()) {
            target = followTarget(player, refYaw, entity);
        } else {
            // Во время знакомства Паймон не двигается: стоит на зафиксированной точке
            // и только поворачивается лицом к игроку.
            target = introPos;
        }
        if (entity.squaredDistanceTo(target) >= TELEPORT_DIST * TELEPORT_DIST) {
            entity.refreshPositionAfterTeleport(target);
            entity.setYaw(faceYaw(entity, player));
            entity.clearTrail();
            entity.pushTrailPoint(entityPos(entity).add(0.0, TRAIL_UP, 0.0));
            return;
        }

        // Плавное сближение: каждый тик проходим долю оставшегося пути,
        // поэтому Паймон замедляется у цели и не дрожит вокруг неё.
        Vec3d delta = target.subtract(entityPos(entity));
        double dist = delta.length();
        if (dist >= 0.01) {
            Vec3d move = delta.multiply(Math.min(1.0, FOLLOW_EASE));
            entity.setPosition(entity.getX() + move.x, entity.getY() + move.y, entity.getZ() + move.z);
        }
        entity.setYaw(faceYaw(entity, player));
        entity.setPitch(0.0f);
        if (entity.isFollowing()) {
            entity.pushTrailPoint(entityPos(entity).add(0.0, TRAIL_UP, 0.0));
            spawnGoldenTrail((ClientWorld) client.world, entity);
        }
    }

    /** Шлейф из частиц фейерверка: яркие золотые искры и крупные вспышки.
     *  FIREWORK — светящаяся искра фейерверка, FLASH — большая яркая вспышка взрыва
     *  (та самая, что вспыхивает, когда фейерверк разрывается). Обе не зависят от
     *  освещения и видны при любом шейдере. */
    private static void spawnGoldenTrail(ClientWorld world, PaimonEntity entity) {
        if (trailTimer++ % 2 != 0) {
            return;
        }
        var random = world.random;
        double x = entity.getX();
        double y = entity.getY() + TRAIL_UP;
        double z = entity.getZ();
        // Искры фейерверка: разлетаются в стороны и вниз, оставляя за собой дорожку.
        for (int i = 0; i < 4; i++) {
            world.addParticleClient(ParticleTypes.FIREWORK,
                    x + (random.nextDouble() - 0.5) * 0.6,
                    y + (random.nextDouble() - 0.5) * 0.6,
                    z + (random.nextDouble() - 0.5) * 0.6,
                    (random.nextDouble() - 0.5) * 0.09, -0.03, (random.nextDouble() - 0.5) * 0.09);
        }
        // Большая золотая вспышка взрыва — яркое золотое свечение вокруг Паймон.
        world.addParticleClient(TintedParticleEffect.create(ParticleTypes.FLASH, 1.0f, 0.85f, 0.35f),
                x + (random.nextDouble() - 0.5) * 0.4,
                y + (random.nextDouble() - 0.5) * 0.4,
                z + (random.nextDouble() - 0.5) * 0.4,
                0.0, -0.02, 0.0);
        // Светящаяся золотая пыль добавляет плотность дорожке.
        for (int i = 0; i < 2; i++) {
            world.addParticleClient(new DustColorTransitionParticleEffect(0xFFE066, 0xFFF7CC, 1.2f),
                    x + (random.nextDouble() - 0.5) * 0.8,
                    y + (random.nextDouble() - 0.5) * 0.8,
                    z + (random.nextDouble() - 0.5) * 0.8,
                    (random.nextDouble() - 0.5) * 0.03, -0.03, (random.nextDouble() - 0.5) * 0.03);
        }
    }

    /** Цель полёта: Паймон держится за спиной героя и очень плавно «переплывает»
     *  то влево, то вправо (медленный синус по бокам), никогда не оказываясь перед
     *  лицом. По вертикали слегка выныривает и ныряет, поэтому не летит по ровной
     *  линии. Точка привязана к сглаженному взгляду, так что при повороте героя
     *  Паймон мягко дрейфует и остаётся за спиной. */
    private static Vec3d followTarget(AbstractClientPlayerEntity player, float yaw, PaimonEntity entity) {
        double orbit = entity.age * 0.045;
        double side = Math.sin(orbit) * FOLLOW_SIDE * 1.05;
        double bob = Math.sin(entity.age * 0.12 + 1.7) * 0.12;
        return playerPos(player)
                .add(sideDeg(yaw, side))
                .add(forwardDeg(yaw, -0.5))
                .add(0.0, FOLLOW_UP + bob, 0.0);
    }

    /** Направление вбок (перпендикулярно взгляду) для позиции сбоку от героя. */
    private static Vec3d sideDeg(float yaw, double dist) {
        return forwardDeg(yaw + 90.0f, dist);
    }

    /** Направление «вперёд» при данном угле. Отрицательная дистанция — за спину. */
    private static Vec3d forwardDeg(float yaw, double dist) {
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad)).multiply(dist);
    }

    private static Vec3d playerPos(AbstractClientPlayerEntity player) {
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    private static Vec3d entityPos(PaimonEntity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    /** Minecraft-yaw, при котором сущность смотрит на игрока. */
    private static float faceYaw(PaimonEntity entity, AbstractClientPlayerEntity player) {
        double dx = player.getX() - entity.getX();
        double dz = player.getZ() - entity.getZ();
        return (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
    }

    private static void say(AbstractClientPlayerEntity player, String text) {
        player.sendMessage(Text.literal("§fПаймон§7: §f" + text), false);
    }

    /** Квест «Познакомиться с Паймон»: сообщаем серверу, что знакомство завершилось,
     *  и зажигаем яркую вспышку в чате. */
    private static void reportQuestMeetPaimon() {
        if (MinecraftClient.getInstance().getNetworkHandler() != null) {
            ClientPlayNetworking.send(new QuestEventPayload(Quests.MEET_PAIMON));
        }
        ChatFlash.trigger();
    }

    public static void remove() {
        if (paimon != null && !paimon.isRemoved()) {
            paimon.discard();
        }
        paimon = null;
    }

    public static boolean isActive() {
        return paimon != null && !paimon.isRemoved();
    }

    /** Идёт ли сейчас знакомство с Паймон: HUD скрыт, пока она представляется. */
    public static boolean isIntroActive() {
        return paimon != null && !paimon.isRemoved() && !paimon.isFollowing();
    }
}
