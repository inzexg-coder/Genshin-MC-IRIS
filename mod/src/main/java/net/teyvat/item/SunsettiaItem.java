package net.teyvat.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * Закатник — мгновенное поедание, восстанавливает 300 HP.
 * Без FoodComponent/ConsumableComponent — полный контроль в use().
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        player.heal(HEAL_AMOUNT);
        player.swingHand(hand);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.0f);
        if (!player.getAbilities().creativeMode) {
            player.getStackInHand(hand).decrement(1);
        }
        return ActionResult.SUCCESS;
    }
}
