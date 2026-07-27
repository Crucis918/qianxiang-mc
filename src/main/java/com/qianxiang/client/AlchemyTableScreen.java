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
    private Button askButton;
    private Button clearButton;
    private Button confirmButton;

    private ClientForgeTableAI.AiResult lastAiResult;
    private Status status = Status.IDLE;
    private long aiRequestStartMillis;

    public AlchemyTableScreen(AlchemyTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 256;
        this.imageHeight = 256;
    }

    @Override
    protected void init() {
        super.init();
        this.requestBox = new EditBox(this.font, leftPos + INPUT_X, topPos + INPUT_Y, INPUT_W, INPUT_H,
                Component.empty());
        this.requestBox.setMaxLength(80);
        this.requestBox.setHint(Component.translatable("qianxiang.alchemy_table.hint.request"));
        this.addRenderableWidget(this.requestBox);

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

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(BG_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        updateStatus();
        renderStatus(g);
        renderCards(g, mouseX, mouseY);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 标题条由纹理承担，不再画默认标题/背包标签（与锻造台界面同风格）。
    }

    private void updateStatus() {
        if (status == Status.PARSING) {
            // 与 BE 的 PARSING 超时同口径：8 秒没回包就本地复位，不依赖服务器状态。
            if (Util.getMillis() - aiRequestStartMillis > 8_000L) {
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

    /** 3 张方案卡：摘要 + 强度 + 材料数；悬停高亮，点击放料。 */
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
            var proposal = proposals.get(i);
            String summary = proposal.summary() == null ? "" : proposal.summary();
            g.drawString(this.font, this.font.plainSubstrByWidth(summary, CARD_W - 4),
                    x + 2, y + 3, 0xFFFFFF, false);
            g.drawString(this.font,
                    Component.translatable("qianxiang.forge_table.preview.power",
                            String.format("%.1f", proposal.estimatedPower())),
                    x + 2, y + 14, 0xFFAA00, false);
            g.drawString(this.font,
                    this.font.plainSubstrByWidth(String.join(", ",
                                    proposal.materialNames().stream().limit(3).toList()),
                            CARD_W - 4),
                    x + 2, y + 25, 0x888888, false);
        }
    }

    // ============================ 交互 ============================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (lastAiResult != null && button == 0) {
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
                request, TARGET_TYPE, TARGET_TIER, collectCurrentMaterials(), "recommend"));
    }

    /** 发送「AI 修改确认」请求到服务端。 */
    private void sendConfirmRequest() {
        String request = this.requestBox.getValue().trim();
        status = Status.PARSING;
        aiRequestStartMillis = Util.getMillis();
        PacketDistributor.sendToServer(new AiRequestPayload(
                request, TARGET_TYPE, TARGET_TIER, collectCurrentMaterials(), "confirm"));
    }

    private void clearRequest() {
        this.requestBox.setValue("");
        this.lastAiResult = null;
        this.status = Status.IDLE;
        ClientAlchemyTableAI.reportProposalIndex(
                com.qianxiang.network.SpellJsonReportPayload.NONE);
    }

    /** 点击某张方案卡：先回传「选了第几条」，再请求服务端放料（包序保证先记选择）。 */
    private void applyProposal(int index) {
        if (lastAiResult == null) return;
        var proposal = lastAiResult.proposals().get(index);
        if (proposal.materialNames().isEmpty()) return;

        ClientAlchemyTableAI.reportProposalIndex(index);
        PacketDistributor.sendToServer(new AiPlaceMaterialsPayload(proposal.materialNames()));
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
