package net.teyvat.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * Закатник — плод Тейвата. Съедобен, восстанавливает 300 HP.
 * В 1.21.10 еда работает через ConsumableComponent: Item.use() → consumable.consume().
 * Мы НЕ переопределяем use() — ванильная система сама запускает анимацию еды.
 * Мы только переопределяем finishUsing() для лечения.
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        // FoodComponent(6, 0.6f, true) = nutrition 6, saturation 0.6, canAlwaysEat=true
        // settings.food() автоматически ставит и ConsumableComponent (FOOD preset)
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        // super.finishUsing() → ConsumableComponent.finishConsumption() → FoodComponent.onConsume()
        // (играет звук еды, обновляет голод, убирает предмет из руки)
        ItemStack result = super.finishUsing(stack, world, user);
        if (!world.isClient() && user != null) {
            user.heal(HEAL_AMOUNT);
        }
        return result;
    }
}
