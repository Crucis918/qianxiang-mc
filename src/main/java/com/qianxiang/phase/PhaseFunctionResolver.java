package com.qianxiang.phase;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.handler.RiftAffix;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 查一个物品的「功能算子」。万物皆零件——原版物品也当零件。
 * <p>
 * 来源：①优先 PhaseData component（自定义相材料，如火蜥蜴腺体）；
 *      ②否则查功能 tag {@code qianxiang:materials/<function>}（覆盖原版物品 + 任何物品）；
 *      ③末尾兜底——{@link ItemConceptResolver#derive} 全物品概念推导（草方块也有概念）。
 *      ④词缀钩子——裂隙试炼掉落的词缀材料带 {@code AFFIX} 组件（词缀 id，见
 *      {@link com.qianxiang.handler.RiftAffix}），在 ①/②/③ 的结果上<b>额外注入</b>
 *      词缀对应的功能算子（燃焰→IGNITE、坚岩→DEFENSE、疾风→SPEED_BOOST、
 *      噬血→LIFESTEAL、雷霆→STRENGTH）——任何垃圾物品带上词缀都能当零件。
 * <p>
 * tag 是数据驱动的，整合包作者可用 KubeJS / datapack 往这些 tag 加物品，
 * 无需改代码就能让新物品获得功能算子、参与自定义合成。
 * 推导兜底的优先级最低，不会覆盖 PhaseData/tag 的显式数据。
 */
public final class PhaseFunctionResolver {

    /** 功能 tag：qianxiang:materials/&lt;function_name&gt;（如 qianxiang:materials/base_metal） */
    public static TagKey<Item> tag(PhaseFunction function) {
        return TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "materials/" + function.name().toLowerCase()));
    }

    /**
     * 返回该物品的全部功能算子（空集=无功能，不能当零件）。
     * <p>
     * 顺序：①PhaseData component；②功能 tag；③末尾兜底——
     * {@link ItemConceptResolver#derive} 的全物品概念推导（食物→HEAL、
     * 石质→BASE_METAL、植物→GROWTH 等），让任何原版物品都能当零件。
     * 只需显式数据（PhaseData/tag，不触发推导）时改用 {@link #getExplicit}。
     */
    public static Set<PhaseFunction> get(ItemStack stack) {
        Set<PhaseFunction> set = getExplicit(stack);
        if (set.isEmpty()) {
            // ③ 推导兜底：全物品概念引擎
            set = ItemConceptResolver.derive(stack).functions();
        }
        // ④ 词缀钩子：栈带 AFFIX 组件时额外注入词缀算子（与显式/推导结果合并而非覆盖）
        RiftAffix affix = RiftAffix.of(stack);
        if (affix != null && !set.contains(affix.function())) {
            Set<PhaseFunction> merged = set.isEmpty()
                    ? EnumSet.noneOf(PhaseFunction.class) : EnumSet.copyOf(set);
            merged.add(affix.function());
            return Collections.unmodifiableSet(merged);
        }
        return set;
    }

    /**
     * 仅显式来源的功能算子：①PhaseData component；②功能 tag。
     * <b>不</b>做概念推导——供 {@link ItemConceptResolver#resolve} 判定优先级用，
     * 避免与推导兜底成环。
     */
    public static Set<PhaseFunction> getExplicit(ItemStack stack) {
        if (stack.isEmpty()) return Set.of();
        // ① 自定义相材料：PhaseData component
        var pd = stack.get(QianxiangDataComponents.PHASE_DATA.get());
        if (pd != null && !pd.functions().isEmpty()) return pd.functions();
        // ①.5 数据包定义（phase_materials/*.json）——UGC 材料
        var dataPd = PhaseMaterialRegistry.phaseData(stack.getItem());
        if (dataPd != null && !dataPd.functions().isEmpty()) return dataPd.functions();
        // ② 原版物品 / 任何物品：查功能 tag
        Set<PhaseFunction> set = EnumSet.noneOf(PhaseFunction.class);
        Item item = stack.getItem();
        for (PhaseFunction f : PhaseFunction.values()) {
            if (item.builtInRegistryHolder().is(tag(f))) set.add(f);
        }
        return set;
    }

    /**
     * 该物品的「有效 PhaseData」：PhaseData component 优先，
     * 否则数据包定义（{@link PhaseMaterialRegistry}），都没有返回 null。
     * 供合成/概念解析统一取显式相数据。
     */
    public static PhaseData effectivePhaseData(ItemStack stack) {
        if (stack.isEmpty()) return null;
        var pd = stack.get(QianxiangDataComponents.PHASE_DATA.get());
        if (pd != null) return pd;
        return PhaseMaterialRegistry.phaseData(stack.getItem());
    }

    /**
     * 判断物品是否为相零件（自定义 component 或任一功能 tag）。
     * 供 {@link com.qianxiang.ai.MaterialLibrary} 等批量扫描用。
     */
    public static boolean isMaterial(ItemStack stack) {
        return !get(stack).isEmpty();
    }

    /**
     * 返回该物品的强度档。
     * 自定义相材料读取 {@code phase_data}；原版/Tag 零件默认 COMMON。
     */
    public static PhaseTier resolveTier(ItemStack stack) {
        if (stack.isEmpty()) return PhaseTier.COMMON;
        var pd = effectivePhaseData(stack);
        if (pd != null && pd.tier() != null) return pd.tier();
        return PhaseTier.COMMON;
    }

    /**
     * 按功能算子推导默认相性。
     * 原版/Tag 零件没有显式 {@code phase_data}，用功能归类赋予合适相性。
     */
    public static Set<Phase> defaultPhases(Set<PhaseFunction> functions) {
        if (functions == null || functions.isEmpty()) return Set.of();
        Set<Phase> phases = EnumSet.noneOf(Phase.class);
        for (PhaseFunction f : functions) {
            Phase p = switch (f) {
                case BASE_METAL -> Phase.ORDER;
                case BASE_WOOD -> Phase.LIFE;
                case BASE_BONE -> Phase.TIME;
                case BASE_HIDE -> Phase.ABYSS;
                case EDGE -> Phase.CONFLICT;
                case IGNITE -> Phase.FIRE;
                case LIFESTEAL -> Phase.CONFLICT;
                case DEFENSE -> Phase.ORDER;
                case HEAL -> Phase.LIFE;
                case SLOW -> Phase.FROST;
                case REFLECT -> Phase.LIGHT;
                case MANA -> Phase.KNOWLEDGE;
                case POISON -> Phase.CONFLICT;
                case FROST -> Phase.FROST;
                case LEVITATION -> Phase.TRANSCEND;
                case STRENGTH -> Phase.CONFLICT;
                case NIGHT_VISION -> Phase.KNOWLEDGE;
                case SPEED_BOOST -> Phase.TIME;
                case JUMP_BOOST -> Phase.LIFE;
                case RESISTANCE -> Phase.ORDER;
                case FIRE_RESIST -> Phase.FIRE;
                case WATER_BREATH -> Phase.ABYSS;
                case REGENERATION -> Phase.LIFE;
                case GROWTH -> Phase.LIFE;
                case AREA_HARVEST -> Phase.ORDER;
                case REVERSE -> Phase.CHAOS;
            };
            phases.add(p);
        }
        return Collections.unmodifiableSet(phases);
    }
}
