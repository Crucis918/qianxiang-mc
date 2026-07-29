package com.qianxiang.item;

import com.qianxiang.QianxiangArmorMaterials;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.ReverseEffectTable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;

/**
 * 相之防具——「材料即零件」的可穿戴护甲产物载体（头盔/胸甲/护腿/靴子四槽位变体）。
 * <p>
 * 与 {@link QianxiangWeaponItem} 同一条设计线：物品自身不硬编码强度，
 * 防御 / 护甲韧性 / 击退抗性 / 耐久全部来自锻造时写入的
 * {@link ComposedAttributes}（{@code qianxiang:composed_attributes} 组件）。
 * </p>
 * <ul>
 *   <li>耐久：{@code ComposedAttributes.durability()}（override {@code getMaxDamage}）。</li>
 *   <li>穿戴属性：override NeoForge 的 {@code getDefaultAttributeModifiers(ItemStack)}，
 *       按本件槽位（HEAD/CHEST/LEGS/FEET）从组件读出护甲/韧性等写成
 *       {@link ItemAttributeModifiers}——1.21.1 原版 {@code ArmorItem#getDefense()}
 *       不认栈，动态数值必须走这条 NeoForge 扩展路径。
 *       modifier id 带槽位后缀（{@code composed.armor.helmet} 等），
 *       否则全身多件同 id 会互相覆盖。</li>
 *   <li>穿戴被动效果（夜视/迅捷/跳跃/抗性/抗火/水下呼吸/再生）：
 *       等级存在 {@link ComposedAttributes#effects()}，由
 *       {@link com.qianxiang.handler.ArmorPassiveHandler} 每 tick 续杯式兑现。</li>
 * </ul>
 */
public class QianxiangArmorItem extends ArmorItem {
    /** 无 ComposedAttributes 时的兜底耐久（如直接 /give 出来的空壳）。 */
    public static final int DEFAULT_DURABILITY = 150;
    /** 兜底可附魔性。 */
    public static final int DEFAULT_ENCHANTABILITY = 12;

    public QianxiangArmorItem(ArmorItem.Type type, Properties properties) {
        super(QianxiangArmorMaterials.PHASE_HIDE, type, properties);
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null && attr.durability() > 0) {
            return attr.durability();
        }
        return DEFAULT_DURABILITY;
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null) {
            return DEFAULT_ENCHANTABILITY + (int) Math.min(10, attr.powerScore() / 3.0);
        }
        return DEFAULT_ENCHANTABILITY;
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    /**
     * 穿戴生效的属性修饰符：从组件动态构建（栈上没有 ATTRIBUTE_MODIFIERS 组件时被采用）。
     * 无组合属性的空壳回退到材质默认值（皮革水准）。
     */
    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) {
            return super.getDefaultAttributeModifiers(stack);
        }
        EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(getType().getSlot());
        String slotName = getType().getName();
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        addEntry(builder, Attributes.ARMOR, "composed.armor." + slotName, attr.armor(), group);
        addEntry(builder, Attributes.ARMOR_TOUGHNESS, "composed.armor_toughness." + slotName, attr.armorToughness(), group);
        addEntry(builder, Attributes.KNOCKBACK_RESISTANCE, "composed.knockback_resistance." + slotName, attr.knockbackResistance(), group);
        addEntry(builder, Attributes.MAX_HEALTH, "composed.max_health." + slotName, attr.maxHealth(), group);
        addEntry(builder, Attributes.MOVEMENT_SPEED, "composed.move_speed." + slotName, attr.moveSpeed(), group);
        return builder.build();
    }

    private static void addEntry(ItemAttributeModifiers.Builder builder,
                                 net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                                 String path, double value, EquipmentSlotGroup group) {
        if (value == 0.0) return;
        builder.add(attribute,
                new AttributeModifier(ResourceLocation.fromNamespaceAndPath("qianxiang", path),
                        value, AttributeModifier.Operation.ADD_VALUE),
                group);
    }

    /** 带主导外观主题或高强度时自带附魔光泽（与相之武器同一判定线）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null) {
            if (!"none".equals(attr.dominantEffect()) && !"plain".equals(attr.appearanceKey())) {
                return true;
            }
            if (attr.powerScore() >= 8.0) {
                return true;
            }
        }
        return super.isFoil(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) {
            return;
        }
        com.qianxiang.phase.UpgradeRules.appendLegendaryLine(stack, tooltip);
        // 强度总分
        tooltip.add(Component.translatable("qianxiang.tooltip.power_score",
                String.format("%.1f", attr.powerScore())).withStyle(ChatFormatting.GOLD));

        // 反转标志行：材料含逆相之核时提示「已反转」
        if (attr.isReversed()) {
            tooltip.add(Component.translatable("qianxiang.tooltip.reversed")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        // 穿戴被动效果等级（来自材料的功能算子）
        ComposedAttributes.EffectLevels effects = attr.effects();
        if (effects != null) {
            appendEffect(tooltip, "qianxiang.phasefn.night_vision", effects.nightVision(), ChatFormatting.AQUA);
            appendEffect(tooltip, "qianxiang.phasefn.speed_boost", effects.speedBoost(), ChatFormatting.YELLOW);
            appendEffect(tooltip, "qianxiang.phasefn.jump_boost", effects.jumpBoost(), ChatFormatting.GREEN);
            appendEffect(tooltip, "qianxiang.phasefn.resistance", effects.resistance(), ChatFormatting.DARK_GRAY);
            appendEffect(tooltip, "qianxiang.phasefn.fire_resist", effects.fireResist(), ChatFormatting.RED);
            appendEffect(tooltip, "qianxiang.phasefn.water_breath", effects.waterBreath(), ChatFormatting.BLUE);
            appendEffect(tooltip, "qianxiang.phasefn.regeneration", effects.regeneration(), ChatFormatting.LIGHT_PURPLE);
        }

        // 自由状态效果（grantedEffects，来自 qianxiang:materials/effect/* tag 材料）：
        // 已反转 → 显示抗性行（命中 ReverseEffectTable 的攻击效果转抗性/免疫）；
        // 未反转 → 显示「接触反伤」行（被攻击时攻击者中这些效果，见 ArmorEffectHandler）。
        for (var entry : attr.grantedEffects().entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            if (attr.isReversed()) {
                if (ReverseEffectTable.hasReversal(entry.getKey())) {
                    tooltip.add(Component.translatable("qianxiang.tooltip.armor_reverse",
                            Component.translatable(ReverseEffectTable.resistanceNameKey(entry.getKey())),
                            entry.getValue()).withStyle(ChatFormatting.GREEN));
                    continue;
                }
                tooltip.add(Component.translatable("qianxiang.tooltip.armor_passive",
                        QianxiangWeaponItem.effectName(entry.getKey()), entry.getValue())
                        .withStyle(ChatFormatting.DARK_PURPLE));
                continue;
            }
            tooltip.add(Component.translatable("qianxiang.tooltip.armor_contact",
                    QianxiangWeaponItem.effectName(entry.getKey()), entry.getValue())
                    .withStyle(ChatFormatting.DARK_PURPLE));
        }

        // 点燃：已反转 → 防具兑现为抗火；未反转 → 接触点燃（被攻击时点燃攻击者）
        if (attr.igniteLevel() > 0) {
            if (attr.isReversed()) {
                tooltip.add(Component.translatable("qianxiang.tooltip.armor_reverse",
                        Component.translatable(ReverseEffectTable.LANG_KEY_PREFIX + "ignite"),
                        attr.igniteLevel()).withStyle(ChatFormatting.GREEN));
            } else {
                tooltip.add(Component.translatable("qianxiang.tooltip.armor_contact",
                        Component.translatable("qianxiang.phasefn.ignite"),
                        attr.igniteLevel()).withStyle(ChatFormatting.DARK_PURPLE));
            }
        }

        // 代价效果（DrawbackLevels）：红色警示行「代价：xxx ×N」
        QianxiangWeaponItem.appendDrawbacks(attr, tooltip);
    }

    private static void appendEffect(List<Component> tooltip, String key, int level, ChatFormatting color) {
        if (level <= 0) return;
        tooltip.add(Component.translatable("qianxiang.tooltip.armor_passive",
                Component.translatable(key), level).withStyle(color));
    }
}
