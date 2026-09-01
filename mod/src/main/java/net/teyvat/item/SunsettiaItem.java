package net.teyvat.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.teyvat.TeyvatSounds;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Закатник — еда с анимацией поедания (1.6 сек) и лечением 300 HP.
 * Кастомный таймер через ConcurrentHashMap, независимый от ванильного ConsumableComponent.
 */
public class SunsettiaItem extends Item {
    private static final int HEAL_AMOUNT = 300;
    private static final int EAT_DURATION_TICKS = 32;

    /** таймер поедания: UUID игрока → оставшиеся тики */
    private static final Map<UUID, Integer> EATING_TIMERS = new ConcurrentHashMap<>();

    public SunsettiaItem(Settings settings) {
        super(settings.maxCount(64));
    }

    @Override
    public ActionResult use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) return ActionResult.PASS;

        UUID id = player.getUuid();
        if (EATING_TIMERS.containsKey(id)) return ActionResult.CONSUME;

        if (!world.isClient()) {
            EATING_TIMERS.put(id, EAT_DURATION_TICKS);
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    TeyvatSounds.EAT_CHEW, SoundCategory.PLAYERS,
                    1.0f, 0.9f + world.random.nextFloat() * 0.1f);
        }
        player.setCurrentHand(hand);
        return ActionResult.CONSUME;
    }

    /** Вызывается сервером каждый тик пока игрок ест. */
    public static void serverTick(PlayerEntity player) {
        UUID id = player.getUuid();
        Integer remaining = EATING_TIMERS.get(id);
        if (remaining == null) return;

        int left = remaining - 1;
        if (left <= 0) {
            EATING_TIMERS.remove(id);
            ItemStack stack = player.getStackInHand(Hand.MAIN_HAND);
            if (stack.getItem() instanceof SunsettiaItem) {
                player.heal(HEAL_AMOUNT);
                player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.5f, 1.0f);
                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }
            }
        } else {
            EATING_TIMERS.put(id, left);
            // Звук поедания каждые 4 тика
            if (left % 4 == 0) {
                player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                        TeyvatSounds.EAT_CHEW, SoundCategory.PLAYERS,
                        1.0f, 0.9f + player.getEntityWorld().random.nextFloat() * 0.1f);
            }
        }
    }

    /** Очистка при выходе игрока / смерти. */
    public static void clearTimer(PlayerEntity player) {
        EATING_TIMERS.remove(player.getUuid());
    }

    @Override
    public int getMaxUseTime(ItemStack stack, net.minecraft.entity.LivingEntity entity) {
        return EAT_DURATION_TICKS;
    }

    @Override
    public net.minecraft.item.consume.UseAction getUseAction(ItemStack stack) {
        return net.minecraft.item.consume.UseAction.EAT;
    }
}
