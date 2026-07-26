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

/** 自定义台（相之凝结台）。右键打开容器 UI，并根据锻造状态释放粒子光效。 */
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

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be) {
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
