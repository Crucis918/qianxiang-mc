package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 「伤害浮字」网络包：服务端→客户端，告诉客户端「这个实体被打了多少血」。
 * <p>
 * vanilla 不显示伤害数字，玩家完全看不见自己砍了多少。这个包把
 * {@link com.qianxiang.combat.CombatEffectHandler} 算出的最终伤害
 * （{@code LivingDamageEvent.Post#getNewDamage}）单发给「追踪该目标的所有玩家」，
 * 客户端拿到后在目标头顶画向上漂的浮动数字。
 * </p>
 * <p>API 查证（NeoForge 1.21.1, neoforge-21.1.219）：</p>
 * <ul>
 *   <li>实现 {@code net.minecraft.network.protocol.common.custom.CustomPacketPayload}
 *       （非旧 CustomPayload），只需实现 {@link #type()}。</li>
 *   <li>StreamCodec 走 {@link StreamCodec#composite} + {@link ByteBufCodecs#VAR_INT}/
 *       {@link ByteBufCodecs#FLOAT}（javap 确认 {@code ByteBufCodecs.VAR_INT/FLOAT}
 *       都是 {@code StreamCodec<ByteBuf, ...>}，可被 RegistryFriendlyByteBuf 解析）。</li>
 *   <li>Type 用 {@link CustomPacketPayload#createType(String)} 构造，参数是 "modid:path"。</li>
 * </ul>
 * <p>record 不可变：{@code targetEntityId}（{@link net.minecraft.world.entity.Entity#getId()}
 * 的客户端镜像）+ {@code amount}（造成的伤害）。</p>
 */
public record DamageNumberPayload(int targetEntityId, float amount) implements CustomPacketPayload {

    /** 唯一类型 id：qianxiang:damage_number。 */
    public static final Type<DamageNumberPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "damage_number"));

    /**
     * 网络编解码器：先写 VAR_INT（实体 id），再写 FLOAT（伤害值）。
     * 与 {@link FriendlyByteBuf#readVarInt()} / {@link FriendlyByteBuf#readFloat()}
     * 的顺序严格对应。
     */
    public static final StreamCodec<FriendlyByteBuf, DamageNumberPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DamageNumberPayload::targetEntityId,
                    ByteBufCodecs.FLOAT, DamageNumberPayload::amount,
                    DamageNumberPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
