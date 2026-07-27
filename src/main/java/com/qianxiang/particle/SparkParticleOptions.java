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
 * spark 火花粒子的可序列化参数：目标颜色（出生白色 → colorTime 内 lerp 到该色）。
 * <p>
 * 服务端经 {@code ServerLevel#sendParticles} 下发时走 {@link #STREAM_CODEC}，
 * 与 {@link #CODEC} 字段顺序一致（错位这种 bug 编译查不出，改动时两边同步改）。
 * 技法参考 Iron's Spells 的 SparkParticleOptions。
 * </p>
 */
public class SparkParticleOptions implements ParticleOptions {

    private final Vector3f color;

    public SparkParticleOptions(Vector3f color) {
        this.color = color;
    }

    public Vector3f color() {
        return color;
    }

    @Override
    public ParticleType<?> getType() {
        return QianxiangParticles.SPARK.get();
    }

    public static final MapCodec<SparkParticleOptions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ExtraCodecs.VECTOR3F.fieldOf("color").forGetter(SparkParticleOptions::color)
    ).apply(instance, SparkParticleOptions::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SparkParticleOptions> STREAM_CODEC =
            StreamCodec.of(
                    (buf, options) -> ByteBufCodecs.VECTOR3F.encode(buf, options.color()),
                    buf -> new SparkParticleOptions(ByteBufCodecs.VECTOR3F.decode(buf)));
}
