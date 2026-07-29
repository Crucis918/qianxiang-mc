package com.qianxiang.client;

import com.qianxiang.network.ComboSyncPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 荣耀连招 HUD：屏幕中右显示大字号「N 连击！」。
 * <p>
 * 显示规则：
 * </p>
 * <ul>
 *   <li>N≥3 才显示（低于 3 没有伤害加成，不值得占屏）；金色、2× 字号居中右；</li>
 *   <li>连击数每次变化带 0.3s 弹出缩放（1.6× → 1.0×）；</li>
 *   <li>断连（收到 combo&lt;3 的权威同步，或本地超过 5s 没等到新施法同步——
 *       服务端不为超时专门发包）时灰色 0.5s 淡出。</li>
 * </ul>
 * <p>状态来源：{@link ComboSyncPayload}（每次成功施法由服务端下发）。</p>
 */
@EventBusSubscriber(modid = com.qianxiang.Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ComboHudRenderer {

    private ComboHudRenderer() {}

    /** 显示的最低连击数（与服务端伤害加成阈值一致）。 */
    private static final int MIN_SHOW = 3;
    /** 弹出动画时长（毫秒）。 */
    private static final long POP_MS = 300L;
    /** 断连灰色淡出时长（毫秒）。 */
    private static final long FADE_MS = 500L;
    /** 本地兜底窗口：超过 5s 无新施法同步视为断连（对应服务端 100 tick 窗口）。 */
    private static final long WINDOW_MS = 5_000L;
    /** 基础字号倍率（大字号）。 */
    private static final float BASE_SCALE = 2.0f;

    private static int combo;
    private static long lastSyncMs;
    private static long popStartMs = -1L;
    private static long breakStartMs = -1L;
    private static int brokenCombo;

    /** 在客户端主线程应用服务端下发的连击数。 */
    public static void receive(ComboSyncPayload payload) {
        int next = Math.max(0, payload.combo());
        long now = Util.getMillis();
        if (next >= MIN_SHOW && next != combo) {
            popStartMs = now;      // 连击数变化才重播弹出动画
            breakStartMs = -1L;    // 重新连上，取消断连淡出
        }
        if (next < MIN_SHOW && combo >= MIN_SHOW) {
            brokenCombo = combo;   // 断连：记住断在哪一连，灰色淡出用
            breakStartMs = now;
        }
        combo = next;
        lastSyncMs = now;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = Util.getMillis();

        if (combo >= MIN_SHOW && now - lastSyncMs > WINDOW_MS) {
            // 本地窗口兜底：超 5s 没有新施法，转入断连淡出
            // （服务端下一次施法会发权威值纠正，这里只影响显示）
            brokenCombo = combo;
            breakStartMs = now;
            combo = 0;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        if (combo >= MIN_SHOW) {
            float pop = popStartMs >= 0 && now - popStartMs < POP_MS
                    ? 1.0f + 0.6f * (1.0f - (now - popStartMs) / (float) POP_MS)
                    : 1.0f;
            render(graphics, mc, combo, pop, 0xFFFFD700); // 金色
            return;
        }
        if (breakStartMs >= 0) {
            long elapsed = now - breakStartMs;
            if (elapsed >= FADE_MS) {
                breakStartMs = -1L;
                return;
            }
            int alpha = 255 - (int) (255L * elapsed / FADE_MS);
            render(graphics, mc, brokenCombo, 1.0f, (alpha << 24) | 0x999999); // 灰色淡出
        }
    }

    /** 中右锚点 + 缩放绘制「N 连击！」。 */
    private static void render(GuiGraphics graphics, Minecraft mc, int n, float scaleMult, int color) {
        Component text = Component.translatable("qianxiang.hud.combo", n);
        float scale = BASE_SCALE * scaleMult;
        int cx = mc.getWindow().getGuiScaledWidth() * 3 / 4;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        graphics.pose().pushPose();
        graphics.pose().translate(cx, cy, 0.0f);
        graphics.pose().scale(scale, scale, 1.0f);
        graphics.drawString(mc.font, text, -mc.font.width(text) / 2, -mc.font.lineHeight / 2, color, true);
        graphics.pose().popPose();
    }
}
