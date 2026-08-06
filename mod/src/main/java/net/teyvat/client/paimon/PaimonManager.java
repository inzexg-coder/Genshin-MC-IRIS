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
    private static final double FOLLOW_DIST = 1.15;
    /** Высота полёта над игроком. */
    private static final double FOLLOW_UP = 1.2;
    /** Сдвиг вбок при полёте за игроком, как в оригинальном моде APaimon. */
    private static final float FOLLOW_SIDE_DEG = 30.0f;
    /** Скорость полёта к цели, блоков/тик. */
    private static final double MOVE_SPEED = 0.22;
    /** Если Паймон отстала дальше этого расстояния — телепорт к игроку. */
    private static final double TELEPORT_DIST = 16.0;
    /** Дистанция до игрока во время знакомства. */
    private static final double INTRO_DIST = 2.4;

    private static PaimonEntity paimon;

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
        Vec3d start = playerPos(client.player).add(forward(client.player, INTRO_DIST)).add(0.0, 1.2, 0.0);
        entity.setPosition(start.x, start.y, start.z);
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

        if (!entity.isFollowing()) {
            int ticks = entity.getIntroTicks() + 1;
            entity.setIntroTicks(ticks);
            if (ticks == 1) {
                say(player, "Ой! Ты наконец проснулся, путешественник? Паймон уже думала, ты будешь спать вечно!");
            } else if (ticks == 55) {
                say(player, "Это пляж Тейвата. За холмами стоит Мондштадт — город свободы. Оттуда всё и начинается.");
            } else if (ticks >= entity.getIntroTicksLimit()) {
                entity.setFollowing(true);
                say(player, "Пойдём! Паймон покажет дорогу и будет рядом, куда бы ты ни пошёл.");
            }
        }

        Vec3d target = entity.isFollowing() ? followTarget(player) : introTarget(player);
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

    /** Цель полёта во время знакомства: чуть перед игроком, на уровне его глаз. */
    private static Vec3d introTarget(AbstractClientPlayerEntity player) {
        return playerPos(player).add(forward(player, INTRO_DIST)).add(0.0, 1.2, 0.0);
    }

    /** Цель полёта за игроком: сзади и чуть сбоку, выше головы. */
    private static Vec3d followTarget(AbstractClientPlayerEntity player) {
        double rad = Math.toRadians(player.getYaw());
        Vec3d behind = new Vec3d(-Math.sin(rad), 0.0, Math.cos(rad));
        double sideRad = Math.toRadians(player.getYaw() + FOLLOW_SIDE_DEG);
        Vec3d side = new Vec3d(-Math.sin(sideRad), 0.0, Math.cos(sideRad));
        return playerPos(player)
                .add(behind.multiply(FOLLOW_DIST))
                .add(side.multiply(FOLLOW_DIST * 0.35))
                .add(0.0, FOLLOW_UP, 0.0);
    }

    private static Vec3d forward(AbstractClientPlayerEntity player, double dist) {
        double rad = Math.toRadians(player.getYaw());
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
