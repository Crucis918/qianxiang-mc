package com.qianxiang.client;

import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 在 HUD 左上角显示当前 mana："法力：cur / max"。
 */
@EventBusSubscriber(modid = com.qianxiang.Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class SpellHudRenderer {

    private SpellHudRenderer() {}

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        PlayerSpellData data = mc.player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        Component text = Component.translatable("qianxiang.hud.mana",
                data.currentMana(), data.maxMana());

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 10;
        int y = 10;
        // 深色描边，让文字在各种背景下可读
        graphics.drawString(mc.font, text, x + 1, y, 0x000000, false);
        graphics.drawString(mc.font, text, x - 1, y, 0x000000, false);
        graphics.drawString(mc.font, text, x, y + 1, 0x000000, false);
        graphics.drawString(mc.font, text, x, y - 1, 0x000000, false);
        graphics.drawString(mc.font, text, x, y, 0x00FFFF, false);
    }
}
