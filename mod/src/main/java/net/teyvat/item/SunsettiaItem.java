package net.teyvat.item;

import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * Закатник — плод дерева закатника, восстанавливает 300 HP.
 * Никакого кастомного use() — ConsumableComponent.handleEat() из FoodComponent
 * сам вызывает setCurrentHand и запускает анимацию.
 * finishUsing() применяет лечение 300 HP + звук отрыжки.
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // Лечение 300 HP + звук отрыжки (только на сервере)
        if (!world.isClient() && user != null) {
            user.heal(HEAL_AMOUNT);
            world.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.0f);
        }
        // В творческом не消耗
        if (user instanceof PlayerEntity p && p.getAbilities().creativeMode) {
            return stack;
        }
        stack.decrement(1);
        return stack;
    }
}
