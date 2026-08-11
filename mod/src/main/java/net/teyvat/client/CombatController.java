package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;

/**
 * Боевка путешественника: комбо из 5 ударов мечом по ЛКМ, как у Итэра/Люмин
 * в Genshin. Ванильная атака майна подавлена миксином MinecraftClient.doAttack:
 * этот класс принимает клик, ведёт тайминги ударов, шлёт серверу пакет урона
 * и рисует позы анимации (applyPose) поверх ванильной модели игрока.
 * Удержание ЛКМ автоматически продолжает серию, тап — один удар.
 */
public final class CombatController {
    /** Текущий удар комбо: 0..HIT_COUNT-1, -1 = атака не идёт. */
    private static int comboStep = -1;
    /** Тики с начала текущего удара. */
    private static int hitTicks;
    /** Тики с конца последнего удара (окно продолжения серии, как в Genshin). */
    private static int idleTicks;
    /** Последний сыгранный удар (для продолжения серии после короткой паузы). */
    private static int lastStep = -1;
    /** Клик (или удержание) уже зацепил следующий удар: серия не обрывается. */
    private static boolean bufferedNext;
    /** Урон по текущему удару отправлен серверу (один раз за удар). */
    private static boolean sentHit;

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        if (comboStep >= 0) {
            hitTicks++;
            if (hitTicks == SwordCombo.DAMAGE_TICKS[comboStep] && !sentHit) {
                sentHit = true;
                sendHit(comboStep);
                spawnSlashParticle(client, client.player);
            }
            if (hitTicks >= SwordCombo.DURATION_TICKS[comboStep]) {
                if (bufferedNext) {
                    // Серия продолжается: следующий удар (после пятого — снова первый).
                    comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
                    hitTicks = 0;
                    bufferedNext = false;
                    sentHit = false;
                } else {
                    // Удар закончился, серия держится ещё RESET_TICKS тиков:
                    // клик в этом окне продолжает комбо со следующего удара.
                    lastStep = comboStep;
                    comboStep = -1;
                    idleTicks = 0;
                }
            } else if (client.options.attackKey.isPressed()
                    && hitTicks >= SwordCombo.DAMAGE_TICKS[comboStep] + SwordCombo.CHAIN_INPUT_TICKS) {
                // Удержание ЛКМ: следующий удар цепляется автоматически (как в Genshin).
                bufferedNext = true;
            }
        } else if (idleTicks <= SwordCombo.RESET_TICKS) {
            idleTicks++;
        }
    }

    /** ЛКМ нажат (вместо ванильной атаки). Возвращает true, если клик съеден комбо. */
    public static boolean onAttackClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return false;
        }
        if (comboStep < 0) {
            // Короткая пауза после удара не обрывает серию: продолжаем со следующего.
            if (idleTicks > 0 && idleTicks < SwordCombo.RESET_TICKS
                    && lastStep >= 0 && lastStep < SwordCombo.HIT_COUNT - 1) {
                comboStep = lastStep + 1;
            } else {
                comboStep = 0;
            }
            hitTicks = 0;
            bufferedNext = false;
            sentHit = false;
        } else {
            bufferedNext = true;
        }
        return true;
    }

    /** Пакет урона серверу: сервер сам находит цели в конусе перед игроком. */
    private static void sendHit(int hitIndex) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new PlayerAttackPayload(hitIndex));
        }
    }

    /** Полумесяц разреза перед игроком на тике урона (как свип-атака майна). */
    private static void spawnSlashParticle(MinecraftClient client, PlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        double d = -Math.sin(Math.toRadians(player.getYaw()));
        double e = Math.cos(Math.toRadians(player.getYaw()));
        // Полумесяц чуть выше талии, перед игроком: дельты задают его ориентацию.
        world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                player.getX() + d * 0.6,
                player.getY() + 1.05,
                player.getZ() + e * 0.6,
                d, 0.0, e);
    }

    /** Идёт ли сейчас удар (для миксина модели). */
    public static boolean isSwinging() {
        return comboStep >= 0;
    }

    /** Номер текущего удара (0..4). */
    public static int getHitIndex() {
        return comboStep;
    }

    /** Прогресс текущего удара 0..1 (для анимации). */
    public static float getHitProgress() {
        if (comboStep < 0) {
            return 0f;
        }
        return SwordCombo.progress(hitTicks, comboStep);
    }

    /** Игрок ли это (для миксина: анимируем только локального путешественника). */
    public static boolean isLocalPlayer(int entityId) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.getId() == entityId;
    }

    /** Наложить позу удара на модель игрока (вызывается в конце setAngles). */
    public static void applyPose(PlayerEntityModel model) {
        if (comboStep < 0) {
            return;
        }
        float p = ease(getHitProgress());
        switch (comboStep) {
            case 0 -> poseSlashRightToLeft(model, p);
            case 1 -> poseSlashLeftToRight(model, p);
            case 2 -> poseRisingSlash(model, p);
            case 3 -> poseThrust(model, p);
            case 4 -> poseOverheadSpin(model, p);
            default -> { }
        }
    }

    /** Плавность 0..1: без рывков на стыке кадров. */
    private static float ease(float t) {
        return t * t * (3f - 2f * t);
    }

    /** Удар 1: горизонтальный разрез справа налево. */
    private static void poseSlashRightToLeft(PlayerEntityModel m, float p) {
        m.rightArm.yaw = lerp(p, -1.15f, 1.0f);
        m.rightArm.pitch = lerp(p, -0.95f, -0.35f);
        m.rightArm.roll = lerp(p, 0.25f, -0.3f);
        m.leftArm.yaw = 0.45f;
        m.leftArm.pitch = 0.35f;
        m.leftArm.roll = -0.2f;
        m.body.yaw = lerp(p, 0.35f, -0.4f);
        m.body.pitch = 0.05f;
        m.rightLeg.pitch = -0.15f;
        m.leftLeg.pitch = 0.2f;
    }

    /** Удар 2: обратный горизонтальный разрез слева направо. */
    private static void poseSlashLeftToRight(PlayerEntityModel m, float p) {
        m.rightArm.yaw = lerp(p, 1.0f, -1.15f);
        m.rightArm.pitch = lerp(p, -0.35f, -0.95f);
        m.rightArm.roll = lerp(p, -0.3f, 0.25f);
        m.leftArm.yaw = lerp(p, 0.0f, 0.55f);
        m.leftArm.pitch = lerp(p, 0.3f, 0.5f);
        m.leftArm.roll = -0.15f;
        m.body.yaw = lerp(p, -0.4f, 0.35f);
        m.body.pitch = 0.05f;
        m.rightLeg.pitch = 0.2f;
        m.leftLeg.pitch = -0.15f;
    }

    /** Удар 3: восходящий диагональный разрез снизу справа вверх налево (обе руки). */
    private static void poseRisingSlash(PlayerEntityModel m, float p) {
        m.rightArm.pitch = lerp(p, 0.45f, -2.55f);
        m.rightArm.yaw = lerp(p, -0.65f, 0.55f);
        m.rightArm.roll = lerp(p, 0.25f, 0.0f);
        m.leftArm.pitch = lerp(p, 0.65f, -2.35f);
        m.leftArm.yaw = lerp(p, -0.4f, 0.35f);
        m.leftArm.roll = 0.0f;
        m.body.yaw = lerp(p, 0.3f, -0.3f);
        m.body.pitch = lerp(p, -0.1f, 0.12f);
        m.head.pitch = lerp(p, 0.1f, -0.3f);
        m.rightLeg.pitch = -0.2f;
        m.leftLeg.pitch = 0.2f;
    }

    /** Удар 4: выпад-укол вперёд (корпус наклонён, нога в шаге). */
    private static void poseThrust(PlayerEntityModel m, float p) {
        m.rightArm.pitch = lerp(p, -0.6f, -1.6f);
        m.rightArm.yaw = lerp(p, 0.25f, 0.0f);
        m.rightArm.roll = 0.0f;
        m.leftArm.pitch = lerp(p, 0.15f, -1.4f);
        m.leftArm.yaw = lerp(p, 0.45f, 0.25f);
        m.leftArm.roll = -0.15f;
        m.body.pitch = lerp(p, 0.1f, 0.6f);
        m.body.yaw = lerp(p, -0.2f, 0.0f);
        m.head.pitch = lerp(p, 0.05f, 0.4f);
        m.rightLeg.pitch = lerp(p, 0.0f, -0.6f);
        m.leftLeg.pitch = lerp(p, 0.0f, 0.45f);
    }

    /** Удар 5: круговой замах над головой с обрушением вниз (три фазы). */
    private static void poseOverheadSpin(PlayerEntityModel m, float p) {
        if (p < 0.4f) {
            // Замах: меч за голову, корпус закручивается вправо.
            float q = ease(p / 0.4f);
            m.rightArm.pitch = lerp(q, -0.8f, -2.7f);
            m.rightArm.yaw = lerp(q, 0.0f, 1.3f);
            m.leftArm.pitch = lerp(q, -0.7f, -2.6f);
            m.leftArm.yaw = lerp(q, 0.0f, 1.1f);
            m.body.yaw = lerp(q, 0.0f, 1.2f);
            m.body.pitch = -0.15f;
            m.head.pitch = -0.1f;
        } else if (p < 0.75f) {
            // Круговой разворот: меч описывает дугу над головой на другую сторону.
            float q = ease((p - 0.4f) / 0.35f);
            m.rightArm.pitch = lerp(q, -2.7f, -2.0f);
            m.rightArm.yaw = lerp(q, 1.3f, -1.3f);
            m.leftArm.pitch = lerp(q, -2.6f, -1.9f);
            m.leftArm.yaw = lerp(q, 1.1f, -1.1f);
            m.body.yaw = lerp(q, 1.2f, -1.2f);
            m.body.pitch = -0.1f;
            m.head.pitch = -0.05f;
        } else {
            // Обрушение: меч с силой падает вниз, корпус доворачивается к цели.
            float q = ease((p - 0.75f) / 0.25f);
            m.rightArm.pitch = lerp(q, -2.0f, -0.5f);
            m.rightArm.yaw = lerp(q, -1.3f, -0.7f);
            m.leftArm.pitch = lerp(q, -1.9f, -0.35f);
            m.leftArm.yaw = lerp(q, -1.1f, -0.5f);
            m.body.yaw = lerp(q, -1.2f, -0.55f);
            m.body.pitch = lerp(q, -0.1f, 0.3f);
            m.head.pitch = lerp(q, -0.05f, 0.15f);
        }
        m.rightLeg.pitch = -0.25f;
        m.leftLeg.pitch = 0.25f;
    }

    private static float lerp(float t, float a, float b) {
        return MathHelper.lerp(t, a, b);
    }
}
