package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Боевка путешественника: комбо из 5 ударов мечом по ЛКМ, как у Итэра/Люмин
 * в Genshin. Ванильная атака майна подавлена миксином MinecraftClient.doAttack.
 *
 * Анимации — клипы, вручную сгенерированные по описанию обычных атак
 * путешественника из Genshin (scripts/gen_combo.py): удар 1 — широкий
 * горизонтальный слева направо, удар 2 — длинный апперкот справа снизу
 * вверх влево, удар 3 — разворот через левое плечо на 360° с рубящим ударом
 * по диагонали, удар 4 — горизонтальный справа налево, удар 5 — очень
 * широкий замах, удар справа налево и увод клинка за спину (с прокатом).
 * Принципы качества: голова ВСЕГДА смотрит строго вперёд (принудительно
 * после любой позы — не следит за камерой); удары МГНОВЕННЫЕ — t=0 клипа
 * это уже замах (клинок отведён), свинг стартует с первого кадра, пик
 * скорости (момент урона) ~0.40 клипа; удары МАКСИМАЛЬНО ШИРОКИЕ — roll до
 * ±155°, pitch до −178°/+60°; корпус доворачивается (bYaw до ±52°) и
 * наклоняется в удар (bPitch до +30°); левая рука — живой противовес;
 * ноги переступают с выпадом в момент урона. Каждый клип непрерывен
 * (t=0 = финал предыдущего удара); разворот на 360° делает root ванильной
 * модели (getRootPart().yaw): торс, голова, руки и клинок поворачиваются
 * как единое целое. Полный круг ≈ 3.5 с, удержание ЛКМ цепляет следующий
 * удар сразу после урона. Клинок вырисовывает разрез: во время свинга
 * рисуется светящаяся дуга-полумесяц (трейл по траектории клинка +
 * усиленные частицы). Между анимациями — плавные переходы (первые 12%
 * каждого удара смешиваются с предыдущей позой через prevAppliedPose).
 * Во время ударов движение клавишами блокируется, герой двигается только
 * микро-рывком по направлению атаки. В первом лице отрисовывается
 * собственное тело (FirstPersonBody): видны все анимации персонажа,
 * «глазами модельки», голова скрыта (см. PlayerEntityModelMixin). После
 * тапа — короткая фаза восстановления в нейтраль. Вне комбо — чистый
 * цикл бега: руки машут противофазно ногам, корпус наклонён и покачивается,
 * лёгкий подскок; в покое — дыхание.
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
    private static final int RECOVERY_TICKS = 6;

    /** Последняя наложенная поза: из неё плавно «въезжаем» в следующий удар
     *  (первые ~12% клипа смешиваются с предыдущей позой — без рывков между
     *  анимациями). */
    private static Pose prevAppliedPose;
    private static boolean hasPrevAppliedPose;

    /** Доля клипа, на которой работает смешивание перехода между ударами. */
    private static final float TRANSITION_BLEND = 0.12f;

    /** Светящийся разрез-полумесяц: мировые точки траектории клинка за
     *  последние кадры свинга. Точки стареют и тают (SLASH_TRAIL_AGE),
     *  лента рисуется в FirstPersonBody. */
    record SlashPoint(Vec3d pos, int age) {}
    private static final Deque<SlashPoint> slashTrail = new ArrayDeque<>();
    private static final int SLASH_TRAIL_AGE = 7;

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
                    impactKick = 1.6f;
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
        // Полумесяцы-разрезы: серия ярких серпов вдоль дуги удара (шире к пятому).
        int crescents = 3;
        float arcSpan = 1.45f + comboStep * 0.12f;
        for (int i = 0; i < crescents; i++) {
            float t = (float) i / (crescents - 1);
            double ang = yaw + (t - 0.5f) * arcSpan;
            world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                    player.getX() - Math.sin(ang) * 0.9,
                    player.getY() + 1.05 + comboStep * 0.07,
                    player.getZ() + Math.cos(ang) * 0.9,
                    -Math.cos(ang), 0.0, -Math.sin(ang));
        }
        // Дуга размаха: плотные штрихи-искры вдоль траектории меча.
        int count = 16;
        double radius = 2.4;
        double height = 1.0 + comboStep * 0.09;
        float span = 1.55f + comboStep * 0.12f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            double ang = yaw + (t - 0.5f) * span;
            world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() - Math.sin(ang) * radius,
                    player.getY() + height,
                    player.getZ() + Math.cos(ang) * radius,
                    -Math.cos(ang) * 1.6, 0.0, -Math.sin(ang) * 1.6);
        }
        // Вспышка в точке контакта: белый крест искр прямо перед героем.
        world.addParticleClient(ParticleTypes.END_ROD,
                player.getX() + d * 1.1,
                player.getY() + 1.15 + comboStep * 0.06,
                player.getZ() + e * 1.1,
                d * 0.8, 0.12, e * 0.8);
        world.addParticleClient(ParticleTypes.END_ROD,
                player.getX() + d * 1.1,
                player.getY() + 1.35 + comboStep * 0.06,
                player.getZ() + e * 1.1,
                -d * 0.5, 0.05, -e * 0.5);
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

    /** Единая точка входа из миксина модели: во время комбо — поза удара,
     *  вне комбо — эпичная ходьба/бег в стиле Origin Animation (широкие махи
     *  рук и ног, наклон и поворот корпуса, лёгкий подскок; в покое —
     *  дыхание). root.yaw сбрасывается вне разворота. */
    public static void applyPlayerPose(PlayerEntityModel model, PlayerEntityRenderState state) {
        if (!isLocalPlayer(state.id)) {
            return;
        }
        if (comboStep >= 0) {
            applyPose(model, state.id);
        } else {
            model.getRootPart().yaw = 0f;
            applyLocomotion(model, state);
            // Запоминаем позу бега: из неё первый удар «въедет» без рывка.
            prevAppliedPose = poseFromModel(model);
            hasPrevAppliedPose = true;
        }
        // Голова ВСЕГДА смотрит строго вперёд (относительно корпуса): не
        // следит за камерой и не вертится во время ударов и бега.
        model.head.yaw = 0f;
        model.head.pitch = 0f;
        model.head.roll = 0f;
    }

    /** Прочитать текущие углы модели как позу (для плавных переходов). */
    private static Pose poseFromModel(PlayerEntityModel m) {
        return new Pose(
                m.rightArm.yaw, m.rightArm.pitch, m.rightArm.roll,
                m.leftArm.yaw, m.leftArm.pitch, m.leftArm.roll,
                m.body.yaw, m.body.pitch, m.body.roll,
                Float.NaN, Float.NaN, Float.NaN,
                m.rightLeg.yaw, m.rightLeg.pitch, m.rightLeg.roll,
                m.leftLeg.yaw, m.leftLeg.pitch, m.leftLeg.roll);
    }

    /** Эпичная ходьба/бег (стиль Origin Animation): руки машут широко
     *  (до ±63°), ноги поднимаются высоко (до ±52°), корпус наклонён вперёд
     *  и слегка поворачивается на каждый шаг, модель чуть подпрыгивает.
     *  Значения ванильной позы (постановка руки с предметом) смешиваются
     *  с эпичной по мере набора скорости — в покое руки держат меч как в
     *  ванилле, добавляется только лёгкое дыхание. Голова не трогается:
     *  она всегда смотрит туда, куда игрок. */
    private static void applyLocomotion(PlayerEntityModel model, PlayerEntityRenderState state) {
        if (state.isSwimming || state.hasVehicle || state.isGliding || state.isInSneakingPose) {
            return;
        }
        float amt = MathHelper.clamp(state.limbSwingAmplitude, 0f, 1f);
        float move = MathHelper.clamp(amt * 1.3f, 0f, 1f);
        if (move < 0.01f) {
            // Покой: дыхание поверх ванильной стойки, сброс следов бега.
            float breath = MathHelper.sin(state.age * 0.09f);
            model.body.pitch += breath * 0.035f;
            model.rightArm.roll += breath * 0.04f;
            model.leftArm.roll += breath * 0.04f;
            model.getRootPart().pitch = 0f;
            model.getRootPart().originY = 0f;
            model.body.yaw = 0f;
            model.body.roll = 0f;
            return;
        }
        float ph = state.limbSwingAnimationProgress * 0.6662f;
        // Единый чистый цикл бега: руки машут противофазно ногам, корпус
        // слегка наклонён и покачивается, модель чуть подпрыгивает. Никаких
        // «отдельных» движений частей — всё связано в один цикл шага.
        float swing = 0.85f * move;
        model.rightArm.pitch = MathHelper.lerp(move, model.rightArm.pitch, -MathHelper.cos(ph) * swing);
        model.leftArm.pitch = MathHelper.lerp(move, model.leftArm.pitch, -MathHelper.cos(ph + (float) Math.PI) * swing);
        model.rightArm.yaw = MathHelper.lerp(move, model.rightArm.yaw, 0f);
        model.leftArm.yaw = MathHelper.lerp(move, model.leftArm.yaw, 0f);
        model.rightArm.roll = MathHelper.lerp(move, model.rightArm.roll, MathHelper.cos(ph + (float) Math.PI) * 0.12f * move);
        model.leftArm.roll = MathHelper.lerp(move, model.leftArm.roll, MathHelper.cos(ph) * 0.12f * move);
        float legSwing = 0.7f * move;
        model.rightLeg.pitch = MathHelper.lerp(move, model.rightLeg.pitch, MathHelper.cos(ph) * legSwing);
        model.leftLeg.pitch = MathHelper.lerp(move, model.leftLeg.pitch, MathHelper.cos(ph + (float) Math.PI) * legSwing);
        model.rightLeg.yaw = MathHelper.lerp(move, model.rightLeg.yaw, 0f);
        model.leftLeg.yaw = MathHelper.lerp(move, model.leftLeg.yaw, 0f);
        model.rightLeg.roll = MathHelper.lerp(move, model.rightLeg.roll, 0f);
        model.leftLeg.roll = MathHelper.lerp(move, model.leftLeg.roll, 0f);
        model.body.pitch = MathHelper.lerp(move, model.body.pitch, 0.3f * move);
        model.body.yaw = MathHelper.lerp(move, model.body.yaw, MathHelper.sin(ph * 2f) * 0.16f * move);
        model.body.roll = MathHelper.lerp(move, model.body.roll, 0f);
        model.getRootPart().pitch = 0.05f * move;
        model.getRootPart().originY = MathHelper.sin(ph * 2f) * 0.8f * move;
    }

    /** Наложить позу удара на модель игрока (вызывается из applyPlayerPose).
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
        // Сброс следов бега (наклон/подскок root от applyLocomotion).
        model.getRootPart().pitch = 0f;
        model.getRootPart().originY = 0f;
        float p = progress();
        Pose pose = computePose(p);
        // Плавный переход: первые 12% каждого удара смешиваются с предыдущей
        // позой (бегом или финалом прошлого удара) — никаких рывков на стыках.
        pose = blendTransition(pose, p);
        prevAppliedPose = pose;
        hasPrevAppliedPose = true;
        applyPoseToModel(model, pose);
        // Разворот: полный оборот делает root модели — торс, голова, руки,
        // ноги и клинок в руке поворачиваются как единое целое (никакого
        // «отдельного вращения тела»). 2π ≡ 0, поэтому на стыке с ударами
        // 2 и 4 рывка не видно. Вне разворота root = 0 (сброс stale-угла).
        model.getRootPart().yaw = comboStep == 2 ? spinTurn(p) : 0f;
    }

    /** Текущий прогресс удара 0..1 с интерполяцией кадров (как в applyPose). */
    public static float progress() {
        if (comboStep < 0) {
            return 0f;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        float tickDelta = client.getRenderTickCounter().getTickProgress(false);
        return Math.min(1f, (hitTicks + tickDelta) / SwordCombo.DURATION_TICKS[comboStep]);
    }

    /** Чистая поза клипа (без перехода) на текущем прогрессe. */
    private static Pose computePose(float p) {
        Pose pose = CLIPS[comboStep].at(p);
        if (recoveryTicks > 0) {
            // Плавный уход в нейтраль после последнего удара.
            float r = 1f - recoveryTicks / (float) RECOVERY_TICKS;
            pose = relax(pose, easeOutCubic(r));
        }
        return pose;
    }

    /** Смешать с предыдущей позой на первых 12% клипа (плавные стыки). */
    private static Pose blendTransition(Pose pose, float p) {
        if (hasPrevAppliedPose && p < TRANSITION_BLEND) {
            float w = MathHelper.clamp(p / TRANSITION_BLEND, 0f, 1f);
            return mix(prevAppliedPose, pose, ease(E_IN_OUT_SINE, w));
        }
        return pose;
    }

    /** Текущая итоговая поза (для трейла разреза). */
    private static Pose currentPose() {
        if (comboStep < 0) {
            return null;
        }
        float p = progress();
        return blendTransition(computePose(p), p);
    }

    /** Семплировать траекторию клинка в трейл разреза во время свинга.
     *  Вызывается каждый кадр рендера из FirstPersonBody. Вне свинга точки
     *  не добавляются и трейл тает за SLASH_TRAIL_AGE кадров. */
    public static void sampleCurrentSlashTrail(float bodyYawDeg, Vec3d playerPos) {
        float p = progress();
        if (comboStep < 0 || p < 0.30f || p > 0.95f) {
            return;
        }
        Pose pose = currentPose();
        if (pose == null) {
            return;
        }
        slashTrail.addLast(new SlashPoint(
                bladeTipWorld(pose, currentRootYawRad(), bodyYawDeg, playerPos), 0));
    }

    /** Состарить точки трейла и выкинуть слишком старые (плавное таяние). */
    public static void ageSlashTrail() {
        int size = slashTrail.size();
        for (int i = 0; i < size; i++) {
            SlashPoint pt = slashTrail.removeFirst();
            int age = pt.age() + 1;
            if (age <= SLASH_TRAIL_AGE) {
                slashTrail.addLast(new SlashPoint(pt.pos(), age));
            }
        }
    }

    /** Поворот root модели на текущем прогрессe (удар 3 — полный оборот). */
    public static float currentRootYawRad() {
        if (comboStep != 2) {
            return 0f;
        }
        return spinTurn(progress());
    }

    /** Клинок блокируется на время удара: клавиши движения не работают,
     *  герой двигается только микро-рывком по направлению атаки. */
    public static boolean lockInputDuringAttack() {
        return comboStep >= 0;
    }

    /** Точки трейла разреза (мировые координаты + возраст, для рендера дуги). */
    public static Deque<CombatController.SlashPoint> slashTrail() {
        return slashTrail;
    }

    /** Максимальный возраст точки трейла (для альфы в рендере). */
    public static int slashTrailAge() {
        return SLASH_TRAIL_AGE;
    }

    /** Траектория клинка в мировых координатах: повторяет геометрию ванильной
     *  PlayerEntityModel (rotationZYX(roll, yaw, pitch) + root.yaw + поворот
     *  тела 180-bodyYaw), проверена рендером scripts/anim_preview.py. */
    public static Vec3d bladeTipWorld(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos) {
        Matrix3f arm = new Matrix3f().rotationZYX(pose.rRoll(), pose.rYaw(), pose.rPitch());
        // Кисть: локальный +Y руки (вниз от плеча), 10 px; пивот правого плеча (-5, 2, 0).
        Vector3f hand = new Vector3f(0f, 10f, 0f);
        arm.transform(hand);
        hand.add(-5f, 2f, 0f);
        // Направление клинка — вдоль локального +Y руки.
        Vector3f dir = new Vector3f(0f, 1f, 0f);
        arm.transform(dir);
        // Root модели (разворот удара 3) + поворот тела (180 - bodyYaw), как в
        // LivingEntityRenderer.setupTransforms: Ry(180-bodyYaw) * Ry(rootYaw).
        float total = (float) Math.toRadians(180f - bodyYawDeg) + rootYawRad;
        Matrix3f root = new Matrix3f().rotationY(total);
        root.transform(hand);
        root.transform(dir);
        // Пиксели модели -> блоки (1/16), клинок ~10 px дальше кисти.
        return new Vec3d(
                playerPos.x + (hand.x() + dir.x() * 10f) / 16.0,
                playerPos.y + (hand.y() + dir.y() * 10f) / 16.0,
                playerPos.z + (hand.z() + dir.z() * 10f) / 16.0);
    }

    /** Очистить трейл разреза (когда self-body выключен или вышел из свинга). */
    public static void clearSlashTrail() {
        slashTrail.clear();
    }



    /** Прогресс полного оборота разворота 0..1: короткая пауза-замах, затем
     *  плавный разгон -> оборот -> стабилизация (E_IN_OUT_CUBIC). */
    private static float spinTurn(float p) {
        float u = MathHelper.clamp((p - 0.06f) / 0.60f, 0f, 1f);
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
    // скриптом scripts/gen_combo.py (вручную по ударам путешественника):
    // стыки — EASE_IN_OUT_SINE в хвосте удара, голова всегда смотрит строго
    // вперёд, левая рука — живой противовес, ноги переступают с выпадом,
    // разворот крутит root модели (см. spinTurn), финал 5-го уводит
    // клинок за спину.

    /** Кривые интерполяции сегмента: замах — E_IN_OUT_CUBIC, свинг — E_LINEAR,
     *  сопровождение — E_OUT_CUBIC, переходы — E_IN_OUT_SINE. */
    private static final int E_LINEAR = 0;
    private static final int E_IN_OUT_CUBIC = 1;
    private static final int E_OUT_CUBIC = 2;
    private static final int E_OUT_BACK = 3;
    private static final int E_IN_OUT_SINE = 4;
    private static final int E_IN_CUBIC = 5;

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

    /** Позы пяти ударов, вручную по описанию из Genshin (scripts/gen_combo.py):
     *  удар 1 — широкий горизонтальный слева направо; удар 2 — длинный
     *  апперкот справа снизу вверх влево; удар 3 — разворот через левое
     *  плечо на 360° с рубящим ударом по диагонали (оборот делает root
     *  модели, см. spinTurn); удар 4 — горизонтальный справа налево;
     *  удар 5 — очень широкий замах, удар справа налево и увод клинка
     *  за спину.
     *  Голова всегда 0 (вперёд). Левая рука — живой противовес (поднимается
     *  в замах, хлещет в противоположную сторону в пике), ноги переступают
     *  с выпадом в момент урона, корпус доворачивается (bYaw до ±52°) и
     *  наклоняется в удар (bPitch до +30°). Углы широкие (roll до ±155°,
     *  pitch до −178°/+60°), без полных оборотов; клипы непрерывны (t=0 =
     *  финал предыдущего удара). Порядок каналов Pose: прав. рука y/p/r,
     *  лев. рука y/p/r, корпус y/p/r, голова y/p/r, прав. нога y/p/r,
     *  лев. нога y/p/r. Углы в радианах, как у ModelPart. */
    // Удар 1: широкий горизонтальный слева направо
    private static final Pose hit1_00 = new Pose(0.000000f, -1.221730f, -2.007129f, -0.174533f, -1.396263f, 0.610865f, 0.488692f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 0.872665f, 0.000000f);
    private static final Pose hit1_01 = new Pose(0.000000f, -1.745329f, -1.396263f, -0.209440f, -0.959931f, 0.139626f, 0.244346f, 0.279253f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.087266f, 0.000000f, 0.000000f, 0.436332f, 0.000000f);
    private static final Pose hit1_02 = new Pose(0.000000f, -1.832596f, -0.261799f, 0.383972f, -0.174533f, -0.523599f, -0.244346f, 0.488692f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 1.134464f, 0.000000f, 0.000000f, -0.349066f, 0.000000f);
    private static final Pose hit1_03 = new Pose(0.000000f, -2.007129f, 0.959931f, 0.436332f, -0.785398f, -0.959931f, -0.558505f, 0.314159f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.698132f, 0.000000f, 0.000000f, 0.349066f, 0.000000f);
    private static final Pose hit1_04 = new Pose(0.000000f, -2.268928f, 2.007129f, 0.087266f, -0.523599f, -0.261799f, -0.733038f, 0.104720f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174533f, 0.000000f, 0.000000f, 0.698132f, 0.000000f);
    private static final Pose hit1_05 = new Pose(0.000000f, -2.705260f, 2.443461f, -0.087266f, -0.436332f, 0.261799f, -0.558505f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit2_00 = new Pose(0.000000f, -2.705260f, 2.443461f, -0.087266f, -0.436332f, 0.261799f, -0.558505f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_01 = new Pose(0.000000f, -0.261799f, 2.530727f, 0.261799f, -1.658063f, 0.436332f, 0.610865f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 0.872665f, 0.000000f);
    private static final Pose hit2_02 = new Pose(0.000000f, 0.698132f, 2.007129f, -0.174533f, -1.134464f, -0.087266f, 0.209440f, 0.087266f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.785398f, 0.000000f, 0.000000f, 0.261799f, 0.000000f);
    private static final Pose hit2_03 = new Pose(0.000000f, -1.745329f, 0.785398f, -0.349066f, -0.436332f, -0.610865f, -0.383972f, -0.209440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.610865f, 0.000000f, 0.000000f, -0.174533f, 0.000000f);
    private static final Pose hit2_04 = new Pose(0.000000f, -2.879793f, -1.047198f, -0.261799f, 0.174533f, -0.785398f, -0.698132f, -0.279253f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_05 = new Pose(0.000000f, -3.001966f, -1.570796f, -0.174533f, 0.000000f, -0.349066f, -0.610865f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit3_00 = new Pose(0.000000f, -3.001966f, -1.570796f, -0.174533f, 0.000000f, -0.349066f, -0.610865f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_01 = new Pose(0.000000f, -3.106686f, -0.523599f, -0.087266f, -3.054326f, -0.174533f, 0.000000f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.610865f, 0.000000f, 0.000000f, 0.610865f, 0.000000f);
    private static final Pose hit3_02 = new Pose(0.000000f, -2.094395f, 0.785398f, -0.261799f, -2.181662f, -0.785398f, 0.383972f, 0.314159f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.872665f, 0.000000f, 0.000000f, -0.610865f, 0.000000f);
    private static final Pose hit3_03 = new Pose(0.000000f, -1.134464f, 1.745329f, -0.349066f, -0.959931f, -1.047198f, 0.698132f, 0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, 0.174533f, 0.000000f);
    private static final Pose hit3_04 = new Pose(0.000000f, -0.872665f, 2.530727f, -0.174533f, -0.610865f, -0.523599f, 0.523599f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.610865f, 0.000000f);
    private static final Pose hit3_05 = new Pose(0.000000f, -1.047198f, 2.617994f, 0.000000f, -0.349066f, 0.174533f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit4_00 = new Pose(0.000000f, -1.047198f, 2.617994f, 0.000000f, -0.349066f, 0.174533f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(0.000000f, -1.396263f, 2.705260f, 0.209440f, -1.483530f, -0.610865f, 0.663225f, 0.209440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.959931f, 0.000000f, 0.000000f, -0.436332f, 0.000000f);
    private static final Pose hit4_02 = new Pose(0.000000f, -1.919862f, 0.872665f, -0.383972f, -0.087266f, 0.523599f, 0.174533f, 0.523599f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 1.134464f, 0.000000f);
    private static final Pose hit4_03 = new Pose(0.000000f, -2.094395f, -0.872665f, -0.436332f, -0.785398f, 0.872665f, -0.436332f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.698132f, 0.000000f);
    private static final Pose hit4_04 = new Pose(0.000000f, -2.356194f, -1.919862f, -0.174533f, -0.436332f, 0.349066f, -0.663225f, 0.104720f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.698132f, 0.000000f, 0.000000f, 0.174533f, 0.000000f);
    private static final Pose hit4_05 = new Pose(0.000000f, -2.530727f, -2.268928f, -0.087266f, -0.523599f, -0.261799f, -0.558505f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit5_00 = new Pose(0.000000f, -2.530727f, -2.268928f, -0.087266f, -0.523599f, -0.261799f, -0.558505f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(0.000000f, -1.221730f, 1.919862f, 0.436332f, -2.356194f, 0.872665f, 0.733038f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.523599f, 0.000000f, 0.000000f, 0.959931f, 0.000000f);
    private static final Pose hit5_02 = new Pose(-0.610865f, -2.932153f, 2.617994f, 0.523599f, -2.094395f, 0.436332f, 0.907571f, 0.209440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.349066f, 0.000000f, 0.000000f, 1.221730f, 0.000000f);
    private static final Pose hit5_03 = new Pose(-0.523599f, -2.007129f, 0.610865f, -0.436332f, -0.174533f, 0.610865f, 0.209440f, 0.523599f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 1.308997f, 0.000000f, 0.000000f, 0.436332f, 0.000000f);
    private static final Pose hit5_04 = new Pose(-0.698132f, -1.832596f, -1.047198f, -0.523599f, -0.959931f, 0.959931f, -0.488692f, 0.314159f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.785398f, 0.000000f, 0.000000f, -0.261799f, 0.000000f);
    private static final Pose hit5_05 = new Pose(-2.007129f, 0.959931f, 0.872665f, -0.174533f, -0.174533f, -0.349066f, -0.610865f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_06 = new Pose(-2.094395f, 1.047198f, 0.959931f, -0.087266f, -0.261799f, -0.436332f, -0.558505f, -0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // hit1
                new Keyframe(0.000f, 4, hit1_00),
                new Keyframe(0.140f, 1, hit1_01),
                new Keyframe(0.340f, 5, hit1_02),
                new Keyframe(0.560f, 2, hit1_03),
                new Keyframe(0.780f, 2, hit1_04),
                new Keyframe(1.000f, 0, hit1_05),
        }),
        new Clip(new Keyframe[] { // hit2
                new Keyframe(0.000f, 4, hit2_00),
                new Keyframe(0.140f, 1, hit2_01),
                new Keyframe(0.340f, 5, hit2_02),
                new Keyframe(0.560f, 2, hit2_03),
                new Keyframe(0.780f, 2, hit2_04),
                new Keyframe(1.000f, 0, hit2_05),
        }),
        new Clip(new Keyframe[] { // hit3
                new Keyframe(0.000f, 4, hit3_00),
                new Keyframe(0.140f, 1, hit3_01),
                new Keyframe(0.340f, 5, hit3_02),
                new Keyframe(0.560f, 2, hit3_03),
                new Keyframe(0.780f, 2, hit3_04),
                new Keyframe(1.000f, 0, hit3_05),
        }),
        new Clip(new Keyframe[] { // hit4
                new Keyframe(0.000f, 4, hit4_00),
                new Keyframe(0.140f, 1, hit4_01),
                new Keyframe(0.340f, 5, hit4_02),
                new Keyframe(0.560f, 2, hit4_03),
                new Keyframe(0.780f, 2, hit4_04),
                new Keyframe(1.000f, 0, hit4_05),
        }),
        new Clip(new Keyframe[] { // hit5
                new Keyframe(0.000f, 4, hit5_00),
                new Keyframe(0.120f, 1, hit5_01),
                new Keyframe(0.260f, 1, hit5_02),
                new Keyframe(0.420f, 5, hit5_03),
                new Keyframe(0.600f, 2, hit5_04),
                new Keyframe(0.780f, 2, hit5_05),
                new Keyframe(1.000f, 0, hit5_06),
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
        if (!Float.isNaN(pose.rlYaw())) {
            m.rightLeg.yaw = pose.rlYaw();
        }
        if (!Float.isNaN(pose.rlPitch())) {
            m.rightLeg.pitch = pose.rlPitch();
        }
        if (!Float.isNaN(pose.rlRoll())) {
            m.rightLeg.roll = pose.rlRoll();
        }
        if (!Float.isNaN(pose.llYaw())) {
            m.leftLeg.yaw = pose.llYaw();
        }
        if (!Float.isNaN(pose.llPitch())) {
            m.leftLeg.pitch = pose.llPitch();
        }
        if (!Float.isNaN(pose.llRoll())) {
            m.leftLeg.roll = pose.llRoll();
        }
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
            case E_IN_CUBIC -> {
                // Тяжёлый свинг: разгон от замаха к пику скорости (момент урона).
                return t * t * t;
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
