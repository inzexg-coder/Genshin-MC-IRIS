package net.teyvat.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.teyvat.client.paimon.PaimonEntity;
import net.teyvat.combat.SwordCombo;
import net.teyvat.network.PlayerAttackPayload;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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
 * после любой позы — не следит за камерой); удары МГНОВЕННЫЕ — замах
 * сжат в первые ~10-12% клипа (очень быстрый размах), пик скорости
 * (момент урона) на 1-2 тике, остаток клипа — широкое сопровождение;
 * удары МАКСИМАЛЬНО ШИРОКИЕ — клинок
 * выписывает широкие дуги (клинок уводится далеко в замах и далеко в
 * сопровождение); КОРПУС НЕ ОТРЫВАЕТСЯ ОТ НОГ — углы корпуса малые
 * (bYaw ±12°, bPitch ≤8°, у тела ванильный пивот на уровне шеи), наклон
 * в удар делает root целиком (root.pitch до 10°) с выпадом ног; левая
 * рука — естественный противовес без резких смен направления; ноги
 * переступают с выпадом в момент урона. Каждый клип непрерывен
 * (t=0 = финал предыдущего удара); разворот на 360° делает root ванильной
 * модели (getRootPart().yaw): торс, голова, руки и клинок поворачиваются
 * как единое целое. Полный круг ≈ 3.5 с, серия идёт по КЛИКАМ: быстрый
 * тап — МГНОВЕННЫЙ удар комбо; если ЛКМ не отпускать, после удара начинается
 * ЗАРЯД — накопление 3 секунды (FULL_CHARGE_TICKS): отпускание в любой
 * момент — спин на 360° с уроном по уровню заряда, не отпускать — через
 * 3 сек автозапуск максимальной (как у путешественника в начале Genshin:
 * хит по полному кругу вокруг тела, «орбиты атомов», полупрозрачная сфера
 * из ударов лезвием, тратит стамину, после неё серия сброшена).
 * Пока с последнего клика прошло ≤ ~1 с, комбо продолжается со следующего
 * удара (после пятого — снова первый); пауза дольше секунды сбрасывает
 * серию (следующий клик — первый удар). После пятого удара — обязательная
 * пауза ~1 сек (клики глотаются).
 * Клинок вырисовывает разрез: во время свинга
 * рисуется светящаяся дуга-полумесяц (трейл по траектории клинка +
 * усиленные частицы). Между анимациями — плавные переходы (первые ~5%
 * каждого удара смешиваются с предыдущей позой через prevAppliedPose).
 * Во время ударов движение клавишами блокируется, герой двигается только
 * микро-рывком по направлению атаки. В первом лице отрисовывается
 * собственное тело (FirstPersonBody): видны все анимации персонажа,
 * «глазами модельки», голова скрыта (см. PlayerEntityModelMixin). После
 * тапа — короткая фаза восстановления в нейтраль. Вне комбо — чистый
 * цикл бега: руки машут противофазно ногам, корпус наклонён и покачивается,
 * лёгкий подскок; в покое — АФК-покачивание (перекат с ноги на ногу,
 * мягкие волны корпуса и рук). После пятого удара — пауза ~1 секунда:
 * клики ЛКМ в неё глотаются, новое комбо начинается только с первого удара.
 */
public final class CombatController {
    /** Текущий удар комбо: 0..HIT_COUNT-1, -1 = атака не идёт. */
    private static int comboStep = -1;
    /** Тики с начала текущего удара. */
    private static int hitTicks;
    /** Счётчик клиентских тиков (для окна комбо от последнего клика). */
    private static int tickCount;
    /** Тик последнего обработанного клика ЛКМ (-1 = кликов ещё не было). */
    private static int lastClickTick = -1;
    /** Последний сыгранный удар (для продолжения серии в окне от последнего клика). */
    private static int lastStep = -1;
    /** Клик уже зацепил следующий удар: серия не обрывается. */
    private static boolean bufferedNext;
    /** Урон по текущему удару отправлен серверу (один раз за удар). */
    private static boolean sentHit;
    /** Кик от удара 0..1: пульс на момент разреза, затухает за ~0.5 сек.
     *  Двигает FOV и наклон камеры (как отдача в Genshin). */
    private static float impactKick;
    /** Тики фазы восстановления после одиночного удара (поза тает в боевую стойку). */
    private static int recoveryTicks;
    /** Тики плавного выхода из комбо в стойку бега/покоя (после recovery). */
    private static int exitBlendTicks;
    /** Тики паузы после 5-го удара: пока > 0, клики ЛКМ глотаются. */
    private static int finalCooldownTicks;
    /** Тики хит-стопа (hitlag) при ПОПАДАНИИ: поза и тайминги комбо замерли
     *  на пару кадров, ввод движения заблокирован. Промах хит-стопа не даёт. */
    private static int hitlagTicks;
    /** Заряженная атака (удержание ЛКМ): идёт ли заряд сейчас. */
    private static boolean charging;
    /** Тики с начала заряда (0..FULL_CHARGE_TICKS): урон и индикатор. */
    private static int chargeTicks;
    /** Уровень заряда выстрелившего спина 0..1 (для вихря и серверного урона). */
    private static float chargeLevel;
    /** Тик последнего нажатия ЛКМ, вооружившего удержание (-1 = нет).
     *  Если ЛКМ не отпустить в течение CHARGE_START_TICKS, начнётся заряд. */
    private static int holdStartTick = -1;

    /** Тиков удержания ЛКМ после удара, после которых начинается заряд
     *  (~0.5 сек). Быстрый тап (отпустили раньше) — обычный удар комбо. */
    private static final int CHARGE_START_TICKS = 6;

    /** Тиков восстановления после одиночного удара: поза плавно уходит в
     *  боевую стойку READY_POSE (клинок остаётся горизонтальным — никакого
     *  «дефолтного майнкрафт» с вертикальным мечом). */
    private static final int RECOVERY_TICKS = 6;
    /** Тиков смешивания последней позы удара с локомоцией (бег/шаг/АФК). */
    private static final int EXIT_BLEND_TICKS = 6;

    /** Последняя наложенная поза: из неё плавно «въезжаем» в следующий удар
     *  (первые ~12% клипа смешиваются с предыдущей позой — без рывков между
     *  анимациями). */
    private static Pose prevAppliedPose;
    private static boolean hasPrevAppliedPose;
    /** Последнее состояние root от локомоции (бег/шаг/АФК): подскок
     *  originY, наклон и покачивание yaw. На старте НОВОГО комбо плавно
     *  гасятся до нуля, чтобы «въезд» из бега/шага/покоя в первый удар
     *  был непрерывным (без щелчка по вертикали и поворота корпуса). */
    private static float lastLocoRootY;
    private static float lastLocoRootPitch;
    private static float lastLocoRootYaw;
    /** Последнее состояние root во время удара (для плавного выхода в локомоцию). */
    private static float lastCombatRootY;
    private static float lastCombatRootPitch;
    private static float lastCombatRootYaw;

    /** Доля клипа, на которой работает смешивание перехода между ударами.
     *  Маленькая, чтобы не съедать сжатый замах (мгновенный удар). */
    private static final float TRANSITION_BLEND = 0.05f;
    /** Старт НОВОГО комбо после паузы: более широкое окно смешивания с
     *  нейтралью, чтобы первый удар плавно «выезжал» из покоя, без рывка
     *  из стойки (используется, пока идёт удар 1 свежего комбо). */
    private static final float COLD_START_BLEND = 0.08f;
    /** Хват меча в беге: рука вытянута вперёд, клинок горизонтально.
     *  Углы ЗЕРКАЛЬНЫ по X (правая рука модели — на стороне −X). */
    private static final float RUN_ARM_PITCH = (float) Math.toRadians(-60f);
    private static final float RUN_ARM_YAW = (float) Math.toRadians(-6f);
    private static final float RUN_ARM_ROLL = (float) Math.toRadians(-8f);
    /** Боевая стойка в покое: клинок горизонтально слева (ready pose),
     *  зеркально по X. */
    private static final float READY_RYAW = (float) Math.toRadians(-22f);
    private static final float READY_RPITCH = (float) Math.toRadians(-46f);
    private static final float READY_RROLL = (float) Math.toRadians(-22f);
    private static final float READY_LPITCH = (float) Math.toRadians(-15f);
    private static final float READY_BYAW = (float) Math.toRadians(-8f);
    private static final float READY_BPITCH = (float) Math.toRadians(3f);

    /** Поворот лезвия в ЛОКАЛЬНОМ пространстве предмета (вокруг оси Y — оси
     *  клинка): разворачивается ТОЛЬКО плоскость лезвия (как кисть в запястье),
     *  направление клинка при этом НЕ меняется — лезвие всегда идёт ПО ПРЯМОЙ
     *  «плечо→кисть» (продолжение руки, см. BLADE_GRIP_C). «Физика меча»:
     *  в сжатом замахе лезвие слегка отстаёт от
     *  руки (trail), к моменту урона (~0.12) плоскость выравнивается,
     *  на хвосте перехлёстывает (whip) и мягко возвращается к финалу удара.
     *  Стыки между ударами совпадают (конец удара N = начало N+1); после
     *  комбо лезвие плавно уходит в нейтраль. Работает и в 1-м, и в 3-м лице
     *  (миксин HeldItemFeatureRenderer), трейл разреза и серпы SWEEP_ATTACK
     *  считают ту же цепочку. */
    private static final float[][] BLADE_DEG = {
            {0f, -24f, 0f, 20f, -6f, 0f},     // hit1: плоскость ровно на ударе
            {0f, 12f, 38f, 26f, 14f, 8f},     // hit2: апперкот — лёгкий разворот
            {0f, -18f, -38f, -16f, -4f, 0f},  // hit3: рубящий по диагонали
            {0f, 24f, 0f, -20f, 6f, 0f},      // hit4: зеркало hit1
            {0f, -30f, 0f, 26f, 8f, 0f},      // hit5: мощный свинг — ведущее ребро
            {0f, -18f, 0f, 24f, 6f, 0f},      // charged: спин — лезвие вбок, орбита
    };
    /** Моменты кадров BLADE_DEG (доли клипа): замах сжат, выравнивание
     *  лезвия и перехлёст (whip) сдвинуты к мгновенному удару. */
    private static final float[] BLADE_T = {0f, 0.05f, 0.12f, 0.22f, 0.60f, 1f};
    private static final float IDLE_BLADE_DEG = 0f;
    /** Последний угол лезвия в бою (для плавного возврата в нейтраль после комбо). */
    private static float lastBladeDeg = IDLE_BLADE_DEG;

    /** Грип-корректировка: доворот клинка в системе руки (вокруг точки хвата),
     *  чтобы лезвие было направлено ТОЧНО по прямой «плечо→кисть» — рука и меч
     *  выглядят одной прямой линией (~180°) при любом сгибе локтя. Без неё
     *  ванильная цепочка (HeldItemFeatureRenderer + display handheld) ведёт
     *  лезвие вдоль предплечья, и на ударах получается тупой угол (~152°)
     *  между рукой и мечом. Числа посчитаны в scripts/blade_geo.py
     *  (JOML-проверка) и совпадают с цепочкой трейла. */
    private static final Quaternionf BLADE_GRIP_C = new Quaternionf()
            .rotationAxis((float) 0.100668672, -0.882085226f, 0f, 0.471089858f);

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
        tickCount++;
        if (comboStep >= 0 && !client.player.isOnGround()) {
            // В воздухе удары не работают (как в Genshin): прыжок прерывает комбо,
            // следующий клик на земле начнёт серию заново.
            comboStep = -1;
            hitTicks = 0;
            recoveryTicks = 0;
            bufferedNext = false;
            sentHit = false;
            lastStep = -1;
            lastClickTick = -1;
            finalCooldownTicks = 0;
            exitBlendTicks = 0;
        }
        // --- Заряженная атака: тап — мгновенный удар комбо; если ЛКМ не
        // отпускать, после удара начинается накопление (3 сек). Отпускание
        // в любой момент — спин с уроном по уровню заряда; не отпускать —
        // через 3 сек автозапуск максимальной. ---
        boolean lmbHeld = client.options.attackKey.isPressed();
        if (charging && !client.player.isOnGround()) {
            // Прыжок (или обрыв) прерывает заряд.
            charging = false;
            chargeTicks = 0;
            holdStartTick = -1;
        }
        if (charging) {
            chargeTicks++;
            spawnChargeParticles(client, client.player);
            // Компактный вихрь со 2-й секунды: визуал + дамажит врагов.
            if (chargeTicks >= CHARGE_START_TICKS) {
                chargeWindupTicks++;
                spawnChargeWhirlwind(client, client.player);
            }
            if (!lmbHeld) {
                // Отпустили: спин с текущим уровнем заряда (ранний отпуск = слабее).
                fireCharged();
            } else if (chargeTicks >= SwordCombo.FULL_CHARGE_TICKS) {
                // 3 секунды заряда — автозапуск максимальной.
                fireCharged();
            }
        } else if (lmbHeld && holdStartTick >= 0
                && tickCount - holdStartTick >= CHARGE_START_TICKS
                && client.player.isOnGround()) {
            // ЛКМ держим после удара: начинаем накопление.
            startCharging(client);
        }
        if (!lmbHeld) {
            holdStartTick = -1;
        }
        if (hitlagTicks > 0) {
            // Хит-стоп: всё замерло — поза удара, тайминги комбо и пауза после
            // 5-го удара. Отдача камеры держится на месте и гаснет после.
            hitlagTicks--;
            return;
        }
        if (finalCooldownTicks > 0) {
            finalCooldownTicks--;
        }
        if (comboStep >= 0) {
            if (comboStep == SwordCombo.CHARGE_INDEX) {
                // Заряженный спин: клинок ведёт орбиту вокруг тела, урон
                // на тике DAMAGE_TICKS по полному кругу (сервер, «орбиты
                // атомов»). После спина серия сброшена.
                if (recoveryTicks > 0) {
                    recoveryTicks--;
                    if (recoveryTicks == 0) {
                        comboStep = -1;
                        exitBlendTicks = EXIT_BLEND_TICKS;
                    }
                } else {
                    hitTicks++;
                    if (hitTicks == 1) {
                        applyStep(client.player);
                    }
                    // Вихрь: серпы и граница шара — «удары лезвием в секунду».
                    spawnWhirlwindEffects(client, client.player);
                    if (hitTicks == SwordCombo.DAMAGE_TICKS[SwordCombo.CHARGE_INDEX] && !sentHit) {
                        sentHit = true;
                        sendHit(SwordCombo.CHARGE_INDEX, chargeLevel);
                        CinematicShots.onDamageTick();
                        spawnSlashEffects(client, client.player);
                        applyLunge(client.player);
                    }
                    if (hitTicks >= SwordCombo.DURATION_TICKS[SwordCombo.CHARGE_INDEX]) {
                        lastStep = -1;
                        lastClickTick = -1;
                        recoveryTicks = RECOVERY_TICKS;
                        sentHit = false;
                        bufferedNext = false;
                    }
                }
                impactKick *= 0.84f;
                return;
            }
            if (recoveryTicks > 0) {
                // Восстановление после одиночного удара: поза тает в нейтраль,
                // чтобы между тапами не было отскока в ванильную стойку.
                recoveryTicks--;
                if (recoveryTicks == 0) {
                    comboStep = -1;
                    exitBlendTicks = EXIT_BLEND_TICKS;
                }
            } else {
                hitTicks++;
                // Кинокамера-орбита (/cinema orbit): герой доворачивается к ближайшему
                // врагу, чтобы каждый удар шёл в его сторону — для съёмки боя со стороны.
                // Во время spin turn (hit3, charged) аим отключён — иначе дёргания.
                if (comboStep != 2 && comboStep != SwordCombo.CHARGE_INDEX) {
                    faceNearestEnemyDuringCinema(client);
                }
                if (hitTicks == 1) {
                    // Шаг вперёд с началом удара (часть LUNGE_STRENGTH): серия
                    // продвигает героя с каждым ударом, как в Genshin.
                    applyStep(client.player);
                }
                if (hitTicks == SwordCombo.DAMAGE_TICKS[comboStep] && !sentHit) {
                    sentHit = true;
                    sendHit(comboStep, 0f);
                    // Автоскриншоты ударов (/cinema shots): следующий кадр — кинокамера сбоку.
                    CinematicShots.onDamageTick();
                    // Свинг: разрез-дуга и свист всегда; толчок на тике урона
                    // (остаток LUNGE_STRENGTH после шага в начале удара).
                    spawnSlashEffects(client, client.player);
                    applyLunge(client.player);
                }
                if (hitTicks >= SwordCombo.DURATION_TICKS[comboStep]) {
                    if (comboStep == SwordCombo.HIT_COUNT - 1) {
                        // После пятого удара — обязательная пауза ~1 сек: клики
                        // в неё глотаются (onAttackClick), серия сбрасывается —
                        // новое комбо начнётся только с первого удара. Поза
                        // тает в нейтраль через recoveryTicks (comboStep ещё
                        // указывает на 5-й удар), затем уходит в -1, а
                        // finalCooldownTicks докручивает оставшуюся паузу.
                        lastStep = -1;
                        lastClickTick = -1;
                        recoveryTicks = RECOVERY_TICKS;
                        finalCooldownTicks = SwordCombo.FINAL_COOLDOWN_TICKS;
                        bufferedNext = false;
                        sentHit = false;
                    } else if (bufferedNext) {
                        // Серия продолжается по КЛИКУ: следующий удар.
                        // lastStep запоминаем и здесь: после цепочки окно продолжения
                        // по паузе тоже должно знать последний сыгранный удар.
                        lastStep = comboStep;
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
                }
            }
        } else if (lastStep >= 0 && tickCount - lastClickTick > SwordCombo.RESET_TICKS) {
            // С последнего клика прошло больше ~1 с: серия сброшена,
            // следующий клик начнёт комбо с первого удара.
            lastStep = -1;
            lastClickTick = -1;
        }
        // Отдача затухает сама: пульс виден пару кадров, затем камера успокаивается.
        impactKick *= 0.84f;
    }

    /** ЛКМ нажат (вместо ванильной атаки). Тап — МГНОВЕННЫЙ удар комбо;
     *  если ЛКМ не отпускать ~0.2 с, после удара начнётся накопление заряда
     *  (3 сек), отпускание — спин с уроном по уровню заряда, как в Genshin.
     *  Возвращает true, если клик съеден (ванильная атака подавлена). */
    public static boolean onAttackPress() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return false;
        }
        if (charging) {
            // Уже заряжаем: повторное нажатие ничего не делает.
            return true;
        }
        if (onAttackClick()) {
            // Удар (или буфер следующего) принят — вооружаем удержание:
            // если кнопку не отпустить, через CHARGE_START_TICKS начнётся заряд.
            holdStartTick = tickCount;
        }
        return true;
    }

    /** Обычный удар комбо (мгновенно, на сам клик). Возвращает true, если
     *  удар/буфер принят; false — клик проглочен (воздух, хит-стоп, пауза). */
    public static boolean onAttackClick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.isPaused()) {
            return false;
        }
        if (!client.player.isOnGround()) {
            // В воздухе обычные атаки не работают (только приземление), клик съедается.
            return false;
        }
        if (hitlagTicks > 0) {
            // Хит-стоп: клики глотаются, следующий удар цепляется после замирания.
            return false;
        }
        if (finalCooldownTicks > 0) {
            // Пауза после пятого удара: клик съедается, комбо не начинается.
            return false;
        }
        // Окно комбо — от последнего клика: пока прошло ≤ ~1 с, серия
        // продолжается со следующего удара (после пятого — снова первый);
        // пауза дольше секунды сбрасывает комбо (клик начнёт с первого удара).
        boolean windowOpen = lastClickTick < 0
                || tickCount - lastClickTick <= SwordCombo.RESET_TICKS;
        lastClickTick = tickCount;
        if (comboStep < 0) {
            if (windowOpen && lastStep >= 0) {
                comboStep = (lastStep + 1) % SwordCombo.HIT_COUNT;
            } else {
                comboStep = 0;
            }
            hitTicks = 0;
            bufferedNext = false;
            sentHit = false;
            exitBlendTicks = 0;
        } else if (recoveryTicks > 0) {
            // Клик в фазе восстановления: продолжаем серию, если окно открыто.
            recoveryTicks = 0;
            if (windowOpen) {
                comboStep = (comboStep + 1) % SwordCombo.HIT_COUNT;
            } else {
                comboStep = 0;
            }
            hitTicks = 0;
            bufferedNext = false;
            sentHit = false;
            exitBlendTicks = 0;
        } else {
            // Клик во время удара: следующий удар цепляется сразу после его конца.
            bufferedNext = true;
        }
        return true;
    }

    /** Начать заряд: сброс атаки/комбо, поза заряда, индикатор на дуге стамины. */
    private static void startCharging(MinecraftClient client) {
        if (client.player == null || !client.player.isOnGround()) {
            return;
        }
        cancelAttack();
        charging = true;
        chargeTicks = 0;
        chargeWindupTicks = 0;
        exitBlendTicks = 0;
        sentHit = false;
        // Серия сбрасывается: после заряженного удара обычный клик начнёт
        // комбо с первого удара (как в Genshin).
        lastStep = -1;
        lastClickTick = -1;
        client.world.playSound(null, client.player.getX(), client.player.getY(), client.player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.3f, 1.6f,
                client.world.random.nextLong());
    }

    /** Отпускание/автозапуск заряда: спин с текущим уровнем. */
    private static void fireCharged() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        charging = false;
        int ready = chargeTicks;
        chargeTicks = 0;
        holdStartTick = -1;
        if (client.player.isOnGround()) {
            float level = Math.min(1f, ready / (float) SwordCombo.FULL_CHARGE_TICKS);
            startChargedAttack(client, level);
        }
    }

    /** Выстрел заряженного спина: спин на 360° с хитом по кругу (сервер сам
     *  бьёт всех вокруг), трата стамины. level — уровень заряда 0..1: от него
     *  зависит урон и размер вихря. */
    private static void startChargedAttack(MinecraftClient client, float level) {
        if (client.player == null || client.world == null || !client.player.isOnGround()) {
            return;
        }
        if (!StaminaController.trySpendCharge()) {
            // Стамины не хватило — заряд отменяется, спин не выстрелит.
            return;
        }
        chargeLevel = level;
        comboStep = SwordCombo.CHARGE_INDEX;
        hitTicks = 0;
        sentHit = false;
        bufferedNext = false;
        recoveryTicks = 0;
        finalCooldownTicks = 0;
        exitBlendTicks = 0;
        lastStep = -1;
        lastClickTick = -1;
        client.world.playSound(null, client.player.getX(), client.player.getY(), client.player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.4f + 0.5f * level, 0.7f,
                client.world.random.nextLong());
        // Вспышка запуска: кольцо-волна по земле.
        if (client.world != null) {
            for (int i = 0; i < 18; i++) {
                double a = i / 18.0 * Math.PI * 2.0;
                double r = 2.0 + 3.5 * level;
                client.world.addParticleClient(ParticleTypes.END_ROD,
                        client.player.getX() + Math.cos(a) * r,
                        client.player.getY() + 0.15,
                        client.player.getZ() + Math.sin(a) * r,
                        Math.cos(a) * 1.4, 0.4, Math.sin(a) * 1.4);
            }
        }
    }

    /** Частицы заряда (3 секунды накопления): спираль-«магнит» сходится
     *  к клинку, радиус и плотность растут, на полном заряде — вспышка. */
    private static void spawnChargeParticles(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        float level = chargeProgress();
        int step = chargeTicks % 2 == 0 ? 1 : 2;
        double baseR = 1.2 + 3.2 * level;
        // Спираль искр, закрученная вокруг героя (радиус растёт с зарядом).
        for (int i = 0; i < 6 / step; i++) {
            double a = client.world.random.nextDouble() * Math.PI * 2.0;
            double r = baseR * (0.5 + client.world.random.nextDouble() * 0.7);
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() + Math.cos(a) * r,
                    player.getY() + 0.5 + client.world.random.nextDouble() * 1.6,
                    player.getZ() + Math.sin(a) * r,
                    -Math.cos(a) * 0.35, 0.3, -Math.sin(a) * 0.35);
        }
        // Кольцо на земле, расширяющееся с зарядом.
        if (chargeTicks % 4 == 0) {
            int n = 8 + (int) (level * 16);
            for (int i = 0; i < n; i++) {
                double a = i / (double) n * Math.PI * 2.0 + 0.4;
                client.world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * baseR,
                        player.getY() + 0.1,
                        player.getZ() + Math.sin(a) * baseR,
                        Math.cos(a) * 0.4, 0.35, Math.sin(a) * 0.4);
            }
        }
        // Свечение клинка (рука поднята вверх-вперёд).
        client.world.addParticleClient(ParticleTypes.END_ROD,
                player.getX(), player.getY() + 2.0, player.getZ(), 0, 0.2, 0);
        // Полный заряд: вспышка один раз.
        if (level >= 1f && chargeTicks == SwordCombo.FULL_CHARGE_TICKS) {
            for (int i = 0; i < 26; i++) {
                double a = i / 26.0 * Math.PI * 2.0;
                client.world.addParticleClient(ParticleTypes.CRIT,
                        player.getX() + Math.cos(a) * 4.6,
                        player.getY() + 0.4 + Math.sin(a * 2.0) * 1.4,
                        player.getZ() + Math.sin(a) * 4.6,
                        0, 0, 0);
            }
            client.world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.4f,
                    client.world.random.nextLong());
        }
    }

    /** Вихрь заряженного спина: серпы-удары по компактной сфере вокруг героя.
     *  Радиус уменьшен (1.5..2.5 вместо 3.0..5.6), плотность увеличена
     *  (больше частиц на тик) для визуально насыщенного вихря. */
    /** Заряженная атака: один ОГРОМНЫЙ косой удар серпом + искры. */
    private static void spawnWhirlwindEffects(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        // Один ОГРОМНЫЙ диагональный серп на 2-м тике удара.
        if (hitTicks == 2) {
            // Три ряда серпов: верхний, средний, нижний — эпичный широкий удар.
            for (int row = 0; row < 3; row++) {
                double rowOffsetX = forwardX * 1.5; // вперёд
                double rowOffsetZ = forwardZ * 1.5;
                double rowY = player.getY() + 2.2 - row * 0.6;
                double rowStartX = player.getX() + rightX * 1.5 - rowOffsetX * 0.3;
                double rowStartZ = player.getZ() + rightZ * 1.5 - rowOffsetZ * 0.3;
                for (int i = 0; i < 3; i++) {
                    float t = i / 2.0f;
                    double sx = rowStartX + forwardX * t * 8.0 - rightX * t * 1.5;
                    double sy = rowY - t * 0.8;
                    double sz = rowStartZ + forwardZ * t * 8.0 - rightZ * t * 1.5;
                    client.world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                            sx, sy, sz,
                            forwardX * 2.5, -0.6, forwardZ * 2.5);
                }
            }
            // Взрыв искр — плотное облако у меча.
            for (int i = 0; i < 30; i++) {
                double spread = 2.0;
                double a = client.world.random.nextDouble() * Math.PI * 2.0;
                double r = client.world.random.nextDouble() * spread;
                client.world.addParticleClient(ParticleTypes.CRIT,
                        player.getX() + rightX * 0.6 + Math.cos(a) * r + forwardX * client.world.random.nextDouble() * 2.0,
                        player.getY() + 1.2 + client.world.random.nextDouble() * 1.5,
                        player.getZ() + rightZ * 0.6 + Math.sin(a) * r + forwardZ * client.world.random.nextDouble() * 2.0,
                        forwardX * 0.8, 0.4, forwardZ * 0.8);
            }
            // Кольцо взрыва по земле.
            for (int i = 0; i < 20; i++) {
                double a = i / 20.0 * Math.PI * 2.0;
                client.world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * 3.0,
                        player.getY() + 0.2,
                        player.getZ() + Math.sin(a) * 3.0,
                        Math.cos(a) * 1.0, 0.6, Math.sin(a) * 1.0);
            }
        }
    }

    /** Cubic bezier: плавный разгон заряда (медленный старт, быстрый финал). */
    private static float cubicBezier(float t) {
        // P0(0,0) P1(0.25,0.1) P2(0.25,1.0) P3(1,1) — ultra smooth
        float cx = 3f * 0.25f;
        float bx = 3f * (0.25f - 0.25f) - cx;
        float ax = 1f - cx - bx;
        float cy = 3f * 0.1f;
        float by = 3f * (1.0f - 0.1f) - cy;
        float ay = 1f - cy - by;
        // Newton-Raphson for x -> t
        float tt = t;
        for (int i = 0; i < 8; i++) {
            float err = ((ax * tt + bx) * tt + cx) * tt - t;
            if (Math.abs(err) < 1e-6f) break;
            float dx = (3f * ax * tt + 2f * bx) * tt + cx;
            if (Math.abs(dx) < 1e-6f) break;
            tt -= err / dx;
        }
        tt = MathHelper.clamp(tt, 0f, 1f);
        return ((ay * tt + by) * tt + cy) * tt;
    }

    /** Тики вихря заряда (компактный, дамажит врагов со 2-й сек). */
    private static int chargeWindupTicks;
    /** Интервал тиков между дамажащими хитами во время заряда. */
    private static final int CHARGE_HIT_INTERVAL = 8;
    /** Радиус компактного вихря во время заряда (близко к телу). */
    private static final float CHARGE_WINDUP_RADIUS = 2.0f;

    /** Игрок держит ЛКМ достаточно долго для заряда (для стамины). */
    public static boolean isHoldingForCharge() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        return charging || (client.options.attackKey.isPressed()
                && holdStartTick >= 0
                && tickCount - holdStartTick >= CHARGE_START_TICKS);
    }

    /** Визуал заряда: МНОГО ярких искр у меча + энергия вокруг тела. Без серпов. */
    private static void spawnChargeWhirlwind(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        float level = chargeProgress();
        float yaw = (float) Math.toRadians(player.getYaw());
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        // Энергия у меча: густая спираль искр, стекающихся к клинку.
        double swordX = player.getX() + rightX * 0.8 + forwardX * 0.3;
        double swordY = player.getY() + 1.4;
        double swordZ = player.getZ() + rightZ * 0.8 + forwardZ * 0.3;
        int sparkCount = 6 + (int)(level * 6);
        for (int i = 0; i < sparkCount; i++) {
            double a = chargeWindupTicks * 1.0 + i * (Math.PI * 2.0 / sparkCount);
            double spiral = 0.2 + level * 0.6;
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    swordX + Math.cos(a) * spiral,
                    swordY + Math.sin(a * 2.0) * 0.4,
                    swordZ + Math.sin(a) * spiral,
                    -Math.cos(a) * 0.2, 0.2, -Math.sin(a) * 0.2);
        }
        // Густые CRIT искры вокруг тела — каждые тик.
        {
            int critCount = 4 + (int)(level * 5);
            for (int i = 0; i < critCount; i++) {
                double a = chargeWindupTicks * 0.4 + i * (Math.PI * 2.0 / critCount);
                double r = 0.4 + level * 1.0;
                client.world.addParticleClient(ParticleTypes.CRIT,
                        player.getX() + Math.cos(a) * r,
                        player.getY() + 0.7 + Math.sin(a * 1.5) * 0.6,
                        player.getZ() + Math.sin(a) * r,
                        -Math.cos(a) * 0.4, 0.25, -Math.sin(a) * 0.4);
            }
        }
        // Дополнительные END_ROД искры вокруг тела для насыщенности.
        if (chargeWindupTicks % 2 == 0) {
            for (int i = 0; i < 3; i++) {
                double a = chargeWindupTicks * 0.6 + i * (Math.PI * 2.0 / 3.0);
                double r = 0.8 + level * 1.2;
                client.world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * r,
                        player.getY() + 1.0 + client.world.random.nextDouble() * 0.8,
                        player.getZ() + Math.sin(a) * r,
                        -Math.cos(a) * 0.2, 0.3, -Math.sin(a) * 0.2);
            }
        }
    }

    /** Прогресс заряда 0..1 с cubic bezier (медленный старт, быстрый финал). */
    public static float chargeProgress() {
        if (!charging) {
            return 0f;
        }
        float linear = MathHelper.clamp(chargeTicks / (float) SwordCombo.FULL_CHARGE_TICKS, 0f, 1f);
        return cubicBezier(linear);
    }

    /** Радиус поиска ближайшего врага для доворота в кинокамере-орбите. */
    private static final double CINEMA_TARGET_RANGE = 12.0;
    /** Скорость доворота к врагу за тик (20 тиков/сек): видимый плавный поворот,
     *  к концу первого удара герой уже почти смотрит на цель. */
    private static final float CINEMA_TURN_RATE = 0.2f;

    /** В режиме кинокамеры-орбиты плавно доворачивает героя к ближайшему врагу.
     *  Вне орбиты и без врагов рядом ничего не делает — управление не трогается. */
    private static void faceNearestEnemyDuringCinema(MinecraftClient client) {
        if (CinematicCamera.mode() != CinematicCamera.Mode.ORBIT && !CinematicShots.isEnabled()) {
            return;
        }
        LivingEntity target = nearestEnemy(client);
        if (target == null || client.player == null) {
            return;
        }
        double dx = target.getX() - client.player.getX();
        double dz = target.getZ() - client.player.getZ();
        if (dx * dx + dz * dz < 1.0E-8) {
            return;
        }
        float desiredYaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        float smoothed = MathHelper.lerpAngleDegrees(CINEMA_TURN_RATE, client.player.getYaw(), desiredYaw);
        client.player.setYaw(smoothed);
        client.player.setBodyYaw(smoothed);
        client.player.setHeadYaw(smoothed);
    }

    /** Ближайший живой враг в радиусе CINEMA_TARGET_RANGE (без игроков, Паймон
     *  и мирных животных). Возвращает null, если таких рядом нет. */
    private static LivingEntity nearestEnemy(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return null;
        }
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        List<LivingEntity> mobs = world.getEntitiesByType(
                TypeFilter.instanceOf(LivingEntity.class),
                new Box(px - CINEMA_TARGET_RANGE, py - CINEMA_TARGET_RANGE, pz - CINEMA_TARGET_RANGE,
                        px + CINEMA_TARGET_RANGE, py + CINEMA_TARGET_RANGE, pz + CINEMA_TARGET_RANGE),
                e -> e != client.player && !e.isPlayer() && e.isAlive()
                        && e.getType() != PaimonEntity.TYPE
                        && !(e instanceof ArmorStandEntity)
                        && !(e instanceof PassiveEntity)
                        && !(e instanceof FishEntity));
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity mob : mobs) {
            double d = mob.squaredDistanceTo(client.player);
            if (d < best) {
                best = d;
                nearest = mob;
            }
        }
        return nearest;
    }

    /** Пакет урона серверу: сервер сам находит цели по хитбоксу удара.
     *  chargeLevel — уровень заряда спина (у обычных ударов 0). */
    private static void sendHit(int hitIndex, float chargeLevel) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            ClientPlayNetworking.send(new PlayerAttackPayload(hitIndex, chargeLevel));
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
        // Полумесяцы-разрезы: серпы рисуются ТОЧНО по траектории клинка (та же
        // цепочка, что у трейла — bladeTipWorld), поэтому дуга визуала удара
        // совпадает с направлением движения меча (с поворотом лезвия).
        boolean charged = comboStep == SwordCombo.CHARGE_INDEX;
        int crescents = charged ? 6 : 3;
        float rootYaw = currentRootYawRad();
        Vec3d slashPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        // Серп идёт по новой траектории: сжатый замах + удар (0.04) и
        // широкое сопровождение (до 0.55) — дуга совпадает с мечом.
        for (int i = 0; i < crescents; i++) {
            float t0 = 0.04f + 0.51f * (float) i / crescents;
            float t1 = 0.04f + 0.51f * (float) (i + 1) / crescents;
            Vec3d a = bladeTipWorld(computePose(t0), rootYaw, player.getBodyYaw(), slashPos);
            Vec3d b = bladeTipWorld(computePose(t1), rootYaw, player.getBodyYaw(), slashPos);
            Vec3d delta = b.subtract(a);
            double len = delta.length();
            if (len < 1.0e-4) {
                continue;
            }
            Vec3d dir = delta.multiply(1.0 / len);
            world.addParticleClient(ParticleTypes.SWEEP_ATTACK,
                    a.x, a.y, a.z,
                    dir.x, dir.y, dir.z);
        }
        // Дуга размаха: плотные штрихи-искры вдоль траектории меча.
        // У заряженного спина — полный круг (орбита вокруг тела).
        int count = 16;
        double radius = 2.4;
        double height = 1.0 + comboStep * 0.09;
        float span = charged ? 6.2f : 1.55f + comboStep * 0.12f;
        for (int i = 0; i < count; i++) {
            float t = (float) i / (count - 1);
            double ang = yaw + (t - 0.5f) * span;
            world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() - Math.sin(ang) * radius,
                    player.getY() + height,
                    player.getZ() + Math.cos(ang) * radius,
                    -Math.cos(ang) * 1.6, 0.0, -Math.sin(ang) * 1.6);
        }
        // Свист свинга (тихий, всегда): «клац» попадания — только на хите
        // (звук с сервера по AttackResultPayload).
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.5f, 1.0f,
                world.random.nextLong());
        // Кольцо «орбит» заряженного спина: искры по кругу вокруг героя.
        if (charged) {
            for (int i = 0; i < 24; i++) {
                double a = i / 24.0 * Math.PI * 2.0;
                world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * 3.0,
                        player.getY() + 0.2,
                        player.getZ() + Math.sin(a) * 3.0,
                        Math.cos(a) * 1.2, 0.1, Math.sin(a) * 1.2);
            }
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

    /** Шаг героя вперёд в начале удара: часть LUNGE_STRENGTH, чтобы серия
     *  продвигала героя с каждым ударом (как в Genshin), а не толчком в один
     *  момент. Клиентское движение, как у рывка. */
    private static void applyStep(ClientPlayerEntity player) {
        float strength = SwordCombo.LUNGE_STRENGTH[comboStep] * SwordCombo.START_STEP_FACTOR;
        if (strength <= 0f) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x - Math.sin(yaw) * strength, v.y, v.z + Math.cos(yaw) * strength);
        player.velocityModified = true;
    }

    /** Толчок героя вперёд на тике урона: остаток LUNGE_STRENGTH после шага
     *  в начале удара. На пятом ударе — мощный прокат. */
    private static void applyLunge(ClientPlayerEntity player) {
        float strength = SwordCombo.LUNGE_STRENGTH[comboStep] * (1f - SwordCombo.START_STEP_FACTOR);
        float up = SwordCombo.LUNGE_UP[comboStep];
        if (strength <= 0f && up <= 0f) {
            return;
        }
        float yaw = (float) Math.toRadians(player.getYaw());
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x - Math.sin(yaw) * strength, v.y + up, v.z + Math.cos(yaw) * strength);
        player.velocityModified = true;
    }

    /** Подтверждение попадания от сервера (AttackResultPayload): хит-стоп,
     *  отдача камеры и искры в точке контакта — строго на хите. Промах
     *  (пустой массив) ничего не делает: свинг остаётся визуальным. */
    public static void onHitConfirmed(MinecraftClient client, int[] entityIds) {
        if (entityIds == null || entityIds.length == 0) {
            return;
        }
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        if (comboStep >= 0) {
            hitlagTicks = Math.max(hitlagTicks, SwordCombo.HITLAG_TICKS[comboStep]);
        } else {
            hitlagTicks = Math.max(hitlagTicks, 3);
        }
        // Отдача камеры (FOV/наклон) — только при попадании.
        impactKick = 1.6f;
        // Искры — одна точка контакта (первая цель), количество не зависит от числа врагов.
        Entity firstTarget = world.getEntityById(entityIds[0]);
        if (firstTarget != null) {
            Vec3d p = firstTarget.getBoundingBox().getCenter();
            for (int i = 0; i < 10; i++) {
                double vx = (world.random.nextDouble() - 0.5) * 1.3;
                double vy = world.random.nextDouble() * 1.5 + 0.2;
                double vz = (world.random.nextDouble() - 0.5) * 1.3;
                world.addParticleClient(ParticleTypes.CRIT, p.x, p.y, p.z, vx, vy, vz);
            }
            world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, 0.5, 0);
        }
    }

    /** Атака идёт прямо сейчас (включая хит-стоп и фазу восстановления). */
    public static boolean isAttacking() {
        return comboStep >= 0 || finalCooldownTicks > 0;
    }

    /** Dash-cancel: рывок прерывает анимацию атаки, как в Genshin. Серия
     *  комбо при этом сохраняется (lastStep) — следующий клик продолжит цепочку. */
    public static boolean tryCancelByDash() {
        return cancelAttack();
    }

    /** Jump-cancel: прыжок прерывает атаку (как в Genshin). */
    public static boolean tryCancelByJump() {
        return cancelAttack();
    }

    /** Общая отмена атаки: сбрасываем удар в нейтраль с плавным выходом.
     *  Так же прерывает заряд (dash/jump-cancel заряженной атаки).
     *  Возвращает true, если что-то было отменено. */
    /**
     * Игрок получил урон — прерываем комбо (как в Genshin).
     */
    public static void onPlayerHurt() {
        // Super Armor: во время выпуска заряженной атаки не прерываемся
        if (comboStep == SwordCombo.CHARGE_INDEX) {
            return;
        }
        if (comboStep >= 0 || charging) {
            cancelAttack();
        }
    }

    private static boolean cancelAttack() {
        boolean wasActive = comboStep >= 0 || finalCooldownTicks > 0 || charging;
        charging = false;
        chargeTicks = 0;
        holdStartTick = -1;
        if (!wasActive) {
            return false;
        }
        comboStep = -1;
        hitTicks = 0;
        recoveryTicks = 0;
        exitBlendTicks = EXIT_BLEND_TICKS;
        bufferedNext = false;
        sentHit = false;
        finalCooldownTicks = 0;
        hitlagTicks = 0;
        return true;
    }

    /** Отдача удара 0..1: пульс FOV и наклона камеры на момент попадания. */
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

    /** Идёт ли сейчас комбо (для миксина предмета: поворот клинка нужен даже
     *  для кастомных мечей вне тега ItemTags.SWORDS — комбо работает с любым
     *  предметом в руке). */
    public static boolean comboActive() {
        return comboStep >= 0;
    }

    /** Единая точка входа из миксина модели: во время комбо — поза удара,
     *  вне комбо — эпичная ходьба/бег в стиле Origin Animation (широкие махи
     *  рук и ног, наклон и поворот корпуса, лёгкий подскок; в покое —
     *  дыхание). root.yaw сбрасывается вне разворота. */
    public static void applyPlayerPose(PlayerEntityModel model, PlayerEntityRenderState state) {
        if (!isLocalPlayer(state.id)) {
            return;
        }
        if (charging) {
            applyChargePose(model, state);
        } else if (comboStep >= 0) {
            applyPose(model, state.id);
        } else {
            model.getRootPart().yaw = 0f;
            applyLocomotion(model, state);
            if (exitBlendTicks > 0) {
                // Плавный выход из комбо: первые кадры после удара смешиваются
                // с последней позой удара (и root), без щелчка в стойку бега/покоя.
                Pose loco = poseFromModel(model);
                float w = 1f - exitBlendTicks / (float) EXIT_BLEND_TICKS;
                applyPoseToModel(model, mix(prevAppliedPose, loco, w));
                model.getRootPart().originY = MathHelper.lerp(w, lastCombatRootY, model.getRootPart().originY);
                model.getRootPart().pitch = MathHelper.lerp(w, lastCombatRootPitch, model.getRootPart().pitch);
                model.getRootPart().yaw = MathHelper.lerp(w, lastCombatRootYaw, model.getRootPart().yaw);
                exitBlendTicks--;
            }
            // Запоминаем позу бега и состояние root: из них первый удар
            // «въедет» без рывка (и части, и подскок/наклон корпуса).
            prevAppliedPose = poseFromModel(model);
            hasPrevAppliedPose = true;
            lastLocoRootY = model.getRootPart().originY;
            lastLocoRootPitch = model.getRootPart().pitch;
            lastLocoRootYaw = model.getRootPart().yaw;
        }
        // Голова ВСЕГДА смотрит строго вперёд (относительно корпуса): не
        // следит за камерой и не вертится во время ударов и бега.
        model.head.yaw = 0f;
        model.head.pitch = 0f;
        model.head.roll = 0f;
    }

    /** Поза заряда: клинок поднят вверх-вперёд обеими руками, готовность
     *  к спину; лёгкое покачивание и мягкий въезд из предыдущей позы.
     *  Голова не трогается (всегда смотрит вперёд). */
    private static void applyChargePose(PlayerEntityModel model, PlayerEntityRenderState state) {
        float t = state.age * 0.06f;
        float sway = MathHelper.sin(t);
        float breath = MathHelper.sin(state.age * 0.11f);
        float progress = chargeProgress();
        float w = MathHelper.clamp(chargeTicks / 6f, 0f, 1f);
        Pose base = hasPrevAppliedPose ? prevAppliedPose : CHARGE_POSE;
        Pose pose = mix(base, CHARGE_POSE, ease(E_IN_OUT_SINE, w));
        // Анимация зарядки: правая рука поднимается вверх по cubic bezier,
        // меч тянет за собой — нарастающее напряжение перед ударом.
        float armLift = cubicBezier(progress) * 0.8f;
        float armTilt = cubicBezier(progress) * 0.4f;
        float bodyLean = cubicBezier(progress) * 0.15f;
        pose = new Pose(
                pose.rYaw() - armTilt + sway * 0.01f,
                pose.rPitch() - armLift + breath * 0.012f,
                pose.rRoll() + sway * 0.008f,
                pose.lYaw() + armTilt * 0.5f,
                pose.lPitch() + armLift * 0.6f + sway * 0.008f,
                pose.lRoll(),
                pose.bYaw() + bodyLean + sway * 0.02f,
                pose.bPitch() - bodyLean * 0.5f + breath * 0.02f,
                pose.bRoll(),
                Float.NaN, Float.NaN, Float.NaN,
                pose.rlYaw() - bodyLean * 0.3f,
                pose.rlPitch() + sway * 0.006f,
                pose.rlRoll(),
                pose.llYaw() + bodyLean * 0.3f,
                pose.llPitch() - sway * 0.006f,
                pose.llRoll());
        applyPoseToModel(model, pose);
        model.getRootPart().yaw = sway * 0.03f;
        model.getRootPart().pitch = breath * 0.01f;
        model.getRootPart().originY = -cubicBezier(progress) * 0.1f;
        prevAppliedPose = poseFromModel(model);
        hasPrevAppliedPose = true;
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
            // АФК-покачивание вокруг БОЕВОЙ СТОЙКИ: клинок держим
            // горизонтально слева (не «дефолтный майнкрафт» с вертикальным
            // мечом), сверху — дыхание, лёгкий перекат с ноги на ногу и
            // покачивание корпуса. Голова не трогается (всегда смотрит вперёд).
            float t = state.age * 0.05f;
            float sway = MathHelper.sin(t);
            float sway2 = MathHelper.sin(t * 0.77f + 1.7f);
            float breath = MathHelper.sin(state.age * 0.09f);
            model.getRootPart().yaw = sway * 0.045f;
            model.getRootPart().pitch = 0.02f + sway2 * 0.02f;
            model.rightArm.yaw = READY_RYAW + sway * 0.01f;
            model.rightArm.pitch = READY_RPITCH + breath * 0.015f + sway2 * 0.012f;
            model.rightArm.roll = READY_RROLL + breath * 0.02f + sway2 * 0.015f;
            model.leftArm.yaw = 0f;
            model.leftArm.pitch = READY_LPITCH + sway2 * 0.02f;
            model.leftArm.roll = breath * 0.04f - sway2 * 0.03f;
            model.body.yaw = READY_BYAW - sway * 0.025f;
            model.body.roll = sway2 * 0.03f;
            model.body.pitch = READY_BPITCH + breath * 0.035f + MathHelper.cos(t) * 0.012f;
            model.rightLeg.roll = sway * 0.012f;
            model.leftLeg.roll = -sway * 0.012f;
            model.rightLeg.yaw = -sway2 * 0.01f;
            model.leftLeg.yaw = sway2 * 0.01f;
            model.getRootPart().originY = 0f;
            return;
        }
        float ph = state.limbSwingAnimationProgress * 0.6662f;
        // Единый чистый цикл бега: правая рука держит меч впереди (стойка с
        // клинком — почти фиксированный хват с лёгким покачиванием в такт
        // шагам), левая рука машет для баланса, корпус слегка наклонён и
        // покачивается, модель чуть подпрыгивает.
        float swing = 0.85f * move;
        model.rightArm.pitch = MathHelper.lerp(move, model.rightArm.pitch, RUN_ARM_PITCH + MathHelper.cos(ph) * 0.06f * move);
        model.leftArm.pitch = MathHelper.lerp(move, model.leftArm.pitch, -MathHelper.cos(ph + (float) Math.PI) * swing);
        model.rightArm.yaw = MathHelper.lerp(move, model.rightArm.yaw, RUN_ARM_YAW);
        model.leftArm.yaw = MathHelper.lerp(move, model.leftArm.yaw, 0f);
        model.rightArm.roll = MathHelper.lerp(move, model.rightArm.roll, RUN_ARM_ROLL + MathHelper.cos(ph + (float) Math.PI) * 0.06f * move);
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
        // Наклон root: всё тело (и клинок) ложится в удар — таз остаётся у бёдер,
        // ноги не отрываются (пивот root на уровне шеи, углы малые).
        // На старте НОВОГО комбо подскок/наклон/покачивание root от бега
        // гасятся плавно (то же окно, что и у смешивания поз) — из бега,
        // шага и АФК-покачивания удар «въезжает» без щелчка.
        float p = progress();
        float rootBlend = 1f;
        if (comboStep == 0) {
            float w = MathHelper.clamp(p / COLD_START_BLEND, 0f, 1f);
            rootBlend = ease(E_IN_OUT_SINE, w);
        }
        model.getRootPart().originY = MathHelper.lerp(rootBlend, lastLocoRootY, 0f);
        model.getRootPart().pitch = MathHelper.lerp(rootBlend, lastLocoRootPitch, rootLean(p));
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
        model.getRootPart().yaw = MathHelper.lerp(rootBlend, lastLocoRootYaw,
                comboStep == 2 ? spinTurn(p)
                        : comboStep == SwordCombo.CHARGE_INDEX ? chargedSpinTurn(p) : 0f);
        lastCombatRootY = model.getRootPart().originY;
        lastCombatRootPitch = model.getRootPart().pitch;
        // During spin turns (hit3, charged), don't update lastLocoRootYaw
        // — it would feed the spinning angle back into lerp, causing wild rotation.
        if (comboStep != 2 && comboStep != SwordCombo.CHARGE_INDEX) {
            lastCombatRootYaw = model.getRootPart().yaw;
        }
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
        if (hasPrevAppliedPose) {
            float win = comboStep == 0 ? COLD_START_BLEND : TRANSITION_BLEND;
            if (p < win) {
                float w = MathHelper.clamp(p / win, 0f, 1f);
                return mix(prevAppliedPose, pose, ease(E_IN_OUT_SINE, w));
            }
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
        if (comboStep < 0 || p < 0.05f || p > 0.90f) {
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

    /** Поворот root модели на текущем прогрессe: удар 3 и заряженный спин —
     *  полный оборот вокруг своей оси (клинок ведёт орбиту). */
    public static float currentRootYawRad() {
        if (comboStep == 2) {
            return spinTurn(progress());
        }
        if (comboStep == SwordCombo.CHARGE_INDEX) {
            return chargedSpinTurn(progress());
        }
        return 0f;
    }

    /** Текущий поворот лезвия в локальном пространстве предмета (item-local,
     *  вокруг оси Y — оси клинка; направление клинка не меняется). Угол
     *  берётся из кейфрейм-кривых BLADE_DEG — лезвие слегка отстаёт в замахе,
     *  выравнивается к моменту урона и перехлёстывает на хвосте; после комбо
     *  (восстановление/выход) плавно возвращается в нейтраль. */
    public static Quaternionf currentBladeRotation() {
        float deg = IDLE_BLADE_DEG;
        if (comboStep >= 0) {
            deg = sampleBladeDeg(comboStep, progress());
            if (recoveryTicks > 0) {
                // Восстановление после удара: лезвие возвращается в нейтраль.
                float r = 1f - recoveryTicks / (float) RECOVERY_TICKS;
                deg = MathHelper.lerp(easeOutCubic(r), deg, IDLE_BLADE_DEG);
            }
            lastBladeDeg = deg;
        } else if (exitBlendTicks > 0) {
            // Выход из комбо: доворот последнего угла лезвия к нейтрали.
            float w = 1f - exitBlendTicks / (float) EXIT_BLEND_TICKS;
            deg = MathHelper.lerp(w, lastBladeDeg, IDLE_BLADE_DEG);
        }
        return new Quaternionf().rotationY((float) Math.toRadians(deg));
    }

    /** Сэмплировать угол клинка удара (линейно между ключевыми кадрами —
     *  плавная последовательность «задержка -> разворот -> перехлёст ->
     *  фиксация», стык с предыдущим ударом совпадает по углу). */
    private static float sampleBladeDeg(int step, float p) {
        float[] kf = BLADE_DEG[step];
        if (p <= BLADE_T[0]) {
            return kf[0];
        }
        for (int i = 0; i < BLADE_T.length - 1; i++) {
            if (p <= BLADE_T[i + 1]) {
                float span = BLADE_T[i + 1] - BLADE_T[i];
                float u = span <= 0f ? 1f : (p - BLADE_T[i]) / span;
                return MathHelper.lerp(u, kf[i], kf[i + 1]);
            }
        }
        return kf[kf.length - 1];
    }

    /** Тот же поворот, но в системе руки после display-трансформации предмета:
     *  R_frame = D·C·Q·D⁻¹, где D = display rotation (0,-90,55), C =
     *  BLADE_GRIP_C (клинок — прямое продолжение руки, ~180°), Q = поворот
     *  плоскости лезвия.
     *  Применяется миксином HeldItemFeatureRenderer перед рендером предмета —
     *  лезвие доворачивается в локальных осях меча и в 1-м, и в 3-м лице. */
    public static Quaternionf currentBladeFrameRotation() {
        Quaternionf d = new Quaternionf().rotationXYZ(0f, (float) Math.toRadians(-90f), (float) Math.toRadians(55f));
        Quaternionf dc = new Quaternionf(d).conjugate();
        return new Quaternionf(d).mul(BLADE_GRIP_C).mul(currentBladeRotation()).mul(dc).normalize();
    }

    /** Клинок блокируется на время удара: клавиши движения не работают,
     *  герой двигается только микро-рывком по направлению атаки. В фазе
     *  восстановления (после удара) движение уже разблокировано — персонаж
     *  не «замирает» на месте. */
    public static boolean lockInputDuringAttack() {
        return (comboStep >= 0 && recoveryTicks == 0) || charging;
    }

    /** Точки трейла разреза (мировые координаты + возраст, для рендера дуги). */
    public static Deque<CombatController.SlashPoint> slashTrail() {
        return slashTrail;
    }

    /** Максимальный возраст точки трейла (для альфы в рендере). */
    public static int slashTrailAge() {
        return SLASH_TRAIL_AGE;
    }

    /** Траектория клинка в мировых координатах: та же цепочка, что у рендера
     *  (LivingEntityRenderer.render -> ModelPart.applyTransform ->
     *  HeldItemFeatureRenderer -> display handheld). Математика проверена
     *  запуском JOML (GeoTest/SwordDir): рука — Quaternionf.rotationZYX,
     *  клинок в системе руки — фиксированные константы из цепочки предмета.
     *  Поэтому разрез совпадает с видимым мечом (scripts/blade_geo.py). */
    public static Vec3d bladeTipWorld(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos) {
        Matrix4f m = new Matrix4f();
        // LivingEntityRenderer.setupTransforms: поворот тела (180 - bodyYaw).
        m.rotate(new Quaternionf().rotationY((float) Math.toRadians(180f - bodyYawDeg)));
        // render: scale(-1,-1,1) и translate(0,-1.501,0) — root модели у ног игрока.
        m.scale(-1f, -1f, 1f);
        m.translate(0f, -1.501f, 0f);
        // Root модели: разворот удара 3 + наклон корпуса в удар.
        m.rotate(new Quaternionf().rotationY(rootYawRad));
        m.rotate(new Quaternionf().rotationX(currentRootPitchRad()));
        // Правое плечо (пивот (-5, 2, 0)) + углы руки (как ModelPart.applyTransform).
        m.translate(-5f / 16f, 2f / 16f, 0f);
        m.rotate(new Quaternionf().rotationZYX(pose.rRoll(), pose.rYaw(), pose.rPitch()));
        // HeldItemFeatureRenderer: rotX(-90), rotY(180), translate(±1/16, 0.125, -0.625).
        m.rotate(new Quaternionf().rotationX((float) Math.toRadians(-90f)));
        m.rotate(new Quaternionf().rotationY((float) Math.toRadians(180f)));
        m.translate(1f / 16f, 0.125f, -0.625f);
        // display handheld (thirdperson_righthand): translate(0, 4, 0.5) в 1/16 блока,
        // rotation (0, -90, 55), scale 0.85.
        m.translate(0f, 4f / 16f, 0.5f / 16f);
        m.rotate(new Quaternionf().rotationXYZ(0f, (float) Math.toRadians(-90f), (float) Math.toRadians(55f)));
        // Грип-корректировка и поворот плоскости лезвия (item-local, те же,
        // что накладывает миксин HeldItemFeatureRenderer): разрез рисуется
        // точно по видимому мечу — клинок идёт по прямой «плечо→кисть».
        m.rotate(BLADE_GRIP_C);
        m.rotate(currentBladeRotation());
        m.scale(0.85f, 0.85f, 0.85f);
        // Кончик клинка — верх модели меча (локальный +Y предмета).
        Vector3f tip = m.transformPosition(new Vector3f(0f, 1f, 0f), new Vector3f());
        return new Vec3d(playerPos.x + tip.x, playerPos.y + tip.y, playerPos.z + tip.z);
    }

    /** Очистить трейл разреза (когда self-body выключен или вышел из свинга). */
    public static void clearSlashTrail() {
        slashTrail.clear();
    }



    /** Наклон всего тела в удар (root.pitch): нарастает к пику скорости
     *  (мгновенный удар, ~0.12 клипа) и спадает к концу. Удар 3 (разворот) —
     *  без наклона. Углы малые (6..10°) — пивот root на уровне шеи, ноги
     *  не отрываются. */
    private static final float[] ROOT_LEAN_DEG = {6f, 8f, 0f, 6f, 10f, 0f};

    private static float rootLean(float p) {
        if (comboStep < 0 || comboStep == 2 || comboStep == SwordCombo.CHARGE_INDEX) {
            return 0f;
        }
        float peak = 0.10f;
        float up = MathHelper.clamp(p / peak, 0f, 1f);
        float down = 1f - MathHelper.clamp((p - peak) / 0.22f, 0f, 1f);
        float u = Math.min(up, down);
        return (float) Math.toRadians(ROOT_LEAN_DEG[comboStep]) * u;
    }

    /** Наклон root на текущем прогрессе (для трейла разреза). */
    public static float currentRootPitchRad() {
        if (comboStep < 0) {
            return 0f;
        }
        return rootLean(progress());
    }

    /** Прогресс полного оборота разворота 0..1: разворот идёт вместе с
     *  сжатым замахом и завершается к мгновенному удару (~0.16), дальше —
     *  стабилизация (E_IN_OUT_CUBIC). */
    private static float spinTurn(float p) {
        float u = MathHelper.clamp(p / 0.16f, 0f, 1f);
        return (float) (-Math.PI * 2.0) * ease(E_IN_OUT_CUBIC, u);
    }

    /** Прогресс оборота заряженного спина 0..1: полный круг к ~0.25 клипа
     *  (урон на 0.077 — в начале орбиты, дальше клинок продолжает круг),
     *  затем стабилизация (E_IN_OUT_CUBIC). */
    private static float chargedSpinTurn(float p) {
        float u = MathHelper.clamp(p / 0.25f, 0f, 1f);
        return (float) (-Math.PI * 2.0) * ease(E_IN_OUT_CUBIC, u);
    }

    /** Боевая стойка (покой/после удара): клинок держим горизонтально слева —
     *  не «дефолтный майнкрафт» (меч вертикально). В неё уходит восстановление
     *  после удара, из неё же растёт АФК-покачивание и стартует бег. */
    private static final Pose READY_POSE = new Pose(
            READY_RYAW, READY_RPITCH, READY_RROLL,
            0f, READY_LPITCH, 0f,
            READY_BYAW, READY_BPITCH, 0f,
            Float.NaN, Float.NaN, Float.NaN,
            0f, 0f, 0f, 0f, 0f, 0f);

    /** Поза заряда (удержание ЛКМ): клинок поднят вверх-вперёд обеими руками,
     *  корпус чуть назад — готовность к спину. Голова — NaN (смотрит вперёд). */
    private static final Pose CHARGE_POSE = new Pose(
            (float) Math.toRadians(-20f), (float) Math.toRadians(-98f), (float) Math.toRadians(-10f),
            0f, (float) Math.toRadians(-72f), (float) Math.toRadians(-6f),
            (float) Math.toRadians(-6f), (float) Math.toRadians(4f), 0f,
            Float.NaN, Float.NaN, Float.NaN,
            0f, (float) Math.toRadians(-8f), 0f,
            0f, (float) Math.toRadians(6f), 0f);

    /** Смешать позу с боевой стойкой (клинок остаётся горизонтальным). */
    private static Pose relax(Pose p, float w) {
        return mix(p, READY_POSE, w);
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

    /** Позы пяти ударов, запечены скриптом scripts/gen_combo.py (якоря ->
     *  плавные кривые -> 41 линейный кадр на удар):
     *  удар 1 — клинок горизонтально, свинг справа налево, задержка руки
     *  слева; удар 2 — апперкот: замах клинком к земле, финиш рукой вверх;
     *  удар 3 — разворот против часовой стрелки (root −2π, см. spinTurn)
     *  с рубящим ударом клинком сверху; удар 4 — замах слева-сверху,
     *  свинг слева направо, клинок горизонтально на ударе; удар 5 — очень
     *  широкий замах за голову, наклон корпуса и рывок вперёд, задержка
     *  руки слева.
     *  Голова всегда 0 (вперёд). Левая рука — живой противовес, ноги
     *  переступают с выпадом в момент урона, корпус доворачивается
     *  (bYaw ±13°, bPitch ≤7°) — таз не отрывается от ног. Кадры плотные
     *  (макс. дельта между соседними ~21°), траектория клинка непрерывна.
     *  Порядок каналов Pose: прав. рука y/p/r, лев. рука y/p/r, корпус
     *  y/p/r, голова y/p/r, прав. нога y/p/r, лев. нога y/p/r. Углы в
     *  радианах, как у ModelPart. */
    // Удар 1: широкий горизонтальный слева направо
    private static final Pose hit1_00 = new Pose(0.000000f, -0.349066f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(0.663225f, -0.767945f, -0.104720f, 0.000000f, -0.663225f, -0.191986f, 0.111701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.209440f, 0.000000f, 0.000000f, 0.139626f, 0.000000f);
    private static final Pose hit1_02 = new Pose(1.012291f, -1.047198f, -0.087266f, 0.000000f, -1.099557f, -0.296706f, 0.171042f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.383972f, 0.000000f, 0.000000f, 0.261799f, 0.000000f);
    private static final Pose hit1_03 = new Pose(0.911935f, -1.036289f, -0.043633f, 0.000000f, -1.049379f, -0.237801f, 0.154025f, 0.006545f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.292343f, 0.000000f, 0.000000f, 0.218166f, 0.000000f);
    private static final Pose hit1_04 = new Pose(0.209440f, -0.959931f, 0.261799f, 0.000000f, -0.698132f, 0.174533f, 0.034907f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, -0.087266f, 0.000000f);
    private static final Pose hit1_05 = new Pose(0.208338f, -0.959967f, 0.261704f, 0.000000f, -0.697749f, 0.174437f, 0.034751f, 0.052350f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349377f, 0.000000f, 0.000000f, -0.087422f, 0.000000f);
    private static final Pose hit1_06 = new Pose(0.200629f, -0.960218f, 0.261033f, 0.000000f, -0.695067f, 0.173767f, 0.033662f, 0.052283f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.351556f, 0.000000f, 0.000000f, -0.088511f, 0.000000f);
    private static final Pose hit1_07 = new Pose(0.179704f, -0.960901f, 0.259214f, 0.000000f, -0.687789f, 0.171947f, 0.030705f, 0.052101f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.357469f, 0.000000f, 0.000000f, -0.091468f, 0.000000f);
    private static final Pose hit1_08 = new Pose(0.138956f, -0.962229f, 0.255670f, 0.000000f, -0.673616f, 0.168404f, 0.024947f, 0.051747f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.368985f, 0.000000f, 0.000000f, -0.097226f, 0.000000f);
    private static final Pose hit1_09 = new Pose(0.071776f, -0.964420f, 0.249829f, 0.000000f, -0.650249f, 0.162562f, 0.015454f, 0.051163f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.387971f, 0.000000f, 0.000000f, -0.106719f, 0.000000f);
    private static final Pose hit1_10 = new Pose(-0.028442f, -0.967688f, 0.241114f, 0.000000f, -0.615390f, 0.153848f, 0.001293f, 0.050291f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.416293f, 0.000000f, 0.000000f, -0.120880f, 0.000000f);
    private static final Pose hit1_11 = new Pose(-0.168308f, -0.972249f, 0.228952f, 0.000000f, -0.566741f, 0.141685f, -0.018471f, 0.049075f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.455821f, 0.000000f, 0.000000f, -0.140644f, 0.000000f);
    private static final Pose hit1_12 = new Pose(-0.354429f, -0.978318f, 0.212767f, 0.000000f, -0.502004f, 0.125501f, -0.044770f, 0.047457f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.508420f, 0.000000f, 0.000000f, -0.166943f, 0.000000f);
    private static final Pose hit1_13 = new Pose(-0.593412f, -0.986111f, 0.191986f, 0.000000f, -0.418879f, 0.104720f, -0.078540f, 0.045379f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.575959f, 0.000000f, 0.000000f, -0.200713f, 0.000000f);
    private static final Pose hit1_14 = new Pose(-0.758303f, -0.984344f, 0.141930f, 0.000000f, -0.374712f, 0.078219f, -0.072062f, 0.042434f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.573603f, 0.000000f, 0.000000f, -0.204835f, 0.000000f);
    private static final Pose hit1_15 = new Pose(-0.881178f, -0.983028f, 0.104629f, 0.000000f, -0.341799f, 0.058472f, -0.067235f, 0.040240f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.571848f, 0.000000f, 0.000000f, -0.207907f, 0.000000f);
    private static final Pose hit1_16 = new Pose(-0.968217f, -0.982095f, 0.078206f, 0.000000f, -0.318485f, 0.044483f, -0.063815f, 0.038686f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.570604f, 0.000000f, 0.000000f, -0.210083f, 0.000000f);
    private static final Pose hit1_17 = new Pose(-1.025597f, -0.981480f, 0.060787f, 0.000000f, -0.303115f, 0.035261f, -0.061561f, 0.037661f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.569785f, 0.000000f, 0.000000f, -0.211517f, 0.000000f);
    private static final Pose hit1_18 = new Pose(-1.059498f, -0.981117f, 0.050496f, 0.000000f, -0.294035f, 0.029813f, -0.060229f, 0.037056f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.569300f, 0.000000f, 0.000000f, -0.212365f, 0.000000f);
    private static final Pose hit1_19 = new Pose(-1.076098f, -0.980939f, 0.045456f, 0.000000f, -0.289588f, 0.027145f, -0.059577f, 0.036759f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.569063f, 0.000000f, 0.000000f, -0.212780f, 0.000000f);
    private static final Pose hit1_20 = new Pose(-1.081577f, -0.980881f, 0.043793f, 0.000000f, -0.288121f, 0.026265f, -0.059362f, 0.036661f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.568985f, 0.000000f, 0.000000f, -0.212917f, 0.000000f);
    private static final Pose hit1_21 = new Pose(-1.073893f, -0.980601f, 0.040212f, 0.000000f, -0.285927f, 0.024127f, -0.057425f, 0.036515f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.568156f, 0.000000f, 0.000000f, -0.212656f, 0.000000f);
    private static final Pose hit1_22 = new Pose(-1.038771f, -0.979431f, 0.025578f, 0.000000f, -0.277146f, 0.015347f, -0.049230f, 0.035930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.564644f, 0.000000f, 0.000000f, -0.211486f, 0.000000f);
    private static final Pose hit1_23 = new Pose(-1.012625f, -0.978559f, 0.014684f, 0.000000f, -0.270610f, 0.008810f, -0.043129f, 0.035494f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.562029f, 0.000000f, 0.000000f, -0.210614f, 0.000000f);
    private static final Pose hit1_24 = new Pose(-0.994211f, -0.977945f, 0.007011f, 0.000000f, -0.266006f, 0.004207f, -0.038833f, 0.035187f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.560188f, 0.000000f, 0.000000f, -0.210000f, 0.000000f);
    private static final Pose hit1_25 = new Pose(-0.982287f, -0.977548f, 0.002043f, 0.000000f, -0.263025f, 0.001226f, -0.036050f, 0.034988f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558996f, 0.000000f, 0.000000f, -0.209603f, 0.000000f);
    private static final Pose hit1_26 = new Pose(-0.975608f, -0.977325f, -0.000740f, 0.000000f, -0.261355f, -0.000444f, -0.034492f, 0.034877f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558328f, 0.000000f, 0.000000f, -0.209380f, 0.000000f);
    private static final Pose hit1_27 = new Pose(-0.972932f, -0.977236f, -0.001855f, 0.000000f, -0.260686f, -0.001113f, -0.033868f, 0.034832f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558060f, 0.000000f, 0.000000f, -0.209291f, 0.000000f);
    private static final Pose hit1_28 = new Pose(-0.973015f, -0.977239f, -0.001821f, 0.000000f, -0.260707f, -0.001092f, -0.033887f, 0.034834f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558068f, 0.000000f, 0.000000f, -0.209294f, 0.000000f);
    private static final Pose hit1_29 = new Pose(-0.974613f, -0.977292f, -0.001155f, 0.000000f, -0.261107f, -0.000693f, -0.034260f, 0.034860f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558228f, 0.000000f, 0.000000f, -0.209347f, 0.000000f);
    private static final Pose hit1_30 = new Pose(-0.976484f, -0.977354f, -0.000375f, 0.000000f, -0.261574f, -0.000225f, -0.034697f, 0.034892f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558415f, 0.000000f, 0.000000f, -0.209410f, 0.000000f);
    private static final Pose hit1_31 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_32 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_33 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_34 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_35 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_36 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_37 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_38 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_39 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit1_40 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);

    private static final Pose hit2_00 = new Pose(-0.977384f, -0.977384f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, -0.034907f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558505f, 0.000000f, 0.000000f, -0.209440f, 0.000000f);
    private static final Pose hit2_01 = new Pose(-0.802851f, -0.744674f, -0.087266f, 0.000000f, -0.479966f, -0.130900f, -0.052360f, 0.023271f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.296706f, 0.000000f, 0.000000f, -0.063995f, 0.000000f);
    private static final Pose hit2_02 = new Pose(-0.757602f, -0.610219f, -0.063349f, 0.000000f, -0.606340f, -0.172594f, -0.061022f, 0.018875f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.140919f, 0.000000f, 0.000000f, 0.021978f, 0.000000f);
    private static final Pose hit2_03 = new Pose(-0.738210f, -0.428575f, 0.012928f, 0.000000f, -0.810608f, -0.206854f, -0.073950f, 0.016419f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.113770f, 0.000000f, 0.000000f, 0.161605f, 0.000000f);
    private static final Pose hit2_04 = new Pose(-0.831102f, -0.916262f, -0.381866f, 0.000000f, -0.949948f, -0.160407f, -0.085562f, 0.060543f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.276332f, 0.000000f, 0.000000f, 0.242886f, 0.000000f);
    private static final Pose hit2_05 = new Pose(-0.865050f, -1.094489f, -0.526145f, 0.000000f, -1.000870f, -0.143433f, -0.089805f, 0.076668f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.335741f, 0.000000f, 0.000000f, 0.272590f, 0.000000f);
    private static final Pose hit2_06 = new Pose(-0.872966f, -1.134765f, -0.558392f, 0.000000f, -1.012178f, -0.139513f, -0.090776f, 0.080278f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.348878f, 0.000000f, 0.000000f, 0.279140f, 0.000000f);
    private static final Pose hit2_07 = new Pose(-0.876096f, -1.137896f, -0.557218f, 0.000000f, -1.011004f, -0.138339f, -0.090972f, 0.080199f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.346921f, 0.000000f, 0.000000f, 0.277966f, 0.000000f);
    private static final Pose hit2_08 = new Pose(-0.885582f, -1.147382f, -0.553661f, 0.000000f, -1.007447f, -0.134782f, -0.091564f, 0.079962f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.340992f, 0.000000f, 0.000000f, 0.274409f, 0.000000f);
    private static final Pose hit2_09 = new Pose(-0.904954f, -1.166754f, -0.546397f, 0.000000f, -1.000182f, -0.127518f, -0.092775f, 0.079478f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.328885f, 0.000000f, 0.000000f, 0.267144f, 0.000000f);
    private static final Pose hit2_10 = new Pose(-0.937743f, -1.199542f, -0.534101f, 0.000000f, -0.987887f, -0.115222f, -0.094825f, 0.078658f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.308392f, 0.000000f, 0.000000f, 0.254848f, 0.000000f);
    private static final Pose hit2_11 = new Pose(-0.987479f, -1.249279f, -0.515450f, 0.000000f, -0.969235f, -0.096571f, -0.097933f, 0.077415f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.277307f, 0.000000f, 0.000000f, 0.236197f, 0.000000f);
    private static final Pose hit2_12 = new Pose(-1.057694f, -1.319493f, -0.489119f, 0.000000f, -0.942905f, -0.070240f, -0.102321f, 0.075659f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.233423f, 0.000000f, 0.000000f, 0.209867f, 0.000000f);
    private static final Pose hit2_13 = new Pose(-1.151917f, -1.413717f, -0.453786f, 0.000000f, -0.907571f, -0.034907f, -0.108210f, 0.073304f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.174533f, 0.000000f);
    private static final Pose hit2_14 = new Pose(-1.162308f, -1.237077f, -0.620035f, 0.000000f, -0.866009f, -0.014125f, -0.111328f, 0.068109f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.112189f, 0.000000f, 0.000000f, 0.138166f, 0.000000f);
    private static final Pose hit2_15 = new Pose(-1.170400f, -1.099510f, -0.749510f, 0.000000f, -0.833640f, 0.002059f, -0.113755f, 0.064062f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.063636f, 0.000000f, 0.000000f, 0.109843f, 0.000000f);
    private static final Pose hit2_16 = new Pose(-1.176481f, -0.996131f, -0.846808f, 0.000000f, -0.809316f, 0.014221f, -0.115580f, 0.061022f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.027150f, 0.000000f, 0.000000f, 0.088559f, 0.000000f);
    private static final Pose hit2_17 = new Pose(-1.180839f, -0.922056f, -0.916525f, 0.000000f, -0.791886f, 0.022936f, -0.116887f, 0.058843f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.001006f, 0.000000f, 0.000000f, 0.073309f, 0.000000f);
    private static final Pose hit2_18 = new Pose(-1.183759f, -0.872401f, -0.963259f, 0.000000f, -0.780203f, 0.028778f, -0.117763f, 0.057383f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.016520f, 0.000000f, 0.000000f, 0.063086f, 0.000000f);
    private static final Pose hit2_19 = new Pose(-1.185531f, -0.842283f, -0.991606f, 0.000000f, -0.773116f, 0.032321f, -0.118295f, 0.056497f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.027150f, 0.000000f, 0.000000f, 0.056885f, 0.000000f);
    private static final Pose hit2_20 = new Pose(-1.186441f, -0.826817f, -1.006162f, 0.000000f, -0.769477f, 0.034140f, -0.118567f, 0.056042f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.032608f, 0.000000f, 0.000000f, 0.053701f, 0.000000f);
    private static final Pose hit2_21 = new Pose(-1.186776f, -0.821119f, -1.011525f, 0.000000f, -0.768136f, 0.034811f, -0.118668f, 0.055874f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.034619f, 0.000000f, 0.000000f, 0.052527f, 0.000000f);
    private static final Pose hit2_22 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.767945f, 0.034907f, -0.118682f, 0.055851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.034907f, 0.000000f, 0.000000f, 0.052360f, 0.000000f);
    private static final Pose hit2_23 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.744438f, 0.040783f, -0.119858f, 0.052324f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.081921f, 0.000000f, 0.000000f, 0.028853f, 0.000000f);
    private static final Pose hit2_24 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.726457f, 0.045279f, -0.120757f, 0.049627f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.117882f, 0.000000f, 0.000000f, 0.010872f, 0.000000f);
    private static final Pose hit2_25 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.713305f, 0.048567f, -0.121414f, 0.047654f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.144187f, 0.000000f, 0.000000f, -0.002280f, 0.000000f);
    private static final Pose hit2_26 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.704282f, 0.050822f, -0.121866f, 0.046301f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.162232f, 0.000000f, 0.000000f, -0.011303f, 0.000000f);
    private static final Pose hit2_27 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.698691f, 0.052220f, -0.122145f, 0.045462f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.173414f, 0.000000f, 0.000000f, -0.016894f, 0.000000f);
    private static final Pose hit2_28 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.695833f, 0.052934f, -0.122288f, 0.045034f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.179130f, 0.000000f, 0.000000f, -0.019752f, 0.000000f);
    private static final Pose hit2_29 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.695011f, 0.053140f, -0.122329f, 0.044910f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.180775f, 0.000000f, 0.000000f, -0.020574f, 0.000000f);
    private static final Pose hit2_30 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.695525f, 0.053012f, -0.122303f, 0.044988f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.179747f, 0.000000f, 0.000000f, -0.020060f, 0.000000f);
    private static final Pose hit2_31 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.696677f, 0.052723f, -0.122246f, 0.045160f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.177442f, 0.000000f, 0.000000f, -0.018908f, 0.000000f);
    private static final Pose hit2_32 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.697770f, 0.052450f, -0.122191f, 0.045324f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.175256f, 0.000000f, 0.000000f, -0.017815f, 0.000000f);
    private static final Pose hit2_33 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.698098f, 0.052360f, -0.122173f, 0.045359f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174599f, 0.000000f, 0.000000f, -0.017520f, 0.000000f);
    private static final Pose hit2_34 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.696963f, 0.052360f, -0.122173f, 0.044677f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.176871f, 0.000000f, 0.000000f, -0.019792f, 0.000000f);
    private static final Pose hit2_35 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.694410f, 0.052360f, -0.122173f, 0.043146f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.181975f, 0.000000f, 0.000000f, -0.024896f, 0.000000f);
    private static final Pose hit2_36 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.690920f, 0.052360f, -0.122173f, 0.041052f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.188955f, 0.000000f, 0.000000f, -0.031876f, 0.000000f);
    private static final Pose hit2_37 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.687146f, 0.052360f, -0.122173f, 0.038787f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.196503f, 0.000000f, 0.000000f, -0.039424f, 0.000000f);
    private static final Pose hit2_38 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.683796f, 0.052360f, -0.122173f, 0.036777f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.203205f, 0.000000f, 0.000000f, -0.046125f, 0.000000f);
    private static final Pose hit2_39 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.681496f, 0.052360f, -0.122173f, 0.035397f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.207804f, 0.000000f, 0.000000f, -0.050725f, 0.000000f);
    private static final Pose hit2_40 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.680678f, 0.052360f, -0.122173f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.209440f, 0.000000f, 0.000000f, -0.052360f, 0.000000f);

    private static final Pose hit3_00 = new Pose(-1.186824f, -0.820305f, -1.012291f, 0.000000f, -0.680678f, 0.052360f, -0.122173f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.209440f, 0.000000f, 0.000000f, -0.052360f, 0.000000f);
    private static final Pose hit3_01 = new Pose(-0.706208f, -2.073340f, -0.188378f, 0.000000f, -0.869492f, -0.067794f, -0.139338f, 0.017742f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.106450f, 0.000000f, 0.000000f, -0.000865f, 0.000000f);
    private static final Pose hit3_02 = new Pose(-0.375307f, -2.578632f, -0.142250f, 0.000000f, -1.098642f, -0.134378f, -0.149311f, -0.003530f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.088975f, 0.000000f, 0.000000f, 0.096847f, 0.000000f);
    private static final Pose hit3_03 = new Pose(-0.279043f, -3.010122f, -0.083608f, 0.000000f, -1.257057f, -0.195645f, -0.155700f, -0.008037f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.160738f, 0.000000f, 0.000000f, 0.132729f, 0.000000f);
    private static final Pose hit3_04 = new Pose(-0.331822f, -2.084352f, -0.153841f, 0.000000f, -1.095480f, -0.111407f, -0.017034f, 0.054294f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.286570f, 0.000000f, 0.000000f, 0.223654f, 0.000000f);
    private static final Pose hit3_05 = new Pose(-0.344213f, -1.903453f, -0.168709f, 0.000000f, -1.060787f, -0.094061f, 0.007747f, 0.065445f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.306394f, 0.000000f, 0.000000f, 0.238522f, 0.000000f);
    private static final Pose hit3_06 = new Pose(-0.348725f, -1.837569f, -0.174124f, 0.000000f, -1.048151f, -0.087743f, 0.016772f, 0.069507f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.313614f, 0.000000f, 0.000000f, 0.243937f, 0.000000f);
    private static final Pose hit3_07 = new Pose(-0.353930f, -1.806329f, -0.170642f, 0.000000f, -1.033578f, -0.080456f, 0.020858f, 0.070008f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.304431f, 0.000000f, 0.000000f, 0.238509f, 0.000000f);
    private static final Pose hit3_08 = new Pose(-0.372941f, -1.703672f, -0.155433f, 0.000000f, -0.980348f, -0.053842f, 0.034166f, 0.070768f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.266410f, 0.000000f, 0.000000f, 0.215696f, 0.000000f);
    private static final Pose hit3_09 = new Pose(-0.395476f, -1.581981f, -0.137405f, 0.000000f, -0.917249f, -0.022292f, 0.049940f, 0.071670f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.221339f, 0.000000f, 0.000000f, 0.188654f, 0.000000f);
    private static final Pose hit3_10 = new Pose(-0.412009f, -1.492702f, -0.124178f, 0.000000f, -0.870956f, 0.000854f, 0.061514f, 0.072331f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.188273f, 0.000000f, 0.000000f, 0.168814f, 0.000000f);
    private static final Pose hit3_11 = new Pose(-0.422874f, -1.434031f, -0.115486f, 0.000000f, -0.840534f, 0.016065f, 0.069119f, 0.072766f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.166543f, 0.000000f, 0.000000f, 0.155776f, 0.000000f);
    private static final Pose hit3_12 = new Pose(-0.429772f, -1.396783f, -0.109968f, 0.000000f, -0.821220f, 0.025722f, 0.073948f, 0.073041f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.152747f, 0.000000f, 0.000000f, 0.147499f, 0.000000f);
    private static final Pose hit3_13 = new Pose(-0.433865f, -1.374682f, -0.106694f, 0.000000f, -0.809761f, 0.031452f, 0.076812f, 0.073205f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.144562f, 0.000000f, 0.000000f, 0.142588f, 0.000000f);
    private static final Pose hit3_14 = new Pose(-0.435884f, -1.363778f, -0.105079f, 0.000000f, -0.804107f, 0.034279f, 0.078226f, 0.073286f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.140523f, 0.000000f, 0.000000f, 0.140164f, 0.000000f);
    private static final Pose hit3_15 = new Pose(-0.443988f, -1.354892f, -0.104550f, 0.000000f, -0.799109f, 0.035247f, 0.078965f, 0.072930f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.136564f, 0.000000f, 0.000000f, 0.137925f, 0.000000f);
    private static final Pose hit3_16 = new Pose(-0.533550f, -1.279262f, -0.102559f, 0.000000f, -0.755323f, 0.039227f, 0.083941f, 0.068551f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.100739f, 0.000000f, 0.000000f, 0.118022f, 0.000000f);
    private static final Pose hit3_17 = new Pose(-0.696388f, -1.141754f, -0.098941f, 0.000000f, -0.675713f, 0.046465f, 0.092987f, 0.060590f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.035604f, 0.000000f, 0.000000f, 0.081836f, 0.000000f);
    private static final Pose hit3_18 = new Pose(-0.860310f, -1.003332f, -0.095298f, 0.000000f, -0.595574f, 0.053750f, 0.102094f, 0.052576f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.029965f, 0.000000f, 0.000000f, 0.045409f, 0.000000f);
    private static final Pose hit3_19 = new Pose(-0.985299f, -0.897785f, -0.092521f, 0.000000f, -0.534468f, 0.059305f, 0.109038f, 0.046465f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.079960f, 0.000000f, 0.000000f, 0.017634f, 0.000000f);
    private static final Pose hit3_20 = new Pose(-1.074056f, -0.822834f, -0.090548f, 0.000000f, -0.491075f, 0.063250f, 0.113969f, 0.042126f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.115463f, 0.000000f, 0.000000f, -0.002090f, 0.000000f);
    private static final Pose hit3_21 = new Pose(-1.135705f, -0.770776f, -0.089178f, 0.000000f, -0.460936f, 0.065990f, 0.117394f, 0.039112f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.140123f, 0.000000f, 0.000000f, -0.015790f, 0.000000f);
    private static final Pose hit3_22 = new Pose(-1.177226f, -0.735713f, -0.088255f, 0.000000f, -0.440637f, 0.067835f, 0.119701f, 0.037082f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.156731f, 0.000000f, 0.000000f, -0.025017f, 0.000000f);
    private static final Pose hit3_23 = new Pose(-1.203383f, -0.713625f, -0.087674f, 0.000000f, -0.427849f, 0.068998f, 0.121154f, 0.035804f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.167194f, 0.000000f, 0.000000f, -0.030829f, 0.000000f);
    private static final Pose hit3_24 = new Pose(-1.217446f, -0.701750f, -0.087362f, 0.000000f, -0.420974f, 0.069623f, 0.121935f, 0.035116f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.172819f, 0.000000f, 0.000000f, -0.033954f, 0.000000f);
    private static final Pose hit3_25 = new Pose(-1.221730f, -0.698132f, -0.087266f, 0.000000f, -0.418879f, 0.069813f, 0.122173f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.174533f, 0.000000f, 0.000000f, -0.034907f, 0.000000f);
    private static final Pose hit3_26 = new Pose(-1.169163f, -0.726168f, -0.087266f, 0.000000f, -0.410994f, 0.066309f, 0.123049f, 0.033154f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.183294f, 0.000000f, 0.000000f, -0.033154f, 0.000000f);
    private static final Pose hit3_27 = new Pose(-1.062115f, -0.783260f, -0.087266f, 0.000000f, -0.394937f, 0.059172f, 0.124833f, 0.029586f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.201136f, 0.000000f, 0.000000f, -0.029586f, 0.000000f);
    private static final Pose hit3_28 = new Pose(-0.912551f, -0.863027f, -0.087266f, 0.000000f, -0.372502f, 0.049201f, 0.127326f, 0.024601f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.226063f, 0.000000f, 0.000000f, -0.024601f, 0.000000f);
    private static final Pose hit3_29 = new Pose(-0.755983f, -0.946531f, -0.087266f, 0.000000f, -0.349017f, 0.038763f, 0.129936f, 0.019382f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.252158f, 0.000000f, 0.000000f, -0.019382f, 0.000000f);
    private static final Pose hit3_30 = new Pose(-0.618688f, -1.019755f, -0.087266f, 0.000000f, -0.328423f, 0.029610f, 0.132224f, 0.014805f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.275040f, 0.000000f, 0.000000f, -0.014805f, 0.000000f);
    private static final Pose hit3_31 = new Pose(-0.506976f, -1.079334f, -0.087266f, 0.000000f, -0.311666f, 0.022163f, 0.134086f, 0.011081f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.293659f, 0.000000f, 0.000000f, -0.011081f, 0.000000f);
    private static final Pose hit3_32 = new Pose(-0.418372f, -1.126590f, -0.087266f, 0.000000f, -0.298375f, 0.016256f, 0.135562f, 0.008128f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.308426f, 0.000000f, 0.000000f, -0.008128f, 0.000000f);
    private static final Pose hit3_33 = new Pose(-0.348817f, -1.163686f, -0.087266f, 0.000000f, -0.287942f, 0.011619f, 0.136722f, 0.005809f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.320019f, 0.000000f, 0.000000f, -0.005809f, 0.000000f);
    private static final Pose hit3_34 = new Pose(-0.294721f, -1.192537f, -0.087266f, 0.000000f, -0.279828f, 0.008013f, 0.137623f, 0.004006f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.329035f, 0.000000f, 0.000000f, -0.004006f, 0.000000f);
    private static final Pose hit3_35 = new Pose(-0.253257f, -1.214651f, -0.087266f, 0.000000f, -0.273608f, 0.005248f, 0.138314f, 0.002624f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.335945f, 0.000000f, 0.000000f, -0.002624f, 0.000000f);
    private static final Pose hit3_36 = new Pose(-0.222259f, -1.231183f, -0.087266f, 0.000000f, -0.268958f, 0.003182f, 0.138831f, 0.001591f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.341112f, 0.000000f, 0.000000f, -0.001591f, 0.000000f);
    private static final Pose hit3_37 = new Pose(-0.200058f, -1.243024f, -0.087266f, 0.000000f, -0.265628f, 0.001702f, 0.139201f, 0.000851f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.344812f, 0.000000f, 0.000000f, -0.000851f, 0.000000f);
    private static final Pose hit3_38 = new Pose(-0.185355f, -1.250866f, -0.087266f, 0.000000f, -0.263423f, 0.000721f, 0.139446f, 0.000361f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.347262f, 0.000000f, 0.000000f, -0.000361f, 0.000000f);
    private static final Pose hit3_39 = new Pose(-0.177121f, -1.255257f, -0.087266f, 0.000000f, -0.262188f, 0.000173f, 0.139583f, 0.000086f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.348634f, 0.000000f, 0.000000f, -0.000086f, 0.000000f);
    private static final Pose hit3_40 = new Pose(-0.174533f, -1.256637f, -0.087266f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit4_00 = new Pose(-0.174533f, -1.256637f, -0.087266f, 0.000000f, -0.261799f, 0.000000f, 0.139626f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(-0.379448f, -1.881724f, -0.053006f, 0.000000f, -0.603755f, -0.176472f, 0.167422f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.201682f, 0.000000f, 0.000000f, 0.056238f, 0.000000f);
    private static final Pose hit4_02 = new Pose(-0.174533f, -3.106686f, 0.104720f, 0.000000f, -1.082104f, -0.244346f, 0.226893f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.279253f, 0.000000f, 0.000000f, 0.209440f, 0.000000f);
    private static final Pose hit4_03 = new Pose(-0.177811f, -2.905075f, 0.070298f, 0.000000f, -1.046044f, -0.205007f, 0.208863f, 0.004917f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.220245f, 0.000000f, 0.000000f, 0.181575f, 0.000000f);
    private static final Pose hit4_04 = new Pose(-0.200759f, -1.493797f, -0.170652f, 0.000000f, -0.793620f, 0.070364f, 0.082651f, 0.039339f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.192812f, 0.000000f, 0.000000f, -0.013480f, 0.000000f);
    private static final Pose hit4_05 = new Pose(-0.207877f, -1.056040f, -0.245391f, 0.000000f, -0.715322f, 0.155780f, 0.043502f, 0.050016f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.320936f, 0.000000f, 0.000000f, -0.073983f, 0.000000f);
    private static final Pose hit4_06 = new Pose(-0.208611f, -0.959988f, -0.261743f, 0.000000f, -0.697830f, 0.174458f, 0.034775f, 0.052349f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349311f, 0.000000f, 0.000000f, -0.087389f, 0.000000f);
    private static final Pose hit4_07 = new Pose(-0.200002f, -0.960575f, -0.261156f, 0.000000f, -0.694700f, 0.173675f, 0.033405f, 0.052231f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.351854f, 0.000000f, 0.000000f, -0.088661f, 0.000000f);
    private static final Pose hit4_08 = new Pose(-0.173916f, -0.962353f, -0.259377f, 0.000000f, -0.685214f, 0.171303f, 0.029255f, 0.051875f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.359562f, 0.000000f, 0.000000f, -0.092514f, 0.000000f);
    private static final Pose hit4_09 = new Pose(-0.120643f, -0.965985f, -0.255745f, 0.000000f, -0.665842f, 0.166460f, 0.020780f, 0.051149f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.375301f, 0.000000f, 0.000000f, -0.100384f, 0.000000f);
    private static final Pose hit4_10 = new Pose(-0.030474f, -0.972133f, -0.249597f, 0.000000f, -0.633053f, 0.158263f, 0.006435f, 0.049919f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.401942f, 0.000000f, 0.000000f, -0.113705f, 0.000000f);
    private static final Pose hit4_11 = new Pose(0.106301f, -0.981459f, -0.240272f, 0.000000f, -0.583317f, 0.145829f, -0.015325f, 0.048054f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.442353f, 0.000000f, 0.000000f, -0.133910f, 0.000000f);
    private static final Pose hit4_12 = new Pose(0.299391f, -0.994624f, -0.227106f, 0.000000f, -0.513102f, 0.128276f, -0.046044f, 0.045421f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.499402f, 0.000000f, 0.000000f, -0.162435f, 0.000000f);
    private static final Pose hit4_13 = new Pose(0.558505f, -1.012291f, -0.209440f, 0.000000f, -0.418879f, 0.104720f, -0.087266f, 0.041888f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.575959f, 0.000000f, 0.000000f, -0.200713f, 0.000000f);
    private static final Pose hit4_14 = new Pose(0.662411f, -1.048658f, -0.188658f, 0.000000f, -0.379914f, 0.081341f, -0.104930f, 0.033575f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.544787f, 0.000000f, 0.000000f, -0.172139f, 0.000000f);
    private static final Pose hit4_15 = new Pose(0.743333f, -1.076981f, -0.172474f, 0.000000f, -0.349569f, 0.063134f, -0.118687f, 0.027102f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.520510f, 0.000000f, 0.000000f, -0.149885f, 0.000000f);
    private static final Pose hit4_16 = new Pose(0.804144f, -1.098265f, -0.160312f, 0.000000f, -0.326764f, 0.049451f, -0.129025f, 0.022237f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.502267f, 0.000000f, 0.000000f, -0.133162f, 0.000000f);
    private static final Pose hit4_17 = new Pose(0.847718f, -1.113515f, -0.151597f, 0.000000f, -0.310424f, 0.039647f, -0.136433f, 0.018751f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.489195f, 0.000000f, 0.000000f, -0.121179f, 0.000000f);
    private static final Pose hit4_18 = new Pose(0.876926f, -1.123738f, -0.145755f, 0.000000f, -0.299471f, 0.033075f, -0.141398f, 0.016414f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.480432f, 0.000000f, 0.000000f, -0.113147f, 0.000000f);
    private static final Pose hit4_19 = new Pose(0.894643f, -1.129939f, -0.142212f, 0.000000f, -0.292827f, 0.029089f, -0.144410f, 0.014997f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.475117f, 0.000000f, 0.000000f, -0.108275f, 0.000000f);
    private static final Pose hit4_20 = new Pose(0.903741f, -1.133123f, -0.140392f, 0.000000f, -0.289416f, 0.027042f, -0.145956f, 0.014269f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.472388f, 0.000000f, 0.000000f, -0.105773f, 0.000000f);
    private static final Pose hit4_21 = new Pose(0.907092f, -1.134296f, -0.139722f, 0.000000f, -0.288159f, 0.026288f, -0.146526f, 0.014001f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.471383f, 0.000000f, 0.000000f, -0.104851f, 0.000000f);
    private static final Pose hit4_22 = new Pose(0.907571f, -1.134464f, -0.139626f, 0.000000f, -0.287979f, 0.026180f, -0.146608f, 0.013963f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.471239f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);
    private static final Pose hit4_23 = new Pose(0.954586f, -1.093327f, -0.139626f, 0.000000f, -0.279164f, 0.017365f, -0.153660f, 0.011024f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.418348f, 0.000000f, 0.000000f, -0.081213f, 0.000000f);
    private static final Pose hit4_24 = new Pose(0.990547f, -1.061860f, -0.139626f, 0.000000f, -0.272421f, 0.010622f, -0.159054f, 0.008777f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.377891f, 0.000000f, 0.000000f, -0.063232f, 0.000000f);
    private static final Pose hit4_25 = new Pose(1.016852f, -1.038843f, -0.139626f, 0.000000f, -0.267489f, 0.005690f, -0.163000f, 0.007133f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.348298f, 0.000000f, 0.000000f, -0.050079f, 0.000000f);
    private static final Pose hit4_26 = new Pose(1.034897f, -1.023054f, -0.139626f, 0.000000f, -0.264106f, 0.002306f, -0.165707f, 0.006005f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.327997f, 0.000000f, 0.000000f, -0.041057f, 0.000000f);
    private static final Pose hit4_27 = new Pose(1.046079f, -1.013270f, -0.139626f, 0.000000f, -0.262009f, 0.000210f, -0.167384f, 0.005306f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.315418f, 0.000000f, 0.000000f, -0.035466f, 0.000000f);
    private static final Pose hit4_28 = new Pose(1.051794f, -1.008269f, -0.139626f, 0.000000f, -0.260937f, -0.000862f, -0.168241f, 0.004949f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.308988f, 0.000000f, 0.000000f, -0.032608f, 0.000000f);
    private static final Pose hit4_29 = new Pose(1.053440f, -1.006829f, -0.139626f, 0.000000f, -0.260629f, -0.001170f, -0.168488f, 0.004846f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.307137f, 0.000000f, 0.000000f, -0.031786f, 0.000000f);
    private static final Pose hit4_30 = new Pose(1.052411f, -1.007729f, -0.139626f, 0.000000f, -0.260822f, -0.000978f, -0.168334f, 0.004910f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.308294f, 0.000000f, 0.000000f, -0.032300f, 0.000000f);
    private static final Pose hit4_31 = new Pose(1.050106f, -1.009746f, -0.139626f, 0.000000f, -0.261254f, -0.000545f, -0.167988f, 0.005054f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.310887f, 0.000000f, 0.000000f, -0.033452f, 0.000000f);
    private static final Pose hit4_32 = new Pose(1.047921f, -1.011658f, -0.139626f, 0.000000f, -0.261664f, -0.000136f, -0.167660f, 0.005191f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.313345f, 0.000000f, 0.000000f, -0.034545f, 0.000000f);
    private static final Pose hit4_33 = new Pose(1.046600f, -1.013188f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.167565f, 0.005226f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.314060f, 0.000000f, 0.000000f, -0.034840f, 0.000000f);
    private static final Pose hit4_34 = new Pose(1.026153f, -1.043858f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.168019f, 0.004885f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.310652f, 0.000000f, 0.000000f, -0.032568f, 0.000000f);
    private static final Pose hit4_35 = new Pose(0.980215f, -1.112765f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.169040f, 0.004120f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.302996f, 0.000000f, 0.000000f, -0.027464f, 0.000000f);
    private static final Pose hit4_36 = new Pose(0.917395f, -1.206996f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.170436f, 0.003073f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.292525f, 0.000000f, 0.000000f, -0.020484f, 0.000000f);
    private static final Pose hit4_37 = new Pose(0.849463f, -1.308893f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.171946f, 0.001940f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.281203f, 0.000000f, 0.000000f, -0.012936f, 0.000000f);
    private static final Pose hit4_38 = new Pose(0.789149f, -1.399364f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.173286f, 0.000935f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.271151f, 0.000000f, 0.000000f, -0.006235f, 0.000000f);
    private static final Pose hit4_39 = new Pose(0.747755f, -1.461454f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.174206f, 0.000245f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.264252f, 0.000000f, 0.000000f, -0.001635f, 0.000000f);
    private static final Pose hit4_40 = new Pose(0.733038f, -1.483530f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit5_00 = new Pose(0.733038f, -1.483530f, -0.139626f, 0.000000f, -0.261799f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.261799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(0.959931f, -1.919862f, -0.104720f, 0.000000f, -0.698132f, -0.209440f, -0.205949f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.034907f, 0.000000f, 0.000000f, 0.139626f, 0.000000f);
    private static final Pose hit5_02 = new Pose(0.959931f, -2.188772f, -0.073692f, 0.000000f, -0.936014f, -0.250810f, -0.212154f, 0.015514f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.099548f, 0.000000f, 0.000000f, 0.186168f, 0.000000f);
    private static final Pose hit5_03 = new Pose(0.952568f, -2.805071f, 0.002727f, 0.000000f, -1.493620f, -0.344157f, -0.224493f, 0.053287f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.410425f, 0.000000f, 0.000000f, 0.294524f, 0.000000f);
    private static final Pose hit5_04 = new Pose(0.761127f, -2.223659f, 0.073631f, 0.000000f, -1.302179f, -0.216530f, -0.162097f, 0.077394f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.190623f, 0.000000f, 0.000000f, 0.237801f, 0.000000f);
    private static final Pose hit5_05 = new Pose(0.687223f, -1.999210f, 0.101003f, 0.000000f, -1.228275f, -0.167261f, -0.138010f, 0.086701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.105770f, 0.000000f, 0.000000f, 0.215904f, 0.000000f);
    private static final Pose hit5_06 = new Pose(0.596957f, -1.725068f, 0.134435f, 0.000000f, -1.138009f, -0.107083f, -0.108590f, 0.098068f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.002131f, 0.000000f, 0.000000f, 0.189158f, 0.000000f);
    private static final Pose hit5_07 = new Pose(0.488692f, -1.396263f, 0.174533f, 0.000000f, -1.029744f, -0.034907f, -0.073304f, 0.111701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.122173f, 0.000000f, 0.000000f, 0.157080f, 0.000000f);
    private static final Pose hit5_08 = new Pose(0.487736f, -1.395454f, 0.174717f, 0.000000f, -1.028972f, -0.034465f, -0.073094f, 0.111742f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.122872f, 0.000000f, 0.000000f, 0.156454f, 0.000000f);
    private static final Pose hit5_09 = new Pose(0.481042f, -1.389790f, 0.176004f, 0.000000f, -1.023565f, -0.031376f, -0.071627f, 0.112025f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.127763f, 0.000000f, 0.000000f, 0.152078f, 0.000000f);
    private static final Pose hit5_10 = new Pose(0.462874f, -1.374417f, 0.179498f, 0.000000f, -1.008891f, -0.022990f, -0.067644f, 0.112793f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.141040f, 0.000000f, 0.000000f, 0.140198f, 0.000000f);
    private static final Pose hit5_11 = new Pose(0.427493f, -1.344479f, 0.186302f, 0.000000f, -0.980314f, -0.006661f, -0.059887f, 0.114290f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.166896f, 0.000000f, 0.000000f, 0.117065f, 0.000000f);
    private static final Pose hit5_12 = new Pose(0.369162f, -1.295123f, 0.197519f, 0.000000f, -0.933201f, 0.020261f, -0.047099f, 0.116758f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.209522f, 0.000000f, 0.000000f, 0.078925f, 0.000000f);
    private static final Pose hit5_13 = new Pose(0.282144f, -1.221492f, 0.214254f, 0.000000f, -0.862917f, 0.060423f, -0.028022f, 0.120440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.273112f, 0.000000f, 0.000000f, 0.022029f, 0.000000f);
    private static final Pose hit5_14 = new Pose(0.160702f, -1.118733f, 0.237608f, 0.000000f, -0.764829f, 0.116474f, -0.001398f, 0.125578f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.361858f, 0.000000f, 0.000000f, -0.057376f, 0.000000f);
    private static final Pose hit5_15 = new Pose(-0.018651f, -1.002553f, 0.254496f, 0.000000f, -0.643750f, 0.169664f, 0.034092f, 0.127248f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.470827f, 0.000000f, 0.000000f, -0.145712f, 0.000000f);
    private static final Pose hit5_16 = new Pose(-0.249435f, -0.960593f, 0.223026f, 0.000000f, -0.559828f, 0.148684f, 0.068185f, 0.111513f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.544258f, 0.000000f, 0.000000f, -0.171938f, 0.000000f);
    private static final Pose hit5_17 = new Pose(-0.424245f, -0.928809f, 0.199188f, 0.000000f, -0.496261f, 0.132792f, 0.094009f, 0.099594f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.599879f, 0.000000f, 0.000000f, -0.191803f, 0.000000f);
    private static final Pose hit5_18 = new Pose(-0.550858f, -0.905788f, 0.181922f, 0.000000f, -0.450220f, 0.121282f, 0.112713f, 0.090961f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.640165f, 0.000000f, 0.000000f, -0.206190f, 0.000000f);
    private static final Pose hit5_19 = new Pose(-0.637045f, -0.890118f, 0.170170f, 0.000000f, -0.418879f, 0.113446f, 0.125446f, 0.085085f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.667588f, 0.000000f, 0.000000f, -0.215984f, 0.000000f);
    private static final Pose hit5_20 = new Pose(-0.690582f, -0.880384f, 0.162869f, 0.000000f, -0.399411f, 0.108579f, 0.133354f, 0.081435f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.684623f, 0.000000f, 0.000000f, -0.222068f, 0.000000f);
    private static final Pose hit5_21 = new Pose(-0.719242f, -0.875173f, 0.158961f, 0.000000f, -0.388989f, 0.105974f, 0.137588f, 0.079480f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.693742f, 0.000000f, 0.000000f, -0.225325f, 0.000000f);
    private static final Pose hit5_22 = new Pose(-0.730799f, -0.873072f, 0.157385f, 0.000000f, -0.384787f, 0.104923f, 0.139296f, 0.078692f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.697419f, 0.000000f, 0.000000f, -0.226638f, 0.000000f);
    private static final Pose hit5_23 = new Pose(-0.733028f, -0.872667f, 0.157081f, 0.000000f, -0.383976f, 0.104721f, 0.139625f, 0.078541f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.698128f, 0.000000f, 0.000000f, -0.226892f, 0.000000f);
    private static final Pose hit5_24 = new Pose(-0.882955f, -0.776290f, 0.114246f, 0.000000f, -0.346493f, 0.075272f, 0.150335f, 0.073721f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.655298f, 0.000000f, 0.000000f, -0.194768f, 0.000000f);
    private static final Pose hit5_25 = new Pose(-1.026146f, -0.684238f, 0.073335f, 0.000000f, -0.310695f, 0.047145f, 0.160563f, 0.069118f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.614387f, 0.000000f, 0.000000f, -0.164084f, 0.000000f);
    private static final Pose hit5_26 = new Pose(-1.126718f, -0.619585f, 0.044600f, 0.000000f, -0.285553f, 0.027390f, 0.167746f, 0.065886f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.585652f, 0.000000f, 0.000000f, -0.142533f, 0.000000f);
    private static final Pose hit5_27 = new Pose(-1.191629f, -0.577857f, 0.026054f, 0.000000f, -0.269325f, 0.014640f, 0.172383f, 0.063799f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.567106f, 0.000000f, 0.000000f, -0.128623f, 0.000000f);
    private static final Pose hit5_28 = new Pose(-1.227839f, -0.554578f, 0.015708f, 0.000000f, -0.260272f, 0.007527f, 0.174969f, 0.062636f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556760f, 0.000000f, 0.000000f, -0.120864f, 0.000000f);
    private static final Pose hit5_29 = new Pose(-1.242309f, -0.545276f, 0.011574f, 0.000000f, -0.256655f, 0.004684f, 0.176003f, 0.062170f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.552626f, 0.000000f, 0.000000f, -0.117763f, 0.000000f);
    private static final Pose hit5_30 = new Pose(-1.241997f, -0.545477f, 0.011663f, 0.000000f, -0.256733f, 0.004746f, 0.175981f, 0.062180f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.552715f, 0.000000f, 0.000000f, -0.117830f, 0.000000f);
    private static final Pose hit5_31 = new Pose(-1.233864f, -0.550705f, 0.013986f, 0.000000f, -0.258766f, 0.006343f, 0.175400f, 0.062442f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.555039f, 0.000000f, 0.000000f, -0.119573f, 0.000000f);
    private static final Pose hit5_32 = new Pose(-1.224870f, -0.556487f, 0.016556f, 0.000000f, -0.261015f, 0.008110f, 0.174757f, 0.062731f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.557608f, 0.000000f, 0.000000f, -0.121500f, 0.000000f);
    private static final Pose hit5_33 = new Pose(-1.221730f, -0.558505f, 0.017420f, 0.000000f, -0.261799f, 0.008710f, 0.174533f, 0.062779f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.558439f, 0.000000f, 0.000000f, -0.122140f, 0.000000f);
    private static final Pose hit5_34 = new Pose(-1.221730f, -0.558505f, 0.016284f, 0.000000f, -0.261799f, 0.008142f, 0.174533f, 0.060961f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.556167f, 0.000000f, 0.000000f, -0.121004f, 0.000000f);
    private static final Pose hit5_35 = new Pose(-1.221730f, -0.558505f, 0.013732f, 0.000000f, -0.261799f, 0.006866f, 0.174533f, 0.056878f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.551063f, 0.000000f, 0.000000f, -0.118452f, 0.000000f);
    private static final Pose hit5_36 = new Pose(-1.221730f, -0.558505f, 0.010242f, 0.000000f, -0.261799f, 0.005121f, 0.174533f, 0.051294f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.544083f, 0.000000f, 0.000000f, -0.114962f, 0.000000f);
    private static final Pose hit5_37 = new Pose(-1.221730f, -0.558505f, 0.006468f, 0.000000f, -0.261799f, 0.003234f, 0.174533f, 0.045255f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.536535f, 0.000000f, 0.000000f, -0.111188f, 0.000000f);
    private static final Pose hit5_38 = new Pose(-1.221730f, -0.558505f, 0.003117f, 0.000000f, -0.261799f, 0.001559f, 0.174533f, 0.039894f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.529833f, 0.000000f, 0.000000f, -0.107837f, 0.000000f);
    private static final Pose hit5_39 = new Pose(-1.221730f, -0.558505f, 0.000818f, 0.000000f, -0.261799f, 0.000409f, 0.174533f, 0.036215f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.525234f, 0.000000f, 0.000000f, -0.105537f, 0.000000f);
    private static final Pose hit5_40 = new Pose(-1.221730f, -0.558505f, 0.000000f, 0.000000f, -0.261799f, 0.000000f, 0.174533f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.523599f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);

    private static final Pose charged_00 = new Pose(0.000000f, -0.349066f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_01 = new Pose(-0.610865f, -1.803507f, -0.116355f, 0.000000f, -0.829031f, -0.145444f, -0.101811f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.174533f, 0.000000f, 0.000000f, 0.116355f, 0.000000f);
    private static final Pose charged_02 = new Pose(-0.645125f, -2.327106f, -0.124112f, 0.000000f, -1.047844f, -0.195218f, -0.132516f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.240468f, 0.000000f, 0.000000f, 0.160312f, 0.000000f);
    private static final Pose charged_03 = new Pose(-0.431730f, -2.783152f, -0.073461f, 0.000000f, -1.225344f, -0.220416f, -0.146955f, 0.003682f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, -0.279185f, 0.000000f, 0.000000f, 0.192873f, 0.000000f);
    private static final Pose charged_04 = new Pose(-0.349066f, -1.047198f, 0.174533f, 0.000000f, -0.663225f, 0.209440f, 0.034907f, 0.069813f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349066f, 0.000000f, 0.000000f, -0.104720f, 0.000000f);
    private static final Pose charged_05 = new Pose(-0.347773f, -1.047245f, 0.174437f, 0.000000f, -0.662842f, 0.209248f, 0.035002f, 0.069789f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.349209f, 0.000000f, 0.000000f, -0.104768f, 0.000000f);
    private static final Pose charged_06 = new Pose(-0.338723f, -1.047581f, 0.173767f, 0.000000f, -0.660161f, 0.207907f, 0.035673f, 0.069622f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.350215f, 0.000000f, 0.000000f, -0.105103f, 0.000000f);
    private static final Pose charged_07 = new Pose(-0.314159f, -1.048490f, 0.171947f, 0.000000f, -0.652882f, 0.204268f, 0.037492f, 0.069167f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.352944f, 0.000000f, 0.000000f, -0.106013f, 0.000000f);
    private static final Pose charged_08 = new Pose(-0.266324f, -1.050262f, 0.168404f, 0.000000f, -0.638709f, 0.197182f, 0.041036f, 0.068281f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.358259f, 0.000000f, 0.000000f, -0.107784f, 0.000000f);
    private static final Pose charged_09 = new Pose(-0.187461f, -1.053183f, 0.162562f, 0.000000f, -0.615342f, 0.185498f, 0.046877f, 0.066820f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.367022f, 0.000000f, 0.000000f, -0.110705f, 0.000000f);
    private static final Pose charged_10 = new Pose(-0.069813f, -1.057540f, 0.153848f, 0.000000f, -0.580484f, 0.168069f, 0.055592f, 0.064642f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.380094f, 0.000000f, 0.000000f, -0.115062f, 0.000000f);
    private static final Pose charged_11 = new Pose(0.094377f, -1.063621f, 0.141685f, 0.000000f, -0.531835f, 0.143744f, 0.067754f, 0.061601f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.398337f, 0.000000f, 0.000000f, -0.121144f, 0.000000f);
    private static final Pose charged_12 = new Pose(0.312866f, -1.071714f, 0.125501f, 0.000000f, -0.467097f, 0.111375f, 0.083939f, 0.057555f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.422614f, 0.000000f, 0.000000f, -0.129236f, 0.000000f);
    private static final Pose charged_13 = new Pose(0.593412f, -1.082104f, 0.104720f, 0.000000f, -0.383972f, 0.069813f, 0.104720f, 0.052360f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.453786f, 0.000000f, 0.000000f, -0.139626f, 0.000000f);
    private static final Pose charged_14 = new Pose(0.361087f, -0.998467f, 0.067548f, 0.000000f, -0.356093f, 0.055874f, 0.100073f, 0.047713f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.407321f, 0.000000f, 0.000000f, -0.121040f, 0.000000f);
    private static final Pose charged_15 = new Pose(0.174155f, -0.931171f, 0.037639f, 0.000000f, -0.333662f, 0.044658f, 0.096335f, 0.043975f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.369934f, 0.000000f, 0.000000f, -0.106086f, 0.000000f);
    private static final Pose charged_16 = new Pose(0.027681f, -0.878441f, 0.014203f, 0.000000f, -0.316085f, 0.035869f, 0.093405f, 0.041045f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.340639f, 0.000000f, 0.000000f, -0.094368f, 0.000000f);
    private static final Pose charged_17 = new Pose(-0.083268f, -0.838499f, -0.003549f, 0.000000f, -0.302771f, 0.029212f, 0.091186f, 0.038826f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.318450f, 0.000000f, 0.000000f, -0.085492f, 0.000000f);
    private static final Pose charged_18 = new Pose(-0.163626f, -0.809570f, -0.016406f, 0.000000f, -0.293128f, 0.024391f, 0.089579f, 0.037219f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.302378f, 0.000000f, 0.000000f, -0.079063f, 0.000000f);
    private static final Pose charged_19 = new Pose(-0.218328f, -0.789878f, -0.025159f, 0.000000f, -0.286564f, 0.021109f, 0.088485f, 0.036125f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.291438f, 0.000000f, 0.000000f, -0.074687f, 0.000000f);
    private static final Pose charged_20 = new Pose(-0.252307f, -0.777645f, -0.030595f, 0.000000f, -0.282486f, 0.019070f, 0.087805f, 0.035446f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.284642f, 0.000000f, 0.000000f, -0.071969f, 0.000000f);
    private static final Pose charged_21 = new Pose(-0.270496f, -0.771097f, -0.033506f, 0.000000f, -0.280303f, 0.017979f, 0.087442f, 0.035082f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.281004f, 0.000000f, 0.000000f, -0.070514f, 0.000000f);
    private static final Pose charged_22 = new Pose(-0.277832f, -0.768456f, -0.034679f, 0.000000f, -0.279423f, 0.017539f, 0.087295f, 0.034935f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.279537f, 0.000000f, 0.000000f, -0.069927f, 0.000000f);
    private static final Pose charged_23 = new Pose(-0.279246f, -0.767947f, -0.034906f, 0.000000f, -0.279253f, 0.017454f, 0.087267f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.279254f, 0.000000f, 0.000000f, -0.069814f, 0.000000f);
    private static final Pose charged_24 = new Pose(-0.279253f, -0.767945f, -0.028495f, 0.000000f, -0.279253f, 0.014248f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.272841f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_25 = new Pose(-0.279253f, -0.767945f, -0.021566f, 0.000000f, -0.279253f, 0.010783f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.265912f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_26 = new Pose(-0.279253f, -0.767945f, -0.015756f, 0.000000f, -0.279253f, 0.007878f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.260103f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_27 = new Pose(-0.279253f, -0.767945f, -0.010973f, 0.000000f, -0.279253f, 0.005487f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.255319f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_28 = new Pose(-0.279253f, -0.767945f, -0.007124f, 0.000000f, -0.279253f, 0.003562f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.251470f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_29 = new Pose(-0.279253f, -0.767945f, -0.004115f, 0.000000f, -0.279253f, 0.002058f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.248461f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_30 = new Pose(-0.279253f, -0.767945f, -0.001855f, 0.000000f, -0.279253f, 0.000928f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.246201f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_31 = new Pose(-0.279253f, -0.767945f, -0.000250f, 0.000000f, -0.279253f, 0.000125f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.244597f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_32 = new Pose(-0.279253f, -0.767945f, 0.000792f, 0.000000f, -0.279253f, -0.000396f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.243555f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_33 = new Pose(-0.279253f, -0.767945f, 0.001364f, 0.000000f, -0.279253f, -0.000682f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.242983f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_34 = new Pose(-0.279253f, -0.767945f, 0.001558f, 0.000000f, -0.279253f, -0.000779f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.242788f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_35 = new Pose(-0.279253f, -0.767945f, 0.001469f, 0.000000f, -0.279253f, -0.000734f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.242877f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_36 = new Pose(-0.279253f, -0.767945f, 0.001187f, 0.000000f, -0.279253f, -0.000594f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.243159f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_37 = new Pose(-0.279253f, -0.767945f, 0.000807f, 0.000000f, -0.279253f, -0.000403f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.243539f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_38 = new Pose(-0.279253f, -0.767945f, 0.000421f, 0.000000f, -0.279253f, -0.000210f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.243926f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_39 = new Pose(-0.279253f, -0.767945f, 0.000121f, 0.000000f, -0.279253f, -0.000060f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.244226f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);
    private static final Pose charged_40 = new Pose(-0.279253f, -0.767945f, 0.000000f, 0.000000f, -0.279253f, 0.000000f, 0.087266f, 0.034907f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.244346f, 0.000000f, 0.000000f, -0.069813f, 0.000000f);

    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // hit1
                new Keyframe(0.000f, 0, hit1_00),
                new Keyframe(0.025f, 0, hit1_01),
                new Keyframe(0.050f, 0, hit1_02),
                new Keyframe(0.075f, 0, hit1_03),
                new Keyframe(0.100f, 0, hit1_04),
                new Keyframe(0.125f, 0, hit1_05),
                new Keyframe(0.150f, 0, hit1_06),
                new Keyframe(0.175f, 0, hit1_07),
                new Keyframe(0.200f, 0, hit1_08),
                new Keyframe(0.225f, 0, hit1_09),
                new Keyframe(0.250f, 0, hit1_10),
                new Keyframe(0.275f, 0, hit1_11),
                new Keyframe(0.300f, 0, hit1_12),
                new Keyframe(0.325f, 0, hit1_13),
                new Keyframe(0.350f, 0, hit1_14),
                new Keyframe(0.375f, 0, hit1_15),
                new Keyframe(0.400f, 0, hit1_16),
                new Keyframe(0.425f, 0, hit1_17),
                new Keyframe(0.450f, 0, hit1_18),
                new Keyframe(0.475f, 0, hit1_19),
                new Keyframe(0.500f, 0, hit1_20),
                new Keyframe(0.525f, 0, hit1_21),
                new Keyframe(0.550f, 0, hit1_22),
                new Keyframe(0.575f, 0, hit1_23),
                new Keyframe(0.600f, 0, hit1_24),
                new Keyframe(0.625f, 0, hit1_25),
                new Keyframe(0.650f, 0, hit1_26),
                new Keyframe(0.675f, 0, hit1_27),
                new Keyframe(0.700f, 0, hit1_28),
                new Keyframe(0.725f, 0, hit1_29),
                new Keyframe(0.750f, 0, hit1_30),
                new Keyframe(0.775f, 0, hit1_31),
                new Keyframe(0.800f, 0, hit1_32),
                new Keyframe(0.825f, 0, hit1_33),
                new Keyframe(0.850f, 0, hit1_34),
                new Keyframe(0.875f, 0, hit1_35),
                new Keyframe(0.900f, 0, hit1_36),
                new Keyframe(0.925f, 0, hit1_37),
                new Keyframe(0.950f, 0, hit1_38),
                new Keyframe(0.975f, 0, hit1_39),
                new Keyframe(1.000f, 0, hit1_40),
        }),
        new Clip(new Keyframe[] { // hit2
                new Keyframe(0.000f, 0, hit2_00),
                new Keyframe(0.025f, 0, hit2_01),
                new Keyframe(0.050f, 0, hit2_02),
                new Keyframe(0.075f, 0, hit2_03),
                new Keyframe(0.100f, 0, hit2_04),
                new Keyframe(0.125f, 0, hit2_05),
                new Keyframe(0.150f, 0, hit2_06),
                new Keyframe(0.175f, 0, hit2_07),
                new Keyframe(0.200f, 0, hit2_08),
                new Keyframe(0.225f, 0, hit2_09),
                new Keyframe(0.250f, 0, hit2_10),
                new Keyframe(0.275f, 0, hit2_11),
                new Keyframe(0.300f, 0, hit2_12),
                new Keyframe(0.325f, 0, hit2_13),
                new Keyframe(0.350f, 0, hit2_14),
                new Keyframe(0.375f, 0, hit2_15),
                new Keyframe(0.400f, 0, hit2_16),
                new Keyframe(0.425f, 0, hit2_17),
                new Keyframe(0.450f, 0, hit2_18),
                new Keyframe(0.475f, 0, hit2_19),
                new Keyframe(0.500f, 0, hit2_20),
                new Keyframe(0.525f, 0, hit2_21),
                new Keyframe(0.550f, 0, hit2_22),
                new Keyframe(0.575f, 0, hit2_23),
                new Keyframe(0.600f, 0, hit2_24),
                new Keyframe(0.625f, 0, hit2_25),
                new Keyframe(0.650f, 0, hit2_26),
                new Keyframe(0.675f, 0, hit2_27),
                new Keyframe(0.700f, 0, hit2_28),
                new Keyframe(0.725f, 0, hit2_29),
                new Keyframe(0.750f, 0, hit2_30),
                new Keyframe(0.775f, 0, hit2_31),
                new Keyframe(0.800f, 0, hit2_32),
                new Keyframe(0.825f, 0, hit2_33),
                new Keyframe(0.850f, 0, hit2_34),
                new Keyframe(0.875f, 0, hit2_35),
                new Keyframe(0.900f, 0, hit2_36),
                new Keyframe(0.925f, 0, hit2_37),
                new Keyframe(0.950f, 0, hit2_38),
                new Keyframe(0.975f, 0, hit2_39),
                new Keyframe(1.000f, 0, hit2_40),
        }),
        new Clip(new Keyframe[] { // hit3
                new Keyframe(0.000f, 0, hit3_00),
                new Keyframe(0.025f, 0, hit3_01),
                new Keyframe(0.050f, 0, hit3_02),
                new Keyframe(0.075f, 0, hit3_03),
                new Keyframe(0.100f, 0, hit3_04),
                new Keyframe(0.125f, 0, hit3_05),
                new Keyframe(0.150f, 0, hit3_06),
                new Keyframe(0.175f, 0, hit3_07),
                new Keyframe(0.200f, 0, hit3_08),
                new Keyframe(0.225f, 0, hit3_09),
                new Keyframe(0.250f, 0, hit3_10),
                new Keyframe(0.275f, 0, hit3_11),
                new Keyframe(0.300f, 0, hit3_12),
                new Keyframe(0.325f, 0, hit3_13),
                new Keyframe(0.350f, 0, hit3_14),
                new Keyframe(0.375f, 0, hit3_15),
                new Keyframe(0.400f, 0, hit3_16),
                new Keyframe(0.425f, 0, hit3_17),
                new Keyframe(0.450f, 0, hit3_18),
                new Keyframe(0.475f, 0, hit3_19),
                new Keyframe(0.500f, 0, hit3_20),
                new Keyframe(0.525f, 0, hit3_21),
                new Keyframe(0.550f, 0, hit3_22),
                new Keyframe(0.575f, 0, hit3_23),
                new Keyframe(0.600f, 0, hit3_24),
                new Keyframe(0.625f, 0, hit3_25),
                new Keyframe(0.650f, 0, hit3_26),
                new Keyframe(0.675f, 0, hit3_27),
                new Keyframe(0.700f, 0, hit3_28),
                new Keyframe(0.725f, 0, hit3_29),
                new Keyframe(0.750f, 0, hit3_30),
                new Keyframe(0.775f, 0, hit3_31),
                new Keyframe(0.800f, 0, hit3_32),
                new Keyframe(0.825f, 0, hit3_33),
                new Keyframe(0.850f, 0, hit3_34),
                new Keyframe(0.875f, 0, hit3_35),
                new Keyframe(0.900f, 0, hit3_36),
                new Keyframe(0.925f, 0, hit3_37),
                new Keyframe(0.950f, 0, hit3_38),
                new Keyframe(0.975f, 0, hit3_39),
                new Keyframe(1.000f, 0, hit3_40),
        }),
        new Clip(new Keyframe[] { // hit4
                new Keyframe(0.000f, 0, hit4_00),
                new Keyframe(0.025f, 0, hit4_01),
                new Keyframe(0.050f, 0, hit4_02),
                new Keyframe(0.075f, 0, hit4_03),
                new Keyframe(0.100f, 0, hit4_04),
                new Keyframe(0.125f, 0, hit4_05),
                new Keyframe(0.150f, 0, hit4_06),
                new Keyframe(0.175f, 0, hit4_07),
                new Keyframe(0.200f, 0, hit4_08),
                new Keyframe(0.225f, 0, hit4_09),
                new Keyframe(0.250f, 0, hit4_10),
                new Keyframe(0.275f, 0, hit4_11),
                new Keyframe(0.300f, 0, hit4_12),
                new Keyframe(0.325f, 0, hit4_13),
                new Keyframe(0.350f, 0, hit4_14),
                new Keyframe(0.375f, 0, hit4_15),
                new Keyframe(0.400f, 0, hit4_16),
                new Keyframe(0.425f, 0, hit4_17),
                new Keyframe(0.450f, 0, hit4_18),
                new Keyframe(0.475f, 0, hit4_19),
                new Keyframe(0.500f, 0, hit4_20),
                new Keyframe(0.525f, 0, hit4_21),
                new Keyframe(0.550f, 0, hit4_22),
                new Keyframe(0.575f, 0, hit4_23),
                new Keyframe(0.600f, 0, hit4_24),
                new Keyframe(0.625f, 0, hit4_25),
                new Keyframe(0.650f, 0, hit4_26),
                new Keyframe(0.675f, 0, hit4_27),
                new Keyframe(0.700f, 0, hit4_28),
                new Keyframe(0.725f, 0, hit4_29),
                new Keyframe(0.750f, 0, hit4_30),
                new Keyframe(0.775f, 0, hit4_31),
                new Keyframe(0.800f, 0, hit4_32),
                new Keyframe(0.825f, 0, hit4_33),
                new Keyframe(0.850f, 0, hit4_34),
                new Keyframe(0.875f, 0, hit4_35),
                new Keyframe(0.900f, 0, hit4_36),
                new Keyframe(0.925f, 0, hit4_37),
                new Keyframe(0.950f, 0, hit4_38),
                new Keyframe(0.975f, 0, hit4_39),
                new Keyframe(1.000f, 0, hit4_40),
        }),
        new Clip(new Keyframe[] { // hit5
                new Keyframe(0.000f, 0, hit5_00),
                new Keyframe(0.025f, 0, hit5_01),
                new Keyframe(0.050f, 0, hit5_02),
                new Keyframe(0.075f, 0, hit5_03),
                new Keyframe(0.100f, 0, hit5_04),
                new Keyframe(0.125f, 0, hit5_05),
                new Keyframe(0.150f, 0, hit5_06),
                new Keyframe(0.175f, 0, hit5_07),
                new Keyframe(0.200f, 0, hit5_08),
                new Keyframe(0.225f, 0, hit5_09),
                new Keyframe(0.250f, 0, hit5_10),
                new Keyframe(0.275f, 0, hit5_11),
                new Keyframe(0.300f, 0, hit5_12),
                new Keyframe(0.325f, 0, hit5_13),
                new Keyframe(0.350f, 0, hit5_14),
                new Keyframe(0.375f, 0, hit5_15),
                new Keyframe(0.400f, 0, hit5_16),
                new Keyframe(0.425f, 0, hit5_17),
                new Keyframe(0.450f, 0, hit5_18),
                new Keyframe(0.475f, 0, hit5_19),
                new Keyframe(0.500f, 0, hit5_20),
                new Keyframe(0.525f, 0, hit5_21),
                new Keyframe(0.550f, 0, hit5_22),
                new Keyframe(0.575f, 0, hit5_23),
                new Keyframe(0.600f, 0, hit5_24),
                new Keyframe(0.625f, 0, hit5_25),
                new Keyframe(0.650f, 0, hit5_26),
                new Keyframe(0.675f, 0, hit5_27),
                new Keyframe(0.700f, 0, hit5_28),
                new Keyframe(0.725f, 0, hit5_29),
                new Keyframe(0.750f, 0, hit5_30),
                new Keyframe(0.775f, 0, hit5_31),
                new Keyframe(0.800f, 0, hit5_32),
                new Keyframe(0.825f, 0, hit5_33),
                new Keyframe(0.850f, 0, hit5_34),
                new Keyframe(0.875f, 0, hit5_35),
                new Keyframe(0.900f, 0, hit5_36),
                new Keyframe(0.925f, 0, hit5_37),
                new Keyframe(0.950f, 0, hit5_38),
                new Keyframe(0.975f, 0, hit5_39),
                new Keyframe(1.000f, 0, hit5_40),
        }),
        new Clip(new Keyframe[] { // charged
                new Keyframe(0.000f, 0, charged_00),
                new Keyframe(0.025f, 0, charged_01),
                new Keyframe(0.050f, 0, charged_02),
                new Keyframe(0.075f, 0, charged_03),
                new Keyframe(0.100f, 0, charged_04),
                new Keyframe(0.125f, 0, charged_05),
                new Keyframe(0.150f, 0, charged_06),
                new Keyframe(0.175f, 0, charged_07),
                new Keyframe(0.200f, 0, charged_08),
                new Keyframe(0.225f, 0, charged_09),
                new Keyframe(0.250f, 0, charged_10),
                new Keyframe(0.275f, 0, charged_11),
                new Keyframe(0.300f, 0, charged_12),
                new Keyframe(0.325f, 0, charged_13),
                new Keyframe(0.350f, 0, charged_14),
                new Keyframe(0.375f, 0, charged_15),
                new Keyframe(0.400f, 0, charged_16),
                new Keyframe(0.425f, 0, charged_17),
                new Keyframe(0.450f, 0, charged_18),
                new Keyframe(0.475f, 0, charged_19),
                new Keyframe(0.500f, 0, charged_20),
                new Keyframe(0.525f, 0, charged_21),
                new Keyframe(0.550f, 0, charged_22),
                new Keyframe(0.575f, 0, charged_23),
                new Keyframe(0.600f, 0, charged_24),
                new Keyframe(0.625f, 0, charged_25),
                new Keyframe(0.650f, 0, charged_26),
                new Keyframe(0.675f, 0, charged_27),
                new Keyframe(0.700f, 0, charged_28),
                new Keyframe(0.725f, 0, charged_29),
                new Keyframe(0.750f, 0, charged_30),
                new Keyframe(0.775f, 0, charged_31),
                new Keyframe(0.800f, 0, charged_32),
                new Keyframe(0.825f, 0, charged_33),
                new Keyframe(0.850f, 0, charged_34),
                new Keyframe(0.875f, 0, charged_35),
                new Keyframe(0.900f, 0, charged_36),
                new Keyframe(0.925f, 0, charged_37),
                new Keyframe(0.950f, 0, charged_38),
                new Keyframe(0.975f, 0, charged_39),
                new Keyframe(1.000f, 0, charged_40),
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
