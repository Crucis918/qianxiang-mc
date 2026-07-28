package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 注册千相客户端按键。
 * <p>
 * 默认键避让清单（实机 options.txt 核实）：Epic Fight 占用
 * R（特殊技能）/ K（技能界面）/ G（锁定）/ LAlt（翻滚）/ Y（武器格挡）
 * 以及鼠标左右键、空格、Shift 等原版键。故本模组默认键选 B/O/J——
 * 均不在 EF 与原版占用之列；此前 G/K 默认值与 EF 正面冲突，已迁移。
 * 注意：options.txt 里已存在的旧绑定不受代码默认值影响，需玩家/整合包迁移。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class SpellKeybinds {

    /** 施放法术（B）：按住开轮盘、松开施放。 */
    public static final KeyMapping CAST_SPELL = new KeyMapping(
            "key.qianxiang.cast_spell",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.qianxiang"
    );

    /** 技能树界面（O）。 */
    public static final KeyMapping SKILL_TREE = new KeyMapping(
            "key.qianxiang.skill_tree",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            "key.categories.qianxiang"
    );

    /** 主动技能：J=战吼，潜行+J=法力涌动。 */
    public static final KeyMapping ACTIVATE_SKILL = new KeyMapping(
            "key.qianxiang.activate_skill",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            "key.categories.qianxiang"
    );

    private SpellKeybinds() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CAST_SPELL);
        event.register(SKILL_TREE);
        event.register(ACTIVATE_SKILL);
    }
}
