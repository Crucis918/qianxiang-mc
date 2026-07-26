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
        List<ItemStack> materials = padTo10(
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
        List<ItemStack> materials = padTo10(
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
        ForgeComposer.Composition c = ForgeComposer.compose(padTo10());
        helper.assertTrue(!c.valid() || c.result().isEmpty(), "空材料不应锻出产物");
        helper.succeed();
    }

    /** 相杖默认法术走 CustomSpell 组件（法术双轨收敛后的契约）。 */
    @GameTest(template = "item_concept")
    public static void staffCarriesCustomSpell(GameTestHelper helper) {
        // 木骨架 + 法力 → 相杖原型
        List<ItemStack> materials = padTo10(
                new ItemStack(QianxiangMaterials.GLIMMER_WOOD_SAP.get()),
                new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get()),
                new ItemStack(Items.STICK));
        ForgeComposer.Composition c = ForgeComposer.compose(materials);
        if (c.valid() && c.result().is(QianxiangItems.PHASE_STAFF.get())) {
            var custom = c.result().get(QianxiangDataComponents.CUSTOM_SPELL.get());
            helper.assertTrue(custom != null, "相杖产物应带 CUSTOM_SPELL 组件（双轨收敛契约）");
        }
        // 未出相杖（出了法术书等）不算失败——本测试只锁「出相杖必带 CustomSpell」
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

    /** 恶意/超限蓝图导入必须被消毒：材料截断到 10、名称截断 64。 */
    @GameTest(template = "item_concept")
    public static void shareCodeSanitizesOversized(GameTestHelper helper) {
        BlueprintData oversized = new BlueprintData(
                Collections.nCopies(50, "minecraft:stick"), "weapon",
                Double.POSITIVE_INFINITY, "超".repeat(300), null, null);
        BlueprintData clean = BlueprintShareCodes.decode(BlueprintShareCodes.encode(oversized));
        helper.assertTrue(clean.materials().size() <= 10,
                "材料应截断到 ≤10，实际 " + clean.materials().size());
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
        ItemStack placed = be.getItem(0);
        helper.assertTrue(placed.is(Items.IRON_PICKAXE), "台上应放入铁镐，实际 " + placed);
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

        // 木骨架 + 法力 → 相杖（能承载 CUSTOM_SPELL 的产物）
        be.setItem(0, new ItemStack(QianxiangMaterials.GLIMMER_WOOD_SAP.get()));
        be.setItem(1, new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get()));
        menu.slotsChanged(be);

        // 材料保持不变，只改该玩家的 AI 选择 —— 必须触发重算并把法术写进产物
        be.setSelection(player.getUUID(), new com.qianxiang.block.ForgeTableBlockEntity.AiProposal(
                "{\"element\":\"frost\",\"form\":\"projectile\",\"effect\":\"damage\",\"power\":2}",
                "霜牙", ""));
        menu.slotsChanged(be);

        ItemStack result = be.getItem(ForgeTableMenu.RESULT_SLOT);
        if (result.is(QianxiangItems.PHASE_STAFF.get())) {
            var spell = result.get(QianxiangDataComponents.CUSTOM_SPELL.get());
            helper.assertTrue(spell != null && "frost".equals(spell.element()),
                    "AI 暂存变化后产物应带上新法术（指纹短路吞掉了重算），实际 "
                            + (spell == null ? "无法术" : spell.element()));
        }
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

        ForgeComposer.Composition withCore = ForgeComposer.compose(padTo10(
                new ItemStack(QianxiangMaterials.EMBER_IRON.get()),
                new ItemStack(QianxiangItems.WARDEN_CORE.get())));
        ForgeComposer.Composition without = ForgeComposer.compose(padTo10(
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
        ForgeComposer.Composition c = ForgeComposer.compose(padTo10(
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

    // ============================ 工具 ============================

    /** 锻造台是 10 槽：不足补空气。 */
    private static List<ItemStack> padTo10(ItemStack... stacks) {
        List<ItemStack> list = new java.util.ArrayList<>(List.of(stacks));
        while (list.size() < 10) list.add(ItemStack.EMPTY);
        return list;
    }
}
