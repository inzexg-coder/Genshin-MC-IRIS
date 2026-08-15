package net.teyvat.particle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;

/**
 * Параметры дуги-разреза (техника Better Combat): квад-частица с текстурой
 * светящейся дуги, ориентированная кватернионом (qx..qw) по фактической
 * плоскости движения клинка. scale — размер квада (радиус дуги на экране),
 * color — ARGB, light — рисовать на полной яркости.
 */
public final class TeyvatSlashEffect implements ParticleEffect {
    private final ParticleType<TeyvatSlashEffect> type;
    private final float qx;
    private final float qy;
    private final float qz;
    private final float qw;
    private final float scale;
    private final int color;
    private final boolean light;

    public TeyvatSlashEffect(ParticleType<TeyvatSlashEffect> type, float qx, float qy, float qz,
                             float qw, float scale, int color, boolean light) {
        this.type = type;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qw = qw;
        this.scale = scale;
        this.color = color;
        this.light = light;
    }

    @Override
    public ParticleType<TeyvatSlashEffect> getType() {
        return this.type;
    }

    public float getQx() {
        return this.qx;
    }

    public float getQy() {
        return this.qy;
    }

    public float getQz() {
        return this.qz;
    }

    public float getQw() {
        return this.qw;
    }

    public float getScale() {
        return this.scale;
    }

    public int getColor() {
        return this.color;
    }

    public boolean isLight() {
        return this.light;
    }

    public static MapCodec<TeyvatSlashEffect> createCodec(ParticleType<TeyvatSlashEffect> particleType) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.FLOAT.fieldOf("qx").forGetter(TeyvatSlashEffect::getQx),
                Codec.FLOAT.fieldOf("qy").forGetter(TeyvatSlashEffect::getQy),
                Codec.FLOAT.fieldOf("qz").forGetter(TeyvatSlashEffect::getQz),
                Codec.FLOAT.fieldOf("qw").forGetter(TeyvatSlashEffect::getQw),
                Codec.FLOAT.fieldOf("scale").forGetter(TeyvatSlashEffect::getScale),
                Codec.INT.fieldOf("color").forGetter(TeyvatSlashEffect::getColor),
                Codec.BOOL.fieldOf("light").forGetter(TeyvatSlashEffect::isLight)
        ).apply(instance, (qx, qy, qz, qw, scale, color, light) ->
                new TeyvatSlashEffect(particleType, qx, qy, qz, qw, scale, color, light)));
    }

    public static PacketCodec<? super RegistryByteBuf, TeyvatSlashEffect> createPacketCodec(
            ParticleType<TeyvatSlashEffect> particleType) {
        return PacketCodec.of(
                (effect, buf) -> {
                    buf.writeFloat(effect.getQx());
                    buf.writeFloat(effect.getQy());
                    buf.writeFloat(effect.getQz());
                    buf.writeFloat(effect.getQw());
                    buf.writeFloat(effect.getScale());
                    buf.writeInt(effect.getColor());
                    buf.writeBoolean(effect.isLight());
                },
                buf -> new TeyvatSlashEffect(particleType,
                        buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readFloat(), buf.readInt(), buf.readBoolean()));
    }
}
