package net.teyvat.progression;

import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.teyvat.config.TeyvatConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Опыт за убийство моба с анти-фармом: первые N убийств одного типа за сессию
 * дают полный опыт, дальше множитель падает до минимума — игра не превращается
 * в бесконечный фарм.
 */
public final class MobXp {
    /** Счётчики убийств: игрок → тип моба → сколько убито за сессию. */
    private static final Map<UUID, Map<String, Integer>> KILL_COUNTS = new ConcurrentHashMap<>();

    private MobXp() {}

    /** Сколько опыта даст это убийство (с учётом анти-фарма). */
    public static long xpForKill(ServerPlayerEntity player, LivingEntity mob) {
        TeyvatConfig.Progression p = TeyvatConfig.get().progression;
        String type = Registries.ENTITY_TYPE.getId(mob.getType()).toString();
        long base = p.mob_xp.getOrDefault(type, p.mob_xp_default);
        if (base <= 0) {
            return 0;
        }
        int count = KILL_COUNTS.computeIfAbsent(player.getUuid(), k -> new HashMap<>())
                .merge(type, 1, Integer::sum);
        double multiplier = 1.0;
        if (p.antifarm_enabled) {
            int over = count - p.antifarm_first_kills;
            if (over > 0) {
                multiplier = Math.max(p.antifarm_min_multiplier,
                        1.0 - over * p.antifarm_decay_per_kill);
            }
        }
        return Math.max(0L, Math.round(base * multiplier));
    }

    /** Игрок вышел — его счётчики больше не нужны. */
    public static void onDisconnect(ServerPlayerEntity player) {
        KILL_COUNTS.remove(player.getUuid());
    }

    /** Сессия сервера кончилась — сбрасываем все счётчики. */
    public static void resetSession() {
        KILL_COUNTS.clear();
    }
}
