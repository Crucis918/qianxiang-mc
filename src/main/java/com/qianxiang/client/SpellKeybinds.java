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
 * 注册千相客户端按键：G 键施放法术（默认绑定，可在键位设置更改）。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class SpellKeybinds {

    public static final KeyMapping CAST_SPELL = new KeyMapping(
            "key.qianxiang.cast_spell",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.qianxiang"
    );

    /** 技能树界面（K）。 */
    public static final KeyMapping SKILL_TREE = new KeyMapping(
            "key.qianxiang.skill_tree",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
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
