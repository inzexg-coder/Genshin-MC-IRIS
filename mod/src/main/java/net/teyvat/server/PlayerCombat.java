package net.teyvat.server;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.teyvat.combat.SwordCombo;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть боевки: по пакету удара от клиента ищет цели в конусе
 * перед игроком (как размах мечом в Genshin) и наносит урон с множителем
 * текущего удара комбо. Урон идёт обычным путём damage(), поэтому работают
 * все правила мода: числа урона, полоски HP, «не бить мирных», тренировка
 * слаймов (бьются только владельцем), опыт и смерть.
 */
public final class PlayerCombat {
    /** Дальность меча: ~3 блока, как в Genshin. */
    private static final float REACH = 3.2f;
    /** Половина ширины коробки поиска целей. */
    private static final float HALF_WIDTH = 1.6f;
    /** Минимальный косинус угла между взглядом и целью (широкий веер удара). */
    private static final double FRONT_DOT = 0.45;
    /** Максимальное боковое отклонение цели от луча взгляда. */
    private static final double MAX_LATERAL = 1.4;
    /** Отбрасывание при попадании. */
    private static final double KNOCKBACK = 0.35;
    /** Минимальный интервал между ударами (защита от спама пакетами). */
    private static final int MIN_INTERVAL_TICKS = 6;
    /** Тик последней атаки каждого игрока (player.age). */
    private static final Map<UUID, Integer> LAST_ATTACK_TICK = new ConcurrentHashMap<>();

    private PlayerCombat() {}

    /** Обработать удар комбо от игрока. */
    public static void onAttack(ServerPlayerEntity player, int hitIndex) {
        if (hitIndex < 0 || hitIndex >= SwordCombo.HIT_COUNT) {
            return;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        Integer last = LAST_ATTACK_TICK.get(player.getUuid());
        if (last != null && player.age - last < MIN_INTERVAL_TICKS) {
            return;
        }
        LAST_ATTACK_TICK.put(player.getUuid(), player.age);

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d center = eye.add(look.multiply(REACH * 0.5));
        Box box = new Box(
                Math.min(eye.x, center.x) - HALF_WIDTH,
                eye.y - 1.2,
                Math.min(eye.z, center.z) - HALF_WIDTH,
                Math.max(eye.x, center.x) + HALF_WIDTH,
                eye.y + 1.2,
                Math.max(eye.z, center.z) + HALF_WIDTH);
        List<LivingEntity> targets = world.getEntitiesByClass(LivingEntity.class, box,
                e -> e.isAlive() && e != player
                        && !(e instanceof PlayerEntity)
                        && !(e instanceof ArmorStandEntity));

        float base = (float) player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        float amount = Math.max(0.5f, base * SwordCombo.MULTIPLIERS[hitIndex]);
        boolean hitAny = false;
        for (LivingEntity target : targets) {
            Vec3d to = target.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > REACH || dist < 1e-4) {
                continue;
            }
            Vec3d dir = to.normalize();
            if (dir.dotProduct(look) < FRONT_DOT) {
                continue;
            }
            Vec3d proj = look.multiply(to.dotProduct(look));
            if (to.subtract(proj).length() > MAX_LATERAL) {
                continue;
            }
            if (target.damage(world, player.getDamageSources().playerAttack(player), amount)) {
                hitAny = true;
                target.takeKnockback(KNOCKBACK, dir.x, dir.z);
            }
        }
        // Звук удара: свист меча при попадании, пустой замах — тихий.
        if (hitAny) {
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                    SoundCategory.PLAYERS, 1.0f, 0.95f + hitIndex * 0.03f);
        } else {
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
    }

    /** Игрок вышел: забываем его тайминг атак. */
    public static void onDisconnect(ServerPlayerEntity player) {
        LAST_ATTACK_TICK.remove(player.getUuid());
    }
}
