package com.qianxiang.handler;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import com.qianxiang.QianxiangMaterials;
import com.qianxiang.phase.PhaseFunction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * 裂隙试炼词缀表——词缀怪的增益、词缀材料的掉落与锻造算子的三方映射。
 * <ul>
 *   <li>{@link #function()}：词缀材料（带 {@code AFFIX} 组件的任意栈）在
 *       {@code PhaseFunctionResolver.get} 额外注入的功能算子——
 *       「任何垃圾物品带上词缀都能当零件」的兑现点。</li>
 *   <li>{@link #material()}：词缀怪掉落的词缀材料基底（50% 概率，叠 {@code AFFIX} 组件）。</li>
 * </ul>
 * 词缀 id 即组件值与 lang 键后缀（{@code qianxiang.affix.<id>}）。
 */
public enum RiftAffix {
    /** 燃焰：攻击带火（命中点燃目标）+ 火抗。→ IGNITE 算子。 */
    EMBER("ember", PhaseFunction.IGNITE, () -> QianxiangItems.EMBER_CRYSTAL.get()),
    /** 坚岩：+6 护甲 + 击退抗性。→ DEFENSE 算子。 */
    BASTION("bastion", PhaseFunction.DEFENSE, () -> QianxiangMaterials.SHADOW_DUST.get()),
    /** 疾风：+40% 移速 + 攻速。→ SPEED_BOOST 算子。 */
    GALE("gale", PhaseFunction.SPEED_BOOST, () -> QianxiangMaterials.FROST_CRYSTAL.get()),
    /** 噬血：造成伤害的 30% 回血。→ LIFESTEAL 算子。 */
    LEECH("leech", PhaseFunction.LIFESTEAL, () -> QianxiangMaterials.VENOM_GLAND.get()),
    /** 雷霆：命中附加 2 点魔法真伤。→ STRENGTH 算子。 */
    STORM("storm", PhaseFunction.STRENGTH, () -> QianxiangMaterials.THUNDER_STONE.get());

    private final String id;
    private final PhaseFunction function;
    private final Supplier<Item> material;

    RiftAffix(String id, PhaseFunction function, Supplier<Item> material) {
        this.id = id;
        this.function = function;
        this.material = material;
    }

    /** 词缀 id——{@code AFFIX} 组件的值、实体 tag 后缀与 lang 键后缀。 */
    public String id() {
        return id;
    }

    /** 词缀材料注入的功能算子（PhaseFunctionResolver 钩子用）。 */
    public PhaseFunction function() {
        return function;
    }

    /** 词缀怪掉落的材料基底物品。 */
    public Item material() {
        return material.get();
    }

    /** lang 键：{@code qianxiang.affix.<id>}。 */
    public String langKey() {
        return "qianxiang.affix." + id;
    }

    /** 按 id 查词缀；null/未知 id 返回 null（不抛——组件可能来自旧存档或手改 NBT）。 */
    public static RiftAffix byId(String id) {
        if (id == null) return null;
        for (RiftAffix a : values()) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    /** 读栈上的 {@code AFFIX} 组件并解析为词缀；无组件/未知 id 返回 null。 */
    public static RiftAffix of(ItemStack stack) {
        if (stack.isEmpty()) return null;
        return byId(stack.get(QianxiangDataComponents.AFFIX.get()));
    }

    /** 造一个词缀材料栈：材料基底 + {@code AFFIX} 组件。 */
    public ItemStack materialStack() {
        ItemStack stack = new ItemStack(material());
        stack.set(QianxiangDataComponents.AFFIX.get(), id);
        return stack;
    }
}
