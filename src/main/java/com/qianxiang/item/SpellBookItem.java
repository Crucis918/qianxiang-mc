package com.qianxiang.item;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 千相法术书 —— 法术增幅器（增幅器化后不再承载/施放法术）。
 * <p>
 * 与相杖同规则：仅凭 {@code qianxiang:composed_attributes} 组件里的
 * spellPowerPercent（法术伤害 +%）/ manaBonus（法力上限 +X）为施法提供加成，
 * 主手+副手同时生效、效果叠加（见 {@link com.qianxiang.spell.AmplifierHelper}）。
 * 法术本体在玩家已学列表里，施法走轮盘/V 键链路。
 * </p>
 * <p>旧存档的 {@code qianxiang:spellbook} 组件（{@link com.qianxiang.spell.SpellBookData}）
 * 由 {@code LegacySpellMigration} 在登录时迁入已学列表并剥离，本类不再读取它。</p>
 */
public class SpellBookItem extends Item {

    public SpellBookItem(Properties properties) {
        super(properties);
    }

    /** 法术书恒带附魔光泽。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) {
            return;
        }
        // 增幅器数值：法术伤害加成 / 法力上限加成，非 0 才显示
        if (attr.spellPowerPercent() > 0) {
            tooltip.add(Component.translatable("qianxiang.tooltip.spell_power",
                    String.format("%.0f", attr.spellPowerPercent())).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (attr.manaBonus() > 0) {
            tooltip.add(Component.translatable("qianxiang.tooltip.mana_bonus",
                    attr.manaBonus()).withStyle(ChatFormatting.AQUA));
        }
    }
}
