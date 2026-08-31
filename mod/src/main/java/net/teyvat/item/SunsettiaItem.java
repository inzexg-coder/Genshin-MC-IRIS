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

public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        if (player.canConsume(true)) {
            player.setCurrentHand(hand);
            return ActionResult.CONSUME;
        }
        return ActionResult.PASS;
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (!world.isClient() && user != null) {
            user.heal(HEAL_AMOUNT);
            world.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.0f);
        }
        if (!(user instanceof PlayerEntity p) || !p.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        return stack;
    }
}
