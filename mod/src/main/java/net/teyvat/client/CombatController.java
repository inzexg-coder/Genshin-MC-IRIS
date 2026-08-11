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
 * Анимации — готовые клипы BetterCombat (автор Daedelus), подогнанные под
 * описание обычных атак путешественника из Genshin (scripts/port_bc_combo.py):
 * удар 1 — горизонтальный слева направо, удар 2 — длинный апперкот справа
 * снизу вверх влево, удар 3 — разворот через левое плечо на 360° с рубящим
 * ударом, удар 4 — горизонтальный справа налево, удар 5 — широкий замах,
 * удар справа налево и увод клинка за спину. Голова ВСЕГДА смотрит строго
 * вперёд (все каналы головы = 0 — никаких кривых «за клинком»). Левая рука
 * держит расслабленную стойку, ноги стоят ровно: движение делают только
 * клинок (правая рука) и корпус, тело не «распадается» на части. Разворот
 * на 360° делает root ванильной модели (getRootPart().yaw): торс, голова,
 * руки, ноги и клинок в руке поворачиваются как единое целое — без
 * «отдельного вращения тела». Хвосты-«удержания» обрезаны, переходы живут
 * в хвосте предыдущего удара (EASE_IN_OUT_SINE), углы развёрнуты в
 * непрерывную кривую — без лишних проворотов на ±360°. Темп замедлен
 * (полный круг ~7.4 с): длинный медленный замах -> короткий решительный
 * рывок свинга -> плавное сопровождение, так удары ощущаются сильными.
 * После тапа — фаза восстановления в нейтраль. Удержание ЛКМ продолжает серию.
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
    private static final int RECOVERY_TICKS = 10;

    private CombatController() {}

    /** Вызывается каждый клиентский тик (END_CLIENT_TICK). */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return;
        }
        if (comboStep >= 0 && !client.player.isOnGround()) {
            // В воздухе удары не работают (как в Genshin): прыжок прерывает комбо,
            // следующий клик на земле начнёт серию заново.
            comboStep = -1;
            hitTicks = 0;
            recoveryTicks = 0;
            bufferedNext = false;
            sentHit = false;
            lastStep = -1;
            idleTicks = 0;
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
                    impactKick = 1.3f;
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
        if (!client.player.isOnGround()) {
            // В воздухе обычные атаки не работают (только приземление), клик съедается.
            return true;
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
    public static void applyPose(PlayerEntityModel model, int entityId) {
        if (!isLocalPlayer(entityId)) {
            return;
        }
        if (comboStep < 0) {
            // Сброс: прерванный в воздухе разворот не оставляет модель
            // повёрнутой (vanilla root.yaw в setAngles не трогает).
            model.getRootPart().yaw = 0f;
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
        // Разворот: полный оборот делает root модели — торс, голова, руки,
        // ноги и клинок в руке поворачиваются как единое целое (никакого
        // «отдельного вращения тела»). 2π ≡ 0, поэтому на стыке с ударами
        // 2 и 4 рывка не видно. Вне разворота root = 0 (сброс stale-угла).
        model.getRootPart().yaw = comboStep == 2 ? spinTurn(p) : 0f;
    }

    /** Прогресс полного оборота разворота 0..1: короткая пауза-замах, затем
     *  плавный разгон -> оборот -> стабилизация (E_IN_OUT_CUBIC). */
    private static float spinTurn(float p) {
        float u = MathHelper.clamp((p - 0.14f) / 0.62f, 0f, 1f);
        return (float) (Math.PI * 2.0) * ease(E_IN_OUT_CUBIC, u);
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
    // скриптом scripts/port_bc_combo.py из готовых анимаций BetterCombat:
    // стыки — EASE_IN_OUT_SINE в хвосте удара, голова всегда смотрит строго
    // вперёд, левая рука и ноги в упрощённой стойке (не двигаются сами по
    // себе), разворот крутит root модели (см. spinTurn), финал 5-го уводит
    // клинок за спину.

    /** Кривые интерполяции сегмента: замах — E_IN_OUT_CUBIC, свинг — E_LINEAR,
     *  сопровождение — E_OUT_CUBIC, переходы — E_IN_OUT_SINE. */
    private static final int E_LINEAR = 0;
    private static final int E_IN_OUT_CUBIC = 1;
    private static final int E_OUT_CUBIC = 2;
    private static final int E_OUT_BACK = 3;
    private static final int E_IN_OUT_SINE = 4;

    /** NaN-канал не трогаем (в клипах головы нет: она всегда смотрит по
     *  направлению удара; NaN появляется только в фазе восстановления —
     *  голова следует за взглядом игрока). Порядок: правая рука y/p/r,
     *  левая рука y/p/r, корпус y/p/r, голова y/p/r, правая нога y/p/r,
     *  левая нога y/p/r (радианы). */
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

    /** Позы пяти ударов, сгенерированы из готовых анимаций BetterCombat
     *  (scripts/port_bc_combo.py, исходники в scripts/anim_sources, автор
     *  анимаций Daedelus): удар 1 — горизонтальный слева направо,
     *  удар 2 — длинный апперкот справа снизу вверх влево, удар 3 —
     *  разворот через левое плечо на 360° с рубящим ударом, удар 4 —
     *  горизонтальный справа налево, удар 5 — широкий замах и удар
     *  справа налево с уводом клинка за спину.
     *  Голова всегда смотрит по направлению удара, но в физиологичном
     *  диапазоне (без абсолютных 2π) — кривая головы следит за клинком.
     *  Полный оборот разворота делает root модели (см. spinTurn),
     *  поэтому всё тело и клинок в руке поворачиваются вместе.
     *  Углы развёрнуты в непрерывную кривую (без проворотов
     *  на ±360°), стыки живут в хвосте удара (EASE_IN_OUT_SINE).
     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус y/p/r,
     *  голова y/p/r, прав. нога y/p/r, лев. нога y/p/r. Углы в радианах,
     *  как у ModelPart (pitch -> yaw -> roll). */
    // Удар 1: hit2_slash_l2r (L->R)
    private static final Pose hit1_00 = new Pose(0.000000f, 0.000000f, 0.000000f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(-1.364577f, -1.165769f, 0.209705f, 0.050000f, -0.250000f, 0.000000f, 0.987599f, 0.100273f, 0.063954f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_02 = new Pose(-0.818640f, -1.454618f, 0.192531f, 0.050000f, -0.250000f, 0.000000f, 0.881116f, 0.067253f, 0.056337f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_03 = new Pose(-0.530830f, -1.571163f, 0.141008f, 0.050000f, -0.250000f, 0.000000f, 0.561668f, -0.031807f, 0.033487f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_04 = new Pose(-0.051145f, -1.765404f, 0.055136f, 0.050000f, -0.250000f, 0.000000f, 0.029255f, -0.196907f, -0.004596f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_05 = new Pose(0.748582f, -2.160833f, -0.491939f, 0.050000f, -0.250000f, 0.000000f, -0.175654f, -0.195887f, -0.004836f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_06 = new Pose(1.148445f, -2.358547f, -0.765476f, 0.050000f, -0.250000f, 0.000000f, -0.329335f, -0.195122f, -0.005017f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_07 = new Pose(1.224356f, -2.439909f, -0.856132f, 0.050000f, -0.250000f, 0.000000f, -0.431790f, -0.194613f, -0.005137f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_08 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.050000f, -0.250000f, 0.000000f, -0.459073f, 0.208042f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_09 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.050000f, -0.250000f, 0.000000f, -0.459073f, 0.208042f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    // Удар 2: hit3_uppercut (R low -> L up)
    private static final Pose hit2_00 = new Pose(0.084932f, -5.508543f, 1.010261f, 0.050000f, -0.250000f, 0.000000f, -0.459073f, 0.208042f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_01 = new Pose(0.084932f, -5.158543f, 1.010261f, 0.050000f, -0.250000f, 0.000000f, -0.459073f, 0.308042f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_02 = new Pose(0.221309f, -6.078504f, 0.285732f, 0.050000f, -0.250000f, 0.000000f, 0.265448f, -0.029857f, -0.037675f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_03 = new Pose(0.507131f, -6.916054f, 0.291184f, 0.050000f, -0.250000f, 0.000000f, 0.633078f, -0.056817f, -0.078771f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_04 = new Pose(0.792954f, -7.753604f, 0.296637f, 0.050000f, -0.250000f, 0.000000f, 1.000709f, -0.083777f, -0.119866f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_05 = new Pose(0.787992f, -7.999039f, 0.512870f, 0.050000f, -0.250000f, 0.000000f, 0.991166f, -0.156952f, -0.170747f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_06 = new Pose(0.550000f, -8.174350f, 0.667322f, 0.050000f, -0.250000f, 0.000000f, 0.984350f, -0.209220f, -0.207091f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_07 = new Pose(-0.350000f, -8.314598f, 0.790884f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.236166f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_08 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.014780f, 0.196205f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_09 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.014780f, 0.196205f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    // Удар 3: hit5_spin (360deg left)
    private static final Pose hit3_00 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.014780f, 0.196205f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_01 = new Pose(0.031490f, -8.440826f, 1.656075f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.014780f, 0.196205f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_02 = new Pose(-0.000275f, -8.438595f, 1.667136f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, -0.046436f, 0.073686f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_03 = new Pose(-0.032040f, -8.436364f, 1.678197f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, -0.030844f, 0.117760f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_04 = new Pose(-0.090441f, -8.129817f, 1.676600f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_05 = new Pose(-0.088135f, -8.051431f, 1.624424f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, -0.034907f, -0.122173f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_06 = new Pose(-0.081217f, -7.816271f, 1.467897f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, -0.087266f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_07 = new Pose(-0.074299f, -7.581112f, 1.311370f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_08 = new Pose(0.492447f, -6.656308f, 1.422186f, 0.050000f, -0.250000f, 0.000000f, -0.252490f, 0.114423f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_09 = new Pose(0.492447f, -6.656308f, 1.422186f, 0.050000f, -0.250000f, 0.000000f, -0.252490f, 0.114423f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    // Удар 4: hit1_slash_r2l (R->L)
    private static final Pose hit4_00 = new Pose(0.492447f, -6.656308f, 1.422186f, 0.050000f, -0.250000f, 0.000000f, -0.252490f, 0.114423f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(1.356147f, -5.599650f, 1.512854f, 0.050000f, -0.250000f, 0.000000f, -0.459073f, 0.208042f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_02 = new Pose(0.947461f, -6.112549f, 1.366530f, 0.050000f, -0.250000f, 0.000000f, 0.265448f, -0.029857f, -0.037675f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_03 = new Pose(0.921403f, -6.751248f, 0.927559f, 0.050000f, -0.250000f, 0.000000f, 0.622172f, -0.140446f, -0.152763f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_04 = new Pose(0.877973f, -7.815746f, 0.195940f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.267850f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_05 = new Pose(-0.273602f, -7.710864f, 0.183535f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.244087f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_06 = new Pose(-0.657461f, -7.675904f, 0.179400f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.236166f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_07 = new Pose(-0.914577f, -7.698955f, 0.209705f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.236166f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_08 = new Pose(-0.914577f, -7.698955f, 0.209705f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.236166f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    // Удар 5: hit5_switch_blade (wide R->L + behind back)
    private static final Pose hit5_00 = new Pose(-0.914577f, -7.698955f, 0.209705f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.251035f, -0.236166f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(-0.664577f, -8.248955f, 0.209705f, 0.050000f, -0.250000f, 0.000000f, 0.978897f, -0.371035f, -0.236166f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_02 = new Pose(-0.844265f, -7.720692f, 0.196825f, 0.050000f, -0.250000f, 0.000000f, 0.951588f, -0.247816f, -0.221740f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_03 = new Pose(-0.633328f, -7.785903f, 0.158185f, 0.050000f, -0.250000f, 0.000000f, 0.869662f, -0.238161f, -0.178465f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_04 = new Pose(-0.281767f, -7.894589f, 0.093785f, 0.050000f, -0.250000f, 0.000000f, 0.733119f, -0.222068f, -0.106339f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_05 = new Pose(0.210419f, -8.046749f, 0.003624f, 0.050000f, -0.250000f, 0.000000f, 0.541958f, -0.199538f, -0.005362f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_06 = new Pose(0.733420f, -8.516134f, -0.545437f, 0.050000f, -0.250000f, 0.000000f, 0.090730f, -0.197258f, -0.005293f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_07 = new Pose(1.106993f, -8.851409f, -0.937624f, 0.050000f, -0.250000f, 0.000000f, -0.231576f, -0.195629f, -0.005244f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_08 = new Pose(1.331136f, -9.052574f, -1.172936f, 0.050000f, -0.250000f, 0.000000f, -0.424959f, -0.194652f, -0.005215f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_09 = new Pose(0.000000f, -6.283185f, 0.000000f, 0.050000f, -0.250000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    /** Клипы пяти ударов: конец N = начало N+1 (переход в хвосте удара N). */
    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // удар 1: hit2_slash_l2r (L->R)
                new Keyframe(0.000f, 4, hit1_00),
                new Keyframe(0.400f, 4, hit1_01),
                new Keyframe(0.453f, 0, hit1_02),
                new Keyframe(0.507f, 0, hit1_03),
                new Keyframe(0.560f, 0, hit1_04),
                new Keyframe(0.667f, 0, hit1_05),
                new Keyframe(0.773f, 0, hit1_06),
                new Keyframe(0.880f, 0, hit1_07),
                new Keyframe(0.980f, 4, hit1_08),
                new Keyframe(1.000f, 4, hit1_09),
        }),
        new Clip(new Keyframe[] { // удар 2: hit3_uppercut (R low -> L up)
                new Keyframe(0.000f, 4, hit2_00),
                new Keyframe(0.340f, 4, hit2_01),
                new Keyframe(0.400f, 0, hit2_02),
                new Keyframe(0.460f, 0, hit2_03),
                new Keyframe(0.520f, 0, hit2_04),
                new Keyframe(0.627f, 0, hit2_05),
                new Keyframe(0.733f, 0, hit2_06),
                new Keyframe(0.840f, 0, hit2_07),
                new Keyframe(0.980f, 4, hit2_08),
                new Keyframe(1.000f, 4, hit2_09),
        }),
        new Clip(new Keyframe[] { // удар 3: hit5_spin (360deg left)
                new Keyframe(0.000f, 4, hit3_00),
                new Keyframe(0.100f, 0, hit3_01),
                new Keyframe(0.177f, 0, hit3_02),
                new Keyframe(0.253f, 0, hit3_03),
                new Keyframe(0.330f, 0, hit3_04),
                new Keyframe(0.407f, 0, hit3_05),
                new Keyframe(0.483f, 0, hit3_06),
                new Keyframe(0.560f, 0, hit3_07),
                new Keyframe(0.720f, 4, hit3_08),
                new Keyframe(1.000f, 4, hit3_09),
        }),
        new Clip(new Keyframe[] { // удар 4: hit1_slash_r2l (R->L)
                new Keyframe(0.000f, 4, hit4_00),
                new Keyframe(0.400f, 4, hit4_01),
                new Keyframe(0.460f, 0, hit4_02),
                new Keyframe(0.520f, 0, hit4_03),
                new Keyframe(0.580f, 0, hit4_04),
                new Keyframe(0.730f, 0, hit4_05),
                new Keyframe(0.880f, 0, hit4_06),
                new Keyframe(0.980f, 4, hit4_07),
                new Keyframe(1.000f, 4, hit4_08),
        }),
        new Clip(new Keyframe[] { // удар 5: hit5_switch_blade (wide R->L + behind back)
                new Keyframe(0.000f, 4, hit5_00),
                new Keyframe(0.360f, 4, hit5_01),
                new Keyframe(0.405f, 0, hit5_02),
                new Keyframe(0.450f, 0, hit5_03),
                new Keyframe(0.495f, 0, hit5_04),
                new Keyframe(0.540f, 0, hit5_05),
                new Keyframe(0.640f, 0, hit5_06),
                new Keyframe(0.740f, 0, hit5_07),
                new Keyframe(0.880f, 4, hit5_08),
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
