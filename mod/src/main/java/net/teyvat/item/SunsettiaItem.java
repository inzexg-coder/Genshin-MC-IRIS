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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;
    private static final Logger LOGGER = LoggerFactory.getLogger("Teyvat/SunsettiaItem");

    public SunsettiaItem(Settings settings) {
        super(settings.food(new FoodComponent(6, 0.6f, true)));
        LOGGER.info("[Sunsettia] Item created, food component attached");
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        int maxUse = stack.getMaxUseTime(player);
        boolean canConsume = player.canConsume(true);
        LOGGER.info("[Sunsettia] use() called: side={}, player={}, canConsume={}, maxUseTime={}, hand={}",
                world.isClient() ? "CLIENT" : "SERVER",
                player.getName().getString(),
                canConsume,
                maxUse,
                hand);
        if (canConsume) {
            player.setCurrentHand(hand);
            LOGGER.info("[Sunsettia] setCurrentHand called, returning CONSUME");
            return ActionResult.CONSUME;
        }
        LOGGER.info("[Sunsettia] canConsume=false, returning PASS");
        return ActionResult.PASS;
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        LOGGER.info("[Sunsettia] finishUsing called: side={}, user={}",
                world.isClient() ? "CLIENT" : "SERVER",
                user.getName().getString());
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
