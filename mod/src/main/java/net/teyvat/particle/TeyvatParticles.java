package net.teyvat.particle;

import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.particle.ParticleType;
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

    /** Дуги-разрезы ударов (Better Combat-стиль): квад с текстурой светящегося
     *  серпа, ориентированный по плоскости движения клинка. Варианты — по
     *  ширине дуги: 45° (вспышка хита), 90°, 180°, 270° (очень широкий удар),
     *  360° (разворот и заряженный спин — полные кольца-орбиты). */
    public static final ParticleType<TeyvatSlashEffect> SLASH_45 = createSlashType();
    public static final ParticleType<TeyvatSlashEffect> SLASH_90 = createSlashType();
    public static final ParticleType<TeyvatSlashEffect> SLASH_180 = createSlashType();
    public static final ParticleType<TeyvatSlashEffect> SLASH_270 = createSlashType();
    public static final ParticleType<TeyvatSlashEffect> SLASH_360 = createSlashType();

    private TeyvatParticles() {
    }

    private static ParticleType<TeyvatSlashEffect> createSlashType() {
        return new ParticleType<>(true) {
            @Override
            public MapCodec<TeyvatSlashEffect> getCodec() {
                return TeyvatSlashEffect.createCodec(this);
            }

            @Override
            public PacketCodec<? super RegistryByteBuf, TeyvatSlashEffect> getPacketCodec() {
                return TeyvatSlashEffect.createPacketCodec(this);
            }
        };
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
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "slash_45"), SLASH_45);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "slash_90"), SLASH_90);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "slash_180"), SLASH_180);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "slash_270"), SLASH_270);
        Registry.register(Registries.PARTICLE_TYPE,
                Identifier.of("teyvat", "slash_360"), SLASH_360);
    }
}
