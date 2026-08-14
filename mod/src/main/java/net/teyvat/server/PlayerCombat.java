package net.teyvat.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
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
import net.teyvat.network.AttackResultPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть боевки: по пакету удара от клиента ищет цели по хитбоксу
 * ТЕКУЩЕГО удара комбо (у каждого удара свои дальность/ширина/веер/высота,
 * 3-й удар — полный круг вокруг героя; заряженный спин, индекс CHARGE_INDEX, —
 * тоже полный круг, но шире, с отбросом по радиусу) и наносит урон.
 * Попадание подтверждается клиенту пакетом AttackResultPayload: hitlag, звук
 * и искры — только на хите. Реакция врага на удар — хит-стоп: цель замирает
 * на время hitlag, затем получает отброс. Промах ничего не подтверждает
 * (свинг остаётся визуальным).
 */
public final class PlayerCombat {
    /** Минимальный интервал между ударами (защита от спама пакетами). */
    private static final int MIN_INTERVAL_TICKS = 6;
    /** Тик последней атаки каждого игрока (player.age). */
    private static final Map<UUID, Integer> LAST_ATTACK_TICK = new ConcurrentHashMap<>();
    /** Хит-стоп целей: entity id -> оставшиеся тики + отброс после стопа. */
    private static final Map<Integer, Stagger> STAGGER = new ConcurrentHashMap<>();

    /** Состояние хит-стопа цели: пока ticksLeft > 0, цель замирает на месте
     *  (реакция на удар), после — получает сохранённый отброс. */
    private record Stagger(int ticksLeft, Vec3d knockback) {}

    private PlayerCombat() {}

    /** Обработать удар комбо от игрока (chargeLevel — уровень заряда спина 0..1:
     *  урон зависит от него, ранний отпуск = слабее). */
    public static void onAttack(ServerPlayerEntity player, int hitIndex, float chargeLevel) {
        if (hitIndex < 0 || hitIndex > SwordCombo.CHARGE_INDEX) {
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

        float reach = SwordCombo.HIT_REACH[hitIndex];
        float halfWidth = SwordCombo.HIT_HALF_WIDTH[hitIndex];
        float frontDot = SwordCombo.HIT_FRONT_DOT[hitIndex];
        float maxLateral = SwordCombo.HIT_MAX_LATERAL[hitIndex];
        float top = SwordCombo.HIT_BOX_TOP[hitIndex];
        float bottom = SwordCombo.HIT_BOX_BOTTOM[hitIndex];
        boolean fullCircle = frontDot <= -0.5f;

        Vec3d eye = player.getEyePos();
        Vec3d look = player.getRotationVec(1.0f);
        Vec3d center = fullCircle ? eye : eye.add(look.multiply(reach * 0.5));
        double halfW = fullCircle ? reach : halfWidth;
        Box box = new Box(
                center.x - halfW, eye.y + bottom, center.z - halfW,
                center.x + halfW, eye.y + top, center.z + halfW);
        List<LivingEntity> targets = world.getEntitiesByClass(LivingEntity.class, box,
                e -> e.isAlive() && e != player
                        && !(e instanceof PlayerEntity)
                        && !(e instanceof ArmorStandEntity));

        float base = (float) player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        float mult = SwordCombo.MULTIPLIERS[hitIndex];
        if (hitIndex == SwordCombo.CHARGE_INDEX) {
            // Заряженный спин: урон масштабируется уровнем заряда — от
            // CHARGE_MIN_MULT (ранний отпуск) до полного (3 сек / автозапуск).
            float lv = Math.max(0f, Math.min(1f, chargeLevel));
            mult *= SwordCombo.CHARGE_MIN_MULT + (1f - SwordCombo.CHARGE_MIN_MULT) * lv;
        }
        float amount = Math.max(0.5f, base * mult);
        List<Integer> hitIds = new ArrayList<>();
        for (LivingEntity target : targets) {
            Vec3d to = target.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > reach || dist < 1e-4) {
                continue;
            }
            if (!fullCircle) {
                Vec3d dir = to.normalize();
                if (dir.dotProduct(look) < frontDot) {
                    continue;
                }
                Vec3d proj = look.multiply(to.dotProduct(look));
                if (to.subtract(proj).length() > maxLateral) {
                    continue;
                }
            }
            if (target.damage(world, player.getDamageSources().playerAttack(player), amount)) {
                hitIds.add(target.getId());
                // Реакция на удар: сначала хит-стоп (замирает как и анимация героя),
                // затем отброс по направлению удара.
                Vec3d dir = to.normalize();
                Vec3d knockback = new Vec3d(dir.x, 0, dir.z).normalize()
                        .multiply(SwordCombo.KNOCKBACK[hitIndex]);
                STAGGER.put(target.getId(), new Stagger(SwordCombo.HITLAG_TICKS[hitIndex] + 1, knockback));
            }
        }
        // Подтверждение попадания клиенту: hitlag, звук и искры только на хите.
        if (!hitIds.isEmpty()) {
            ServerPlayNetworking.send(player, new AttackResultPayload(hitIds.stream().mapToInt(i -> i).toArray()));
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                    SoundCategory.PLAYERS, 1.0f, 0.9f + hitIndex * 0.04f);
        }
    }

    /** Серверный тик: хит-стоп целей — замирание, затем отброс. */
    public static void tick(ServerWorld world) {
        if (STAGGER.isEmpty()) {
            return;
        }
        // ConcurrentHashMap не поддерживает Entry.setValue в removeIf,
        // поэтому обновления копим в отдельной map и применяем после.
        Map<Integer, Stagger> updates = new HashMap<>();
        STAGGER.entrySet().removeIf(entry -> {
            Entity entity = world.getEntityById(entry.getKey());
            if (entity == null || !entity.isAlive()) {
                return true;
            }
            Stagger s = entry.getValue();
            if (s.ticksLeft() > 1) {
                // Замерла на месте: гасим скорость (включая прыжок/хоп слайма).
                entity.setVelocity(0, 0, 0);
                entity.velocityModified = true;
                updates.put(entry.getKey(), new Stagger(s.ticksLeft() - 1, s.knockback()));
                return false;
            }
            // Стоп закончился: отброс цели.
            entity.setVelocity(s.knockback().x, s.knockback().y, s.knockback().z);
            entity.velocityModified = true;
            return true;
        });
        STAGGER.putAll(updates);
    }

    /** Игрок вышел: забываем его тайминг атак. */
    public static void onDisconnect(ServerPlayerEntity player) {
        LAST_ATTACK_TICK.remove(player.getUuid());
    }
}
