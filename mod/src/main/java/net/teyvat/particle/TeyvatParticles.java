package net.teyvat.particle;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** Кастомные частицы мода. Регистрация — в TeyvatMod (общая для клиента и сервера). */
public final class TeyvatParticles {
    /** Всплеск воды при смерти Гидро слайма: расширяющееся полупрозрачное кольцо. */
    public static final SimpleParticleType WATER_SPLASH = FabricParticleTypes.simple(true);

    private TeyvatParticles() {
    }

    public static void register() {
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "water_splash"), WATER_SPLASH);
    }
}
