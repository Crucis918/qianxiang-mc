package com.qianxiang.client;

import com.qianxiang.spell.CustomSpell;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 法术显示名解析（仅客户端——{@link I18n} 是 dist 敏感类，服务端类严禁触碰）。
 * <p>
 * 优先级：真名 {@code qianxiang.spell.name.<id.path>}（卷轴模板 26 条全配）
 * → {@code spell.<ns>.<path>} 翻译键（预置/旧锻造成语）
 * → 「元素·效果」拼接（复用本地化键，AI 法术也不裸显翻译键）。
 * </p>
 */
public final class ClientSpellNames {

    private ClientSpellNames() {}

    /** 法术显示名：真名 > 旧翻译键 > 元素·效果拼接。 */
    public static Component displayName(CustomSpell spell) {
        String trueKey = "qianxiang.spell.name." + spell.id().getPath();
        if (I18n.exists(trueKey)) {
            return Component.translatable(trueKey);
        }
        String key = "spell." + spell.id().getNamespace() + "." + spell.id().getPath();
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("qianxiang.spell.wheel.name",
                CustomSpell.elementName(spell.element()),
                CustomSpell.effectName(spell.effect()));
    }
}
