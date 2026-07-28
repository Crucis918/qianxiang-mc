package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 「游戏内截图眼」的客户端执行端：收到 {@code ScreenshotRequestPayload} 后
 * 用原版 F2 同款 {@link Screenshot#grab} 抓整个窗口（含 GUI/手持/虚影/粒子），
 * 存 {@code run/screenshots/qx_<时间戳>.png} 并在聊天栏回执路径。
 * <p>仅客户端：本类只被 S2C 回包 lambda 惰性引用，服务端不加载。</p>
 */
public final class ClientScreenshot {

    private ClientScreenshot() {}

    /** 抓一帧整窗（调用方已在客户端主线程=渲染线程）。 */
    public static void grab() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            String name = "qx_" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
            // grab 内部按名字去重（同秒连拍 → qx_<ts>_1.png），目录自动建
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
                    component -> mc.player.displayClientMessage(component, false));
            Qianxiang.LOGGER.info("[Qianxiang] 截图已保存：screenshots/{}.png", name);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 截图失败：{}", t.toString());
        }
    }
}
