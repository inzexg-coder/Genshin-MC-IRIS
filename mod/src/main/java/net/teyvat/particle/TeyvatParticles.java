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
    /** Брызга воды: взлетает вверх и падает дугой. */
    public static final SimpleParticleType WATER_DROPLET = FabricParticleTypes.simple(true);
    /** Рябь на воде: тонкое расширяющееся кольцо на поверхности. */
    public static final SimpleParticleType WATER_RIPPLE = FabricParticleTypes.simple(true);
    /** Водяная дымка: мягкое поднимающееся облачко. */
    public static final SimpleParticleType WATER_MIST = FabricParticleTypes.simple(true);

    private TeyvatParticles() {
    }

    public static void register() {
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "water_splash"), WATER_SPLASH);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "water_droplet"), WATER_DROPLET);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "water_ripple"), WATER_RIPPLE);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "water_mist"), WATER_MIST);
    }
}
