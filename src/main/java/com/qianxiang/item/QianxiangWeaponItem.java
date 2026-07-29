package com.qianxiang.item;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.combat.WeaponFormProfile;
import com.qianxiang.particle.SparkParticleOptions;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.UpgradeRules;
import com.qianxiang.phase.WeaponForm;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.List;

/**
 * 相之武器——「材料即零件」的动态产物载体。
 * <p>
 * 它自己不带任何硬编码属性。攻击力 / 护甲 / 耐久 / 特殊效果等级，
 * 全部来自合成时锻造台写入的 {@link ComposedAttributes}（存于
 * {@code qianxiang:composed_attributes} 组件）。
 * </p>
 * <p>
 * 这是「强度靠材料稀有度、不靠反噬」在物品层的兑现：
 * 用更好的料 → 数值更高；换个便宜料 → 自动降档。换材料 = 调强度。
 * </p>
 * <ul>
 *   <li>耐久：{@code ComposedAttributes.durability()}（override {@code getMaxDamage}）。</li>
 *   <li>攻击力/护甲等：锻造台写入的 {@code ATTRIBUTE_MODIFIERS} 组件承载（见 {@link com.qianxiang.phase.AttributeScheme}）。</li>
 *   <li>特殊效果（灼烧/吸血/反伤/迟缓/疗伤）：等级存在 ComposedAttributes，
 *       由战斗事件钩子 / Epic Fight 技能读取并兑现。</li>
 *   <li>外观变体：根据 {@code dominantEffect} / {@code appearanceKey} 发放彩色粒子、
 *       决定是否附魔光泽，并在 tooltip 显示「相之形」。</li>
 * </ul>
 */
public class QianxiangWeaponItem extends Item {
    /** 无 ComposedAttributes 时的兜底耐久（如直接 /give 出来的空壳）。 */
    public static final int DEFAULT_DURABILITY = 250;
    /** 兜底可附魔性。 */
    public static final int DEFAULT_ENCHANTABILITY = 14;

    public QianxiangWeaponItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null && attr.durability() > 0) {
            return attr.durability();
        }
        return DEFAULT_DURABILITY;
    }

    @Override
    public int getEnchantmentValue(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        // 强度越高，越能承载附魔（用 powerScore 做软代理）。
        if (attr != null) {
            return DEFAULT_ENCHANTABILITY + (int) Math.min(10, attr.powerScore() / 3.0);
        }
        return DEFAULT_ENCHANTABILITY;
    }

    /**
     * 千机伞·形态转换（全职高手·荣耀风）：传奇满级（L{@value UpgradeRules#MAX_LEVEL}）武器
     * 右键在战斗中循环切换 9 种形态（{@link WeaponFormProfile#SWITCHABLE_FORMS}）。
     * <ul>
     *   <li>只重写 {@code AppearanceData.form}（{@link ComposedAttributes#withForm}），
     *       攻击力/耐久/已损耐久/特殊效果/升级等级经验全部保留；</li>
     *   <li>同步：组件变更落在玩家手持栈上，由原版背包同步下发客户端，无需自定义包；</li>
     *   <li>反馈：spark 火花 + 风铃音效 + actionbar 新形态名；</li>
     *   <li>未满级：actionbar 提示解锁条件（行为同旧版无操作）；无形态产物不接管。</li>
     * </ul>
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null || attr.form().isEmpty()) {
            return InteractionResultHolder.pass(stack); // 无形态产物：不接管（旧行为）
        }
        if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }
        if (attr.upgradeLevel() < UpgradeRules.MAX_LEVEL) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "qianxiang.formswitch.locked", UpgradeRules.MAX_LEVEL), true);
            return InteractionResultHolder.fail(stack);
        }

        WeaponForm next = WeaponFormProfile.nextSwitchableForm(attr.form());
        stack.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(), attr.withForm(next.id()));

        // 切换反馈：冰蓝色 spark 喷泉（每颗单独发包以获得各自初速，同 SpellEffectEngine 技法）
        ServerLevel serverLevel = (ServerLevel) level;
        var spark = new SparkParticleOptions(new Vector3f(0.55f, 0.85f, 1.00f));
        double x = player.getX();
        double y = player.getY() + player.getBbHeight() * 0.6;
        double z = player.getZ();
        for (int i = 0; i < 12; i++) {
            double vx = (serverLevel.random.nextDouble() - 0.5) * 0.5;
            double vy = 0.2 + serverLevel.random.nextDouble() * 0.4;
            double vz = (serverLevel.random.nextDouble() - 0.5) * 0.5;
            serverLevel.sendParticles(spark, x, y, z, 1, vx, vy, vz, 0.0);
        }
        serverLevel.playSound(null, player.blockPosition(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.9f, 1.2f);
        serverPlayer.displayClientMessage(Component.translatable(
                "qianxiang.formswitch.switched",
                Component.translatable("qianxiang.weapon_form." + next.id())
                        .withStyle(ChatFormatting.AQUA)), true);
        return InteractionResultHolder.consume(stack);
    }

    /**
     * 易碎（frail）代价兑现：攻击命中后在正常耐久损耗之外，按 frail 等级额外扣耐久
     * （本类继承自 Item，基类 hurtEnemy 本身不扣耐久，故这里只做「额外」部分）。
     */
    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        com.qianxiang.combat.DrawbackHandler.applyFrailExtraDamage(stack, attacker, EquipmentSlot.MAINHAND);
        return super.hurtEnemy(stack, target, attacker);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return true;
    }

    /** 外观多样性：带强力特殊效果（≥2 级点燃/吸血/反伤/迟缓/治疗）、传奇级属性，
     * 或有主导外观主题时自带附魔光泽。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null) {
            if (!"none".equals(attr.dominantEffect()) && !"plain".equals(attr.appearanceKey())) {
                return true;
            }
            if (attr.igniteLevel() >= 2 || attr.lifestealLevel() >= 2 || attr.thornsLevel() >= 2
                    || attr.slowLevel() >= 2 || attr.healLevel() >= 2 || attr.powerScore() >= 8.0) {
                return true;
            }
        }
        return super.isFoil(stack);
    }

    /** 手持时按外观主题释放彩色粒子，让材料组合在视觉上可辨识。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (level.isClientSide() || !isSelected) return;
        if (!(entity instanceof Player player)) return;
        if (level.getGameTime() % 6 != 0) return;

        AppearanceProfile profile = AppearanceProfile.of(stack);
        if (profile.particle == null) return;

        double x = player.getX();
        double y = player.getY() + player.getBbHeight() * 0.55;
        double z = player.getZ();
        double spread = 0.22;
        ((ServerLevel) level).sendParticles(
                profile.particle,
                x, y, z, 1,
                spread, spread, spread, 0.0);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null) {
            return;
        }
        com.qianxiang.phase.UpgradeRules.appendLegendaryLine(stack, tooltip);
        // 增幅器数值（相杖/魔法书等法系产物）：法术伤害加成 / 法力上限加成，非 0 才显示
        if (attr.spellPowerPercent() > 0) {
            tooltip.add(Component.translatable("qianxiang.tooltip.spell_power",
                    String.format("%.0f", attr.spellPowerPercent())).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (attr.manaBonus() > 0) {
            tooltip.add(Component.translatable("qianxiang.tooltip.mana_bonus",
                    attr.manaBonus()).withStyle(ChatFormatting.AQUA));
        }
        // 武器形态（锻造时一次推导写入，外观/EF 动作/判定盒统一按形走；无形态不显示）
        if (!attr.form().isEmpty()) {
            tooltip.add(Component.translatable("qianxiang.weapon_form." + attr.form())
                    .withStyle(ChatFormatting.AQUA));
        }
        // 相之形：外观来源
        AppearanceProfile profile = AppearanceProfile.of(stack);
        tooltip.add(Component.translatable("qianxiang.tooltip.appearance",
                        Component.translatable(profile.translationKey))
                .withStyle(profile.tooltipColor));

        // 强度总分——「概念期协商」的直观体现：这把器有多猛，一目了然。
        tooltip.add(Component.translatable("qianxiang.tooltip.power_score",
                String.format("%.1f", attr.powerScore())).withStyle(ChatFormatting.GOLD));

        // 反转标志行：材料含逆相之核时提示「已反转」（伤害型效果极性倒转）
        if (attr.isReversed()) {
            tooltip.add(Component.translatable("qianxiang.tooltip.reversed")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }

        // 数值属性简报
        if (attr.attackDamage() > 0) {
            tooltip.add(Component.translatable("qianxiang.tooltip.attack_damage",
                    String.format("%.1f", attr.attackDamage())).withStyle(ChatFormatting.YELLOW));
        }
        if (attr.armor() > 0) {
            tooltip.add(Component.translatable("qianxiang.tooltip.armor",
                    String.format("%.1f", attr.armor())).withStyle(ChatFormatting.GRAY));
        }

        // 特殊效果等级（来自材料的功能算子）
        if (attr.igniteLevel() > 0) {
            tooltip.add(specialLine("qianxiang.phasefn.ignite", attr.igniteLevel(), ChatFormatting.RED));
        }
        if (attr.lifestealLevel() > 0) {
            tooltip.add(specialLine("qianxiang.phasefn.lifesteal", attr.lifestealLevel(), ChatFormatting.DARK_RED));
        }
        if (attr.thornsLevel() > 0) {
            tooltip.add(specialLine("qianxiang.phasefn.reflect", attr.thornsLevel(), ChatFormatting.AQUA));
        }
        if (attr.slowLevel() > 0) {
            tooltip.add(specialLine("qianxiang.phasefn.slow", attr.slowLevel(), ChatFormatting.BLUE));
        }
        if (attr.healLevel() > 0) {
            tooltip.add(specialLine("qianxiang.phasefn.heal", attr.healLevel(), ChatFormatting.GREEN));
        }

        // 自由状态效果（grantedEffects，来自 qianxiang:materials/effect/* tag 材料）：攻击时施加给目标
        for (var entry : attr.grantedEffects().entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            tooltip.add(Component.translatable("qianxiang.tooltip.granted_effect",
                    effectName(entry.getKey()), entry.getValue()).withStyle(ChatFormatting.DARK_PURPLE));
        }

        // 代价效果（DrawbackLevels）：红色警示行「代价：xxx ×N」
        appendDrawbacks(attr, tooltip);
    }

    /**
     * 把代价效果写成红色警示 tooltip 行（每个代价一行：「代价：易碎 ×1」）。
     * 武器/防具/工具共用——三种产物都带 ComposedAttributes，代价都应可见。
     */
    static void appendDrawbacks(ComposedAttributes attr, List<Component> tooltip) {
        if (attr == null) return;
        ComposedAttributes.DrawbackLevels d = attr.drawbacks();
        if (d == null || !d.anyPositive()) return;
        appendDrawbackLine(tooltip, "qianxiang.drawback.frail", d.frail());
        appendDrawbackLine(tooltip, "qianxiang.drawback.heavy", d.heavy());
        appendDrawbackLine(tooltip, "qianxiang.drawback.draining", d.draining());
        appendDrawbackLine(tooltip, "qianxiang.drawback.unstable", d.unstable());
        appendDrawbackLine(tooltip, "qianxiang.drawback.cursed", d.cursed());
    }

    private static void appendDrawbackLine(List<Component> tooltip, String key, int level) {
        if (level <= 0) return;
        tooltip.add(Component.translatable("qianxiang.tooltip.drawback",
                Component.translatable(key), level).withStyle(ChatFormatting.RED));
    }

    /** 效果 id → 显示名：注册表里有就用官方译名，否则退化为 path 原文。 */
    static Component effectName(ResourceLocation effectId) {
        return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.getHolder(effectId)
                .<Component>map(h -> Component.translatable(h.value().getDescriptionId()))
                .orElse(Component.literal(effectId.getPath()));
    }

    private static Component specialLine(String key, int level, ChatFormatting color) {
        return Component.translatable(key).append(" ×" + level).withStyle(color);
    }

    /** 外观配置：把 appearanceKey 映射到粒子与 tooltip 颜色。 */
    private enum AppearanceProfile {
        PLAIN("qianxiang.appearance.plain", null, ChatFormatting.GRAY),
        EMBER("qianxiang.appearance.ember", ParticleTypes.FLAME, ChatFormatting.RED),
        BLOOD("qianxiang.appearance.blood", ParticleTypes.DAMAGE_INDICATOR, ChatFormatting.DARK_RED),
        THORN("qianxiang.appearance.thorn", ParticleTypes.CRIT, ChatFormatting.GREEN),
        SHADOW("qianxiang.appearance.shadow", ParticleTypes.SMOKE, ChatFormatting.DARK_GRAY),
        LIFE("qianxiang.appearance.life", ParticleTypes.HAPPY_VILLAGER, ChatFormatting.GREEN),
        ARCANE("qianxiang.appearance.arcane", ParticleTypes.WITCH, ChatFormatting.LIGHT_PURPLE),
        BONE("qianxiang.appearance.bone", ParticleTypes.SMOKE, ChatFormatting.WHITE),
        BULWARK("qianxiang.appearance.bulwark", ParticleTypes.ENCHANTED_HIT, ChatFormatting.AQUA);

        final String translationKey;
        final ParticleOptions particle;
        final ChatFormatting tooltipColor;

        AppearanceProfile(String translationKey, ParticleOptions particle, ChatFormatting tooltipColor) {
            this.translationKey = translationKey;
            this.particle = particle;
            this.tooltipColor = tooltipColor;
        }

        static AppearanceProfile of(ItemStack stack) {
            ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            if (attr == null) return PLAIN;
            String key = attr.appearanceKey();
            return switch (key) {
                case "ember" -> EMBER;
                case "blood" -> BLOOD;
                case "thorn" -> THORN;
                case "shadow" -> SHADOW;
                case "life" -> LIFE;
                case "arcane" -> ARCANE;
                case "bone" -> BONE;
                case "bulwark" -> BULWARK;
                default -> PLAIN;
            };
        }
    }
}
