package com.qianxiang;

import com.qianxiang.blueprint.BlueprintData;
import com.qianxiang.blueprint.BlueprintShareCodes;
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

    // ============================ 工具 ============================

    /** 锻造台是 10 槽：不足补空气。 */
    private static List<ItemStack> padTo10(ItemStack... stacks) {
        List<ItemStack> list = new java.util.ArrayList<>(List.of(stacks));
        while (list.size() < 10) list.add(ItemStack.EMPTY);
        return list;
    }
}
