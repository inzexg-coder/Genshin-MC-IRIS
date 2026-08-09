package net.teyvat.client;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Водяная дымка: мягкое полупрозрачное облачко, медленно поднимается и расплывается.
 */
public class WaterMistParticle extends BillboardParticle {
    private final SpriteProvider spriteProvider;
    private final float startScale;

    protected WaterMistParticle(ClientWorld world, double x, double y, double z,
                                double velocityX, double velocityY, double velocityZ,
                                SpriteProvider spriteProvider) {
        super(world, x, y, z, velocityX, velocityY, velocityZ, spriteProvider.getFirst());
        this.spriteProvider = spriteProvider;
        this.startScale = 0.5f + this.random.nextFloat() * 0.35f;
        this.scale = this.startScale;
        this.maxAge = 40 + this.random.nextInt(20);
        this.gravityStrength = -0.012f;
        this.setColor(0.85f, 0.94f, 1.0f);
        this.setAlpha(0.32f);
        this.velocityX = (this.random.nextFloat() - 0.5f) * 0.08f;
        this.velocityY = 0.02f + this.random.nextFloat() * 0.05f;
        this.velocityZ = (this.random.nextFloat() - 0.5f) * 0.08f;
    }

    @Override
    public void tick() {
        super.tick();
        float progress = this.age / (float) this.maxAge;
        this.scale = this.startScale + progress * 1.4f;
        this.setAlpha(Math.max(0.0f, 0.32f - progress * 0.32f));
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
            return new WaterMistParticle(world, x, y, z, velocityX, velocityY, velocityZ,
                    this.spriteProvider);
        }
    }
}
