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
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.teyvat.combat.SwordCombo;
import net.teyvat.server.WikiDiscoveries;
import net.teyvat.wiki.TeyvatWiki;
import net.teyvat.entity.HydroSlimeEntity;
import net.teyvat.network.AttackResultPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная часть боевки: по пакету удара от клиента ищет цели по хитбоксу
 * ТЕКУЩЕГО удара комбо и наносит урон.
 *
 * Механики взаимодействия (как в Genshin):
 *  - Hitlag: моб замирает на HITLAG_TICKS тиков. Скорость = 0.01 (не 0).
 *  - Poise-extending: если у моба есть стойкость, хит-стоп dài hơn (+0.06 сек).
 *  - Poise damage: каждый удар наносит PoiseDamage. При 0 — оглушение (3× хит-стоп, 1.5× кнокбек).
 *  - Stagger levels: Light/Heavy/Air — разные типы отброса.
 *  - Weight: вес моба влияет на дальность кнокбека.
 */
public final class PlayerCombat {
    private static final int MIN_INTERVAL_TICKS = 6;
    private static final Map<UUID, Integer> LAST_ATTACK_TICK = new ConcurrentHashMap<>();
    private static final Map<Integer, Stagger> STAGGER = new ConcurrentHashMap<>();

    /** Сущность хит-стопа: тики + от镚 + оглушён ли моб. */
    private record Stagger(int ticksLeft, Vec3d knockback, boolean wasStunned) {}

    /** Скорость сущности во время хит-стопа (0.01 = почти замирание, но не 0). */
    private static final float HITLAG_SPEED_MULT = 0.01f;
    /** Дополнительные тики хит-стопа при наличии стойкости (+0.06 сек ≈ +1 тик). */
    private static final int POISE_EXTEND_TICKS = 1;

    private PlayerCombat() {}

    public static void onAttack(ServerPlayerEntity player, int hitIndex, float chargeLevel) {
        if (hitIndex < 0 || hitIndex > SwordCombo.CHARGE_INDEX) return;
        if (!(player.getEntityWorld() instanceof ServerWorld world)) return;

        Integer last = LAST_ATTACK_TICK.get(player.getUuid());
        if (last != null && player.age - last < MIN_INTERVAL_TICKS) return;
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
                WikiDiscoveries.discover(player, TeyvatWiki.ID_COMBAT_CHARGED);
            float lv = Math.max(0f, Math.min(1f, chargeLevel));
            mult *= SwordCombo.CHARGE_MIN_MULT + (1f - SwordCombo.CHARGE_MIN_MULT) * lv;
        }
        float amount = Math.max(0.5f, base * mult);

        List<Integer> hitIds = new ArrayList<>();

        for (LivingEntity target : targets) {
            Vec3d to = target.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > reach || dist < 1e-4) continue;

            if (!fullCircle) {
                Vec3d dir = to.normalize();
                if (dir.dotProduct(look) < frontDot) continue;
                Vec3d proj = look.multiply(to.dotProduct(look));
                if (to.subtract(proj).length() > maxLateral) continue;
            }

            if (target.damage(world, player.getDamageSources().playerAttack(player), amount)) {
                hitIds.add(target.getId());

                // === POISE DAMAGE ===
                float poiseDamage = SwordCombo.POISE_DAMAGE[hitIndex];
                boolean stunned = PoiseSystem.applyPoiseDamage(target, poiseDamage);

                // === KNOCKBACK ===
                Vec3d dir = to.normalize();
                float kbStrength = SwordCombo.KNOCKBACK[hitIndex];

                // Weight: тяжёлые мобы получают меньше кнокбека
                float weight = target.getMaxHealth() / 200f; // 200 HP = вес 1.0
                weight *= knockbackWeightMultiplier(target);
                weight = Math.max(0.3f, Math.min(3.0f, weight));
                kbStrength /= weight;

                // Stun multiplier: оглушённые мобы отбрасываются дальше
                if (stunned) {
                    kbStrength *= PoiseSystem.STAGGER_KNOCKBACK_MULT;
                }

                Vec3d knockback = new Vec3d(dir.x, 0, dir.z).normalize().multiply(kbStrength);

                // Air stagger: 5-й удар подбрасывает вверх
                if (SwordCombo.STAGGER_LEVEL[hitIndex] == 2) {
                    knockback = knockback.add(0, 0.3, 0);
                }

                // === HITLAG DURATION ===
                int hitlag = SwordCombo.HITLAG_TICKS[hitIndex];

                // Poise-extending: если у моба ещё есть стойкость — хит-стоп dài hơn
                PoiseSystem.MobPoiseData poiseData = PoiseSystem.getOrCreate(target);
                if (poiseData.current > 0 && !stunned) {
                    hitlag += POISE_EXTEND_TICKS;
                }

                // Stun multiplier: оглушённые мобы замирают дольше
                if (stunned) {
                    hitlag *= PoiseSystem.STAGGER_HITLAG_MULT;
                }

                STAGGER.put(target.getId(), new Stagger(hitlag + 1, knockback, stunned));
            }
        }

        if (!hitIds.isEmpty()) {
            ServerPlayNetworking.send(player, new AttackResultPayload(hitIds.stream().mapToInt(i -> i).toArray()));
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                    SoundCategory.PLAYERS, 1.0f, 0.9f + hitIndex * 0.04f);
        }
    }

    /** Серверный тик: хит-стоп + poise. */
    public static void tick(ServerWorld world) {
        // Poise tick: восстановление стойкости + таймер оглушения
        PoiseSystem.tick(world);

        if (STAGGER.isEmpty()) return;

        Map<Integer, Stagger> updates = new HashMap<>();
        STAGGER.entrySet().removeIf(entry -> {
            Entity entity = world.getEntityById(entry.getKey());
            if (entity == null || !entity.isAlive()) return true;

            Stagger s = entry.getValue();
            if (s.ticksLeft() > 1) {
                // Хит-стоп: скорость = 0.01 (не 0), моб чуть дёргается
                float speed = s.wasStunned() ? 0f : HITLAG_SPEED_MULT;
                entity.setVelocity(
                        entity.getVelocity().x * speed,
                        s.wasStunned() ? 0 : entity.getVelocity().y * speed,
                        entity.getVelocity().z * speed);
                entity.velocityModified = true;
                updates.put(entry.getKey(), new Stagger(s.ticksLeft() - 1, s.knockback(), s.wasStunned()));
                return false;
            }
            // Стоп закончился: отброс
            entity.setVelocity(s.knockback().x, s.knockback().y, s.knockback().z);
            entity.velocityModified = true;
            return true;
        });
        STAGGER.putAll(updates);
    }

    /** Множитель веса по типу моба: маленькие и хрупкие мобы тяжелее
     *  для кнокбека (получают меньше отбрасывания), как в Genshin. */
    private static float knockbackWeightMultiplier(LivingEntity entity) {
        if (entity instanceof HydroSlimeEntity) return 2.5f;  // Слаймы: почти не отлетают
        if (entity instanceof SlimeEntity)       return 2.5f;  // Ванильные слаймы
        if (entity instanceof AnimalEntity)      return 1.8f;  // Животные: чуть тяжелее
        return 1.0f;                                            // Остальные: базовый вес
    }

    /** Игрок вышел: забываем его тайминги. */
    public static void onDisconnect(ServerPlayerEntity player) {
        LAST_ATTACK_TICK.remove(player.getUuid());
    }
}
