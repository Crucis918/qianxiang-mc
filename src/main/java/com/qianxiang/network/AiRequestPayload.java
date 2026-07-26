package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端→服务端：玩家锻造台输入框里的自然语言需求 + 当前配置。
 * <p>
 * 字段契约：
 * <ul>
 *   <li>{@code request} —— 玩家自然语言需求</li>
 *   <li>{@code targetType} —— 产物类型：weapon/magic/armor/tool 之一</li>
 *   <li>{@code targetTier} —— 强度档位：common/rare/epic/legendary 之一</li>
 *   <li>{@code currentMaterials} —— 当前材料槽内物品的 registry name 列表（空槽跳过）</li>
 *   <li>{@code mode} —— "recommend"（AI 推荐）或 "confirm"（确认当前材料）</li>
 *   <li>{@code allowedMaterials} —— 玩家在「材料筛选」里勾选的材料白名单（registry name）；
 *       空列表 = 不限制（全选/未勾选筛选，AI 可用全部材料）</li>
 * </ul>
 */
public record AiRequestPayload(String request, String targetType, String targetTier,
                               List<String> currentMaterials, String mode,
                               List<String> allowedMaterials) implements CustomPacketPayload {

    /** 兼容旧五参构造：allowedMaterials 空 = 不限制。 */
    public AiRequestPayload(String request, String targetType, String targetTier,
                            List<String> currentMaterials, String mode) {
        this(request, targetType, targetTier, currentMaterials, mode, List.of());
    }

    public static final Type<AiRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ai_request"));

    public static final StreamCodec<FriendlyByteBuf, AiRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, AiRequestPayload::request,
                    ByteBufCodecs.STRING_UTF8, AiRequestPayload::targetType,
                    ByteBufCodecs.STRING_UTF8, AiRequestPayload::targetTier,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), AiRequestPayload::currentMaterials,
                    ByteBufCodecs.STRING_UTF8, AiRequestPayload::mode,
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8), AiRequestPayload::allowedMaterials,
                    AiRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
