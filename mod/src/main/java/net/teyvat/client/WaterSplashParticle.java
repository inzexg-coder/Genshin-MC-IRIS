package net.teyvat.client;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Всплеск воды: кольцо расширяется из точки гибели слайма и плавно тает.
 * Текстура кольца — assets/teyvat/textures/particle/water_splash_ring.png.
 */
public class WaterSplashParticle extends BillboardParticle {
    private final SpriteProvider spriteProvider;

    protected WaterSplashParticle(ClientWorld world, double x, double y, double z,
                                  double velocityX, double velocityY, double velocityZ,
                                  SpriteProvider spriteProvider) {
        super(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider.getFirst());
        this.spriteProvider = spriteProvider;
        this.scale = 0.4f;
        this.maxAge = 26;
        this.gravityStrength = 0.0f;
        this.setColor(0.62f, 0.87f, 1.0f);
        this.setAlpha(0.85f);
    }

    @Override
    public void tick() {
        super.tick();
        float progress = this.age / (float) this.maxAge;
        this.scale = 0.4f + progress * 1.35f;
        this.setAlpha(Math.max(0.0f, 0.9f - progress * 0.9f));
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
            return new WaterSplashParticle(world, x, y, z, velocityX, velocityY, velocityZ,
                    this.spriteProvider);
        }
    }
}
