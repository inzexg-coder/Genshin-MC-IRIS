package net.teyvat.combat;

/**
 * Комбо атак путешественника (Итэр/Люмин) с обычным мечом, как в Genshin:
 * серия из 5 ударов, у каждого своя длительность, момент нанесения урона
 * и множитель урона от атаки. Общие данные для клиента (тайминги анимации)
 * и сервера (множители урона).
 */
public final class SwordCombo {
    /** Число ударов в комбо (у путешественника с мечом — 5). */
    public static final int HIT_COUNT = 5;
    /** Длительность каждого удара в тиках (полный круг ≈ 2.6 сек — как в Genshin,
     *  где серия меча идёт плотно, без пауз между ударами). */
    public static final int[] DURATION_TICKS = {9, 9, 10, 11, 14};
    /** Тик удара, в который наносится урон (середина быстрого разреза). */
    public static final int[] DAMAGE_TICKS = {5, 3, 3, 3, 12};
    /** Множители урона от атаки: 44.7% / 43.4% / 53.0% / 58.3% / 70.9% (уровень таланта 1). */
    public static final float[] MULTIPLIERS = {0.447f, 0.434f, 0.530f, 0.583f, 0.709f};
    /** Пауза без нажатий, после которой комбо сбрасывается (2.5 сек). */
    public static final int RESET_TICKS = 50;
    /** Тик, начиная с которого нажатие (или удержание) ЛКМ цепляет следующий удар. */
    public static final int CHAIN_INPUT_TICKS = 2;

    private SwordCombo() {}

    /** Прогресс удара 0..1 (для интерполяции поз). */
    public static float progress(int hitTicks, int hitIndex) {
        return Math.max(0f, Math.min(1f, (float) hitTicks / DURATION_TICKS[hitIndex]));
    }
}
