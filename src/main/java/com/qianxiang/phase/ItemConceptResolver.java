package com.qianxiang.phase;

import com.qianxiang.QianxiangDataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 全物品概念引擎：为<b>任意</b> ItemStack 推导「概念」——
 * 让《千相》里连草方块都有意义（万物皆零件）。
 * <p>
 * 推导优先级（先到先得）：①已有 PhaseData / 功能 tag / 效果 tag → 用现有结果
 * （①.5 杂项栏物品例外：给具体概念——记载/指引/计时/容器/龙息/翼…，显式数据并入不丢弃）；
 * ②食物（腐肉/有害食物特例在前：不给 HEAL 给 POISON）；③装备（金制装备额外 MANA——
 * 黄金是中和之物）；④原版 tag / 名称推导（木质/石质/矿物）；⑤红石；⑥下界；⑦末地；
 * ⑧水生；⑨植物（花特例在前）；⑩土类（沙特例在前）；⑪怪物掉落；⑫兜底「凡物」。
 * 任何常见物品都会得到一个非空概念（至少一组相性 + 概念键）。
 * <p>
 * <b>设计意图——萌新保底：常见物也有用。</b>
 * 满地都是的东西（泥土/草方块/原木/圆石/种子/花/沙子/羽毛/鸡蛋/腐肉）必须能拼出
 * 有基础效果的装备，哪怕弱：泥土/草方块=微量 HEAL（大地滋养，萌新第一件治疗装）、
 * 圆石/石头=BASE_METAL+DEFENSE（坚岩，萌新第一件防具）、沙子=SLOW（流沙控场）、
 * 羽毛=微弱 LEVITATION（轻盈）、腐肉=微弱 POISON（反向利用）、鸡蛋=生命相「新生」。
 * 萌新用「泥土+原木+石头+种子」即可在锻造台拼出带疗伤/护甲/催熟的入门装备。
 * <p>
 * 注意：{@link #derive(ItemStack)} 是纯推导（不查 PhaseData/tag），
 * 供 {@link PhaseFunctionResolver#get} 末尾兜底调用；
 * {@link #resolve(ItemStack)} 才是带优先级的完整入口。二者不可互相成环。
 */
public final class ItemConceptResolver {

    private ItemConceptResolver() {}

    /** 一个物品的概念：相性 + 功能算子 + 状态效果 + 概念键（本地化键，对应 lang 里的概念名）。 */
    public record ItemConcept(Set<Phase> phases, Set<PhaseFunction> functions,
                              Map<ResourceLocation, Integer> effects, String conceptKey) {
        /** 完全空（仅空栈）。常见物品不会为空。 */
        public boolean isEmpty() {
            return phases.isEmpty() && functions.isEmpty() && effects.isEmpty();
        }
    }

    // —— 概念键（zh_cn/en_us 各有一份本地化概念名）——
    public static final String CONCEPT_MATERIAL = "qianxiang.concept.material";  // 相材（已有 PhaseData/tag）
    public static final String CONCEPT_NOURISH  = "qianxiang.concept.nourish";   // 滋养（食物）
    public static final String CONCEPT_ARMOR    = "qianxiang.concept.armor";     // 坚甲（护甲）
    public static final String CONCEPT_BLADE    = "qianxiang.concept.blade";     // 兵刃（刀剑/弓弩）
    public static final String CONCEPT_TOOL     = "qianxiang.concept.tool";      // 利器（工具）
    public static final String CONCEPT_WOOD     = "qianxiang.concept.wood";      // 木质
    public static final String CONCEPT_REDSTONE = "qianxiang.concept.redstone";  // 机枢（红石）
    public static final String CONCEPT_METAL    = "qianxiang.concept.metal";     // 精矿（矿石/锭/宝石）
    public static final String CONCEPT_NETHER   = "qianxiang.concept.nether";    // 狱焰（下界）
    public static final String CONCEPT_ENDER    = "qianxiang.concept.ender";     // 末界（末地）
    public static final String CONCEPT_AQUATIC  = "qianxiang.concept.aquatic";   // 渊息（水生）
    public static final String CONCEPT_STONE    = "qianxiang.concept.stone";     // 坚岩（石质基底）
    public static final String CONCEPT_PLANT    = "qianxiang.concept.plant";     // 生机（植物）
    public static final String CONCEPT_EARTH    = "qianxiang.concept.earth";     // 大地（土类辅料）
    public static final String CONCEPT_MONSTER  = "qianxiang.concept.monster";   // 魔骸（怪物掉落）
    public static final String CONCEPT_SAND     = "qianxiang.concept.sand";      // 流沙（沙类，lang 已有同名条目）
    public static final String CONCEPT_NEWBORN  = "qianxiang.concept.newborn";   // 新生（鸡蛋）
    public static final String CONCEPT_LIGHTWEIGHT = "qianxiang.concept.lightweight"; // 轻盈（羽毛）
    public static final String CONCEPT_COMMON   = "qianxiang.concept.common";    // 凡物（兜底）

    // —— 杂项栏物品概念（创造模式「杂项」标签页：书/地图/时钟/桶/龙息/鞘翅/蜡烛…）——
    public static final String CONCEPT_RECORD      = "qianxiang.concept.record";      // 记载（书/纸）
    public static final String CONCEPT_GUIDE       = "qianxiang.concept.guide";       // 指引（地图/指南针）
    public static final String CONCEPT_TIMEKEEPING = "qianxiang.concept.timekeeping"; // 计时（时钟）
    public static final String CONCEPT_VESSEL      = "qianxiang.concept.vessel";      // 容器（桶）
    public static final String CONCEPT_CLEANSE     = "qianxiang.concept.cleanse";     // 净化（奶桶，解毒）
    public static final String CONCEPT_UTENSIL     = "qianxiang.concept.utensil";     // 器皿（碗）
    public static final String CONCEPT_MANA_VIAL   = "qianxiang.concept.mana_vial";   // 魔瓶（玻璃瓶/附魔之瓶）
    public static final String CONCEPT_DRAGON_BREATH = "qianxiang.concept.dragon_breath"; // 龙息
    public static final String CONCEPT_ECHO        = "qianxiang.concept.echo";        // 回响（回响碎片）
    public static final String CONCEPT_PRISMARINE  = "qianxiang.concept.prismarine";  // 海晶（海晶碎片/砂粒）
    public static final String CONCEPT_SPIRAL_SHELL = "qianxiang.concept.spiral_shell"; // 螺旋护壳（鹦鹉螺壳）
    public static final String CONCEPT_SEA_HEART   = "qianxiang.concept.sea_heart";   // 海洋之心
    public static final String CONCEPT_CELEBRATION = "qianxiang.concept.celebration"; // 庆典（烟花）
    public static final String CONCEPT_TACK        = "qianxiang.concept.tack";        // 驭具（马鞍）
    public static final String CONCEPT_NAME_BOND   = "qianxiang.concept.name_bond";   // 名契（命名牌）
    public static final String CONCEPT_BINDING     = "qianxiang.concept.binding";     // 束缚（锁链）
    public static final String CONCEPT_TETHER      = "qianxiang.concept.tether";      // 牵引（拴绳）
    public static final String CONCEPT_HONEY       = "qianxiang.concept.honey";       // 甘蜜（蜂蜜瓶/蜜脾）
    public static final String CONCEPT_BLINK       = "qianxiang.concept.blink";       // 瞬移（紫颂果）
    public static final String CONCEPT_SHULKER     = "qianxiang.concept.shulker";     // 潜影（潜影壳）
    public static final String CONCEPT_WING        = "qianxiang.concept.wing";        // 翼（鞘翅）
    public static final String CONCEPT_SKULL       = "qianxiang.concept.skull";       // 颅（头颅）
    public static final String CONCEPT_CANDLE      = "qianxiang.concept.candle";      // 烛（蜡烛）
    public static final String CONCEPT_SCULK       = "qianxiang.concept.sculk";       // 幽匿（幽匿系方块）

    /** 概念键 → 中文概念名（喂给 LLM 的服务端 displayName 用；客户端 UI 请走 lang 键）。 */
    private static final Map<String, String> CONCEPT_CN = Map.ofEntries(
            Map.entry(CONCEPT_MATERIAL, "相材"),
            Map.entry(CONCEPT_NOURISH, "滋养"),
            Map.entry(CONCEPT_ARMOR, "坚甲"),
            Map.entry(CONCEPT_BLADE, "兵刃"),
            Map.entry(CONCEPT_TOOL, "利器"),
            Map.entry(CONCEPT_WOOD, "木质"),
            Map.entry(CONCEPT_REDSTONE, "机枢"),
            Map.entry(CONCEPT_METAL, "精矿"),
            Map.entry(CONCEPT_NETHER, "狱焰"),
            Map.entry(CONCEPT_ENDER, "末界"),
            Map.entry(CONCEPT_AQUATIC, "渊息"),
            Map.entry(CONCEPT_STONE, "坚岩"),
            Map.entry(CONCEPT_PLANT, "生机"),
            Map.entry(CONCEPT_EARTH, "大地"),
            Map.entry(CONCEPT_MONSTER, "魔骸"),
            Map.entry(CONCEPT_SAND, "流沙"),
            Map.entry(CONCEPT_NEWBORN, "新生"),
            Map.entry(CONCEPT_LIGHTWEIGHT, "轻盈"),
            Map.entry(CONCEPT_COMMON, "凡物"),
            Map.entry(CONCEPT_RECORD, "记载"),
            Map.entry(CONCEPT_GUIDE, "指引"),
            Map.entry(CONCEPT_TIMEKEEPING, "计时"),
            Map.entry(CONCEPT_VESSEL, "容器"),
            Map.entry(CONCEPT_CLEANSE, "净化"),
            Map.entry(CONCEPT_UTENSIL, "器皿"),
            Map.entry(CONCEPT_MANA_VIAL, "魔瓶"),
            Map.entry(CONCEPT_DRAGON_BREATH, "龙息"),
            Map.entry(CONCEPT_ECHO, "回响"),
            Map.entry(CONCEPT_PRISMARINE, "海晶"),
            Map.entry(CONCEPT_SPIRAL_SHELL, "螺旋护壳"),
            Map.entry(CONCEPT_SEA_HEART, "海洋之心"),
            Map.entry(CONCEPT_CELEBRATION, "庆典"),
            Map.entry(CONCEPT_TACK, "驭具"),
            Map.entry(CONCEPT_NAME_BOND, "名契"),
            Map.entry(CONCEPT_BINDING, "束缚"),
            Map.entry(CONCEPT_TETHER, "牵引"),
            Map.entry(CONCEPT_HONEY, "甘蜜"),
            Map.entry(CONCEPT_BLINK, "瞬移"),
            Map.entry(CONCEPT_SHULKER, "潜影"),
            Map.entry(CONCEPT_WING, "翼"),
            Map.entry(CONCEPT_SKULL, "颅"),
            Map.entry(CONCEPT_CANDLE, "烛"),
            Map.entry(CONCEPT_SCULK, "幽匿")
    );

    /** 概念键 → 中文概念名；未知键退化为键本身。 */
    public static String conceptNameCn(String conceptKey) {
        return CONCEPT_CN.getOrDefault(conceptKey, conceptKey);
    }

    /**
     * 完整入口：PhaseData > 功能/效果 tag > 推导（①.5 杂项栏物品在 ① 之后插队，
     * 给具体概念并并入显式数据；其余已有显式数据（①）的物品概念键固定为
     * {@link #CONCEPT_MATERIAL}，数据原样沿用）。
     */
    public static ItemConcept resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new ItemConcept(Set.of(), Set.of(), Map.of(), CONCEPT_COMMON);
        }
        // ① 已有 PhaseData（component 或数据包定义）/ 功能 tag / 效果 tag → 用现有结果
        var pd = PhaseFunctionResolver.effectivePhaseData(stack);
        Set<PhaseFunction> functions = PhaseFunctionResolver.getExplicit(stack);
        Map<ResourceLocation, Integer> effects =
                EffectMaterialResolver.get(stack, PhaseFunctionResolver.resolveTier(stack));
        // ①.5 杂项栏物品：给具体概念（记载/指引/计时/容器/龙息/翼…）。
        //      显式 PhaseData/tag 数据<b>并入</b>而非丢弃——如回响碎片的 AREA_HARVEST tag、
        //      潜影壳的 LEVITATION tag、鹦鹉螺壳的 conduit_power effect tag 全部保留。
        ItemConcept misc = resolveMisc(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        if (misc != null) {
            return mergeMisc(misc, pd, functions, effects);
        }
        if (pd != null || !functions.isEmpty() || !effects.isEmpty()) {
            Set<Phase> phases = (pd != null && pd.phases() != null && !pd.phases().isEmpty())
                    ? pd.phases()
                    : PhaseFunctionResolver.defaultPhases(functions);
            return new ItemConcept(phases == null ? Set.of() : phases, functions, effects, CONCEPT_MATERIAL);
        }
        // ②~⑫ 推导
        return derive(stack);
    }

    /**
     * 纯推导（②~⑫）：不查 PhaseData / 功能 tag / 效果 tag。
     * 先到先得；任何常见物品都会有结果，兜底「凡物」。
     */
    public static ItemConcept derive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new ItemConcept(Set.of(), Set.of(), Map.of(), CONCEPT_COMMON);
        }
        Item item = stack.getItem();
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();

        // ①.5 腐肉特例（必须在食物之前）：萌新反向利用 → 冲突相 + 微弱 POISON，概念「魔骸」
        if (path.equals("rotten_flesh")) {
            return of(CONCEPT_MONSTER, phases(Phase.CONFLICT), fns(PhaseFunction.POISON), Map.of());
        }

        // ①.6 杂项栏物品（必须在食物/装备/各类别之前：蜂蜜瓶与紫颂果是食物、鞘翅是装备、
        //      海晶/鹦鹉螺/海洋之心会命中水生、回响碎片/潜影壳/龙息/头颅会命中怪物掉落或末地）——
        //      书/地图/时钟/桶/蜡烛/烟花等给具体概念，不再落兜底「凡物」。
        ItemConcept misc = resolveMisc(path);
        if (misc != null) return misc;

        // ②a 有害食物（腐肉已特例在前；蜘蛛眼/毒马铃薯/河豚等带非有益食用效果）→
        //      冲突相 + POISON。「食物=滋养」不适用于下毒的东西——按 FoodProperties
        //      的食用效果判定，不堆物品特例。
        if (isHarmfulFood(stack)) {
            return of(CONCEPT_MONSTER, phases(Phase.CONFLICT), fns(PhaseFunction.POISON), Map.of());
        }

        // ② 食物 → HEAL + 生命相，概念「滋养」
        if (stack.get(DataComponents.FOOD) != null) {
            return of(CONCEPT_NOURISH, phases(Phase.LIFE), fns(PhaseFunction.HEAL), Map.of());
        }

        // ③ 装备类（金制装备额外给 MANA——黄金是中和之物，见矿物规则的金条目）
        if (item instanceof ArmorItem) {
            boolean hide = path.contains("leather") || path.contains("turtle");
            boolean gold = path.contains("gold");
            Set<Phase> p = hide ? phases(Phase.ABYSS, Phase.ORDER) : phases(Phase.ORDER);
            Set<PhaseFunction> f = hide ? fns(PhaseFunction.BASE_HIDE, PhaseFunction.DEFENSE)
                                        : fns(PhaseFunction.BASE_METAL, PhaseFunction.DEFENSE);
            if (gold) {
                p.add(Phase.KNOWLEDGE);
                f.add(PhaseFunction.MANA);
            }
            return of(CONCEPT_ARMOR, p, f, Map.of());
        }
        if (item instanceof SwordItem || path.equals("trident") || path.equals("mace")) {
            boolean wood = item instanceof TieredItem t && t.getTier() == Tiers.WOOD;
            boolean gold = item instanceof TieredItem t && t.getTier() == Tiers.GOLD;
            Set<Phase> p = wood ? phases(Phase.LIFE, Phase.CONFLICT) : phases(Phase.ORDER, Phase.CONFLICT);
            Set<PhaseFunction> f = fns(wood ? PhaseFunction.BASE_WOOD : PhaseFunction.BASE_METAL,
                    PhaseFunction.EDGE);
            if (gold) {
                p.add(Phase.KNOWLEDGE);
                f.add(PhaseFunction.MANA);
            }
            return of(CONCEPT_BLADE, p, f, Map.of());
        }
        if (path.equals("bow") || path.equals("crossbow")) {
            return of(CONCEPT_BLADE, phases(Phase.LIFE, Phase.CONFLICT),
                    fns(PhaseFunction.BASE_WOOD, PhaseFunction.EDGE), Map.of());
        }
        // 箭矢（普通/光灵/药箭）→ 锋锐，概念「兵刃」
        if (path.equals("arrow") || path.equals("spectral_arrow") || path.equals("tipped_arrow")) {
            return of(CONCEPT_BLADE, phases(Phase.LIFE, Phase.CONFLICT),
                    fns(PhaseFunction.EDGE), Map.of());
        }
        if (path.equals("shield")) {
            return of(CONCEPT_ARMOR, phases(Phase.LIFE, Phase.ORDER),
                    fns(PhaseFunction.BASE_WOOD, PhaseFunction.DEFENSE), Map.of());
        }
        if (path.equals("shears")) {
            return of(CONCEPT_TOOL, phases(Phase.ORDER), fns(PhaseFunction.EDGE), Map.of());
        }
        if (path.equals("flint_and_steel")) {
            return of(CONCEPT_TOOL, phases(Phase.FIRE), fns(PhaseFunction.IGNITE), Map.of());
        }
        if (item instanceof DiggerItem digger) {
            boolean wood = digger.getTier() == Tiers.WOOD;
            boolean gold = digger.getTier() == Tiers.GOLD;
            Set<PhaseFunction> f = fns(wood ? PhaseFunction.BASE_WOOD : PhaseFunction.BASE_METAL,
                    PhaseFunction.AREA_HARVEST);
            if (item instanceof HoeItem) f.add(PhaseFunction.GROWTH);
            Set<Phase> p = wood ? phases(Phase.LIFE) : phases(Phase.ORDER);
            if (gold) {
                p.add(Phase.KNOWLEDGE);
                f.add(PhaseFunction.MANA);
            }
            return of(CONCEPT_TOOL, p, f, Map.of());
        }

        // ④ 矿物（矿石/粗矿/锭/宝石，含对应矿石块）→ 对应相 + EDGE 或 BASE_METAL，概念「精矿」
        ItemConcept mineral = resolveMineral(path);
        if (mineral != null) return mineral;

        // ⑤ 红石类 → 秩序相 + MANA，概念「机枢」
        //    （必须在石质之前：redstone 含 "stone" 子串）
        if (isRedstoneComponent(path)) {
            return of(CONCEPT_REDSTONE, phases(Phase.ORDER), fns(PhaseFunction.MANA), Map.of());
        }

        // ⑥ 下界物 → 混沌/火相，概念「狱焰」
        ItemConcept nether = resolveNether(path);
        if (nether != null) return nether;

        // ⑦ 末地物 → 超脱相，概念「末界」
        ItemConcept ender = resolveEnder(path);
        if (ender != null) return ender;

        // ⑧ 木质：logs → BASE_WOOD + 生命；planks/stick → BASE_WOOD，概念「木质」
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || path.equals("stick")) {
            return of(CONCEPT_WOOD, phases(Phase.LIFE), fns(PhaseFunction.BASE_WOOD), Map.of());
        }

        // ⑨ 水生 → 沉潜相，概念「渊息」
        ItemConcept aquatic = resolveAquatic(path);
        if (aquatic != null) return aquatic;

        // ⑩ 石质/岩类 → BASE_METAL（石质基底）+ DEFENSE（坚岩：萌新第一件防具）+ 沉潜/秩序相，概念「坚岩」
        if (containsAny(path, "stone", "deepslate", "andesite", "granite", "diorite",
                "tuff", "calcite", "brick", "bedrock")) {
            return of(CONCEPT_STONE, phases(Phase.ABYSS, Phase.ORDER),
                    fns(PhaseFunction.BASE_METAL, PhaseFunction.DEFENSE), Map.of());
        }

        // ⑪a 花（先于一般植物）：生命/圣相 + GROWTH + 微光 REGENERATION，概念「生机」
        if (stack.is(ItemTags.FLOWERS)) {
            return of(CONCEPT_PLANT, phases(Phase.LIFE, Phase.LIGHT),
                    fns(PhaseFunction.GROWTH, PhaseFunction.REGENERATION), Map.of());
        }

        // ⑪ 植物类（原版 tag + 名称）→ 生命相 + GROWTH，概念「生机」
        if (stack.is(ItemTags.SAPLINGS) || stack.is(ItemTags.LEAVES)
                || containsAny(path, "seeds", "sprouts", "moss", "vine", "fungus", "mushroom",
                        "roots", "azalea", "blossom", "petal", "fern", "lily", "flower",
                        "wheat", "sugar_cane", "bamboo", "cactus", "propagule",
                        "short_grass", "tall_grass")) {
            return of(CONCEPT_PLANT, phases(Phase.LIFE), fns(PhaseFunction.GROWTH), Map.of());
        }

        // ⑫a 沙类（先于土类）：时间相 + SLOW（流沙控场，萌新满地可得的软控辅料），概念「流沙」
        if (path.equals("sand") || path.equals("red_sand")) {
            return of(CONCEPT_SAND, phases(Phase.TIME), fns(PhaseFunction.SLOW), Map.of());
        }

        // ⑫ 土类 → 生命/中和相 + 微量 HEAL（大地滋养：萌新第一件治疗装的来源），概念「大地」
        if (isEarth(path)) {
            return of(CONCEPT_EARTH, phases(Phase.LIFE, Phase.NEUTRAL),
                    fns(PhaseFunction.HEAL), Map.of());
        }

        // ⑬a 羽毛 → 超脱相 + 微弱 LEVITATION（轻盈），概念「轻盈」
        if (path.equals("feather")) {
            return of(CONCEPT_LIGHTWEIGHT, phases(Phase.TRANSCEND),
                    fns(PhaseFunction.LEVITATION), Map.of());
        }

        // ⑬b 鸡蛋 → 生命相，无功能算子（辅料），概念「新生」
        if (path.equals("egg")) {
            return of(CONCEPT_NEWBORN, phases(Phase.LIFE), Set.of(), Map.of());
        }

        // ⑬ 怪物掉落 → 冲突相 + 对应算子，概念「魔骸」
        ItemConcept monster = resolveMonsterDrop(path);
        if (monster != null) return monster;

        // ⑭ 兜底：方块 → 秩序相「凡物」；物品 → 中和相「凡物」
        return of(CONCEPT_COMMON,
                item instanceof BlockItem ? phases(Phase.ORDER) : phases(Phase.NEUTRAL),
                Set.of(), Map.of());
    }

    // ============ 各类别规则 ============

    /**
     * 杂项栏规则：创造模式「杂项」标签页的物品给具体概念——书/纸→「记载」、地图/指南针→「指引」、
     * 时钟→「计时」、桶→「容器」、奶桶→「净化」、碗→「器皿」、玻璃瓶→「魔瓶」、龙息→「龙息」、
     * 回响碎片→「回响」、海晶→「海晶」、鹦鹉螺壳→「螺旋护壳」、海洋之心→「海洋之心」、烟花→「庆典」、
     * 马鞍→「驭具」、命名牌→「名契」、锁链→「束缚」、拴绳→「牵引」、蜂蜜→「甘蜜」、紫颂果→「瞬移」、
     * 潜影壳→「潜影」、鞘翅→「翼」、头颅→「颅」、蜡烛→「烛」、幽匿系→「幽匿」。
     * <p>
     * 强弱按获取难度：海洋之心/鞘翅/龙息给高档效果（潮涌能量×2 / 缓降×2 / MANA+IGNITE 双算子），
     * 普通杂货（书/纸/桶/碗/蜡烛/烟花…）只给相性 + 概念。返回 null 表示非杂项栏物品。
     */
    private static ItemConcept resolveMisc(String path) {
        // 智识：书/纸 → 「记载」
        if (path.contains("book") || path.equals("paper")) {
            return of(CONCEPT_RECORD, phases(Phase.KNOWLEDGE), Set.of(), Map.of());
        }
        // 智识+秩序：地图/指南针 → 「指引」
        if (path.equals("map") || path.equals("filled_map") || path.contains("compass")) {
            return of(CONCEPT_GUIDE, phases(Phase.KNOWLEDGE, Phase.ORDER), Set.of(), Map.of());
        }
        // 时间：时钟 → 「计时」
        if (path.equals("clock")) {
            return of(CONCEPT_TIMEKEEPING, phases(Phase.TIME), Set.of(), Map.of());
        }
        // 生命：奶桶 → HEAL（净化解毒），概念「净化」（必须在通用桶规则之前）
        if (path.equals("milk_bucket")) {
            return of(CONCEPT_CLEANSE, phases(Phase.LIFE), fns(PhaseFunction.HEAL), Map.of());
        }
        // 中和：一切桶 → 「容器」
        if (path.contains("bucket")) {
            return of(CONCEPT_VESSEL, phases(Phase.NEUTRAL), Set.of(), Map.of());
        }
        // 中和：碗 → 「器皿」
        if (path.equals("bowl")) {
            return of(CONCEPT_UTENSIL, phases(Phase.NEUTRAL), Set.of(), Map.of());
        }
        // 超脱：玻璃瓶/附魔之瓶 → 「魔瓶」
        if (path.equals("glass_bottle") || path.equals("experience_bottle")) {
            return of(CONCEPT_MANA_VIAL, phases(Phase.TRANSCEND), Set.of(), Map.of());
        }
        // 超脱：药水/喷溅药水/滞留药水 → MANA（装满的魔瓶），概念「魔瓶」
        if (path.equals("potion") || path.equals("splash_potion") || path.equals("lingering_potion")) {
            return of(CONCEPT_MANA_VIAL, phases(Phase.TRANSCEND), fns(PhaseFunction.MANA), Map.of());
        }
        // 混沌：龙息（高档）→ MANA + IGNITE 双算子，概念「龙息」
        if (path.equals("dragon_breath")) {
            return of(CONCEPT_DRAGON_BREATH, phases(Phase.CHAOS),
                    fns(PhaseFunction.MANA, PhaseFunction.IGNITE), Map.of());
        }
        // 混沌+智识：回响碎片 → 「回响」（AREA_HARVEST tag 在 resolve() 里并入，保持）
        if (path.equals("echo_shard")) {
            return of(CONCEPT_ECHO, phases(Phase.CHAOS, Phase.KNOWLEDGE),
                    fns(PhaseFunction.MANA), Map.of());
        }
        // 沉潜：海晶碎片/海晶砂粒 → 「海晶」（prismarine 方块仍走水生「渊息」）
        if (path.equals("prismarine_shard") || path.equals("prismarine_crystals")) {
            return of(CONCEPT_PRISMARINE, phases(Phase.ABYSS), Set.of(), Map.of());
        }
        // 沉潜：鹦鹉螺壳 → DEFENSE，概念「螺旋护壳」
        if (path.equals("nautilus_shell")) {
            return of(CONCEPT_SPIRAL_SHELL, phases(Phase.ABYSS), fns(PhaseFunction.DEFENSE), Map.of());
        }
        // 沉潜+超脱：海洋之心（高档）→ MANA + 潮涌能量×2，概念「海洋之心」
        if (path.equals("heart_of_the_sea")) {
            return of(CONCEPT_SEA_HEART, phases(Phase.ABYSS, Phase.TRANSCEND),
                    fns(PhaseFunction.MANA), effect("conduit_power", 2));
        }
        // 火+超脱：烟花火箭/烟花之星 → 「庆典」
        if (path.equals("firework_rocket") || path.equals("firework_star")) {
            return of(CONCEPT_CELEBRATION, phases(Phase.FIRE, Phase.TRANSCEND), Set.of(), Map.of());
        }
        // 秩序：马鞍/命名牌/拴绳 → 「驭具」/「名契」/「牵引」
        if (path.equals("saddle")) {
            return of(CONCEPT_TACK, phases(Phase.ORDER), Set.of(), Map.of());
        }
        if (path.equals("name_tag")) {
            return of(CONCEPT_NAME_BOND, phases(Phase.ORDER), Set.of(), Map.of());
        }
        if (path.equals("lead")) {
            return of(CONCEPT_TETHER, phases(Phase.ORDER), Set.of(), Map.of());
        }
        // 秩序：锁链 → DEFENSE，概念「束缚」
        if (path.equals("chain")) {
            return of(CONCEPT_BINDING, phases(Phase.ORDER), fns(PhaseFunction.DEFENSE), Map.of());
        }
        // 生命：蜂蜜瓶/蜜脾/蜜块 → REGENERATION，概念「甘蜜」（必须在食物规则之前）
        if (path.contains("honey")) {
            return of(CONCEPT_HONEY, phases(Phase.LIFE), fns(PhaseFunction.REGENERATION), Map.of());
        }
        // 超脱：紫颂果/爆裂紫颂果 → LEVITATION，概念「瞬移」（必须在食物规则之前）
        if (path.equals("chorus_fruit") || path.equals("popped_chorus_fruit")) {
            return of(CONCEPT_BLINK, phases(Phase.TRANSCEND), fns(PhaseFunction.LEVITATION), Map.of());
        }
        // 超脱：潜影壳 → DEFENSE，概念「潜影」
        if (path.equals("shulker_shell")) {
            return of(CONCEPT_SHULKER, phases(Phase.TRANSCEND), fns(PhaseFunction.DEFENSE), Map.of());
        }
        // 超脱：鞘翅（高档）→ LEVITATION + 缓降×2，概念「翼」
        if (path.equals("elytra")) {
            return of(CONCEPT_WING, phases(Phase.TRANSCEND),
                    fns(PhaseFunction.LEVITATION), effect("slow_falling", 2));
        }
        // 冲突：头颅 → 「颅」（凋零骷髅头保留时间相+凋零效果，龙头保留超脱相）
        if (path.endsWith("_head") || path.endsWith("_skull")) {
            if (path.equals("wither_skeleton_skull")) {
                return of(CONCEPT_SKULL, phases(Phase.CONFLICT, Phase.TIME), Set.of(), effect("wither"));
            }
            if (path.equals("dragon_head")) {
                return of(CONCEPT_SKULL, phases(Phase.CONFLICT, Phase.TRANSCEND), Set.of(), Map.of());
            }
            return of(CONCEPT_SKULL, phases(Phase.CONFLICT), Set.of(), Map.of());
        }
        // 火+圣：蜡烛（含染色蜡烛与蜡烛蛋糕）→ 「烛」
        if (path.contains("candle")) {
            return of(CONCEPT_CANDLE, phases(Phase.FIRE, Phase.LIGHT), Set.of(), Map.of());
        }
        // 沉潜+智识：幽匿系（幽匿催发体/感测体/尖啸体等）→ 「幽匿」
        if (path.contains("sculk")) {
            return of(CONCEPT_SCULK, phases(Phase.ABYSS, Phase.KNOWLEDGE), Set.of(), Map.of());
        }
        return null;
    }

    /**
     * 杂项概念 × 显式数据合并：PhaseData/tag 的相性、算子、效果全部并入杂项概念，
     * 效果同 id 取高等级。保证「已有 tag 保持」——回响碎片的 AREA_HARVEST、
     * 潜影壳/紫颂果的 LEVITATION、鹦鹉螺壳的 conduit_power 等不会因具体概念而丢失。
     */
    private static ItemConcept mergeMisc(ItemConcept misc, PhaseData pd, Set<PhaseFunction> functions,
                                         Map<ResourceLocation, Integer> effects) {
        Set<Phase> phases = EnumSet.noneOf(Phase.class);
        phases.addAll(misc.phases());
        if (pd != null && pd.phases() != null) phases.addAll(pd.phases());
        if (phases.isEmpty()) phases.addAll(PhaseFunctionResolver.defaultPhases(functions));
        Set<PhaseFunction> fn = EnumSet.noneOf(PhaseFunction.class);
        fn.addAll(misc.functions());
        fn.addAll(functions);
        Map<ResourceLocation, Integer> fx = new HashMap<>(effects);
        misc.effects().forEach((id, lv) -> fx.merge(id, lv, Math::max));
        return of(misc.conceptKey(), phases, fn, fx);
    }

    /** 矿物规则：coal→火、iron→秩序、copper→火、gold→智识、diamond→超脱、emerald→生命、
     *  lapis→智识、redstone→秩序+MANA、quartz→秩序、netherite→混沌。 */
    private static ItemConcept resolveMineral(String path) {
        if (path.contains("spawn_egg")) return null;  // 刷怪蛋不算矿物
        boolean ore = path.endsWith("_ore");
        // 煤/木炭：火相 + 点燃
        if (ore && path.contains("coal") || path.equals("coal") || path.equals("charcoal")
                || path.equals("coal_block")) {
            return of(CONCEPT_METAL, phases(Phase.FIRE), fns(PhaseFunction.IGNITE), Map.of());
        }
        // 青金石：智识 + 法力
        if ((ore && path.contains("lapis")) || path.contains("lapis_lazuli") || path.equals("lapis_block")) {
            return of(CONCEPT_METAL, phases(Phase.KNOWLEDGE), fns(PhaseFunction.MANA), Map.of());
        }
        // 红石矿石：秩序 + 法力
        if (ore && path.contains("redstone")) {
            return of(CONCEPT_METAL, phases(Phase.ORDER), fns(PhaseFunction.MANA), Map.of());
        }
        // 石英：秩序 + 锋锐
        if (path.contains("quartz")) {
            return of(CONCEPT_METAL, phases(Phase.ORDER), fns(PhaseFunction.EDGE), Map.of());
        }
        // 钻石：超脱 + 金属基底 + 锋锐
        if ((ore && path.contains("diamond")) || path.contains("diamond")) {
            return of(CONCEPT_METAL, phases(Phase.TRANSCEND),
                    fns(PhaseFunction.BASE_METAL, PhaseFunction.EDGE), Map.of());
        }
        // 绿宝石：生命 + 金属基底
        if ((ore && path.contains("emerald")) || path.contains("emerald")) {
            return of(CONCEPT_METAL, phases(Phase.LIFE), fns(PhaseFunction.BASE_METAL), Map.of());
        }
        // 下界合金/远古残骸：混沌 + 金属基底
        if (path.contains("netherite") || path.equals("ancient_debris")) {
            return of(CONCEPT_METAL, phases(Phase.CHAOS), fns(PhaseFunction.BASE_METAL), Map.of());
        }
        // 铁：秩序 + 金属基底
        if ((ore && path.contains("iron")) || path.contains("iron")) {
            return of(CONCEPT_METAL, phases(Phase.ORDER), fns(PhaseFunction.BASE_METAL), Map.of());
        }
        // 铜：火 + 金属基底
        if ((ore && path.contains("copper")) || path.contains("copper")) {
            return of(CONCEPT_METAL, phases(Phase.FIRE), fns(PhaseFunction.BASE_METAL), Map.of());
        }
        // 金粒：硬币（通行全游戏的中和介质）→ 智识 + MANA，不当骨架
        if (path.equals("gold_nugget")) {
            return of(CONCEPT_METAL, phases(Phase.KNOWLEDGE), fns(PhaseFunction.MANA), Map.of());
        }
        // 金：智识 + 金属基底 + MANA——黄金是中和之物（通行全游戏的中和介质，见黄金经济）
        if ((ore && path.contains("gold")) || path.contains("gold")) {
            return of(CONCEPT_METAL, phases(Phase.KNOWLEDGE),
                    fns(PhaseFunction.BASE_METAL, PhaseFunction.MANA), Map.of());
        }
        // 紫水晶：智识/超脱 + 锋锐
        if (path.contains("amethyst")) {
            return of(CONCEPT_METAL, phases(Phase.KNOWLEDGE, Phase.TRANSCEND),
                    fns(PhaseFunction.EDGE), Map.of());
        }
        // 其余 *_ore（mod 矿物等）：秩序 + 金属基底
        if (ore) {
            return of(CONCEPT_METAL, phases(Phase.ORDER), fns(PhaseFunction.BASE_METAL), Map.of());
        }
        return null;
    }

    /** 有害食物：带任一非有益食用效果（毒/饥饿/凋零…）的食物不算「滋养」，算「魔骸」POISON。 */
    private static boolean isHarmfulFood(ItemStack stack) {
        var food = stack.get(DataComponents.FOOD);
        if (food == null) return false;
        for (var entry : food.effects()) {
            if (!entry.effect().getEffect().value().isBeneficial()) return true;
        }
        return false;
    }

    private static boolean isRedstoneComponent(String path) {
        return containsAny(path, "redstone", "repeater", "comparator", "piston", "observer",
                "dispenser", "dropper", "hopper", "lever", "_button", "pressure_plate",
                "daylight_detector", "tripwire_hook", "target", "note_block", "rail",
                "bell", "lightning_rod");
    }

    private static ItemConcept resolveNether(String path) {
        if (path.equals("netherrack")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS), Set.of(), Map.of());
        }
        if (path.equals("soul_sand") || path.equals("soul_soil")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS, Phase.ABYSS), Set.of(), Map.of());
        }
        if (containsAny(path, "basalt", "blackstone", "obsidian", "nether_brick")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS, Phase.ORDER), Set.of(), Map.of());
        }
        if (containsAny(path, "glowstone")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS, Phase.LIGHT), Set.of(), Map.of());
        }
        if (path.equals("magma_block")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS, Phase.FIRE),
                    fns(PhaseFunction.IGNITE), Map.of());
        }
        if (containsAny(path, "nether_wart", "wart_block", "nylium")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS, Phase.LIFE), Set.of(), Map.of());
        }
        if (path.equals("shroomlight")) {
            return of(CONCEPT_NETHER, phases(Phase.CHAOS, Phase.LIGHT), Set.of(), Map.of());
        }
        return null;
    }

    private static ItemConcept resolveEnder(String path) {
        if (path.equals("end_crystal") || path.equals("dragon_egg")) {
            return of(CONCEPT_ENDER, phases(Phase.TRANSCEND, Phase.LIGHT),
                    fns(PhaseFunction.MANA), Map.of());
        }
        if (path.equals("elytra")) {
            return of(CONCEPT_ENDER, phases(Phase.TRANSCEND), fns(PhaseFunction.LEVITATION), Map.of());
        }
        if (path.contains("chorus")) {
            return of(CONCEPT_ENDER, phases(Phase.TRANSCEND), fns(PhaseFunction.LEVITATION), Map.of());
        }
        if (path.equals("dragon_head")) {
            return of(CONCEPT_ENDER, phases(Phase.TRANSCEND, Phase.CONFLICT), Set.of(), Map.of());
        }
        if (containsAny(path, "end_stone", "end_rod", "purpur", "shulker_box", "ender_chest")) {
            return of(CONCEPT_ENDER, phases(Phase.TRANSCEND), Set.of(), Map.of());
        }
        return null;
    }

    private static ItemConcept resolveAquatic(String path) {
        if (path.equals("heart_of_the_sea")) {
            return of(CONCEPT_AQUATIC, phases(Phase.ABYSS, Phase.KNOWLEDGE),
                    fns(PhaseFunction.MANA), Map.of());
        }
        if (path.equals("nautilus_shell")) {
            return of(CONCEPT_AQUATIC, phases(Phase.ABYSS), fns(PhaseFunction.DEFENSE), Map.of());
        }
        if (path.equals("turtle_scute") || path.equals("turtle_egg")) {
            return of(CONCEPT_AQUATIC, phases(Phase.ABYSS, Phase.LIFE), Set.of(), Map.of());
        }
        if (containsAny(path, "kelp", "seagrass", "sea_pickle", "sponge", "ink_sac",
                "coral", "prismarine", "sea_lantern", "conduit")) {
            return of(CONCEPT_AQUATIC, phases(Phase.ABYSS), Set.of(), Map.of());
        }
        return null;
    }

    private static boolean isEarth(String path) {
        return switch (path) {
            case "dirt", "grass_block", "sand", "red_sand", "gravel", "clay", "mud",
                 "packed_mud", "podzol", "mycelium", "farmland", "dirt_path",
                 "rooted_dirt", "coarse_dirt", "clay_ball" -> true;
            default -> path.contains("dirt");
        };
    }

    private static ItemConcept resolveMonsterDrop(String path) {
        return switch (path) {
            case "bone", "bone_block" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT), fns(PhaseFunction.BASE_BONE), Map.of());
            case "bone_meal" ->
                    of(CONCEPT_MONSTER, phases(Phase.LIFE), fns(PhaseFunction.GROWTH), Map.of());
            case "string" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT), Set.of(), Map.of());
            case "gunpowder", "tnt" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.FIRE),
                            fns(PhaseFunction.IGNITE), Map.of());
            case "ender_pearl", "ender_eye" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.TRANSCEND), Set.of(), Map.of());
            case "blaze_rod", "blaze_powder" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.FIRE),
                            fns(PhaseFunction.IGNITE), Map.of());
            case "ghast_tear" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT), fns(PhaseFunction.REGENERATION),
                            effect("regeneration"));
            case "spider_eye", "fermented_spider_eye" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT), fns(PhaseFunction.POISON),
                            effect("poison"));
            case "slime_ball", "slime_block" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT), fns(PhaseFunction.SLOW), Map.of());
            case "magma_cream" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.FIRE),
                            fns(PhaseFunction.FIRE_RESIST), Map.of());
            case "shulker_shell" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.TRANSCEND),
                            fns(PhaseFunction.DEFENSE), Map.of());
            case "phantom_membrane" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.TRANSCEND),
                            fns(PhaseFunction.LEVITATION), Map.of());
            case "nether_star" ->
                    of(CONCEPT_MONSTER, phases(Phase.TRANSCEND, Phase.LIGHT),
                            fns(PhaseFunction.MANA), Map.of());
            case "dragon_breath" ->
                    of(CONCEPT_MONSTER, phases(Phase.FIRE, Phase.TRANSCEND), Set.of(), Map.of());
            case "wither_skeleton_skull" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.TIME), Set.of(),
                            effect("wither"));
            case "breeze_rod" ->
                    of(CONCEPT_MONSTER, phases(Phase.CONFLICT, Phase.TIME), Set.of(), Map.of());
            case "echo_shard" ->
                    of(CONCEPT_MONSTER, phases(Phase.KNOWLEDGE, Phase.ABYSS),
                            fns(PhaseFunction.MANA), Map.of());
            default -> null;
        };
    }

    // ============ 构造辅助 ============

    private static ItemConcept of(String conceptKey, Set<Phase> phases, Set<PhaseFunction> functions,
                                  Map<ResourceLocation, Integer> effects) {
        return new ItemConcept(
                phases.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(phases)),
                functions.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(functions)),
                effects.isEmpty() ? Map.of() : Collections.unmodifiableMap(new HashMap<>(effects)),
                conceptKey);
    }

    private static Set<Phase> phases(Phase... ps) {
        Set<Phase> set = EnumSet.noneOf(Phase.class);
        Collections.addAll(set, ps);
        return set;
    }

    private static Set<PhaseFunction> fns(PhaseFunction... fs) {
        Set<PhaseFunction> set = EnumSet.noneOf(PhaseFunction.class);
        Collections.addAll(set, fs);
        return set;
    }

    private static Map<ResourceLocation, Integer> effect(String effectPath) {
        return effect(effectPath, 1);
    }

    private static Map<ResourceLocation, Integer> effect(String effectPath, int level) {
        return Map.of(ResourceLocation.withDefaultNamespace(effectPath), Math.max(1, level));
    }

    private static boolean containsAny(String path, String... needles) {
        for (String n : needles) {
            if (path.contains(n)) return true;
        }
        return false;
    }
}
