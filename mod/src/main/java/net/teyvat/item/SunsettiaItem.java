package net.teyvat.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * Закатник — плод Тейвата. Съедобен (съедается, но голод в моде отключён),
 * восстанавливает 300 HP поверх максимума здоровья героя (912 HP).
 */
public class SunsettiaItem extends Item {
    /** Сколько HP восстанавливает один плод. */
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // Переопределяем лечение: не обычный реген от еды, а сильное исцеление.
        super.finishUsing(stack, world, user);
        if (!world.isClient() && user != null) {
            user.heal(HEAL_AMOUNT);
        }
        return stack;
    }
}
