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
 * 服务端→客户端：AI 放料后告知「请求了但没放进」的缺料物品 id 列表。
 * <p>
 * 此前服务端直接用 {@code new ItemStack(item).getHoverName()} 拼缺料名单再发
 * actionbar——hoverName 按<b>服务端</b>语言环境解析（en_us），中文客户端看到英文物品名。
 * 改为只发物品 id，客户端用本地语言环境的 {@code ItemStack#getHoverName()} 渲染，
 * lang 框架键仍用 {@code qianxiang.table.missing}（渲染见
 * {@link com.qianxiang.client.ClientMissingMaterialsNotice}）。
 * </p>
 */
public record MissingMaterialsPayload(List<String> itemIds) implements CustomPacketPayload {

    /** 唯一类型 id：qianxiang:missing_materials。 */
    public static final Type<MissingMaterialsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "missing_materials"));

    /**
     * 网络编解码器：VAR_INT 长度的物品 id（"namespace:path"）列表。
     * 上限同 {@link AiPlaceMaterialsPayload}——缺料名单不可能超过请求的槽数，
     * 不带 maxSize 的重载默认 Integer.MAX_VALUE，恶意包会放大注册表查询成本。
     */
    public static final StreamCodec<FriendlyByteBuf, MissingMaterialsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8,
                            com.qianxiang.menu.ForgeTableMenu.MATERIAL_SLOTS),
                    MissingMaterialsPayload::itemIds,
                    MissingMaterialsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
