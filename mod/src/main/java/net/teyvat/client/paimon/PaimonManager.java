package net.teyvat.client.paimon;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
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
    /** Насколько близко за спиной игрока держится Паймон. */
    private static final double FOLLOW_DIST = 0.9;
    /** Высота полёта за спиной (чуть выше головы героя). */
    private static final double FOLLOW_UP = 1.5;
    /** Скорость полёта к цели, блоков/тик. */
    private static final double MOVE_SPEED = 0.22;
    /** Если Паймон отстала дальше этого расстояния — телепорт к игроку. */
    private static final double TELEPORT_DIST = 16.0;
    /** Дистанция до игрока во время знакомства. */
    private static final double INTRO_DIST = 2.4;
    /** Высота над игроком во время знакомства (чуть выше линии взгляда). */
    private static final double INTRO_UP = 1.4;
    /** Сглаживание поворота цели следования: быстрые движения мыши не дёргают Паймон. */
    private static final float REF_YAW_LERP = 0.08f;
    /** Сколько тиков Паймон облетает героя по дуге после знакомства. */
    private static final int TRANSITION_TICKS = 30;
    /** Насколько сильно дуга уводит Паймон в сторону, чтобы она не пролетала сквозь героя. */
    private static final double TRANSITION_ARC = 1.8;

    private static PaimonEntity paimon;
    /** Сглаженный угол, от которого зависит цель Паймон (не дёргается от взгляда). */
    private static float refYaw;
    private static boolean refYawReady;
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
            if (ticks == 1) {
                say(player, "Ой! Ты наконец проснулся, путешественник? Паймон уже думала, ты будешь спать вечно!");
            } else if (ticks == 55) {
                say(player, "Это пляж Тейвата. За холмами стоит Мондштадт — город свободы. Оттуда всё и начинается.");
            } else if (ticks >= entity.getIntroTicksLimit()) {
                entity.setFollowing(true);
                entity.setTransitionTicks(0);
                // Сразу за спину по текущему взгляду игрока, без долгого разворота цели.
                refYaw = player.getYaw();
                say(player, "Пойдём! Паймон покажет дорогу и будет рядом, куда бы ты ни пошёл.");
            }
        } else {
            // Сглаживаем поворот: при быстром движении мыши цель почти не смещается.
            refYaw = MathHelper.lerpAngleDegrees(REF_YAW_LERP, refYaw, player.getYaw());
        }

        Vec3d target;
        if (entity.isFollowing()) {
            target = followTarget(player, refYaw);
            // Первые мгновения после знакомства Паймон облетает героя по дуге,
            // чтобы не пролететь сквозь него по пути из «перед лицом» в «за спину».
            int t = entity.getTransitionTicks() + 1;
            entity.setTransitionTicks(t);
            if (t < TRANSITION_TICKS) {
                double arc = Math.sin(Math.PI * t / TRANSITION_TICKS) * TRANSITION_ARC;
                target = target.add(sideDeg(refYaw, arc));
            }
        } else {
            // Во время знакомства Паймон не двигается: стоит на зафиксированной точке
            // и только поворачивается лицом к игроку.
            target = introPos;
        }
        if (entity.squaredDistanceTo(target) >= TELEPORT_DIST * TELEPORT_DIST) {
            entity.refreshPositionAfterTeleport(target);
            entity.setYaw(faceYaw(entity, player));
            return;
        }

        Vec3d delta = target.subtract(entityPos(entity));
        double dist = delta.length();
        if (dist >= 0.05) {
            Vec3d move = delta.multiply(1.0 / dist).multiply(MOVE_SPEED);
            entity.setPosition(entity.getX() + move.x, entity.getY() + move.y, entity.getZ() + move.z);
        }
        entity.setYaw(faceYaw(entity, player));
        entity.setPitch(0.0f);
    }

    /** Цель полёта за игроком: за спиной и чуть выше головы. */
    private static Vec3d followTarget(AbstractClientPlayerEntity player, float yaw) {
        return playerPos(player)
                .add(forwardDeg(yaw, -FOLLOW_DIST))
                .add(0.0, FOLLOW_UP, 0.0);
    }

    /** Направление «вперёд» при данном угле. Отрицательная дистанция — за спину. */
    private static Vec3d forwardDeg(float yaw, double dist) {
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad)).multiply(dist);
    }

    /** Направление вбок (перпендикулярно взгляду) для дуги облёта. */
    private static Vec3d sideDeg(float yaw, double dist) {
        return forwardDeg(yaw + 90.0f, dist);
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
