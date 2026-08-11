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
 * в Genshin. Ванильная атака майна подавлена миксином MinecraftClient.doAttack.
 *
 * Анимация построена как непрерывная серия фаз: каждый удар = антиципация →
 * быстрый разрез (easeInOutCubic) → плавный возврат, который одновременно
 * является замахом следующего удара. Поэтому стыки между ударами не «прыгают»:
 * поза конца удара N совпадает с позой начала удара N+1. Удар 5 — круговой
 * замах над головой с обрушением. Удержание ЛКМ продолжает серию, тап — один удар.
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
                player.getY() + 1.0 + comboStep * 0.06,
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

    /** Наложить позу удара на модель игрока (вызывается в конце setAngles).
     *  Прогресс считается по кадрам (тик + tickDelta), поэтому на высоком FPS
     *  движение остаётся плавным, а не шагает по 20 тикам в секунду. */
    public static void applyPose(PlayerEntityModel model) {
        if (comboStep < 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);
        float p = Math.min(1f, (hitTicks + tickDelta) / SwordCombo.DURATION_TICKS[comboStep]);
        switch (comboStep) {
            case 0 -> poseHit0(model, p);
            case 1 -> poseHit1(model, p);
            case 2 -> poseHit2(model, p);
            case 3 -> poseHit3(model, p);
            case 4 -> poseHit4(model, p);
            default -> { }
        }
    }

    // ---------- Позы ----------
    // hPitch == NaN — голову не трогаем (она следует за взглядом, как в Genshin).

    private record Pose(float rYaw, float rPitch, float rRoll,
                        float lYaw, float lPitch, float lRoll,
                        float bYaw, float bPitch, float hPitch,
                        float rLegPitch, float lLegPitch) {}

    /** Нейтральная стойка (старт первого удара и конец пятого). */
    private static final Pose NEUTRAL = new Pose(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, Float.NaN, 0f, 0f);

    // Удар 1: горизонтальный разрез справа налево.
    private static final Pose W1 = new Pose(-1.15f, -1.0f, 0.3f, 0.4f, 0.35f, -0.2f, 0.35f, -0.05f, Float.NaN, -0.12f, 0.15f);
    private static final Pose S1 = new Pose(1.05f, -0.4f, -0.35f, -0.15f, 0.5f, -0.1f, -0.5f, 0.1f, Float.NaN, -0.12f, 0.15f);

    // Удар 2: обратный разрез слева направо.
    private static final Pose W2 = new Pose(1.0f, -0.5f, -0.3f, -0.25f, 0.5f, -0.1f, -0.45f, 0.05f, Float.NaN, 0.15f, -0.12f);
    private static final Pose S2 = new Pose(-1.2f, -0.95f, 0.3f, 0.55f, 0.25f, 0.15f, 0.5f, 0.1f, Float.NaN, 0.15f, -0.12f);

    // Удар 3: восходящий диагональный разрез снизу справа вверх налево (обе руки).
    private static final Pose W3 = new Pose(-0.7f, 0.55f, 0.25f, -0.35f, 0.7f, 0.1f, 0.35f, -0.15f, 0.05f, -0.15f, 0.18f);
    private static final Pose S3 = new Pose(0.55f, -2.6f, 0.0f, 0.35f, -2.4f, 0.0f, -0.4f, 0.2f, -0.3f, -0.15f, 0.18f);

    // Удар 4: выпад-укол вперёд (корпус наклонён, нога в шаге).
    private static final Pose W4 = new Pose(0.35f, -0.35f, -0.1f, 0.5f, 0.25f, -0.15f, -0.3f, 0.15f, 0.05f, 0.0f, 0.0f);
    private static final Pose S4 = new Pose(0.0f, -1.62f, 0.0f, 0.3f, -1.5f, -0.1f, 0.1f, 0.6f, 0.35f, -0.65f, 0.5f);

    // Удар 5: круговой замах над головой с обрушением.
    private static final Pose W5 = new Pose(1.25f, -2.65f, 0.15f, 1.05f, -2.5f, 0.1f, 1.15f, -0.15f, -0.05f, -0.2f, 0.22f);
    private static final Pose MID5 = new Pose(-1.2f, -2.15f, -0.1f, -1.0f, -2.0f, -0.05f, -1.15f, -0.1f, -0.05f, -0.2f, 0.22f);
    private static final Pose SLAM5 = new Pose(-0.65f, -0.45f, 0.0f, -0.45f, -0.35f, 0.0f, -0.5f, 0.35f, 0.1f, -0.3f, 0.3f);

    /** Первый удар: видимый замах из нейтральной стойки. */
    private static void poseHit0(PlayerEntityModel m, float p) {
        if (p < 0.3f) {
            pose(m, NEUTRAL, W1, easeInOutCubic(p / 0.3f));
        } else if (p < 0.7f) {
            pose(m, W1, S1, easeInOutCubic((p - 0.3f) / 0.4f));
        } else {
            // Возврат сразу перетекает в замах второго удара.
            pose(m, S1, W2, easeInOutSine((p - 0.7f) / 0.3f));
        }
    }

    /** Удары 2-4: быстрый разрез из замаха, затем возврат в замах следующего. */
    private static void poseHit1(PlayerEntityModel m, float p) {
        if (p < 0.45f) {
            pose(m, W2, S2, easeInOutCubic(p / 0.45f));
        } else {
            pose(m, S2, W3, easeInOutSine((p - 0.45f) / 0.55f));
        }
    }

    private static void poseHit2(PlayerEntityModel m, float p) {
        if (p < 0.45f) {
            pose(m, W3, S3, easeInOutCubic(p / 0.45f));
        } else {
            pose(m, S3, W4, easeInOutSine((p - 0.45f) / 0.55f));
        }
    }

    private static void poseHit3(PlayerEntityModel m, float p) {
        if (p < 0.45f) {
            pose(m, W4, S4, easeInOutCubic(p / 0.45f));
        } else {
            pose(m, S4, W5, easeInOutSine((p - 0.45f) / 0.55f));
        }
    }

    /** Пятый удар: замах за голову → круговой разворот → обрушение → стойка. */
    private static void poseHit4(PlayerEntityModel m, float p) {
        if (p < 0.32f) {
            pose(m, W5, W5, 1f);  // антиципация: меч уже за головой
        } else if (p < 0.62f) {
            pose(m, W5, MID5, easeInOutCubic((p - 0.32f) / 0.3f));
        } else if (p < 0.88f) {
            pose(m, MID5, SLAM5, easeInOutCubic((p - 0.62f) / 0.26f));
        } else {
            pose(m, SLAM5, NEUTRAL, easeInOutSine((p - 0.88f) / 0.12f));
        }
    }

    /** Интерполяция позы целиком (голова — только если задана). */
    private static void pose(PlayerEntityModel m, Pose a, Pose b, float t) {
        m.rightArm.yaw = lerp(t, a.rYaw(), b.rYaw());
        m.rightArm.pitch = lerp(t, a.rPitch(), b.rPitch());
        m.rightArm.roll = lerp(t, a.rRoll(), b.rRoll());
        m.leftArm.yaw = lerp(t, a.lYaw(), b.lYaw());
        m.leftArm.pitch = lerp(t, a.lPitch(), b.lPitch());
        m.leftArm.roll = lerp(t, a.lRoll(), b.lRoll());
        m.body.yaw = lerp(t, a.bYaw(), b.bYaw());
        m.body.pitch = lerp(t, a.bPitch(), b.bPitch());
        if (!Float.isNaN(a.hPitch()) || !Float.isNaN(b.hPitch())) {
            m.head.pitch = lerp(t, a.hPitch(), b.hPitch());
        }
        m.rightLeg.pitch = lerp(t, a.rLegPitch(), b.rLegPitch());
        m.leftLeg.pitch = lerp(t, a.lLegPitch(), b.lLegPitch());
    }

    private static float lerp(float t, float a, float b) {
        return MathHelper.lerp(t, a, b);
    }

    /** Плавный разгон и торможение (для замахов и возвратов). */
    private static float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    /** Мягкий синусоидальный переход (для возвратов в следующий замах). */
    private static float easeInOutSine(float t) {
        return (float) (-(Math.cos(Math.PI * t) - 1.0) / 2.0);
    }
}
