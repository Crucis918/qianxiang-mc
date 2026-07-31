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

/** 炼金台。右键打开容器 UI（6 材料槽 + 1 卷轴产物槽，支持 AI 方案）。
 * <p>「去格子化」投入式交互与锻造台一致（逻辑共用见 {@link TableInteractions}）：
 * 手持材料右键投入（潜行投整组）；空手右键开 GUI（DONE 时拾取仪式产物；
 * 仪式只从 GUI 触发，空手右键不再启动）；潜行+空手右键取回全部材料。</p> */
public class AlchemyTableBlock extends BaseEntityBlock {
    public static final MapCodec<AlchemyTableBlock> CODEC = simpleCodec(AlchemyTableBlock::new);

    public AlchemyTableBlock(Properties properties) { super(properties); }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new AlchemyTableBlockEntity(pos, state); }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, QianxiangBlockEntities.ALCHEMY_TABLE.get(), AlchemyTableBlockEntity::tick);
    }

    /** 手持材料右键：投入 1 个（潜行投整组）；满槽提示不消耗。 */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack,
            BlockState state, Level level, BlockPos pos, Player player,
            net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        // 仪式中投料一律拒绝（先判仪式再投入，材料不动）
        if (be.ritualState().active()) {
            RitualLogic.notifyBusy(player);
            return net.minecraft.world.ItemInteractionResult.CONSUME;
        }
        var result = TableInteractions.insertFromHand(
                be, com.qianxiang.menu.AlchemyTableMenu.SLOT_FILL_ORDER, player, hand, level, pos);
        if (result.consumesAction() && !level.isClientSide()) {
            be.recomputeResult(player.getUUID());
        }
        return result;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be)) {
            return InteractionResult.SUCCESS;
        }
        // 潜行+空手右键：取回全部材料（仪式中一律拒绝）
        if (player.isShiftKeyDown()) {
            if (be.ritualState().active()) {
                RitualLogic.notifyBusy(player);
                return InteractionResult.SUCCESS;
            }
            if (!level.isClientSide) {
                TableInteractions.retrieveAll(be, com.qianxiang.menu.AlchemyTableMenu.MATERIAL_SLOTS,
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
        // 空手右键：永远开 GUI（哪怕有产物）——玩家要能在界面里取回材料。
        // 仪式只从 GUI 的「开始创作」按钮与产物槽点击触发（RitualStartPayload 链路），
        // 空手右键不再触发仪式（此前有产物时右键直接开仪式，GUI 根本开不了）。
        if (!level.isClientSide) {
            player.openMenu(be);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 破坏方块时把材料槽内容掉出来——否则 BE 连同材料一起销毁（吞物品）。
     * <p>产物槽（{@link com.qianxiang.menu.AlchemyTableMenu#RESULT_SLOT}）是实时预览、非实体库存，
     * 由 {@link AlchemyTableBlockEntity#dropContentsOnRemove} 排除，避免"挖台子白得成品"。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be) {
                be.dropContentsOnRemove(level, pos);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
