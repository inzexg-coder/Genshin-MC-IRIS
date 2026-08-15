package net.teyvat.client;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.BillboardParticleSubmittable;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.particle.SpriteProvider;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.teyvat.particle.TeyvatSlashEffect;
import org.joml.Quaternionf;

/**
 * Дуга-разрез удара (техника Better Combat): билборд-частица с текстурой
 * светящегося серпа, ориентированная кватернионом из TeyvatSlashEffect —
 * квад ставится ровно в плоскость движения клинка, а не «ложится» как
 * ванильный SWEEP_ATTACK. Рендерится с двух сторон (передняя/задняя грань),
 * на полной яркости — виден из любого ракурса, в 1-м и 3-м лице.
 */
public class TeyvatSlashParticle extends BillboardParticle {
    private static final int FULL_BRIGHT = 15728880;
    private final SpriteProvider spriteProvider;
    private final Quaternionf rotation;
    private final boolean light;

    protected TeyvatSlashParticle(ClientWorld world, double x, double y, double z,
                                  TeyvatSlashEffect effect, SpriteProvider spriteProvider) {
        super(world, x, y, z, spriteProvider.getFirst());
        this.spriteProvider = spriteProvider;
        this.rotation = new Quaternionf(effect.getQx(), effect.getQy(), effect.getQz(), effect.getQw());
        this.light = effect.isLight();
        this.scale = effect.getScale();
        this.maxAge = 6;
        this.gravityStrength = 0.0f;
        this.velocityMultiplier = 0.0f;
        this.collidesWithWorld = false;
        int c = effect.getColor();
        this.setColor(((c >> 16) & 0xFF) / 255.0f, ((c >> 8) & 0xFF) / 255.0f, (c & 0xFF) / 255.0f);
        this.setAlpha(((c >> 24) & 0xFF) / 255.0f);
    }

    @Override
    public void tick() {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        if (this.age++ >= this.maxAge) {
            this.markDead();
            return;
        }
        // Короткая вспышка: первые 4 кадра дуга «прочерчивается» текстурой,
        // последние 2 — плавно тает.
        if (this.age >= 4) {
            this.setAlpha(Math.max(0.0f, this.alpha - 0.35f));
        }
        this.updateSprite(this.spriteProvider);
    }

    @Override
    public BillboardParticle.RenderType getRenderType() {
        return BillboardParticle.RenderType.PARTICLE_ATLAS_TRANSLUCENT;
    }

    @Override
    public void render(BillboardParticleSubmittable submittable, Camera camera, float tickProgress) {
        Vec3d cameraPos = camera.getPos();
        float x = (float) (MathHelper.lerp(tickProgress, this.lastX, this.x) - cameraPos.x);
        float y = (float) (MathHelper.lerp(tickProgress, this.lastY, this.y) - cameraPos.y);
        float z = (float) (MathHelper.lerp(tickProgress, this.lastZ, this.z) - cameraPos.z);
        float size = this.getSize(tickProgress);
        int color = packArgb(this.alpha, this.red, this.green, this.blue);
        int brightness = this.light ? FULL_BRIGHT : this.getBrightness(tickProgress);
        // Передняя грань.
        submittable.render(this.getRenderType(), x, y, z,
                this.rotation.x, this.rotation.y, this.rotation.z, this.rotation.w,
                size, this.getMinU(), this.getMaxU(), this.getMinV(), this.getMaxV(),
                color, brightness);
        // Задняя грань: поворот на 180° вокруг локальной Y — серп виден и сзади.
        Quaternionf back = new Quaternionf(this.rotation).mul(new Quaternionf().rotationY((float) Math.PI));
        submittable.render(this.getRenderType(), x, y, z,
                back.x, back.y, back.z, back.w,
                size, this.getMaxU(), this.getMinU(), this.getMinV(), this.getMaxV(),
                color, brightness);
    }

    private static int packArgb(float a, float r, float g, float b) {
        return ((int) (a * 255.0f) << 24)
                | ((int) (r * 255.0f) << 16)
                | ((int) (g * 255.0f) << 8)
                | (int) (b * 255.0f);
    }

    public static class Factory implements ParticleFactory<TeyvatSlashEffect> {
        private final SpriteProvider spriteProvider;

        public Factory(SpriteProvider spriteProvider) {
            this.spriteProvider = spriteProvider;
        }

        @Override
        public Particle createParticle(TeyvatSlashEffect parameters, ClientWorld world,
                                       double x, double y, double z,
                                       double velocityX, double velocityY, double velocityZ,
                                       Random random) {
            return new TeyvatSlashParticle(world, x, y, z, parameters, this.spriteProvider);
        }
    }
}
