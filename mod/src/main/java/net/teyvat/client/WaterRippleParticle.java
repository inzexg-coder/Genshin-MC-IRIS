package net.teyvat.client;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Рябь на воде: тонкое кольцо медленно расширяется по поверхности и тает.
 */
public class WaterRippleParticle extends BillboardParticle {
    private final SpriteProvider spriteProvider;

    protected WaterRippleParticle(ClientWorld world, double x, double y, double z,
                                  double velocityX, double velocityY, double velocityZ,
                                  SpriteProvider spriteProvider) {
        super(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider.getFirst());
        this.spriteProvider = spriteProvider;
        this.scale = 0.35f + this.random.nextFloat() * 0.15f;
        this.maxAge = 30 + this.random.nextInt(12);
        this.gravityStrength = 0.0f;
        this.velocityX = 0.0;
        this.velocityY = 0.0;
        this.velocityZ = 0.0;
        this.setColor(0.72f, 0.9f, 1.0f);
        this.setAlpha(0.8f);
    }

    @Override
    public void tick() {
        super.tick();
        float progress = this.age / (float) this.maxAge;
        this.scale = 0.35f + progress * 1.9f;
        this.setAlpha(Math.max(0.0f, 0.8f - progress * 0.8f));
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
            return new WaterRippleParticle(world, x, y, z, velocityX, velocityY, velocityZ,
                    this.spriteProvider);
        }
    }
}
