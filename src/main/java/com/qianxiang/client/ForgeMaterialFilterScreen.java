package com.qianxiang.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 「材料筛选」勾选表格（从相之凝结台进入，返回时回到工作台，容器保持打开）。
 * <p>
 * 列出概念引擎快照里的<b>全部材料</b>（不只背包已有的），每行：复选框 + 材料图标 +
 * 名称 + 核心效果 + 拥有状态（已拥有 = 彩色高亮 + 「×数量」；未拥有 = 灰色 + 「未获得」）。
 * 勾选「想用」的材料即可（设想/规划，不要求已拥有），AI 按勾选材料做方案，
 * 玩家可先设计蓝图再按方案采集。
 * 勾选状态存 {@link ClientMaterialFilter}（仅客户端），
 * 默认全选；顶部提供「全选 / 全不选」。列表超出可视区时滚轮滚动。
 */
public class ForgeMaterialFilterScreen extends Screen {

    /** 返回目标（通常是 {@link ForgeTableScreen}）。 */
    private final Screen parent;

    private static final int LIST_W = 260;
    private static final int LIST_TOP = 64;
    private static final int LIST_BOTTOM_MARGIN = 34;
    private static final int ROW_H = 20;
    private static final int CHECK_W = 10;

    private Button selectAllButton;
    private Button selectNoneButton;
    private Button doneButton;

    /** 打开界面时扫描出的材料条目。 */
    private List<ClientMaterialFilter.Entry> entries = List.of();
    /** 滚动偏移（行）。 */
    private int scrollRows = 0;

    /** 右侧拥有状态栏预留宽度（「×12」/「未获得」）。 */
    private static final int STATUS_W = 48;

    public ForgeMaterialFilterScreen(Screen parent) {
        super(Component.translatable("qianxiang.forge_table.filter.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        try {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.entries = ClientMaterialFilter.scanAll(this.minecraft.player.getInventory());
            }
        } catch (Throwable t) {
            this.entries = List.of();
        }
        this.scrollRows = 0;

        int cx = this.width / 2;
        int by = this.height - 26;
        this.selectAllButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.filter.select_all"),
                        b -> ClientMaterialFilter.setAll(entries, true))
                .bounds(cx - 130, by, 80, 20)
                .build();
        this.addRenderableWidget(this.selectAllButton);

        this.selectNoneButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.filter.select_none"),
                        b -> ClientMaterialFilter.setAll(entries, false))
                .bounds(cx - 40, by, 80, 20)
                .build();
        this.addRenderableWidget(this.selectNoneButton);

        this.doneButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.filter.done"), b -> onClose())
                .bounds(cx + 50, by, 80, 20)
                .build();
        this.addRenderableWidget(this.doneButton);
    }

    @Override
    public void onClose() {
        // 返回工作台（容器保持打开，见 ForgeTableScreen.removed 的切换保护）
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    private int listX() {
        return this.width / 2 - LIST_W / 2;
    }

    private int listBottom() {
        return this.height - LIST_BOTTOM_MARGIN;
    }

    private int maxScrollRows() {
        int visible = (listBottom() - LIST_TOP) / ROW_H;
        return Math.max(0, entries.size() - visible);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= LIST_TOP && mouseY < listBottom()) {
            scrollRows = Math.clamp(scrollRows - (int) Math.signum(scrollY), 0, maxScrollRows());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int row = hitTestRow(mouseX, mouseY);
            if (row >= 0 && row < entries.size()) {
                var entry = entries.get(row);
                ClientMaterialFilter.setChecked(entry.id(), !ClientMaterialFilter.isChecked(entry.id()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中列表行号（含滚动偏移），未命中返回 -1。 */
    private int hitTestRow(double mouseX, double mouseY) {
        int x = listX();
        if (mouseX < x || mouseX >= x + LIST_W || mouseY < LIST_TOP || mouseY >= listBottom()) {
            return -1;
        }
        return scrollRows + (int) (mouseY - LIST_TOP) / ROW_H;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 26, 0xFFFFD700);
        g.drawCenteredString(this.font,
                Component.translatable("qianxiang.forge_table.filter.hint"),
                cx, 40, 0xFF888888);

        int x = listX();
        int bottom = listBottom();

        // 已选计数
        int checked = 0;
        for (var e : entries) {
            if (ClientMaterialFilter.isChecked(e.id())) checked++;
        }
        g.drawString(this.font,
                Component.translatable("qianxiang.forge_table.filter.count", checked, entries.size()),
                x, LIST_TOP - 11, 0xFFAAAAAA, false);

        if (entries.isEmpty()) {
            g.drawCenteredString(this.font,
                    Component.translatable("qianxiang.forge_table.filter.empty"),
                    cx, LIST_TOP + 20, 0xFF888888);
            return;
        }

        // 列表底板 + 裁剪区
        g.fill(x - 2, LIST_TOP - 2, x + LIST_W + 2, bottom, 0x80101018);
        g.enableScissor(x - 2, LIST_TOP - 2, x + LIST_W + 2, bottom);

        int hoveredRow = hitTestRow(mouseX, mouseY);
        int visible = (bottom - LIST_TOP) / ROW_H + 1;
        for (int i = scrollRows; i < entries.size() && i < scrollRows + visible; i++) {
            var entry = entries.get(i);
            int ry = LIST_TOP + (i - scrollRows) * ROW_H;
            boolean isChecked = ClientMaterialFilter.isChecked(entry.id());

            if (i == hoveredRow) {
                g.fill(x, ry, x + LIST_W, ry + ROW_H, 0x33FFFFFF);
            }

            // 复选框
            int cbY = ry + (ROW_H - CHECK_W) / 2;
            int border = isChecked ? 0xFFFFD700 : 0xFF666666;
            g.fill(x + 2, cbY, x + 2 + CHECK_W, cbY + 1, border);
            g.fill(x + 2, cbY + CHECK_W - 1, x + 2 + CHECK_W, cbY + CHECK_W, border);
            g.fill(x + 2, cbY, x + 3, cbY + CHECK_W, border);
            g.fill(x + 2 + CHECK_W - 1, cbY, x + 2 + CHECK_W, cbY + CHECK_W, border);
            if (isChecked) {
                g.drawString(this.font, "✓", x + 4, cbY + 1, 0xFFFFD700, false);
            }

            // 材料图标
            g.renderItem(entry.icon(), x + 16, ry + 2);

            // 右侧拥有状态：已拥有「×N」（青绿），未拥有「未获得」（暗灰）
            boolean owned = entry.isOwned();
            Component status = owned
                    ? Component.literal("×" + entry.owned())
                    : Component.translatable("qianxiang.forge_table.filter.not_obtained");
            String statusStr = this.font.plainSubstrByWidth(status.getString(), STATUS_W);
            int statusColor = owned ? 0xFF7FE3C0 : 0xFF666666;
            g.drawString(this.font, statusStr,
                    x + LIST_W - 4 - this.font.width(statusStr), ry + 2, statusColor, false);

            // 名称（第一行）+ 核心效果（第二行，灰色小字截断）；未拥有整体灰化
            int textW = LIST_W - 40 - STATUS_W;
            String name = this.font.plainSubstrByWidth(entry.name(), textW);
            int nameColor = !owned ? 0xFF666666 : (isChecked ? 0xFFE0E0E0 : 0xFF888888);
            g.drawString(this.font, name, x + 36, ry + 2, nameColor, false);
            if (!entry.effects().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Component c : entry.effects()) {
                    if (sb.length() > 0) sb.append('/');
                    sb.append(c.getString());
                }
                String effects = this.font.plainSubstrByWidth(sb.toString(), textW);
                g.drawString(this.font, effects, x + 36, ry + 11,
                        owned ? 0xFF7FE3C0 : 0xFF4E665C, false);
            }
        }
        g.disableScissor();

        // 简易滚动条
        int maxScroll = maxScrollRows();
        if (maxScroll > 0) {
            int trackH = bottom - LIST_TOP;
            int barH = Math.max(12, trackH * trackH / (entries.size() * ROW_H));
            int barY = LIST_TOP + (trackH - barH) * scrollRows / maxScroll;
            g.fill(x + LIST_W - 2, LIST_TOP, x + LIST_W, bottom, 0xFF333333);
            g.fill(x + LIST_W - 2, barY, x + LIST_W, barY + barH, 0xFFAAAAAA);
        }
    }
}
