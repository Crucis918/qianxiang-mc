package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：{@code /qianxiang shot} 开发调试命令触发的截图请求。
 * <p>
 * 客户端 handler 用原版 F2 同款 {@code Screenshot.grab} 抓整个窗口，
 * 存 {@code run/screenshots/qx_<时间戳>.png}——排查外观/虚影/仪式表现的「游戏内眼睛」。
 * </p>
 */
public record ScreenshotRequestPayload() implements CustomPacketPayload {

    public static final Type<ScreenshotRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "screenshot_request"));

    public static final StreamCodec<FriendlyByteBuf, ScreenshotRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new ScreenshotRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
