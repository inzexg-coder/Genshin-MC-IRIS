package net.teyvat.mixin.common;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Подбор лута как в Genshin: добыча никогда не попадает в руку (выбранный слот
 * хотбара). Сначала заполняются все остальные слоты; если свободно только в руке —
 * предмет остаётся лежать на земле (insertStack вернёт false).
 */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventoryMixin {
    @Shadow
    @Final
    private DefaultedList<ItemStack> main;

    @Shadow
    private int selectedSlot;

    @Inject(method = "insertStack(Lnet/minecraft/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true)
    private void teyvat_avoidSelectedSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.isEmpty()) {
            return;
        }
        int selected = this.selectedSlot;
        if (selected < 0 || selected >= this.main.size()) {
            return;
        }
        PlayerInventory inventory = (PlayerInventory) (Object) this;

        // 1) Дособираем в уже лежащие стаки, кроме слота в руке.
        for (int i = 0; i < this.main.size(); i++) {
            if (i == selected) {
                continue;
            }
            ItemStack existing = this.main.get(i);
            if (!existing.isEmpty()
                    && ItemStack.areItemsAndComponentsEqual(existing, stack)
                    && existing.isStackable()
                    && existing.getCount() < existing.getMaxCount()) {
                int room = Math.min(stack.getCount(), existing.getMaxCount() - existing.getCount());
                if (room > 0) {
                    existing.increment(room);
                    stack.decrement(room);
                }
                if (stack.isEmpty()) {
                    cir.setReturnValue(true);
                    return;
                }
            }
        }

        // 2) Кладём в первый свободный слот, но не в руку.
        for (int i = 0; i < this.main.size(); i++) {
            if (i != selected && this.main.get(i).isEmpty()) {
                inventory.setStack(i, stack.copy());
                stack.setCount(0);
                cir.setReturnValue(true);
                return;
            }
        }

        // 3) Место есть только в руке — не подбираем, предмет остаётся на земле.
        cir.setReturnValue(false);
    }
}
