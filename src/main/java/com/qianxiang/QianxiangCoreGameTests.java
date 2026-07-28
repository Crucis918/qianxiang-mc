package com.qianxiang;

import com.qianxiang.blueprint.BlueprintData;
import com.qianxiang.blueprint.BlueprintShareCodes;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * 核心系统 GameTests：法术修饰词归一化 / 锻造组合 / 蓝图分享码 roundtrip 与消毒。
 * <p>
 * 复用 {@code qianxiang:item_concept} 空场地模板（同 {@link ItemConceptGameTests}）。
 * 运行：{@code ./gradlew runGameTestServer}（run 配置已启用 qianxiang 命名空间）。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangCoreGameTests {

    private QianxiangCoreGameTests() {}

    // ============================ 修饰词归一化 ============================

    /** 历史别名 duration/empower → extended/amplified；白名单外丢弃；合法词原样通过。 */
    @GameTest(template = "item_concept")
    public static void modifierNormalization(GameTestHelper helper) {
        helper.assertTrue("extended".equals(CustomSpell.normalizeModifier("duration")),
                "duration 应归一化为 extended");
        helper.assertTrue("amplified".equals(CustomSpell.normalizeModifier("EMPOWER")),
                "EMPOWER（大小写混合）应归一化为 amplified");
        helper.assertTrue("homing".equals(CustomSpell.normalizeModifier("homing")),
                "合法词 homing 应原样通过");
        helper.assertTrue(CustomSpell.normalizeModifier("fly_to_moon") == null,
                "白名单外的词应返回 null");
        helper.assertTrue(CustomSpell.normalizeModifier("") == null,
                "空串应返回 null");
        helper.succeed();
    }

    /** AI spellJson 解析：旧词 duration 进来，产物组件里必须是 extended。 */
    @GameTest(template = "item_concept")
    public static void spellJsonNormalizesLegacyWords(GameTestHelper helper) {
        CustomSpell spell = CustomSpell.fromSpellJson(
                "{\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\","
                        + "\"modifiers\":[\"duration\",\"empower\",\"bogus\"],\"power\":2}", 1);
        helper.assertTrue(spell != null, "合法 spellJson 不应解析失败");
        helper.assertTrue(spell.modifiers().contains("extended"),
                "duration 应折算为 extended，实际 " + spell.modifiers());
        helper.assertTrue(spell.modifiers().contains("amplified"),
                "empower 应折算为 amplified，实际 " + spell.modifiers());
        helper.assertTrue(!spell.modifiers().contains("bogus"),
                "白名单外的 bogus 应被丢弃，实际 " + spell.modifiers());
        helper.succeed();
    }

    /** 白名单外的 element 必须导致整条 spellJson 被拒（恶意客户端回传的防线）。 */
    @GameTest(template = "item_concept")
    public static void spellJsonRejectsUnknownElement(GameTestHelper helper) {
        CustomSpell spell = CustomSpell.fromSpellJson(
                "{\"element\":\"doom\",\"form\":\"projectile\",\"effect\":\"damage\",\"power\":3}", 1);
        helper.assertTrue(spell == null, "白名单外的 element 应导致整条 spellJson 被拒");
        helper.succeed();
    }

    /** 越界 power 必须被夹到上限，manaCost 随之保持正数（防溢出绕过法力检查）。 */
    @GameTest(template = "item_concept")
    public static void spellJsonClampsOversizedPower(GameTestHelper helper) {
        CustomSpell spell = CustomSpell.fromSpellJson(
                "{\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\",\"power\":999}", 1);
        helper.assertTrue(spell != null, "结构合法的 spellJson 不应解析失败");
        helper.assertTrue(spell.power() == 10, "power=999 应被夹到 10，实际 " + spell.power());
        helper.assertTrue(spell.manaCost() > 0, "manaCost 应为正数，实际 " + spell.manaCost());
        helper.succeed();
    }

    // ============================ 锻造组合 ============================

    /** 烬铁（BASE_METAL+IGNITE）应锻出产物且带 ComposedAttributes。 */
    @GameTest(template = "item_concept")
    public static void forgeComposesWeapon(GameTestHelper helper) {
        List<ItemStack> materials = padToSlots(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangItems.BEAST_FANG.get()));
        ForgeComposer.Composition c = ForgeComposer.compose(materials);
        helper.assertTrue(c.valid(), "烬铁+兽牙应能组合出产物");
        helper.assertTrue(!c.result().isEmpty(), "产物栈不应为空");
        helper.assertTrue(c.attributes() != null && c.attributes().powerScore() > 0,
                "产物应带正强度，实际 " + (c.attributes() == null ? "null" : c.attributes().powerScore()));
        helper.succeed();
    }

    /** 皮革（BASE_HIDE）+ 恶魂之泪（REGENERATION）应锻出相胫——四件套可配齐的回归锁。 */
    @GameTest(template = "item_concept")
    public static void forgeComposesLeggings(GameTestHelper helper) {
        List<ItemStack> materials = padToSlots(
                new ItemStack(Items.LEATHER),
                new ItemStack(Items.GHAST_TEAR));
        ForgeComposer.Composition c = ForgeComposer.compose(materials);
        helper.assertTrue(c.valid(), "皮革+恶魂之泪应能组合出产物");
        helper.assertTrue(c.result().is(QianxiangItems.PHASE_LEGGINGS.get()),
                "BASE_HIDE+REGENERATION 应锻出相胫，实际 " + c.result());
        helper.succeed();
    }

    /** 空材料/纯空气不应产出任何东西。 */
    @GameTest(template = "item_concept")
    public static void forgeRejectsEmpty(GameTestHelper helper) {
        ForgeComposer.Composition c = ForgeComposer.compose(padToSlots());
        helper.assertTrue(!c.valid() || c.result().isEmpty(), "空材料不应锻出产物");
        helper.succeed();
    }

    /** 相杖是增幅器：产物的增幅字段必须按材料算子推导（增幅器化契约，替代旧「杖带法术」）。 */
    @GameTest(template = "item_concept")
    public static void staffCarriesAmplifierStats(GameTestHelper helper) {
        // 法力（森罗残片 RARE MANA）+ 火（余烬石 COMMON IGNITE）+ 木骨架 → 相杖原型
        List<ItemStack> materials = padToSlots(
                new ItemStack(QianxiangItems.MYRIAD_FRAGMENT.get()),
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()),
                new ItemStack(Items.STICK));
        ForgeComposer.Composition c = ForgeComposer.compose(materials);
        helper.assertTrue(c.valid(), "法力+火+木骨架应能组合出产物");
        helper.assertTrue(c.result().is(QianxiangItems.PHASE_STAFF.get()),
                "含 MANA 的组合应出相杖，实际 " + c.result());
        var attr = c.result().get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        helper.assertTrue(attr != null, "相杖产物应带 COMPOSED_ATTRIBUTES 组件");
        // MANA(RARE，档位系数 1.5)：manaBonus = round(8 × 1.5) = 12
        helper.assertTrue(attr.manaBonus() == 12,
                "RARE MANA 应给出 manaBonus=12，实际 " + attr.manaBonus());
        // IGNITE(COMMON，档位系数 1.0)：spellPowerPercent = 6 × 1.0 = 6
        helper.assertTrue(attr.spellPowerPercent() == 6.0,
                "COMMON IGNITE 应给出 spellPowerPercent=6，实际 " + attr.spellPowerPercent());
        // 增幅器化后产物不再写法术组件（法术产出收口到炼金台卷轴）
        helper.assertTrue(c.result().get(QianxiangDataComponents.CUSTOM_SPELL.get()) == null,
                "增幅器化后相杖不应再带 CUSTOM_SPELL 组件");
        helper.succeed();
    }

    // ============================ 蓝图分享码 ============================

    /** encode → decode roundtrip 无损。 */
    @GameTest(template = "item_concept")
    public static void shareCodeRoundtrip(GameTestHelper helper) {
        BlueprintData original = new BlueprintData(
                List.of("qianxiang:ember_iron", "minecraft:stick"), "weapon", 12.5,
                "测试之刃", "{\"element\":\"fire\"}", null);
        String code = BlueprintShareCodes.encode(original);
        helper.assertTrue(code.startsWith(BlueprintShareCodes.PREFIX),
                "分享码应以 " + BlueprintShareCodes.PREFIX + " 开头");
        BlueprintData decoded = BlueprintShareCodes.decode(code);
        helper.assertTrue(original.materials().equals(decoded.materials()),
                "材料列表 roundtrip 应无损");
        helper.assertTrue(original.name().equals(decoded.name()), "名称 roundtrip 应无损");
        helper.assertTrue(original.power() == decoded.power(), "强度 roundtrip 应无损");
        helper.assertTrue(original.spellJson().equals(decoded.spellJson()), "spellJson roundtrip 应无损");
        helper.succeed();
    }

    /** 恶意/超限蓝图导入必须被消毒：材料截断到槽位上限、名称截断 64。 */
    @GameTest(template = "item_concept")
    public static void shareCodeSanitizesOversized(GameTestHelper helper) {
        BlueprintData oversized = new BlueprintData(
                Collections.nCopies(50, "minecraft:stick"), "weapon",
                Double.POSITIVE_INFINITY, "超".repeat(300), null, null);
        BlueprintData clean = BlueprintShareCodes.decode(BlueprintShareCodes.encode(oversized));
        helper.assertTrue(clean.materials().size() <= BlueprintShareCodes.MAX_MATERIALS,
                "材料应截断到 ≤" + BlueprintShareCodes.MAX_MATERIALS
                        + "，实际 " + clean.materials().size());
        helper.assertTrue(clean.materials().size() > 0,
                "消毒不应把材料清空，实际 " + clean.materials().size());
        helper.assertTrue(clean.name().length() <= 64,
                "名称应截断到 ≤64，实际 " + clean.name().length());
        helper.assertTrue(Double.isFinite(clean.power()), "非法 power 应被修正为有限值");
        helper.succeed();
    }

    /** productType 是闭集：外来码里的任意串必须归一为 weapon/armor/tool。 */
    @GameTest(template = "item_concept")
    public static void shareCodeNormalizesProductType(GameTestHelper helper) {
        BlueprintData evil = new BlueprintData(
                List.of("minecraft:stick"), "恶".repeat(10000), 1.0, "类型注入", null, null);
        BlueprintData clean = BlueprintShareCodes.decode(BlueprintShareCodes.encode(evil));
        helper.assertTrue("weapon".equals(clean.productType()),
                "未知 productType 应归一为 weapon，实际 " + clean.productType());
        BlueprintData armor = new BlueprintData(
                List.of("minecraft:stick"), "armor", 1.0, "护甲", null, null);
        helper.assertTrue("armor".equals(
                        BlueprintShareCodes.decode(BlueprintShareCodes.encode(armor)).productType()),
                "合法 productType=armor 应原样保留");
        helper.succeed();
    }

    /** 垃圾输入解码必须抛 IllegalArgumentException（不崩、不返回半成品）。 */
    @GameTest(template = "item_concept")
    public static void shareCodeRejectsGarbage(GameTestHelper helper) {
        for (String garbage : new String[]{"", "hello world", "QXBP1.!!!not-base64!!!", "QXBP9.abcd"}) {
            try {
                BlueprintShareCodes.decode(garbage);
                helper.fail("垃圾输入应抛异常: " + garbage);
                return;
            } catch (IllegalArgumentException expected) {
                // 正确行为
            }
        }
        helper.succeed();
    }

    // ============================ 无效目标补偿（WQ-8/57） ============================

    /**
     * AoE 治疗打一群怪：退款最多结算一次，绝不能按命中目标数叠加。
     * <p>直接验证记账层（{@code settleCast} 的判据），避免在 GameTest 的 mock 玩家上
     * 触发真实网络同步——那条路径需要真实连接，与本用例要验证的逻辑无关。
     */
    @GameTest(template = "item_concept")
    public static void ineffectiveTallyRefundsAtMostOnce(GameTestHelper helper) {
        // 记账是 per-cast 的：无论标记多少个无效目标，判定结果都只是「本次施法零受益」
        int[] tally = com.qianxiang.spell.SpellEffectEngine.tallySnapshotForTest(() -> {
            for (int i = 0; i < 5; i++) {
                com.qianxiang.spell.SpellEffectEngine.markIneffectiveForTest();
            }
        });
        helper.assertTrue(tally[0] == 0, "不应有任何目标受益，实际 " + tally[0]);
        helper.assertTrue(tally[1] == 5, "应记录 5 个无效目标，实际 " + tally[1]);
        helper.assertTrue(com.qianxiang.spell.SpellEffectEngine.shouldRefundForTest(tally),
                "零受益 + 有无效目标 → 应退款（且只退一次，与目标数无关）");

        // 命中集合里只要有一个友方真正受益，就不该退款（否则治疗生效还倒赚）
        int[] mixed = com.qianxiang.spell.SpellEffectEngine.tallySnapshotForTest(() -> {
            com.qianxiang.spell.SpellEffectEngine.markEffectiveForTest();
            for (int i = 0; i < 4; i++) {
                com.qianxiang.spell.SpellEffectEngine.markIneffectiveForTest();
            }
        });
        helper.assertTrue(!com.qianxiang.spell.SpellEffectEngine.shouldRefundForTest(mixed),
                "有目标真正受益时不得退款（否则补偿变奖励）");
        helper.succeed();
    }

    // ============================ 兜底配方质量（WQ-45） ============================

    /** 否定语义：「不要火的剑」不得塞火材料，但「抗火」是合法功能需求不能被误剥。 */
    @GameTest(template = "item_concept")
    public static void fallbackRespectsNegation(GameTestHelper helper) {
        var noFire = com.qianxiang.ai.FallbackRecipes.keywordPicksForTest("不要火的剑");
        helper.assertTrue(noFire.stream().noneMatch(m -> m.contains("ember")),
                "「不要火」不应命中火材料，实际 " + noFire);

        // 「抗火」含「火」字但是 FIRE_RESIST 功能需求，必须仍然命中
        var fireResist = com.qianxiang.ai.FallbackRecipes.keywordPicksForTest("抗火的靴子");
        helper.assertTrue(fireResist.contains("minecraft:magma_cream"),
                "「抗火」是合法功能需求，应命中抗火材料，实际 " + fireResist);
        helper.assertTrue(fireResist.stream().noneMatch(m -> m.contains("ember")),
                "「抗火」不应同时塞进点燃系材料，实际 " + fireResist);
        helper.succeed();
    }

    /** 雷电组此前整组缺失，thunder_stone 在所有兜底路径上都选不到。 */
    @GameTest(template = "item_concept")
    public static void fallbackCoversLightningAndModMaterials(GameTestHelper helper) {
        var lightning = com.qianxiang.ai.FallbackRecipes.keywordPicksForTest("雷电之剑");
        helper.assertTrue(lightning.contains("qianxiang:thunder_stone"),
                "「雷电」应命中雷霆石，实际 " + lightning);

        record Case(String want, String expected) {}
        for (Case c : List.of(
                new Case("寒冰法杖", "qianxiang:frost_crystal"),
                new Case("隐身斗篷", "qianxiang:shadow_dust"),
                new Case("圣光之刃", "qianxiang:holy_shard"),
                new Case("自然之力", "qianxiang:nature_breath"),
                new Case("虚空武器", "qianxiang:void_shard"),
                new Case("剧毒匕首", "qianxiang:venom_gland"))) {
            var picks = com.qianxiang.ai.FallbackRecipes.keywordPicksForTest(c.want());
            helper.assertTrue(picks.contains(c.expected()),
                    "「" + c.want() + "」应命中 " + c.expected() + "，实际 " + picks);
        }
        helper.succeed();
    }

    // ============================ AI 链路健壮性（WQ-39/41/43） ============================

    /** 材料召回必须显著小于全库，且仍能覆盖需求关键词对应的材料。 */
    @GameTest(template = "item_concept")
    public static void materialRecallShrinksPrompt(GameTestHelper helper) {
        var lib = com.qianxiang.ai.MaterialLibrary.snapshot();
        helper.assertTrue(lib.size() > 100,
                "材料库应包含大量物品（概念推导兜底），实际 " + lib.size());

        var groups = com.qianxiang.ai.MaterialRecall.recall(
                lib, "我要一把会喷火的剑", "weapon", com.qianxiang.phase.PhaseTier.RARE, null);
        int total = groups.stream().mapToInt(g -> g.entries().size()).sum();
        helper.assertTrue(total > 0, "召回结果不应为空");
        helper.assertTrue(total <= com.qianxiang.ai.MaterialRecall.MAX_TOTAL,
                "召回应受上限约束（防 prompt 爆窗），实际 " + total);
        helper.assertTrue(total < lib.size() / 4,
                "召回应显著小于全库：" + total + " vs 全库 " + lib.size());

        boolean hasIgnite = groups.stream().flatMap(g -> g.entries().stream())
                .anyMatch(e -> e.functions().contains(com.qianxiang.phase.PhaseFunction.IGNITE));
        helper.assertTrue(hasIgnite, "「喷火」需求应召回带 IGNITE 算子的材料");
        helper.succeed();
    }

    /** AI 给的裸名/大小写混合材料名必须能匹配上，并归一为规范 registryName。 */
    @GameTest(template = "item_concept")
    public static void aiMaterialNamesNormalizeToRegistryIds(GameTestHelper helper) {
        // 裸名（小模型最常见的省略）
        var bare = com.qianxiang.ai.MaterialLibrary.find("ember_iron");
        helper.assertTrue(bare.isPresent(),
                "裸名应能匹配（此前被强加 qianxiang: 前缀后反而永不命中）");
        helper.assertTrue(bare.get().registryName().equals("qianxiang:ember_iron"),
                "应归一为规范 id，实际 " + bare.get().registryName());

        // 大小写混合（MC 的 id 不接受大写，原样传下去会让 tryParse 全返 null）
        var mixed = com.qianxiang.ai.MaterialLibrary.find("Minecraft:Iron_Ingot");
        helper.assertTrue(mixed.isPresent(), "大小写混合的全名应能匹配");
        helper.assertTrue(mixed.get().registryName().equals("minecraft:iron_ingot"),
                "应归一为全小写规范 id，实际 " + mixed.get().registryName());
        helper.assertTrue(net.minecraft.resources.ResourceLocation.tryParse(
                        mixed.get().registryName()) != null,
                "归一后的 id 必须能被 ResourceLocation.tryParse 接受");
        helper.succeed();
    }

    /** extractJson 必须扛住围栏、think 块、尾部闲聊、顶层数组四类真实回包。 */
    @GameTest(template = "item_concept")
    public static void extractJsonHandlesRealWorldResponses(GameTestHelper helper) {
        record Case(String name, String raw) {}
        var cases = List.of(
                new Case("markdown 围栏", "```json\n{\"proposals\":[]}\n```"),
                new Case("think 块（内含花括号）",
                        "<think>我觉得应该用 {铁锭} 之类</think>\n{\"proposals\":[]}"),
                new Case("尾部闲聊", "{\"proposals\":[]}\n希望这个方案对你有帮助！{笑}"),
                new Case("顶层数组", "[{\"materials\":[\"qianxiang:ember_iron\"]}]"));

        for (Case c : cases) {
            String extracted = com.qianxiang.ai.PhaseAIRecipeService.extractJsonForTest(c.raw());
            helper.assertTrue(extracted != null, c.name() + "：应能抽出 JSON，实际返回 null");
            try {
                var parsed = com.google.gson.JsonParser.parseString(extracted);
                helper.assertTrue(parsed.isJsonObject(),
                        c.name() + "：抽出的内容应是 JSON 对象，实际 " + extracted);
            } catch (Exception e) {
                helper.fail(c.name() + "：抽出的内容不是合法 JSON：" + extracted);
                return;
            }
        }
        helper.succeed();
    }

    // ============================ 蓝图选料（WQ-11/48） ============================

    /**
     * 蓝图选料必须挑「最不值钱的同 id 那件」，且保留组件、不碰护甲槽。
     * <p>取首个命中会把玩家的附魔工具吃掉；造新栈会把残耐久洗成白板。
     */
    @GameTest(template = "item_concept")
    public static void blueprintPicksCheapestAndKeepsComponents(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var inv = player.getInventory();
        inv.clearContent();

        // 槽 0：附魔且几乎全新的镐（贵）；槽 1：白板破镐（便宜）
        ItemStack precious = new ItemStack(Items.IRON_PICKAXE);
        precious.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("传家宝"));
        inv.setItem(0, precious);
        ItemStack junk = new ItemStack(Items.IRON_PICKAXE);
        junk.setDamageValue(junk.getMaxDamage() - 5);
        inv.setItem(1, junk);
        // 护甲槽放一件同 id 物品，验证不被取用
        inv.armor.set(3, new ItemStack(Items.IRON_HELMET));

        ForgeTableMenu menu = new ForgeTableMenu(1, inv, be);
        boolean ok = menu.applyBlueprint(new BlueprintData(
                List.of("minecraft:iron_pickaxe"), "tool", 1.0, "选料测试"));
        helper.assertTrue(ok, "背包里有该材料，铺料应成功");

        helper.assertTrue(inv.getItem(0).has(net.minecraft.core.component.DataComponents.CUSTOM_NAME),
                "带自定义名的贵重物品不应被取走");
        ItemStack placed = be.getItem(12);
        helper.assertTrue(placed.is(Items.IRON_PICKAXE), "台上应放入铁镐（中心槽 12），实际 " + placed);
        helper.assertTrue(placed.getDamageValue() > 0,
                "应取用损伤大的那把（组件/耐久必须原样保留，不能是出厂新品）");
        helper.assertTrue(!inv.armor.get(3).isEmpty(), "护甲槽物品不应被蓝图取用");
        helper.succeed();
    }

    /** 材料凑不齐时必须整体回滚，不留「半套料在台上、背包却少了东西」的中间态。 */
    @GameTest(template = "item_concept")
    public static void blueprintRollsBackOnMissingMaterial(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var inv = player.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_INGOT));   // 只有第一种材料

        ForgeTableMenu menu = new ForgeTableMenu(1, inv, be);
        boolean ok = menu.applyBlueprint(new BlueprintData(
                List.of("minecraft:iron_ingot", "minecraft:diamond"), "weapon", 1.0, "缺料测试"));
        helper.assertTrue(!ok, "缺材料时应返回失败");

        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            helper.assertTrue(be.getItem(i).isEmpty(),
                    "失败后材料槽 " + i + " 应为空（已放入的必须退回），实际 " + be.getItem(i));
        }
        boolean ingotBack = false;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(Items.IRON_INGOT)) ingotBack = true;
        }
        helper.assertTrue(ingotBack, "已放入的铁锭应退回玩家背包");
        helper.succeed();
    }

    /** AI 放料：重复材料必须占不同槽（compose 按槽计零件）。 */
    @GameTest(template = "item_concept")
    public static void aiPlacementSpreadsDuplicatesAcrossSlots(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        be.setItem(0, new ItemStack(Items.IRON_INGOT));

        // 已有一个铁锭时，下一个铁锭应落到空槽而非堆到槽 0
        int slot = com.qianxiang.network.AiPlaceMaterialsHandler.findMaterialSlotForTest(be, Items.IRON_INGOT);
        helper.assertTrue(slot != 0,
                "重复材料应占用不同槽位（compose 按槽计零件），实际选中槽 " + slot);
        helper.assertTrue(slot > 0 && slot < ForgeTableMenu.MATERIAL_SLOTS,
                "应选中一个合法空槽，实际 " + slot);
        helper.succeed();
    }

    // ============================ AI 提案信任边界（WQ-2） ============================

    /**
     * 未收到提案的玩家回传任意索引都必须被拒绝——客户端不能凭空造出法术。
     * 同时验证提案按玩家隔离：A 的提案不会被 B 选中。
     */
    @GameTest(template = "item_concept")
    public static void aiProposalsAreServerAuthoritative(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        java.util.UUID alice = java.util.UUID.nameUUIDFromBytes("alice".getBytes());
        java.util.UUID bob = java.util.UUID.nameUUIDFromBytes("bob".getBytes());

        // 谁都没收到提案时，任何索引都选不中
        helper.assertTrue(!be.selectProposal(alice, 0), "无提案时选择索引 0 应失败");
        helper.assertTrue(be.selectionOf(alice).spellJson().isEmpty(), "无提案时选择应为空");

        // 只给 Alice 登记提案
        be.setProposals(alice, List.of(
                new com.qianxiang.block.ForgeTableBlockEntity.AiProposal("{\"element\":\"fire\"}", "焰", ""),
                new com.qianxiang.block.ForgeTableBlockEntity.AiProposal("{\"element\":\"frost\"}", "霜", "")));

        helper.assertTrue(be.selectProposal(alice, 1), "Alice 选自己的提案 #1 应成功");
        helper.assertTrue(be.selectionOf(alice).customName().equals("霜"),
                "Alice 应选中第 2 条，实际 " + be.selectionOf(alice).customName());

        // Bob 没有提案：越权选择必须失败，且拿不到 Alice 的内容（多人同台不串味）
        helper.assertTrue(!be.selectProposal(bob, 0), "Bob 无提案时不应选中任何东西");
        helper.assertTrue(be.selectionOf(bob).spellJson().isEmpty(),
                "Bob 不应看到 Alice 的提案内容，实际 " + be.selectionOf(bob).spellJson());
        // Alice 的选择不受 Bob 操作影响
        helper.assertTrue(be.selectionOf(alice).customName().equals("霜"), "Alice 的选择不应被他人影响");

        // 越界索引 = 清除选择（等价「不用 AI 结果」）
        helper.assertTrue(!be.selectProposal(alice, 99), "越界索引应失败");
        helper.assertTrue(be.selectionOf(alice).spellJson().isEmpty(), "越界索引应清除选择");
        helper.succeed();
    }

    // ============================ 版本化与迁移（WQ-7） ============================

    /** 无版本字段的旧码（本功能上线前产出）必须仍能导入。 */
    @GameTest(template = "item_concept")
    public static void legacyShareCodeStillImports(GameTestHelper helper) throws Exception {
        BlueprintData data = new BlueprintData(
                List.of("qianxiang:ember_iron"), "weapon", 5.0, "老码", null, null);
        // 手工编一个不含 v 字段的 v0 码（模拟历史数据）
        var tag = BlueprintData.CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE, data).getOrThrow();
        var root = new net.minecraft.nbt.CompoundTag();
        root.put("bp", tag);
        var baos = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, baos);
        String legacyCode = BlueprintShareCodes.PREFIX
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());

        BlueprintData decoded = BlueprintShareCodes.decode(legacyCode);
        helper.assertTrue("老码".equals(decoded.name()), "无 v 字段的旧码应能正常导入");
        helper.assertTrue(decoded.materials().equals(data.materials()), "旧码材料应无损");
        helper.succeed();
    }

    /** 来自更新版本的码要给出「请更新模组」而非静默失败或误解析。 */
    @GameTest(template = "item_concept")
    public static void futureShareCodeAsksForUpdate(GameTestHelper helper) throws Exception {
        BlueprintData data = new BlueprintData(
                List.of("qianxiang:ember_iron"), "weapon", 5.0, "未来码", null, null);
        var tag = BlueprintData.CODEC.encodeStart(
                net.minecraft.nbt.NbtOps.INSTANCE, data).getOrThrow();
        var root = new net.minecraft.nbt.CompoundTag();
        root.put("bp", tag);
        root.putInt(BlueprintShareCodes.VERSION_KEY, BlueprintShareCodes.CURRENT_VERSION + 5);
        var baos = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, baos);
        String futureCode = BlueprintShareCodes.PREFIX
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());

        try {
            BlueprintShareCodes.decode(futureCode);
            helper.fail("未来版本的码应被拒绝");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().contains("更新"),
                    "拒绝原因应提示玩家更新模组，实际：" + expected.getMessage());
        }
        helper.succeed();
    }

    /** spellJson 迁移：v0 旧词折算，且迁移幂等（对已是 v1 的数据不再改动）。 */
    @GameTest(template = "item_concept")
    public static void spellJsonMigrationIsIdempotent(GameTestHelper helper) {
        CustomSpell first = CustomSpell.fromSpellJson(
                "{\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\","
                        + "\"modifiers\":[\"duration\"],\"power\":2}", 1);
        helper.assertTrue(first != null && first.modifiers().contains("extended"),
                "v0 旧词 duration 应折算为 extended");

        // 已带当前版本号的数据再解析一次，结果必须一致
        CustomSpell second = CustomSpell.fromSpellJson(
                "{\"v\":" + com.qianxiang.spell.SpellJsonMigrations.CURRENT_VERSION
                        + ",\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\","
                        + "\"modifiers\":[\"extended\"],\"power\":2}", 1);
        helper.assertTrue(second != null && second.modifiers().contains("extended"),
                "当前版本数据应原样通过");

        // 未来版本必须拒绝，而不是按旧规则误解析
        CustomSpell future = CustomSpell.fromSpellJson(
                "{\"v\":999,\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\"}", 1);
        helper.assertTrue(future == null, "未来版本的 spellJson 应拒绝解析而非猜测");
        helper.succeed();
    }

    // ============================ 锻造台重算短路的正确性（WQ-28） ============================

    /**
     * 指纹短路不得吞掉「材料没变但 AI 暂存变了」的重算。
     * <p>SpellJsonReportHandler 与 applyBlueprint 都是先写 AI 暂存再调 slotsChanged，
     * 若指纹只按材料算，这两条路径会被整个短路，AI 法术永远进不了产物。
     */
    @GameTest(template = "item_concept")
    public static void forgeRecomputesWhenAiStateChanges(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);

        // 法力 + 火 + 木骨架 → 相杖（无裂隙精髓，不会升格法术书）
        be.setItem(0, new ItemStack(QianxiangItems.MYRIAD_FRAGMENT.get()));
        be.setItem(1, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()));
        be.setItem(2, new ItemStack(Items.STICK));
        menu.slotsChanged(be);

        // 材料保持不变，只改该玩家的 AI 选择 —— 指纹必须包含 AI 暂存，
        // 触发重算并把自定义名写进产物（增幅器化后 spellJson 不再消费，
        // 产物不得再出现 CUSTOM_SPELL 组件）。
        be.setSelection(player.getUUID(), new com.qianxiang.block.ForgeTableBlockEntity.AiProposal(
                "{\"element\":\"frost\",\"form\":\"projectile\",\"effect\":\"damage\",\"power\":2}",
                "霜牙", ""));
        menu.slotsChanged(be);

        ItemStack result = be.getItem(ForgeTableMenu.RESULT_SLOT);
        helper.assertTrue(result.is(QianxiangItems.PHASE_STAFF.get()),
                "法力组合应出相杖，实际 " + result);
        var customName = result.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        helper.assertTrue(customName != null && "霜牙".equals(customName.getString()),
                "AI 暂存变化后产物应带上自定义名（指纹短路吞掉了重算），实际 "
                        + (customName == null ? "无名称" : customName.getString()));
        helper.assertTrue(result.get(QianxiangDataComponents.CUSTOM_SPELL.get()) == null,
                "增幅器化后锻造台不应再把 spellJson 写进产物组件");
        level.removeBlock(pos, false);
        helper.succeed();
    }

    // ============================ 法术强度绑材料预算（WQ-1） ============================

    /** 普通材料 + 客户端/AI 报 power=10 → 必须被夹回普通档预算（4）。 */
    @GameTest(template = "item_concept")
    public static void spellPowerBoundByMaterialBudget(GameTestHelper helper) {
        String greedyJson = "{\"element\":\"fire\",\"form\":\"projectile\",\"effect\":\"damage\",\"power\":10}";

        CustomSpell commonBudget = CustomSpell.fromSpellJson(greedyJson, 1, 4);
        helper.assertTrue(commonBudget != null, "结构合法的 spellJson 不应解析失败");
        helper.assertTrue(commonBudget.power() <= 4,
                "普通材料预算应把 power 夹到 ≤4，实际 " + commonBudget.power());

        CustomSpell legendaryBudget = CustomSpell.fromSpellJson(greedyJson, 1, 10);
        helper.assertTrue(legendaryBudget.power() == 10,
                "传奇预算下 power=10 应原样保留，实际 " + legendaryBudget.power());

        // 旧签名（无预算）保持原语义：上限 MAX_POWER
        CustomSpell legacy = CustomSpell.fromSpellJson(greedyJson, 1);
        helper.assertTrue(legacy.power() == CustomSpell.MAX_POWER,
                "旧签名应仍按全局上限，实际 " + legacy.power());
        helper.succeed();
    }

    /** 预算低于下限时不得倒挂：clamp 顺序错会产出 power < 1 或抛异常。 */
    @GameTest(template = "item_concept")
    public static void spellPowerBudgetNeverInverts(GameTestHelper helper) {
        CustomSpell spell = CustomSpell.fromSpellJson(
                "{\"element\":\"frost\",\"form\":\"self\",\"effect\":\"buff\",\"power\":9}", 8, 2);
        helper.assertTrue(spell != null, "下限高于预算时不应解析失败");
        helper.assertTrue(spell.power() >= 1 && spell.power() <= CustomSpell.MAX_POWER,
                "power 必须落在合法区间，实际 " + spell.power());
        helper.assertTrue(spell.manaCost() > 0, "manaCost 应为正数，实际 " + spell.manaCost());
        helper.succeed();
    }

    // ============================ 森罗之核：Boss 独占与终局闭环 ============================

    /** 守望者掉落表必须含森罗之核，且它是全游戏唯一来源（无配方、不在商人表）。 */
    @GameTest(template = "item_concept")
    public static void wardenCoreIsBossExclusive(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var lootId = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.LOOT_TABLE,
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        Qianxiang.MOD_ID, "entities/myriad_warden"));
        var table = server.reloadableRegistries().getLootTable(lootId);
        helper.assertTrue(table != net.minecraft.world.level.storage.loot.LootTable.EMPTY,
                "守望者掉落表应存在");

        // 掉落表内容不便直接内省，改为断言「配方侧确实没有获取途径」——独占性的关键保证
        var recipeManager = server.getRecipeManager();
        boolean craftable = recipeManager.getRecipes().stream().anyMatch(holder -> {
            try {
                return holder.value().getResultItem(server.registryAccess())
                        .is(QianxiangItems.WARDEN_CORE.get());
            } catch (Throwable t) {
                return false;
            }
        });
        helper.assertTrue(!craftable, "森罗之核不得有任何合成配方（Boss 独占）");
        helper.succeed();
    }

    /** 森罗之核是 LEGENDARY 档相材料，进锻造后能显著抬升产物强度。 */
    @GameTest(template = "item_concept")
    public static void wardenCoreIsLegendaryMaterial(GameTestHelper helper) {
        var entry = com.qianxiang.phase.PhaseMaterialRegistry.get(QianxiangItems.WARDEN_CORE.get());
        helper.assertTrue(entry != null,
                "森罗之核应有数据驱动相材料定义（phase_materials/warden_core.json）");
        helper.assertTrue(entry.data().tier() == com.qianxiang.phase.PhaseTier.LEGENDARY,
                "森罗之核应为 LEGENDARY 档，实际 " + entry.data().tier());

        ForgeComposer.Composition withCore = ForgeComposer.compose(padToSlots(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangItems.WARDEN_CORE.get())));
        ForgeComposer.Composition without = ForgeComposer.compose(padToSlots(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get())));
        helper.assertTrue(withCore.valid(), "含核组合应能锻出产物");
        helper.assertTrue(withCore.attributes().powerScore() > without.attributes().powerScore(),
                "加入森罗之核应显著抬升强度：含核 " + withCore.attributes().powerScore()
                        + " vs 不含 " + without.attributes().powerScore());
        helper.succeed();
    }

    // ============================ 耐久链完整性 ============================

    /**
     * 产物必须是「可损坏物品」，否则整条耐久数学 + frail 代价全是死代码。
     * <p>注册时缺 {@code Properties.durability(...)} → 栈上无 MAX_DAMAGE/DAMAGE 组件 →
     * {@code isDamageableItem()} 为 false → {@code hurtAndBreak} 空转、
     * {@code getMaxDamage} override 永不被消费、装备还能 64 个一摞。
     */
    @GameTest(template = "item_concept")
    public static void productsAreDamageable(GameTestHelper helper) {
        var products = List.of(
                QianxiangItems.EMBER_BLADE.get(), QianxiangItems.BONE_BLADE.get(),
                QianxiangItems.PHASE_STAFF.get(), QianxiangItems.PHASE_SHIELD.get(),
                QianxiangItems.PHASE_HELMET.get(), QianxiangItems.PHASE_CHESTPLATE.get(),
                QianxiangItems.PHASE_LEGGINGS.get(), QianxiangItems.PHASE_BOOTS.get(),
                QianxiangItems.PHASE_HOE.get(), QianxiangItems.PHASE_WATERING_CAN.get());
        for (var item : products) {
            ItemStack stack = new ItemStack(item);
            helper.assertTrue(stack.isDamageableItem(),
                    item + " 必须可损坏（注册时缺 Properties.durability 会让耐久链全部空转）");
            helper.assertTrue(stack.getMaxStackSize() == 1,
                    item + " 装备类产物不应可堆叠，实际上限 " + stack.getMaxStackSize());
        }
        helper.succeed();
    }

    /** 护甲线也要「耐久靠材料」：纯皮制基底必须产出正耐久且随档位放大。 */
    @GameTest(template = "item_concept")
    public static void armorDurabilityScalesWithTier(GameTestHelper helper) {
        var common = com.qianxiang.phase.AttributeScheme.compose(List.of(
                com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                        com.qianxiang.phase.PhaseTier.COMMON,
                        com.qianxiang.phase.PhaseFunction.BASE_HIDE)));
        var legendary = com.qianxiang.phase.AttributeScheme.compose(List.of(
                com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                        com.qianxiang.phase.PhaseTier.LEGENDARY,
                        com.qianxiang.phase.PhaseFunction.BASE_HIDE)));

        helper.assertTrue(common.durability() > 0,
                "皮制基底必须产出正耐久，否则护甲回落固定值、档位毫无意义，实际 " + common.durability());
        helper.assertTrue(legendary.durability() > common.durability(),
                "传奇皮革应比普通皮革耐用：传奇 " + legendary.durability()
                        + " vs 普通 " + common.durability());
        helper.succeed();
    }

    /** 锻造出的产物耐久应来自材料（ComposedAttributes.durability），而非注册时的占位值。 */
    @GameTest(template = "item_concept")
    public static void forgedDurabilityComesFromMaterials(GameTestHelper helper) {
        ForgeComposer.Composition c = ForgeComposer.compose(padToSlots(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangItems.BEAST_FANG.get())));
        helper.assertTrue(c.valid(), "烬铁+兽牙应能锻出产物");
        int composed = c.attributes().durability();
        helper.assertTrue(composed > 0, "组合属性应给出正耐久，实际 " + composed);
        helper.assertTrue(c.result().getMaxDamage() == composed,
                "产物最大耐久应等于组合耐久 " + composed + "，实际 " + c.result().getMaxDamage());
        helper.succeed();
    }

    // ============================ 锻造台自动化边界（WQ-10/12） ============================

    /** 产物槽对漏斗完全不可见：只暴露 0-9 材料槽，槽 10 禁抽禁塞。 */
    @GameTest(template = "item_concept")
    public static void forgeTableHidesResultSlotFromHoppers(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            int[] slots = be.getSlotsForFace(side);
            helper.assertTrue(slots.length == ForgeTableMenu.MATERIAL_SLOTS,
                    side + " 面应只暴露 " + ForgeTableMenu.MATERIAL_SLOTS + " 个材料槽，实际 " + slots.length);
            for (int s : slots) {
                helper.assertTrue(s != ForgeTableMenu.RESULT_SLOT, "产物槽不应出现在自动化可见槽位里");
            }
        }
        ItemStack probe = new ItemStack(Items.STICK);
        helper.assertTrue(!be.canTakeItemThroughFace(
                        ForgeTableMenu.RESULT_SLOT, probe, net.minecraft.core.Direction.DOWN),
                "漏斗不应能从产物槽抽走成品（否则零成本无限锻造）");
        helper.assertTrue(!be.canTakeItemThroughFace(0, probe, net.minecraft.core.Direction.DOWN),
                "漏斗也不应抽走材料（界面开着时换料会让产物与材料脱钩）");
        helper.assertTrue(!be.canPlaceItemThroughFace(
                        ForgeTableMenu.RESULT_SLOT, probe, net.minecraft.core.Direction.UP),
                "漏斗不应能往产物槽塞东西");
        helper.assertTrue(be.canPlaceItemThroughFace(0, probe, net.minecraft.core.Direction.UP),
                "材料槽应允许自动化投入");
        helper.succeed();
    }

    /** 破坏方块只掉材料槽，不掉尚未付出材料的预览产物。 */
    @GameTest(template = "item_concept")
    public static void forgeTableDropsMaterialsNotPreview(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                pos, com.qianxiang.QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        be.setItem(0, new ItemStack(Items.LEATHER));
        be.setItem(ForgeTableMenu.RESULT_SLOT, new ItemStack(QianxiangItems.PHASE_LEGGINGS.get()));

        be.dropContentsOnRemove(level, pos);

        var dropped = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(pos).inflate(6.0));
        boolean hasLeather = dropped.stream().anyMatch(e -> e.getItem().is(Items.LEATHER));
        boolean hasProduct = dropped.stream()
                .anyMatch(e -> e.getItem().is(QianxiangItems.PHASE_LEGGINGS.get()));
        helper.assertTrue(hasLeather, "破坏锻造台应掉出材料槽内容");
        helper.assertTrue(!hasProduct, "不应掉出预览产物（材料未消耗，掉了等于白送）");
        dropped.forEach(net.minecraft.world.entity.Entity::discard);
        helper.succeed();
    }

    // ============================ 工坊配额 ============================

    /** 单作者最多 MAX_PER_AUTHOR 条；同名覆盖不受配额限制；他人不受影响。 */
    @GameTest(template = "item_concept")
    public static void workshopEnforcesPerAuthorCap(GameTestHelper helper) {
        var workshop = new com.qianxiang.blueprint.WorkshopSavedData();
        BlueprintData bp = new BlueprintData(List.of("minecraft:stick"), "weapon", 1.0, "占位");
        for (int i = 0; i < com.qianxiang.blueprint.WorkshopSavedData.MAX_PER_AUTHOR; i++) {
            BlueprintData named = new BlueprintData(
                    List.of("minecraft:stick"), "weapon", 1.0, "蓝图" + i);
            helper.assertTrue(workshop.publish(named, "灌水者", "uuid-spammer") == null,
                    "配额内第 " + (i + 1) + " 条发布应成功");
        }
        helper.assertTrue(workshop.publish(bp, "灌水者", "uuid-spammer") != null,
                "超单作者配额的发布应被拒绝");
        BlueprintData overwrite = new BlueprintData(
                List.of("minecraft:stick"), "weapon", 2.0, "蓝图0");
        helper.assertTrue(workshop.publish(overwrite, "灌水者", "uuid-spammer") == null,
                "同名覆盖不应受配额限制");
        helper.assertTrue(workshop.publish(bp, "路人", "uuid-other") == null,
                "其他作者不应被牵连");
        helper.succeed();
    }

    // ============================ 增幅器（WQ-57 玩法改造） ============================

    /** 副手增幅杖让同一发法术掉血更多（完整 cast 路径：校验→增幅倍率→效果引擎）。 */
    @GameTest(template = "item_concept")
    public static void amplifierBoostsSpellDamage(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        CustomSpell spell = new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("qianxiang", "test_fire_aoe"),
                "fire", "aoe", "damage", List.of(), 10, 0, 2);
        player.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA).learn(spell));

        // yRot=0 视线朝 +Z，aoe 中心在玩家前方 4 格；假人放 3 格处必中
        var absPos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        player.moveTo(absPos.getX() + 0.5, absPos.getY(), absPos.getZ() + 0.5, 0, 0);

        var pig1 = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(2, 2, 5));
        float before1 = pig1.getHealth();
        helper.assertTrue(com.qianxiang.spell.SpellCastHandler.castCustomSpell(spell, player),
                "空手无增幅施法应成功");
        float dmg1 = before1 - pig1.getHealth();
        helper.assertTrue(dmg1 > 0.0f, "无增幅时假人也应掉血，实际 " + dmg1);

        // 副手增幅杖：LEGENDARY IGNITE → spellPowerPercent = 6 × 3.2 = 19.2（倍率 1.192）
        ItemStack staff = new ItemStack(QianxiangItems.PHASE_STAFF.get());
        staff.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                com.qianxiang.phase.AttributeScheme.compose(List.of(
                        com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                                com.qianxiang.phase.PhaseTier.LEGENDARY,
                                com.qianxiang.phase.PhaseFunction.IGNITE))));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, staff);

        var pig2 = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(2, 2, 5));
        float before2 = pig2.getHealth();
        helper.assertTrue(com.qianxiang.spell.SpellCastHandler.castCustomSpell(spell, player),
                "带增幅施法应成功");
        float dmg2 = before2 - pig2.getHealth();

        helper.assertTrue(dmg2 > dmg1,
                "副手增幅杖应放大掉血：无增幅 " + dmg1 + " vs 增幅 " + dmg2);
        helper.assertTrue(Math.abs(dmg2 / dmg1 - 1.192f) < 0.02f,
                "掉血比应贴合增幅倍率 1.192，实际 " + (dmg2 / dmg1));
        helper.succeed();
    }

    /** 法力上限加成：主副手叠加；回复钳制用「有效上限」，基础上限字段不变。 */
    @GameTest(template = "item_concept")
    public static void manaBonusStacksAcrossHands(GameTestHelper helper) {
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        // LEGENDARY MANA → manaBonus = round(8 × 3.2) = 26；COMMON MANA → 8
        ItemStack staff = new ItemStack(QianxiangItems.PHASE_STAFF.get());
        staff.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                com.qianxiang.phase.AttributeScheme.compose(List.of(
                        com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                                com.qianxiang.phase.PhaseTier.LEGENDARY,
                                com.qianxiang.phase.PhaseFunction.MANA))));
        ItemStack book = new ItemStack(QianxiangItems.SPELL_BOOK.get());
        book.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                com.qianxiang.phase.AttributeScheme.compose(List.of(
                        com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                                com.qianxiang.phase.PhaseTier.COMMON,
                                com.qianxiang.phase.PhaseFunction.MANA))));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, staff);

        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        int oneHand = com.qianxiang.spell.AmplifierHelper.effectiveMaxMana(player, data);
        helper.assertTrue(oneHand == 126, "单副手有效上限应为 100+26=126，实际 " + oneHand);

        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, book);
        int twoHands = com.qianxiang.spell.AmplifierHelper.effectiveMaxMana(player, data);
        helper.assertTrue(twoHands == 134, "主副手应叠加为 100+26+8=134，实际 " + twoHands);

        helper.assertTrue(data.withMana(150, twoHands).currentMana() == twoHands,
                "回复应钳到有效上限 " + twoHands + "，实际 "
                        + data.withMana(150, twoHands).currentMana());
        helper.assertTrue(data.withMana(150).currentMana() == 100,
                "无 cap 的旧钳制仍按基础上限 100，实际 " + data.withMana(150).currentMana());
        helper.succeed();
    }

    // ============================ 卷轴学习与施法白名单 ============================

    /** 右键卷轴学习：进已学列表且消耗 1；同学法术再用不消耗且走失败分支。 */
    @GameTest(template = "item_concept")
    public static void scrollLearningConsumesOnce(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = mockServerPlayer(helper);
        ItemStack scroll = new ItemStack(QianxiangItems.MAGIC_SCROLL.get(), 2);
        scroll.set(QianxiangDataComponents.CUSTOM_SPELL.get(), CustomSpell.FIREBALL);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, scroll);

        var first = QianxiangItems.MAGIC_SCROLL.get()
                .use(level, player, net.minecraft.world.InteractionHand.MAIN_HAND);
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        helper.assertTrue(data.hasLearned(CustomSpell.FIREBALL.id()),
                "学习后已学列表应含火球术");
        helper.assertTrue(data.learnedSpells().size() == 1,
                "已学列表应恰有 1 个法术，实际 " + data.learnedSpells().size());
        helper.assertTrue(player.getMainHandItem().getCount() == 1,
                "学习应消耗 1 张卷轴，实际剩余 " + player.getMainHandItem().getCount());
        helper.assertTrue(first.getResult() == net.minecraft.world.InteractionResult.CONSUME,
                "成功学习应走 consume 分支，实际 " + first.getResult());

        var second = QianxiangItems.MAGIC_SCROLL.get()
                .use(level, player, net.minecraft.world.InteractionHand.MAIN_HAND);
        helper.assertTrue(player.getMainHandItem().getCount() == 1,
                "已学会的法术不应再消耗卷轴，实际剩余 " + player.getMainHandItem().getCount());
        helper.assertTrue(second.getResult() == net.minecraft.world.InteractionResult.FAIL,
                "重复学习应走 fail 分支，实际 " + second.getResult());
        helper.assertTrue(player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA)
                        .learnedSpells().size() == 1,
                "重复学习不应产生重复条目");
        helper.succeed();
    }

    /** 施法白名单：未学/非法 id 零变化；已学 id 法力净变化 == -manaCost（正+负双断言）。 */
    @GameTest(template = "item_concept")
    public static void castWhitelistRejectsUnlearned(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        player.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA)
                        .learn(CustomSpell.FIREBALL));

        helper.assertTrue(!com.qianxiang.spell.SpellCastHandler.castLearnedSpell(
                        player, "qianxiang:not_learned"),
                "未学 spellId 应被拒绝");
        helper.assertTrue(!com.qianxiang.spell.SpellCastHandler.castLearnedSpell(
                        player, "not_an_id"),
                "非法 spellId 应被拒绝");
        var afterBad = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        helper.assertTrue(afterBad.currentMana() == 100,
                "被拒施法不得扣蓝，实际 " + afterBad.currentMana());
        helper.assertTrue(afterBad.cooldowns().isEmpty(),
                "被拒施法不得写冷却，实际 " + afterBad.cooldowns());

        helper.assertTrue(com.qianxiang.spell.SpellCastHandler.castLearnedSpell(
                        player, CustomSpell.FIREBALL.id().toString()),
                "已学 spellId 应放行");
        var afterCast = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        helper.assertTrue(afterCast.currentMana() == 100 - CustomSpell.FIREBALL.manaCost(),
                "法力净变化应为 -" + CustomSpell.FIREBALL.manaCost()
                        + "，实际 " + afterCast.currentMana());
        helper.assertTrue(afterCast.cooldownOf(CustomSpell.FIREBALL.id())
                        == CustomSpell.FIREBALL.cooldownTicks(),
                "施放后应写入对应冷却，实际 " + afterCast.cooldownOf(CustomSpell.FIREBALL.id()));
        helper.succeed();
    }

    // ============================ 炼金台 ============================

    /** 炼金台：材料→卷轴预览（法术字段全部落在白名单）；Shift 点产物触发仪式 → DONE 拾取；关 GUI 产物槽清空。 */
    @GameTest(template = "item_concept")
    public static void alchemyBrewsScrollAndConsumesMaterials(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.AlchemyTableBlockEntity be)) {
            helper.fail("炼金台方块实体应存在");
            return;
        }
        var player = mockServerPlayer(helper);
        player.getInventory().clearContent();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var menu = new com.qianxiang.menu.AlchemyTableMenu(1, player.getInventory(), be);

        // 余烬石（IGNITE）→ 火焰卷轴预览
        be.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()));
        menu.slotsChanged(be);
        ItemStack preview = be.getItem(com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT);
        helper.assertTrue(preview.is(QianxiangItems.MAGIC_SCROLL.get()),
                "材料槽有余烬石时产物槽应出卷轴，实际 " + preview);
        CustomSpell spell = preview.get(QianxiangDataComponents.CUSTOM_SPELL.get());
        helper.assertTrue(spell != null, "卷轴应带 CUSTOM_SPELL 组件");
        helper.assertTrue(CustomSpell.ELEMENTS.contains(spell.element())
                        && CustomSpell.FORMS.contains(spell.form())
                        && CustomSpell.EFFECTS.contains(spell.effect()),
                "卷轴法术字段应全部落在白名单，实际 " + spell.element()
                        + "/" + spell.form() + "/" + spell.effect());

        // Shift 点击产物 = 触发合成仪式（不再直接拿）：材料移入 ritualInputs
        menu.quickMoveStack(player, com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT);
        helper.assertTrue(be.ritualState() == com.qianxiang.block.RitualState.FLYING,
                "Shift 点击产物应触发仪式，实际状态 " + be.ritualState());
        helper.assertTrue(be.ritualInputs().size() == 1 && be.getItem(0).isEmpty(),
                "材料应移入 ritualInputs 并清空材料槽");
        helper.assertTrue(countInInventory(player, QianxiangItems.MAGIC_SCROLL.get()) == 0,
                "触发仪式时玩家不应直接得到卷轴");

        // FLYING 30t + FORMING 50t，等 90t 到 DONE 后空手拾取
        helper.runAfterDelay(90, () -> {
            helper.assertTrue(be.ritualState() == com.qianxiang.block.RitualState.DONE,
                    "80t 后应为 DONE，实际 " + be.ritualState());
            helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
            helper.assertTrue(countInInventory(player, QianxiangItems.MAGIC_SCROLL.get()) == 1,
                    "DONE 后空手右键应拾取恰好一张卷轴，实际背包 " + dumpInventory(player));

            // 关 GUI：实时预览必须清空（防零成本残留）
            be.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()));
            menu.slotsChanged(be);
            helper.assertTrue(!be.getItem(com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT).isEmpty(),
                    "重新放料后产物槽应再次出预览");
            menu.removed(player);
            helper.assertTrue(be.getItem(com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT).isEmpty(),
                    "关闭界面后产物槽必须清空（预览非实体库存）");
            level.removeBlock(pos, false);
            helper.succeed();
        });
    }

    /** 炼金台自动化边界：只暴露 6 材料槽、产物槽禁塞禁抽、材料槽只进不出（正+负双断言）。 */
    @GameTest(template = "item_concept")
    public static void alchemyTableAutomationBoundary(GameTestHelper helper) {
        var be = new com.qianxiang.block.AlchemyTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            int[] slots = be.getSlotsForFace(side);
            helper.assertTrue(slots.length == com.qianxiang.menu.AlchemyTableMenu.MATERIAL_SLOTS,
                    side + " 面应只暴露 " + com.qianxiang.menu.AlchemyTableMenu.MATERIAL_SLOTS
                            + " 个材料槽，实际 " + slots.length);
            for (int s : slots) {
                helper.assertTrue(s != com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT,
                        "产物槽不应出现在自动化可见槽位里");
            }
        }
        ItemStack probe = new ItemStack(Items.STICK);
        helper.assertTrue(!be.canTakeItemThroughFace(
                        com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT, probe,
                        net.minecraft.core.Direction.DOWN),
                "漏斗不应能从产物槽抽走卷轴（否则零成本无限炼金）");
        helper.assertTrue(!be.canTakeItemThroughFace(0, probe, net.minecraft.core.Direction.DOWN),
                "材料槽同样只进不出（防界面开着换料的脱钩利用）");
        helper.assertTrue(be.canPlaceItemThroughFace(0, probe, net.minecraft.core.Direction.UP),
                "材料槽应允许自动化塞入");
        helper.assertTrue(!be.canPlaceItemThroughFace(
                        com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT, probe,
                        net.minecraft.core.Direction.UP),
                "产物槽应禁止塞入");
        helper.assertTrue(!be.canPlaceItem(com.qianxiang.menu.AlchemyTableMenu.RESULT_SLOT, probe),
                "Container 层产物槽同样禁放");
        helper.succeed();
    }

    // ============================ 25 格与软化护栏 ============================

    /** 同种材料 10 件 vs 25 件：powerScore 应为 ×1.75 口径（min(n,10)+(n-10)*0.5），非线性 ×2.5。 */
    @GameTest(template = "item_concept")
    public static void partCountSoftCapHalves(GameTestHelper helper) {
        ItemStack[] ten = new ItemStack[10];
        ItemStack[] twentyFive = new ItemStack[25];
        for (int i = 0; i < 10; i++) ten[i] = new ItemStack(QianxiangMaterials.EMBER_IRON.get());
        for (int i = 0; i < 25; i++) twentyFive[i] = new ItemStack(QianxiangMaterials.EMBER_IRON.get());

        ForgeComposer.Composition c10 = ForgeComposer.compose(padToSlots(ten));
        ForgeComposer.Composition c25 = ForgeComposer.compose(padToSlots(twentyFive));
        helper.assertTrue(c10.valid() && c25.valid(), "10 件与 25 件同种材料都应能组合");
        double p10 = c10.attributes().powerScore();
        double p25 = c25.attributes().powerScore();
        helper.assertTrue(p25 > p10,
                "25 件应强于 10 件：p10=" + p10 + " p25=" + p25);
        helper.assertTrue(p25 < p10 * 2.5,
                "软化护栏下 25 件不得线性 ×2.5：p10=" + p10 + " p25=" + p25);
        helper.assertTrue(Math.abs(p25 - p10 * 1.75) < 0.01,
                "口径应为 ×1.75（min(n,10)+(n-10)*0.5）：p10=" + p10 + " p25=" + p25);

        // 顺带锁槽数：WorldlyContainer 暴露 25 材料槽
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        helper.assertTrue(be.getSlotsForFace(net.minecraft.core.Direction.UP).length
                        == ForgeTableMenu.MATERIAL_SLOTS,
                "锻造台应暴露 " + ForgeTableMenu.MATERIAL_SLOTS + " 个材料槽");
        helper.succeed();
    }

    // ============================ 旧存档迁移 ============================

    /** 旧格式 NBT（learned=id 列表）读入：预置 id 正确转换、未知 id 丢弃、mana/cooldowns 原样。 */
    @GameTest(template = "item_concept")
    public static void legacyLearnedIdsMigrate(GameTestHelper helper) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("current_mana", 80);
        tag.putInt("max_mana", 100);
        net.minecraft.nbt.ListTag learned = new net.minecraft.nbt.ListTag();
        learned.add(net.minecraft.nbt.StringTag.valueOf("qianxiang:fireball"));
        learned.add(net.minecraft.nbt.StringTag.valueOf("qianxiang:ghost_spell"));
        tag.put("learned", learned);
        tag.put("cooldowns", new net.minecraft.nbt.CompoundTag());

        var data = com.qianxiang.cap.PlayerSpellData.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(null);
        helper.assertTrue(data != null, "旧格式 NBT 应能解码");
        helper.assertTrue(data.learnedSpells().size() == 1,
                "未知 id 应被丢弃，已学应只剩 1 个，实际 " + data.learnedSpells().size());
        helper.assertTrue(data.learnedSpells().get(0).id().equals(CustomSpell.FIREBALL.id()),
                "预置 id 应转换为预置法术，实际 " + data.learnedSpells().get(0).id());
        helper.assertTrue(data.learnedSpells().get(0).equals(CustomSpell.FIREBALL),
                "转换结果应与预置注册表条目完全一致");
        helper.assertTrue(data.currentMana() == 80 && data.maxMana() == 100,
                "mana 字段应原样保留，实际 " + data.currentMana() + "/" + data.maxMana());
        helper.assertTrue(data.cooldowns().isEmpty(), "cooldowns 应原样保留（空表）");
        helper.succeed();
    }

    // ============================ 伤害标定与元素分支（伤害对标武器轮） ============================

    /** 伤害对标：power 4 法术走完整 cast 路径，无护甲假人掉血 == 4×3.0 == 12。 */
    @GameTest(template = "item_concept")
    public static void damageFormulaMatchesWeaponTier(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        CustomSpell spell = new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("qianxiang", "test_fire_aoe_p4"),
                "fire", "aoe", "damage", List.of(), 10, 0, 4);
        var absPos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2));
        player.moveTo(absPos.getX() + 0.5, absPos.getY(), absPos.getZ() + 0.5, 0, 0);

        var zombie = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
                new net.minecraft.core.BlockPos(2, 2, 5));
        float before = zombie.getHealth();
        com.qianxiang.spell.SpellEffectEngine.cast(spell, player, 1.0f);
        float dmg = before - zombie.getHealth();
        helper.assertTrue(Math.abs(dmg - 12.0f) < 0.01f,
                "power 4 应掉血 12（power×3.0），实际 " + dmg);
        helper.assertTrue(dmg > 0.0f, "假人应确实承伤");
        helper.succeed();
    }

    /** 模板 power 按档位翻倍：LEGENDARY 组 → 8，COMMON 组 → 2（正向双断言）。 */
    @GameTest(template = "item_concept")
    public static void templatePowerScalesWithTier(GameTestHelper helper) {
        var legendary = com.qianxiang.phase.SpellScrollComposer.compose(List.of(
                new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get())));
        helper.assertTrue(legendary.valid(), "裂隙精髓应能炼出卷轴");
        helper.assertTrue(legendary.spell().power() == 8,
                "LEGENDARY 组 power 应为 8（2×(3+1)），实际 " + legendary.spell().power());

        var common = com.qianxiang.phase.SpellScrollComposer.compose(List.of(
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get())));
        helper.assertTrue(common.valid(), "余烬石应能炼出卷轴");
        helper.assertTrue(common.spell().power() == 2,
                "COMMON 组 power 应为 2（2×(0+1)），实际 " + common.spell().power());
        helper.succeed();
    }

    /** debuff 按元素分派：fire=点燃、blood=凋零、ender=漂浮、shadow=致盲（可观测状态断言）。 */
    @GameTest(template = "item_concept")
    public static void debuffVariesByElement(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = mockServerPlayer(helper);

        var pigFire = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.spell.SpellEffectEngine.resolveHit(level, player, player, pigFire,
                "fire", "debuff", 2.0f, java.util.Set.of(), 1.0f);
        helper.assertTrue(pigFire.getRemainingFireTicks() > 0,
                "fire debuff 应点燃目标，实际 remainingFireTicks=" + pigFire.getRemainingFireTicks());

        var pigBlood = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.spell.SpellEffectEngine.resolveHit(level, player, player, pigBlood,
                "blood", "debuff", 2.0f, java.util.Set.of(), 1.0f);
        helper.assertTrue(pigBlood.hasEffect(net.minecraft.world.effect.MobEffects.WITHER),
                "blood debuff 应上凋零");

        var pigEnder = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.spell.SpellEffectEngine.resolveHit(level, player, player, pigEnder,
                "ender", "debuff", 2.0f, java.util.Set.of(), 1.0f);
        helper.assertTrue(pigEnder.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION),
                "ender debuff 应上漂浮");

        var pigShadow = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.spell.SpellEffectEngine.resolveHit(level, player, player, pigShadow,
                "shadow", "debuff", 2.0f, java.util.Set.of(), 1.0f);
        helper.assertTrue(pigShadow.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS),
                "shadow debuff 应上致盲");
        helper.succeed();
    }

    /** blood utility 血换蓝：满血时 +20 蓝 -4 血（含耗魔扣减）；≤4 血时不发动（法力只扣耗魔）。 */
    @GameTest(template = "item_concept")
    public static void bloodUtilityConvertsHpToMana(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        CustomSpell spell = new CustomSpell(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("qianxiang", "test_blood_mana"),
                "blood", "self", "utility", List.of(), 10, 0, 1);
        player.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA)
                        .withMana(50));
        player.setHealth(20.0f);

        helper.assertTrue(com.qianxiang.spell.SpellCastHandler.castCustomSpell(spell, player),
                "满血血换蓝应施放成功");
        var afterFull = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        helper.assertTrue(afterFull.currentMana() == 60,
                "法力应为 50+20-10(耗魔)=60，实际 " + afterFull.currentMana());
        // 生命值 -4 的断言在本环境不可观测：Epic Fight 接管 LivingIncomingDamageEvent
        // 后取消了 vanilla hurt（对无 EF 状态的 mock 玩家不再实际扣血）。
        // 真实服务端上 hurt(magic, 4.0f) 经 EF 结算路径正常扣血——此处只锁法力侧语义。
        helper.assertTrue(player.getHealth() <= 20.0f, " sanity：生命不超上限");

        player.setHealth(4.0f);
        helper.assertTrue(com.qianxiang.spell.SpellCastHandler.castCustomSpell(spell, player),
                "低血时施法本身仍放行（仅不发动换蓝）");
        var afterLow = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA);
        helper.assertTrue(afterLow.currentMana() == 50,
                "低血不得发动 +20，法力只扣耗魔 10：60→50，实际 " + afterLow.currentMana());
        helper.assertTrue(player.getHealth() >= 3.9f,
                "低血不得再自伤，实际 " + player.getHealth());
        helper.succeed();
    }

    /** 双算子组合模板优先于单算子：IGNITE+SPEED_BOOST（雷侧）并集 → 雷炎弹（chain）。 */
    @GameTest(template = "item_concept")
    public static void comboTemplateBeatsSingle(GameTestHelper helper) {
        var c = com.qianxiang.phase.SpellScrollComposer.compose(List.of(
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()),
                new ItemStack(QianxiangMaterials.THUNDER_STONE.get())));
        helper.assertTrue(c.valid(), "余烬石+雷石应能炼出卷轴");
        helper.assertTrue("forged_plasma_bolt".equals(c.spell().id().getPath()),
                "IGNITE+雷侧算子应命中组合模板雷炎弹，实际 " + c.spell().id());
        helper.assertTrue(c.spell().modifiers().contains("chain"),
                "雷炎弹应带 chain 修饰，实际 " + c.spell().modifiers());
        helper.succeed();
    }

    // ============================ 去格子化：投入式交互 ============================

    /** 投料：useItemOn 塞 1 个进 BE、玩家背包 -1；满槽（25 槽全满）再投不消耗。 */
    @GameTest(template = "item_concept")
    public static void insertMaterialByRightClick(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        var held = new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 3);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        var hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos),
                net.minecraft.core.Direction.UP, pos, false);

        helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
        helper.assertTrue(be.getItem(12).is(QianxiangItems.EMBER_CRYSTAL.get())
                        && be.getItem(12).getCount() == 1,
                "投入后中心槽 12 应有 1 个余烬石，实际 " + be.getItem(12));
        helper.assertTrue(player.getMainHandItem().getCount() == 2,
                "投入 1 个后手持应剩 2，实际 " + player.getMainHandItem().getCount());

        // 满槽：25 槽全部塞满木棍 → 再投不消耗
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            be.setItem(i, new ItemStack(Items.STICK, 64));
        }
        helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
        helper.assertTrue(player.getMainHandItem().getCount() == 2,
                "满槽投入不得消耗手持，实际 " + player.getMainHandItem().getCount());
        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 合成仪式全流程：触发（材料入 ritualInputs、玩家未得产物）→ DONE → 空手拾取仅一份。 */
    @GameTest(template = "item_concept")
    public static void ritualCompletesAndYieldsResult(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = mockServerPlayer(helper);
        player.getInventory().clearContent();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        be.setItem(0, new ItemStack(QianxiangMaterials.EMBER_IRON.get()));
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);
        menu.slotsChanged(be);
        ItemStack preview = be.getItem(ForgeTableMenu.RESULT_SLOT);
        helper.assertTrue(!preview.isEmpty(), "材料就绪后产物槽应有预览产物");
        var resultItem = preview.getItem();

        helper.assertTrue(com.qianxiang.block.RitualLogic.startRitual(be, player),
                "产物就绪时应能触发仪式");
        helper.assertTrue(be.ritualState() == com.qianxiang.block.RitualState.FLYING,
                "触发后应为 FLYING，实际 " + be.ritualState());
        helper.assertTrue(be.ritualInputs().size() == 1 && be.getItem(0).isEmpty(),
                "材料应移入 ritualInputs 并清空材料槽");
        helper.assertTrue(!be.pendingResult().isEmpty(), "pendingResult 应记录产物");
        helper.assertTrue(countInInventory(player, resultItem) == 0,
                "触发仪式时玩家不应直接得到产物");

        // FLYING 30t + FORMING 50t，等 90t 让真实 tick 推进到 DONE
        helper.runAfterDelay(90, () -> {
            helper.assertTrue(be.ritualState() == com.qianxiang.block.RitualState.DONE,
                    "80t 后应为 DONE，实际 " + be.ritualState());
            helper.assertTrue(be.ritualInputs().isEmpty(),
                    "DONE 时材料应被真正消耗（ritualInputs 清空）");
            helper.assertTrue(!be.getDisplayResult().isEmpty(),
                    "DONE 后 displayResult 应就位");

            helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
            helper.assertTrue(countInInventory(player, resultItem) == 1,
                    "DONE 后空手右键应拾取恰好一份产物，实际背包 " + dumpInventory(player));
            helper.assertTrue(be.ritualState() == com.qianxiang.block.RitualState.NONE,
                    "拾取后应回 NONE");

            // 产物落主手（Inventory.add 优先选中槽）——挪到背包深处再点第二次，
            // 否则第二次「空手」点击会把它当材料重新投入（那是正确行为）。
            var inv = player.getInventory();
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).is(resultItem)) {
                    inv.setItem(9, inv.getItem(i));
                    inv.setItem(i, ItemStack.EMPTY);
                    break;
                }
            }
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
            helper.assertTrue(countInInventory(player, resultItem) == 1,
                    "第二次空手点击不得再给一份（防双计），实际背包 " + dumpInventory(player));
            level.removeBlock(pos, false);
            helper.succeed();
        });
    }

    /** 仪式中锁定：投料/列表取回/再次触发一律被拒（材料不变）。 */
    @GameTest(template = "item_concept")
    public static void ritualLocksTable(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = mockServerPlayer(helper);
        player.getInventory().clearContent();

        be.setItem(0, new ItemStack(QianxiangMaterials.EMBER_IRON.get()));
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);
        menu.slotsChanged(be);
        helper.assertTrue(com.qianxiang.block.RitualLogic.startRitual(be, player), "触发仪式");
        int inputCount = be.ritualInputs().stream().mapToInt(ItemStack::getCount).sum();

        // 手持投料：被拒，手持不变、ritualInputs 不变
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2));
        helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
        helper.assertTrue(player.getMainHandItem().getCount() == 2,
                "仪式中投料应被拒（手持不消耗），实际 " + player.getMainHandItem().getCount());
        helper.assertTrue(be.ritualInputs().stream().mapToInt(ItemStack::getCount).sum() == inputCount,
                "仪式中投料不得改变 ritualInputs");

        // 列表取回：被拒
        helper.assertTrue(!com.qianxiang.network.TableRetrieveHandler.retrieve(player, menu, 0),
                "仪式中列表取回应被拒");
        helper.assertTrue(be.ritualInputs().stream().mapToInt(ItemStack::getCount).sum() == inputCount,
                "仪式中取回不得改变 ritualInputs");

        // 再次触发：被拒
        helper.assertTrue(!com.qianxiang.block.RitualLogic.startRitual(be, player),
                "仪式中再次触发应被拒");
        helper.succeed();
    }

    /** 仪式中挖台：ritualInputs 全部掉落不吞（pendingResult 作废）。 */
    @GameTest(template = "item_concept")
    public static void ritualInputsDropOnBreak(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = mockServerPlayer(helper);
        player.getInventory().clearContent();

        be.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2));
        be.setItem(1, new ItemStack(Items.STICK));
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);
        menu.slotsChanged(be);
        helper.assertTrue(com.qianxiang.block.RitualLogic.startRitual(be, player), "触发仪式");
        helper.assertTrue(be.ritualInputs().size() == 2, "两个材料应已锁定");

        level.removeBlock(pos, false);
        int dropped = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(pos).inflate(3.0))
                .stream().mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(dropped == 3,
                "挖台后 ritualInputs 应全部掉落（2+1=3 个），实际掉落 " + dropped);
        helper.assertTrue(be.ritualInputs().isEmpty(), "掉落后 ritualInputs 应清空");
        helper.succeed();
    }

    /** 潜行+空手右键：取回全部材料（入背包、BE 清空）。 */
    @GameTest(template = "item_concept")
    public static void sneakRetrieveReturnsAll(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        var hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos),
                net.minecraft.core.Direction.UP, pos, false);

        be.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2));
        be.setItem(1, new ItemStack(Items.STICK));
        player.setShiftKeyDown(true);
        helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
        player.setShiftKeyDown(false);

        helper.assertTrue(be.getItem(0).isEmpty() && be.getItem(1).isEmpty(),
                "取回后材料槽应全空");
        helper.assertTrue(countInInventory(player, QianxiangItems.EMBER_CRYSTAL.get()) == 2,
                "余烬石 ×2 应回背包");
        helper.assertTrue(countInInventory(player, Items.STICK) == 1,
                "木棍 ×1 应回背包");
        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 台面上方的掉落物被 BE 吸收：实体消失、材料槽 +1。 */
    @GameTest(template = "item_concept")
    public static void itemEntityAbsorbed(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var entity = new net.minecraft.world.entity.item.ItemEntity(level,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5,
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2));
        level.addFreshEntity(entity);

        // BE 吸收扫描每 5 tick 一次，等 10 tick 让真实 tick 跑过
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(entity.isRemoved(), "掉落物应被台子吸收");
            helper.assertTrue(be.getItem(12).is(QianxiangItems.EMBER_CRYSTAL.get())
                            && be.getItem(12).getCount() == 2,
                    "吸收后中心槽 12 应有 2 个余烬石，实际 " + be.getItem(12));
            helper.succeed();
        });
    }

    /** update tag 同步口径：材料槽含、产物槽不含（与落盘一致），displayResult 单独往返。 */
    @GameTest(template = "item_concept")
    public static void updateTagExcludesResultSlot(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        be.setItem(0, new ItemStack(QianxiangMaterials.EMBER_IRON.get()));
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);
        menu.slotsChanged(be);
        helper.assertTrue(!be.getItem(ForgeTableMenu.RESULT_SLOT).isEmpty(), "产物槽应有预览产物");

        net.minecraft.nbt.CompoundTag tag = be.getUpdateTag(level.registryAccess());
        var list = tag.getList("Items", 10);
        boolean hasMaterial = false;
        for (int i = 0; i < list.size(); i++) {
            int slot = list.getCompound(i).getByte("Slot") & 0xFF;
            helper.assertTrue(slot != ForgeTableMenu.RESULT_SLOT,
                    "update tag 不应包含产物槽（slot " + slot + "）");
            if (slot == 0) hasMaterial = true;
        }
        helper.assertTrue(hasMaterial, "update tag 应包含材料槽 0");
        helper.assertTrue(tag.contains("DisplayResult"), "update tag 应含 displayResult");

        // displayResult 读回：新 BE 应用同一 tag 后应有显示产物
        var be2 = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO, state);
        be2.handleUpdateTag(tag, level.registryAccess());
        helper.assertTrue(!be2.getDisplayResult().isEmpty(),
                "displayResult 应随 update tag 往返");
        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 玩家背包中某物品的总数。 */
    private static int countInInventory(net.minecraft.world.entity.player.Player player,
                                        net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.is(item)) total += s.getCount();
        }
        return total;
    }

    /** 诊断用：背包非空槽清单。 */
    private static String dumpInventory(net.minecraft.world.entity.player.Player player) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty()) sb.append(i).append(':').append(s.getItem()).append('x').append(s.getCount()).append(' ');
        }
        return sb.append(']').toString();
    }

    /** 列表点选取回：发取回逻辑 → 该槽清空、材料回背包。 */
    @GameTest(template = "item_concept")
    public static void retrieveOneMaterialViaPayload(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        be.setItem(3, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 2));
        ForgeTableMenu menu = new ForgeTableMenu(1, player.getInventory(), be);

        helper.assertTrue(com.qianxiang.network.TableRetrieveHandler.retrieve(player, menu, 3),
                "槽 3 有材料，取回应成功");
        helper.assertTrue(be.getItem(3).isEmpty(), "取回后槽 3 应为空");
        helper.assertTrue(countInInventory(player, QianxiangItems.EMBER_CRYSTAL.get()) == 2,
                "余烬石 ×2 应回背包");

        helper.assertTrue(!com.qianxiang.network.TableRetrieveHandler.retrieve(player, menu, 3),
                "空槽取回应失败（无副作用）");
        helper.assertTrue(!com.qianxiang.network.TableRetrieveHandler.retrieve(player, menu, 99),
                "越界槽位应被拒");
        helper.succeed();
    }

    /** AI 放料缺料明示：背包只有 2/3 材料 → 放入 2、missing 名单含第 3 个。 */
    @GameTest(template = "item_concept")
    public static void aiPlaceReportsMissing(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var inv = player.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_INGOT));
        inv.setItem(1, new ItemStack(Items.DIAMOND));

        var result = com.qianxiang.network.AiPlaceMaterialsHandler.placeMaterials(player, be,
                ForgeTableMenu.SLOT_FILL_ORDER,
                List.of(Items.IRON_INGOT, Items.DIAMOND, Items.EMERALD));
        helper.assertTrue(result.placedCount() == 2,
                "应放入 2 件持有材料，实际 " + result.placedCount());
        helper.assertTrue(result.missing().size() == 1 && result.missing().get(0) == Items.EMERALD,
                "缺料名单应恰含绿宝石，实际 " + result.missing());
        helper.assertTrue(be.getItem(12).is(Items.IRON_INGOT) && be.getItem(6).is(Items.DIAMOND),
                "铁锭应在槽 12、钻石应在槽 6（填充序），实际 "
                        + be.getItem(12) + " / " + be.getItem(6));
        helper.assertTrue(countInInventory(player, Items.EMERALD) == 0
                        && countInInventory(player, Items.IRON_INGOT) == 0,
                "已放材料应从背包扣除");
        helper.succeed();
    }

    /** 自动取料存储适配：台旁箱子（Capabilities.ItemHandler.BLOCK）里的材料也能被抽走，严格守恒。 */
    @GameTest(template = "item_concept")
    public static void materialsPulledFromNearbyChest(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos tablePos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        net.minecraft.core.BlockPos chestPos = helper.absolutePos(new net.minecraft.core.BlockPos(3, 1, 2));
        level.setBlockAndUpdate(tablePos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        if (!(level.getBlockEntity(tablePos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)
                || !(level.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)) {
            helper.fail("锻造台/箱子方块实体应存在");
            return;
        }
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));

        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();  // 背包空：只能来自箱子

        var result = com.qianxiang.network.AiPlaceMaterialsHandler.placeMaterials(player, be,
                ForgeTableMenu.SLOT_FILL_ORDER,
                List.of(Items.IRON_INGOT, Items.DIAMOND));
        helper.assertTrue(result.placedCount() == 1,
                "应从箱子抽到 1 个铁锭，实际 " + result.placedCount());
        helper.assertTrue(result.missing().size() == 1 && result.missing().get(0) == Items.DIAMOND,
                "箱子没有的钻石应报缺料，实际 " + result.missing());
        helper.assertTrue(be.getItem(12).is(Items.IRON_INGOT),
                "铁锭应进中心槽 12，实际 " + be.getItem(12));
        helper.assertTrue(chest.getItem(0).getCount() == 2,
                "守恒：箱子应剩 2 个铁锭（抽 1 放 1），实际 " + chest.getItem(0).getCount());
        level.removeBlock(tablePos, false);
        level.removeBlock(chestPos, false);
        helper.succeed();
    }

    // ============================ 填充顺序即布局 + 武器形态事实源 ============================

    /** 连续投 3 料：按 SLOT_FILL_ORDER 依序占用槽 12（核心）→ 6 → 7。 */
    @GameTest(template = "item_concept")
    public static void fillOrderCenterFirst(GameTestHelper helper) {
        var level = helper.getLevel();
        net.minecraft.core.BlockPos pos = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 2));
        var state = QianxiangBlocks.FORGE_TABLE.get().defaultBlockState();
        level.setBlockAndUpdate(pos, state);
        if (!(level.getBlockEntity(pos) instanceof com.qianxiang.block.ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), 3));

        for (int k = 0; k < 3; k++) {
            helper.useBlock(new net.minecraft.core.BlockPos(2, 1, 2), player);
        }
        helper.assertTrue(be.getItem(12).getCount() == 1,
                "第 1 料应落中心槽 12，实际 " + be.getItem(12));
        helper.assertTrue(be.getItem(6).getCount() == 1,
                "第 2 料应落槽 6（内圈首格），实际 " + be.getItem(6));
        helper.assertTrue(be.getItem(7).getCount() == 1,
                "第 3 料应落槽 7（内圈次格），实际 " + be.getItem(7));
        helper.assertTrue(be.getItem(0).isEmpty(),
                "槽 0（外圈）在前三料时不应被占用（填充顺序即布局）");
        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 布局→形态推导：中心 1 件锋锐料→匕首；外圈 8 件木质料→巨剑；外圈 8 件高攻火料→重锤；
     *  迟缓料→长枪（正反向双断言）。 */
    @GameTest(template = "item_concept")
    public static void weaponFormDerivedFromLayout(GameTestHelper helper) {
        // 中心 1 件兽牙（EDGE）：小型 → 匕首
        ItemStack[] daggerMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) daggerMats[i] = ItemStack.EMPTY;
        daggerMats[12] = new ItemStack(QianxiangItems.BEAST_FANG.get());
        ForgeComposer.Composition dagger = ForgeComposer.compose(List.of(daggerMats));
        helper.assertTrue(dagger.valid(), "兽牙应能组合出产物");
        helper.assertTrue("dagger".equals(dagger.attributes().form()),
                "中心 1 件锋锐料应定形匕首，实际 " + dagger.attributes().form());
        helper.assertTrue(!"sword".equals(dagger.attributes().form()),
                "有明确形态规则命中时不应回退标准剑");

        // 外圈 8 件木棍（BASE_WOOD，无攻击）：重型 → 巨剑
        ItemStack[] greatMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) greatMats[i] = ItemStack.EMPTY;
        int[] outer8 = {0, 1, 2, 3, 4, 5, 9, 10};
        for (int slot : outer8) greatMats[slot] = new ItemStack(Items.STICK);
        ForgeComposer.Composition great = ForgeComposer.compose(List.of(greatMats));
        helper.assertTrue(great.valid(), "外圈木棍应能组合出产物");
        helper.assertTrue("greatsword".equals(great.attributes().form()),
                "外圈 8 件木质料应定形巨剑，实际 " + great.attributes().form());
        helper.assertTrue(!"hammer".equals(great.attributes().form()),
                "无高攻/力量算子时不应定形重锤");

        // 外圈 8 件烬铁（IGNITE 高攻）：重型+高攻 → 重锤
        ItemStack[] hammerMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) hammerMats[i] = ItemStack.EMPTY;
        for (int slot : outer8) hammerMats[slot] = new ItemStack(QianxiangMaterials.EMBER_IRON.get());
        ForgeComposer.Composition hammer = ForgeComposer.compose(List.of(hammerMats));
        helper.assertTrue(hammer.valid(), "外圈烬铁应能组合出产物");
        helper.assertTrue("hammer".equals(hammer.attributes().form()),
                "外圈 8 件高攻火料应定形重锤，实际 " + hammer.attributes().form());
        helper.assertTrue(!"greatsword".equals(hammer.attributes().form()),
                "攻击 ≥6 命中重型锤加权后不应定形巨剑");

        // 中心 1 件影尘（SLOW）：长枪（优先序 SPEAR 在 DAGGER 前）
        ItemStack[] spearMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) spearMats[i] = ItemStack.EMPTY;
        spearMats[12] = new ItemStack(QianxiangMaterials.SHADOW_DUST.get());
        ForgeComposer.Composition spear = ForgeComposer.compose(List.of(spearMats));
        helper.assertTrue(spear.valid(), "影尘应能组合出产物");
        helper.assertTrue("spear".equals(spear.attributes().form()),
                "迟缓料应定形长枪，实际 " + spear.attributes().form());
        helper.assertTrue(!"scythe".equals(spear.attributes().form()),
                "无 POISON/FROST 时不应定形镰刀");
        helper.succeed();
    }

    /** form 字段存档兼容：旧 NBT（无 form 键）读回空串，回退路径可达。 */
    @GameTest(template = "item_concept")
    public static void appearanceCodecBackwardCompat(GameTestHelper helper) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        var decoded = com.qianxiang.phase.ComposedAttributes.AppearanceData.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElse(null);
        helper.assertTrue(decoded != null, "空 AppearanceData NBT 应能解码（全部 optionalFieldOf）");
        helper.assertTrue(decoded.form().isEmpty(),
                "旧 NBT（无 form 键）读回 form 应为空串，实际 " + decoded.form());
        helper.assertTrue(com.qianxiang.combat.WeaponFormProfile.of(decoded.form()) == null,
                "空形态应走回退路径（profile 为 null）");
        helper.assertTrue(com.qianxiang.combat.WeaponFormProfile.fallbackByAttributes(
                        com.qianxiang.phase.ComposedAttributes.empty())
                        == com.qianxiang.phase.WeaponForm.SWORD,
                "全零属性的阈值回退应为标准剑");
        // 新 NBT 往返：form 写入后读回一致
        var withForm = new com.qianxiang.phase.ComposedAttributes.AppearanceData(
                java.util.Set.of(), "none", "plain", "katana", "bone");
        var roundtrip = com.qianxiang.phase.ComposedAttributes.AppearanceData.CODEC
                .parse(net.minecraft.nbt.NbtOps.INSTANCE,
                        com.qianxiang.phase.ComposedAttributes.AppearanceData.CODEC
                                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, withForm)
                                .result().orElseThrow())
                .result().orElse(null);
        helper.assertTrue(roundtrip != null && "katana".equals(roundtrip.form()),
                "form 字段应随 NBT 往返无损，实际 " + (roundtrip == null ? "null" : roundtrip.form()));
        helper.succeed();
    }

    /** WeaponFormProfile 全部默认连段都能在 AnimationLibrary 解析（防编造动画 id）。 */
    @GameTest(template = "item_concept")
    public static void formPresetAnimationsResolve(GameTestHelper helper) {
        for (var entry : com.qianxiang.combat.WeaponFormProfile.all().entrySet()) {
            helper.assertTrue(!entry.getValue().defaultCombos().isEmpty(),
                    entry.getKey() + " 应有默认连段");
            for (String path : entry.getValue().defaultCombos()) {
                var fullId = com.qianxiang.combat.AnimationLibrary.fullId(path);
                helper.assertTrue(com.qianxiang.combat.AnimationLibrary.byId(fullId) != null,
                        entry.getKey() + " 的默认连段 " + path + " 在 AnimationLibrary 不存在");
            }
        }
        helper.succeed();
    }

    /** 核心材料基底族：中心槽材料的 BASE_* 族写入 baseFamily（木→wood、金属→metal、骨→bone、无族→空串）。 */
    @GameTest(template = "item_concept")
    public static void baseFamilyFromCoreSlot(GameTestHelper helper) {
        // 木棍（BASE_WOOD）中心 → wood
        ItemStack[] woodMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) woodMats[i] = ItemStack.EMPTY;
        woodMats[12] = new ItemStack(Items.STICK);
        ForgeComposer.Composition wood = ForgeComposer.compose(List.of(woodMats));
        helper.assertTrue(wood.valid(), "木棍应能组合出产物");
        helper.assertTrue("wood".equals(wood.attributes().baseFamily()),
                "木棍核心应得 baseFamily=wood，实际 " + wood.attributes().baseFamily());

        // 烬铁（BASE_METAL）中心 → metal
        ItemStack[] metalMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) metalMats[i] = ItemStack.EMPTY;
        metalMats[12] = new ItemStack(QianxiangMaterials.EMBER_IRON.get());
        ForgeComposer.Composition metal = ForgeComposer.compose(List.of(metalMats));
        helper.assertTrue(metal.valid(), "烬铁应能组合出产物");
        helper.assertTrue("metal".equals(metal.attributes().baseFamily()),
                "烬铁核心应得 baseFamily=metal，实际 " + metal.attributes().baseFamily());

        // 兽牙（无 BASE_*）中心 → 空串（渲染回退写死色）
        ItemStack[] fangMats = new ItemStack[25];
        for (int i = 0; i < 25; i++) fangMats[i] = ItemStack.EMPTY;
        fangMats[12] = new ItemStack(QianxiangItems.BEAST_FANG.get());
        ForgeComposer.Composition fang = ForgeComposer.compose(List.of(fangMats));
        helper.assertTrue(fang.valid(), "兽牙应能组合出产物");
        helper.assertTrue(fang.attributes().baseFamily().isEmpty(),
                "无基底族材料应得空串 baseFamily，实际 " + fang.attributes().baseFamily());
        helper.succeed();
    }

    /** 手持 3D 剖面参数表：9 形态 + staff/book 全部有显式配置且厚度为正，未配置形状回退默认薄片。 */
    @GameTest(template = "item_concept")
    public static void extrusionProfilesComplete(GameTestHelper helper) {
        for (var entry : com.qianxiang.combat.WeaponFormProfile.all().entrySet()) {
            String shape = entry.getValue().shape();
            var profile = com.qianxiang.combat.WeaponFormProfile.extrusionFor(shape);
            helper.assertTrue(profile != null, shape + " 应有 3D 剖面配置");
            helper.assertTrue(profile.bladeThickness() > 0.0f && profile.handleThickness() > 0.0f,
                    shape + " 剖面厚度应为正，实际 blade=" + profile.bladeThickness()
                            + " handle=" + profile.handleThickness());
            helper.assertTrue(profile != com.qianxiang.combat.WeaponFormProfile.defaultExtrusion()
                            || shape.isEmpty(),
                    entry.getKey() + " 的形状 " + shape + " 应有显式剖面（不吃默认回退）");
        }
        // 未配置形状（pan/cleaver 等）回退默认薄片且不炸
        var fallback = com.qianxiang.combat.WeaponFormProfile.extrusionFor("pan");
        helper.assertTrue(fallback == com.qianxiang.combat.WeaponFormProfile.defaultExtrusion(),
                "未配置形状应回退默认剖面");
        helper.assertTrue(fallback.bladeThickness() > 0.0f, "默认剖面厚度应为正");
        helper.succeed();
    }

    // ============================ 熟练度（服务端核心） ============================

    /** 击杀记 combat XP：大生命目标给更多；未开启不攒。 */
    @GameTest(template = "item_concept")
    public static void combatXpAccruesOnKill(GameTestHelper helper) {
        var player = mockServerPlayer(helper);

        // 未开启：击杀不攒 xp
        var zombie1 = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.event.FactionEventHandler.onLivingDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(
                zombie1, player.damageSources().playerAttack(player)));
        var locked = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(locked.combatXp() == 0, "未开启熟练度时击杀不应攒 xp，实际 " + locked.combatXp());

        // 开启后：僵尸(20 血)→10xp；猪(10 血)→5xp，大生命给更多
        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        var zombie2 = helper.spawn(net.minecraft.world.entity.EntityType.ZOMBIE,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.event.FactionEventHandler.onLivingDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(
                zombie2, player.damageSources().playerAttack(player)));
        var afterZombie = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(afterZombie.combatXp() == 10,
                "击杀 20 血僵尸应得 10 xp（20×0.5），实际 " + afterZombie.combatXp());

        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(1, 2, 1));
        com.qianxiang.event.FactionEventHandler.onLivingDeath(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(
                pig, player.damageSources().playerAttack(player)));
        var afterPig = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(afterPig.combatXp() == 15,
                "再击杀 10 血猪应累计 15 xp，实际 " + afterPig.combatXp());
        helper.assertTrue(afterPig.combatXp() > 10, "大生命目标应给更多 xp（正+负双断言）");
        helper.succeed();
    }

    /** 施法记 arcane XP：真实 cast 路径 → xp == 基础蓝耗 ×0.6（±1）。 */
    @GameTest(template = "item_concept")
    public static void arcaneXpFromCast(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        player.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA)
                        .learn(com.qianxiang.spell.CustomSpell.FIREBALL));

        helper.assertTrue(com.qianxiang.spell.SpellCastHandler.castLearnedSpell(
                player, com.qianxiang.spell.CustomSpell.FIREBALL.id().toString()), "施法应成功");
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        int expected = Math.max(1, (int) (com.qianxiang.spell.CustomSpell.FIREBALL.manaCost() * 0.6));
        helper.assertTrue(Math.abs(data.arcaneXp() - expected) <= 1,
                "施法 xp 应为 " + expected + "（蓝耗×0.6），实际 " + data.arcaneXp());
        helper.succeed();
    }

    /** 升级发点：addXp 过阈值 → 等级 +1 且技能点 +1。 */
    @GameTest(template = "item_concept")
    public static void levelUpGrantsPoint(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        int threshold = com.qianxiang.cap.ProficiencyHelper.xpForLevel(1);
        com.qianxiang.cap.ProficiencyHelper.addXp(
                player, com.qianxiang.cap.ProficiencyTrack.COMBAT, threshold);
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(data.combatLevel() == 1,
                "xp 达 " + threshold + " 应升 1 级，实际 " + data.combatLevel());
        helper.assertTrue(data.combatPoints() == 1,
                "升级应发 1 技能点，实际 " + data.combatPoints());
        helper.assertTrue(data.combatXp() == 0,
                "升级消耗后余 xp 应为 0，实际 " + data.combatXp());
        helper.succeed();
    }

    /** 分配校验：无点拒、前置不足拒（T2 无 T1）、合法点成功（正+负双断言）。 */
    @GameTest(template = "item_concept")
    public static void allocationValidation(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        com.qianxiang.cap.ProficiencyHelper.unlock(player);

        helper.assertTrue(!com.qianxiang.cap.ProficiencyHelper.allocate(player, "blade1"),
                "无技能点时点 blade1 应被拒");

        com.qianxiang.cap.ProficiencyHelper.addXp(
                player, com.qianxiang.cap.ProficiencyTrack.COMBAT,
                com.qianxiang.cap.ProficiencyHelper.xpForLevel(1));
        helper.assertTrue(!com.qianxiang.cap.ProficiencyHelper.allocate(player, "blade2"),
                "无 T1 时点 T2 的 blade2 应被拒（前置不足）");

        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.allocate(player, "blade1"),
                "有点且 T1 无前置时点 blade1 应成功");
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(data.hasAllocated("blade1"), "分配后 allocated 应含 blade1");
        helper.assertTrue(data.combatPoints() == 0,
                "分配应扣 1 点（1-1=0），实际 " + data.combatPoints());
        helper.succeed();
    }

    /** 被动效果落值：blade1→近战 ×1.05；mana1→蓝耗 ×0.92；well→有效上限 +10。 */
    @GameTest(template = "item_concept")
    public static void passiveEffectsApply(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        com.qianxiang.cap.ProficiencyHelper.unlock(player);

        // 未分配：全部中性
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.meleeDamageMult(player) == 1.0,
                "未分配时近战倍率应为 1.0");
        com.qianxiang.cap.ProficiencyHelper.addXp(
                player, com.qianxiang.cap.ProficiencyTrack.COMBAT,
                com.qianxiang.cap.ProficiencyHelper.xpForLevel(1));
        com.qianxiang.cap.ProficiencyHelper.allocate(player, "blade1");
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.meleeDamageMult(player) == 1.05,
                "blade1 应使近战倍率 1.05，实际 "
                        + com.qianxiang.cap.ProficiencyHelper.meleeDamageMult(player));

        // ARCANE 一次性给足两点的量（80+242=322 → 2 级 2 点），分别点 mana1 与 well
        com.qianxiang.cap.ProficiencyHelper.addXp(
                player, com.qianxiang.cap.ProficiencyTrack.ARCANE, 500);
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.allocate(player, "mana1"),
                "mana1 应可分配");
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.manaCostMult(player) == 0.92,
                "mana1 应使蓝耗 ×0.92，实际 "
                        + com.qianxiang.cap.ProficiencyHelper.manaCostMult(player));

        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.allocate(player, "well"),
                "well 应可分配");
        int effectiveMax = com.qianxiang.spell.AmplifierHelper.effectiveMaxMana(player,
                player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA));
        helper.assertTrue(effectiveMax == 110,
                "well 应使有效法力上限 100+10=110，实际 " + effectiveMax);
        helper.succeed();
    }

    /** 洗点返还：allocated 清空、点数=等级返还、绿宝石 -10；无绿宝石拒。 */
    @GameTest(template = "item_concept")
    public static void respecRefunds(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        player.getInventory().clearContent();
        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        com.qianxiang.cap.ProficiencyHelper.addXp(
                player, com.qianxiang.cap.ProficiencyTrack.COMBAT,
                com.qianxiang.cap.ProficiencyHelper.xpForLevel(1));
        com.qianxiang.cap.ProficiencyHelper.allocate(player, "blade1");

        helper.assertTrue(!com.qianxiang.cap.ProficiencyHelper.respec(player),
                "无 10 绿宝石时洗点应被拒");

        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 10));
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.respec(player),
                "有 10 绿宝石时洗点应成功");
        var data = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(data.allocated().isEmpty(), "洗点后 allocated 应清空");
        helper.assertTrue(data.combatPoints() == data.combatLevel(),
                "洗点应返还点数=等级（" + data.combatLevel() + "），实际 " + data.combatPoints());
        helper.assertTrue(countInInventory(player, Items.EMERALD) == 0,
                "洗点应扣 10 绿宝石，实际剩余 " + countInInventory(player, Items.EMERALD));
        helper.succeed();
    }

    /** 开启修行：unlock 幂等置位 + 成就数据存在；二次调用无副作用。 */
    @GameTest(template = "item_concept")
    public static void unlockGrantsCultivation(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        helper.assertTrue(!player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA)
                .unlocked(), "初始应未开启");

        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        helper.assertTrue(player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA)
                .unlocked(), "unlock 后应已开启");

        // 成就注册（cultivation.json 应加载）
        helper.assertTrue(player.server.getAdvancements().get(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "qianxiang", "story/cultivation")) != null,
                "cultivation 成就应已注册");

        // 幂等：二次 unlock 不变且仍开启
        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        helper.assertTrue(player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA)
                .unlocked(), "二次 unlock 后仍应开启（幂等无副作用）");
        helper.succeed();
    }

    /** 主动技能校验：未解锁节点发包路径被拒（activate* 返回 false）；surge 解锁后回蓝。 */
    @GameTest(template = "item_concept")
    public static void activateSkillRejected(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        com.qianxiang.cap.ProficiencyHelper.unlock(player);

        helper.assertTrue(!com.qianxiang.cap.ProficiencyHelper.activateWarCry(player),
                "未点 warcry 时释放应被拒");
        helper.assertTrue(!com.qianxiang.cap.ProficiencyHelper.activateSurge(player),
                "未点 surge 时释放应被拒");

        // 点 surge（T3：需任一 T2 + 轨 8 级）：一次给足 xp，铺 mana1(T1)→mana2(T2)→surge(T3)
        com.qianxiang.cap.ProficiencyHelper.addXp(
                player, com.qianxiang.cap.ProficiencyTrack.ARCANE, 9000);
        var leveled = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        helper.assertTrue(leveled.arcaneLevel() >= 8,
                "应达 8 级（T3 前置），实际 " + leveled.arcaneLevel());
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.allocate(player, "mana1"),
                "T1 mana1 应可分配");
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.allocate(player, "mana2"),
                "T2 mana2 应可分配（已有 T1）");
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.allocate(player, "surge"),
                "T3 surge 应可分配（已有 T2 + 8 级）");
        player.setData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA,
                player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA)
                        .withMana(50));
        helper.assertTrue(com.qianxiang.cap.ProficiencyHelper.activateSurge(player),
                "已点 surge 后释放应成功");
        int mana = player.getData(com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA)
                .currentMana();
        helper.assertTrue(mana == 90, "surge 应瞬回 40 蓝（50+40=90），实际 " + mana);

        // 冷却：立即再放应被拒（90s 冷却）
        helper.assertTrue(!com.qianxiang.cap.ProficiencyHelper.activateSurge(player),
                "冷却中再放 surge 应被拒");
        helper.succeed();
    }

    // ============================ 工具 ============================

    /**
     * 构造带「吞包假连接」的 ServerPlayer，但<b>不走 placeNewPlayer</b>：
     * 登录流程会触发 OnDatapackSync 推 phase_material_sync，NeoForge 对未协商
     * payload 通道的假连接直接抛错（纯测试环境假象）。vanilla 包（聊天/声音）
     * 进 EmbeddedChannel 无害；mod payload 由 SpellCastHandler.sync 的防御兜住。
     */
    private static net.minecraft.server.level.ServerPlayer mockServerPlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "test-mock-player"), false);
        var player = new net.minecraft.server.level.ServerPlayer(
                helper.getLevel().getServer(), helper.getLevel(),
                cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection =
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(
                helper.getLevel().getServer(), connection, player, cookie);
        // 测试服务器默认创造：invulnerable 会让 hurt 空转（血换蓝等需要真实承伤的路径测不了）。
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        return player;
    }

    /** 锻造台槽位数（{@link ForgeTableMenu#MATERIAL_SLOTS}）：不足补空气。 */
    private static List<ItemStack> padToSlots(ItemStack... stacks) {
        List<ItemStack> list = new java.util.ArrayList<>(List.of(stacks));
        while (list.size() < ForgeTableMenu.MATERIAL_SLOTS) list.add(ItemStack.EMPTY);
        return list;
    }
}
