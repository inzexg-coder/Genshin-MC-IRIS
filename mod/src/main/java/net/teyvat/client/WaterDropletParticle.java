package net.teyvat.client;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Брызга воды: взлетает вверх, дугой падает вниз и тает.
 * Скорость задаётся внутри частицы — позиции/разброс из спавна не нужны.
 */
public class WaterDropletParticle extends BillboardParticle {
    private final SpriteProvider spriteProvider;

    protected WaterDropletParticle(ClientWorld world, double x, double y, double z,
                                   double velocityX, double velocityY, double velocityZ,
                                   SpriteProvider spriteProvider) {
        super(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider.getFirst());
        this.spriteProvider = spriteProvider;
        this.scale = 0.14f + this.random.nextFloat() * 0.12f;
        this.maxAge = 20 + this.random.nextInt(16);
        this.gravityStrength = 0.85f;
        this.setColor(0.66f, 0.88f, 1.0f);
        this.setAlpha(0.95f);
        this.velocityX = (this.random.nextFloat() - 0.5f) * 0.55f;
        this.velocityY = 0.35f + this.random.nextFloat() * 0.55f;
        this.velocityZ = (this.random.nextFloat() - 0.5f) * 0.55f;
    }

    @Override
    public void tick() {
        super.tick();
        float progress = this.age / (float) this.maxAge;
        this.scale = Math.max(0.02f, this.scale - 0.004f);
        this.setAlpha(Math.max(0.0f, 0.95f - progress * 0.95f));
        this.updateSprite(this.spriteProvider);
    }

    @Override
    public BillboardParticle.RenderType getRenderType() {
        return BillboardParticle.RenderType.PARTICLE_ATLAS_TRANSLUCENT;
    }

    public static class Factory implements ParticleFactory<SimpleParticleType> {
        private final SpriteProvider spriteProvider;

        public Factory(SpriteProvider spriteProvider) {
            this.spriteProvider = spriteProvider;
        }

        @Override
        public Particle createParticle(SimpleParticleType parameters, ClientWorld world,
                                       double x, double y, double z,
                                       double velocityX, double velocityY, double velocityZ,
                                       Random random) {
            return new WaterDropletParticle(world, x, y, z, velocityX, velocityY, velocityZ,
                    this.spriteProvider);
        }
    }
}
