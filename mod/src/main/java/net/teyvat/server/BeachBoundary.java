package net.teyvat.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.teyvat.config.TeyvatConfig;
import net.teyvat.quest.Quests;

/**
 * Граница пляжа во время обучения: пока игрок не прошёл все задания
 * (знакомство, скролл, зум, бег, рывок — и будущее «убей слаймов»),
 * его мягко отталкивает к центру пляжа, как невидимая стена в Genshin.
 * Центр границы — точка мирового спавна, которую TeyvatSpawn ставит на пляж.
 */
public final class BeachBoundary {
    private BeachBoundary() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if ((server.getTicks() % 5) != 0) {
                return;
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                try {
                    check(player);
                } catch (Exception e) {
                    // ошибка не должна ломать тик сервера
                }
            }
        });
    }

    /** Все задания обучения пройдены — граница открыта. */
    private static boolean tutorialDone(ServerPlayerEntity player) {
        return TeyvatQuests.isCompleted(player, Quests.MEET_PAIMON)
                && TeyvatQuests.isCompleted(player, Quests.TRY_SCROLL)
                && TeyvatQuests.isCompleted(player, Quests.TRY_ZOOM)
                && TeyvatQuests.isCompleted(player, Quests.TRY_SPRINT)
                && TeyvatQuests.isCompleted(player, Quests.TRY_DASH)
                && TeyvatQuests.isCompleted(player, Quests.TRY_ATTACK)
                && TeyvatQuests.isCompleted(player, Quests.TRY_PICKUP);
    }

    private static void check(ServerPlayerEntity player) {
        TeyvatConfig.Tutorial cfg = TeyvatConfig.get().tutorial;
        if (cfg == null || !cfg.lock_beach) {
            return;
        }
        if (tutorialDone(player)) {
            return;
        }
        BlockPos center;
        if (player.getEntityWorld() instanceof ServerWorld serverWorld) {
            center = serverWorld.getSpawnPoint().globalPos().pos();
        } else {
            return;
        }
        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        double dx = pos.x - (center.getX() + 0.5);
        double dz = pos.z - (center.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= cfg.beach_radius) {
            return;
        }
        // Мягкий возврат к центру пляжа + короткое сообщение (не чаще cooldown).
        double push = 0.45;
        player.setVelocity(new Vec3d(-dx / dist * push, 0.12, -dz / dist * push));
        player.velocityModified = true;
        if (player.age % cfg.message_cooldown_ticks == 0) {
            player.sendMessage(Text.literal("§e" + cfg.message), false);
        }
    }
}
