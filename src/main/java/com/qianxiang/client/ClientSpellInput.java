package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端按键监听：G 键（法术键）按压/释放沿 + K 键（技能树）+ J 键（主动技能）。
 * <p>
 * 轮盘施法需要「按住开轮盘、松开来施放」的两段语义，consumeClick 只有
 * 离散点击，给不了沿——改为每 tick 对比 {@code KeyMapping.isDown()} 与上一帧。
 * GUI（screen != null）打开时不触发；player==null 守卫保留。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class ClientSpellInput {

    private ClientSpellInput() {}

    /** 上一帧 G 键是否按住。 */
    private static boolean wasDown;
    /** 上一帧 K/J 键状态（边沿检测）。 */
    private static boolean wasSkillTreeDown;
    private static boolean wasSkillDown;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // 主菜单/加载中没有玩家与连接：isDown 安全，但开轮盘/发包不安全。
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            wasDown = false;
            wasSkillTreeDown = false;
            wasSkillDown = false;
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

        // 技能树（K）：边沿打开；相师 OpenSkillTreePayload 的 pendingOpenTree 也在此消费
        boolean treeDown = SpellKeybinds.SKILL_TREE.isDown();
        if (treeDown && !wasSkillTreeDown && mc.screen == null) {
            mc.setScreen(new SkillTreeScreen());
        }
        wasSkillTreeDown = treeDown;
        if (ClientProficiencyData.pendingOpenTree && mc.screen == null) {
            ClientProficiencyData.pendingOpenTree = false;
            mc.setScreen(new SkillTreeScreen());
        }

        // 主动技能（J=战吼，潜行+J=涌动）：客户端先查已解锁节点，未解锁直接提示不发包
        boolean skillDown = SpellKeybinds.ACTIVATE_SKILL.isDown();
        if (skillDown && !wasSkillDown && mc.screen == null) {
            String skillId = mc.player.isShiftKeyDown() ? "surge" : "warcry";
            if (ClientProficiencyData.allocated.contains(skillId)) {
                try {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.qianxiang.network.ActivateSkillPayload(skillId));
                } catch (Throwable t) {
                    Qianxiang.LOGGER.warn("[Qianxiang] 发送主动技能包失败", t);
                }
            } else {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "qianxiang.proficiency.skill.locked"), true);
            }
        }
        wasSkillDown = skillDown;

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
