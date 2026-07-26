package com.qianxiang;

import com.qianxiang.item.QianxiangArmorItem;
import com.qianxiang.item.QianxiangMaterialItem;
import com.qianxiang.item.QianxiangToolItem;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseData;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.core.registries.Registries;
import com.qianxiang.entity.QianxiangEntities;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

/** 相材料与产物物品。材料带 PhaseData（功能算子+档位+相性）。 */
public final class QianxiangItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, Qianxiang.MOD_ID);

    // 余烬石 ember_crystal —— 火·残忆。IGNITE（灼烧）；普通；火。
    public static final DeferredHolder<Item, Item> EMBER_CRYSTAL =
            ITEMS.register("ember_crystal", () -> new QianxiangMaterialItem(new Item.Properties()
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.COMMON, Set.of(Phase.FIRE), PhaseFunction.IGNITE))));

    // 兽牙 beast_fang —— 冲突·决断。EDGE（锋锐）；普通；冲突。
    public static final DeferredHolder<Item, Item> BEAST_FANG =
            ITEMS.register("beast_fang", () -> new QianxiangMaterialItem(new Item.Properties()
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.COMMON, Set.of(Phase.CONFLICT), PhaseFunction.EDGE))));

    // 灼烧之刃 ember_blade —— 锻造台「金属基底 + 火源」等组合的动态产物载体。
    // 用 QianxiangWeaponItem：属性不硬编码，全部来自合成时写入的 ComposedAttributes（强度靠材料）。
    // （task 9 接 Epic Fight 武器类型——见 docs/epic-fight-compat-brief.md，纯数据包即可挂武器模组）
    public static final DeferredHolder<Item, Item> EMBER_BLADE =
            ITEMS.register("ember_blade", () -> new QianxiangWeaponItem(new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相杖 phase_staff —— 「木质/金属基底 + 法力」组合的法系武器载体（动态属性，强度靠材料）。
    public static final DeferredHolder<Item, Item> PHASE_STAFF =
            ITEMS.register("phase_staff", () -> new QianxiangWeaponItem(new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 骨刃 bone_blade —— 「骨制基底 + 锋锐」组合的锋锐武器载体（动态属性）。
    public static final DeferredHolder<Item, Item> BONE_BLADE =
            ITEMS.register("bone_blade", () -> new QianxiangWeaponItem(new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相盾 phase_shield —— 「皮制/金属基底 + 防御/反伤」组合的防具载体（动态属性）。
    public static final DeferredHolder<Item, Item> PHASE_SHIELD =
            ITEMS.register("phase_shield", () -> new QianxiangWeaponItem(new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相盔 phase_helmet —— 「皮制基底 + 夜视(NIGHT_VISION)」组合的头盔载体（动态属性，强度靠材料）。
    public static final DeferredHolder<Item, Item> PHASE_HELMET =
            ITEMS.register("phase_helmet", () -> new QianxiangArmorItem(ArmorItem.Type.HELMET,
                    new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相甲 phase_chestplate —— 「皮制基底 + 抗性(RESISTANCE)」组合的胸甲载体（动态属性）。
    public static final DeferredHolder<Item, Item> PHASE_CHESTPLATE =
            ITEMS.register("phase_chestplate", () -> new QianxiangArmorItem(ArmorItem.Type.CHESTPLATE,
                    new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相裤 phase_leggings —— 护腿载体（动态属性；当前锻造原型规则未指向它，创造/蓝图可用）。
    public static final DeferredHolder<Item, Item> PHASE_LEGGINGS =
            ITEMS.register("phase_leggings", () -> new QianxiangArmorItem(ArmorItem.Type.LEGGINGS,
                    new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相靴 phase_boots —— 「皮制基底 + 迅捷(SPEED_BOOST)」组合的靴子载体（动态属性）。
    public static final DeferredHolder<Item, Item> PHASE_BOOTS =
            ITEMS.register("phase_boots", () -> new QianxiangArmorItem(ArmorItem.Type.BOOTS,
                    new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相锄 phase_hoe —— 「基底 + 广域耕作(AREA_HARVEST)」组合的广域耕地工具载体（动态属性，强度靠材料）。
    public static final DeferredHolder<Item, Item> PHASE_HOE =
            ITEMS.register("phase_hoe", () -> new QianxiangToolItem(QianxiangToolItem.Kind.HOE,
                    new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 相之水壶 phase_watering_can —— 「基底 + 催熟(GROWTH)」组合的范围催熟工具载体（动态属性）。
    public static final DeferredHolder<Item, Item> PHASE_WATERING_CAN =
            ITEMS.register("phase_watering_can", () -> new QianxiangToolItem(QianxiangToolItem.Kind.WATERING_CAN,
                    new Item.Properties().rarity(Rarity.UNCOMMON)));

    // 千相法术书 spell_book —— 自由法术系统的施法载体：存法术列表+选中下标，
    // 右键施放、潜行+右键切换。默认带 3 个入门预置法术；锻造台（含裂隙精髓的魔法组合）
    // 会产出按材料算子生成的自定义法术书。
    public static final DeferredHolder<Item, Item> SPELL_BOOK =
            ITEMS.register("spell_book", () -> new com.qianxiang.item.SpellBookItem(new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.RARE)
                    .component(QianxiangDataComponents.SPELLBOOK.get(),
                            com.qianxiang.spell.SpellBookData.withDefaults())));

    // 森罗残片 myriad_fragment —— 万象森罗掉落的专属材料（微光树叶概率掉落），
    // 万象生机凝成的残片：MANA（法力）+ GROWTH（生长）；稀有；混沌+生命。
    // 4 片可合 1 裂隙精髓（见 recipe/rift_essence_from_myriad_fragments.json）。
    public static final DeferredHolder<Item, Item> MYRIAD_FRAGMENT =
            ITEMS.register("myriad_fragment", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.RARE)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.CHAOS, Phase.LIFE),
                                    PhaseFunction.MANA, PhaseFunction.GROWTH))));

    // 逆相之核 reverse_core —— 反转器：传奇稀有度材料，自身零数值贡献（机制开关）。
    // 只要它在材料槽，产物所有概念倒转含义：防具携带效果由「接触反伤」变「抗性/免疫」，
    // 武器伤害型效果极性反转（点燃→冰冻、中毒→攻击者再生等）。
    // PhaseData 给 REVERSE 算子 + 混沌/超脱双相性，ForgeComposer 检测后写反转标志。
    public static final DeferredHolder<Item, Item> REVERSE_CORE =
            ITEMS.register("reverse_core", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.EPIC)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.LEGENDARY, Set.of(Phase.CHAOS, Phase.TRANSCEND),
                                    PhaseFunction.REVERSE))));

    // 森罗守望者刷怪蛋（Boss，测试/地图作者用）
    public static final DeferredHolder<Item, Item> MYRIAD_WARDEN_SPAWN_EGG =
            ITEMS.register("myriad_warden_spawn_egg", () -> new SpawnEggItem(
                    QianxiangEntities.MYRIAD_WARDEN.get(), 0x2E8B57, 0x9FF0D0, new Item.Properties()));

    // 流浪相师刷怪蛋
    public static final DeferredHolder<Item, Item> WANDERING_SAGE_SPAWN_EGG =
            ITEMS.register("wandering_sage_spawn_egg", () -> new SpawnEggItem(
                    QianxiangEntities.WANDERING_SAGE.get(), 0x8B7355, 0x4A90E2, new Item.Properties()));

    // 深渊商人刷怪蛋
    public static final DeferredHolder<Item, Item> ABYSS_MERCHANT_SPAWN_EGG =
            ITEMS.register("abyss_merchant_spawn_egg", () -> new SpawnEggItem(
                    QianxiangEntities.ABYSS_MERCHANT.get(), 0x2C3E50, 0x9B59B6, new Item.Properties()));
}
