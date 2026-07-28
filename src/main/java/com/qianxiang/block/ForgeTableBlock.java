package com.qianxiang.block;

import com.mojang.serialization.MapCodec;
import com.qianxiang.QianxiangBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 自定义台（相之凝结台）。右键打开容器 UI，并根据锻造状态释放粒子光效。
 * <p>「去格子化」投入式交互：手持材料右键投入（潜行投整组）；空手右键有产物直接拿、
 * 无产物开 GUI；潜行+空手右键取回全部材料。逻辑共用见 {@link TableInteractions}。</p> */
public class ForgeTableBlock extends BaseEntityBlock {
    public static final MapCodec<ForgeTableBlock> CODEC = simpleCodec(ForgeTableBlock::new);

    public ForgeTableBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ForgeTableBlockEntity(pos, state); }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, QianxiangBlockEntities.FORGE_TABLE.get(), ForgeTableBlockEntity::tick);
    }

    /** 手持材料右键：投入 1 个（潜行投整组）；满槽提示不消耗。 */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // 仪式中投料一律拒绝（先判仪式再投入，材料不动）
        if (be.ritualState().active()) {
            RitualLogic.notifyBusy(player);
            return net.minecraft.world.ItemInteractionResult.CONSUME;
        }
        var result = TableInteractions.insertFromHand(
                be, com.qianxiang.menu.ForgeTableMenu.SLOT_FILL_ORDER, player, hand, level, pos);
        if (result.consumesAction() && !level.isClientSide()) {
            be.recomputeResult(player.getUUID());
        }
        return result;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be)) {
            return InteractionResult.SUCCESS;
        }
        // 潜行+空手右键：取回全部材料（仪式中一律拒绝）
        if (player.isShiftKeyDown()) {
            if (be.ritualState().active()) {
                RitualLogic.notifyBusy(player);
                return InteractionResult.SUCCESS;
            }
            if (!level.isClientSide) {
                TableInteractions.retrieveAll(be, com.qianxiang.menu.ForgeTableMenu.MATERIAL_SLOTS,
                        player, level, pos);
                be.recomputeResult(player.getUUID());
                level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.0f);
            }
            return InteractionResult.SUCCESS;
        }
        // DONE：空手右键拾取仪式产物（只此一份，拾取后回 NONE）
        if (be.ritualState() == RitualState.DONE) {
            if (!level.isClientSide) {
                net.minecraft.world.item.ItemStack taken = be.getDisplayResult().copy();
                if (!taken.isEmpty()) {
                    be.setDisplayResultFromRitual(net.minecraft.world.item.ItemStack.EMPTY);
                    be.clearRitualState();
                    if (!player.getInventory().add(taken)) {
                        player.drop(taken, false);
                    }
                }
            }
            return InteractionResult.SUCCESS;
        }
        // 仪式进行中（FLYING/FORMING）：投料/取回/再触发一律拒绝
        if (be.ritualState().active()) {
            RitualLogic.notifyBusy(player);
            return InteractionResult.SUCCESS;
        }
        // 空手右键：有产物 → 触发合成仪式（材料飞入→成型→台面拾取）；无产物开 GUI
        net.minecraft.world.item.ItemStack result = be.getItem(com.qianxiang.menu.ForgeTableMenu.RESULT_SLOT);
        if (!result.isEmpty()) {
            if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                RitualLogic.startRitual(be, sp);
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide) {
            player.openMenu(be);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 破坏方块时把材料槽内容掉出来——否则 BE 连同 11 格物品一起销毁（吞物品）。
     * <p>产物槽（{@link ForgeTableMenu#RESULT_SLOT}）是实时预览、非实体库存，
     * 由 {@link ForgeTableBlockEntity#dropContentsOnRemove} 排除，避免"挖台子白得成品"。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be) {
                be.dropContentsOnRemove(level, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
