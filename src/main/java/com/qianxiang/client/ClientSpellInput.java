package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端按键监听：V 键（法术键）的<b>按压/释放沿</b>检测。
 * <p>
 * 轮盘施法需要「按住开轮盘、松开来施放」的两段语义，consumeClick 只有
 * 离散点击，给不了沿——改为每 tick 对比 {@code KeyMapping.isDown()} 与上一帧，
 * 检出按下沿调 {@link SpellWheelOverlay#onPress}、释放沿调 {@link SpellWheelOverlay#onRelease}。
 * GUI（screen != null）打开时不触发；player==null 守卫保留。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientSpellInput {

    private ClientSpellInput() {}

    /** 上一帧 V 键是否按住。 */
    private static boolean wasDown;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 主菜单/加载中没有玩家与连接：isDown 安全，但开轮盘/发包不安全。
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            wasDown = false;
            while (SpellKeybinds.CAST_SPELL.consumeClick()) {
                // 丢弃期间积压的点击，避免进世界就连放
            }
            SpellWheelOverlay.resetForWorldChange();
            return;
        }

        boolean down = SpellKeybinds.CAST_SPELL.isDown();
        boolean pressed = down && !wasDown;
        boolean released = !down && wasDown;
        wasDown = down;

        if (mc.screen != null) {
            // GUI 打开时不触发；轮盘开着时被界面打断则取消（指针交给 Screen 管）。
            SpellWheelOverlay.cancelIfActive();
            return;
        }
        if (pressed) {
            SpellWheelOverlay.onPress(mc);
        } else if (released) {
            SpellWheelOverlay.onRelease(mc);
        }
    }
}
