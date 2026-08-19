package net.teyvat.client;

import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Управление боевыми анимациями через Player Animation Library (PAL).
 * Заменяет процедурные клипы CombatController на motion-capture анимации
 * из BetterCombat (Daedelus), конвертированные в GeckoLib/Bedrock формат.
 *
 * Пять обычных атак + заряженная атака.
 */
public final class PlayerCombatAnimations {
    public static final Identifier COMBAT_LAYER =
            Identifier.of("teyvat", "combat");

    /** Приоритет: 1500+ для боевых анимаций (важный геймплей). */
    private static final int PRIORITY = 1500;

    // Имена анимаций (совпадают с ключами в JSON файлах)
    public static final Identifier ANIM_SLASH1 = Identifier.of("teyvat", "combat_slash1");
    public static final Identifier ANIM_SLASH2 = Identifier.of("teyvat", "combat_slash2");
    public static final Identifier ANIM_UPPERCUT = Identifier.of("teyvat", "combat_uppercut");
    public static final Identifier ANIM_STAB = Identifier.of("teyvat", "combat_stab");
    public static final Identifier ANIM_SPIN = Identifier.of("teyvat", "combat_spin");
    public static final Identifier ANIM_WIDE = Identifier.of("teyvat", "combat_wide");
    public static final Identifier ANIM_SLAM = Identifier.of("teyvat", "combat_slam");

    /** Массив анимаций по индексу комбо (0..4 = удары 1-5, 5 = заряженная). */
    private static final Identifier[] COMBO_ANIMS = {
        ANIM_SLASH1, ANIM_UPPERCUT, ANIM_SPIN, ANIM_STAB, ANIM_WIDE, ANIM_SLAM
    };

    private static boolean registered = false;

    /** Зарегистрировать слой анимаций (вызвать из TeyvatClient.onInitializeClient). */
    public static void init() {
        if (registered) return;
        registered = true;

        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(COMBAT_LAYER, PRIORITY,
                player -> new PlayerAnimationController(player,
                        (controller, state, animSetter) -> PlayState.STOP
                )
        );
    }

    /**
     * Запустить боевую анимацию для локального игрока.
     * @param comboStep индекс удара 0..5
     */
    public static void playComboHit(int comboStep) {
        if (comboStep < 0 || comboStep >= COMBO_ANIMS.length) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PlayerAnimationController controller = getController(client.player);
        if (controller != null) {
            controller.triggerAnimation(COMBO_ANIMS[comboStep]);
        }
    }

    /** Остановить боевую анимацию (возврат в нейтраль). */
    public static void stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        PlayerAnimationController controller = getController(client.player);
        if (controller != null) {
            controller.triggerAnimation((Identifier) null);
        }
    }

    /** Получить контроллер анимаций для игрока. */
    private static PlayerAnimationController getController(PlayerEntity player) {
        Object layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, COMBAT_LAYER);
        if (layer instanceof PlayerAnimationController ctrl) {
            return ctrl;
        }
        return null;
    }
}
