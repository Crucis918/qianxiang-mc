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
                data.currentMana(), ClientSpellData.effectiveMaxMana(mc.player));

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 10;
        int y = 10;
        // 深色描边，让文字在各种背景下可读
        graphics.drawString(mc.font, text, x + 1, y, 0x000000, false);
        graphics.drawString(mc.font, text, x - 1, y, 0x000000, false);
        graphics.drawString(mc.font, text, x, y + 1, 0x000000, false);
        graphics.drawString(mc.font, text, x, y - 1, 0x000000, false);
        graphics.drawString(mc.font, text, x, y, 0x00FFFF, false);

        renderCooldownBar(graphics, mc.player, x, y + 11);
        renderLastCast(graphics, mc, data, x, y + 20);
        // 战吼生效剩余秒数（ProficiencySyncPayload 快照；0 = 未生效）
        if (ClientProficiencyData.warcryActiveMs > 0) {
            graphics.drawString(mc.font,
                    Component.translatable("qianxiang.hud.warcry_active",
                            (ClientProficiencyData.warcryActiveMs + 999) / 1000),
                    x, y + 29, 0xFFAA00, false);
        }
    }

    /** 「上次施放：X」行（无记录不显示；法术已忘记则只显示 id 路径）。 */
    private static void renderLastCast(GuiGraphics graphics, Minecraft mc, PlayerSpellData data, int x, int y) {
        String lastId = ClientSpellData.lastCastSpellId();
        if (lastId == null) return;
        var id = net.minecraft.resources.ResourceLocation.tryParse(lastId);
        if (id == null) return;
        Component name = data.findLearned(id)
                .map(spell -> Component.translatable("qianxiang.hud.last_cast",
                        spell.displayName()))
                .orElseGet(() -> Component.translatable("qianxiang.hud.last_cast",
                        Component.literal(id.getPath())));
        graphics.drawString(mc.font, name, x, y, 0xAAAAAA, false);
    }

    /**
     * 法力行下方的冷却条：灰底 + 白色剩余条，冷却结束不绘制。
     * <p>此前冷却完全没有客户端显示——玩家只能靠按键失败的提示知道还在冷却。
     */
    private static void renderCooldownBar(GuiGraphics graphics,
                                          net.minecraft.world.entity.player.Player player,
                                          int x, int y) {
        int remaining = ClientSpellData.remainingCooldownTicks(player);
        if (remaining <= 0) {
            return;
        }
        int baseline = Math.max(1, ClientSpellData.cooldownBaselineTicks());
        int filled = Math.clamp(Math.round(BAR_WIDTH * (remaining / (float) baseline)), 0, BAR_WIDTH);

        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xC0000000); // 描边底
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF3A3A3A);                 // 槽
        graphics.fill(x, y, x + filled, y + BAR_HEIGHT, 0xFFE8E8E8);                    // 剩余冷却
    }

    /** 冷却条尺寸。 */
    private static final int BAR_WIDTH = 80;
    private static final int BAR_HEIGHT = 4;
}
