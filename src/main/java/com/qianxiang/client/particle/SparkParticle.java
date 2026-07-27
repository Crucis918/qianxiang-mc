package com.qianxiang.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * spark 火花粒子：出生白色 → {@code colorTime}（8~20t）内 lerp 到目标色；
 * 重力 1.3、摩擦 0.985；落地弹跳（反弹系数逐次 ×0.8 衰减、着地 quadSize ×0.9）；
 * 速度归零时提前移除。技法参考 Iron's Spells 的 SparkParticle。
 */
public class SparkParticle extends TextureSheetParticle {

    private final Vector3f targetColor;
    private final int colorTime;
    /** 落地反弹系数：每次触地 ×0.8 衰减。 */
    private float bounciness = 1.0f;
    /** 本 tick 运动前的垂直速度（super.tick 后 yd 已被地面清零，弹跳要用冲击前速度）。 */
    private double preTickYd;

    protected SparkParticle(ClientLevel level, double x, double y, double z,
                            double xd, double yd, double zd, Vector3f color, SpriteSet sprites) {
        super(level, x, y, z, xd, yd, zd);
        this.targetColor = color;
        this.colorTime = 8 + this.random.nextInt(13); // 8~20t 完成上色
        this.lifetime = 20 + this.random.nextInt(20);
        this.gravity = 1.3f;
        this.friction = 0.985f;
        this.quadSize = 0.08f + this.random.nextFloat() * 0.06f;
        // 出生白色
        this.rCol = 1.0f;
        this.gCol = 1.0f;
        this.bCol = 1.0f;
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        this.preTickYd = this.yd;
        super.tick();

        // 白 → 目标色 lerp
        float f = Math.min(1.0f, (float) this.age / (float) this.colorTime);
        this.rCol = Mth.lerp(f, 1.0f, this.targetColor.x());
        this.gCol = Mth.lerp(f, 1.0f, this.targetColor.y());
        this.bCol = Mth.lerp(f, 1.0f, this.targetColor.z());

        // 落地弹跳（反弹系数逐次衰减，着地变小）
        if (this.onGround && this.preTickYd < 0.0 && this.bounciness > 0.05f) {
            this.yd = -this.preTickYd * this.bounciness;
            this.bounciness *= 0.8f;
            this.quadSize *= 0.9f;
        }

        // 速度归零 → 提前移除（别让火星在地上躺满寿命）
        if (this.age > 3
                && this.xd * this.xd + this.yd * this.yd + this.zd * this.zd < 1.0E-5) {
            this.remove();
        }
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    /** 由 {@code ClientSetup#registerParticles} 经 registerSpriteSet 挂接。 */
    public static class Provider implements ParticleProvider<com.qianxiang.particle.SparkParticleOptions> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(com.qianxiang.particle.SparkParticleOptions options,
                                       ClientLevel level, double x, double y, double z,
                                       double xd, double yd, double zd) {
            return new SparkParticle(level, x, y, z, xd, yd, zd, options.color(), this.sprites);
        }
    }
}
