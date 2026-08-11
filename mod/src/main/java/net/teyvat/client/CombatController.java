package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;

/**
 * Боевка путешественника: комбо из 5 ударов мечом по ЛКМ, как у Итэра/Люмин
 * в Genshin. Ванильная атака майна подавлена миксином MinecraftClient.doAttack.
 *
 * Анимации — ключевые кадры, сгенерированные по описанию обычных атак
 * путешественника из Genshin (scripts/gen_genshin_combo.py): удар 1 —
 * широкий горизонтальный слева направо, удар 2 — диагональ снизу справа
 * вверх налево, удар 3 — разворот по кругу (клинок описывает круг справа
 * сверху влево вниз), удар 4 — горизонтальный справа налево, удар 5 —
 * очень широкий справа налево с замахом за спину и прокатом вперёд.
 * Переход к следующему удару живёт в хвосте предыдущего (EASE_IN_OUT_SINE,
 * без отскока в стойку), серия идёт непрерывно в темпе игры (полный круг
 * ~3.5 с): каждый удар — плавный замах, разгон свинга и сопровождение.
 * После тапа — короткая фаза восстановления в нейтраль. Удержание ЛКМ
 * продолжает серию.
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
    /** Кик от удара 0..1: пульс на момент разреза, затухает за ~0.5 сек.
     *  Двигает FOV и наклон камеры (как отдача в Genshin). */
    private static float impactKick;
    /** Тики фазы восстановления после одиночного удара (поза тает в нейтраль). */
    private static int recoveryTicks;

    /** Тиков восстановления после одиночного удара: поза плавно уходит в нейтраль
     *  (без отскока в ванильную стойку между тапами). */
    private static final int RECOVERY_TICKS = 8;

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        if (comboStep >= 0) {
            if (recoveryTicks > 0) {
                // Восстановление после одиночного удара: поза тает в нейтраль,
                // чтобы между тапами не было отскока в ванильную стойку.
                recoveryTicks--;
                if (recoveryTicks == 0) {
                    comboStep = -1;
                    idleTicks = 0;
                }
            } else {
                hitTicks++;
                if (hitTicks == SwordCombo.DAMAGE_TICKS[comboStep] && !sentHit) {
                    sentHit = true;
                    sendHit(comboStep);
                    spawnSlashEffects(client, client.player);
                    applyLunge(client.player);
                    impactKick = 1f;
                }
                if (hitTicks >= SwordCombo.DURATION_TICKS[comboStep]) {
                    if (bufferedNext) {
                        // Серия продолжается: следующий удар (после пятого — снова первый).
                        comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
                        hitTicks = 0;
                        bufferedNext = false;
                        sentHit = false;
                    } else {
                        // Тап: короткая фаза восстановления, клик в ней продолжает комбо.
                        lastStep = comboStep;
                        recoveryTicks = RECOVERY_TICKS;
                        bufferedNext = false;
                    }
                } else if (client.options.attackKey.isPressed()
                        && hitTicks >= SwordCombo.DAMAGE_TICKS[comboStep] + SwordCombo.CHAIN_INPUT_TICKS) {
                    // Удержание ЛКМ: следующий удар цепляется автоматически (как в Genshin).
                    bufferedNext = true;
                }
            }
        } else if (idleTicks <= SwordCombo.RESET_TICKS) {
            idleTicks++;
        }
        // Отдача затухает сама: пульс виден пару кадров, затем камера успокаивается.
        impactKick *= 0.84f;
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
        } else if (recoveryTicks > 0) {
            // Клик в фазе восстановления: сразу начинаем следующий удар серии.
            recoveryTicks = 0;
            comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
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

    /** Эффекты удара на тике урона: полумесяц-разрез, дуга искр по траектории
     *  меча и пыль из-под ног на «шагающих» ударах. Работают и по врагам,
     *  и по воздуху — свинг всегда анимируется. */
    private static void spawnSlashEffects(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        double yaw = Math.toRadians(player.getYaw());
        double d = -Math.sin(yaw);
        double e = Math.cos(yaw);
        // Полумесяц-разрез (ориентируется по дельтам, как ванильная свип-атака).
        world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                player.getX() + d * 0.6,
                player.getY() + 1.0 + comboStep * 0.06,
                player.getZ() + e * 0.6,
                d, 0.0, e);
        // Дуга размаха: штрихи-искры вдоль траектории меча, шире к пятому удару.
        int count = 9;
        double radius = 1.7;
        double height = 0.95 + comboStep * 0.09;
        float span = 1.05f + comboStep * 0.08f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            double ang = yaw + (t - 0.5f) * span;
            world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() - Math.sin(ang) * radius,
                    player.getY() + height,
                    player.getZ() + Math.cos(ang) * radius,
                    -Math.cos(ang) * 1.4, 0.0, -Math.sin(ang) * 1.4);
        }
        // Пыль из-под ног на ударах с шагом/выпадом.
        if (SwordCombo.LUNGE_STRENGTH[comboStep] >= 0.18f) {
            for (int i = 0; i < 3; i++) {
                world.addParticleClient(ParticleTypes.POOF,
                        player.getX() + (world.random.nextDouble() - 0.5) * 0.4,
                        player.getY() + 0.1,
                        player.getZ() + (world.random.nextDouble() - 0.5) * 0.4,
                        0, 0, 0);
            }
        }
    }

    /** Шаг героя вперёд на момент удара: лёгкий толчок по направлению взгляда,
     *  на пятом ударе — ещё и маленький подброс. Клиентское движение, как у рывка. */
    private static void applyLunge(ClientPlayerEntity player) {
        float strength = SwordCombo.LUNGE_STRENGTH[comboStep];
        float up = SwordCombo.LUNGE_UP[comboStep];
        if (strength <= 0f && up <= 0f) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x - Math.sin(yaw) * strength, v.y + up, v.z + Math.cos(yaw) * strength);
        player.velocityModified = true;
    }

    /** Идёт ли сейчас удар (для миксина модели). */
    public static boolean isSwinging() {
        return comboStep >= 0;
    }

    /** Отдача удара 0..1: пульс FOV и наклона камеры на момент разреза. */
    public static float impactKick() {
        return impactKick;
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
        Pose pose = CLIPS[comboStep].at(p);
        if (recoveryTicks > 0) {
            // Плавный уход в нейтраль после последнего удара (голова — за взглядом).
            float r = 1f - recoveryTicks / (float) RECOVERY_TICKS;
            pose = relax(pose, easeOutCubic(r));
        }
        applyPoseToModel(model, pose);
    }

    /** Смешать позу с нейтралью (голова уходит во взгляд игрока). */
    private static Pose relax(Pose p, float w) {
        return new Pose(
                lerp(w, p.rYaw(), 0f), lerp(w, p.rPitch(), 0f), lerp(w, p.rRoll(), 0f),
                lerp(w, p.lYaw(), 0f), lerp(w, p.lPitch(), 0f), lerp(w, p.lRoll(), 0f),
                lerp(w, p.bYaw(), 0f), lerp(w, p.bPitch(), 0f), lerp(w, p.bRoll(), 0f),
                Float.NaN, Float.NaN, Float.NaN,
                lerp(w, p.rlYaw(), 0f), lerp(w, p.rlPitch(), 0f), lerp(w, p.rlRoll(), 0f),
                lerp(w, p.llYaw(), 0f), lerp(w, p.llPitch(), 0f), lerp(w, p.llRoll(), 0f));
    }

    /** Ease-out cubic для восстановления: быстрое расслабление, мягкий выход. */
    private static float easeOutCubic(float t) {
        return 1f - (float) Math.pow(1f - t, 3);
    }

    // ---------- Позы ----------
    // Анимации — клипы ключевых кадров (time + поза + кривая), сгенерированы
    // скриптом scripts/gen_genshin_combo.py по описанию боевки путешественника
    // из Genshin: стыки — EASE_IN_OUT_SINE в хвосте удара, финал 5-го уходит
    // в нейтральную стойку.

    /** Кривые интерполяции сегмента: замах — E_IN_OUT_CUBIC, свинг — E_LINEAR,
     *  сопровождение — E_OUT_CUBIC, переходы — E_IN_OUT_SINE. */
    private static final int E_LINEAR = 0;
    private static final int E_IN_OUT_CUBIC = 1;
    private static final int E_OUT_CUBIC = 2;
    private static final int E_OUT_BACK = 3;
    private static final int E_IN_OUT_SINE = 4;

    /** NaN-канал не трогаем (у головы NaN = следует за взглядом игрока).
     *  Порядок: правая рука y/p/r, левая рука y/p/r, корпус y/p/r,
     *  голова y/p/r, правая нога y/p/r, левая нога y/p/r (радианы). */
    private record Pose(float rYaw, float rPitch, float rRoll,
                        float lYaw, float lPitch, float lRoll,
                        float bYaw, float bPitch, float bRoll,
                        float hYaw, float hPitch, float hRoll,
                        float rlYaw, float rlPitch, float rlRoll,
                        float llYaw, float llPitch, float llRoll) {}

    private record Keyframe(float t, int easing, Pose pose) {}

    /** Клип удара: непрерывная последовательность ключевых кадров 0..1. */
    private record Clip(Keyframe[] frames) {
        Pose at(float p) {
            if (p <= frames[0].t) {
                return frames[0].pose;
            }
            for (int i = 0; i < frames.length - 1; i++) {
                Keyframe a = frames[i];
                Keyframe b = frames[i + 1];
                if (p <= b.t) {
                    float span = b.t - a.t;
                    float u = span <= 0f ? 1f : (p - a.t) / span;
                    return mix(a.pose, b.pose, ease(a.easing, u));
                }
            }
            return frames[frames.length - 1].pose;
        }
    }

// Стыки между ударами — 0.000 (конец N = начало N+1, переход в хвосте N).
// Момент урона (середина свинга, доля клипа):
//   hit1_wide_l2r: u=0.50 -> DAMAGE_TICK=6
//   hit2_diag_r2l_up: u=0.50 -> DAMAGE_TICK=6
//   hit3_spin_circle: u=0.56 -> DAMAGE_TICK=9
//   hit4_slash_r2l: u=0.50 -> DAMAGE_TICK=6
//   hit5_wide_r2l_lunge: u=0.56 -> DAMAGE_TICK=10
    /** Позы пяти ударов, сгенерированы по описанию боевки
     *  путешественника из Genshin (scripts/gen_genshin_combo.py):
     *  удар 1 — широкий горизонтальный слева направо,
     *  удар 2 — диагональ снизу справа вверх налево,
     *  удар 3 — разворот по кругу (справа сверху влево вниз),
     *  удар 4 — горизонтальный справа налево,
     *  удар 5 — очень широкий справа налево, замах за спину
     *  и прокат вперёд.
     *  Стыки живут в хвосте предыдущего удара (EASE_IN_OUT_SINE),
     *  финал 5-го уходит в нейтральную стойку.
     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r,
     *  корпус y/p/r, голова y/p/r (NaN — не трогаем),
     *  прав. нога y/p/r, лев. нога y/p/r. Углы в радианах,
     *  как у ModelPart (pitch -> yaw -> roll). */
    // Удар 1: hit1_wide_l2r
    private static final Pose hit1_00 = new Pose(-3.141593f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, Float.NaN, Float.NaN, Float.NaN, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(2.490912f, 1.897414f, 0.350000f, 0.000000f, -0.280000f, -0.120000f, 0.300000f, 0.030000f, 0.000000f, 0.220000f, -0.030000f, 0.000000f, 0.000000f, 0.100000f, 0.000000f, 0.000000f, -0.080000f, 0.000000f);
    private static final Pose hit1_02 = new Pose(0.619109f, 1.835080f, 0.400000f, 0.000000f, -0.200000f, -0.100000f, 0.150000f, 0.020000f, 0.000000f, 0.120000f, -0.020000f, 0.000000f, 0.000000f, 0.080000f, 0.000000f, 0.000000f, -0.060000f, 0.000000f);
    private static final Pose hit1_03 = new Pose(-0.038632f, 1.532569f, 0.400000f, 0.000000f, -0.120000f, -0.080000f, -0.050000f, 0.020000f, 0.000000f, -0.040000f, 0.000000f, 0.000000f, 0.000000f, 0.020000f, 0.000000f, 0.000000f, 0.080000f, 0.000000f);
    private static final Pose hit1_04 = new Pose(-1.074163f, 1.127272f, 0.450000f, 0.000000f, -0.050000f, -0.050000f, -0.320000f, 0.020000f, 0.100000f, -0.220000f, 0.020000f, 0.000000f, 0.000000f, -0.050000f, 0.000000f, 0.000000f, 0.130000f, 0.000000f);
    private static final Pose hit1_05 = new Pose(-2.214253f, 1.374686f, 0.400000f, 0.000000f, -0.020000f, -0.020000f, -0.420000f, 0.040000f, 0.120000f, -0.300000f, 0.040000f, 0.000000f, 0.000000f, -0.090000f, 0.000000f, 0.000000f, 0.120000f, 0.000000f);
    private static final Pose hit1_06 = new Pose(-2.558932f, 1.173091f, 0.320000f, 0.000000f, -0.060000f, -0.040000f, -0.220000f, 0.020000f, 0.050000f, -0.150000f, 0.020000f, 0.000000f, 0.000000f, -0.030000f, 0.000000f, 0.000000f, 0.050000f, 0.000000f);
    private static final Pose hit1_07 = new Pose(-2.673742f, 0.944526f, 0.300000f, 0.000000f, -0.100000f, -0.050000f, -0.120000f, 0.020000f, 0.000000f, -0.080000f, 0.020000f, 0.000000f, 0.000000f, 0.040000f, 0.000000f, 0.000000f, 0.030000f, 0.000000f);
    // Удар 2: hit2_diag_r2l_up
    private static final Pose hit2_00 = new Pose(-2.673742f, 0.944526f, 0.300000f, 0.000000f, -0.100000f, -0.050000f, -0.120000f, 0.020000f, 0.000000f, -0.080000f, 0.020000f, 0.000000f, 0.000000f, 0.040000f, 0.000000f, 0.000000f, 0.030000f, 0.000000f);
    private static final Pose hit2_01 = new Pose(-2.548314f, 0.715195f, 0.300000f, 0.000000f, -0.220000f, -0.100000f, -0.250000f, 0.060000f, 0.000000f, -0.180000f, -0.100000f, 0.000000f, 0.000000f, 0.120000f, 0.000000f, 0.000000f, 0.100000f, 0.000000f);
    private static final Pose hit2_02 = new Pose(-0.546652f, 1.037050f, 0.350000f, 0.000000f, -0.160000f, -0.100000f, -0.100000f, 0.040000f, 0.000000f, -0.060000f, -0.060000f, 0.000000f, 0.000000f, 0.080000f, 0.000000f, 0.000000f, 0.080000f, 0.000000f);
    private static final Pose hit2_03 = new Pose(0.028879f, 1.450697f, 0.400000f, 0.000000f, -0.100000f, -0.080000f, 0.150000f, 0.000000f, 0.000000f, 0.100000f, -0.050000f, 0.000000f, 0.000000f, 0.020000f, 0.000000f, 0.000000f, 0.100000f, 0.000000f);
    private static final Pose hit2_04 = new Pose(0.241592f, 2.497293f, 0.450000f, 0.000000f, 0.020000f, -0.040000f, 0.420000f, -0.050000f, 0.000000f, 0.300000f, -0.080000f, 0.000000f, 0.000000f, -0.040000f, 0.000000f, 0.000000f, 0.120000f, 0.000000f);
    private static final Pose hit2_05 = new Pose(2.499553f, 2.588440f, 0.400000f, 0.000000f, 0.060000f, -0.020000f, 0.520000f, -0.060000f, 0.050000f, 0.380000f, -0.060000f, 0.000000f, 0.000000f, -0.080000f, 0.000000f, 0.000000f, 0.100000f, 0.000000f);
    private static final Pose hit2_06 = new Pose(-2.818416f, 2.097524f, 0.320000f, 0.000000f, -0.060000f, -0.040000f, 0.340000f, -0.040000f, 0.000000f, 0.240000f, -0.040000f, 0.000000f, 0.000000f, -0.040000f, 0.000000f, 0.000000f, 0.060000f, 0.000000f);
    private static final Pose hit2_07 = new Pose(-2.498168f, 1.953796f, 0.300000f, 0.000000f, -0.140000f, -0.080000f, 0.240000f, -0.040000f, 0.000000f, 0.160000f, -0.040000f, 0.000000f, 0.000000f, -0.040000f, 0.000000f, 0.000000f, 0.060000f, 0.000000f);
    // Удар 3: hit3_spin_circle
    private static final Pose hit3_00 = new Pose(-2.498168f, 1.953796f, 0.300000f, 0.000000f, -0.140000f, -0.080000f, 0.240000f, -0.040000f, 0.000000f, 0.160000f, -0.040000f, 0.000000f, 0.000000f, -0.040000f, 0.000000f, 0.000000f, 0.060000f, 0.000000f);
    private static final Pose hit3_01 = new Pose(-2.017135f, 1.964466f, 0.400000f, 0.000000f, -0.200000f, -0.120000f, -0.300000f, -0.030000f, 0.000000f, -0.220000f, -0.020000f, 0.000000f, 0.000000f, 0.080000f, 0.000000f, 0.000000f, -0.060000f, 0.000000f);
    private static final Pose hit3_02 = new Pose(-1.093237f, 1.763231f, 0.450000f, 0.000000f, -0.140000f, -0.100000f, 0.450000f, 0.020000f, 0.000000f, 0.320000f, 0.020000f, 0.000000f, 0.000000f, 0.060000f, 0.000000f, 0.000000f, -0.040000f, 0.000000f);
    private static final Pose hit3_03 = new Pose(-0.376022f, 1.714791f, 0.500000f, 0.000000f, -0.080000f, -0.060000f, 1.050000f, 0.040000f, 0.000000f, 0.780000f, 0.040000f, 0.000000f, 0.150000f, 0.040000f, 0.000000f, 0.050000f, 0.020000f, 0.000000f);
    private static final Pose hit3_04 = new Pose(0.435218f, 1.935284f, 0.500000f, 0.000000f, 0.020000f, -0.030000f, 1.550000f, 0.060000f, 0.050000f, 1.150000f, 0.030000f, 0.000000f, 0.300000f, 0.040000f, 0.000000f, 0.180000f, 0.020000f, 0.000000f);
    private static final Pose hit3_05 = new Pose(1.147604f, 1.758171f, 0.450000f, 0.000000f, 0.080000f, -0.020000f, 1.950000f, 0.040000f, 0.080000f, 1.450000f, 0.020000f, 0.000000f, 0.400000f, 0.060000f, 0.000000f, 0.300000f, 0.020000f, 0.000000f);
    private static final Pose hit3_06 = new Pose(2.237575f, 1.556475f, 0.400000f, 0.000000f, 0.100000f, 0.000000f, 1.750000f, 0.000000f, 0.050000f, 1.300000f, 0.000000f, 0.000000f, 0.350000f, 0.040000f, 0.000000f, 0.280000f, 0.020000f, 0.000000f);
    private static final Pose hit3_07 = new Pose(-2.909084f, 2.005483f, 0.350000f, 0.000000f, 0.000000f, -0.020000f, 1.500000f, -0.020000f, 0.020000f, 1.100000f, -0.020000f, 0.000000f, 0.280000f, 0.020000f, 0.000000f, 0.200000f, 0.020000f, 0.000000f);
    private static final Pose hit3_08 = new Pose(-2.465992f, 1.782532f, 0.300000f, 0.000000f, -0.100000f, -0.060000f, 1.300000f, 0.000000f, 0.000000f, 0.920000f, 0.000000f, 0.000000f, 0.220000f, 0.040000f, 0.000000f, 0.150000f, 0.020000f, 0.000000f);
    // Удар 4: hit4_slash_r2l
    private static final Pose hit4_00 = new Pose(-2.465992f, 1.782532f, 0.300000f, 0.000000f, -0.100000f, -0.060000f, 1.300000f, 0.000000f, 0.000000f, 0.920000f, 0.000000f, 0.000000f, 0.220000f, 0.040000f, 0.000000f, 0.150000f, 0.020000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(-2.204987f, 1.430784f, 0.400000f, 0.000000f, -0.160000f, -0.080000f, 1.650000f, 0.020000f, 0.000000f, 1.180000f, 0.020000f, 0.000000f, 0.180000f, 0.060000f, 0.000000f, 0.120000f, 0.000000f, 0.000000f);
    private static final Pose hit4_02 = new Pose(-1.012420f, 1.181967f, 0.450000f, 0.000000f, -0.100000f, -0.060000f, 1.750000f, 0.020000f, 0.000000f, 1.260000f, 0.000000f, 0.000000f, 0.140000f, 0.040000f, 0.000000f, 0.100000f, 0.020000f, 0.000000f);
    private static final Pose hit4_03 = new Pose(0.026971f, 1.561396f, 0.450000f, 0.000000f, -0.040000f, -0.030000f, 1.900000f, 0.020000f, 0.050000f, 1.360000f, 0.000000f, 0.000000f, 0.100000f, 0.000000f, 0.000000f, 0.080000f, 0.060000f, 0.000000f);
    private static final Pose hit4_04 = new Pose(1.041868f, 1.957975f, 0.500000f, 0.000000f, 0.040000f, -0.020000f, 2.050000f, 0.000000f, 0.100000f, 1.460000f, 0.000000f, 0.000000f, 0.060000f, -0.040000f, 0.000000f, 0.050000f, 0.100000f, 0.000000f);
    private static final Pose hit4_05 = new Pose(2.269192f, 2.084862f, 0.450000f, 0.000000f, 0.080000f, 0.000000f, 2.100000f, 0.000000f, 0.080000f, 1.500000f, 0.020000f, 0.000000f, 0.040000f, -0.080000f, 0.000000f, 0.030000f, 0.100000f, 0.000000f);
    private static final Pose hit4_06 = new Pose(2.983537f, 2.146324f, 0.350000f, 0.000000f, 0.020000f, -0.020000f, 1.850000f, -0.020000f, 0.020000f, 1.320000f, 0.000000f, 0.000000f, 0.060000f, -0.040000f, 0.000000f, 0.050000f, 0.060000f, 0.000000f);
    private static final Pose hit4_07 = new Pose(-2.411879f, 1.680436f, 0.300000f, 0.000000f, -0.080000f, -0.040000f, 1.550000f, 0.000000f, 0.020000f, 1.100000f, 0.020000f, 0.000000f, 0.100000f, -0.020000f, 0.000000f, 0.080000f, 0.050000f, 0.000000f);
    // Удар 5: hit5_wide_r2l_lunge
    private static final Pose hit5_00 = new Pose(-2.411879f, 1.680436f, 0.300000f, 0.000000f, -0.080000f, -0.040000f, 1.550000f, 0.000000f, 0.020000f, 1.100000f, 0.020000f, 0.000000f, 0.100000f, -0.020000f, 0.000000f, 0.080000f, 0.050000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(-2.274589f, 1.706977f, 0.450000f, 0.000000f, -0.250000f, -0.140000f, 1.950000f, 0.020000f, 0.000000f, 1.380000f, 0.020000f, 0.000000f, 0.080000f, 0.100000f, 0.000000f, 0.040000f, -0.040000f, 0.000000f);
    private static final Pose hit5_02 = new Pose(-2.746989f, 1.646478f, 0.500000f, 0.000000f, -0.180000f, -0.100000f, 2.200000f, 0.040000f, 0.000000f, 1.550000f, 0.040000f, 0.000000f, 0.050000f, 0.140000f, 0.000000f, 0.020000f, -0.080000f, 0.000000f);
    private static final Pose hit5_03 = new Pose(-0.947197f, 1.242066f, 0.500000f, 0.000000f, -0.080000f, -0.060000f, 2.350000f, 0.060000f, 0.000000f, 1.660000f, 0.040000f, 0.000000f, 0.020000f, 0.060000f, 0.000000f, 0.000000f, 0.060000f, 0.000000f);
    private static final Pose hit5_04 = new Pose(0.024209f, 1.526516f, 0.500000f, 0.000000f, -0.020000f, -0.030000f, 2.450000f, 0.080000f, 0.050000f, 1.720000f, 0.040000f, 0.000000f, -0.020000f, 0.020000f, 0.000000f, -0.020000f, 0.120000f, 0.000000f);
    private static final Pose hit5_05 = new Pose(1.055123f, 1.907822f, 0.500000f, 0.000000f, 0.060000f, -0.020000f, 2.550000f, 0.100000f, 0.100000f, 1.800000f, 0.050000f, 0.000000f, -0.040000f, -0.060000f, 0.000000f, -0.030000f, 0.160000f, 0.000000f);
    private static final Pose hit5_06 = new Pose(2.484601f, 1.558035f, 0.450000f, 0.000000f, 0.120000f, 0.000000f, 2.200000f, 0.220000f, 0.080000f, 1.550000f, 0.120000f, 0.000000f, 0.200000f, -0.300000f, -0.060000f, 0.100000f, 0.400000f, 0.000000f);
    private static final Pose hit5_07 = new Pose(2.924552f, 0.903601f, 0.350000f, 0.000000f, 0.040000f, -0.020000f, 1.100000f, 0.140000f, 0.040000f, 0.780000f, 0.080000f, 0.000000f, 0.120000f, -0.100000f, -0.030000f, 0.060000f, 0.180000f, 0.000000f);
    private static final Pose hit5_08 = new Pose(2.665428f, 0.554286f, 0.300000f, 0.000000f, 0.020000f, -0.010000f, 0.450000f, 0.080000f, 0.020000f, 0.320000f, 0.040000f, 0.000000f, 0.060000f, -0.050000f, -0.020000f, 0.030000f, 0.080000f, 0.000000f);
    private static final Pose hit5_09 = new Pose(-3.141593f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, Float.NaN, Float.NaN, Float.NaN, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    /** Клипы пяти ударов: конец N = начало N+1 (переход в хвосте удара N). */
    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // удар 1: hit1_wide_l2r
                new Keyframe(0.000f, 4, hit1_00),
                new Keyframe(0.160f, 4, hit1_01),
                new Keyframe(0.300f, 1, hit1_02),
                new Keyframe(0.440f, 1, hit1_03),
                new Keyframe(0.580f, 2, hit1_04),
                new Keyframe(0.720f, 2, hit1_05),
                new Keyframe(0.860f, 4, hit1_06),
                new Keyframe(1.000f, 4, hit1_07),
        }),
        new Clip(new Keyframe[] { // удар 2: hit2_diag_r2l_up
                new Keyframe(0.000f, 4, hit2_00),
                new Keyframe(0.160f, 4, hit2_01),
                new Keyframe(0.300f, 1, hit2_02),
                new Keyframe(0.440f, 1, hit2_03),
                new Keyframe(0.580f, 2, hit2_04),
                new Keyframe(0.720f, 2, hit2_05),
                new Keyframe(0.860f, 4, hit2_06),
                new Keyframe(1.000f, 4, hit2_07),
        }),
        new Clip(new Keyframe[] { // удар 3: hit3_spin_circle
                new Keyframe(0.000f, 4, hit3_00),
                new Keyframe(0.140f, 4, hit3_01),
                new Keyframe(0.260f, 1, hit3_02),
                new Keyframe(0.380f, 1, hit3_03),
                new Keyframe(0.500f, 1, hit3_04),
                new Keyframe(0.620f, 2, hit3_05),
                new Keyframe(0.760f, 2, hit3_06),
                new Keyframe(0.880f, 4, hit3_07),
                new Keyframe(1.000f, 4, hit3_08),
        }),
        new Clip(new Keyframe[] { // удар 4: hit4_slash_r2l
                new Keyframe(0.000f, 4, hit4_00),
                new Keyframe(0.160f, 4, hit4_01),
                new Keyframe(0.300f, 1, hit4_02),
                new Keyframe(0.440f, 1, hit4_03),
                new Keyframe(0.580f, 2, hit4_04),
                new Keyframe(0.720f, 2, hit4_05),
                new Keyframe(0.860f, 4, hit4_06),
                new Keyframe(1.000f, 4, hit4_07),
        }),
        new Clip(new Keyframe[] { // удар 5: hit5_wide_r2l_lunge
                new Keyframe(0.000f, 4, hit5_00),
                new Keyframe(0.120f, 4, hit5_01),
                new Keyframe(0.240f, 4, hit5_02),
                new Keyframe(0.360f, 1, hit5_03),
                new Keyframe(0.500f, 1, hit5_04),
                new Keyframe(0.620f, 2, hit5_05),
                new Keyframe(0.740f, 2, hit5_06),
                new Keyframe(0.880f, 4, hit5_07),
                new Keyframe(0.940f, 4, hit5_08),
                new Keyframe(1.000f, 4, hit5_09),
        }),
    };





    /** Смешать две позы (голова — NaN-безопасно: NaN «замирает» на другом значении). */
    private static Pose mix(Pose a, Pose b, float t) {
        return new Pose(
                lerp(t, a.rYaw(), b.rYaw()),
                lerp(t, a.rPitch(), b.rPitch()),
                lerp(t, a.rRoll(), b.rRoll()),
                lerp(t, a.lYaw(), b.lYaw()),
                lerp(t, a.lPitch(), b.lPitch()),
                lerp(t, a.lRoll(), b.lRoll()),
                lerp(t, a.bYaw(), b.bYaw()),
                lerp(t, a.bPitch(), b.bPitch()),
                lerp(t, a.bRoll(), b.bRoll()),
                lerpSafe(t, a.hYaw(), b.hYaw()),
                lerpSafe(t, a.hPitch(), b.hPitch()),
                lerpSafe(t, a.hRoll(), b.hRoll()),
                lerp(t, a.rlYaw(), b.rlYaw()),
                lerp(t, a.rlPitch(), b.rlPitch()),
                lerp(t, a.rlRoll(), b.rlRoll()),
                lerp(t, a.llYaw(), b.llYaw()),
                lerp(t, a.llPitch(), b.llPitch()),
                lerp(t, a.llRoll(), b.llRoll()));
    }

    /** Наложить позу на модель: руки, корпус, голова (NaN — не трогаем), ноги. */
    private static void applyPoseToModel(PlayerEntityModel m, Pose pose) {
        m.rightArm.yaw = pose.rYaw();
        m.rightArm.pitch = pose.rPitch();
        m.rightArm.roll = pose.rRoll();
        m.leftArm.yaw = pose.lYaw();
        m.leftArm.pitch = pose.lPitch();
        m.leftArm.roll = pose.lRoll();
        m.body.yaw = pose.bYaw();
        m.body.pitch = pose.bPitch();
        m.body.roll = pose.bRoll();
        if (!Float.isNaN(pose.hYaw())) {
            m.head.yaw = pose.hYaw();
        }
        if (!Float.isNaN(pose.hPitch())) {
            m.head.pitch = pose.hPitch();
        }
        if (!Float.isNaN(pose.hRoll())) {
            m.head.roll = pose.hRoll();
        }
        m.rightLeg.yaw = pose.rlYaw();
        m.rightLeg.pitch = pose.rlPitch();
        m.rightLeg.roll = pose.rlRoll();
        m.leftLeg.yaw = pose.llYaw();
        m.leftLeg.pitch = pose.llPitch();
        m.leftLeg.roll = pose.llRoll();
    }

    /** Кривые сегментов. OUT_BACK даёт перелёт ~10% — «хлыстовое» движение меча. */
    private static float ease(int kind, float t) {
        switch (kind) {
            case E_IN_OUT_CUBIC -> {
                return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            }
            case E_OUT_CUBIC -> {
                return 1f - (float) Math.pow(1f - t, 3);
            }
            case E_OUT_BACK -> {
                float c1 = 1.1f;
                float c3 = c1 + 1f;
                return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
            }
            case E_IN_OUT_SINE -> {
                return (float) (-(Math.cos(Math.PI * t) - 1.0) / 2.0);
            }
            default -> {
                return t;
            }
        }
    }

    private static float lerp(float t, float a, float b) {
        return MathHelper.lerp(t, a, b);
    }

    /** Lerp, который не даёт NaN: NaN заменяется на другое значение (голова «замирает»). */
    private static float lerpSafe(float t, float a, float b) {
        if (Float.isNaN(a)) {
            return b;
        }
        if (Float.isNaN(b)) {
            return a;
        }
        return MathHelper.lerp(t, a, b);
    }
}
