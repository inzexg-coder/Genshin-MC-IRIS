package net.teyvat.item;

import net.minecraft.component.type.ConsumableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.consume.UseAction;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;

/**
 * Закатник — еда, работает как обычное яблоко (ванильная механика поедания),
 * но восстанавливает 300 HP. Звук, анимация и частицы — ванильные.
 * Можно есть даже на полном здоровье.
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;

    public SunsettiaItem(Settings settings) {
        super(settings
                .maxCount(64)
                .food(new FoodComponent(1, 0.3f, true),
                        ConsumableComponent.builder()
                                .consumeSeconds(1.6f)
                                .useAction(UseAction.EAT)
                                .sound(SoundEvents.ENTITY_GENERIC_EAT)
                                .consumeParticles(true)
                                .build()));
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        user.heal(HEAL_AMOUNT);
        return super.finishUsing(stack, world, user);
    }
}
