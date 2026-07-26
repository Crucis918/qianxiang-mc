package com.qianxiang;

import com.qianxiang.item.QianxiangMaterialItem;
import com.qianxiang.phase.Phase;
import com.qianxiang.phase.PhaseData;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Set;

/**
 * 相材料注册表——「材料即零件」的零件库。
 * <p>
 * 这里只放「材料」本身（带 {@link PhaseData} 的 Item）。
 * 产物物品（武器、工具）仍在 {@link QianxiangItems}。
 * 与 {@code QianxiangItems.ITEMS} 分开成表，是为了让 AI 配方检索时只在这张表里翻零件，
 * 避免把 ember_blade 这种成品也当材料取用。
 * <p>
 * 覆盖度：跨四档（COMMON/RARE/EPIC/LEGENDARY），覆盖全部 12 个 PhaseFunction，
 * 九相与衍生相尽量分散。详细风味见各材料注释。
 */
public final class QianxiangMaterials {
    public static final DeferredRegister<Item> MATERIALS =
            DeferredRegister.create(Registries.ITEM, Qianxiang.MOD_ID);

    // 闪光木汁 glimmer_wood_sap —— 万象森罗树心渗出的活体树液，触之暖、伤则合。
    // BASE_WOOD（木质骨架）+ HEAL（疗伤）；普通；生命+中和。
    public static final DeferredHolder<Item, Item> GLIMMER_WOOD_SAP =
            MATERIALS.register("glimmer_wood_sap", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.COMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.COMMON, Set.of(Phase.LIFE, Phase.NEUTRAL),
                                    PhaseFunction.BASE_WOOD, PhaseFunction.HEAL))));

    // 暗鞣皮 shadowhide_patch —— 深渊之喉蛛兽的鞣制皮，触之冰冷、浸魂之影。
    // BASE_HIDE（皮甲骨架）+ SLOW（迟缓）；普通；暗+沉潜。
    public static final DeferredHolder<Item, Item> SHADOWHIDE_PATCH =
            MATERIALS.register("shadowhide_patch", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.COMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.COMMON, Set.of(Phase.SHADOW, Phase.ABYSS),
                                    PhaseFunction.BASE_HIDE, PhaseFunction.SLOW))));

    // 烬铁 ember_iron —— 浸过火蜥蜴血的铁锭，余温不灭，铸刃则灼。
    // BASE_METAL（金属骨架）+ IGNITE（灼烧）；稀有；火。
    public static final DeferredHolder<Item, Item> EMBER_IRON =
            MATERIALS.register("ember_iron", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.FIRE),
                                    PhaseFunction.BASE_METAL, PhaseFunction.IGNITE))));

    // 血根 bloodroot —— 无尽战场上饮血而生的红藤，断之则血涌，可酿吸血之剂。
    // LIFESTEAL（吸血）+ BASE_WOOD（木质骨架）；稀有；冲突+生命。
    public static final DeferredHolder<Item, Item> BLOODROOT =
            MATERIALS.register("bloodroot", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.CONFLICT, Phase.LIFE),
                                    PhaseFunction.LIFESTEAL, PhaseFunction.BASE_WOOD))));

    // 沉潜铁 abyss_iron —— 深渊之喉底压沉的冷铁，重而韧，可承万钧。
    // BASE_METAL（金属骨架）+ DEFENSE（防御）；稀有；沉潜。
    public static final DeferredHolder<Item, Item> ABYSS_IRON =
            MATERIALS.register("abyss_iron", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.ABYSS),
                                    PhaseFunction.BASE_METAL, PhaseFunction.DEFENSE))));

    // 火蜥蜴腺体 salamander_gland —— 火蜥蜴火囊中的腺体，灼热搏动，触之灼皮。
    // IGNITE（灼烧）+ DEFENSE（防御，鳞皮之坚）；史诗；火+生命。
    public static final DeferredHolder<Item, Item> SALAMANDER_GLAND =
            MATERIALS.register("salamander_gland", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.RARE)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.EPIC, Set.of(Phase.FIRE, Phase.LIFE),
                                    PhaseFunction.IGNITE, PhaseFunction.DEFENSE))));

    // 龙骨 dragon_bone —— 龙脊沙海深处出土的龙骸，骨如黑玉、锋若寒星。
    // BASE_BONE（骨制骨架）+ EDGE（锋锐）；史诗；时间+冲突。
    public static final DeferredHolder<Item, Item> DRAGON_BONE =
            MATERIALS.register("dragon_bone", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.RARE)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.EPIC, Set.of(Phase.TIME, Phase.CONFLICT),
                                    PhaseFunction.BASE_BONE, PhaseFunction.EDGE))));

    // 裂隙精髓 rift_essence —— 裂隙之境偶溢的浑相残片，凝如星砂、含万法之根。
    // MANA（法力）+ REFLECT（反伤）；传奇；混沌+超脱。
    public static final DeferredHolder<Item, Item> RIFT_ESSENCE =
            MATERIALS.register("rift_essence", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.EPIC)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.LEGENDARY, Set.of(Phase.CHAOS, Phase.TRANSCEND),
                                    PhaseFunction.MANA, PhaseFunction.REFLECT))));

    // —— 元素扩展材料（冰/雷/毒/暗影/光明/自然/虚空），补齐火焰之外的元素谱系 ——

    // 寒霜晶 frost_crystal —— 永夜极光深处凝结的寒冰核心，触之霜结、挥之则冰封。
    // FROST（霜冻）+ SLOW（迟缓，寒气滞行）；稀有；冰。
    public static final DeferredHolder<Item, Item> FROST_CRYSTAL =
            MATERIALS.register("frost_crystal", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.FROST),
                                    PhaseFunction.FROST, PhaseFunction.SLOW))));

    // 雷霆石 thunder_stone —— 雷暴之夜落在龙脊的奇石，石中仍有电光奔涌。
    // STRENGTH（力量）+ SPEED_BOOST（疾速，电光之迅）；史诗；时间+秩序。
    public static final DeferredHolder<Item, Item> THUNDER_STONE =
            MATERIALS.register("thunder_stone", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.RARE)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.EPIC, Set.of(Phase.TIME, Phase.ORDER),
                                    PhaseFunction.STRENGTH, PhaseFunction.SPEED_BOOST))));

    // 剧毒腺 venom_gland —— 毒兽体内的腺体，翠毒欲滴，一滴便可蚀骨。
    // POISON（剧毒）+ EDGE（锋锐，毒牙之利）；稀有；冲突。
    public static final DeferredHolder<Item, Item> VENOM_GLAND =
            MATERIALS.register("venom_gland", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.CONFLICT),
                                    PhaseFunction.POISON, PhaseFunction.EDGE))));

    // 暗影尘 shadow_dust —— 暗影凝成的细尘，散之则万物迟滞、目能穿夜。
    // SLOW（迟缓）+ NIGHT_VISION（夜视，暗中视物）；稀有；暗。
    public static final DeferredHolder<Item, Item> SHADOW_DUST =
            MATERIALS.register("shadow_dust", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.SHADOW),
                                    PhaseFunction.SLOW, PhaseFunction.NIGHT_VISION))));

    // 圣辉碎片 holy_shard —— 圣光熄灭前最后的残片，仍低语着愈合与新生。
    // REGENERATION（再生）+ HEAL（疗伤）；史诗；圣。
    public static final DeferredHolder<Item, Item> HOLY_SHARD =
            MATERIALS.register("holy_shard", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.RARE)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.EPIC, Set.of(Phase.LIGHT),
                                    PhaseFunction.REGENERATION, PhaseFunction.HEAL))));

    // 自然之息 nature_breath —— 万象森罗吐出的一缕生机，落土则万物生长。
    // GROWTH（生长）+ HEAL（疗伤）；稀有；生命。
    public static final DeferredHolder<Item, Item> NATURE_BREATH =
            MATERIALS.register("nature_breath", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.UNCOMMON)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.RARE, Set.of(Phase.LIFE),
                                    PhaseFunction.GROWTH, PhaseFunction.HEAL))));

    // 虚空裂片 void_shard —— 虚空回响凝成的裂片，握着它，仿佛重力也忘了你。
    // LEVITATION（悬浮）+ MANA（法力）；传奇；超脱+混沌。
    public static final DeferredHolder<Item, Item> VOID_SHARD =
            MATERIALS.register("void_shard", () -> new QianxiangMaterialItem(new Item.Properties()
                    .rarity(Rarity.EPIC)
                    .component(QianxiangDataComponents.PHASE_DATA.get(),
                            PhaseData.of(PhaseTier.LEGENDARY, Set.of(Phase.TRANSCEND, Phase.CHAOS),
                                    PhaseFunction.LEVITATION, PhaseFunction.MANA))));

    private QianxiangMaterials() {}
}
