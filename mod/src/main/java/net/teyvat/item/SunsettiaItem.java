package net.teyvat.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * Закатник — плод дерева закатника, восстанавливает 300 HP.
 * ПКМ запускает анимацию поедания через ConsumableComponent (vanilla-механика).
 * finishUsing() применяет лечение + звук.
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        // FoodComponent(6, 0.6f, true) автоматически создаёт ConsumableComponent:
        // - canAlwaysEat = true → можно есть когда угодно
        // - consumeTicks = 32 → анимация поедания 1.6 сек
        // - useAction = EAT → правильная анимация руки
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    /**
     * Ванильная анимация поедания обрабатывается ConsumableComponent.consume(),
     * который вызывается из Item.use() → ItemStack.use(). Не трогаем!
     * use() возвращать PASS — ванильный Item.use() уже обрабатывает
     * ConsumableComponent и возвращает CONSUME.
     */

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // Лечение 300 HP на сервере + звук отрыжки
        if (!world.isClient() && user != null) {
            user.heal(HEAL_AMOUNT);
            world.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.0f);
        }
        if (user instanceof PlayerEntity p && p.getAbilities().creativeMode) {
            return stack;
        }
        stack.decrement(1);
        return stack;
    }
}
