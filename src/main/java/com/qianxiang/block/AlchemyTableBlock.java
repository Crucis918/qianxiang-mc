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

/** 炼金台。右键打开容器 UI（6 材料槽 + 1 卷轴产物槽，支持 AI 方案）。 */
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

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AlchemyTableBlockEntity be) {
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
