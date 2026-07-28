package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：截图请求（{@code /qianxiang shot} 与 dev 自动冒烟装置共用）。
 * <p>
 * 客户端 handler 用原版 F2 同款 {@code Screenshot.grab} 抓整个窗口，
 * 存 {@code run/screenshots/qx_<时间戳>.png}。
 * 字段：
 * <ul>
 *   <li>{@code perspective} —— 拍前切视角：0=不切 / 1=第一人称 / 2=第三人称背面
 *       （手持 3D 验收需要第三人称）。</li>
 *   <li>{@code closeScreen} —— 拍前先关闭当前界面（技能树等 Screen 验收完收尾用）；
 *       背包屏不拍：图标验收靠手持+台子虚影，避免再加 openInventory 语义（见类注释决策）。</li>
 * </ul>
 * 视角/界面变化在拍前 2 tick 应用，等一帧新画面进帧缓冲再抓。
 * </p>
 */
public record ScreenshotRequestPayload(int perspective, boolean closeScreen)
        implements CustomPacketPayload {

    public static final Type<ScreenshotRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "screenshot_request"));

    public static final StreamCodec<FriendlyByteBuf, ScreenshotRequestPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ScreenshotRequestPayload::perspective,
                    ByteBufCodecs.BOOL, ScreenshotRequestPayload::closeScreen,
                    ScreenshotRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
