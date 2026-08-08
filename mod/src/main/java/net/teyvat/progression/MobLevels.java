package net.teyvat.progression;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.teyvat.TeyvatMod;
import net.teyvat.config.TeyvatConfig;

/**
 * Уровни мобов: растут с расстоянием от спавна мира (точную формулу каждого
 * моба пропишем позже). Уровень хранится в attachment-е сущности и сам
 * сохраняется в NBT при выгрузке чанка (createPersistent).
 */
public final class MobLevels {
    private MobLevels() {}

    /** Attachment уровня моба: число >= 1, отсутствует, пока уровень не назначен. */
    public static final AttachmentType<Integer> MOB_LEVEL = AttachmentRegistry.createPersistent(
            Identifier.of(TeyvatMod.MOD_ID, "mob_level"), Codec.INT);

    /** Вызывается при загрузке сущности в серверный мир: назначает уровень, если его нет.
     *  Никогда не должен бросать исключений: этот хук выполняется внутри загрузки
     *  сущности в мир (startTracking), и любая ошибка здесь ломает инициализацию
     *  сущности — моб не попадёт в список тика и замрёт. */
    public static void onEntityLoad(Entity entity, ServerWorld world) {
        try {
            if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity) {
                return;
            }
            TeyvatConfig.MobLevels cfg = TeyvatConfig.get().mob_levels;
            if (cfg == null || !cfg.enabled) {
                return;
            }
            if (living.getAttached(MOB_LEVEL) != null) {
                return;
            }
            living.setAttached(MOB_LEVEL, computeLevel(world, entity.getBlockPos()));
        } catch (Exception e) {
            TeyvatMod.LOGGER.error("Не удалось назначить уровень мобу {}: {}", entity.getType(), e.toString());
        }
    }

    /** Формула уровня: базовый + прирост за блок расстояния от спавна мира. */
    public static int computeLevel(ServerWorld world, BlockPos pos) {
        TeyvatConfig.MobLevels cfg = TeyvatConfig.get().mob_levels;
        double dist = Math.sqrt(pos.getSquaredDistance(world.getSpawnPoint().getPos()));
        int level = cfg.base + (int) Math.floor(dist * cfg.per_block);
        return Math.max(1, Math.min(cfg.cap, level));
    }

    /** Уровень сущности для клиентского отображения (полоски HP и подписи «Ур. X»).
     *  Если уровень ещё не назначен (например, моб появился без события загрузки) —
     *  назначаем лениво при первом обращении. Вызывается из AFTER_DAMAGE,
     *  поэтому не должен бросать исключений: иначе прервётся обработка урона. */
    public static int getLevel(LivingEntity entity) {
        try {
            Integer level = entity.getAttached(MOB_LEVEL);
            if (level != null) {
                return level;
            }
            if (entity.getEntityWorld() instanceof ServerWorld serverWorld) {
                level = computeLevel(serverWorld, entity.getBlockPos());
                entity.setAttached(MOB_LEVEL, level);
                return level;
            }
            return TeyvatConfig.get().mob_levels.base;
        } catch (Exception e) {
            return TeyvatConfig.get().mob_levels.base;
        }
    }
}
