package com.qianxiang;

import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.PhaseFunction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 算子落地矩阵（「吞功能」审计）+ 手持属性修饰符生效链。
 * <p>
 * 矩阵：25 个 {@link PhaseFunction} 各挑一个代表物品（功能 tag / PhaseData，见表），
 * 单材料走完整 {@link ForgeComposer#compose}（resolver → AttributeScheme → 产物组件），
 * 从<b>产物栈的 COMPOSED_ATTRIBUTES 组件</b>读回对应字段断言落地——
 * 哪个算子字段为零，哪个就是「吞功能」实锤（测试一次性报告全部缺失项）。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangFunctionMatrixGameTests {

    private QianxiangFunctionMatrixGameTests() {}

    /** 矩阵行：算子 → 代表物品 + 落地判定（从产物组件属性读）。 */
    private record MatrixRow(PhaseFunction fn, ItemStack material, String field,
                             java.util.function.Predicate<ComposedAttributes> lands) {}

    private static List<MatrixRow> matrix() {
        List<MatrixRow> rows = new ArrayList<>();
        rows.add(new MatrixRow(PhaseFunction.BASE_METAL, new ItemStack(Items.IRON_INGOT),
                "durability/attackSpeed", a -> a.durability() > 0 && a.attackSpeed() > 0));
        rows.add(new MatrixRow(PhaseFunction.BASE_WOOD, new ItemStack(Items.STICK),
                "durability", a -> a.durability() > 0));
        rows.add(new MatrixRow(PhaseFunction.BASE_BONE, new ItemStack(Items.BONE),
                "durability/knockbackResistance", a -> a.durability() > 0 && a.knockbackResistance() > 0));
        rows.add(new MatrixRow(PhaseFunction.BASE_HIDE, new ItemStack(Items.LEATHER),
                "durability/armor", a -> a.durability() > 0 && a.armor() > 0));
        rows.add(new MatrixRow(PhaseFunction.EDGE, new ItemStack(Items.FLINT),
                "attackDamage", a -> a.attackDamage() > 0));
        rows.add(new MatrixRow(PhaseFunction.DEFENSE, new ItemStack(Items.TURTLE_SCUTE),
                "armor", a -> a.armor() > 0));
        rows.add(new MatrixRow(PhaseFunction.MANA, new ItemStack(Items.REDSTONE),
                "manaBonus/armorToughness", a -> a.manaBonus() > 0 && a.armorToughness() > 0));
        rows.add(new MatrixRow(PhaseFunction.IGNITE, new ItemStack(Items.BLAZE_POWDER),
                "igniteLevel", a -> a.igniteLevel() > 0));
        rows.add(new MatrixRow(PhaseFunction.LIFESTEAL, new ItemStack(Items.SPIDER_EYE),
                "lifestealLevel", a -> a.lifestealLevel() > 0));
        rows.add(new MatrixRow(PhaseFunction.REFLECT, new ItemStack(Items.CACTUS),
                "thornsLevel", a -> a.thornsLevel() > 0));
        rows.add(new MatrixRow(PhaseFunction.SLOW, new ItemStack(Items.COBWEB),
                "slowLevel", a -> a.slowLevel() > 0));
        rows.add(new MatrixRow(PhaseFunction.HEAL, new ItemStack(Items.EMERALD),
                "healLevel/maxHealth", a -> a.healLevel() > 0 && a.maxHealth() > 0));
        rows.add(new MatrixRow(PhaseFunction.POISON, new ItemStack(Items.PUFFERFISH),
                "effects.poison", a -> a.effects().poison() > 0));
        rows.add(new MatrixRow(PhaseFunction.FROST, new ItemStack(Items.ICE),
                "effects.frost", a -> a.effects().frost() > 0));
        rows.add(new MatrixRow(PhaseFunction.LEVITATION, new ItemStack(Items.SHULKER_SHELL),
                "effects.levitation", a -> a.effects().levitation() > 0));
        rows.add(new MatrixRow(PhaseFunction.STRENGTH, new ItemStack(Items.NETHER_WART),
                "effects.strength", a -> a.effects().strength() > 0));
        rows.add(new MatrixRow(PhaseFunction.NIGHT_VISION, new ItemStack(Items.GOLDEN_CARROT),
                "effects.nightVision", a -> a.effects().nightVision() > 0));
        rows.add(new MatrixRow(PhaseFunction.SPEED_BOOST, new ItemStack(Items.SUGAR),
                "effects.speedBoost", a -> a.effects().speedBoost() > 0));
        rows.add(new MatrixRow(PhaseFunction.JUMP_BOOST, new ItemStack(Items.RABBIT_FOOT),
                "effects.jumpBoost", a -> a.effects().jumpBoost() > 0));
        rows.add(new MatrixRow(PhaseFunction.RESISTANCE, new ItemStack(Items.NETHERITE_INGOT),
                "effects.resistance", a -> a.effects().resistance() > 0));
        rows.add(new MatrixRow(PhaseFunction.FIRE_RESIST, new ItemStack(Items.MAGMA_CREAM),
                "effects.fireResist", a -> a.effects().fireResist() > 0));
        rows.add(new MatrixRow(PhaseFunction.WATER_BREATH, new ItemStack(Items.PRISMARINE_SHARD),
                "effects.waterBreath", a -> a.effects().waterBreath() > 0));
        rows.add(new MatrixRow(PhaseFunction.REGENERATION, new ItemStack(Items.GHAST_TEAR),
                "effects.regeneration", a -> a.effects().regeneration() > 0));
        rows.add(new MatrixRow(PhaseFunction.GROWTH, new ItemStack(Items.BONE_MEAL),
                "effects.growth", a -> a.effects().growth() > 0));
        rows.add(new MatrixRow(PhaseFunction.AREA_HARVEST, new ItemStack(Items.ECHO_SHARD),
                "effects.areaHarvest", a -> a.effects().areaHarvest() > 0));
        rows.add(new MatrixRow(PhaseFunction.REVERSE, new ItemStack(QianxiangItems.REVERSE_CORE.get()),
                "extraEffects.reversed", a -> a.extraEffects().reversed()));
        return rows;
    }

    /** 25 算子单材料全链路落地矩阵：缺失项一次性全部报告。 */
    @GameTest(template = "item_concept")
    public static void functionOperatorLandsOnProduct(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        for (MatrixRow row : matrix()) {
            List<ItemStack> mats = new ArrayList<>(25);
            for (int i = 0; i < 25; i++) mats.add(ItemStack.EMPTY);
            mats.set(12, row.material().copy());
            ForgeComposer.Composition comp = ForgeComposer.compose(mats);
            if (!comp.valid()) {
                missing.add(row.fn() + "(" + row.field() + " → 组合无效，产物都没出)");
                continue;
            }
            var attr = comp.result().get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            if (attr == null) {
                missing.add(row.fn() + "(" + row.field() + " → 产物缺 COMPOSED_ATTRIBUTES 组件)");
                continue;
            }
            if (!row.lands().test(attr)) {
                missing.add(row.fn() + "(" + row.field() + " = 0，吞功能)");
            }
        }
        helper.assertTrue(missing.isEmpty(),
                "算子落地矩阵缺失 " + missing.size() + "/25：" + String.join("；", missing));
        helper.succeed();
    }

    /**
     * 手持生效链：锻武器（铁 BASE_METAL + 燧石 EDGE）→ 主手装备 →
     * ①玩家 ATTACK_DAMAGE/ATTACK_SPEED 属性含修饰符（组件→玩家属性真生效）；
     * ②满力攻击假人掉血 == 1（玩家基底）+ 4.0（EDGE COMMON）== 5.0。
     */
    @GameTest(template = "item_concept")
    public static void heldWeaponModifiersReallyApply(GameTestHelper helper) throws Exception {
        List<ItemStack> mats = new ArrayList<>(25);
        for (int i = 0; i < 25; i++) mats.add(ItemStack.EMPTY);
        mats.set(12, new ItemStack(Items.IRON_INGOT));
        mats.set(6, new ItemStack(Items.FLINT));
        ForgeComposer.Composition comp = ForgeComposer.compose(mats);
        helper.assertTrue(comp.valid(), "铁+燧石应能锻出武器");
        ItemStack weapon = comp.result();
        var attr = weapon.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        helper.assertTrue(attr != null && attr.attackDamage() == 4.0,
                "产物攻击应为 EDGE 4.0，实际 " + (attr == null ? "null" : attr.attackDamage()));

        // 桥：栈上的 ATTRIBUTE_MODIFIERS 组件应带 ATTACK_DAMAGE/ATTACK_SPEED 条目
        var mods = weapon.get(DataComponents.ATTRIBUTE_MODIFIERS);
        helper.assertTrue(mods != null && mods.modifiers().stream().anyMatch(e ->
                        e.attribute().equals(Attributes.ATTACK_DAMAGE) && e.modifier().amount() == 4.0),
                "栈修饰符组件应含 ATTACK_DAMAGE +4.0 条目");
        helper.assertTrue(mods.modifiers().stream().anyMatch(e ->
                        e.attribute().equals(Attributes.ATTACK_SPEED) && e.modifier().amount() == 0.4),
                "栈修饰符组件应含 ATTACK_SPEED +0.4 条目（BASE_METAL）");

        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();
        // 1.21.1 的装备属性刷新走 tick 的 detectEquipmentUpdates 管线（私有），
        // setItemSlot 不即时改属性；且 lastHandItems 首次调用即以当前物品播种。
        // 反射调两次：先空手播种，再装备检出变更 → 修饰符真落属性图。
        var detectEquipmentUpdates = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredMethod("detectEquipmentUpdates");
        detectEquipmentUpdates.setAccessible(true);
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        detectEquipmentUpdates.invoke(player);
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, weapon);
        detectEquipmentUpdates.invoke(player);

        double atk = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        helper.assertTrue(Math.abs(atk - 5.0) < 0.001,
                "主手持械后玩家攻击属性应为 1+4=5，实际 " + atk);
        double spd = player.getAttributeValue(Attributes.ATTACK_SPEED);
        helper.assertTrue(Math.abs(spd - 4.4) < 0.001,
                "主手持械后玩家攻速属性应为 4+0.4=4.4，实际 " + spd);

        // 满力一击：攻击蓄力槽直接灌满（LivingEntity.attackStrengthTicker 受保护字段）
        var tickerField = net.minecraft.world.entity.LivingEntity.class
                .getDeclaredField("attackStrengthTicker");
        tickerField.setAccessible(true);
        tickerField.setInt(player, 200);

        var pig = helper.spawn(net.minecraft.world.entity.EntityType.PIG,
                new net.minecraft.core.BlockPos(2, 2, 2));
        player.moveTo(pig.getX(), pig.getY(), pig.getZ() + 1.5, 0, 0);
        float before = pig.getHealth();
        player.attack(pig);
        float dmg = before - pig.getHealth();
        helper.assertTrue(Math.abs(dmg - 5.0f) < 0.01f,
                "满力一击掉血应 == 5.0（修饰符经属性真生效），实际 " + dmg);
        helper.succeed();
    }
}
