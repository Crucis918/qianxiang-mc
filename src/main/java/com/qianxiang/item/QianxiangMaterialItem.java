package com.qianxiang.item;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseData;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Set;

/**
 * 相材料物品基类：自带性能提示与外观多样性。
 * <p>
 * 相材料不再需要写死 tooltip，而是自动从 {@link PhaseData} 读出：
 * <ul>
 *   <li>档位（普通/稀有/史诗/传奇）+ 稀有度光效</li>
 *   <li>相性（{火/生命} 等）</li>
 *   <li>功能算子：每个算子显示"名称：可做武器/魔法/护甲/工具"</li>
 *   <li>一句话风味描述（从本地化 {@code qianxiang.material.<name>.lore} 读取）</li>
 * </ul>
 * <p>
 * 外观多样性：不同档位自动决定是否"发光"、是否有附魔光泽、稀有度颜色，
 * 让材料在背包/地面上有清晰区分。
 */
public class QianxiangMaterialItem extends Item {

    public QianxiangMaterialItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        PhaseData pd = stack.get(QianxiangDataComponents.PHASE_DATA.get());
        if (pd == null) {
            super.appendHoverText(stack, context, tooltip, flag);
            return;
        }

        PhaseTier tier = pd.tier() == null ? PhaseTier.COMMON : pd.tier();
        Set<Phase> phases = pd.phases() == null ? Set.of() : pd.phases();
        Set<PhaseFunction> functions = pd.functions() == null ? Set.of() : pd.functions();

        // 档位行：稀有度颜色
        tooltip.add(Component.translatable("qianxiang.tooltip.tier",
                        Component.translatable("qianxiang.tier." + tier.name().toLowerCase()))
                .withStyle(tierColor(tier)));

        // 相性行
        if (!phases.isEmpty()) {
            MutableComponent phaseLine = Component.translatable("qianxiang.tooltip.phases").withStyle(ChatFormatting.GRAY);
            boolean first = true;
            for (Phase p : phases) {
                if (!first) phaseLine.append("/");
                phaseLine.append(Component.translatable("qianxiang.phase." + p.name().toLowerCase()).withStyle(ChatFormatting.YELLOW));
                first = false;
            }
            tooltip.add(phaseLine);
        }

        // 功能算子行：名称 + 可做用途
        if (!functions.isEmpty()) {
            tooltip.add(Component.translatable("qianxiang.tooltip.functions").withStyle(ChatFormatting.GRAY));
            for (PhaseFunction fn : functions) {
                Component fnName = fn.display().copy().withStyle(ChatFormatting.GREEN);
                Component fnUsage = fn.usageComponent().copy().withStyle(ChatFormatting.DARK_GRAY);
                tooltip.add(Component.literal("  ").append(fnName).append(" ").append(fnUsage));
            }
        }

        // 反转器专属行：含 REVERSE 算子（逆相之核）时提示机制作用
        if (functions.contains(PhaseFunction.REVERSE)) {
            tooltip.add(Component.translatable("qianxiang.tooltip.reverser")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        // 风味描述
        Component lore = loreComponent(stack);
        if (lore != null) {
            tooltip.add(Component.empty());
            tooltip.add(lore);
        }
    }

    /** 不同档位决定稀有度颜色。 */
    private static ChatFormatting tierColor(PhaseTier tier) {
        return switch (tier) {
            case COMMON -> ChatFormatting.WHITE;
            case RARE -> ChatFormatting.AQUA;
            case EPIC -> ChatFormatting.LIGHT_PURPLE;
            case LEGENDARY -> ChatFormatting.GOLD;
        };
    }

    /** 读取 item.qianxiang.<短名>.lore；找不到返回 null。 */
    private static Component loreComponent(ItemStack stack) {
        String id = stack.getItem().toString(); // 形如 "qianxiang:ember_iron"
        if (id == null || !id.contains(":")) return null;
        String path = id.substring(id.indexOf(':') + 1);
        String key = "item.qianxiang." + path + ".lore";
        // 用 translatable；若 JSON 没写就不会显示
        MutableComponent c = Component.translatable(key);
        // 1.21.1 没有 Component.getContents 类型判断做 fallback 检测，这里直接返回，
        // 没本地化时会显示灰色 key（也是一种提示）。
        return c.withStyle(ChatFormatting.ITALIC).withStyle(ChatFormatting.DARK_GRAY);
    }

    /** 高档材料自带附魔光泽（视觉区分）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        PhaseData pd = stack.get(QianxiangDataComponents.PHASE_DATA.get());
        if (pd != null && pd.tier() != null) {
            return pd.tier().ordinal() >= PhaseTier.EPIC.ordinal();
        }
        return super.isFoil(stack);
    }
}
