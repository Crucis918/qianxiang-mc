package com.qianxiang.item;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.phase.ComposedAttributes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * 相之工具——「材料即零件」的功能性工具产物载体（相锄 / 相之水壶）。
 * <p>
 * 与 {@link QianxiangWeaponItem} 一样，自身不硬编码数值；
 * 功能等级来自锻造台写入的 {@link ComposedAttributes#effects()} 子记录：
 * <ul>
 *   <li>相锄（{@link Kind#HOE}）：读 {@code effects().areaHarvest()}，
 *       右键泥土/草方块时以点击方块为中心耕作 (1+level×2)² 范围（1级=3×3，2级=5×5）。</li>
 *   <li>相之水壶（{@link Kind#WATERING_CAN}）：读 {@code effects().growth()}，
 *       右键时对 (1+level×2)² 范围作物施加骨粉催熟效果（1级=3×3，2级=5×5），带粒子。</li>
 * </ul>
 * 空壳（无 composed_attributes 组件，如创造栏直接取出的物品）兜底 1 级，
 * 保证物品开箱可用；强度仍然靠材料——更好的料 → 更大范围。
 * </p>
 */
public class QianxiangToolItem extends Item {
    /** 无 ComposedAttributes 时的兜底耐久（与武器空壳一致）。 */
    public static final int DEFAULT_DURABILITY = 250;

    /** 工具种类：决定读哪个效果等级、右键执行哪种行为。 */
    public enum Kind { HOE, WATERING_CAN }

    /** 可耕作的方块 → 耕作结果（对齐原版锄头 TILLABLES 的核心子集；ROOTED_DIRT 退化为泥土）。 */
    private static final Map<Block, Block> TILLABLES = Map.of(
            Blocks.GRASS_BLOCK, Blocks.FARMLAND,
            Blocks.DIRT_PATH, Blocks.FARMLAND,
            Blocks.DIRT, Blocks.FARMLAND,
            Blocks.COARSE_DIRT, Blocks.FARMLAND,
            Blocks.ROOTED_DIRT, Blocks.DIRT
    );

    private final Kind kind;

    public QianxiangToolItem(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null && attr.durability() > 0) {
            return attr.durability();
        }
        return DEFAULT_DURABILITY;
    }

    /**
     * 当前工具的功能等级：相锄读 areaHarvest，水壶读 growth。
     * 空壳兜底 1 级（3×3），保证 /give、创造栏取出的物品可用。
     */
    public int effectLevel(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null || attr.effects() == null) {
            return 1;
        }
        int level = kind == Kind.HOE ? attr.effects().areaHarvest() : attr.effects().growth();
        return Math.max(1, level);
    }

    /** 高等级工具自带附魔光泽（外观多样性，与武器的阈值风格一致）。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr != null && attr.effects() != null) {
            int level = kind == Kind.HOE ? attr.effects().areaHarvest() : attr.effects().growth();
            if (level >= 2) {
                return true;
            }
        }
        return super.isFoil(stack);
    }

    // ============================ 右键行为 ============================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        try {
            EquipmentSlot slot = LivingEntity.getSlotForHand(context.getHand());
            return switch (kind) {
                case HOE -> tillArea(context, slot);
                case WATERING_CAN -> waterArea(level, context.getClickedPos(), context.getItemInHand(), context.getPlayer(), slot);
            };
        } catch (Exception e) {
            return InteractionResult.PASS;
        }
    }

    /** 水壶右键空气（未点中方块）时，以玩家脚下为中心催熟；锄头对空气右键无意义。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (kind != Kind.WATERING_CAN) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide()) {
            return InteractionResultHolder.success(stack);
        }
        try {
            InteractionResult result = waterArea(level, player.blockPosition(), stack, player,
                    LivingEntity.getSlotForHand(hand));
            return result == InteractionResult.PASS
                    ? InteractionResultHolder.pass(stack)
                    : InteractionResultHolder.success(stack);
        } catch (Exception e) {
            return InteractionResultHolder.pass(stack);
        }
    }

    /**
     * 相锄：以 center 为中心耕作 (1+level×2)² 的方形范围（与原版一致要求上方为空气）。
     * 至少耕出一格才消耗 1 点耐久并播放音效。
     */
    private InteractionResult tillArea(UseOnContext context, EquipmentSlot slot) {
        Level level = context.getLevel();
        BlockPos center = context.getClickedPos();
        int half = effectLevel(context.getItemInHand());
        int tilled = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-half, 0, -half), center.offset(half, 0, half))) {
            Block result = TILLABLES.get(level.getBlockState(pos).getBlock());
            if (result != null && level.getBlockState(pos.above()).isAir()) {
                level.setBlock(pos, result.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
                tilled++;
            }
        }
        if (tilled == 0) {
            return InteractionResult.PASS;
        }
        level.playSound(null, center, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (context.getPlayer() != null) {
            context.getItemInHand().hurtAndBreak(1, context.getPlayer(), slot);
            // 易碎（frail）代价：正常损耗之外按等级额外扣耐久
            com.qianxiang.combat.DrawbackHandler.applyFrailExtraDamage(
                    context.getItemInHand(), context.getPlayer(), slot);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 相之水壶：对 (1+level×2)² 范围内的可催熟方块（作物等 {@link BonemealableBlock}）
     * 施加与骨粉完全一致的催熟逻辑，并发放开心村民 + 水花粒子。
     * 竖向扫 center 与 center.above() 两层，兼容「点耕地」（作物在上方）与「点作物」两种点法。
     * 至少催熟一株才消耗 1 点耐久。
     */
    private InteractionResult waterArea(Level level, BlockPos center, ItemStack stack,
                                        Player player, EquipmentSlot slot) {
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }
        int half = effectLevel(stack);
        int affected = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-half, 0, -half), center.offset(half, 1, half))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BonemealableBlock growable
                    && growable.isValidBonemealTarget(level, pos, state)) {
                if (growable.isBonemealSuccess(level, level.getRandom(), pos, state)) {
                    growable.performBonemeal(server, level.getRandom(), pos, state);
                }
                server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                        pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
                        3, 0.25, 0.25, 0.25, 0.0);
                affected++;
            }
        }
        if (affected == 0) {
            return InteractionResult.PASS;
        }
        server.sendParticles(ParticleTypes.SPLASH,
                center.getX() + 0.5, center.getY() + 1.0, center.getZ() + 0.5,
                12, half * 0.5, 0.3, half * 0.5, 0.0);
        level.playSound(null, center, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        if (player != null) {
            stack.hurtAndBreak(1, player, slot);
            // 易碎（frail）代价：正常损耗之外按等级额外扣耐久
            com.qianxiang.combat.DrawbackHandler.applyFrailExtraDamage(stack, player, slot);
        }
        return InteractionResult.SUCCESS;
    }

    // ============================ Tooltip ============================

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        try {
            ComposedAttributes attr = stack.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
            int level = effectLevel(stack);
            // 空壳（无材料组件）不显示功能行，与武器 tooltip 的克制风格一致
            if (attr == null) {
                return;
            }
            if (kind == Kind.HOE) {
                tooltip.add(Component.translatable("qianxiang.tooltip.area_harvest")
                        .append(" ×" + level).withStyle(ChatFormatting.GOLD));
            } else {
                tooltip.add(Component.translatable("qianxiang.tooltip.growth")
                        .append(" ×" + level).withStyle(ChatFormatting.AQUA));
            }
            if (attr.powerScore() > 0) {
                tooltip.add(Component.translatable("qianxiang.tooltip.power_score",
                        String.format("%.1f", attr.powerScore())).withStyle(ChatFormatting.GOLD));
            }
            // 代价效果（DrawbackLevels）：红色警示行「代价：xxx ×N」
            QianxiangWeaponItem.appendDrawbacks(attr, tooltip);
        } catch (Exception ignored) {
            // tooltip 渲染绝不能炸客户端
        }
    }
}
