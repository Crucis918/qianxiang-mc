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
 *   <li>{@code seq} —— 客户端自增请求序号：服务端在 {@link AiResponsePayload} 里原样带回，
 *       客户端据此丢弃落后响应（WQ-76）</li>
 * </ul>
 */
public record AiRequestPayload(int seq, String request, String targetType, String targetTier,
                               List<String> currentMaterials, String mode,
                               List<String> allowedMaterials) implements CustomPacketPayload {

    /** 含序号的六参构造：allowedMaterials 空 = 不限制。 */
    public AiRequestPayload(int seq, String request, String targetType, String targetTier,
                            List<String> currentMaterials, String mode) {
        this(seq, request, targetType, targetTier, currentMaterials, mode, List.of());
    }

    /** 兼容旧六参构造（无序号）：seq = 0。 */
    public AiRequestPayload(String request, String targetType, String targetTier,
                            List<String> currentMaterials, String mode,
                            List<String> allowedMaterials) {
        this(0, request, targetType, targetTier, currentMaterials, mode, allowedMaterials);
    }

    /** 兼容旧五参构造：allowedMaterials 空 = 不限制。 */
    public AiRequestPayload(String request, String targetType, String targetTier,
                            List<String> currentMaterials, String mode) {
        this(0, request, targetType, targetTier, currentMaterials, mode, List.of());
    }

    /** 玩家需求文本上限（会原样进 prompt）。 */
    public static final int MAX_REQUEST_CHARS = 1024;
    /** targetType/targetTier/mode 这类枚举式短字段上限。 */
    public static final int MAX_SHORT_FIELD_CHARS = 64;
    /** 单个物品 id 上限（namespace:path 远小于此）。 */
    public static final int MAX_ITEM_ID_CHARS = 256;
    /** 材料白名单上限（客户端筛选器产物，正常远小于此）。 */
    public static final int MAX_ALLOWED_MATERIALS = 512;

    public static final Type<AiRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ai_request"));

    public static final StreamCodec<FriendlyByteBuf, AiRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        // 全部字段限长：需求文本进 prompt 直发 LLM（注入+烧 token），
                        // 材料列表每条都触发全注册表扫描，默认上限 32767/Integer.MAX_VALUE 太宽。
                        buf.writeVarInt(p.seq());
                        ByteBufCodecs.stringUtf8(MAX_REQUEST_CHARS).encode(buf, p.request());
                        ByteBufCodecs.stringUtf8(MAX_SHORT_FIELD_CHARS).encode(buf, p.targetType());
                        ByteBufCodecs.stringUtf8(MAX_SHORT_FIELD_CHARS).encode(buf, p.targetTier());
                        ByteBufCodecs.collection(ArrayList::new,
                                        ByteBufCodecs.stringUtf8(MAX_ITEM_ID_CHARS),
                                        com.qianxiang.menu.ForgeTableMenu.MATERIAL_SLOTS)
                                .encode(buf, new ArrayList<>(p.currentMaterials()));
                        ByteBufCodecs.stringUtf8(MAX_SHORT_FIELD_CHARS).encode(buf, p.mode());
                        ByteBufCodecs.collection(ArrayList::new,
                                        ByteBufCodecs.stringUtf8(MAX_ITEM_ID_CHARS), MAX_ALLOWED_MATERIALS)
                                .encode(buf, new ArrayList<>(p.allowedMaterials()));
                    },
                    buf -> new AiRequestPayload(
                            buf.readVarInt(),
                            ByteBufCodecs.stringUtf8(MAX_REQUEST_CHARS).decode(buf),
                            ByteBufCodecs.stringUtf8(MAX_SHORT_FIELD_CHARS).decode(buf),
                            ByteBufCodecs.stringUtf8(MAX_SHORT_FIELD_CHARS).decode(buf),
                            ByteBufCodecs.collection(ArrayList::new,
                                            ByteBufCodecs.stringUtf8(MAX_ITEM_ID_CHARS),
                                            com.qianxiang.menu.ForgeTableMenu.MATERIAL_SLOTS)
                                    .decode(buf),
                            ByteBufCodecs.stringUtf8(MAX_SHORT_FIELD_CHARS).decode(buf),
                            ByteBufCodecs.collection(ArrayList::new,
                                            ByteBufCodecs.stringUtf8(MAX_ITEM_ID_CHARS), MAX_ALLOWED_MATERIALS)
                                    .decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
