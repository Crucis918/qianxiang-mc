package com.qianxiang.phase;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 锻造台动态组合器：把任意材料槽内容物实时组合成一把属性由材料决定的武器。
 * <p>
 * 这是「自定义功能」的核心兑现——玩家不再挑静态配方，
 * 而是把任意零件（原版铁锭/煤炭/自定义相材料）丢进锻造台，
 * 系统按 {@link AttributeScheme} 把它们翻译成 {@link ComposedAttributes}，
 * 再写进按材料并集选定的原型产物栈（相杖/骨刃/灼烧之刃）。
 * </p>
 *
 * <h3>组合流程</h3>
 * <ol>
 *   <li>解析每个材料栈：功能算子来自 {@link PhaseFunctionResolver}，
 *       档位来自栈上的 {@code phase_data}（无则默认 {@link PhaseTier#COMMON}——
 *       普通料弱，符合「强度靠稀有度」）。</li>
 *   <li>调 {@link AttributeScheme#compose} 算出 {@link ComposedAttributes}。</li>
 *   <li>可锻造判定：任意带功能算子的材料即可出产物，不再硬性要求 {@code BASE_*}；
 *       无 {@code BASE_*} 时按「无相骨架」兜底（补 36 点默认耐久，无其他加成）。</li>
 *   <li>产物 = ember_blade 栈，写入 composed_attributes + 数值 modifiers。</li>
 * </ol>
 */
public final class ForgeComposer {

    private ForgeComposer() {}

    /** 一条材料的解析结果：功能算子 + 档位。 */
    public record MaterialPart(Set<PhaseFunction> functions, PhaseTier tier) {}

    /**
     * 零件数软上限（25 槽扩容的平衡护栏）：超过此数的零件，其 powerScore 按
     * {@link #FORGE_EXCESS_WEIGHT} 计入——{@code effectiveParts = min(n,10) + (n-10)*0.5}。
     * 槽位从 10 涨到 25 是摆放自由度，不等于强度可以线性 ×2.5。调平只动这两个常量。
     */
    public static final int FORGE_PART_SOFT_CAP = 10;
    /** 超出软上限零件的 powerScore 权重（见 {@link #FORGE_PART_SOFT_CAP}）。 */
    public static final double FORGE_EXCESS_WEIGHT = 0.5;

    /** 组合结果：产物栈 + 组合属性（供 UI 预览/将来 AI 协商用）。 */
    public record Composition(ItemStack result, ComposedAttributes attributes) {
        /** 空结果：无产物、无属性。 */
        public static Composition empty() {
            return new Composition(ItemStack.EMPTY, ComposedAttributes.empty());
        }
        /** 产物非空即视为有效组合。 */
        public boolean valid() {
            return !result.isEmpty();
        }
    }

    /**
     * 把材料槽的 stacks 组合成产物（无 AI 覆盖的旧入口，等价于 {@code compose(stacks, null, null)}）。
     * <p>
     * 空输入、或所有材料都无功能算子（纯辅料，如草方块/泥土）→ 返回 {@link Composition#empty()}。
     * 无 BASE_* 骨架不再判空——走「无相骨架」兜底出低耐久产物。
     *
     * @param materialStacks 材料槽内容（含空栈也安全）
     * @return 组合结果；{@link Composition#valid()} 为 false 即不可锻造
     */
    public static Composition compose(List<ItemStack> materialStacks) {
        return compose(materialStacks, null, null);
    }

    /**
     * 把材料槽的 stacks 组合成产物，可附带最近一次 AI 响应的输出覆盖。
     *
     * @param materialStacks 材料槽内容（含空栈也安全）
     * @param aiSpellJson    最近一次 AI 响应附带的 spellJson（可空）；法术铭刻已迁往炼金台，
     *                       锻造台不再消费它，仅在 aiCustomName 为空时回退取其 name 字段
     * @param aiCustomName   AI 给产物起的自定义名（可空；空时尝试取 spellJson 的 name 字段）
     * @return 组合结果；{@link Composition#valid()} 为 false 即不可锻造
     */
    public static Composition compose(List<ItemStack> materialStacks, String aiSpellJson, String aiCustomName) {
        return compose(materialStacks, aiSpellJson, aiCustomName, null);
    }

    /**
     * 把材料槽的 stacks 组合成产物，可附带最近一次 AI 响应的输出覆盖（含 EF 动作定制）。
     *
     * @param materialStacks 材料槽内容（含空栈也安全）
     * @param aiSpellJson    最近一次 AI 响应附带的 spellJson（可空）；锻造台不再消费它，
     *                       仅在 aiCustomName 为空时回退取其 name 字段
     * @param aiCustomName   AI 给产物起的自定义名（可空；空时尝试取 spellJson 的 name 字段）
     * @param aiMovesetJson  最近一次 AI 响应附带的 movesetJson（可空，契约：category/combos/collider）；
     *                       JSON 有效且运行环境有动作集支持时写入产物 CUSTOM_MOVESET 组件
     *                       （任意产物类型都写），无效/无支持时静默跳过
     * @return 组合结果；{@link Composition#valid()} 为 false 即不可锻造
     */
    public static Composition compose(List<ItemStack> materialStacks, String aiSpellJson, String aiCustomName,
                                      String aiMovesetJson) {
        if (materialStacks == null || materialStacks.isEmpty()) {
            return Composition.empty();
        }

        // 1. 解析每条非空材料 → MaterialPart
        //    PhaseFunctionResolver.get 末尾带 ItemConceptResolver 推导兜底：
        //    石质→BASE_METAL、食物→HEAL 等推导算子也能当零件；
        //    纯辅料（草方块/泥土等只给相性不给算子）在此跳过，
        //    其相性/效果由 collectPhases / collectGrantedEffects 收集。
        List<MaterialPart> parts = new ArrayList<>(materialStacks.size());
        Set<PhaseFunction> union = EnumSet.noneOf(PhaseFunction.class);
        for (ItemStack stack : materialStacks) {
            if (stack == null || stack.isEmpty()) continue;
            Set<PhaseFunction> functions = PhaseFunctionResolver.get(stack);
            if (functions.isEmpty()) continue;  // 无功能算子，不算零件
            PhaseTier tier = resolveTier(stack);
            parts.add(new MaterialPart(functions, tier));
            union.addAll(functions);
        }
        if (parts.isEmpty()) {
            return Composition.empty();
        }

        // 2. 可锻造判定：不再硬性要求 BASE_* 骨架——任意带功能算子的材料都能出产物。
        //    无 BASE_* 时走「无相骨架」兜底（见步骤 3.5）：产物照出，只补少量默认耐久。
        //    （纯辅料无功能算子，已在步骤 1 被跳过，parts 为空时上面已返回。）
        boolean hasBase = hasBase(union);

        // 3. MaterialPart → MaterialInput → ComposedAttributes
        //    逐零件单独 compose 再累加：超过 FORGE_PART_SOFT_CAP 的零件 powerScore 减半
        //    （软化护栏，见常量注释）；属性/效果等级不受影响，足额累加。
        ComposedAttributes attr = ComposedAttributes.empty();
        int partIndex = 0;
        for (MaterialPart p : parts) {
            var input = AttributeScheme.MaterialInput.of(p.tier(),
                    p.functions().toArray(new PhaseFunction[0]));
            ComposedAttributes part = AttributeScheme.compose(List.of(input));
            if (partIndex >= FORGE_PART_SOFT_CAP) {
                part = part.withPowerScore(part.powerScore() * FORGE_EXCESS_WEIGHT);
            }
            attr = attr.add(part);
            partIndex++;
        }

        // 3.5 无相骨架兜底：无 BASE_* 时手动补一个虚拟基底贡献——
        //     相当于 COMMON BASE_WOOD 的 60% 耐久（36 点），无攻击速度等其他加成，
        //     不抬 powerScore（避免触发代价机制阈值）。
        if (!hasBase) {
            attr = attr.add(AttributeScheme.formlessBaseContribution());
        }

        // 4. 外观驱动：从原始材料栈收集相性，并按效果等级 + 功能算子判定主导外观
        Set<Phase> dominantPhases = collectPhases(materialStacks);
        String dominantEffect = resolveDominantEffect(attr, union);
        String appearanceKey = ComposedAttributes.appearanceKeyFor(dominantEffect);
        attr = attr.withAppearance(dominantPhases, dominantEffect, appearanceKey);

        // 4.5 通用自由效果：扫描所有材料栈（含无功能算子的纯效果材料，如凋零玫瑰）的
        //     qianxiang:materials/effect/* tag，写入 grantedEffects；同效果取大。
        attr = attr.withGrantedEffects(collectGrantedEffects(materialStacks));

        // 4.6 代价机制：强度总分超阈值或正面效果 ≥3 种时，自动附 1~2 个代价效果
        //     （「强力必有代价」）。读最终 powerScore/效果种类，须在 4.5 之后执行。
        attr = attr.withDrawbacks(DrawbackRules.generate(attr));

        // 4.7 反转器：材料槽含逆相之核（或任意 REVERSE 算子来源）→ 写入反转标志。
        //     防具携带效果由「接触反伤」变「抗性/免疫」；武器伤害型效果极性反转。
        if (containsReverse(materialStacks, union)) {
            attr = attr.withReversed();
        }

        // 5. 产物栈：按材料并集选原型（自定义广度），写入 composed_attributes + 数值 modifiers
        //    特例：法系组合（含 MANA）且材料含裂隙精髓 → 产物改为「千相法术书」。
        //    法杖/法术书已「增幅器化」：不再铭刻法术，只凭 COMPOSED_ATTRIBUTES 的
        //    spellPowerPercent/manaBonus 为施法提供加成（见 AmplifierHelper）。
        boolean makeSpellBook = union.contains(PhaseFunction.MANA) && containsRiftEssence(materialStacks);
        ItemStack out = makeSpellBook
                ? new ItemStack(QianxiangItems.SPELL_BOOK.get())
                : new ItemStack(pickArchetype(union).get());
        // 5.5 武器形态事实源：按布局（填充顺序=中心向外）一次推导写入组件——
        //     外观纹理/EF 动作/判定盒下游统一按形走（见 WeaponFormProfile）。
        //     核心材料基底族同写（刃体着色=核心材料色，刃缘=主导效果色）。
        attr = attr.withForm(com.qianxiang.combat.WeaponFormProfile.deriveForm(
                materialStacks, union, attr, out.getItem()))
                .withBaseFamily(com.qianxiang.combat.WeaponFormProfile.deriveBaseFamily(materialStacks));
        out.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(), attr);
        // 防具产物（QianxiangArmorItem）不写 MAINHAND 槽的 ATTRIBUTE_MODIFIERS——
        // 栈上组件会覆盖物品默认修饰符，而穿戴属性由
        // QianxiangArmorItem#getDefaultAttributeModifiers(ItemStack) 按护甲槽动态提供。
        // 法术书同理：它是纯增幅器（非武器），加成由 AmplifierHelper 读 COMPOSED_ATTRIBUTES 结算。
        if (!makeSpellBook && !(out.getItem() instanceof com.qianxiang.item.QianxiangArmorItem)) {
            AttributeScheme.applyModifiersToStack(out, attr);
        }

        // 6. AI 自定义名称：写 CUSTOM_NAME（任意产物类型均可，不限法系）。
        applyAiName(out, aiCustomName, aiSpellJson);

        // 7. AI 动作定制：movesetJson 有效且环境有动作集支持时 → 写 CUSTOM_MOVESET 组件
        //    （任意产物类型都写；动作集子代理未合并时静默跳过，不影响产物）。
        applyAiMoveset(out, aiMovesetJson);

        return new Composition(out, attr);
    }

    /** AI 自定义名称：优先显式 customName，缺省时取 spellJson 的 name 字段；空白则不写。 */
    private static void applyAiName(ItemStack out, String customName, String spellJson) {
        if (out.isEmpty()) return;
        try {
            String name = (customName == null || customName.isBlank())
                    ? CustomSpell.extractName(spellJson)
                    : customName.trim();
            if (name.isEmpty()) return;
            out.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 应用 AI 自定义名称失败（不影响产物）", t);
        }
    }

    /**
     * AI 动作定制：movesetJson（契约：category/combos/collider）解析为
     * {@link com.qianxiang.combat.WeaponMoveset} 写入产物 CUSTOM_MOVESET 组件——
     * 任意产物类型都写（武器形态由 category 表达）。
     * <p>
     * 与连击编辑器链路（{@link com.qianxiang.network.MovesetApplyHandler}）语义一致：
     * <b>最多 6 段、允许重复段</b>。此前 AI 链路走反射层且限 4 段并去重，
     * 两条链路对同一份 movesetJson 会产出不同结果。
     * <p>
     * 任何异常吞掉记日志——AI 输出不可信，不能拖垮合成。
     */
    private static void applyAiMoveset(ItemStack out, String movesetJson) {
        if (movesetJson == null || movesetJson.isBlank() || out.isEmpty()) return;
        try {
            com.qianxiang.combat.WeaponMoveset parsed =
                    com.qianxiang.combat.WeaponMoveset.fromJson(movesetJson);
            if (parsed == null) return;
            java.util.List<net.minecraft.resources.ResourceLocation> combos = new ArrayList<>();
            for (var id : parsed.combos()) {
                if (com.qianxiang.combat.AnimationLibrary.byId(id) == null) continue;
                combos.add(id);
                if (combos.size() >= com.qianxiang.network.MovesetApplyHandler.MAX_SEGMENTS) break;
            }
            if (combos.isEmpty()) return;
            out.set(QianxiangDataComponents.CUSTOM_MOVESET.get(),
                    new com.qianxiang.combat.WeaponMoveset(
                            parsed.category(), combos, parsed.colliderPreset()));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 应用 AI movesetJson 失败（不影响产物）", t);
        }
    }

    /**
     * 按材料功能并集选产物原型——「自定义功能」的广度兑现。
     * <ul>
     *   <li>含 MANA + 武器基底 → {@link QianxiangItems#PHASE_STAFF} 相杖（法系）</li>
     *   <li>含 BASE_HIDE + NIGHT_VISION/WATER_BREATH → {@link QianxiangItems#PHASE_HELMET} 相盔（头部感知类）</li>
     *   <li>含 BASE_HIDE + RESISTANCE/FIRE_RESIST → {@link QianxiangItems#PHASE_CHESTPLATE} 相甲（躯干抗性类）</li>
     *   <li>含 BASE_HIDE + REGENERATION/DEFENSE → {@link QianxiangItems#PHASE_LEGGINGS} 相胫（体干续航/防御类）</li>
     *   <li>含 BASE_HIDE + SPEED_BOOST/JUMP_BOOST → {@link QianxiangItems#PHASE_BOOTS} 相靴（腿脚机动类）</li>
     *   <li>纯 BASE_HIDE（无以上穿戴算子）或反伤 → {@link QianxiangItems#PHASE_SHIELD} 相盾（保持现有）</li>
     *   <li>含 AREA_HARVEST → {@link QianxiangItems#PHASE_HOE} 相锄（广域耕作工具）</li>
     *   <li>含 GROWTH → {@link QianxiangItems#PHASE_WATERING_CAN} 相之水壶（范围催熟工具）</li>
     *   <li>含 BASE_BONE → {@link QianxiangItems#BONE_BLADE} 骨刃（锋锐）</li>
     *   <li>否则（金属/木质基底）→ {@link QianxiangItems#EMBER_BLADE} 灼烧之刃</li>
     * </ul>
     * 优先级：MANA 杖 > 防具（盔 > 甲 > 胫 > 靴 > 盾；同槽位内按上表行内顺序）> 骨刃 > 武器特征（攻击向算子）> 工具（锄 > 水壶）> 金属刃。
     * 同一材料组合产出固定原型；换材料 = 换属性（强度靠材料）。
     * <p>
     * 无 BASE_*（无相骨架）时走独立兜底分支：MANA→相杖；GROWTH→水壶；AREA_HARVEST→相锄；
     * 其余（攻击向算子或纯效果）一律以灼烧之刃作通用载体。
     * </p>
     */
    private static DeferredHolder<Item, Item> pickArchetype(Set<PhaseFunction> union) {
        if (union.contains(PhaseFunction.MANA)) return QianxiangItems.PHASE_STAFF;
        if (!hasBase(union)) {
            // —— 无相骨架兜底原型：工具特征优先，其次攻击向/纯效果统一用金属刃载体 ——
            if (union.contains(PhaseFunction.GROWTH)) return QianxiangItems.PHASE_WATERING_CAN;
            if (union.contains(PhaseFunction.AREA_HARVEST)) return QianxiangItems.PHASE_HOE;
            return QianxiangItems.EMBER_BLADE;
        }
        if (union.contains(PhaseFunction.BASE_HIDE)) {
            // —— 防具分支：皮制基底 + 穿戴效果算子 → 对应护甲件；无穿戴算子的纯皮制 → 相盾 ——
            //    槽位判定固定按「盔 > 甲 > 胫 > 靴」顺序，多算子并存时取先命中槽位（结果确定且稳定）。
            if (union.contains(PhaseFunction.NIGHT_VISION)
                    || union.contains(PhaseFunction.WATER_BREATH)) return QianxiangItems.PHASE_HELMET;
            if (union.contains(PhaseFunction.RESISTANCE)
                    || union.contains(PhaseFunction.FIRE_RESIST)) return QianxiangItems.PHASE_CHESTPLATE;
            if (union.contains(PhaseFunction.REGENERATION)
                    || union.contains(PhaseFunction.DEFENSE)) return QianxiangItems.PHASE_LEGGINGS;
            if (union.contains(PhaseFunction.SPEED_BOOST)
                    || union.contains(PhaseFunction.JUMP_BOOST)) return QianxiangItems.PHASE_BOOTS;
            return QianxiangItems.PHASE_SHIELD;
        }
        if (union.contains(PhaseFunction.REFLECT)) return QianxiangItems.PHASE_SHIELD;
        if (union.contains(PhaseFunction.BASE_BONE)) return QianxiangItems.BONE_BLADE;
        // —— 武器特征优先于工具：有攻击向算子就是武器，哪怕混了植物/骨粉辅料（防「全是水壶」）——
        if (hasWeaponTrait(union)) return QianxiangItems.EMBER_BLADE;
        // —— 工具分支：只在无武器特征时生效（纯工具向组合才出工具）——
        if (union.contains(PhaseFunction.AREA_HARVEST)) return QianxiangItems.PHASE_HOE;
        if (union.contains(PhaseFunction.GROWTH)) return QianxiangItems.PHASE_WATERING_CAN;
        return QianxiangItems.EMBER_BLADE;
    }

    /**
     * 是否有武器特征：含任何攻击向效果算子即视为武器组合。
     * <p>概念引擎会把植物/种子/骨粉推导出 GROWTH、锄头类物品推导出 AREA_HARVEST，
     * 若工具算子优先级高于武器，玩家材料里混进一点植物辅料就全变水壶——
     * 武器特征判定保证「铁锭+余烬石+种子」仍出武器而非水壶。</p>
     */
    private static boolean hasWeaponTrait(Set<PhaseFunction> union) {
        return union.contains(PhaseFunction.EDGE)
                || union.contains(PhaseFunction.IGNITE)
                || union.contains(PhaseFunction.LIFESTEAL)
                || union.contains(PhaseFunction.POISON)
                || union.contains(PhaseFunction.FROST)
                || union.contains(PhaseFunction.STRENGTH)
                || union.contains(PhaseFunction.LEVITATION);
    }

    /** 读栈上的 phase_data 拿档位；没有（原版/Tag 零件）默认 COMMON。 */
    private static PhaseTier resolveTier(ItemStack stack) {
        return PhaseFunctionResolver.resolveTier(stack);
    }

    // ============================ 法术书产物判定（裂隙精髓 + MANA 组合） ============================

    /** 材料槽中是否含裂隙精髓（万法之根 → 法系组合升格为法术书）。 */
    private static boolean containsRiftEssence(List<ItemStack> materialStacks) {
        for (ItemStack stack : materialStacks) {
            if (stack != null && stack.is(com.qianxiang.QianxiangMaterials.RIFT_ESSENCE.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 材料槽是否含反转器：功能并集带 {@link PhaseFunction#REVERSE} 算子
     * （逆相之核的 PhaseData 自带），或直接出现 {@link QianxiangItems#REVERSE_CORE} 物品。
     * 双口径兜底——PhaseData 被数据包/组件剥离时物品 id 判定仍生效。
     */
    private static boolean containsReverse(List<ItemStack> materialStacks, Set<PhaseFunction> union) {
        if (union.contains(PhaseFunction.REVERSE)) {
            return true;
        }
        for (ItemStack stack : materialStacks) {
            if (stack != null && stack.is(QianxiangItems.REVERSE_CORE.get())) {
                return true;
            }
        }
        return false;
    }

    /** 是否含至少一个 BASE_* 骨架功能算子。 */
    private static boolean hasBase(Set<PhaseFunction> functions) {
        return functions.contains(PhaseFunction.BASE_METAL)
                || functions.contains(PhaseFunction.BASE_WOOD)
                || functions.contains(PhaseFunction.BASE_BONE)
                || functions.contains(PhaseFunction.BASE_HIDE);
    }

    /** 从材料栈收集相性并集（自定义 component 优先，否则经 ItemConceptResolver 按
     *  PhaseData>tag>推导 取概念相性——草方块等辅料也能贡献相性）。 */
    private static Set<Phase> collectPhases(List<ItemStack> materialStacks) {
        var set = EnumSet.noneOf(Phase.class);
        for (ItemStack stack : materialStacks) {
            if (stack == null || stack.isEmpty()) continue;
            PhaseData pd = PhaseFunctionResolver.effectivePhaseData(stack);
            if (pd != null && pd.phases() != null) {
                set.addAll(pd.phases());
            } else {
                set.addAll(ItemConceptResolver.resolve(stack).phases());
            }
        }
        return set.isEmpty() ? Set.of() : Collections.unmodifiableSet(set);
    }

    /**
     * 汇总所有材料栈的自由状态效果（effect tag → 等级），同效果取大。
     * 纯效果材料（无功能算子，如 minecraft:wither_rose）也参与——它们不当零件，
     * 只贡献 grantedEffects；单独放置不出产物（需至少一条带功能算子的材料，不要求 BASE_*）。
     * 经 {@link ItemConceptResolver#resolve} 取效果：effect tag 之外，
     * 推导概念自带的效果（如蜘蛛眼→中毒、恶魂之泪→再生）同样注入。
     */
    private static Map<ResourceLocation, Integer> collectGrantedEffects(List<ItemStack> materialStacks) {
        Map<ResourceLocation, Integer> granted = null;
        for (ItemStack stack : materialStacks) {
            if (stack == null || stack.isEmpty()) continue;
            Map<ResourceLocation, Integer> part = ItemConceptResolver.resolve(stack).effects();
            if (part.isEmpty()) continue;
            if (granted == null) granted = new HashMap<>();
            Map<ResourceLocation, Integer> g = granted;
            part.forEach((id, lv) -> g.merge(id, lv, Math::max));
        }
        return granted == null ? Map.of() : granted;
    }

    /**
     * 判定主导外观效果。
     * <p>
     * 优先看特殊效果等级（点燃/吸血/反伤/迟缓/疗伤）；若都没有，
     * 再看功能算子中的法力/骨制基底/防御，分别对应「法相」「骨相」「盾相」。
     * 这样火系发红光、骨系变白骨、法力系发紫光等外观变体才能被材料组合驱动。
     * </p>
     */
    private static String resolveDominantEffect(ComposedAttributes attr, Set<PhaseFunction> union) {
        String byLevel = ComposedAttributes.pickDominantEffect(
                attr.igniteLevel(), attr.lifestealLevel(), attr.thornsLevel(),
                attr.slowLevel(), attr.healLevel());
        if (!"none".equals(byLevel)) {
            return byLevel;
        }
        // 无特殊效果等级时，按功能算子优先级取外观主题
        if (union.contains(PhaseFunction.MANA)) return "mana";
        if (union.contains(PhaseFunction.BASE_BONE)) return "bone";
        if (union.contains(PhaseFunction.DEFENSE)) return "defense";
        return "none";
    }
}
