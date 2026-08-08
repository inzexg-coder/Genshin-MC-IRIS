package net.teyvat.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.teyvat.entity.HydroSlimeEntity;
import net.teyvat.network.QuestCompletePayload;
import net.teyvat.quest.Quests;

import java.util.List;
import java.util.UUID;

/** Тренировка атаки: три Гидро слайма, которых призывает Паймон после рывка.
 *  Слаймы принадлежат игроку (урон засчитывается только владельцу), не стреляют
 *  во время урока, а когда все трое повержены — квест «Победи Гидро слаймов»
 *  выполняется на сервере. Владелец хранится в NBT слайма, поэтому тренировка
 *  переживает перезаход и перезапуск сервера. */
public final class SlimeTraining {
    private static final int SLIME_COUNT = 3;
    private static final double SPAWN_MIN_DIST = 2.5;
    private static final double SPAWN_MAX_DIST = 4.5;
    /** Радиус поиска живых слаймов тренировки (вокруг игрока/слайма). */
    private static final double SEARCH_RADIUS = 512.0;

    private SlimeTraining() {}

    /** Призвать слаймов вокруг игрока. Если живые тренировочные слаймы уже есть
     *  (перезаход во время боя) — новых не создаём, а включаем им бой. */
    public static void spawnAround(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        if (TeyvatQuests.isCompleted(player, Quests.TRY_ATTACK)) {
            return;
        }
        Vec3d center = new Vec3d(player.getX(), player.getY(), player.getZ());
        List<HydroSlimeEntity> existing = aliveSlimes(world, center, player.getUuid());
        if (!existing.isEmpty()) {
            for (HydroSlimeEntity slime : existing) {
                slime.setCombatReady(true);
                slime.setTarget(player);
            }
            return;
        }
        for (int i = 0; i < SLIME_COUNT; i++) {
            HydroSlimeEntity slime = new HydroSlimeEntity(HydroSlimeEntity.TYPE, world);
            slime.setOwnerUuid(player.getUuid());
            slime.setCombatReady(true);
            Vec3d pos = spawnPos(world, center);
            slime.setPosition(pos.x, pos.y, pos.z);
            slime.setTarget(player);
            world.spawnEntity(slime);
        }
    }

    /** Слайм тренировки повержен: если это был последний живой слайм владельца —
     *  квест выполнен, шлём игроку уведомление. */
    public static void onSlimeKilled(ServerWorld world, HydroSlimeEntity slime) {
        UUID owner = slime.getOwnerUuid();
        if (owner == null) {
            return;
        }
        Vec3d pos = new Vec3d(slime.getX(), slime.getY(), slime.getZ());
        if (!aliveSlimes(world, pos, owner).isEmpty()) {
            return;
        }
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(owner);
        if (player == null) {
            return;
        }
        TeyvatQuests.complete(player, Quests.TRY_ATTACK);
        ServerPlayNetworking.send(player,
                new QuestCompletePayload(Quests.TRY_ATTACK, Quests.TRY_ATTACK_TITLE));
    }

    /** Живые тренировочные слаймы владельца в загруженных чанках вокруг центра. */
    private static List<HydroSlimeEntity> aliveSlimes(ServerWorld world, Vec3d center, UUID owner) {
        Box box = new Box(center.x - SEARCH_RADIUS, center.y - SEARCH_RADIUS, center.z - SEARCH_RADIUS,
                center.x + SEARCH_RADIUS, center.y + SEARCH_RADIUS, center.z + SEARCH_RADIUS);
        return world.getEntitiesByType(HydroSlimeEntity.TYPE, box,
                e -> e.isAlive() && owner.equals(e.getOwnerUuid()));
    }

    /** Точка спавна: случайный угол вокруг игрока на поверхности пляжа. */
    private static Vec3d spawnPos(ServerWorld world, Vec3d center) {
        double angle = world.random.nextDouble() * Math.PI * 2.0;
        double dist = SPAWN_MIN_DIST + world.random.nextDouble() * (SPAWN_MAX_DIST - SPAWN_MIN_DIST);
        int x = (int) Math.floor(center.x + Math.cos(angle) * dist);
        int z = (int) Math.floor(center.z + Math.sin(angle) * dist);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
        return new Vec3d(x + 0.5, y + 0.05, z + 0.5);
    }
}
