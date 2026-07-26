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
 * 客户端→服务端：玩家点击了某条 AI 推荐方案，请求把方案中的材料放入锻造台材料槽。
 * <p>
 * 服务端收到后校验：
 * <ol>
 *   <li>玩家当前打开的菜单必须是锻造台菜单</li>
 *   <li>玩家背包中确实有对应材料</li>
 *   <li>材料槽有空位（或可堆叠同种物品）</li>
 * </ol>
 * 若材料槽已满，服务端会向客户端回一条提示（目前通过聊天栏/动作栏提示，避免新增包）。
 */
public record AiPlaceMaterialsPayload(List<String> materialNames) implements CustomPacketPayload {

    public static final Type<AiPlaceMaterialsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "ai_place_materials"));

    public static final StreamCodec<FriendlyByteBuf, AiPlaceMaterialsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    // 上限 = 锻造台槽数：不带 maxSize 的重载默认 Integer.MAX_VALUE，
                    // 恶意包可塞上万条 id，每条都触发注册表查询 + 全背包扫描（主线程）。
                    ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8,
                            com.qianxiang.menu.ForgeTableMenu.MATERIAL_SLOTS),
                    AiPlaceMaterialsPayload::materialNames,
                    AiPlaceMaterialsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
