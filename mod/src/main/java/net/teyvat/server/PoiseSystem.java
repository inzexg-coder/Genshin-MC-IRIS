package net.teyvat.server;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * Система стойкости (poise) мобов: при получении урона шкала стойкости
 * уменьшается на PoiseDamage удара. Когда стойкость падает до 0 — моб
 * оглушён (длинное замирание + knockback). Стойкость восстанавливается
 * со временем.
 *
 * Формулы по Genshin:
 *  - PoiseDamage — фиксированное значение каждого удара (из SwordCombo).
 *  - MaxPoise зависит от типа моба (слайм=100, зомби=200, и т.д.).
 *  - При оглушении: Hitlag × 3, Knockback × 1.5.
 *  - Стойкость восстанавливается 50/сек (2.5/тик).
 */
public final class PoiseSystem {

    private static final float POISE_REGEN_PER_TICK = 2.5f;
    /** Множитель отбрасывания при оглушении. */
    public static final float STAGGER_KNOCKBACK_MULT = 1.5f;
    /** Множитель длительности хит-стопа при оглушении. */
    public static final int STAGGER_HITLAG_MULT = 3;

    private static final Map<Integer, MobPoiseData> POISE = new HashMap<>();

    private PoiseSystem() {}

    public static final class MobPoiseData {
        float current;
        float max;
        int stunTicksLeft;       // Тики оглушения (>0 = оглушён)
        int stunDuration;        // Полная длительность оглушения (для прогресса)

        MobPoiseData(float max) {
            this.current = max;
            this.max = max;
        }
    }

    /** Получить или создать данные стойкости для моба. */
    public static MobPoiseData getOrCreate(LivingEntity entity) {
        return POISE.computeIfAbsent(entity.getId(), id -> {
            float maxPoise = estimateMaxPoise(entity);
            return new MobPoiseData(maxPoise);
        });
    }

    /** Оценка максимальной стойкости по типу моба. */
    private static float estimateMaxPoise(LivingEntity entity) {
        // Слаймы — хрупкие, Зомби — средние, Каджит/Рыцари — крепкие
        float hp = entity.getMaxHealth();
        // Формула: maxPoise ≈ HP × 1.5 + базовая стойкость
        if (hp <= 60)  return 80f;   // Слаймы, маленькие мобы
        if (hp <= 150) return 150f;  // Зомби, скелеты
        if (hp <= 300) return 250f;  // Каджит, рыцари
        return 400f;                  // Боссы, элиты
    }

    /**
     * Применить poise damage при ударе. Возвращает true если моб оглушён.
     * @param hitPoiseDamage poise damage удара (из SwordCombo PoiseDamage)
     */
    public static boolean applyPoiseDamage(LivingEntity target, float hitPoiseDamage) {
        MobPoiseData data = getOrCreate(target);
        data.current -= hitPoiseDamage;

        if (data.current <= 0) {
            // Моб оглушён!
            data.current = 0;
            // Чем глубже урон под 0 — тем дольше оглушение
            float overflow = Math.abs(data.current);
            int baseStun = 20; // 1 сек базовое оглушение
            data.stunTicksLeft = baseStun + (int)(overflow * 0.5f);
            data.stunDuration = data.stunTicksLeft;
            return true;
        }
        return false;
    }

    /** Тик системы: восстановление стойкости и таймер оглушения. */
    public static void tick(ServerWorld world) {
        POISE.entrySet().removeIf(entry -> {
            Entity entity = world.getEntityById(entry.getKey());
            if (entity == null || !entity.isAlive()) return true;

            MobPoiseData data = entry.getValue();

            // Восстановление стойкости (не во время оглушения)
            if (data.stunTicksLeft <= 0 && data.current < data.max) {
                data.current = Math.min(data.max, data.current + POISE_REGEN_PER_TICK);
            }

            // Таймер оглушения
            if (data.stunTicksLeft > 0) {
                data.stunTicksLeft--;
            }

            return false;
        });
    }

    /** Моб оглушён прямо сейчас. */
    public static boolean isStunned(LivingEntity entity) {
        MobPoiseData data = POISE.get(entity.getId());
        return data != null && data.stunTicksLeft > 0;
    }

    /** Прогресс оглушения 0..1 (для анимации). */
    public static float stunProgress(LivingEntity entity) {
        MobPoiseData data = POISE.get(entity.getId());
        if (data == null || data.stunDuration <= 0) return 0f;
        return (float) data.stunTicksLeft / data.stunDuration;
    }

    /** Текущая стойкость 0..1 (для UI). */
    public static float poiseRatio(LivingEntity entity) {
        MobPoiseData data = POISE.get(entity.getId());
        if (data == null) return 1f;
        return data.current / data.max;
    }

    /** Максимальная стойкость. */
    public static float maxPoise(LivingEntity entity) {
        return estimateMaxPoise(entity);
    }

    public static void onEntityRemoved(int entityId) {
        POISE.remove(entityId);
    }
}
