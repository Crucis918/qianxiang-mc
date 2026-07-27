package com.qianxiang.particle;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.qianxiang.QianxiangParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ExtraCodecs;
import org.joml.Vector3f;

/**
 * shockwave 冲击波环粒子的可序列化参数：颜色 + 扩散目标半径。
 * <p>
 * 单个粒子渲染成一整圈贴地双面环（见 {@code ShockwaveParticle}），
 * 技法参考 Iron's Spells 的 BlastwaveParticleOptions。
 * {@link #CODEC} 与 {@link #STREAM_CODEC} 字段顺序一致（color 先、scale 后）。
 * </p>
 */
public class ShockwaveParticleOptions implements ParticleOptions {

    private final Vector3f color;
    private final float scale;

    public ShockwaveParticleOptions(Vector3f color, float scale) {
        this.color = color;
        this.scale = scale;
    }

    public Vector3f color() {
        return color;
    }

    public float scale() {
        return scale;
    }

    @Override
    public ParticleType<?> getType() {
        return QianxiangParticles.SHOCKWAVE.get();
    }

    public static final MapCodec<ShockwaveParticleOptions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ExtraCodecs.VECTOR3F.fieldOf("color").forGetter(ShockwaveParticleOptions::color),
            ExtraCodecs.POSITIVE_FLOAT.fieldOf("scale").forGetter(ShockwaveParticleOptions::scale)
    ).apply(instance, ShockwaveParticleOptions::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ShockwaveParticleOptions> STREAM_CODEC =
            StreamCodec.of(
                    (buf, options) -> {
                        ByteBufCodecs.VECTOR3F.encode(buf, options.color());
                        ByteBufCodecs.FLOAT.encode(buf, options.scale());
                    },
                    buf -> new ShockwaveParticleOptions(
                            ByteBufCodecs.VECTOR3F.decode(buf), ByteBufCodecs.FLOAT.decode(buf)));
}
