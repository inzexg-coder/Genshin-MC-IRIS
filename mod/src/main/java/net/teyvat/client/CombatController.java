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
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.teyvat.particle.TeyvatSlashEffect;
import net.teyvat.particle.TeyvatParticles;
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
     *  (~0.2 сек). Быстрый тап (отпустили раньше) — обычный удар комбо. */
    private static final int CHARGE_START_TICKS = 4;

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

    // --- Визуал заряженной атаки: растущая сфера в руке, разлёт сферы на
    // отпускании и «шерстяная дуга» по поверхности (1..6 оборотов). ---

    /** Сфера заряда в руке (у клинка): центр, радиус, альфа. */
    public record ChargeSphere(Vec3d center, float radius, float alpha) {}
    private static Vec3d chargeSphereCenter = Vec3d.ZERO;
    private static float chargeSphereRadius = 0f;
    private static float chargeSphereAlpha = 0f;

    /** Разлёт сферы вокруг игрока на отпускании (частицы): сколько тиков
     *  идёт разлёт, текущий радиус, альфа по уровню заряда. */
    private static int burstTicks = -1;
    private static float burstRadius = 0f;
    private static float burstAlpha = 0f;

    /** Шерстяная дуга (частицы по поверхности сферы): 1..6 оборотов. */
    private static Vec3d wrapCenter = Vec3d.ZERO;
    private static float wrapRadius = 0f;
    private static float wrapTurns = 1f;
    private static float wrapTiltYaw = 0f;
    private static float wrapTiltPitch = 0f;
    private static int wrapDrawn = 0;
    private static int wrapTotal = 0;

    /** Предыдущая точка кончика клинка (для цепочки дуг за мечом в вихре). */
    private static Vec3d lastWhirlBlade = null;

    /** Активная сфера заряда (пустая, если заряда нет). */
    public static ChargeSphere chargeSphere() {
        if (chargeSphereRadius <= 0f) {
            return null;
        }
        return new ChargeSphere(chargeSphereCenter, chargeSphereRadius, chargeSphereAlpha);
    }

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
        if (!charging) {
            // Сфера заряда живёт только пока копим; на спине её сменяет разлёт.
            chargeSphereRadius = 0f;
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
                // атомов»). Длится ровно 1 секунду (DURATION_TICKS[5] = 20),
                // в течение которой любые действия запрещены; после — серия
                // сброшена и плавный выход.
                hitTicks++;
                if (hitTicks == 1) {
                    applyStep(client.player);
                }
                // Вихрь: серпы и граница шара — «удары лезвием в секунду».
                spawnWhirlwindEffects(client, client.player);
                // Разлёт сферы (частицы): первые тики — расширяющаяся оболочка
                // искр вокруг игрока; плотность зависит от уровня заряда.
                if (burstTicks >= 0) {
                    burstTicks++;
                    burstRadius = 0.8f + (wrapRadius - 0.8f) * Math.min(1f, burstTicks / 4f);
                    int shell = (int) (10 + 18 * burstAlpha);
                    for (int i = 0; i < shell; i++) {
                        double theta = client.world.random.nextDouble() * Math.PI * 2.0;
                        double phi = Math.acos(2.0 * client.world.random.nextDouble() - 1.0);
                        client.world.addParticleClient(ParticleTypes.CRIT,
                                client.player.getX() + burstRadius * Math.sin(phi) * Math.cos(theta),
                                client.player.getY() + 1.0 + burstRadius * Math.cos(phi),
                                client.player.getZ() + burstRadius * Math.sin(phi) * Math.sin(theta),
                                0, 0, 0);
                    }
                    if (burstTicks > 5) {
                        burstTicks = -1;
                    }
                }
                // Шерстяная дуга (частицы): молниеносно летит по поверхности
                // сферы (1..6 оборотов), лёгкий снос наружу — нити клубка.
                wrapCenter = new Vec3d(client.player.getX(), client.player.getY() + 1.0, client.player.getZ());
                int perTick = Math.max(1, (wrapTotal + 19) / 20);
                int target = Math.min(wrapTotal, wrapDrawn + perTick);
                while (wrapDrawn < target) {
                    Vec3d wp = wrapPoint(wrapDrawn);
                    Vec3d out = wp.subtract(wrapCenter).normalize().multiply(0.03);
                    client.world.addParticleClient(ParticleTypes.END_ROD,
                            wp.x, wp.y, wp.z, out.x, out.y + 0.02, out.z);
                    if (wrapDrawn % 3 == 0) {
                        client.world.addParticleClient(ParticleTypes.CRIT,
                                wp.x, wp.y, wp.z, out.x, out.y + 0.02, out.z);
                    }
                    wrapDrawn++;
                }
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
                    sentHit = false;
                    bufferedNext = false;
                    comboStep = -1;
                    exitBlendTicks = EXIT_BLEND_TICKS;
                    wrapDrawn = 0;
                    wrapTotal = 0;
                    burstTicks = -1;
                    lastWhirlBlade = null;
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
                // След-серп каждый тик: квад у текущей позиции клинка.
                spawnTickSlash(client, client.player);
                // Кинокамера-орбита (/cinema orbit): герой доворачивается к ближайшему
                // врагу, чтобы каждый удар шёл в его сторону — для съёмки боя со стороны.
                faceNearestEnemyDuringCinema(client);
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
        if (isChargedAttackActive()) {
            // Спин заряженной атаки: клики глотаются — ни удар, ни буфер, ни заряд.
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
        exitBlendTicks = 0;
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
        // Разлёт сферы вокруг игрока: резкое расширение в первые тики спина,
        // прозрачность зависит от длины удержания (уровня заряда).
        burstTicks = 0;
        burstAlpha = 0.25f + 0.65f * level;
        burstRadius = 0.8f;
        // Шерстяная дуга по поверхности сферы: 1..6 оборотов (от удержания),
        // «плетение» по широте и случайный наклон — витки не наслаиваются.
        wrapCenter = new Vec3d(client.player.getX(), client.player.getY() + 1.0, client.player.getZ());
        wrapRadius = (float) (1.6 + 0.8 * level);
        wrapTurns = 1f + 5f * level;
        wrapTiltYaw = client.world.random.nextFloat() * (float) Math.PI * 2.0f;
        wrapTiltPitch = (client.world.random.nextFloat() - 0.5f) * 1.1f;
        wrapTotal = (int) (wrapTurns * 48f);
        wrapDrawn = 0;
        lastWhirlBlade = null;
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
        // Растущая светлая сфера В КИСТЯХ, где меч (не на кончике клинка):
        // центр — origin предмета в руке, радиус компактный, яркость растёт
        // с зарядом.
        chargeSphereRadius = 0.10f + 0.34f * level;
        chargeSphereAlpha = 0.55f + 0.45f * level;
        chargeSphereCenter = bladeHandWorld(CHARGE_POSE, currentRootYawRad(),
                player.getBodyYaw(), new Vec3d(player.getX(), player.getY(), player.getZ()));
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

    /** Вихрь заряженного спина: компактный ПЛОТНЫЙ шар «ударов лезвием»
     *  вокруг героя — меньший радиус, но десятки серпов и искр на каждый
     *  кадр (граница шара + концентрические слои). Радиус зависит от
     *  уровня заряда, но всегда заметно меньше исходной сферы. */
    private static void spawnWhirlwindEffects(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        float level = chargeLevel;
        double r = 1.6 + 0.8 * level;
        Vec3d center = new Vec3d(player.getX(), player.getY() + 1.0, player.getZ());
        // Серп-«хвост за клинком»: маленькая дуга-частица РОВНО по траектории
        // кончика меча (удары совпадают с мечом — техника Better Combat).
        float p = Math.min(1f, hitTicks / (float) SwordCombo.DURATION_TICKS[SwordCombo.CHARGE_INDEX]);
        Vec3d slashPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d tip = bladeTipWorld(computePose(p), chargedSpinTurn(p), player.getBodyYaw(), slashPos);
        if (lastWhirlBlade != null && lastWhirlBlade.squaredDistanceTo(tip) > 1.0e-6) {
            spawnSlashArc(client.world, TeyvatParticles.SLASH_90,
                    new Vec3d[]{lastWhirlBlade, tip}, 2.6f, 0x88FFFFFF);
        }
        lastWhirlBlade = tip;
        // Орбиты-атомы: плотный шар из КОЛЕЦ-дуг (360°) в случайных плоскостях
        // вокруг тела — граница вихря читается как светящаяся сфера из лезвий.
        int orbits = (int) (2 + 2 * level);
        for (int i = 0; i < orbits; i++) {
            Vec3d rnd = new Vec3d(
                    client.world.random.nextDouble() - 0.5,
                    client.world.random.nextDouble() - 0.5,
                    client.world.random.nextDouble() - 0.5);
            if (rnd.lengthSquared() < 1.0e-6) {
                rnd = new Vec3d(1.0, 0.0, 0.0);
            }
            double rr = r * (0.72 + client.world.random.nextDouble() * 0.55);
            spawnOrbitArc(client.world, TeyvatParticles.SLASH_360,
                    center, rnd, (float) rr, 0x99FFFFFF);
        }
        // Полупрозрачная граница шара: плотный слой искр на сфере.
        for (int i = 0; i < 48; i++) {
            double theta = client.world.random.nextDouble() * Math.PI * 2.0;
            double phi = Math.acos(2.0 * client.world.random.nextDouble() - 1.0);
            client.world.addParticleClient(ParticleTypes.CRIT,
                    player.getX() + r * Math.sin(phi) * Math.cos(theta),
                    player.getY() + 1.0 + r * Math.cos(phi) * 0.75,
                    player.getZ() + r * Math.sin(phi) * Math.sin(theta),
                    0, 0, 0);
        }
        // Кольца по земле: две орбиты искр (внешняя и внутренняя).
        for (int i = 0; i < 24; i++) {
            double a = i / 24.0 * Math.PI * 2.0;
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() + Math.cos(a) * r,
                    player.getY() + 0.1,
                    player.getZ() + Math.sin(a) * r,
                    Math.cos(a) * 0.5, 0.4, Math.sin(a) * 0.5);
            client.world.addParticleClient(ParticleTypes.END_ROD,
                    player.getX() + Math.cos(a + 0.4) * r * 0.6,
                    player.getY() + 0.35,
                    player.getZ() + Math.sin(a + 0.4) * r * 0.6,
                    Math.cos(a + 0.4) * 0.35, 0.5, Math.sin(a + 0.4) * 0.35);
        }
    }

    /** Прогресс заряда 0..1 (для индикатора на дуге стамины). */
    public static float chargeProgress() {
        if (!charging) {
            return 0f;
        }
        return MathHelper.clamp(chargeTicks / (float) SwordCombo.FULL_CHARGE_TICKS, 0f, 1f);
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

    /** Спавн дуги-частицы по плоскости фактического пути клинка (path):
     *  строим ортонормированный базис (sweep, bow, normal), дуга выгибается
     *  к камере — читается из любого ракурса, в 1-м и 3-м лице. */
    private static void spawnSlashArc(ClientWorld world, ParticleType<TeyvatSlashEffect> type,
                                      Vec3d[] path, float scale, int color) {
        if (world == null || path.length < 2) {
            return;
        }
        Vec3d p0 = path[0];
        Vec3d pMid = path[path.length / 2];
        Vec3d pEnd = path[path.length - 1];
        Vec3d v1 = pMid.subtract(p0);
        Vec3d v2 = pEnd.subtract(p0);
        Vec3d n = v1.crossProduct(v2);
        if (n.lengthSquared() < 1.0e-10) {
            n = v2.normalize().crossProduct(new Vec3d(0.0, 1.0, 0.0));
        }
        n = n.normalize();
        Vec3d sweep = v2.normalize();
        Vec3d bow = n.crossProduct(sweep).normalize();
        Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
        if (bow.dotProduct(cam.subtract(pMid)) < 0.0) {
            bow = bow.multiply(-1.0);
        }
        Quaternionf q = basisQuat(sweep, bow, n);
        world.addParticleClient(new TeyvatSlashEffect(type, q.x, q.y, q.z, q.w, scale, color, true),
                pMid.x, pMid.y, pMid.z, 0.0, 0.0, 0.0);
    }

    /** Параметры дуги-серпа обычного удара (техника Better Combat): угол серпа
     *  (тип частицы), радиус кольца и наклоны в градусах (pitch/roll/localYaw)
     *  — плоскость дуги подобрана под каждое движение клинка. */
    private record SlashArcSpec(ParticleType<TeyvatSlashEffect> type, float radius,
                                float pitchDeg, float rollDeg, float localYawDeg, double centerY) {}

    /** Дуга-серп обычного удара: кольцо вокруг игрока радиусом ~дистанции
     *  атаки, держится за поворот игрока (yaw), плоскость задаётся
     *  pitch/roll/localYaw на каждый удар — серп «рассекает воздух» ровно
     *  в сторону движения меча (как в Better Combat). */
    private static SlashArcSpec slashArcSpec(int step) {
        return switch (step) {
            // 1: горизонтальный слева направо — полукруг через фронт.
            case 0 -> new SlashArcSpec(TeyvatParticles.SLASH_180, 2.9f, 0f, 0f, 90f, 1.05);
            // 2: апперкот — диагональ вверх-влево за плечо.
            case 1 -> new SlashArcSpec(TeyvatParticles.SLASH_90, 2.6f, -45f, 0f, 60f, 1.15);
            // 3: разворот на 360° — полное кольцо вокруг тела.
            case 2 -> new SlashArcSpec(TeyvatParticles.SLASH_360, 2.4f, 0f, 0f, 0f, 1.0);
            // 4: горизонтальный справа налево — полукруг через фронт (зеркало 1).
            case 3 -> new SlashArcSpec(TeyvatParticles.SLASH_180, 3.0f, 0f, 180f, 90f, 1.05);
            // 5: финальный очень широкий слева направо — 3/4 кольца.
            case 4 -> new SlashArcSpec(TeyvatParticles.SLASH_270, 3.7f, 0f, 0f, 90f, 1.0);
            default -> new SlashArcSpec(TeyvatParticles.SLASH_180, 2.9f, 0f, 0f, 90f, 1.05);
        };
    }

    /** Спавн дуги-серпа вокруг игрока (Better Combat): квад ставится по
     *  кватерниону Ry(-yaw)*Rx(pitch+90)*Ry(roll)*Rz(localYaw). */
    private static void spawnPlayerArc(ClientWorld world, ClientPlayerEntity player, int step) {
        if (world == null) {
            return;
        }
        SlashArcSpec spec = slashArcSpec(step);
        Quaternionf q = slashQuat(player.getYaw(), spec.pitchDeg(), spec.rollDeg(), spec.localYawDeg());
        world.addParticleClient(new TeyvatSlashEffect(spec.type(), q.x, q.y, q.z, q.w,
                        spec.radius() / 0.36f, 0xFFFFFFFF, true),
                player.getX(), player.getY() + spec.centerY(), player.getZ(), 0.0, 0.0, 0.0);
    }

    /** Кватернион дуги-серпа (техника Better Combat):
     *  Ry(-yaw) * Rx(pitch+90) * Ry(roll) * Rz(localYaw). */
    private static Quaternionf slashQuat(float yawDeg, float pitchDeg, float rollDeg, float localYawDeg) {
        Matrix4f m = new Matrix4f();
        m.identity();
        m.rotateY((float) Math.toRadians(-yawDeg));
        m.rotateX((float) Math.toRadians(pitchDeg + 90.0f));
        m.rotateY((float) Math.toRadians(rollDeg));
        m.rotateZ((float) Math.toRadians(localYawDeg));
        return new Quaternionf().setFromNormalized(m);
    }

    /** Спавн КОЛЬЦА-дуги (360°) в плоскости с нормалью normal вокруг центра:
     *  орбита «атома» — для заряженного спина и вихря. Радиус кольца
     *  задаётся scale через spawnOrbitArc. */
    private static void spawnOrbitArc(ClientWorld world, ParticleType<TeyvatSlashEffect> type,
                                      Vec3d center, Vec3d normal, float radius, int color) {
        if (world == null) {
            return;
        }
        Vec3d n = normal.normalize();
        Vec3d ref = Math.abs(n.y) < 0.9 ? new Vec3d(0.0, 1.0, 0.0) : new Vec3d(1.0, 0.0, 0.0);
        Vec3d xAxis = n.crossProduct(ref).normalize();
        Vec3d yAxis = n.crossProduct(xAxis).normalize();
        Quaternionf q = basisQuat(xAxis, yAxis, n);
        // Кольцо радиусом radius: квад 360° ставится по диаметру — размер
        // квада = 2 * радиус / 0.72 (радиус дуги в текстуре ≈ 0.36 размера).
        float scale = radius / 0.36f;
        world.addParticleClient(new TeyvatSlashEffect(type, q.x, q.y, q.z, q.w, scale, color, true),
                center.x, center.y, center.z, 0.0, 0.0, 0.0);
    }

    /** Кватернион поворота из ортонормированного базиса (x, y, z) — так
     *  локальная ось X квада ложится вдоль x, ось Y — вдоль y. */
    private static Quaternionf basisQuat(Vec3d x, Vec3d y, Vec3d z) {
        Matrix4f m = new Matrix4f();
        m.m00((float) x.x);
        m.m01((float) x.y);
        m.m02((float) x.z);
        m.m10((float) y.x);
        m.m11((float) y.y);
        m.m12((float) y.z);
        m.m20((float) z.x);
        m.m21((float) z.y);
        m.m22((float) z.z);
        return new Quaternionf().setFromNormalized(m);
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
        // Обычный удар — ОДИН длинный толстый серп по траектории клинка;
        // заряженный спин — короткие толстые дуги-орбиты (стена лезвий).
        float rootYaw = currentRootYawRad();
        Vec3d slashPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (charged) {
            // Вспышка урона спина: плотный шар КОЛЕЦ-дуг вокруг тела (орбиты
            // атомов) — «стена лезвий» по всей площади спина.
            Vec3d ctr = new Vec3d(player.getX(), player.getY() + 1.0, player.getZ());
            for (int i = 0; i < 6; i++) {
                Vec3d rnd = new Vec3d(
                        world.random.nextDouble() - 0.5,
                        world.random.nextDouble() - 0.5,
                        world.random.nextDouble() - 0.5);
                if (rnd.lengthSquared() < 1.0e-6) {
                    rnd = new Vec3d(1.0, 0.0, 0.0);
                }
                spawnOrbitArc(world, TeyvatParticles.SLASH_360, ctr, rnd, 1.9f, 0xAAFFFFFF);
            }
        } else {
            // Вспышка-серп на тике урона: крупный яркий серп по траектории клинка.
            spawnPlayerArc(world, player, comboStep);
        }
        // Дуга размаха: плотные штрихи-искры вдоль траектории меча.
        // У заряженного спина — полный круг (орбита вокруг тела).
        int count = 16;
        double radius = charged ? 1.9 : 2.4;
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
        // Кольцо «орбит» заряженного спина: искры по кругу вокруг героя
        // (радиус под компактную плотную сферу вихря).
        if (charged) {
            for (int i = 0; i < 24; i++) {
                double a = i / 24.0 * Math.PI * 2.0;
                world.addParticleClient(ParticleTypes.END_ROD,
                        player.getX() + Math.cos(a) * 2.2,
                        player.getY() + 0.2,
                        player.getZ() + Math.sin(a) * 2.2,
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

    /** Спавн кусочка следа-серпа КАЖДЫЙ тик во время свинга.
     *  Квад ставится по当前位置 клинка (bladeTipWorld), ориентирован
     *  по направлению движения лезвия — получается непрерывный след. */
    private static void spawnTickSlash(MinecraftClient client, ClientPlayerEntity player) {
        ClientWorld world = client.world;
        if (world == null || comboStep < 0 || comboStep == SwordCombo.CHARGE_INDEX) {
            return;
        }
        int duration = SwordCombo.DURATION_TICKS[comboStep];
        float progress = (float) hitTicks / duration;
        // Позиция клинка в текущий тик
        float rootYaw = currentRootYawRad();
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d tipCurrent = bladeTipWorld(computePose(progress), rootYaw, player.getBodyYaw(), playerPos);
        // Позиция клинка чуть раньше (для направления)
        float prevProgress = Math.max(0f, progress - 0.08f);
        Vec3d tipPrev = bladeTipWorld(computePose(prevProgress), rootYaw, player.getBodyYaw(), playerPos);
        Vec3d delta = tipCurrent.subtract(tipPrev);
        double len = delta.length();
        if (len < 1.0e-4) {
            return;
        }
        Vec3d dir = delta.normalize();
        // Ориентация квада: нормаль плоскости = cross(dir, cameraDir)
        Vec3d cam = client.gameRenderer.getCamera().getPos();
        Vec3d toCam = cam.subtract(tipCurrent).normalize();
        Vec3d normal = dir.crossProduct(toCam);
        if (normal.lengthSquared() < 1.0e-6) {
            normal = dir.crossProduct(new Vec3d(0, 1, 0));
        }
        normal = normal.normalize();
        Vec3d up = normal.crossProduct(dir).normalize();
        Quaternionf q = basisQuat(dir, up, normal);
        // Масштаб: мелкий в начале/конце, нормальный в середине
        float scaleMult = (progress < 0.2f) ? progress / 0.2f
                        : (progress > 0.8f) ? (1.0f - progress) / 0.2f
                        : 1.0f;
        float scale = 2.0f * scaleMult;
        // Спавним серп
        world.addParticleClient(
                new TeyvatSlashEffect(TeyvatParticles.SLASH_90, q.x, q.y, q.z, q.w,
                        scale, 0xCCFFFFFF, true),
                tipCurrent.x, tipCurrent.y, tipCurrent.z,
                0.0, 0.0, 0.0);
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
        // Искры в точке контакта каждой задетой цели.
        for (int id : entityIds) {
            Entity target = world.getEntityById(id);
            if (target == null) {
                continue;
            }
            Vec3d p = target.getBoundingBox().getCenter();
            // Искры-брызги в сторону удара (направление текущего движения клинка).
            Vec3d dir = hitDirection();
            for (int i = 0; i < 10; i++) {
                double vx = (world.random.nextDouble() - 0.5) * 0.9 + dir.x * 0.7;
                double vy = world.random.nextDouble() * 1.5 + 0.2 + dir.y * 0.5;
                double vz = (world.random.nextDouble() - 0.5) * 0.9 + dir.z * 0.7;
                world.addParticleClient(ParticleTypes.CRIT, p.x, p.y, p.z, vx, vy, vz);
            }
            world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, 0, 0.5, 0);
            // Маленькая дуга-вспышка в точке контакта — строго на хите.
            Vec3d a = p.subtract(dir.multiply(0.6));
            Vec3d b = p.add(dir.multiply(0.6));
            spawnSlashArc(world, TeyvatParticles.SLASH_45, new Vec3d[]{a, p, b}, 2.4f, 0x88FFFFFF);
        }
    }

    /** Направление текущего удара в мире (для искр/вспышки на хите):
     *  от начала замаха к сопровождению по bladeTipWorld. */
    private static Vec3d hitDirection() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || comboStep < 0) {
            return new Vec3d(0.0, 0.2, 1.0);
        }
        Vec3d slashPos = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        float rootYaw = currentRootYawRad();
        Vec3d a = bladeTipWorld(computePose(0.06f), rootYaw, client.player.getBodyYaw(), slashPos);
        Vec3d b = bladeTipWorld(computePose(0.5f), rootYaw, client.player.getBodyYaw(), slashPos);
        Vec3d d = b.subtract(a);
        return d.lengthSquared() < 1.0e-8 ? new Vec3d(0.0, 0.2, 1.0) : d.normalize();
    }

    /** Атака идёт прямо сейчас (включая хит-стоп и фазу восстановления). */
    public static boolean isAttacking() {
        return comboStep >= 0 || finalCooldownTicks > 0;
    }

    /** Заряженный спин идёт прямо сейчас (1 секунда, DURATION_TICKS[5]): в это
     *  время запрещены любые действия — удары, рывок, прыжок, движение. */
    public static boolean isChargedAttackActive() {
        return comboStep == SwordCombo.CHARGE_INDEX;
    }

    /** Dash-cancel: рывок прерывает анимацию атаки, как в Genshin. Серия
     *  комбо при этом сохраняется (lastStep) — следующий клик продолжит цепочку.
     *  Заряженный спин рывком не прерывается (блок на всю секунду). */
    public static boolean tryCancelByDash() {
        if (isChargedAttackActive()) {
            return false;
        }
        return cancelAttack();
    }

    /** Jump-cancel: прыжок прерывает атаку (как в Genshin). Заряженный спин
     *  прыжком не прерывается — герой «заперт» на секунду спина. */
    public static boolean tryCancelByJump() {
        if (isChargedAttackActive()) {
            return false;
        }
        return cancelAttack();
    }

    /** Общая отмена атаки: сбрасываем удар в нейтраль с плавным выходом.
     *  Так же прерывает заряд (dash/jump-cancel заряженной атаки).
     *  Возвращает true, если что-то было отменено. */
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
        chargeSphereRadius = 0f;
        burstTicks = -1;
        wrapDrawn = 0;
        wrapTotal = 0;
        lastWhirlBlade = null;
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
        float w = MathHelper.clamp(chargeTicks / 6f, 0f, 1f);
        Pose base = hasPrevAppliedPose ? prevAppliedPose : CHARGE_POSE;
        Pose pose = mix(base, CHARGE_POSE, ease(E_IN_OUT_SINE, w));
        pose = new Pose(
                pose.rYaw() + sway * 0.01f,
                pose.rPitch() + breath * 0.012f,
                pose.rRoll() + sway * 0.008f,
                pose.lYaw(),
                pose.lPitch() + sway * 0.008f,
                pose.lRoll(),
                pose.bYaw() + sway * 0.02f,
                pose.bPitch() + breath * 0.02f,
                pose.bRoll(),
                Float.NaN, Float.NaN, Float.NaN,
                pose.rlYaw(),
                pose.rlPitch() + sway * 0.006f,
                pose.rlRoll(),
                pose.llYaw(),
                pose.llPitch() - sway * 0.006f,
                pose.llRoll());
        applyPoseToModel(model, pose);
        model.getRootPart().yaw = sway * 0.03f;
        model.getRootPart().pitch = breath * 0.01f;
        model.getRootPart().originY = 0f;
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
            // Idle: Genshin idle animation (8.333s cycle)
            float t = (state.age % 166.7f) / 166.7f;
            model.rightArm.yaw = LocomotionClips.sample_idle_rYaw(t);
            model.rightArm.pitch = LocomotionClips.sample_idle_rPitch(t);
            model.rightArm.roll = LocomotionClips.sample_idle_rRoll(t);
            model.leftArm.yaw = LocomotionClips.sample_idle_lYaw(t);
            model.leftArm.pitch = LocomotionClips.sample_idle_lPitch(t);
            model.leftArm.roll = LocomotionClips.sample_idle_lRoll(t);
            model.body.yaw = LocomotionClips.sample_idle_bYaw(t);
            model.body.pitch = LocomotionClips.sample_idle_bPitch(t);
            model.body.roll = LocomotionClips.sample_idle_bRoll(t);
            model.rightLeg.yaw = LocomotionClips.sample_idle_rlYaw(t);
            model.rightLeg.pitch = LocomotionClips.sample_idle_rlPitch(t);
            model.rightLeg.roll = LocomotionClips.sample_idle_rlRoll(t);
            model.leftLeg.yaw = LocomotionClips.sample_idle_llYaw(t);
            model.leftLeg.pitch = LocomotionClips.sample_idle_llPitch(t);
            model.leftLeg.roll = LocomotionClips.sample_idle_llRoll(t);
            model.getRootPart().originY = 0f;
            return;
        }

        // Choose animation based on movement speed
        float ph = state.limbSwingAnimationProgress * 0.6662f;
        float p;
        if (move > 0.8f) {
            p = (state.age % 14.0f) / 14.0f;
        } else if (move > 0.4f) {
            p = (state.age % 16.7f) / 16.7f;
        } else {
            p = (state.age % 31.3f) / 31.3f;
        }

        // Sample run locomotion (walk/run/sprint share similar motion)
        model.rightArm.yaw = LocomotionClips.sample_run_rYaw(p);
        model.rightArm.pitch = LocomotionClips.sample_run_rPitch(p);
        model.rightArm.roll = LocomotionClips.sample_run_rRoll(p);
        model.leftArm.yaw = LocomotionClips.sample_run_lYaw(p);
        model.leftArm.pitch = LocomotionClips.sample_run_lPitch(p);
        model.leftArm.roll = LocomotionClips.sample_run_lRoll(p);
        model.body.yaw = LocomotionClips.sample_run_bYaw(p);
        model.body.pitch = LocomotionClips.sample_run_bPitch(p);
        model.body.roll = LocomotionClips.sample_run_bRoll(p);
        model.rightLeg.yaw = LocomotionClips.sample_run_rlYaw(p);
        model.rightLeg.pitch = LocomotionClips.sample_run_rlPitch(p);
        model.rightLeg.roll = LocomotionClips.sample_run_rlRoll(p);
        model.leftLeg.yaw = LocomotionClips.sample_run_llYaw(p);
        model.leftLeg.pitch = LocomotionClips.sample_run_llPitch(p);
        model.leftLeg.roll = LocomotionClips.sample_run_llRoll(p);
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
        lastCombatRootYaw = model.getRootPart().yaw;
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
        return comboStep >= 0 && recoveryTicks == 0;
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
        return bladeItemPoint(pose, rootYawRad, bodyYawDeg, playerPos, 0f, 1f, 0f);
    }

    /** Точка КИСТИ (origin предмета в руке) в мировых координатах — для сферы
     *  заряда «в руках, где меч» (а не на кончике клинка). */
    public static Vec3d bladeHandWorld(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos) {
        return bladeItemPoint(pose, rootYawRad, bodyYawDeg, playerPos, 0f, 0f, 0f);
    }

    /** Промаршировать точку (lx, ly, lz) предмета через ту же цепочку, что у
     *  видимого меча: плечо->рука->display handheld->грип->лезвие. */
    private static Vec3d bladeItemPoint(Pose pose, float rootYawRad, float bodyYawDeg, Vec3d playerPos,
                                        float lx, float ly, float lz) {
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
        Vector3f tip = m.transformPosition(new Vector3f(lx, ly, lz), new Vector3f());
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

    /** Точка «шерстяной дуги» на сфере спина: θ — долгота (1..6 оборотов),
     *  φ — широта с некратным «плетением» (0.618 — золотое сечение), чтобы
     *  витки не ложились друг на друга; вся «сфера параллелей» наклонена
     *  на случайный угол — как нити шерстяного клубка. */
    private static Vec3d wrapPoint(int i) {
        double t = i / (double) Math.max(1, wrapTotal);
        double theta = Math.PI * 2.0 * wrapTurns * t;
        double phi = 0.9 * Math.sin(theta * 0.618);
        double x0 = Math.cos(phi) * Math.cos(theta);
        double y0 = Math.sin(phi);
        double z0 = Math.cos(phi) * Math.sin(theta);
        double cy = Math.cos(wrapTiltYaw);
        double sy = Math.sin(wrapTiltYaw);
        double cx = Math.cos(wrapTiltPitch);
        double sx = Math.sin(wrapTiltPitch);
        double x = x0 * cy + z0 * sy;
        double z = -x0 * sy + z0 * cy;
        double y = y0 * cx - z * sx;
        z = y0 * sx + z * cx;
        return wrapCenter.add(x * wrapRadius, y * wrapRadius, z * wrapRadius);
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

    // ═══════════════════════════════════════════════════════════════
    // Mixamo 'Great Sword' motion-capture animations
    // Source: Mixamo Great Sword Pack → converted via mixamo_final_converter.py
    // ═══════════════════════════════════════════════════════════════

    private static final Pose hit1_00 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, -0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_01 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.136202f, 0.040319f, -0.263205f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_02 = new Pose(-0.000000f, -0.031305f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.437828f, 0.090597f, -0.274565f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_03 = new Pose(-0.000000f, -0.146449f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.451858f, 0.157473f, -0.195464f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_04 = new Pose(0.000000f, -0.155239f, -0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.503778f, 0.035257f, 0.157011f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_05 = new Pose(0.000000f, -0.058167f, -0.000000f, 0.000000f, -0.523599f, -0.087266f, -0.038017f, -0.087354f, 0.152631f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_06 = new Pose(0.000000f, -0.000476f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.512817f, 0.039532f, 0.354839f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_07 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.627862f, 0.008509f, 0.141684f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_08 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.578953f, -0.043212f, -0.308125f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_09 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.563943f, -0.068238f, -0.366170f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_10 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.470226f, -0.025933f, -0.301701f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit1_11 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, -0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit2_00 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_01 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.389159f, -0.014930f, -0.309405f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_02 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.010381f, -0.002493f, 0.092838f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_03 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.498570f, -0.024370f, 0.371367f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_04 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.488914f, -0.022857f, 0.503684f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_05 = new Pose(-0.129891f, 0.137950f, 0.171320f, 0.000000f, -0.523599f, -0.087266f, 0.600020f, -0.032550f, 0.432124f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_06 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.046952f, -0.028006f, -0.395457f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_07 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.420168f, -0.016319f, -0.069064f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_08 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.372796f, -0.014707f, -0.375387f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_09 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.117654f, -0.005070f, 0.403074f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_10 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, 0.569173f, -0.030517f, 0.341285f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit2_11 = new Pose(-0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.523599f, -0.087266f, -0.000014f, -0.000349f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit3_00 = new Pose(-0.000000f, -0.000000f, 0.000000f, -0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_01 = new Pose(0.009352f, -0.004679f, 0.058237f, -0.009715f, 0.048871f, -0.000433f, -0.469282f, -0.043680f, 0.112127f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_02 = new Pose(0.017711f, -0.001267f, 0.045114f, -0.019510f, 0.097724f, 0.000084f, -0.467310f, -0.043207f, 0.246152f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_03 = new Pose(0.006012f, 0.320766f, 0.100603f, -0.029481f, 0.146507f, 0.001266f, -0.108591f, -0.009120f, 0.482711f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_04 = new Pose(0.038851f, -0.018655f, -0.026366f, -0.039684f, 0.195159f, 0.003299f, -0.205544f, -0.017173f, -0.288560f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_05 = new Pose(0.049410f, -0.023333f, -0.061054f, -0.049786f, 0.243774f, 0.006167f, -0.171669f, -0.014374f, 0.151003f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_06 = new Pose(0.060268f, -0.028050f, -0.095591f, -0.059825f, 0.292276f, 0.009763f, -0.451759f, -0.039415f, -0.183596f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_07 = new Pose(0.071452f, -0.032836f, -0.129907f, -0.069912f, 0.340621f, 0.014307f, 0.168775f, 0.014134f, 0.326752f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_08 = new Pose(0.082940f, -0.037667f, -0.164353f, -0.079745f, 0.388816f, 0.019757f, 0.160960f, 0.013485f, 0.378982f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_09 = new Pose(0.094796f, -0.042727f, -0.198529f, -0.089314f, 0.436952f, 0.025901f, 0.184487f, 0.015435f, 0.315548f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_10 = new Pose(0.104802f, -0.046907f, -0.226445f, -0.097160f, 0.476475f, 0.031460f, 0.070959f, 0.005966f, 0.157741f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit3_11 = new Pose(0.104802f, -0.046907f, -0.226445f, -0.097160f, 0.476475f, 0.031460f, 0.070959f, 0.005966f, 0.157741f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit4_00 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_01 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.486928f, -0.020413f, 0.371162f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_02 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.482549f, -0.020752f, 0.426779f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_03 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.491029f, -0.013708f, 0.573282f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_04 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, -0.205149f, -0.269233f, 0.528511f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_05 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.447189f, -0.017205f, 0.080600f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_06 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.533486f, -0.028812f, 0.023206f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_07 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.003434f, 0.033575f, -0.409654f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_08 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.541857f, -0.027359f, -0.398663f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_09 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.530728f, -0.026617f, -0.562612f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_10 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.188944f, -0.003439f, -0.460946f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit4_11 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.051929f, -0.011850f, -0.120440f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose hit5_00 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, -0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_01 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.249944f, 0.017598f, -0.365201f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_02 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.609011f, 0.060399f, -0.554938f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_03 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.655593f, 0.066091f, 0.419942f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_04 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.382406f, 0.026450f, 0.001383f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_05 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.334629f, 0.023476f, 0.419129f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_06 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.175144f, 0.013798f, 0.548645f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_07 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.484857f, 0.040049f, -0.192791f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_08 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.307829f, 0.021079f, -0.490151f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_09 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, -0.251115f, -0.019079f, -0.494153f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_10 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, -0.070739f, -0.005127f, -0.230781f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose hit5_11 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, -0.000174f, -0.000012f, 0.005585f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Pose charged_00 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_01 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.454806f, 0.033762f, -0.069226f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_02 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.623197f, 0.061356f, -0.072719f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_03 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.697445f, 0.067906f, -0.143922f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_04 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.620818f, 0.060716f, -0.036233f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_05 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.468515f, 0.036361f, 0.018042f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_06 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.582397f, 0.056130f, 0.038998f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_07 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.573862f, 0.054902f, -0.075155f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_08 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.562100f, 0.053137f, -0.114275f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_09 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.482724f, 0.039234f, 0.002574f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_10 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.140167f, 0.009843f, 0.022644f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);
    private static final Pose charged_11 = new Pose(0.000000f, -0.610865f, 0.087266f, 0.000000f, -0.523599f, -0.087266f, 0.000000f, 0.000000f, -0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f, 0.000000f);

    private static final Clip[] CLIPS = {
        new Clip(new Keyframe[] { // hit1
            new Keyframe(0.000f, 0, hit1_00),
            new Keyframe(0.091f, 0, hit1_01),
            new Keyframe(0.182f, 0, hit1_02),
            new Keyframe(0.273f, 0, hit1_03),
            new Keyframe(0.364f, 0, hit1_04),
            new Keyframe(0.455f, 0, hit1_05),
            new Keyframe(0.545f, 0, hit1_06),
            new Keyframe(0.636f, 0, hit1_07),
            new Keyframe(0.727f, 0, hit1_08),
            new Keyframe(0.818f, 0, hit1_09),
            new Keyframe(0.909f, 0, hit1_10),
            new Keyframe(1.000f, 0, hit1_11),
        }),
        new Clip(new Keyframe[] { // hit2
            new Keyframe(0.000f, 0, hit2_00),
            new Keyframe(0.091f, 0, hit2_01),
            new Keyframe(0.182f, 0, hit2_02),
            new Keyframe(0.273f, 0, hit2_03),
            new Keyframe(0.364f, 0, hit2_04),
            new Keyframe(0.455f, 0, hit2_05),
            new Keyframe(0.545f, 0, hit2_06),
            new Keyframe(0.636f, 0, hit2_07),
            new Keyframe(0.727f, 0, hit2_08),
            new Keyframe(0.818f, 0, hit2_09),
            new Keyframe(0.909f, 0, hit2_10),
            new Keyframe(1.000f, 0, hit2_11),
        }),
        new Clip(new Keyframe[] { // hit3
            new Keyframe(0.000f, 0, hit3_00),
            new Keyframe(0.091f, 0, hit3_01),
            new Keyframe(0.182f, 0, hit3_02),
            new Keyframe(0.273f, 0, hit3_03),
            new Keyframe(0.364f, 0, hit3_04),
            new Keyframe(0.455f, 0, hit3_05),
            new Keyframe(0.545f, 0, hit3_06),
            new Keyframe(0.636f, 0, hit3_07),
            new Keyframe(0.727f, 0, hit3_08),
            new Keyframe(0.818f, 0, hit3_09),
            new Keyframe(0.909f, 0, hit3_10),
            new Keyframe(1.000f, 0, hit3_11),
        }),
        new Clip(new Keyframe[] { // hit4
            new Keyframe(0.000f, 0, hit4_00),
            new Keyframe(0.091f, 0, hit4_01),
            new Keyframe(0.182f, 0, hit4_02),
            new Keyframe(0.273f, 0, hit4_03),
            new Keyframe(0.364f, 0, hit4_04),
            new Keyframe(0.455f, 0, hit4_05),
            new Keyframe(0.545f, 0, hit4_06),
            new Keyframe(0.636f, 0, hit4_07),
            new Keyframe(0.727f, 0, hit4_08),
            new Keyframe(0.818f, 0, hit4_09),
            new Keyframe(0.909f, 0, hit4_10),
            new Keyframe(1.000f, 0, hit4_11),
        }),
        new Clip(new Keyframe[] { // hit5
            new Keyframe(0.000f, 0, hit5_00),
            new Keyframe(0.091f, 0, hit5_01),
            new Keyframe(0.182f, 0, hit5_02),
            new Keyframe(0.273f, 0, hit5_03),
            new Keyframe(0.364f, 0, hit5_04),
            new Keyframe(0.455f, 0, hit5_05),
            new Keyframe(0.545f, 0, hit5_06),
            new Keyframe(0.636f, 0, hit5_07),
            new Keyframe(0.727f, 0, hit5_08),
            new Keyframe(0.818f, 0, hit5_09),
            new Keyframe(0.909f, 0, hit5_10),
            new Keyframe(1.000f, 0, hit5_11),
        }),
        new Clip(new Keyframe[] { // charged
            new Keyframe(0.000f, 0, charged_00),
            new Keyframe(0.091f, 0, charged_01),
            new Keyframe(0.182f, 0, charged_02),
            new Keyframe(0.273f, 0, charged_03),
            new Keyframe(0.364f, 0, charged_04),
            new Keyframe(0.455f, 0, charged_05),
            new Keyframe(0.545f, 0, charged_06),
            new Keyframe(0.636f, 0, charged_07),
            new Keyframe(0.727f, 0, charged_08),
            new Keyframe(0.818f, 0, charged_09),
            new Keyframe(0.909f, 0, charged_10),
            new Keyframe(1.000f, 0, charged_11),
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
