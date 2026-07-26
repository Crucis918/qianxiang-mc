package com.qianxiang.client;

import com.qianxiang.phase.AttributeScheme;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.EffectGlossary;
import com.qianxiang.phase.ItemConceptResolver;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseFunctionResolver;
import com.qianxiang.phase.PhaseTier;
import com.qianxiang.phase.ReverseEffectTable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 材料性质卡 / 贡献估算（纯客户端展示逻辑，不发网络包、不改合成结果）。
 * <p>
 * 供 {@link ForgeTableScreen} 使用：
 * <ul>
 *   <li>{@link #analyze(ItemStack)} —— 汇总一个材料栈的全部性质（档位/相性/算子/自由效果/概念/
 *       单材料贡献预估）。原版物品走 {@link ItemConceptResolver} 推导，与合成端口径一致。</li>
 *   <li>{@link #buildCard(MaterialInfo)} —— 悬停材料槽时的完整性质卡 tooltip。</li>
 *   <li>{@link #contributionTokens(MaterialInfo)} —— 「+3.0 攻击 / +灼烧 ×1 / 提供金属骨架」
 *       式贡献短句，贡献明细面板与性质卡共用。</li>
 * </ul>
 * 贡献预估用 {@link AttributeScheme} 对该材料单独 compose 得到——与
 * {@link com.qianxiang.phase.ForgeComposer} 的组合规则同源，只是不合并其他材料。
 * 所有入口都吞异常返回空结果，绝不让显示逻辑拖垮 GUI。
 */
public final class MaterialCardHelper {

    private MaterialCardHelper() {}

    /** 稀有度档位颜色（普通-灰 / 稀有-青 / 史诗-紫 / 传奇-金）。 */
    private static final int[] TIER_COLORS = {0xFFAAAAAA, 0xFF55FFFF, 0xFFFF55FF, 0xFFFFAA00};

    /** 一条材料的完整性质解析结果。 */
    public record MaterialInfo(
            PhaseTier tier,
            Set<Phase> phases,
            Set<PhaseFunction> functions,
            Map<ResourceLocation, Integer> effects,
            String conceptKey,
            ComposedAttributes estimate) {

        /** 是否提供 BASE_* 骨架。 */
        public boolean hasBase() {
            return functions.contains(PhaseFunction.BASE_METAL)
                    || functions.contains(PhaseFunction.BASE_WOOD)
                    || functions.contains(PhaseFunction.BASE_BONE)
                    || functions.contains(PhaseFunction.BASE_HIDE);
        }

        /** 是否提供骨架以外的效果（非 BASE 算子或自由状态效果）。 */
        public boolean hasEffect() {
            if (!effects.isEmpty()) return true;
            for (PhaseFunction f : functions) {
                if (!f.name().startsWith("BASE_")) return true;
            }
            return false;
        }
    }

    /**
     * 解析一个材料栈的全部性质。空栈返回 null。
     * <p>
     * 口径与合成端一致：功能算子走 {@link PhaseFunctionResolver#get}（PhaseData > tag > 推导），
     * 相性/自由效果/概念键走 {@link ItemConceptResolver#resolve}，
     * 贡献预估用 {@link AttributeScheme} 单独 compose。
     */
    public static MaterialInfo analyze(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return null;
            Set<PhaseFunction> functions = PhaseFunctionResolver.get(stack);
            PhaseTier tier = PhaseFunctionResolver.resolveTier(stack);
            ItemConceptResolver.ItemConcept concept = ItemConceptResolver.resolve(stack);
            Set<Phase> phases = concept.phases() == null ? Set.of() : concept.phases();
            Map<ResourceLocation, Integer> effects = concept.effects() == null ? Map.of() : concept.effects();
            ComposedAttributes estimate = functions.isEmpty()
                    ? ComposedAttributes.empty()
                    : AttributeScheme.compose(List.of(AttributeScheme.MaterialInput.of(
                            tier, functions.toArray(new PhaseFunction[0]))));
            return new MaterialInfo(tier, phases, functions, effects, concept.conceptKey(), estimate);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 稀有度档位颜色。 */
    public static int tierColor(PhaseTier tier) {
        int idx = tier == null ? 0 : Math.clamp(tier.ordinal(), 0, TIER_COLORS.length - 1);
        return TIER_COLORS[idx];
    }

    // ========================== 贡献短句 ==========================

    /**
     * 单材料贡献短句列表：「提供金属骨架」「+3.0 攻击」「+灼烧 ×1」……
     * 无任何数值/效果贡献（纯相性辅料，如泥土）时返回「仅提供相性」一条。
     */
    public static List<Component> contributionTokens(MaterialInfo info) {
        List<Component> out = new ArrayList<>();
        if (info == null) return out;
        try {
            // 1. 骨架
            for (PhaseFunction f : info.functions()) {
                switch (f) {
                    case BASE_METAL -> out.add(Component.translatable("qianxiang.forge_table.contrib.base_metal"));
                    case BASE_WOOD -> out.add(Component.translatable("qianxiang.forge_table.contrib.base_wood"));
                    case BASE_BONE -> out.add(Component.translatable("qianxiang.forge_table.contrib.base_bone"));
                    case BASE_HIDE -> out.add(Component.translatable("qianxiang.forge_table.contrib.base_hide"));
                    default -> { }
                }
            }
            // 1b. 反转器：机制开关，无数值贡献，但要让玩家看见它「反转所有概念」
            if (info.functions().contains(PhaseFunction.REVERSE)) {
                out.add(Component.translatable("qianxiang.forge_table.contrib.reverse"));
            }
            // 2. 数值属性（AttributeScheme 单材料预估）
            ComposedAttributes a = info.estimate();
            if (a != null) {
                if (a.attackDamage() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.attack", fmt1(a.attackDamage())));
                }
                if (a.durability() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.durability", a.durability()));
                }
                if (a.armor() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.armor", fmt1(a.armor())));
                }
                if (a.armorToughness() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.toughness", fmt1(a.armorToughness())));
                }
                if (a.attackSpeed() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.attack_speed", fmt1(a.attackSpeed())));
                }
                if (a.maxHealth() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.max_health", fmt1(a.maxHealth())));
                }
                if (a.knockbackResistance() > 0) {
                    out.add(Component.translatable("qianxiang.forge_table.contrib.knockback", fmt2(a.knockbackResistance())));
                }
                // 3. 固定效果等级（经典 5 + 扩展 13）
                addEffectToken(out, "ignite", a.igniteLevel());
                addEffectToken(out, "lifesteal", a.lifestealLevel());
                addEffectToken(out, "reflect", a.thornsLevel());
                addEffectToken(out, "slow", a.slowLevel());
                addEffectToken(out, "heal", a.healLevel());
                ComposedAttributes.EffectLevels fx = a.effects();
                addEffectToken(out, "poison", fx.poison());
                addEffectToken(out, "frost", fx.frost());
                addEffectToken(out, "levitation", fx.levitation());
                addEffectToken(out, "strength", fx.strength());
                addEffectToken(out, "night_vision", fx.nightVision());
                addEffectToken(out, "speed_boost", fx.speedBoost());
                addEffectToken(out, "jump_boost", fx.jumpBoost());
                addEffectToken(out, "resistance", fx.resistance());
                addEffectToken(out, "fire_resist", fx.fireResist());
                addEffectToken(out, "water_breath", fx.waterBreath());
                addEffectToken(out, "regeneration", fx.regeneration());
                addEffectToken(out, "growth", fx.growth());
                addEffectToken(out, "area_harvest", fx.areaHarvest());
            }
            // 4. 自由状态效果（effect tag / 推导概念自带）
            info.effects().forEach((id, lv) -> {
                if (id == null || lv == null || lv <= 0) return;
                out.add(Component.translatable("qianxiang.forge_table.contrib.effect",
                        mobEffectName(id), lv));
            });
        } catch (Throwable t) {
            // 部分失败仍返回已收集的短句
        }
        if (out.isEmpty()) {
            out.add(Component.translatable("qianxiang.forge_table.contrib.none"));
        }
        return out;
    }

    /** 贡献短句合成一行（贡献明细面板用），空格分隔。 */
    public static Component contributionSummary(MaterialInfo info) {
        MutableComponent line = Component.empty();
        for (Component token : contributionTokens(info)) {
            if (!line.getSiblings().isEmpty() || !line.getString().isEmpty()) {
                line.append(" ");
            }
            line.append(token);
        }
        return line;
    }

    private static void addEffectToken(List<Component> out, String phasefnKey, int level) {
        if (level <= 0) return;
        out.add(Component.translatable("qianxiang.forge_table.contrib.effect",
                Component.translatable("qianxiang.phasefn." + phasefnKey), level));
    }

    // ========================== 完整性质卡 ==========================

    /**
     * 悬停材料槽时的完整性质卡：物品名 / 稀有度档位（带色）/ 相性 / 功能算子（中文名+用途）/
     * 自由状态效果 / 概念描述 / 贡献预估。
     */
    public static List<Component> buildCard(ItemStack stack, MaterialInfo info) {
        List<Component> lines = new ArrayList<>();
        if (stack == null || stack.isEmpty() || info == null) return lines;
        try {
            // 1. 物品名（沿用物品自身的稀有度样式）
            lines.add(stack.getHoverName());
            // 2. 稀有度档位（带色）
            Component tierName = Component.translatable(
                            "qianxiang.tier." + info.tier().name().toLowerCase(Locale.ROOT))
                    .withStyle(Style.EMPTY.withColor(tierColor(info.tier())));
            lines.add(Component.translatable("qianxiang.forge_table.matcard.tier", tierName)
                    .withStyle(Style.EMPTY.withColor(0xFF9AA0AE)));
            // 3. 相性
            if (!info.phases().isEmpty()) {
                MutableComponent phases = Component.empty();
                boolean first = true;
                for (Phase p : info.phases()) {
                    if (!first) phases.append("/");
                    phases.append(Component.translatable("qianxiang.phase." + p.name().toLowerCase(Locale.ROOT)));
                    first = false;
                }
                lines.add(Component.translatable("qianxiang.forge_table.matcard.phases", phases)
                        .withStyle(Style.EMPTY.withColor(0xFF7FE3C0)));
            }
            // 4. 功能算子（每个算子一行：中文名 + 用途）
            if (!info.functions().isEmpty()) {
                lines.add(Component.translatable("qianxiang.forge_table.matcard.functions")
                        .withStyle(Style.EMPTY.withColor(0xFFFFD700)));
                List<PhaseFunction> sorted = new ArrayList<>(info.functions());
                sorted.sort(java.util.Comparator.comparingInt(PhaseFunction::ordinal));
                for (PhaseFunction f : sorted) {
                    lines.add(Component.literal("· ").append(f.display())
                            .append(" ").append(f.usageComponent())
                            .withStyle(Style.EMPTY.withColor(0xFFE0E0E0)));
                }
            }
            // 5. 自由状态效果
            if (!info.effects().isEmpty()) {
                lines.add(Component.translatable("qianxiang.forge_table.matcard.effects")
                        .withStyle(Style.EMPTY.withColor(0xFFFFD700)));
                info.effects().forEach((id, lv) -> {
                    if (id == null || lv == null || lv <= 0) return;
                    lines.add(Component.translatable("qianxiang.forge_table.contrib.effect",
                                    mobEffectName(id), lv)
                            .withStyle(Style.EMPTY.withColor(effectColor(id))));
                    // 反转材料双用途说明：「武器：凋零 / 防具：防凋零」
                    if (ReverseEffectTable.hasReversal(id)) {
                        lines.add(Component.translatable("qianxiang.forge_table.matcard.reverse",
                                        mobEffectName(id),
                                        Component.translatable(ReverseEffectTable.resistanceNameKey(id)))
                                .withStyle(Style.EMPTY.withColor(0xFF8FDB8F)));
                    }
                });
            }
            // 5b. 反转·点燃：材料贡献灼烧等级时，提示防具用途是抗火
            if (info.estimate() != null && info.estimate().igniteLevel() > 0) {
                lines.add(Component.translatable("qianxiang.forge_table.matcard.reverse",
                                Component.translatable("qianxiang.phasefn.ignite"),
                                Component.translatable(ReverseEffectTable.LANG_KEY_PREFIX + "ignite"))
                        .withStyle(Style.EMPTY.withColor(0xFF8FDB8F)));
            }
            // 6. 概念描述（ItemConceptResolver 的 conceptKey 本地化）
            lines.add(Component.translatable("qianxiang.forge_table.matcard.concept",
                            Component.translatable(info.conceptKey()))
                    .withStyle(Style.EMPTY.withColor(0xFF9AA0AE)));
            // 7. 贡献预估（AttributeScheme 单材料 compose）
            lines.add(Component.translatable("qianxiang.forge_table.matcard.contrib")
                    .withStyle(Style.EMPTY.withColor(0xFFFFD700)));
            for (Component token : contributionTokens(info)) {
                lines.add(Component.literal("· ").append(token)
                        .withStyle(Style.EMPTY.withColor(0xFF9AD0FF)));
            }
            // 8. 代价提示：强力组合会带代价（如攻击耗饥饿），避免玩家误以为是 bug
            lines.add(Component.translatable("qianxiang.forge_table.matcard.drawback_note")
                    .withStyle(Style.EMPTY.withColor(0xFF7A6A6A)));
            // 9. 动作定制提示：描述攻击动作（如三段连斩/拔刀斩）可让 AI 定制 EF 连击
            lines.add(Component.translatable("qianxiang.forge_table.matcard.moveset_note")
                    .withStyle(Style.EMPTY.withColor(0xFF6A7A6A)));
        } catch (Throwable t) {
            // 卡片构建失败时至少保留物品名
            if (lines.isEmpty()) lines.add(stack.getHoverName());
        }
        return lines;
    }

    /** MobEffect registry id → 本地化显示名；未注册退化为 id 字符串。 */
    private static Component mobEffectName(ResourceLocation id) {
        return BuiltInRegistries.MOB_EFFECT.getHolder(id)
                .map(h -> h.value().getDisplayName())
                .orElse(Component.literal(id.toString()));
    }

    /** 自由状态效果颜色：词典收录的负面=红、增益=绿（{@link EffectGlossary}）；未收录保持原紫色。 */
    private static int effectColor(ResourceLocation id) {
        if (id != null) {
            if (EffectGlossary.isDebuff(id.getPath())) return 0xFFFF5555;
            if (EffectGlossary.isBuff(id.getPath())) return 0xFF55FF55;
        }
        return 0xFFB27DFF;
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
