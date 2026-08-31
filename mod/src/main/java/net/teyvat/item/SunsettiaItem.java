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
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        // Сначала даём ванили попробовать (ConsumableComponent.consume)
        ActionResult result = super.use(world, player, hand);
        // Если ваниль не обработала — пробуем сами
        if (result != ActionResult.CONSUME && player.canConsume(true)) {
            player.setCurrentHand(hand);
            return ActionResult.CONSUME;
        }
        return result;
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        ItemStack result = super.finishUsing(stack, world, user);
        if (!world.isClient() && user != null) {
            user.heal(HEAL_AMOUNT);
        }
        return result;
    }
}
