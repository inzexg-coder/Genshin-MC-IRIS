package net.teyvat.mixin.common;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Доступ к полю уровня моба (teyvat$level), который хранит LivingEntityMixin. */
@Mixin(LivingEntity.class)
public interface LivingEntityLevelAccessor {
    @Accessor("teyvat$level")
    int teyvat$getLevelField();

    @Accessor("teyvat$level")
    void teyvat$setLevelField(int level);
}
