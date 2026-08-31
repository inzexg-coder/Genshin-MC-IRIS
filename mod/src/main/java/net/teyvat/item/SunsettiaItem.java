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
 * Закатник — мгновенное поедание, восстанавливает 300 HP.
 * ПКМ → сразу лечение + отрыжка, без анимации поедания.
 * (Ванильная анимация поедания через ConsumableComponent не работает
 *  из-за сброса таймера при повторных вызовах use() на клиенте.)
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings.food(new FoodComponent(6, 0.6f, true)));
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }
        // Сервер: лечение + звук +消耗 предмет
        player.heal(HEAL_AMOUNT);
        player.swingHand(hand);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.0f);
        if (!player.getAbilities().creativeMode) {
            player.getStackInHand(hand).decrement(1);
        }
        return ActionResult.SUCCESS;
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        return stack;
    }
}
