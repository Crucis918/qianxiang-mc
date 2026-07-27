package com.qianxiang.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

/**
 * shockwave 冲击波环：单个粒子渲染成一整圈贴地双面环——
 * 四元数 XP ±90° 平铺地面、正反面各画一次（下方也可见）；
 * {@link #getQuadSize} 缓出扩散（lerp(1-(1-f)², target×0.75, target)），alpha 随年龄渐隐；
 * 寿命 8~12 tick。技法参考 Iron's Spells 的 BlastwaveParticle。
 */
public class ShockwaveParticle extends TextureSheetParticle {

    private final float targetScale;

    protected ShockwaveParticle(ClientLevel level, double x, double y, double z,
                                double xd, double yd, double zd,
                                org.joml.Vector3f color, float scale, SpriteSet sprites) {
        super(level, x, y, z, xd, yd, zd);
        this.targetScale = scale;
        this.lifetime = 8 + this.random.nextInt(5); // 8~12t
        this.gravity = 0.1f;
        this.friction = 0.85f;
        this.rCol = color.x();
        this.gCol = color.y();
        this.bCol = color.z();
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        // alpha 随年龄渐隐
        this.alpha = Mth.clamp(1.0f - (float) this.age / (float) this.lifetime, 0.0f, 1.0f);
    }

    @Override
    public float getQuadSize(float partialTicks) {
        float f = ((float) this.age + partialTicks) / (float) this.lifetime;
        float eased = 1.0f - (1.0f - f) * (1.0f - f); // 缓出
        return Mth.lerp(eased, this.targetScale * 0.75f, this.targetScale);
    }

    /** 贴地双面环：XP ±90° 各画一次（从下方看也可见）。 */
    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        this.renderRotatedQuad(buffer, camera,
                new Quaternionf().rotationX((float) (Math.PI / 2.0)), partialTicks);
        this.renderRotatedQuad(buffer, camera,
                new Quaternionf().rotationX((float) (-Math.PI / 2.0)), partialTicks);
    }

    /** 扩散半径远超包围盒，按目标半径外扩（防视锥误剔除）。 */
    @Override
    public AABB getRenderBoundingBox(float partialTicks) {
        return this.getBoundingBox().inflate(this.targetScale + 1.0);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    /** 由 {@code ClientSetup#registerParticles} 经 registerSpriteSet 挂接。 */
    public static class Provider implements ParticleProvider<com.qianxiang.particle.ShockwaveParticleOptions> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(com.qianxiang.particle.ShockwaveParticleOptions options,
                                       ClientLevel level, double x, double y, double z,
                                       double xd, double yd, double zd) {
            return new ShockwaveParticle(level, x, y, z, xd, yd, zd,
                    options.color(), options.scale(), this.sprites);
        }
    }
}
