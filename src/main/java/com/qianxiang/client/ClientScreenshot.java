package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.network.ScreenshotRequestPayload;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 「游戏内截图眼」的客户端执行端：收到 {@link ScreenshotRequestPayload} 后
 * 用原版 F2 同款 {@link Screenshot#grab} 抓整个窗口（含 GUI/手持/虚影/粒子），
 * 存 {@code run/screenshots/qx_<时间戳>.png} 并在聊天栏回执路径。
 * <p>
 * 视角切换/关屏请求在收包时立即应用，但帧缓冲里是<b>上一帧</b>的画面——
 * 延迟 2 tick 再抓，等新视角/新界面渲染进缓冲（否则第三人称拍到的还是第一人称）。
 * </p>
 * <p>仅客户端：本类只被 S2C 回包 lambda 惰性引用，服务端不加载。</p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientScreenshot {

    private ClientScreenshot() {}

    /** 距离下次抓帧的 tick 数（-1 = 无待抓请求）。 */
    private static int pendingTicks = -1;

    /** 收包入口：应用视角/关屏请求，2 tick 后抓帧。 */
    public static void grab(ScreenshotRequestPayload payload) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (payload.closeScreen() && mc.screen != null) {
                mc.setScreen(null);
            }
            if (payload.perspective() == 1) {
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            } else if (payload.perspective() == 2) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            pendingTicks = 2;
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 截图预处理失败：{}", t.toString());
        }
    }

    /** 到点抓帧（延迟见类文档）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pendingTicks <= 0) return;
        if (--pendingTicks > 0) return;
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
