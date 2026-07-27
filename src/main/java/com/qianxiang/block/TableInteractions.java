package com.qianxiang.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 功能台（锻造台/炼金台）「去格子化」投入式交互的共用逻辑。
 * <p>
 * 两台语义一致：材料槽 0..N-1（N=menu 的 MATERIAL_SLOTS），产物槽 N。
 * 投入优先空槽（compose 按占用槽数计零件，与 AI 放料同一口径），
 * 无空槽才尝试向同种物品槽堆叠。自动化规则（WorldlyContainer 只进不出）不受影响。
 * </p>
 */
public final class TableInteractions {

    private TableInteractions() {}

    /** useItemOn 入口：手持材料右键投入（潜行投整组）；满槽提示不消耗。 */
    public static ItemInteractionResult insertFromHand(Container container, int materialSlots,
                                                       Player player, net.minecraft.world.InteractionHand hand,
                                                       Level level, BlockPos pos) {
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS; // 客户端手臂摆动即可，实际写入在服务端
        }
        int moved = insert(container, materialSlots, held, player.isShiftKeyDown());
        if (moved <= 0) {
            player.displayClientMessage(Component.translatable("qianxiang.table.full"), true);
            return ItemInteractionResult.CONSUME;
        }
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6f, 1.1f);
        return ItemInteractionResult.CONSUME;
    }

    /** 潜行+空手右键：取回全部材料（入背包，溢出掉到世界）。 */
    public static void retrieveAll(Container container, int materialSlots,
                                   Player player, Level level, BlockPos pos) {
        for (int i = 0; i < materialSlots; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            container.setItem(i, ItemStack.EMPTY);
            if (!player.getInventory().add(stack)) {
                Containers.dropContents(level, pos, NonNullList.of(ItemStack.EMPTY, stack));
            }
        }
    }

    /** 吸收台面上方的掉落物（BE tick 驱动，满槽不吸）。返回吸收总数。 */
    public static int absorbAbove(Container container, int materialSlots, Level level, BlockPos pos) {
        int moved = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(0.5))) {
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) continue;
            int m = insert(container, materialSlots, stack, true);
            if (m > 0) {
                moved += m;
                if (stack.isEmpty()) {
                    entity.discard();
                } else {
                    entity.setItem(stack);
                }
            }
        }
        if (moved > 0) {
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.5f, 1.3f);
        }
        return moved;
    }

    /**
     * 往材料槽塞物品：优先空槽（compose 按占用槽计零件），其次可堆叠的同种槽。
     *
     * @param wholeStack true = 整组投入（潜行/吸收掉落物），false = 只塞 1 个
     * @return 实际塞入数量（0 = 满槽）
     */
    public static int insert(Container container, int materialSlots, ItemStack source, boolean wholeStack) {
        int moved = 0;
        int want = wholeStack ? source.getCount() : 1;
        while (moved < want && !source.isEmpty()) {
            int empty = firstEmpty(container, materialSlots);
            if (empty >= 0) {
                // 空槽：整组（或余量）直接进，尊重最大堆叠
                ItemStack place = source.split(Math.min(want - moved, source.getMaxStackSize()));
                container.setItem(empty, place);
                moved += place.getCount();
                continue;
            }
            int stackable = findStackable(container, materialSlots, source);
            if (stackable < 0) break;
            ItemStack existing = container.getItem(stackable);
            int room = existing.getMaxStackSize() - existing.getCount();
            if (room <= 0) break;
            ItemStack part = source.split(Math.min(room, want - moved));
            existing.grow(part.getCount());
            container.setItem(stackable, existing);
            moved += part.getCount();
        }
        return moved;
    }

    private static int firstEmpty(Container container, int materialSlots) {
        for (int i = 0; i < materialSlots; i++) {
            if (container.getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    /** 找可堆叠的同种物品槽（同 id 同组件、未满），找不到返回 -1。 */
    private static int findStackable(Container container, int materialSlots, ItemStack probe) {
        for (int i = 0; i < materialSlots; i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty() && s.getCount() < s.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(s, probe)) {
                return i;
            }
        }
        return -1;
    }
}
