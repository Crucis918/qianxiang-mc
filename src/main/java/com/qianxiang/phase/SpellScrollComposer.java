package com.qianxiang.phase;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 炼金台卷轴组合器：把材料槽内容实时组合成一张「魔法卷轴」。
 * <p>
 * 与 {@link ForgeComposer} 的关系：法术模板表（SPELL_TEMPLATES）就是锻造台
 * 「材料算子 → 法术」映射迁回——增幅器化后法杖/魔法书不再铭刻法术，
 * 法术产出统一收口到炼金台的卷轴。
 * </p>
 * <h3>组合规则</h3>
 * <ul>
 *   <li>材料算子并集按优先级命中 {@link #SPELL_TEMPLATES} 第一条 → 法术主题
 *       （元素/形式/效果/修饰）；都没命中时兜底奥术飞弹。</li>
 *   <li>power = clamp(最高材料档位序数 + 1, 1, 材料预算)——强度靠材料，
 *       预算 = 4 + 2×最高档位序数（COMMON=4 / RARE=6 / EPIC=8 / LEGENDARY=10，
 *       与 WQ-1 的 spellJson 预算同一口径）。</li>
 *   <li>耗魔 = 8 + 6×power；材料含 MANA 算子时 -4（下限 1）——法力材料让卷轴更省蓝。
 *       冷却 = 30 + 15×power（tick）。</li>
 *   <li>AI 提案的 spellJson 经 {@link CustomSpell#fromSpellJson}（唯一校验入口，
 *       同一 maxPower 钳制）后<b>优先于</b>模板结果；无效时回退模板。</li>
 * </ul>
 */
public final class SpellScrollComposer {

    private SpellScrollComposer() {}

    /** 组合结果：卷轴产物栈 + 法术（供 UI 预览）。 */
    public record Composition(ItemStack result, CustomSpell spell) {
        /** 空结果：无产物、无法术。 */
        public static Composition empty() {
            return new Composition(ItemStack.EMPTY, null);
        }
        /** 产物非空即视为有效组合。 */
        public boolean valid() {
            return !result.isEmpty();
        }
    }

    /**
     * 无 AI 覆盖的入口，等价于 {@code compose(materialStacks, null)}。
     * 空输入、或所有材料都无功能算子（纯辅料）→ 返回 {@link Composition#empty()}。
     */
    public static Composition compose(List<ItemStack> materialStacks) {
        return compose(materialStacks, null);
    }

    /**
     * 把材料槽的 stacks 组合成一张卷轴，可附带最近一次 AI 响应的 spellJson 覆盖。
     *
     * @param materialStacks 材料槽内容（含空栈也安全）
     * @param aiSpellJson    服务端为该玩家暂存的选中提案 spellJson（可空）；
     *                       有效时优先于材料模板结果，无效回退模板
     */
    public static Composition compose(List<ItemStack> materialStacks, String aiSpellJson) {
        if (materialStacks == null || materialStacks.isEmpty()) {
            return Composition.empty();
        }

        // 1. 解析每条非空材料：功能算子 + 档位（与 ForgeComposer 同一套解析器）
        Set<PhaseFunction> union = EnumSet.noneOf(PhaseFunction.class);
        int maxTier = 0;
        boolean anyPart = false;
        for (ItemStack stack : materialStacks) {
            if (stack == null || stack.isEmpty()) continue;
            Set<PhaseFunction> functions = PhaseFunctionResolver.get(stack);
            if (functions.isEmpty()) continue;  // 无功能算子，不算零件
            anyPart = true;
            union.addAll(functions);
            maxTier = Math.max(maxTier, PhaseFunctionResolver.resolveTier(stack).ordinal());
        }
        if (!anyPart) {
            return Composition.empty();
        }

        // 2. AI spellJson 优先（唯一校验入口 + 材料预算钳制）；无效回退模板
        int powerBudget = 4 + 2 * maxTier;
        CustomSpell spell = null;
        if (aiSpellJson != null && !aiSpellJson.isBlank()) {
            try {
                spell = CustomSpell.fromSpellJson(aiSpellJson, maxTier + 1, powerBudget);
            } catch (Throwable t) {
                Qianxiang.LOGGER.warn("[Qianxiang] 炼金台应用 AI spellJson 失败（回退材料模板）", t);
            }
        }
        if (spell == null) {
            spell = templateSpell(union, maxTier);
        }
        if (spell == null) {
            return Composition.empty();
        }

        ItemStack out = new ItemStack(QianxiangItems.MAGIC_SCROLL.get());
        out.set(QianxiangDataComponents.CUSTOM_SPELL.get(), spell);
        return new Composition(out, spell);
    }

    // ============================ 材料模板路径 ============================

    /**
     * 材料功能算子 → 法术模板 映射表（按优先级取第一个命中）。
     * 每条：算子 → (id 路径, 元素, 形式, 效果, 修饰)。
     * 从锻造台 buildSpellBook 迁回（增幅器化后法术产出收口到炼金台）。
     */
    private record SpellTemplate(String path, String element, String form, String effect, List<String> modifiers) {}

    private static final List<Map.Entry<PhaseFunction, SpellTemplate>> SPELL_TEMPLATES = List.of(
            Map.entry(PhaseFunction.IGNITE, new SpellTemplate("forged_fireball", "fire", "projectile", "damage", List.of())),
            Map.entry(PhaseFunction.HEAL, new SpellTemplate("forged_nature_heal", "nature", "self", "heal", List.of())),
            Map.entry(PhaseFunction.SLOW, new SpellTemplate("forged_ice_shard", "frost", "projectile", "damage", List.of("piercing"))),
            Map.entry(PhaseFunction.REFLECT, new SpellTemplate("forged_holy_ward", "holy", "self", "buff", List.of("extended"))),
            Map.entry(PhaseFunction.LIFESTEAL, new SpellTemplate("forged_blood_drain", "blood", "touch", "damage", List.of())),
            Map.entry(PhaseFunction.FROST, new SpellTemplate("forged_frost_nova", "frost", "aoe", "debuff", List.of("extended"))),
            Map.entry(PhaseFunction.POISON, new SpellTemplate("forged_venom", "nature", "touch", "debuff", List.of())),
            Map.entry(PhaseFunction.LEVITATION, new SpellTemplate("forged_levitate", "ender", "self", "utility", List.of())),
            Map.entry(PhaseFunction.GROWTH, new SpellTemplate("forged_growth", "nature", "aoe", "utility", List.of())),
            // MANA 兜底放最后：纯法力材料也给一个可用的奥术飞弹。
            Map.entry(PhaseFunction.MANA, new SpellTemplate("forged_arcane_bolt", "arcane", "projectile", "damage", List.of("homing")))
    );

    /** 兜底模板：材料算子一个都不命中映射表时（如纯基底），仍给一发奥术飞弹。 */
    private static final SpellTemplate FALLBACK =
            new SpellTemplate("forged_arcane_bolt", "arcane", "projectile", "damage", List.of("homing"));

    /** 按材料算子并集从模板表生成法术；强度/消耗按最高档位推导（规则见类文档）。 */
    private static CustomSpell templateSpell(Set<PhaseFunction> union, int maxTier) {
        SpellTemplate template = null;
        for (Map.Entry<PhaseFunction, SpellTemplate> entry : SPELL_TEMPLATES) {
            if (union.contains(entry.getKey())) {
                template = entry.getValue();
                break;
            }
        }
        if (template == null) {
            template = FALLBACK;
        }

        int power = net.minecraft.util.Mth.clamp(maxTier + 1, 1, 4 + 2 * maxTier);
        int manaCost = 8 + power * 6;
        if (union.contains(PhaseFunction.MANA)) {
            manaCost = Math.max(1, manaCost - 4);  // 法力材料让卷轴更省蓝
        }
        int cooldown = 30 + power * 15;
        return new CustomSpell(
                ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, template.path()),
                template.element(), template.form(), template.effect(), template.modifiers(),
                manaCost, cooldown, power);
    }

    /** 供界面预览：材料槽内容会组合出的法术（无 AI 覆盖）；无效组合返回 null。 */
    public static CustomSpell previewSpell(List<ItemStack> materialStacks) {
        Composition c = compose(materialStacks);
        return c.valid() ? c.spell() : null;
    }

    /** 收集材料槽内容（供 menu 调用方拼入 AI 请求的 currentMaterials）。 */
    public static List<String> materialNames(List<ItemStack> materialStacks) {
        List<String> names = new ArrayList<>(materialStacks.size());
        for (ItemStack stack : materialStacks) {
            if (stack == null || stack.isEmpty()) continue;
            names.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return names;
    }
}
