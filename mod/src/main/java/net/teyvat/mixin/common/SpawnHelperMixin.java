package net.teyvat.mixin.common;

import net.minecraft.entity.SpawnGroup;
import net.minecraft.world.SpawnHelper;
import net.teyvat.config.TeyvatConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Естественный спавн мобов: по флагу world.only_peaceful_spawns убираем
 * враждебную группу (MONSTER) из списка групп, которые чанк пытается
 * заспавнить. Мирные животные, рыбы и летучие мыши спавнятся как обычно.
 */
@Mixin(SpawnHelper.class)
public abstract class SpawnHelperMixin {
    @Inject(method = "collectSpawnableGroups", at = @At("RETURN"), cancellable = true)
    private static void teyvat$onlyPeacefulSpawns(SpawnHelper.Info info, boolean spawnAnimals,
                                                  boolean spawnMonsters, boolean rare,
                                                  CallbackInfoReturnable<List<SpawnGroup>> cir) {
        if (!TeyvatConfig.get().world.only_peaceful_spawns) {
            return;
        }
        List<SpawnGroup> groups = cir.getReturnValue();
        cir.setReturnValue(groups.stream().filter(g -> g != SpawnGroup.MONSTER).toList());
    }
}
