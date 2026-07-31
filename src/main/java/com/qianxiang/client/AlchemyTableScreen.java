package com.qianxiang.client;

import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.network.AiPlaceMaterialsPayload;
import com.qianxiang.network.AiRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 炼金台界面（从简版锻造台界面）。
 * <p>
 * 布局（与 textures/gui/alchemy_table.png 一致）：
 * 左上 6 材料槽（2 行 3 列）、右上需求输入框、中部 问AI/清空/确认 按钮、
 * 右侧 32×32 卷轴产物槽、下方 3 张方案卡（点击→放料）、底部玩家背包。
 * 只做这些元素——蓝图/说明书/材料筛选面板不搬。
 * </p>
 */
public class AlchemyTableScreen extends AbstractContainerScreen<AlchemyTableMenu> {

    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(com.qianxiang.Qianxiang.MOD_ID,
                    "textures/gui/alchemy_table.png");

    // —— 布局常量（与菜单槽位坐标、GUI 纹理一一对应）——
    private static final int INPUT_X = 104, INPUT_Y = 17, INPUT_W = 144, INPUT_H = 14;
    private static final int BTN_X = 146, BTN_W = 58, BTN_H = 13;
    private static final int STATUS_X = 8, STATUS_Y = 56, STATUS_W = 132;
    private static final int CARD_Y = 96, CARD_W = 76, CARD_H = 40;
    private static final int[] CARD_XS = {8, 90, 172};

    /** 炼金台产物固定为法系（AI 走 type=magic 产出 spellJson 方案）。 */
    private static final String TARGET_TYPE = "magic";
    /** 固定档位：炼金台从简，不做档位按钮；AI 按稀有档出方案。 */
    private static final String TARGET_TIER = "rare";

    private enum Status { IDLE, PARSING, READY, COMPLETE }

    private EditBox requestBox;
    /** 「示例」按钮（轮换填入示例需求）。 */
    private Button exampleButton;
    /** 示例轮换下标。 */
    private int exampleIndex = 0;
    private Button askButton;
    private Button clearButton;
    private Button confirmButton;
    /** 「开始创作」按钮（产物就绪时显示，点击触发合成仪式）。 */
    private Button beginCraftButton;
    /** 「全部取回」按钮（材料区标题旁，材料为空/仪式启动后置灰）。 */
    private Button retrieveAllButton;
    /** 「自动备料」按钮（有 AI 方案时显示，一键放料）。 */
    private Button autoPlaceButton;
    /** 已发仪式请求、等服务端关 GUI 的窗口期：禁用一切投入/取回点击（服务端仍权威拒判）。 */
    private boolean awaitingRitual = false;

    private ClientForgeTableAI.AiResult lastAiResult;
    private Status status = Status.IDLE;
    private long aiRequestStartMillis;
    /** 玩家点选过的方案卡索引（-1=无）与点选时的结果实例：渲染选中高亮（WQ-76）。 */
    private int selectedCard = -1;
    private ClientForgeTableAI.AiResult selectedCardResult = null;

    public AlchemyTableScreen(AlchemyTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 256;
        this.imageHeight = 256;
    }

    @Override
    protected void init() {
        // WQ-77 同病：窗口 resize 会重走 init 重建输入框——先暂存旧值，末尾回填。
        String prevRequestText = this.requestBox == null ? null : this.requestBox.getValue();
        super.init();
        this.requestBox = new EditBox(this.font, leftPos + INPUT_X, topPos + INPUT_Y, INPUT_W, INPUT_H,
                Component.empty());
        this.requestBox.setMaxLength(80);
        this.requestBox.setHint(Component.translatable("qianxiang.alchemy_table.hint.request"));
        if (prevRequestText != null) {
            this.requestBox.setValue(prevRequestText);
        }
        this.addRenderableWidget(this.requestBox);

        // 「示例」按钮（输入框下方空档）：点击轮换填入 3 条示例需求，帮玩家起步
        this.exampleButton = Button.builder(
                        Component.translatableWithFallback("qianxiang.ai.example_button", "示例"),
                        b -> cycleExample())
                .bounds(leftPos + 168, topPos + 35, 36, 14)
                .build();
        this.addRenderableWidget(this.exampleButton);

        this.askButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.ask"), b -> sendAiRequest())
                .bounds(leftPos + BTN_X, topPos + 50, BTN_W, BTN_H).build();
        this.clearButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.clear"), b -> clearRequest())
                .bounds(leftPos + BTN_X, topPos + 66, BTN_W, BTN_H).build();
        this.confirmButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.confirm"), b -> sendConfirmRequest())
                .bounds(leftPos + BTN_X, topPos + 82, BTN_W, BTN_H).build();
        this.addRenderableWidget(this.askButton);
        this.addRenderableWidget(this.clearButton);
        this.addRenderableWidget(this.confirmButton);

        // 「开始创作」按钮：产物就绪时显示（材料列表下方空档），点击发仪式请求
        this.beginCraftButton = Button.builder(
                        Component.translatable("qianxiang.table.begin_craft"),
                        b -> {
                            awaitingRitual = true; // 仪式启动窗口期禁用投入/取回
                            PacketDistributor.sendToServer(new com.qianxiang.network.RitualStartPayload());
                        })
                .bounds(leftPos + 8, topPos + 80, 64, 12)
                .build();
        this.addRenderableWidget(this.beginCraftButton);

        // 「自动备料」按钮（「开始创作」右侧）：对当前选中方案（或第 0 方案）一键放料
        this.autoPlaceButton = Button.builder(
                        Component.translatableWithFallback("qianxiang.ai.auto_place", "自动备料"),
                        b -> autoPlace())
                .bounds(leftPos + 76, topPos + 80, 46, 12)
                .build();
        this.addRenderableWidget(this.autoPlaceButton);

        // 「全部取回」按钮（材料区标题旁）：取回全部材料（slotIndex=-1 + all）
        this.retrieveAllButton = Button.builder(
                        Component.translatable("qianxiang.table.retrieve_all"),
                        b -> PacketDistributor.sendToServer(
                                new com.qianxiang.network.TableRetrievePayload(-1, true)))
                .bounds(leftPos + 44, topPos + 4, 56, 12)
                .build();
        this.addRenderableWidget(this.retrieveAllButton);

        // 结果界面回调：AI 回包到达时刷新方案卡。
        ClientAlchemyTableAI.setListener(result -> {
            this.lastAiResult = result;
            this.status = result.proposals().isEmpty() ? Status.IDLE : Status.READY;
        });
    }

    @Override
    public void onClose() {
        ClientAlchemyTableAI.clearListener();
        super.onClose();
    }

    // ============================ 渲染 ============================

    // 「去格子化」：材料区改文字列表（槽位坐标已挪屏外，列表只读数据模型）。
    private static final int MATLIST_X = 8;
    private static final int MATLIST_Y = 17;
    private static final int MATLIST_ROW_H = 9;
    private static final int MATLIST_MAX_ROWS = 4;
    /** 三行操作提示首行 y（0.6 缩放小字、行距 6px：状态条与「开始创作」按钮之间）。 */
    private static final int MATLIST_HINT_Y = 66;

    /** 去格子化：材料槽不再画物品，产物槽/背包走原版。 */
    @Override
    protected void renderSlot(GuiGraphics g, net.minecraft.world.inventory.Slot slot) {
        if (slot.index < AlchemyTableMenu.MATERIAL_SLOTS) {
            return;
        }
        super.renderSlot(g, slot);
    }

    /** 材料列表行：真实槽位下标 + 物品栈（取回 payload 要用槽位下标）。 */
    private record MatRow(int slot, net.minecraft.world.item.ItemStack stack) {}

    /** 材料区文字列表：材料名×数量，超出省略；行 hover 高亮（点击取回）；下方投入提示行。 */
    private void renderMaterialList(GuiGraphics g) {
        List<MatRow> rows = new ArrayList<>();
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            var s = this.menu.getSlot(i).getItem();
            if (!s.isEmpty()) rows.add(new MatRow(i, s));
        }
        int shown = Math.min(rows.size(), MATLIST_MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            var s = rows.get(i).stack();
            String line = s.getHoverName().getString() + " ×" + s.getCount();
            g.drawString(this.font, this.font.plainSubstrByWidth(line, 60),
                    leftPos + MATLIST_X, topPos + MATLIST_Y + i * MATLIST_ROW_H, 0xE0E0E0, false);
        }
        if (rows.size() > shown) {
            g.drawString(this.font, "… +" + (rows.size() - shown),
                    leftPos + MATLIST_X, topPos + MATLIST_Y + shown * MATLIST_ROW_H, 0xAAAAAA, false);
        }
        // 三行操作说明（0.6 缩放小字：投入/取回/台子外交互各一行）
        drawTinyString(g, Component.translatable("qianxiang.table.hint_insert").getString(),
                leftPos + MATLIST_X, topPos + MATLIST_HINT_Y, 0x777777, 0.6f);
        drawTinyString(g, Component.translatable("qianxiang.table.hint_insert_2").getString(),
                leftPos + MATLIST_X, topPos + MATLIST_HINT_Y + 6, 0x777777, 0.6f);
        drawTinyString(g, Component.translatable("qianxiang.table.hint_insert_3").getString(),
                leftPos + MATLIST_X, topPos + MATLIST_HINT_Y + 12, 0x777777, 0.6f);
    }

    /** 命中材料列表行 → 该行的真实槽位下标；未命中 -1。 */
    private int hitTestMaterialRow(double mouseX, double mouseY) {
        List<MatRow> rows = new ArrayList<>();
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            var s = this.menu.getSlot(i).getItem();
            if (!s.isEmpty()) rows.add(new MatRow(i, s));
        }
        int shown = Math.min(rows.size(), MATLIST_MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            int rx = leftPos + MATLIST_X;
            int ry = topPos + MATLIST_Y + i * MATLIST_ROW_H;
            if (mouseX >= rx && mouseX < rx + 64 && mouseY >= ry && mouseY < ry + MATLIST_ROW_H) {
                return rows.get(i).slot();
            }
        }
        return -1;
    }

    /** 缩放绘制小字（卡片材料状态标签用）。 */
    private void drawTinyString(GuiGraphics g, String text, float x, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 玩家背包（主背包 36 格）里某物品的实时数量（卡片有/缺显示用）。 */
    private int countInClientInventory(net.minecraft.world.item.Item item) {
        if (this.minecraft == null || this.minecraft.player == null) return 0;
        int total = 0;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            var s = this.minecraft.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(item)) total += s.getCount();
        }
        return total;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(BG_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        updateStatus();
        // 「开始创作」只在产物就绪时显示
        this.beginCraftButton.visible = !this.menu.getSlot(AlchemyTableMenu.RESULT_SLOT).getItem().isEmpty();
        // 「全部取回」：材料为空或仪式启动窗口期置灰
        this.retrieveAllButton.active = hasMaterialsInSlots() && !awaitingRitual;
        // 「自动备料」：有 AI 方案才显示
        this.autoPlaceButton.visible = lastAiResult != null
                && !lastAiResult.proposals().isEmpty() && !awaitingRitual;
        renderMaterialList(g);
        renderStatus(g);
        renderCards(g, mouseX, mouseY);
        renderTooltip(g, mouseX, mouseY);
        renderMaterialRowTooltip(g, mouseX, mouseY);
        renderSuggestionLine(g);
    }

    /** 「能做啥」主动建议行（顶部标题行右侧：状态条下方已被三行提示与开始创作占满，
     *  标题行 x≥104 在输入框上方恒空；纯展示，不占 AI 结果区）。 */
    private void renderSuggestionLine(GuiGraphics g) {
        Component line = ClientTableSuggestion.line();
        if (line == null) return;
        g.drawString(this.font, this.font.plainSubstrByWidth(line.getString(), 146),
                leftPos + 104, topPos + 6, 0x7FE3C0, false);
    }

    /** 材料槽是否有任何材料（「全部取回」按钮置灰用）。 */
    private boolean hasMaterialsInSlots() {
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            if (!this.menu.getSlot(i).getItem().isEmpty()) return true;
        }
        return false;
    }

    /** 材料行 hover tooltip：物品名 + 取回操作提示（锻造台同语义，从简无性质卡）。 */
    private void renderMaterialRowTooltip(GuiGraphics g, int mouseX, int mouseY) {
        int slot = hitTestMaterialRow(mouseX, mouseY);
        if (slot < 0) return;
        var stack = this.menu.getSlot(slot).getItem();
        if (stack.isEmpty()) return;
        g.renderTooltip(this.font, List.of(stack.getHoverName(),
                        Component.translatable("qianxiang.table.retrieve_hint")
                                .withStyle(net.minecraft.ChatFormatting.GRAY)),
                java.util.Optional.empty(), mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题条由纹理承担，不再画默认标题/背包标签（与锻造台界面同风格）。
    }

    private void updateStatus() {
        if (status == Status.PARSING) {
            // WQ-74 超时兜底：AI 超时上限 30s×2（本体+重试）+10s 余量仍无回包就本地复位，
            // 不依赖服务器状态（此前 8 秒是按旧 5s 超时拍的，30s 超时下会误杀正常请求）。
            if (Util.getMillis() - aiRequestStartMillis > 70_000L) {
                status = Status.IDLE;
            }
            return;
        }
        if (status == Status.COMPLETE) {
            return;
        }
        if (lastAiResult != null && !lastAiResult.proposals().isEmpty()) {
            status = Status.READY;
        } else if (status != Status.IDLE) {
            status = Status.IDLE;
        }
    }

    private void renderStatus(GuiGraphics g) {
        Component text = switch (status) {
            case IDLE -> Component.translatable("qianxiang.alchemy_table.status.idle");
            case PARSING -> Component.translatable("qianxiang.alchemy_table.status.parsing");
            case READY -> Component.translatable("qianxiang.alchemy_table.status.ready");
            case COMPLETE -> Component.translatable("qianxiang.alchemy_table.status.complete");
        };
        int color = switch (status) {
            case PARSING -> 0x55FFFF;
            case READY -> 0x55FF55;
            case COMPLETE -> 0xFFAA00;
            case IDLE -> 0xAAAAAA;
        };
        g.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), STATUS_W),
                leftPos + STATUS_X, topPos + STATUS_Y + 1, color, false);
    }

    /** 3 张方案卡：摘要 + 强度 + 材料条目（持有=×n 绿 / 缺失=压暗+红「缺」，逐条可点击）。 */
    private void renderCards(GuiGraphics g, int mouseX, int mouseY) {
        if (lastAiResult == null) return;
        var proposals = lastAiResult.proposals();
        for (int i = 0; i < Math.min(proposals.size(), CARD_XS.length); i++) {
            int x = leftPos + CARD_XS[i];
            int y = topPos + CARD_Y;
            boolean hovered = mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H;
            if (hovered) {
                g.fill(x, y, x + CARD_W, y + CARD_H, 0x30FFFFFF);
            }
            // WQ-76：选中卡片金色描边（只认点选时的那份结果，新响应到达旧卡高亮消失）
            if (i == selectedCard && lastAiResult == selectedCardResult) {
                int sel = 0xCCFFD700;
                g.fill(x, y, x + CARD_W, y + 1, sel);
                g.fill(x, y + CARD_H - 1, x + CARD_W, y + CARD_H, sel);
                g.fill(x, y, x + 1, y + CARD_H, sel);
                g.fill(x + CARD_W - 1, y, x + CARD_W, y + CARD_H, sel);
            }
            var proposal = proposals.get(i);
            String summary = proposal.summary() == null ? "" : proposal.summary();
            g.drawString(this.font, this.font.plainSubstrByWidth(summary, CARD_W - 4),
                    x + 2, y + 3, 0xFFFFFF, false);
            g.drawString(this.font,
                    Component.translatable("qianxiang.forge_table.preview.power",
                            String.format("%.1f", proposal.estimatedPower())),
                    x + 2, y + 14, 0xFFAA00, false);

            // 材料条目：图标 + 背包实时状态（条目区域优先于整张卡）
            var materials = proposal.materialNames();
            for (int j = 0; j < materials.size() && j < 4; j++) {
                int entryX = x + 2 + j * 14;
                int entryY = y + 25;
                var matStack = resolveItemStack(materials.get(j));
                if (matStack.isEmpty()) continue;
                boolean entryHover = mouseX >= entryX - 1 && mouseX < entryX + 13
                        && mouseY >= entryY - 1 && mouseY < entryY + 9;
                if (entryHover) {
                    g.fill(entryX - 1, entryY - 1, entryX + 13, entryY + 9, 0x30FFFFFF);
                }
                g.pose().pushPose();
                g.pose().translate(entryX, entryY, 0);
                g.pose().scale(0.5f, 0.5f, 0.5f);
                g.renderItem(matStack, 0, 0);
                g.pose().popPose();
                int held = countInClientInventory(matStack.getItem());
                if (held > 0) {
                    drawTinyString(g, "×" + held, entryX + 9, entryY + 1, 0xFF7FE3C0, 0.45f);
                } else {
                    g.fill(entryX, entryY, entryX + 8, entryY + 8, 0xA0000000);
                    drawTinyString(g, Component.translatable("qianxiang.table.missing_mark").getString(),
                            entryX + 9, entryY + 1, 0xFFFF5555, 0.45f);
                }
            }
        }
    }

    /** 命中的卡片材料条目 → [卡片下标, 材料下标]；条目区域优先于整张卡。未命中 null。 */
    private int[] hitTestCardMaterial(double mouseX, double mouseY) {
        if (lastAiResult == null) return null;
        var proposals = lastAiResult.proposals();
        for (int i = 0; i < Math.min(proposals.size(), CARD_XS.length); i++) {
            int x = leftPos + CARD_XS[i];
            int y = topPos + CARD_Y;
            var materials = proposals.get(i).materialNames();
            for (int j = 0; j < materials.size() && j < 4; j++) {
                int entryX = x + 2 + j * 14;
                int entryY = y + 25;
                if (mouseX >= entryX - 1 && mouseX < entryX + 13
                        && mouseY >= entryY - 1 && mouseY < entryY + 9) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    // ============================ 交互 ============================

    /** 「示例」轮换：3 条示例需求依次填入输入框（qianxiang.ai.example.1~3）。 */
    private void cycleExample() {
        exampleIndex = exampleIndex % 3 + 1;
        String key = "qianxiang.ai.example." + exampleIndex;
        String fallback = switch (exampleIndex) {
            case 1 -> "我要一把会喷火的剑";
            case 2 -> "想要猛一点的巨剑，越重越好";
            default -> "整把帅的，带闪电特效";
        };
        this.requestBox.setValue(Component.translatableWithFallback(key, fallback).getString());
        this.requestBox.moveCursorToEnd(false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 材料条目区域优先于整张卡：点单个材料 = 只放入该材料
            int[] cardMaterial = hitTestCardMaterial(mouseX, mouseY);
            if (cardMaterial != null) {
                applyProposalMaterial(cardMaterial[0], cardMaterial[1]);
                return true;
            }
            if (lastAiResult != null) {
                var proposals = lastAiResult.proposals();
                for (int i = 0; i < Math.min(proposals.size(), CARD_XS.length); i++) {
                    int x = leftPos + CARD_XS[i];
                    int y = topPos + CARD_Y;
                    if (mouseX >= x && mouseX < x + CARD_W && mouseY >= y && mouseY < y + CARD_H) {
                        applyProposal(i);
                        return true;
                    }
                }
            }
            // 材料列表行点击 = 取回该槽材料（左键 1 个，Shift+左键全部）
            int retrieveSlot = hitTestMaterialRow(mouseX, mouseY);
            if (retrieveSlot >= 0) {
                if (!awaitingRitual) {
                    PacketDistributor.sendToServer(new com.qianxiang.network.TableRetrievePayload(
                            retrieveSlot, hasShiftDown()));
                }
                return true;
            }
            // 背包/快捷栏槽位左键 = 向材料区投入 1 个（不走原版 slotClicked，避免拿起物品；
            // Shift+左键不拦截，保持现有 quickMove 整组投入路径）
            if (!awaitingRitual && !hasShiftDown()
                    && this.hoveredSlot != null
                    && this.hoveredSlot.index >= AlchemyTableMenu.RESULT_SLOT + 1
                    && this.hoveredSlot.hasItem()) {
                PacketDistributor.sendToServer(
                        new com.qianxiang.network.TableInsertPayload(this.hoveredSlot.index, false));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.requestBox != null && this.requestBox.isFocused()) {
            if (keyCode == 256) { // ESC：先失焦，不直接关界面
                this.requestBox.setFocused(false);
                return true;
            }
            if (this.requestBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 发送 AI 推荐请求到服务端（type=magic，产出带 spellJson 的卷轴方案）。 */
    private void sendAiRequest() {
        String request = this.requestBox.getValue().trim();
        status = Status.PARSING;
        aiRequestStartMillis = Util.getMillis();
        PacketDistributor.sendToServer(new AiRequestPayload(
                ClientAlchemyTableAI.nextRequestSeq(),
                request, TARGET_TYPE, TARGET_TIER, collectCurrentMaterials(), "recommend"));
    }

    /** 发送「AI 修改确认」请求到服务端。 */
    private void sendConfirmRequest() {
        String request = this.requestBox.getValue().trim();
        status = Status.PARSING;
        aiRequestStartMillis = Util.getMillis();
        PacketDistributor.sendToServer(new AiRequestPayload(
                ClientAlchemyTableAI.nextRequestSeq(),
                request, TARGET_TYPE, TARGET_TIER, collectCurrentMaterials(), "confirm"));
    }

    private void clearRequest() {
        this.requestBox.setValue("");
        this.lastAiResult = null;
        this.status = Status.IDLE;
        this.selectedCard = -1;
        this.selectedCardResult = null;
        ClientAlchemyTableAI.reportProposalIndex(
                com.qianxiang.network.SpellJsonReportPayload.NONE);
    }

    /** 点击某张方案卡：先回传「选了第几条」，再请求服务端放料（包序保证先记选择）。
     *  WQ-75：整卡点击 = 替换语义（服务端先退回台上现有材料再放），不再叠加。 */
    private void applyProposal(int index) {
        if (lastAiResult == null) return;
        var proposal = lastAiResult.proposals().get(index);
        if (proposal.materialNames().isEmpty()) return;

        ClientAlchemyTableAI.reportProposalIndex(index);
        this.selectedCard = index;
        this.selectedCardResult = lastAiResult;
        PacketDistributor.sendToServer(new AiPlaceMaterialsPayload(
                proposal.materialNames(), true, ClientAlchemyTableAI.lastReqId()));
    }

    /** 「自动备料」：对当前选中方案（无选中取第 0 方案）一键放料（复用点卡路径，replace 语义）。 */
    private void autoPlace() {
        if (lastAiResult == null || lastAiResult.proposals().isEmpty()) return;
        int index = this.selectedCard >= 0 && this.selectedCard < lastAiResult.proposals().size()
                ? this.selectedCard : 0;
        applyProposal(index);
    }

    /** 点击卡片上单个材料条目：选中该方案（同点卡）但只放入这一种材料（叠加，非替换）。 */
    private void applyProposalMaterial(int cardIndex, int materialIndex) {
        if (lastAiResult == null) return;
        var proposal = lastAiResult.proposals().get(cardIndex);
        if (materialIndex < 0 || materialIndex >= proposal.materialNames().size()) return;
        ClientAlchemyTableAI.reportProposalIndex(cardIndex);
        this.selectedCard = cardIndex;
        this.selectedCardResult = lastAiResult;
        PacketDistributor.sendToServer(new AiPlaceMaterialsPayload(
                List.of(proposal.materialNames().get(materialIndex)), false,
                ClientAlchemyTableAI.lastReqId()));
    }

    /** registry 名 → 物品栈（卡片材料图标用；不存在则空栈）。 */
    private net.minecraft.world.item.ItemStack resolveItemStack(String registryName) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(registryName);
        if (id == null) return net.minecraft.world.item.ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.get(id);
        return item == null ? net.minecraft.world.item.ItemStack.EMPTY
                : new net.minecraft.world.item.ItemStack(item);
    }

    /** 当前材料槽内物品的 registry name 列表（空槽跳过）。 */
    private List<String> collectCurrentMaterials() {
        List<String> names = new ArrayList<>(AlchemyTableMenu.MATERIAL_SLOTS);
        for (int i = 0; i < AlchemyTableMenu.MATERIAL_SLOTS; i++) {
            var stack = this.menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                names.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        }
        return names;
    }
}
