package net.teyvat.client.paimon;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.teyvat.client.TravelerChoiceScreen;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.client.TravelerChoiceClient;
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
    /** Фразы Паймон: показываются внизу экрана (action bar) с паузами между ними. */
    private static final String PHRASE_1 = "Ой! Ты наконец проснулся, путешественник? Паймон уже думала, ты будешь спать вечно!";
    private static final String PHRASE_2 = "Это пляж Тейвата. За холмами стоит Мондштадт — город свободы. Оттуда всё и начинается.";
    private static final String PHRASE_3 = "Пойдём! Паймон покажет дорогу и будет рядом, куда бы ты ни пошёл.";
    /** Сколько тиков финальная фраза держится на экране после знакомства. */
    private static final int POST_INTRO_TICKS = 70;
    /** Дистанция до игрока во время знакомства. */
    private static final double INTRO_DIST = 2.4;
    /** Высота над игроком во время знакомства (чуть выше линии взгляда). */
    private static final double INTRO_UP = 1.4;

    private static PaimonEntity paimon;
    /** Сглаженный угол, от которого зависит позиция Паймон (не дёргается от взгляда). */
    private static float refYaw;
    private static boolean refYawReady;
    /** Счётчик для дорожки свечения. */
    private static int trailTimer;
    /** Прошлая позиция Паймон: по отрезку пути рисуем шлейф. */
    private static Vec3d lastTrailPos;
    private static boolean hasLastTrailPos;
    /** Оставшиеся тики финальной фразы на экране после знакомства. */
    private static int postIntroTicks;
    /** Абсолютная точка знакомства: Паймон не сдвигается с неё, пока говорит. */
    private static Vec3d introPos;

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

        if (!refYawReady) {
            refYaw = player.getYaw();
            refYawReady = true;
        }

        if (!entity.isFollowing()) {
            int ticks = entity.getIntroTicks() + 1;
            entity.setIntroTicks(ticks);
            int limit = entity.getIntroTicksLimit();
            // Диалог внизу экрана: фраза — пауза — фраза — пауза — переход к полёту.
            int p1End = (int) (limit * 0.45);
            int p2Start = (int) (limit * 0.55);
            int p2End = (int) (limit * 0.9);
            if (ticks < p1End) {
                showActionBar(player, PHRASE_1);
            } else if (ticks < p2Start) {
                clearActionBar(player);
            } else if (ticks < p2End) {
                showActionBar(player, PHRASE_2);
            } else if (ticks < limit) {
                clearActionBar(player);
            } else {
                entity.setFollowing(true);
                postIntroTicks = POST_INTRO_TICKS;
            }
        } else {
            // Плавно доводим угол: быстрые повороты мыши почти не сдвигают позицию Паймон.
            refYaw = MathHelper.lerpAngleDegrees(REF_YAW_LERP, refYaw, player.getYaw());
            // Финальная фраза после знакомства держится на экране несколько секунд.
            if (postIntroTicks > 0) {
                postIntroTicks--;
                if (postIntroTicks == 0) {
                    clearActionBar(player);
                } else {
                    showActionBar(player, PHRASE_3);
                }
            }
        }

        Vec3d target;
        if (entity.isFollowing()) {
            target = followTarget(player, refYaw);
        } else {
            // Во время знакомства Паймон не двигается: стоит на зафиксированной точке
            // и только поворачивается лицом к игроку.
            target = introPos;
        }
        if (entity.squaredDistanceTo(target) >= TELEPORT_DIST * TELEPORT_DIST) {
            entity.refreshPositionAfterTeleport(target);
            entity.setYaw(faceYaw(entity, player));
            hasLastTrailPos = false;
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
            spawnGoldenTrail((ClientWorld) client.world, entity);
        }
    }

    /** Цель полёта: справа от героя на уровне головы, как Паймон в Genshin.
     *  Точка привязана к сглаженному взгляду, поэтому при повороте Паймон мягко
     *  дрейфует, а не перелетает за спину; при беге она не оказывается перед лицом. */
    private static Vec3d followTarget(AbstractClientPlayerEntity player, float yaw) {
        return playerPos(player)
                .add(sideDeg(yaw, FOLLOW_SIDE))
                .add(0.0, FOLLOW_UP, 0.0);
    }

    /** Направление вбок (перпендикулярно взгляду) для позиции сбоку от героя. */
    private static Vec3d sideDeg(float yaw, double dist) {
        return forwardDeg(yaw + 90.0f, dist);
    }

    /** Плотный золотой шлейф за Паймон: частицы сыплются вдоль её пути.
     *  Используются золотая пыль и искры END_ROD — те типы, которые гарантированно
     *  видны в шейдере; синеватый GLOW-ореол убран. */
    private static void spawnGoldenTrail(ClientWorld world, PaimonEntity entity) {
        if (trailTimer++ % 2 != 0) {
            return;
        }
        var random = world.random;
        Vec3d now = entityPos(entity).add(0.0, 0.3, 0.0);
        Vec3d from = hasLastTrailPos ? lastTrailPos : now;
        hasLastTrailPos = true;
        lastTrailPos = now;
        double dist = from.distanceTo(now);
        // Частицы по всей дуге от прошлой позиции к текущей — дорожка видна даже
        // при быстром полёте.
        int steps = 1 + Math.min(6, (int) (dist * 8.0));
        for (int s = 0; s < steps; s++) {
            double t = s / (double) steps;
            double px = MathHelper.lerp(t, from.x, now.x);
            double py = MathHelper.lerp(t, from.y, now.y);
            double pz = MathHelper.lerp(t, from.z, now.z);
            for (int j = 0; j < 2; j++) {
                world.addParticleClient(
                        new DustColorTransitionParticleEffect(0xFFE066, 0xFFF7CC,
                                0.9f + random.nextFloat() * 0.5f),
                        px + (random.nextDouble() - 0.5) * 0.35,
                        py + (random.nextDouble() - 0.5) * 0.35,
                        pz + (random.nextDouble() - 0.5) * 0.35,
                        (random.nextDouble() - 0.5) * 0.02, -0.02, (random.nextDouble() - 0.5) * 0.02);
            }
        }
        // Яркие золотистые искры-штрихи.
        if (random.nextInt(2) == 0) {
            world.addParticleClient(ParticleTypes.END_ROD,
                    now.x + (random.nextDouble() - 0.5) * 0.35,
                    now.y + (random.nextDouble() - 0.5) * 0.35,
                    now.z + (random.nextDouble() - 0.5) * 0.35,
                    (random.nextDouble() - 0.5) * 0.04, 0.01, (random.nextDouble() - 0.5) * 0.04);
        }
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

    /** Реплика Паймон внизу экрана, как action bar командного блока. */
    private static void showActionBar(AbstractClientPlayerEntity player, String text) {
        player.sendMessage(Text.literal("§fПаймон§7: §f" + text), true);
    }

    /** Убирает реплику с нижней части экрана. */
    private static void clearActionBar(AbstractClientPlayerEntity player) {
        player.sendMessage(Text.empty(), true);
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
}
