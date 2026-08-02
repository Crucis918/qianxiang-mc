package com.qianxiang.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 材料区图标网格（锻造台 5 列 × 5 行 / 炼金台 3 列 × 2 行共用）。
 * <p>
 * 每格 18×18：物品图标 + 数量角标；空槽位画淡虚线框（提示「点背包物品投入」）；
 * hover 显示名称 +「左键取回1 · Shift取全部」；点击语义由调用方发
 * {@code TableRetrievePayload(slot, shift)}（取回 1 个 / 整槽）。
 * 几何计算抽成纯静态（{@link #slotAt}），GameTest 直接驱动。
 * </p>
 */
public final class MaterialGrid {

    private MaterialGrid() {}

    /** 格子边长（含间距，原版槽位规格）。 */
    public static final int CELL = 18;

    /** 虚线框颜色（空槽）。 */
    private static final int EMPTY_BORDER = 0x50666666;
    /** hover 高亮。 */
    private static final int HOVER_FILL = 0x30FFFFFF;

    /**
     * 命中计算（纯逻辑）：鼠标点 → 材料槽下标，未命中 -1。
     *
     * @param originX/originY 网格左上（相对屏幕）
     * @param cols            列数
     * @param slotCount       槽位总数（超出下标的格子不算命中）
     */
    public static int slotAt(int originX, int originY, int cols, double mouseX, double mouseY,
                             int slotCount) {
        if (cols <= 0) return -1;
        int col = (int) Math.floor((mouseX - originX) / CELL);
        int row = (int) Math.floor((mouseY - originY) / CELL);
        if (col < 0 || col >= cols || row < 0) return -1;
        int idx = row * cols + col;
        return idx >= 0 && idx < slotCount ? idx : -1;
    }

    /** 画一格：空槽淡虚线框，有物画图标 + 数量角标；hover 高亮。 */
    public static void renderCell(GuiGraphics g, net.minecraft.client.gui.Font font,
                                  Slot slot, int x, int y, boolean hover) {
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) {
            dashedBorder(g, x, y);
        } else {
            g.fill(x, y, x + CELL, y + CELL, 0x60101010);
            g.renderItem(stack, x + 1, y + 1);
            g.renderItemDecorations(font, stack, x + 1, y + 1);
        }
        if (hover) {
            g.fill(x, y, x + CELL, y + CELL, HOVER_FILL);
        }
    }

    /** hover tooltip：名称 + 取回操作提示。 */
    public static List<Component> tooltipFor(Slot slot) {
        ItemStack stack = slot.getItem();
        return List.of(stack.getHoverName(),
                Component.translatable("qianxiang.table.retrieve_hint")
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /** 淡虚线框（四边各两段，留缝成虚线）。 */
    private static void dashedBorder(GuiGraphics g, int x, int y) {
        // 上/下边：两段横线
        g.fill(x + 2, y, x + 8, y + 1, EMPTY_BORDER);
        g.fill(x + 10, y, x + 16, y + 1, EMPTY_BORDER);
        g.fill(x + 2, y + CELL - 1, x + 8, y + CELL, EMPTY_BORDER);
        g.fill(x + 10, y + CELL - 1, x + 16, y + CELL, EMPTY_BORDER);
        // 左/右边：两段竖线
        g.fill(x, y + 2, x + 1, y + 8, EMPTY_BORDER);
        g.fill(x, y + 10, x + 1, y + 16, EMPTY_BORDER);
        g.fill(x + CELL - 1, y + 2, x + CELL, y + 8, EMPTY_BORDER);
        g.fill(x + CELL - 1, y + 10, x + CELL, y + 16, EMPTY_BORDER);
    }
}
