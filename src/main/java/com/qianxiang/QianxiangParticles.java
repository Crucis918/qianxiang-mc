package com.qianxiang;

import com.mojang.serialization.MapCodec;
import com.qianxiang.particle.ShockwaveParticleOptions;
import com.qianxiang.particle.SparkParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 自定义粒子类型注册（spark 火花 / shockwave 冲击波环）。
 * <p>
 * 客户端渲染见 {@code client/particle/}，provider 注册见 {@code client/ClientSetup}；
 * 服务端经 {@code ServerLevel#sendParticles} 下发（options 自带 streamCodec）。
 * </p>
 */
public final class QianxiangParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Qianxiang.MOD_ID);

    public static final DeferredHolder<ParticleType<?>, ParticleType<SparkParticleOptions>> SPARK =
            PARTICLE_TYPES.register("spark", () -> new ParticleType<SparkParticleOptions>(false) {
                @Override
                public MapCodec<SparkParticleOptions> codec() {
                    return SparkParticleOptions.CODEC;
                }

                @Override
                public StreamCodec<? super RegistryFriendlyByteBuf, SparkParticleOptions> streamCodec() {
                    return SparkParticleOptions.STREAM_CODEC;
                }
            });

    public static final DeferredHolder<ParticleType<?>, ParticleType<ShockwaveParticleOptions>> SHOCKWAVE =
            PARTICLE_TYPES.register("shockwave", () -> new ParticleType<ShockwaveParticleOptions>(false) {
                @Override
                public MapCodec<ShockwaveParticleOptions> codec() {
                    return ShockwaveParticleOptions.CODEC;
                }

                @Override
                public StreamCodec<? super RegistryFriendlyByteBuf, ShockwaveParticleOptions> streamCodec() {
                    return ShockwaveParticleOptions.STREAM_CODEC;
                }
            });

    private QianxiangParticles() {}
}
