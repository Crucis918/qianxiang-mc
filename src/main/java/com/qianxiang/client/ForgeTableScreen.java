package com.qianxiang.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import com.qianxiang.ai.FallbackRecipes;
import com.qianxiang.ai.MaterialLibrary;
import com.qianxiang.ai.PhaseAIRecipeService;
import com.qianxiang.blueprint.BlueprintData;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiPlaceMaterialsPayload;
import com.qianxiang.network.AiRequestPayload;
import com.qianxiang.network.BlueprintListRequestPayload;
import com.qianxiang.network.BlueprintSavePayload;
import com.qianxiang.network.BlueprintUsePayload;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.PhaseData;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 相之凝结台 UI（256×256 扩大版）。
 * <p>
 * 主窗口 256×256：
 * <ul>
 *   <li>顶部右侧：加宽自然语言输入框；其下方为 24×14 类型/档位图标切换按钮</li>
 *   <li>左上：10 个材料槽 2 行 5 列（间距 18），18×18 色环（槽0 核心-青 / 槽1-4 辅助-紫 / 槽5-9 基底-橙）</li>
 *   <li>材料槽下方：状态条（10px 高，状态文字直接绘制在条内）</li>
 *   <li>中部中间：问 AI / 清空 / AI 修改确认操作按钮</li>
 *   <li>中部右侧：32×32 结果槽，未放齐材料时显示目标产物半透明预览，放齐后保留放大+抠洞+关键材料旋转特效</li>
 *   <li>下方：2 列网格 AI 推荐配方卡片（产物图标 + 材料图标 + 强度 + 说明）</li>
 *   <li>底部：玩家背包</li>
 * </ul>
 * 右侧扩展面板（x=260，位于主窗口右侧，不遮挡主窗口）：
 * 千相蓝图面板（8 条，含强度/材料数）+ 历史记录区（6 条）+ 底部「AI 设置」按钮
 * （打开 {@link ForgeAIConfigScreen}，返回时容器保持打开），整个面板可折叠/展开。
 */
public class ForgeTableScreen extends AbstractContainerScreen<ForgeTableMenu> {
    /** GUI 背景纹理。 */
    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("qianxiang", "textures/gui/forge_table.png");

    private static final String[] TYPES = {"weapon", "magic", "armor", "tool"};
    private static final String[] TIERS = {"common", "rare", "epic", "legendary"};

    // ========================== 主窗口布局（256×256） ==========================
    /** 自然语言输入框（材料槽右侧，与第一行材料槽同排）。 */
    private static final int INPUT_X = 104;
    private static final int INPUT_Y = 17;
    private static final int INPUT_W = 144;
    private static final int INPUT_H = 14;

    /** 类型/档位图标按钮（输入框下方，24×14）。 */
    private static final int TYPE_X = 104;
    private static final int TYPE_Y = 35;
    private static final int TYPE_W = 24;
    private static final int TYPE_H = 14;
    private static final int TIER_X = 132;
    private static final int TIER_Y = 35;
    private static final int TIER_W = 24;
    private static final int TIER_H = 14;

    /** 状态条（材料网格下方，状态文字绘制在条内）。 */
    private static final int STATUS_X = 8;
    private static final int STATUS_Y = 108;
    private static final int STATUS_W = 132;
    private static final int STATUS_H = 10;

    // 「去格子化」：材料网格退役，左上材料区改文字列表（材料名×数量）。
    // 槽位数据模型不变（menu 槽位坐标已挪屏外，快速移动按索引工作）。
    /** 材料列表：左上角起点与行距。 */
    private static final int MATLIST_X = 8;
    private static final int MATLIST_Y = 17;
    private static final int MATLIST_ROW_H = 9;
    private static final int MATLIST_MAX_ROWS = 8;
    /** 三行操作提示首行 y（0.6 缩放小字、行距 6px：列表区底部与状态条之间）。 */
    private static final int MATLIST_HINT_Y = 90;

    /** 结果槽坐标（逻辑槽 16×16，视觉渲染为 32×32）。 */
    private static final int RESULT_SLOT_X = 222;
    private static final int RESULT_SLOT_Y = 54;

    /** 操作按钮（材料槽与结果槽之间）。 */
    private static final int ASK_X = 146;
    private static final int ASK_Y = 50;
    private static final int CLEAR_X = 146;
    private static final int CLEAR_Y = 66;
    private static final int CONFIRM_X = 146;
    private static final int CONFIRM_Y = 82;
    private static final int BUTTON_W = 58;
    private static final int BUTTON_H = 13;
    private static final int CONFIRM_W = 58;

    /** AI 推荐配方卡片（2 列网格，材料网格与状态条下方、背包上方）。 */
    private static final int CARD_X = 8;
    private static final int CARD_Y = 132;
    private static final int CARD_W = 116;
    private static final int CARD_H = 20;
    private static final int CARD_GAP_X = 8;
    private static final int CARD_GAP_Y = 2;
    private static final int CARD_COLS = 2;
    private static final int MAX_CARDS = 4;

    /** 材料即时预览面板（结果槽右下方始终空闲的窄带，x=205..253，y=79..102）。 */
    private static final int PREVIEW_X = 205;
    private static final int PREVIEW_Y = 79;
    private static final int PREVIEW_W = 48;
    private static final int PREVIEW_H = 23;

    /** AI 反问选项 chips：与「AI 推荐」标题同排（标题右侧），点击追加到输入框并重问 AI。 */
    private static final int SUGGEST_Y = CARD_Y - 11;
    private static final int SUGGEST_H = 10;
    private static final int SUGGEST_GAP = 3;
    private static final int SUGGEST_MAX = 3;

    /** 材料贡献明细面板：主窗口左侧外挂（与扩展面板同理不占主窗口），宽度与顶边。 */
    private static final int CONTRIB_PANEL_W = 108;
    private static final int CONTRIB_PANEL_GAP = 4;
    private static final int CONTRIB_PANEL_Y = 46;
    /** 面板文字缩放：标题/引导行 0.75，材料行 0.6。 */
    private static final float CONTRIB_TITLE_SCALE = 0.75f;
    private static final float CONTRIB_ROW_SCALE = 0.6f;

    /** 关键词提示行：半字号小字填在右列输入框/按钮组下方的缝隙里（不撞左侧材料网格）。 */
    private static final float HINT_SCALE = 0.45f;
    private static final int HINT_X = 104;
    private static final float HINT_LINE1_Y = 104.5f;
    private static final float HINT_LINE2_Y = 114.5f;
    private static final int HINT_W = 100;
    private static final int HINT_MAX_LINES = 2;

    // ========================== 右侧扩展面板布局 ==========================
    /** 扩展面板整体区域（主窗口 256 宽时，面板从 x=260 开始，与主窗口间隔 4px）。 */
    private static final int EXT_PANEL_X = 260;
    private static final int EXT_PANEL_W = 108;
    /** 面板背景纵向范围。 */
    private static final int EXT_PANEL_BG_TOP = 4;
    private static final int EXT_PANEL_BG_BOTTOM = 230;

    /** 蓝图面板。 */
    private static final int BLUEPRINT_PANEL_X = EXT_PANEL_X;
    private static final int BLUEPRINT_PANEL_Y = 8;
    private static final int BLUEPRINT_LINE_H = 9;
    private static final int BLUEPRINT_MAX_VISIBLE = 8;

    /** 历史记录折叠区（位于蓝图按钮组下方）。 */
    private static final int HISTORY_PANEL_X = EXT_PANEL_X;
    private static final int HISTORY_PANEL_Y = 142;
    private static final int HISTORY_LINE_H = 9;
    private static final int HISTORY_MAX_ENTRIES = 6;

    /** 「AI 设置」按钮（扩展面板底部）。 */
    private static final int AI_SETTINGS_X = EXT_PANEL_X;
    private static final int AI_SETTINGS_Y = 196;
    private static final int AI_SETTINGS_W = 64;

    /** 「动作编辑」按钮（扩展面板最底行，AI 设置/材料筛选之下）：打开连击编辑器。 */
    private static final int MOVESET_EDIT_X = EXT_PANEL_X;
    private static final int MOVESET_EDIT_Y = 212;

    private EditBox requestBox;
    /** 「示例」按钮（轮换填入示例需求）。 */
    private Button exampleButton;
    /** 示例轮换下标。 */
    private int exampleIndex = 0;
    private Button askButton;
    private Button clearButton;
    private Button typeButton;
    private Button tierButton;
    private Button confirmButton;
    private Button saveBlueprintButton;
    private Button useBlueprintButton;
    private Button prevBlueprintButton;
    private Button nextBlueprintButton;
    /** 扩展面板折叠/展开切换按钮。 */
    private Button togglePanelButton;
    /** 「说明」按钮（扩展面板顶部，打开锻造说明书）。 */
    private Button guideButton;

    /** 说明书模板待回填的描述文本（init 重建输入框后应用）。 */
    private String pendingGuideText = null;
    /** 说明书模板待切换的产物类型。 */
    private String pendingGuideType = null;
    /** 「AI 设置」按钮（扩展面板底部）。 */
    private Button aiSettingsButton;
    /** 「材料筛选」按钮（扩展面板底部，AI 设置右侧）。 */
    private Button materialFilterButton;
    /** 「动作编辑」按钮（扩展面板最底行）：打开连击编辑器 {@link MovesetEditorScreen}。 */
    private Button movesetEditorButton;
    /** 「开始创作」按钮（产物就绪时显示，点击触发合成仪式）。 */
    private Button beginCraftButton;
    /** 「全部取回」按钮（材料区标题旁，材料为空/仪式启动后置灰）。 */
    private Button retrieveAllButton;
    /** 「自动备料」按钮（有 AI 方案时显示，一键放料）。 */
    private Button autoPlaceButton;
    /** 已发仪式请求、等服务端关 GUI 的窗口期：禁用一切投入/取回点击（服务端仍权威拒判）。 */
    private boolean awaitingRitual = false;

    private int typeIndex = 0;  // weapon
    private int tierIndex = 1;  // rare

    /** 当前显示的 AI 推荐结果。 */
    private ClientForgeTableAI.AiResult lastAiResult = null;
    /** 用于旋转动画的角度（每帧递增）。 */
    private float spinAngle = 0f;
    /** 高亮背包的截止时刻（{@link Util#getMillis()}，无空材料槽时点方案触发）。
     *  用墙钟而非按帧递减——144FPS 下 60 帧只剩 0.4 秒，玩家根本看不见（WQ-80②，同类 WQ-24）。 */
    private long highlightUntilMillis = 0L;
    /** 当前鼠标悬停的配方卡片索引，-1 表示无。 */
    private int hoveredCard = -1;
    /** 玩家点选过的方案卡索引（-1=无）：渲染金色选中边框（WQ-76 选中高亮）。 */
    private int selectedCard = -1;
    /** 点选发生时的 AI 结果实例：新响应到达后旧卡已不在列表里，高亮随之消失（选择本身粘性保留在服务端）。 */
    private ClientForgeTableAI.AiResult selectedCardResult = null;

    /** 客户端缓存的蓝图列表。 */
    private List<BlueprintData> clientBlueprints = new ArrayList<>();
    /** 当前选中的蓝图索引。 */
    private int selectedBlueprint = -1;
    /** 蓝图面板可视窗滚动偏移（WQ-78：>8 条时选中项须夹进可视窗，不许滚出面板仍被误用）。 */
    private int blueprintScroll = 0;
    /** 右侧扩展面板是否展开（折叠后只留一个展开小按钮，返回主界面视角）。 */
    private boolean extPanelExpanded = true;

    // ========================== 状态与历史 ==========================
    private enum Status {
        IDLE(0xFF888888),
        PARSING(0xFF00AAFF),
        READY(0xFF55FF55),
        FORGING(0xFFFFFF00),
        COMPLETE(0xFFFFAA00);

        final int color;

        Status(int color) { this.color = color; }
    }

    private Status status = Status.IDLE;
    private int statusPulse = 0;

    /** 材料即时预览：材料槽签名 + 对应的 ForgeComposer 预估（签名不变不重算）。 */
    private int previewKey = Integer.MIN_VALUE;
    private boolean previewHasMaterials = false;
    private ForgeComposer.Composition previewComposition = null;

    /** 材料贡献明细面板：每个非空材料槽一行（与 {@link #previewKey} 同签名缓存，材料变动即时刷新）。 */
    private List<ContribRow> contributionRows = List.of();
    /** 拼装引导：有材料但无任何 BASE_* 骨架时为 true。 */
    private boolean contribMissingBase = false;
    /** 拼装引导：有骨架但无任何效果算子/自由效果时为 true。 */
    private boolean contribMissingEffect = false;

    /** 贡献明细面板一行：材料显示名 + 性质解析结果。 */
    private record ContribRow(String name, MaterialCardHelper.MaterialInfo info) {}

    /** AI 请求发出时刻（{@link Util#getMillis()}），-1 表示当前无进行中请求。 */
    private long aiRequestStartMillis = -1L;
    /** 状态条闪烁截止时刻（{@link Util#getMillis()}）与颜色（响应到达=绿 / 超时回退=黄）。
     *  毫秒截止而非按帧递减——高刷屏下按帧递减会一闪即逝（WQ-80②）。 */
    private long statusFlashUntilMillis = 0L;
    private int statusFlashColor = 0xFF55FF55;
    /** 上一帧状态，用于检测 PARSING → 其他 的跳变。 */
    private Status prevStatusForFlash = Status.IDLE;
    /** PARSING 超时兜底（WQ-74）：AI 超时上限 30s×2（本体+重试）+10s 余量。 */
    private static final long PARSING_TIMEOUT_MS = 70_000L;

    /** 关键词即时提示：上次参与计算的输入与算出的提示行（输入不变不重算）。 */
    private String lastHintInput = null;
    private List<Component> hintLines = List.of();

    /** 客户端历史记录缓存（跨 UI 打开保留）。切换世界时由 {@link ClientStateReset} 清空。 */
    private static final List<HistoryEntry> HISTORY = new ArrayList<>();
    private boolean historyExpanded = false;

    /** 切换世界/断线时清空历史（登记在 {@link ClientStateReset#resetAll}，WQ-79①）。 */
    public static void clearHistory() {
        HISTORY.clear();
    }

    private record HistoryEntry(String request, String type, String tier, long time) {}

    public ForgeTableScreen(ForgeTableMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.imageWidth = 256;
        this.imageHeight = 256;
        this.titleLabelY = 6;
        this.inventoryLabelY = 166;
    }

    @Override
    protected void init() {
        // WQ-77：子页面返回/窗口 resize 都会重走 init 重建输入框——先暂存旧值，末尾回填。
        String prevRequestText = this.requestBox == null ? null : this.requestBox.getValue();
        super.init();

        // 自然语言输入框
        this.requestBox = new EditBox(this.font, leftPos + INPUT_X, topPos + INPUT_Y, INPUT_W, INPUT_H,
                Component.translatable("qianxiang.forge_table.hint.request"));
        this.requestBox.setMaxLength(80);
        this.requestBox.setHint(Component.translatable("qianxiang.forge_table.hint.request"));
        this.addRenderableWidget(this.requestBox);

        // 「示例」按钮（输入框下方空档）：点击轮换填入 3 条示例需求，帮玩家起步
        this.exampleButton = Button.builder(
                        Component.translatableWithFallback("qianxiang.ai.example_button", "示例"),
                        b -> cycleExample())
                .pos(leftPos + 168, topPos + 35)
                .size(36, 14)
                .build();
        this.addRenderableWidget(this.exampleButton);

        // 产物类型图标按钮
        this.typeButton = Button.builder(Component.empty(), b -> cycleType())
                .pos(leftPos + TYPE_X, topPos + TYPE_Y)
                .size(TYPE_W, TYPE_H)
                .build();
        this.addRenderableWidget(this.typeButton);

        // 强度档位图标按钮
        this.tierButton = Button.builder(Component.empty(), b -> cycleTier())
                .pos(leftPos + TIER_X, topPos + TIER_Y)
                .size(TIER_W, TIER_H)
                .build();
        this.addRenderableWidget(this.tierButton);

        // 问 AI 按钮
        this.askButton = Button.builder(Component.translatable("qianxiang.forge_table.button.ask"), b -> sendAiRequest())
                .pos(leftPos + ASK_X, topPos + ASK_Y)
                .size(BUTTON_W, BUTTON_H)
                .build();
        this.addRenderableWidget(this.askButton);

        // 清空按钮
        this.clearButton = Button.builder(Component.translatable("qianxiang.forge_table.button.clear"), b -> clearRequest())
                .pos(leftPos + CLEAR_X, topPos + CLEAR_Y)
                .size(BUTTON_W, BUTTON_H)
                .build();
        this.addRenderableWidget(this.clearButton);

        // AI 修改确认按钮（材料槽非空时启用）
        this.confirmButton = Button.builder(Component.translatable("qianxiang.forge_table.button.confirm"), b -> sendConfirmRequest())
                .pos(leftPos + CONFIRM_X, topPos + CONFIRM_Y)
                .size(CONFIRM_W, BUTTON_H)
                .build();
        this.addRenderableWidget(this.confirmButton);

        updateTypeButton();
        updateTierButton();

        // 蓝图面板按钮
        int bpx = leftPos + BLUEPRINT_PANEL_X;
        int bpy = topPos + BLUEPRINT_PANEL_Y + 12 + BLUEPRINT_MAX_VISIBLE * BLUEPRINT_LINE_H + 2;
        this.saveBlueprintButton = Button.builder(Component.translatable("qianxiang.forge_table.button.save_blueprint"), b -> sendSaveBlueprint())
                .pos(bpx, bpy)
                .size(64, TYPE_H)
                .build();
        this.addRenderableWidget(this.saveBlueprintButton);

        this.useBlueprintButton = Button.builder(Component.translatable("qianxiang.forge_table.button.use_blueprint"), b -> sendUseBlueprint())
                .pos(bpx, bpy + 14)
                .size(64, TYPE_H)
                .build();
        this.addRenderableWidget(this.useBlueprintButton);

        this.prevBlueprintButton = Button.builder(Component.literal("<"), b -> cycleBlueprint(-1))
                .pos(bpx, bpy + 28)
                .size(30, TYPE_H)
                .build();
        this.addRenderableWidget(this.prevBlueprintButton);

        this.nextBlueprintButton = Button.builder(Component.literal(">"), b -> cycleBlueprint(1))
                .pos(bpx + 34, bpy + 28)
                .size(30, TYPE_H)
                .build();
        this.addRenderableWidget(this.nextBlueprintButton);

        // 扩展面板折叠/展开切换按钮（始终可见，折叠时移到面板左缘）
        this.togglePanelButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.panel.collapse"), b -> toggleExtPanel())
                .pos(leftPos + EXT_PANEL_X + EXT_PANEL_W - 13, topPos + EXT_PANEL_BG_TOP)
                .size(12, TYPE_H)
                .build();
        this.addRenderableWidget(this.togglePanelButton);

        // 「说明」按钮（扩展面板顶部，蓝图标题与折叠按钮之间；折叠面板时隐藏）
        this.guideButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.guide"),
                        b -> openGuide())
                .pos(leftPos + EXT_PANEL_X + 52, topPos + EXT_PANEL_BG_TOP)
                .size(40, TYPE_H)
                .build();
        this.addRenderableWidget(this.guideButton);

        // 说明书模板回填：setScreen 会触发 init 重建输入框，因此在 init 末尾应用暂存值
        if (this.pendingGuideType != null) {
            for (int i = 0; i < TYPES.length; i++) {
                if (TYPES[i].equals(this.pendingGuideType)) {
                    this.typeIndex = i;
                    break;
                }
            }
            updateTypeButton();
            this.pendingGuideType = null;
        }
        if (this.pendingGuideText != null) {
            this.requestBox.setValue(this.pendingGuideText);
            this.pendingGuideText = null;
        } else if (prevRequestText != null) {
            // WQ-77：无说明书模板时回填 resize/子页面往返前的需求文本
            this.requestBox.setValue(prevRequestText);
        }

        // 「AI 设置」按钮（扩展面板底部）：打开 ForgeAIConfigScreen 配置小页面
        this.aiSettingsButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.ai_settings"), b -> openAiConfig())
                .pos(leftPos + AI_SETTINGS_X, topPos + AI_SETTINGS_Y)
                .size(AI_SETTINGS_W, TYPE_H)
                .build();
        this.addRenderableWidget(this.aiSettingsButton);

        // 「材料筛选」按钮（AI 设置右侧）：打开材料勾选表格 ForgeMaterialFilterScreen
        this.materialFilterButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.material_filter"),
                        b -> openMaterialFilter())
                .pos(leftPos + AI_SETTINGS_X + AI_SETTINGS_W + 4, topPos + AI_SETTINGS_Y)
                .size(EXT_PANEL_W - AI_SETTINGS_W - 4, TYPE_H)
                .build();
        this.addRenderableWidget(this.materialFilterButton);

        // 「动作编辑」按钮（扩展面板最底行整宽）：打开连击编辑器 MovesetEditorScreen
        this.movesetEditorButton = Button.builder(
                        Component.translatable("qianxiang.forge_table.button.moveset_editor"),
                        b -> openMovesetEditor())
                .pos(leftPos + MOVESET_EDIT_X, topPos + MOVESET_EDIT_Y)
                .size(EXT_PANEL_W, TYPE_H)
                .build();
        this.addRenderableWidget(this.movesetEditorButton);

        // 「开始创作」按钮：产物就绪时显示（操作按钮组下方），点击发仪式请求
        this.beginCraftButton = Button.builder(
                        Component.translatable("qianxiang.table.begin_craft"),
                        b -> {
                            awaitingRitual = true; // 仪式启动窗口期禁用投入/取回
                            PacketDistributor.sendToServer(new com.qianxiang.network.RitualStartPayload());
                        })
                .pos(leftPos + 146, topPos + 100)
                .size(58, 13)
                .build();
        this.addRenderableWidget(this.beginCraftButton);

        // 「自动备料」按钮（操作按钮组下方）：对当前选中方案（或第 0 方案）一键放料
        this.autoPlaceButton = Button.builder(
                        Component.translatableWithFallback("qianxiang.ai.auto_place", "自动备料"),
                        b -> autoPlace())
                .pos(leftPos + 146, topPos + 116)
                .size(58, 13)
                .build();
        this.addRenderableWidget(this.autoPlaceButton);

        // 「全部取回」按钮（材料区标题旁）：取回全部材料（slotIndex=-1 + all）
        this.retrieveAllButton = Button.builder(
                        Component.translatable("qianxiang.table.retrieve_all"),
                        b -> PacketDistributor.sendToServer(
                                new com.qianxiang.network.TableRetrievePayload(-1, true)))
                .pos(leftPos + 44, topPos + 4)
                .size(56, 12)
                .build();
        this.addRenderableWidget(this.retrieveAllButton);

        // 注册 AI 结果监听器
        ClientForgeTableAI.setListener(result -> {
            this.lastAiResult = result;
            this.status = (result != null && !result.proposals().isEmpty()) ? Status.READY : Status.IDLE;
        });
        this.lastAiResult = ClientForgeTableAI.getLastResult();
        this.status = (lastAiResult != null && !lastAiResult.proposals().isEmpty()) ? Status.READY : Status.IDLE;

        // 注册蓝图列表监听器并请求同步
        ClientBlueprintCache.setListener(list -> {
            this.clientBlueprints = new ArrayList<>(list);
            if (this.selectedBlueprint >= this.clientBlueprints.size()) {
                this.selectedBlueprint = this.clientBlueprints.isEmpty() ? -1 : 0;
            } else if (this.selectedBlueprint < 0 && !this.clientBlueprints.isEmpty()) {
                this.selectedBlueprint = 0;
            }
            clampBlueprintScroll();
        });
        this.clientBlueprints = new ArrayList<>(ClientBlueprintCache.get());
        // WQ-77：蓝图选中项跨 init 保留——只在越界/未选时归位，不再无条件打回第 0 条
        if (this.clientBlueprints.isEmpty()) {
            this.selectedBlueprint = -1;
        } else if (this.selectedBlueprint < 0 || this.selectedBlueprint >= this.clientBlueprints.size()) {
            this.selectedBlueprint = 0;
        }
        clampBlueprintScroll();
        PacketDistributor.sendToServer(new BlueprintListRequestPayload());
    }

    @Override
    public void onClose() {
        ClientForgeTableAI.clearListener();
        ClientBlueprintCache.clearListener();
        super.onClose();
    }

    /**
     * 切到「AI 设置」页时为 true：setScreen 触发 removed() 时跳过容器清理，
     * 从设置页返回时工作台容器仍然开着（menu/槽位状态不变）。
     */
    private boolean switchingToAiConfig = false;

    /** 打开「AI 设置」配置小页面（{@link ForgeAIConfigScreen}），返回时回到本界面。 */
    private void openAiConfig() {
        switchingToAiConfig = true;
        this.minecraft.setScreen(new ForgeAIConfigScreen(this));
    }

    /**
     * 切到「说明书」页时为 true：与 {@link #switchingToAiConfig} 同理，
     * setScreen 触发 removed() 时跳过容器清理，从说明书返回时容器仍开着。
     */
    private boolean switchingToGuide = false;

    /** 打开锻造说明书（{@link ForgeGuideScreen}），返回时回到本界面。 */
    private void openGuide() {
        switchingToGuide = true;
        this.minecraft.setScreen(new ForgeGuideScreen(this));
    }

    /**
     * 切到「材料筛选」页时为 true：与 {@link #switchingToAiConfig} 同理，
     * 从筛选页返回时工作台容器仍开着。
     */
    private boolean switchingToMaterialFilter = false;

    /** 打开材料勾选表格（{@link ForgeMaterialFilterScreen}），返回时回到本界面。 */
    private void openMaterialFilter() {
        switchingToMaterialFilter = true;
        this.minecraft.setScreen(new ForgeMaterialFilterScreen(this));
    }

    /**
     * 切到「动作编辑」页时为 true：与 {@link #switchingToAiConfig} 同理，
     * 从连击编辑器返回时工作台容器仍开着。
     */
    private boolean switchingToMovesetEditor = false;

    /** 打开连击编辑器（{@link MovesetEditorScreen}），返回时回到本界面。 */
    private void openMovesetEditor() {
        switchingToMovesetEditor = true;
        this.minecraft.setScreen(new MovesetEditorScreen(this));
    }

    @Override
    public void removed() {
        if (switchingToAiConfig) {
            // 只是切到子设置页：不触发 AbstractContainerScreen.removed 的容器清理
            switchingToAiConfig = false;
            return;
        }
        if (switchingToGuide) {
            // 只是切到说明书页：同上，不清理容器
            switchingToGuide = false;
            return;
        }
        if (switchingToMaterialFilter) {
            // 只是切到材料筛选页：同上，不清理容器
            switchingToMaterialFilter = false;
            return;
        }
        if (switchingToMovesetEditor) {
            // 只是切到动作编辑页：同上，不清理容器
            switchingToMovesetEditor = false;
            return;
        }
        super.removed();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(BG_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xFFE0E0E0, false);
        // 背包标签不画：卡片第二行（y≈154..174）已占满背包上方的标签位。
    }

    /**
     * 材料槽（0-9）的 tooltip 由 {@link #renderMaterialSlotTooltips} 统一绘制：
     * 有物品时显示完整材料性质卡，空槽显示槽位角色——
     * 这里跳过原版实现，避免与原版物品 tooltip 叠成双份。
     * 结果槽与玩家背包仍走原版逻辑。
     */
    @Override
    protected void renderTooltip(GuiGraphics g, int x, int y) {
        if (this.hoveredSlot != null && this.hoveredSlot.index < ForgeTableMenu.MATERIAL_SLOTS) {
            return;
        }
        super.renderTooltip(g, x, y);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.spinAngle += partialTick * 2f;
        this.statusPulse++;

        updateStatusFromSlots();
        updatePreview();
        updateKeywordHints();
        updateStatusFlash();
        this.confirmButton.visible = hasMaterialsInSlots();
        this.confirmButton.active = hasMaterialsInSlots();
        // 「开始创作」只在产物就绪时显示
        this.beginCraftButton.visible = !this.menu.getSlot(ForgeTableMenu.RESULT_SLOT).getItem().isEmpty();
        // 「全部取回」：材料为空或仪式启动窗口期置灰
        this.retrieveAllButton.active = hasMaterialsInSlots() && !awaitingRitual;
        // 「自动备料」：有 AI 方案才显示
        this.autoPlaceButton.visible = lastAiResult != null
                && !lastAiResult.proposals().isEmpty() && !awaitingRitual;
        updatePanelVisibility();

        super.render(g, mouseX, mouseY, partialTick);

        renderMaterialList(g);
        renderStatusBar(g);
        renderSuggestionLine(g);
        renderKeywordHints(g);
        renderResultSlotPreview(g);
        renderResultSlotEffect(g);
        renderRecipeCards(g, mouseX, mouseY);
        renderSuggestChips(g, mouseX, mouseY);
        renderMaterialPreview(g);
        renderContributionPanel(g);
        renderMaterialSlotTooltips(g, mouseX, mouseY);
        renderInventoryHighlight(g);
        if (extPanelExpanded) {
            renderExtPanelBackground(g);
            renderBlueprintPanel(g);
            renderHistoryPanel(g, mouseX, mouseY);
        } else {
            renderExtPanelTab(g);
        }
        renderButtonTooltips(g, mouseX, mouseY);
        renderPreviewTooltip(g, mouseX, mouseY);
    }

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
            String suggestion = hitTestSuggestion(mouseX, mouseY);
            if (suggestion != null) {
                applySuggestion(suggestion);
                return true;
            }
            // 材料条目区域优先于整张卡：点单个材料 = 只放入该材料
            int[] cardMaterial = hitTestCardMaterial(mouseX, mouseY);
            if (cardMaterial != null) {
                applyProposalMaterial(cardMaterial[0], cardMaterial[1]);
                return true;
            }
            int clickedCard = hitTestCard(mouseX, mouseY);
            if (clickedCard >= 0 && lastAiResult != null && clickedCard < lastAiResult.proposals().size()) {
                applyProposal(clickedCard);
                return true;
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
                    && this.hoveredSlot.index >= ForgeTableMenu.RESULT_SLOT + 1
                    && this.hoveredSlot.hasItem()) {
                PacketDistributor.sendToServer(
                        new com.qianxiang.network.TableInsertPayload(this.hoveredSlot.index, false));
                return true;
            }
            if (extPanelExpanded && hitTestHistoryHeader(mouseX, mouseY)) {
                historyExpanded = !historyExpanded;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 键盘输入优先给输入框：原版 {@code AbstractContainerScreen.keyPressed}
     * 会先匹配物品栏键（默认 E）直接关容器——打字时按到 e 就闪退；
     * 数字键还会触发快捷栏切换。修复：输入框聚焦时，按键一律先给输入框，
     * 字符键返回 false 走 charTyped 正常输入；ESC 只取消焦点不关闭。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.requestBox != null && this.requestBox.isFocused()) {
            if (keyCode == 256) { // ESC：取消输入框焦点，不关闭容器
                this.requestBox.setFocused(false);
                return true;
            }
            // 输入框自己消费特殊键（方向键/删除/回车/复制粘贴等）
            if (this.requestBox.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
            // 字符键（字母/数字/符号，含物品栏键 E）：不走 super 的物品栏/快捷栏逻辑，
            // 返回 false 让 charTyped 把字符输入到输入框
            return false;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 根据当前材料槽/结果槽状态刷新 status。 */
    private void updateStatusFromSlots() {
        if (status == Status.PARSING) {
            // WQ-74 超时兜底：超过 AI 超时上限×2+余量仍无回包（旧服务端静默限流/丢包），
            // 强制退出 PARSING——此前只能关界面重开。
            if (aiRequestStartMillis >= 0L
                    && Util.getMillis() - aiRequestStartMillis > PARSING_TIMEOUT_MS) {
                status = Status.IDLE;
                aiRequestStartMillis = -1L;
            }
            return; // 等待服务端回包
        }

        ItemStack result = this.menu.getSlot(ForgeTableMenu.RESULT_SLOT).getItem();
        if (!result.isEmpty()) {
            status = Status.COMPLETE;
            return;
        }

        if (hasMaterialsInSlots()) {
            status = (lastAiResult != null && !lastAiResult.proposals().isEmpty()) ? Status.FORGING : Status.IDLE;
        } else {
            status = (lastAiResult != null && !lastAiResult.proposals().isEmpty()) ? Status.READY : Status.IDLE;
        }
    }

    /** 发送 AI 推荐请求到服务端。 */
    private void sendAiRequest() {
        String request = this.requestBox.getValue().trim();
        addHistoryEntry(request);
        status = Status.PARSING;
        aiRequestStartMillis = Util.getMillis();
        PacketDistributor.sendToServer(new AiRequestPayload(
                ClientForgeTableAI.nextRequestSeq(),
                request, TYPES[typeIndex], TIERS[tierIndex], collectCurrentMaterials(), "recommend",
                collectAllowedMaterials()));
    }

    /** 发送「AI 修改确认」请求到服务端。 */
    private void sendConfirmRequest() {
        String request = this.requestBox.getValue().trim();
        addHistoryEntry(request);
        status = Status.PARSING;
        aiRequestStartMillis = Util.getMillis();
        PacketDistributor.sendToServer(new AiRequestPayload(
                ClientForgeTableAI.nextRequestSeq(),
                request, TYPES[typeIndex], TIERS[tierIndex], collectCurrentMaterials(), "confirm",
                collectAllowedMaterials()));
    }

    /** 「材料筛选」勾选的白名单（空 = 不限制）；异常时退化为不限制。 */
    private List<String> collectAllowedMaterials() {
        try {
            if (this.minecraft != null && this.minecraft.player != null) {
                return ClientMaterialFilter.buildWhitelist(this.minecraft.player.getInventory());
            }
        } catch (Throwable ignored) {
            // 白名单构建失败不该阻塞 AI 请求
        }
        return List.of();
    }

    /** 收集当前材料槽内物品的 registry name。 */
    private List<String> collectCurrentMaterials() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            ItemStack s = this.menu.getSlot(i).getItem();
            if (s.isEmpty()) continue;
            names.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        }
        return names;
    }

    /** 材料槽是否已有材料。 */
    private boolean hasMaterialsInSlots() {
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            if (!this.menu.getSlot(i).getItem().isEmpty()) return true;
        }
        return false;
    }

    /** 清空输入与结果。 */
    private void clearRequest() {
        this.requestBox.setValue("");
        this.lastAiResult = null;
        this.status = Status.IDLE;
        this.aiRequestStartMillis = -1L;
        this.statusFlashUntilMillis = 0L;
        this.selectedCard = -1;
        this.selectedCardResult = null;
        // WQ-73①：不要在这里 clearListener+setListener 重注册——setListener 会同步回放
        // static lastResult，旧推荐卡一帧不消失地原样塞回。init 注册的 listener 从未被
        // 移除（onClose 才清），这里无需任何重注册。
        // WQ-73②：同时撤销服务端已记的提案选择——否则玩家清空后手动改材料，
        // 产物仍带着已清空的 AI 法术与自定义名。
        ClientForgeTableAI.reportProposalIndex(
                com.qianxiang.network.SpellJsonReportPayload.NONE);
    }

    private void cycleType() {
        typeIndex = (typeIndex + 1) % TYPES.length;
        updateTypeButton();
    }

    private void cycleTier() {
        tierIndex = (tierIndex + 1) % TIERS.length;
        updateTierButton();
    }

    private void updateTypeButton() {
        this.typeButton.setMessage(Component.translatable("qianxiang.forge_table.type_icon." + TYPES[typeIndex]));
    }

    private void updateTierButton() {
        this.tierButton.setMessage(Component.translatable("qianxiang.forge_table.tier_icon." + TIERS[tierIndex]));
    }

    // ========================== 材料列表（去格子化：网格退役，改文字列表） ==========================

    /** 去格子化：材料槽不再画物品（坐标已挪屏外双保险），产物槽/背包走原版。 */
    @Override
    protected void renderSlot(GuiGraphics g, net.minecraft.world.inventory.Slot slot) {
        if (slot.index < ForgeTableMenu.MATERIAL_SLOTS) {
            return;
        }
        super.renderSlot(g, slot);
    }

    /** 材料列表行：真实槽位下标 + 物品栈（取回 payload 要用槽位下标，不能用行号）。 */
    private record MatRow(int slot, ItemStack stack) {}

    /** 当前非空材料槽的物品列表（展示/命中测试共用，按槽序）。 */
    private List<MatRow> materialListRows() {
        List<MatRow> rows = new ArrayList<>();
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            ItemStack s = this.menu.getSlot(i).getItem();
            if (!s.isEmpty()) rows.add(new MatRow(i, s));
        }
        return rows;
    }

    /** 材料区文字列表：材料名×数量，超出省略；行 hover 高亮（点击取回）；底部投入提示行。 */
    private void renderMaterialList(GuiGraphics g) {
        List<MatRow> rows = materialListRows();
        int shown = Math.min(rows.size(), MATLIST_MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            int rx = leftPos + MATLIST_X;
            int ry = topPos + MATLIST_Y + i * MATLIST_ROW_H;
            boolean hover = this.minecraft != null
                    && mouseInRect(rx, ry, 92, MATLIST_ROW_H);
            if (hover) {
                g.fill(rx - 1, ry - 1, rx + 92, ry + MATLIST_ROW_H, 0x20FFFFFF);
            }
            ItemStack s = rows.get(i).stack();
            String line = s.getHoverName().getString() + " ×" + s.getCount();
            g.drawString(this.font, this.font.plainSubstrByWidth(line, 88),
                    rx, ry, hover ? 0xFFFFFF : 0xE0E0E0, false);
        }
        if (rows.size() > shown) {
            g.drawString(this.font, "… +" + (rows.size() - shown),
                    leftPos + MATLIST_X, topPos + MATLIST_Y + shown * MATLIST_ROW_H, 0xAAAAAA, false);
        }
        // 三行操作说明（0.6 缩放小字：投入/取回/台子外交互各一行）
        String[] hints = {
                Component.translatable("qianxiang.table.hint_insert").getString(),
                Component.translatable("qianxiang.table.hint_insert_2").getString(),
                Component.translatable("qianxiang.table.hint_insert_3").getString()
        };
        for (int i = 0; i < hints.length; i++) {
            g.pose().pushPose();
            g.pose().translate(leftPos + MATLIST_X, topPos + MATLIST_HINT_Y + i * 6, 0);
            g.pose().scale(0.6f, 0.6f, 1.0f);
            g.drawString(this.font, this.font.plainSubstrByWidth(hints[i], 156), 0, 0, 0x777777, false);
            g.pose().popPose();
        }
    }

    /** 当前鼠标是否在给定矩形内（相对窗口坐标）。 */
    private boolean mouseInRect(int x, int y, int w, int h) {
        double mx = this.minecraft.mouseHandler.xpos() * this.minecraft.getWindow().getGuiScaledWidth()
                / this.minecraft.getWindow().getScreenWidth();
        double my = this.minecraft.mouseHandler.ypos() * this.minecraft.getWindow().getGuiScaledHeight()
                / this.minecraft.getWindow().getScreenHeight();
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 命中材料列表行 → 该行的真实槽位下标；未命中 -1。 */
    private int hitTestMaterialRow(double mouseX, double mouseY) {
        List<MatRow> rows = materialListRows();
        int shown = Math.min(rows.size(), MATLIST_MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            int rx = leftPos + MATLIST_X;
            int ry = topPos + MATLIST_Y + i * MATLIST_ROW_H;
            if (mouseX >= rx && mouseX < rx + 92 && mouseY >= ry && mouseY < ry + MATLIST_ROW_H) {
                return rows.get(i).slot();
            }
        }
        return -1;
    }

    /**
     * 材料列表 tooltip：悬停某一行显示 {@link MaterialCardHelper#buildCard} 的完整材料性质卡
     * （名称/稀有度档位/相性/功能算子+用途/自由状态效果/概念/贡献预估）。
     */
    private void renderMaterialSlotTooltips(GuiGraphics g, int mouseX, int mouseY) {
        List<MatRow> rows = materialListRows();
        int shown = Math.min(rows.size(), MATLIST_MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            int rx = leftPos + MATLIST_X;
            int ry = topPos + MATLIST_Y + i * MATLIST_ROW_H;
            if (mouseX >= rx && mouseX < rx + 92 && mouseY >= ry && mouseY < ry + MATLIST_ROW_H) {
                ItemStack stack = rows.get(i).stack();
                try {
                    MaterialCardHelper.MaterialInfo info = MaterialCardHelper.analyze(stack);
                    List<Component> card = MaterialCardHelper.buildCard(stack, info);
                    if (!card.isEmpty()) {
                        card.add(Component.translatable("qianxiang.table.retrieve_hint")
                                .withStyle(net.minecraft.ChatFormatting.GRAY));
                        g.renderTooltip(this.font, card, Optional.empty(), mouseX, mouseY);
                        return;
                    }
                } catch (Throwable t) {
                    // 性质卡渲染失败退化为物品名
                }
                g.renderTooltip(this.font, List.of(stack.getHoverName(),
                                Component.translatable("qianxiang.table.retrieve_hint")
                                        .withStyle(net.minecraft.ChatFormatting.GRAY)),
                        Optional.empty(), mouseX, mouseY);
                return;
            }
        }
    }

    /** 「能做啥」主动建议行（状态条与方案卡之间；纯展示，不占 AI 结果区）。
     *  核心槽是可升级传奇装备时改显示「可升级：Lv.N → 喂料升级」（升级模式提示）。 */
    private void renderSuggestionLine(GuiGraphics g) {
        Component line = upgradeSuggestionLine();
        if (line == null) line = ClientTableSuggestion.line();
        if (line == null) return;
        g.drawString(this.font, this.font.plainSubstrByWidth(line.getString(), 240),
                leftPos + STATUS_X, topPos + 121, 0x7FE3C0, false);
    }

    /** 核心槽（12）放传奇装备时的升级提示（客户端读同步组件，无新包）；否则 null。 */
    private Component upgradeSuggestionLine() {
        ItemStack core = this.menu.getSlot(12).getItem();
        if (core.isEmpty()) return null;
        var attr = core.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
        if (attr == null || !com.qianxiang.phase.UpgradeRules.isUpgradeable(core)) return null;
        int level = attr.upgradeLevel();
        if (level >= com.qianxiang.phase.UpgradeRules.MAX_LEVEL) {
            return Component.translatableWithFallback("qianxiang.table.suggestion.upgrade_max",
                    "传奇 MAX — 已满级");
        }
        return Component.translatableWithFallback("qianxiang.table.suggestion.upgrade",
                "可升级：Lv.%d → 喂料升级", level);
    }

    // ========================== 状态条 ==========================

    private void renderStatusBar(GuiGraphics g) {
        int color = status.color;
        if (status == Status.PARSING) {
            // 解析中做脉冲透明度
            int alpha = 0x55 + (int) (0x55 * Math.sin(statusPulse * 0.15));
            color = (0x00AAFF | (Math.clamp(alpha, 0, 255) << 24));
        } else if (statusFlashUntilMillis > Util.getMillis()) {
            // 响应到达/超时回退后的短暂闪烁（绿=正常响应，黄=兜底回退）
            if (((statusFlashUntilMillis - Util.getMillis()) / 150) % 2 == 0) {
                color = statusFlashColor;
            }
        }
        g.fill(leftPos + STATUS_X, topPos + STATUS_Y,
                leftPos + STATUS_X + STATUS_W, topPos + STATUS_Y + STATUS_H, color);
        // 状态文字直接绘制在条内（深色字，水平居中）；
        // AI 等待中改为「AI 思考中…已等待 X.Xs」每帧计时
        Component text;
        if (status == Status.PARSING && aiRequestStartMillis >= 0L) {
            text = Component.translatable("qianxiang.forge_table.status.parsing_wait",
                    fmt1((Util.getMillis() - aiRequestStartMillis) / 1000.0));
        } else {
            text = Component.translatable(
                    "qianxiang.forge_table.status." + status.name().toLowerCase(Locale.ROOT));
        }
        int tx = leftPos + STATUS_X + (STATUS_W - this.font.width(text)) / 2;
        g.drawString(this.font, text, tx, topPos + STATUS_Y + 1, 0xFF101418, false);
    }

    /** 检测 PARSING → 其他状态 的跳变：正常响应闪绿，回退（fallback/超时兜底）闪黄。 */
    private void updateStatusFlash() {
        if (prevStatusForFlash == Status.PARSING && status != Status.PARSING) {
            aiRequestStartMillis = -1L;
            if (lastAiResult != null) {
                statusFlashUntilMillis = Util.getMillis() + 1500L;
                statusFlashColor = lastAiResult.fallback() ? 0xFFFFFF55 : 0xFF55FF55;
            }
        }
        prevStatusForFlash = status;
    }

    // ========================== 材料即时预览（结果槽旁窄面板） ==========================

    /**
     * 材料槽变动即刻重算 {@link ForgeComposer#compose} 预估——纯客户端计算，不发网络包。
     * 用 {@link ItemStack#hashItemAndComponents} 做签名，签名不变不重算。
     */
    private void updatePreview() {
        try {
            List<ItemStack> mats = new ArrayList<>(ForgeTableMenu.MATERIAL_SLOTS);
            boolean has = false;
            int key = 1;
            for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
                ItemStack s = this.menu.getSlot(i).getItem();
                mats.add(s);
                if (!s.isEmpty()) has = true;
                key = key * 31 + ItemStack.hashItemAndComponents(s);
            }
            if (has == previewHasMaterials && key == previewKey) return;
            previewHasMaterials = has;
            previewKey = key;
            previewComposition = has ? ForgeComposer.compose(mats) : null;
            updateContributionRows(mats, has);
        } catch (Throwable t) {
            // 预估失败不拖垮 GUI：直接隐藏预览
            previewComposition = null;
            previewKey = Integer.MIN_VALUE;
            contributionRows = List.of();
            contribMissingBase = false;
            contribMissingEffect = false;
        }
    }

    /**
     * 重建贡献明细面板数据：每个非空材料槽解析一条 {@link MaterialCardHelper.MaterialInfo}，
     * 并推导拼装引导提示（缺基底 / 缺效果）。纯客户端解析，不发网络包。
     */
    private void updateContributionRows(List<ItemStack> mats, boolean has) {
        if (!has) {
            contributionRows = List.of();
            contribMissingBase = false;
            contribMissingEffect = false;
            return;
        }
        try {
            List<ContribRow> rows = new ArrayList<>(ForgeTableMenu.MATERIAL_SLOTS);
            boolean anyBase = false;
            boolean anyEffect = false;
            for (ItemStack s : mats) {
                if (s == null || s.isEmpty()) continue;
                MaterialCardHelper.MaterialInfo info = MaterialCardHelper.analyze(s);
                if (info == null) continue;
                rows.add(new ContribRow(s.getHoverName().getString(), info));
                anyBase |= info.hasBase();
                anyEffect |= info.hasEffect();
            }
            contributionRows = rows;
            contribMissingBase = !rows.isEmpty() && !anyBase;
            contribMissingEffect = anyBase && !anyEffect;
        } catch (Throwable t) {
            contributionRows = List.of();
            contribMissingBase = false;
            contribMissingEffect = false;
        }
    }

    /** 结果槽旁的即时预览面板：原型名 / 强度 / 攻击或护甲 / 效果数；悬停面板看完整效果列表。 */
    private void renderMaterialPreview(GuiGraphics g) {
        if (!previewHasMaterials) return;
        int x = leftPos + PREVIEW_X;
        int y = topPos + PREVIEW_Y;
        g.fill(x, y, x + PREVIEW_W, y + PREVIEW_H, 0xC0101018);
        int border = 0x66FFD700;
        g.fill(x, y, x + PREVIEW_W, y + 1, border);
        g.fill(x, y + PREVIEW_H - 1, x + PREVIEW_W, y + PREVIEW_H, border);
        g.fill(x, y, x + 1, y + PREVIEW_H, border);
        g.fill(x + PREVIEW_W - 1, y, x + PREVIEW_W, y + PREVIEW_H, border);

        float tx = x + 2.5f;
        float ty = y + 2.0f;
        if (previewComposition == null || !previewComposition.valid()) {
            // 有材料但都无功能算子（纯辅料）：ForgeComposer 判不可锻造
            // （无 BASE_* 不再阻断——走无相骨架兜底出低耐久产物）
            drawTinyString(g, Component.translatable("qianxiang.forge_table.preview.no_base").getString(),
                    tx, ty + 4f, 0xFFFFAA55, 0.5f);
            return;
        }
        ComposedAttributes attr = previewComposition.attributes();
        String name = this.font.plainSubstrByWidth(
                previewComposition.result().getHoverName().getString(), PREVIEW_W * 2 - 5);
        drawTinyString(g, name, tx, ty, 0xFFFFD700, 0.5f);
        drawTinyString(g, Component.translatable("qianxiang.forge_table.preview.power",
                fmt1(attr.powerScore())).getString(), tx, ty + 5f, 0xFFFFD700, 0.5f);
        if (attr.armor() > 0 || attr.armorToughness() > 0) {
            drawTinyString(g, Component.translatable("qianxiang.forge_table.preview.armor",
                    fmt1(attr.armor()), fmt1(attr.armorToughness())).getString(), tx, ty + 10f, 0xFF9AD0FF, 0.5f);
        } else {
            drawTinyString(g, Component.translatable("qianxiang.forge_table.preview.attack",
                    fmt1(attr.attackDamage()), fmt1(attr.attackSpeed())).getString(), tx, ty + 10f, 0xFF9AD0FF, 0.5f);
        }
        drawTinyString(g, Component.translatable("qianxiang.forge_table.preview.effects_count",
                buildEffectLines(attr).size()).getString(), tx, ty + 15f, 0xFFB27DFF, 0.5f);
    }

    // ========================== 材料贡献明细面板（主窗口左侧外挂） ==========================

    /**
     * 材料贡献明细面板：实时列出每个材料槽各自的贡献——
     * 每行一个材料「材料名 → +X 攻 / +X 耐久 / +灼烧×1 / 提供金属骨架」。
     * 顶部按需提供拼装引导（缺基底 / 可加效果材料）。
     * 数据随 {@link #updatePreview} 的材料签名缓存，材料变动即时刷新。
     */
    private void renderContributionPanel(GuiGraphics g) {
        if (!previewHasMaterials || contributionRows.isEmpty()) return;
        try {
            int x1 = leftPos - CONTRIB_PANEL_GAP;
            int x0 = Math.max(2, x1 - CONTRIB_PANEL_W); // 小窗口时贴左缘，不画出屏幕外
            int y0 = topPos + CONTRIB_PANEL_Y;
            float innerX = x0 + 4f;
            int innerW = CONTRIB_PANEL_W - 8;
            float y = y0 + 3f;

            // 先收集所有行（文本/颜色/缩放），算好总高再画底板
            List<Object[]> lines = new ArrayList<>();
            lines.add(new Object[]{Component.translatable("qianxiang.forge_table.contrib.panel_title").getString(),
                    0xFFFFD700, CONTRIB_TITLE_SCALE});
            if (contribMissingBase) {
                lines.add(new Object[]{Component.translatable("qianxiang.forge_table.contrib.hint_no_base").getString(),
                        0xFFFFAA55, CONTRIB_TITLE_SCALE});
            }
            if (contribMissingEffect) {
                lines.add(new Object[]{Component.translatable("qianxiang.forge_table.contrib.hint_no_effect").getString(),
                        0xFFFFAA55, CONTRIB_TITLE_SCALE});
            }
            for (ContribRow row : contributionRows) {
                String name = this.font.plainSubstrByWidth(row.name(), (int) (innerW * 0.45 / CONTRIB_ROW_SCALE));
                String summary = MaterialCardHelper.contributionSummary(row.info()).getString();
                String text = this.font.plainSubstrByWidth(name + " → " + summary,
                        (int) (innerW / CONTRIB_ROW_SCALE));
                lines.add(new Object[]{text, 0xFF7FE3C0, CONTRIB_ROW_SCALE});
            }

            // 底板 + 金色描边（与预览面板同款）；高度按各行实际缩放累加
            float lineH = 9f;
            float contentH = 0f;
            for (Object[] line : lines) {
                contentH += lineH * (float) line[2];
            }
            int panelH = 6 + (int) Math.ceil(contentH);
            g.fill(x0, y0, x0 + CONTRIB_PANEL_W, y0 + panelH, 0xC0101018);
            int border = 0x66FFD700;
            g.fill(x0, y0, x0 + CONTRIB_PANEL_W, y0 + 1, border);
            g.fill(x0, y0 + panelH - 1, x0 + CONTRIB_PANEL_W, y0 + panelH, border);
            g.fill(x0, y0, x0 + 1, y0 + panelH, border);
            g.fill(x0 + CONTRIB_PANEL_W - 1, y0, x0 + CONTRIB_PANEL_W, y0 + panelH, border);

            for (Object[] line : lines) {
                String text = (String) line[0];
                int color = (int) line[1];
                float scale = (float) line[2];
                drawTinyString(g, text, innerX, y, color, scale);
                y += lineH * scale;
            }
        } catch (Throwable t) {
            // 面板渲染失败不影响主界面
        }
    }

    /** 悬停预览面板：完整效果列表（经典 5 + EffectLevels 13 + grantedEffects 自由效果）。 */
    private void renderPreviewTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!previewHasMaterials || previewComposition == null || !previewComposition.valid()) return;
        int x = leftPos + PREVIEW_X;
        int y = topPos + PREVIEW_Y;
        if (mouseX < x || mouseX >= x + PREVIEW_W || mouseY < y || mouseY >= y + PREVIEW_H) return;
        ComposedAttributes attr = previewComposition.attributes();
        List<Component> lines = new ArrayList<>();
        lines.add(previewComposition.result().getHoverName());
        lines.add(Component.translatable("qianxiang.forge_table.preview.power", fmt1(attr.powerScore())));
        lines.add(Component.translatable("qianxiang.forge_table.preview.attack",
                fmt1(attr.attackDamage()), fmt1(attr.attackSpeed())));
        if (attr.armor() > 0 || attr.armorToughness() > 0) {
            lines.add(Component.translatable("qianxiang.forge_table.preview.armor",
                    fmt1(attr.armor()), fmt1(attr.armorToughness())));
        }
        lines.addAll(buildEffectLines(attr));
        g.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    /** 完整效果行：固定算子用 {@code qianxiang.phasefn.*} 中文名，自由效果用 MobEffect 显示名。 */
    private List<Component> buildEffectLines(ComposedAttributes attr) {
        List<Component> out = new ArrayList<>();
        addEffectLine(out, "ignite", attr.igniteLevel());
        addEffectLine(out, "lifesteal", attr.lifestealLevel());
        addEffectLine(out, "reflect", attr.thornsLevel());
        addEffectLine(out, "slow", attr.slowLevel());
        addEffectLine(out, "heal", attr.healLevel());
        ComposedAttributes.EffectLevels fx = attr.effects();
        addEffectLine(out, "poison", fx.poison());
        addEffectLine(out, "frost", fx.frost());
        addEffectLine(out, "levitation", fx.levitation());
        addEffectLine(out, "strength", fx.strength());
        addEffectLine(out, "night_vision", fx.nightVision());
        addEffectLine(out, "speed_boost", fx.speedBoost());
        addEffectLine(out, "jump_boost", fx.jumpBoost());
        addEffectLine(out, "resistance", fx.resistance());
        addEffectLine(out, "fire_resist", fx.fireResist());
        addEffectLine(out, "water_breath", fx.waterBreath());
        addEffectLine(out, "regeneration", fx.regeneration());
        addEffectLine(out, "growth", fx.growth());
        addEffectLine(out, "area_harvest", fx.areaHarvest());
        attr.grantedEffects().forEach((id, lv) -> {
            if (id == null || lv == null || lv <= 0) return;
            Component name = BuiltInRegistries.MOB_EFFECT.getHolder(id)
                    .map(h -> h.value().getDisplayName())
                    .orElse(Component.literal(id.toString()));
            out.add(Component.translatable("qianxiang.forge_table.preview.effect_fmt", name, lv));
        });
        return out;
    }

    private void addEffectLine(List<Component> out, String phasefnKey, int level) {
        if (level <= 0) return;
        out.add(Component.translatable("qianxiang.forge_table.preview.effect_fmt",
                Component.translatable("qianxiang.phasefn." + phasefnKey), level));
    }

    // ========================== 输入框关键词即时提示 ==========================

    /** 输入变化时重算关键词提示（每帧比较输入，无需给 EditBox 加回调，IME 组词也能捕获）。 */
    private void updateKeywordHints() {
        String v = this.requestBox == null ? "" : this.requestBox.getValue().trim();
        if (v.equals(lastHintInput)) return;
        lastHintInput = v;
        hintLines = buildHintLines(v);
    }

    /** 「匹配：烬铁/余烬水晶（灼烧）」式提示行，最多 {@value #HINT_MAX_LINES} 行；匹配源为 FallbackRecipes 关键词表。 */
    private List<Component> buildHintLines(String input) {
        if (input.isEmpty()) return List.of();
        try {
            List<Component> lines = new ArrayList<>();
            for (FallbackRecipes.KeywordHint hint : FallbackRecipes.keywordHints(input)) {
                StringBuilder mats = new StringBuilder();
                for (String id : hint.materialNames()) {
                    String display = materialDisplayName(id);
                    if (display == null || display.isEmpty()) continue;
                    if (mats.indexOf(display) >= 0) continue; // 同组去重（如河豚同时中毒/水下呼吸）
                    if (mats.length() > 0) mats.append('/');
                    mats.append(display);
                }
                if (mats.length() == 0) continue;
                lines.add(Component.translatable("qianxiang.forge_table.hint.match",
                        mats.toString(), Component.translatable(hint.labelKey())));
                if (lines.size() >= HINT_MAX_LINES) break;
            }
            return lines;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** 材料 registry 名 → 本地化显示名；物品不存在（如旧版 scute）返回 null 跳过。 */
    private String materialDisplayName(String registryName) {
        ResourceLocation id = ResourceLocation.tryParse(registryName);
        if (id == null) return null;
        var item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) return null;
        return new ItemStack(item).getHoverName().getString();
    }

    /** 半字号提示行：填在右列按钮组下方的两条缝隙里。 */
    private void renderKeywordHints(GuiGraphics g) {
        for (int i = 0; i < hintLines.size() && i < HINT_MAX_LINES; i++) {
            float y = i == 0 ? HINT_LINE1_Y : HINT_LINE2_Y;
            String text = hintLines.get(i).getString();
            text = this.font.plainSubstrByWidth(text, (int) (HINT_W / HINT_SCALE));
            drawTinyString(g, text, leftPos + HINT_X, topPos + y, 0xFF7FE3C0, HINT_SCALE);
        }
    }

    // ========================== 通用小工具 ==========================

    /** 缩放绘制小字（预览面板与缝隙提示行用）。 */
    private void drawTinyString(GuiGraphics g, String text, float x, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ========================== 结果槽预览与特效 ==========================

    /** 材料未放齐时，在结果槽显示目标产物半透明预览（放大到 32×32）。 */
    private void renderResultSlotPreview(GuiGraphics g) {
        ItemStack result = this.menu.getSlot(ForgeTableMenu.RESULT_SLOT).getItem();
        if (!result.isEmpty()) return; // 已有真实产物，走特效
        if (lastAiResult == null || lastAiResult.proposals().isEmpty()) return;

        ItemStack preview = estimateProductIcon(TYPES[typeIndex]);
        if (preview.isEmpty()) return;

        int cx = leftPos + RESULT_SLOT_X + 8; // 结果槽中心（16×16 逻辑槽取中点）
        int cy = topPos + RESULT_SLOT_Y + 8;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 0.45f);
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(2.0f, 2.0f, 2.0f);
        g.pose().translate(-8, -8, 0);
        g.renderItem(preview, 0, 0);
        g.pose().popPose();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** 根据产物类型预估一个代表性图标。 */
    private ItemStack estimateProductIcon(String type) {
        return switch (safeType(type)) {
            case "magic" -> new ItemStack(QianxiangItems.PHASE_STAFF.get());
            case "armor" -> new ItemStack(QianxiangItems.PHASE_SHIELD.get());
            default -> new ItemStack(QianxiangItems.EMBER_BLADE.get());
        };
    }

    private static String safeType(String type) {
        if (type == null) return "weapon";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "magic", "armor", "tool" -> type.toLowerCase(Locale.ROOT);
            default -> "weapon";
        };
    }

    /** 结果槽特效：整体改模（32×32） + 中间抠洞 + 关键材料旋转。 */
    private void renderResultSlotEffect(GuiGraphics g) {
        ItemStack result = this.menu.getSlot(ForgeTableMenu.RESULT_SLOT).getItem();
        if (result.isEmpty()) return;

        int cx = leftPos + RESULT_SLOT_X + 8; // 结果槽中心（16×16 逻辑槽取中点）
        int cy = topPos + RESULT_SLOT_Y + 8;

        // 1. 整体改模：产物放大到 32×32，覆盖结果槽视觉框
        g.pose().pushPose();
        g.pose().translate(cx, cy, 100);
        g.pose().scale(2.0f, 2.0f, 2.0f);
        g.pose().translate(-8, -8, 0);
        g.renderItem(result, 0, 0);
        g.pose().popPose();

        // 2. 找最关键材料（档位最高、或唯一能触发特殊效果的原料）
        ItemStack keyMaterial = findKeyMaterial();
        if (!keyMaterial.isEmpty()) {
            // 3. 中间抠洞：画一个深色圆盖住产物中心
            drawHole(g, cx, cy, 13);

            // 4. 关键材料在洞中旋转
            g.pose().pushPose();
            g.pose().translate(cx, cy, 150);
            g.pose().scale(1.0f, 1.0f, 1.0f);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(spinAngle));
            g.pose().translate(-8, -8, 0);
            g.renderItem(keyMaterial, 0, 0);
            g.pose().popPose();

            // 5. 外层旋转光环
            drawSpinRing(g, cx, cy, 20, spinAngle);
        }
    }

    /** 从材料槽中挑出「最关键」材料：优先档位高，其次有非基底功能算子。支持原版物品。 */
    private ItemStack findKeyMaterial() {
        ItemStack best = ItemStack.EMPTY;
        int bestScore = -1;
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            ItemStack s = this.menu.getSlot(i).getItem();
            if (s.isEmpty()) continue;
            PhaseData pd = s.get(QianxiangDataComponents.PHASE_DATA.get());
            Set<com.qianxiang.phase.PhaseFunction> functions;
            int tierScore;
            if (pd != null) {
                functions = pd.functions() == null ? Set.of() : pd.functions();
                tierScore = pd.tier() != null ? pd.tier().ordinal() : 0;
            } else {
                // 原版/数据包物品：通过 PhaseFunctionResolver 查询功能算子
                functions = com.qianxiang.phase.PhaseFunctionResolver.get(s);
                tierScore = 0; // 原版默认普通档
            }
            int specialCount = (int) functions.stream().filter(f -> !f.name().startsWith("BASE_")).count();
            int score = tierScore * 10 + specialCount;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    /** 画一个中间洞（深色圆）。 */
    private void drawHole(GuiGraphics g, int cx, int cy, int radius) {
        int color = 0xFF1A1A2E;
        for (int y = -radius; y <= radius; y++) {
            int dx = (int) Math.sqrt(radius * radius - y * y);
            g.fill(cx - dx, cy + y, cx + dx + 1, cy + y + 1, color);
        }
    }

    /** 画一个旋转的光环（4 个小光点）。 */
    private void drawSpinRing(GuiGraphics g, int cx, int cy, int radius, float angle) {
        RenderSystem.enableBlend();
        int color = 0x80A67BFA;
        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(angle + i * 90f);
            int px = cx + (int) (Math.cos(rad) * radius);
            int py = cy + (int) (Math.sin(rad) * radius);
            g.fill(px - 1, py - 1, px + 2, py + 2, color);
        }
    }

    // ========================== AI 推荐配方卡片（2 列网格） ==========================

    private void renderRecipeCards(GuiGraphics g, int mouseX, int mouseY) {
        int x = leftPos + CARD_X;
        int y = topPos + CARD_Y;

        boolean hasConfirm = lastAiResult != null && !lastAiResult.confirmMessage().isBlank();

        // 标题行（y-10）：左标题/空态文案，右侧接确认消息——25 槽布局下标题行只有一条。
        int headerY = y - 10;
        int cursorX = x;
        boolean noProposals = lastAiResult == null || lastAiResult.proposals().isEmpty();
        if (noProposals) {
            Component emptyText = Component.translatable("qianxiang.forge_table.cards.empty");
            g.drawString(this.font, emptyText, cursorX, headerY, 0xFF666666, false);
            cursorX += this.font.width(emptyText) + 6;
            this.hoveredCard = -1;
        } else {
            Component title = Component.translatable("qianxiang.forge_table.cards.title");
            g.drawString(this.font, title, cursorX, headerY, 0xFFFFFFFF, false);
            cursorX += this.font.width(title) + 6;
        }
        if (hasConfirm) {
            String msg = "✓ " + localizeText(lastAiResult.confirmMessage()).getString();
            msg = this.font.plainSubstrByWidth(msg, Math.max(20, leftPos + 248 - cursorX));
            g.drawString(this.font, msg, cursorX, headerY, 0xFF55FF55, false);
        }
        if (noProposals) {
            return;
        }

        List<PhaseAIRecipeService.RecipeProposal> proposals = lastAiResult.proposals();
        this.hoveredCard = -1;
        for (int i = 0; i < proposals.size() && i < MAX_CARDS; i++) {
            var proposal = proposals.get(i);
            int cx = x + (i % CARD_COLS) * (CARD_W + CARD_GAP_X);
            int cy = y + (i / CARD_COLS) * (CARD_H + CARD_GAP_Y);
            boolean hover = mouseX >= cx && mouseX < cx + CARD_W
                    && mouseY >= cy && mouseY < cy + CARD_H;
            if (hover) this.hoveredCard = i;

            int bgColor = hover ? 0x55FFFFFF : 0x22FFFFFF;
            g.fill(cx, cy, cx + CARD_W, cy + CARD_H, bgColor);

            // WQ-76：选中卡片金色描边（只认点选时的那份结果，新响应到达旧卡高亮消失）
            if (i == selectedCard && lastAiResult == selectedCardResult) {
                int sel = 0xCCFFD700;
                g.fill(cx, cy, cx + CARD_W, cy + 1, sel);
                g.fill(cx, cy + CARD_H - 1, cx + CARD_W, cy + CARD_H, sel);
                g.fill(cx, cy, cx + 1, cy + CARD_H, sel);
                g.fill(cx + CARD_W - 1, cy, cx + CARD_W, cy + CARD_H, sel);
            }

            // 产物图标（按类型预估）
            ItemStack product = estimateProductIcon(TYPES[typeIndex]);
            g.renderItem(product, cx + 4, cy + 2);

            // 材料条目：图标 + 背包实时状态（持有=×n 绿，缺失=压暗+红「缺」），
            // 逐条可点击（单材料放入，见 hitTestCardMaterial）
            List<String> materials = proposal.materialNames();
            for (int j = 0; j < materials.size() && j < 4; j++) {
                int entryX = cx + 24 + j * 14;
                int entryY = cy + 1;
                ItemStack matStack = resolveItemStack(materials.get(j));
                if (matStack.isEmpty()) continue;
                boolean entryHover = mouseX >= entryX - 1 && mouseX < entryX + 13
                        && mouseY >= cy && mouseY < cy + 10;
                if (entryHover) {
                    g.fill(entryX - 1, cy, entryX + 13, cy + 10, 0x30FFFFFF);
                }
                g.pose().pushPose();
                g.pose().translate(entryX, entryY, 0);
                g.pose().scale(0.5f, 0.5f, 0.5f);
                g.renderItem(matStack, 0, 0);
                g.pose().popPose();
                int held = countInClientInventory(matStack.getItem());
                if (held > 0) {
                    drawTinyString(g, "×" + held, entryX + 9, cy + 2, 0xFF7FE3C0, 0.45f);
                } else {
                    g.fill(entryX, entryY, entryX + 8, entryY + 8, 0xA0000000);
                    drawTinyString(g, Component.translatable("qianxiang.table.missing_mark").getString(),
                            entryX + 9, cy + 2, 0xFFFF5555, 0.45f);
                }
            }

            // 强度（材料图标右侧，金色）
            String power = String.format("%.1f", proposal.estimatedPower());
            g.drawString(this.font,
                    Component.translatable("qianxiang.forge_table.card.power", power),
                    cx + 62, cy + 3, 0xFFFFD700, false);
            // 说明（下方一行，按像素宽度截断）
            String summaryText = localizeText(proposal.summary()).getString();
            summaryText = this.font.plainSubstrByWidth(summaryText, CARD_W - 28);
            g.drawString(this.font, summaryText, cx + 24, cy + 10, 0xFFC8C8C8, false);
        }
    }

    private int hitTestCard(double mouseX, double mouseY) {
        if (lastAiResult == null || lastAiResult.proposals().isEmpty()) return -1;
        int x = leftPos + CARD_X;
        int y = topPos + CARD_Y;
        List<PhaseAIRecipeService.RecipeProposal> proposals = lastAiResult.proposals();
        for (int i = 0; i < proposals.size() && i < MAX_CARDS; i++) {
            int cx = x + (i % CARD_COLS) * (CARD_W + CARD_GAP_X);
            int cy = y + (i / CARD_COLS) * (CARD_H + CARD_GAP_Y);
            if (mouseX >= cx && mouseX < cx + CARD_W
                    && mouseY >= cy && mouseY < cy + CARD_H) {
                return i;
            }
        }
        return -1;
    }

    /** 命中的卡片材料条目 → [卡片下标, 材料名]；条目区域优先于整张卡。未命中 null。 */
    private int[] hitTestCardMaterial(double mouseX, double mouseY) {
        if (lastAiResult == null) return null;
        int x = leftPos + CARD_X;
        int y = topPos + CARD_Y;
        var proposals = lastAiResult.proposals();
        for (int i = 0; i < proposals.size() && i < MAX_CARDS; i++) {
            int cx = x + (i % CARD_COLS) * (CARD_W + CARD_GAP_X);
            int cy = y + (i / CARD_COLS) * (CARD_H + CARD_GAP_Y);
            var materials = proposals.get(i).materialNames();
            for (int j = 0; j < materials.size() && j < 4; j++) {
                int entryX = cx + 24 + j * 14;
                if (mouseX >= entryX - 1 && mouseX < entryX + 13
                        && mouseY >= cy && mouseY < cy + 10) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    /** 玩家背包（主背包 36 格）里某物品的实时数量（卡片有/缺显示用）。 */
    private int countInClientInventory(net.minecraft.world.item.Item item) {
        if (this.minecraft == null || this.minecraft.player == null) return 0;
        int total = 0;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = this.minecraft.player.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(item)) total += s.getCount();
        }
        return total;
    }

    // ========================== AI 反问选项 chips ==========================

    /** 一个反问 chip：label 为显示文本（可能截断），appendText 为点击后追加到输入框的原词。 */
    private record SuggestChip(String label, String appendText, int x, int y, int w) {}

    /**
     * 计算反问 chips 布局：与「AI 推荐」标题同排、在其右侧，最多 {@value #SUGGEST_MAX} 个，
     * 右边界不越过材料预览面板。无反问时返回空列表。
     */
    private List<SuggestChip> layoutSuggestChips() {
        if (lastAiResult == null || lastAiResult.suggestQuestions() == null
                || lastAiResult.suggestQuestions().isEmpty()) {
            return List.of();
        }
        int y = topPos + SUGGEST_Y;
        int x = leftPos + CARD_X;
        if (!lastAiResult.proposals().isEmpty()) {
            x += this.font.width(Component.translatable("qianxiang.forge_table.cards.title")) + 8;
        } else {
            // WQ-80①：只有反问没有方案时，标题行画的是 cards.empty 占位文案——
            // chips 同样要右移让开，否则文字被 chip 底板完全压住。
            x += this.font.width(Component.translatable("qianxiang.forge_table.cards.empty")) + 8;
        }
        int maxRight = leftPos + PREVIEW_X - 4;
        List<SuggestChip> out = new ArrayList<>();
        for (String q : lastAiResult.suggestQuestions()) {
            if (q == null || q.isBlank()) continue;
            String appendText = q.trim();
            String label = appendText;
            int avail = maxRight - x - 8;
            if (avail < 20) break;
            if (this.font.width(label) > avail) {
                label = this.font.plainSubstrByWidth(label, avail);
            }
            int chipW = this.font.width(label) + 8;
            out.add(new SuggestChip(label, appendText, x, y, chipW));
            x += chipW + SUGGEST_GAP;
            if (out.size() >= SUGGEST_MAX) break;
        }
        return out;
    }

    /** 渲染反问 chips：金色描边小按钮，悬停显示「点击追加并重问」提示。 */
    private void renderSuggestChips(GuiGraphics g, int mouseX, int mouseY) {
        for (SuggestChip chip : layoutSuggestChips()) {
            boolean hover = mouseX >= chip.x() && mouseX < chip.x() + chip.w()
                    && mouseY >= chip.y() && mouseY < chip.y() + SUGGEST_H;
            g.fill(chip.x(), chip.y(), chip.x() + chip.w(), chip.y() + SUGGEST_H,
                    hover ? 0x55FFD700 : 0xC0101018);
            int border = 0x66FFD700;
            g.fill(chip.x(), chip.y(), chip.x() + chip.w(), chip.y() + 1, border);
            g.fill(chip.x(), chip.y() + SUGGEST_H - 1, chip.x() + chip.w(), chip.y() + SUGGEST_H, border);
            g.fill(chip.x(), chip.y(), chip.x() + 1, chip.y() + SUGGEST_H, border);
            g.fill(chip.x() + chip.w() - 1, chip.y(), chip.x() + chip.w(), chip.y() + SUGGEST_H, border);
            g.drawString(this.font, chip.label(), chip.x() + 4, chip.y() + 1,
                    hover ? 0xFFFFEA00 : 0xFFFFD700, false);
            if (hover) {
                g.renderTooltip(this.font,
                        Component.translatable("qianxiang.forge_table.suggest.tooltip"), mouseX, mouseY);
            }
        }
    }

    /** 命中反问 chip 时返回要追加的原词，未命中返回 null。 */
    private String hitTestSuggestion(double mouseX, double mouseY) {
        for (SuggestChip chip : layoutSuggestChips()) {
            if (mouseX >= chip.x() && mouseX < chip.x() + chip.w()
                    && mouseY >= chip.y() && mouseY < chip.y() + SUGGEST_H) {
                return chip.appendText();
            }
        }
        return null;
    }

    /** 点选反问选项：追加到输入框末尾并自动重问 AI（如「一把武器」+「巨剑」→「一把武器 巨剑」）。 */
    private void applySuggestion(String suggestion) {
        String cur = this.requestBox.getValue().trim();
        this.requestBox.setValue(cur.isEmpty() ? suggestion : cur + " " + suggestion);
        sendAiRequest();
    }

    /** 点击某条推荐方案：尝试把材料放入空闲材料槽，无空槽则高亮背包。 */
    private void applyProposal(int index) {
        if (lastAiResult == null) return;
        var proposal = lastAiResult.proposals().get(index);
        if (proposal.materialNames().isEmpty()) return;

        if (countEmptyMaterialSlots() == 0) {
            this.highlightUntilMillis = Util.getMillis() + 3000L;
            return;
        }

        // 先回传「选了第几条」（包序保证服务端先记下选择再放料），
        // 服务端放料触发 slotsChanged 时产物即按该提案的法术/名称组合。
        ClientForgeTableAI.reportProposalIndex(index);
        this.selectedCard = index;
        this.selectedCardResult = lastAiResult;
        // WQ-75：点整张卡 = 替换语义——服务端先退回台上现有材料再放本方案材料，
        // 连点两张卡不再叠成大杂烩（产物强度与卡片摘要一致）。
        // WQ-71：随包回传响应 reqId，采纳日志才能对上请求行。
        PacketDistributor.sendToServer(new AiPlaceMaterialsPayload(
                proposal.materialNames(), true, ClientForgeTableAI.lastReqId()));
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
        ClientForgeTableAI.reportProposalIndex(cardIndex);
        this.selectedCard = cardIndex;
        this.selectedCardResult = lastAiResult;
        PacketDistributor.sendToServer(new AiPlaceMaterialsPayload(
                List.of(proposal.materialNames().get(materialIndex)), false,
                ClientForgeTableAI.lastReqId()));
    }

    private int countEmptyMaterialSlots() {
        int empty = 0;
        for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
            if (this.menu.getSlot(i).getItem().isEmpty()) empty++;
        }
        return empty;
    }

    private ItemStack resolveItemStack(String registryName) {
        var entryOpt = MaterialLibrary.find(registryName);
        if (entryOpt.isPresent()) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entryOpt.get().registryName()));
            if (item != null) return new ItemStack(item);
        }
        ResourceLocation id = ResourceLocation.tryParse(registryName);
        if (id != null) {
            var item = BuiltInRegistries.ITEM.get(id);
            if (item != null) return new ItemStack(item);
        }
        return ItemStack.EMPTY;
    }

    /** 把可能是本地化键的字符串转成 Component；普通字符串原样显示。 */
    private Component localizeText(String text) {
        if (text == null || text.isBlank()) return Component.empty();
        return text.startsWith("qianxiang.") ? Component.translatable(text) : Component.literal(text);
    }

    /** 无空材料槽时点方案：半透明红色闪烁覆盖玩家背包区。 */
    private void renderInventoryHighlight(GuiGraphics g) {
        long remain = highlightUntilMillis - Util.getMillis();
        if (remain > 0) {
            int alpha = (int) (0x33 + 0x33 * Math.sin(remain * 0.018));
            int color = 0xFF0000 | (Math.clamp(alpha, 0, 255) << 24);
            g.fill(leftPos + 7, topPos + 175, leftPos + 169, topPos + 253, color);
        }
    }

    // ========================== 千相蓝图 ==========================

    private void sendSaveBlueprint() {
        List<String> materials = collectCurrentMaterials();
        PacketDistributor.sendToServer(new BlueprintSavePayload(materials));
    }

    private void sendUseBlueprint() {
        if (selectedBlueprint < 0 || selectedBlueprint >= clientBlueprints.size()) return;
        PacketDistributor.sendToServer(new BlueprintUsePayload(selectedBlueprint));
    }

    private void cycleBlueprint(int delta) {
        if (clientBlueprints.isEmpty()) {
            selectedBlueprint = -1;
            blueprintScroll = 0;
            return;
        }
        selectedBlueprint = (selectedBlueprint + delta + clientBlueprints.size()) % clientBlueprints.size();
        clampBlueprintScroll();
    }

    /** 把 {@link #selectedBlueprint} 夹进蓝图面板可视窗（{@link #BLUEPRINT_MAX_VISIBLE} 行）。 */
    private void clampBlueprintScroll() {
        if (clientBlueprints.isEmpty() || selectedBlueprint < 0) {
            blueprintScroll = 0;
            return;
        }
        blueprintScroll = Math.clamp(blueprintScroll, 0,
                Math.max(0, clientBlueprints.size() - BLUEPRINT_MAX_VISIBLE));
        if (selectedBlueprint < blueprintScroll) {
            blueprintScroll = selectedBlueprint;
        } else if (selectedBlueprint >= blueprintScroll + BLUEPRINT_MAX_VISIBLE) {
            blueprintScroll = selectedBlueprint - BLUEPRINT_MAX_VISIBLE + 1;
        }
    }

    // ========================== 锻造说明书 ==========================

    /**
     * 说明书模板回填接口（{@link ForgeGuideScreen} 点击模板时调用）。
     * 只暂存值：随后 {@code setScreen(this)} 会触发本界面 init 重建输入框，
     * 暂存的文本与产物类型在 init 末尾应用。
     */
    public void applyGuideTemplate(String text, String type) {
        this.pendingGuideText = text;
        this.pendingGuideType = type;
    }

    // ========================== 右侧扩展面板 ==========================

    /** 折叠/展开扩展面板（折叠即「返回主界面」视角，只留一个展开小按钮）。 */
    private void toggleExtPanel() {
        extPanelExpanded = !extPanelExpanded;
        updatePanelVisibility();
    }

    /** 按折叠状态同步面板内按钮的可见性与切换按钮位置/文案。 */
    private void updatePanelVisibility() {
        this.saveBlueprintButton.visible = extPanelExpanded;
        this.useBlueprintButton.visible = extPanelExpanded;
        this.prevBlueprintButton.visible = extPanelExpanded;
        this.nextBlueprintButton.visible = extPanelExpanded;
        this.guideButton.visible = extPanelExpanded;
        this.aiSettingsButton.visible = extPanelExpanded;
        this.materialFilterButton.visible = extPanelExpanded;
        this.movesetEditorButton.visible = extPanelExpanded;
        if (extPanelExpanded) {
            this.togglePanelButton.setX(leftPos + EXT_PANEL_X + EXT_PANEL_W - 13);
            this.togglePanelButton.setMessage(Component.translatable("qianxiang.forge_table.panel.collapse"));
        } else {
            this.togglePanelButton.setX(leftPos + EXT_PANEL_X);
            this.togglePanelButton.setMessage(Component.translatable("qianxiang.forge_table.panel.expand"));
        }
    }

    /** 扩展面板背景：半透明底板 + 金色描边，与主窗口明显区分。 */
    private void renderExtPanelBackground(GuiGraphics g) {
        int x0 = leftPos + EXT_PANEL_X - 3;
        int y0 = topPos + EXT_PANEL_BG_TOP;
        int x1 = leftPos + EXT_PANEL_X + EXT_PANEL_W + 2;
        int y1 = topPos + EXT_PANEL_BG_BOTTOM;
        g.fill(x0, y0, x1, y1, 0xC0101018);
        int border = 0x66FFD700;
        g.fill(x0, y0, x1, y0 + 1, border);         // 上
        g.fill(x0, y1 - 1, x1, y1, border);         // 下
        g.fill(x0, y0, x0 + 1, y1, border);         // 左
        g.fill(x1 - 1, y0, x1, y1, border);         // 右
    }

    /** 折叠状态下只画一个小标签底板，衬托展开按钮。 */
    private void renderExtPanelTab(GuiGraphics g) {
        int x0 = leftPos + EXT_PANEL_X - 2;
        int y0 = topPos + EXT_PANEL_BG_TOP - 1;
        g.fill(x0, y0, x0 + 16, y0 + 14, 0xC0101018);
        int border = 0x66FFD700;
        g.fill(x0, y0, x0 + 16, y0 + 1, border);
        g.fill(x0, y0 + 13, x0 + 16, y0 + 14, border);
        g.fill(x0, y0, x0 + 1, y0 + 14, border);
        g.fill(x0 + 15, y0, x0 + 16, y0 + 14, border);
    }

    private void renderBlueprintPanel(GuiGraphics g) {
        int x = leftPos + BLUEPRINT_PANEL_X;
        int y = topPos + BLUEPRINT_PANEL_Y;

        g.drawString(this.font,
                Component.translatable("qianxiang.forge_table.blueprint.title"),
                x, y, 0xFFFFD700, false);
        y += BLUEPRINT_LINE_H + 2;

        if (clientBlueprints.isEmpty()) {
            g.drawString(this.font,
                    Component.translatable("qianxiang.forge_table.blueprint.empty"),
                    x, y, 0xFF888888, false);
            return;
        }

        // WQ-78：从 scrollOffset 起画可视窗，保证选中项总在面板内；
        // WQ-80③：按像素宽度截断（substring 按 char 截会劈开 emoji 代理对渲染成乱码）
        for (int row = 0; row < BLUEPRINT_MAX_VISIBLE; row++) {
            int i = blueprintScroll + row;
            if (i >= clientBlueprints.size()) break;
            BlueprintData bp = clientBlueprints.get(i);
            boolean selected = i == selectedBlueprint;
            if (selected) {
                g.fill(x - 1, y - 1, x + EXT_PANEL_W, y + BLUEPRINT_LINE_H - 1, 0x33FFD700);
            }
            // 右侧对齐显示强度与材料数
            int matCount = bp.materials() == null ? 0 : bp.materials().size();
            String stats = Component.translatable("qianxiang.forge_table.blueprint.entry_stats",
                    String.format("%.1f", bp.power()), matCount).getString();
            int statsW = this.font.width(stats);
            String prefix = selected ? "▶ " : "  ";
            String text = this.font.plainSubstrByWidth(bp.name(),
                    Math.max(10, EXT_PANEL_W - statsW - this.font.width(prefix) - 4));
            Component line = Component.literal(prefix + text);
            g.drawString(this.font, line, x, y, selected ? 0xFFFFD700 : 0xFFE0E0E0, false);
            g.drawString(this.font, stats, x + EXT_PANEL_W - statsW - 1, y, 0xFFAAAAAA, false);
            y += BLUEPRINT_LINE_H;
        }
    }

    // ========================== 历史记录折叠区 ==========================

    private void addHistoryEntry(String request) {
        if (request == null) request = "";
        HISTORY.add(0, new HistoryEntry(request, TYPES[typeIndex], TIERS[tierIndex], System.currentTimeMillis()));
        while (HISTORY.size() > HISTORY_MAX_ENTRIES) {
            HISTORY.remove(HISTORY.size() - 1);
        }
    }

    private boolean hitTestHistoryHeader(double mouseX, double mouseY) {
        int x = leftPos + HISTORY_PANEL_X;
        int y = topPos + HISTORY_PANEL_Y;
        return mouseX >= x && mouseX < x + EXT_PANEL_W && mouseY >= y && mouseY < y + HISTORY_LINE_H + 2;
    }

    private void renderHistoryPanel(GuiGraphics g, int mouseX, int mouseY) {
        int x = leftPos + HISTORY_PANEL_X;
        int y = topPos + HISTORY_PANEL_Y;

        boolean hover = hitTestHistoryHeader(mouseX, mouseY);
        String arrow = historyExpanded ? "▼" : "▶";
        int headerColor = hover ? 0xFFFFEA00 : 0xFFFFD700;
        g.drawString(this.font,
                Component.literal(arrow + " ").append(Component.translatable("qianxiang.forge_table.history.title")),
                x, y, headerColor, false);
        y += HISTORY_LINE_H + 2;

        if (!historyExpanded) return;

        if (HISTORY.isEmpty()) {
            g.drawString(this.font,
                    Component.translatable("qianxiang.forge_table.history.empty"),
                    x, y, 0xFF888888, false);
            return;
        }

        for (HistoryEntry entry : HISTORY) {
            String typeText = Component.translatable("qianxiang.forge_table.type." + entry.type).getString();
            String tierText = Component.translatable("qianxiang.forge_table.tier." + entry.tier).getString();
            // WQ-80③：按像素宽度截断（substring 按 char 截会劈开 emoji 代理对渲染成乱码）
            String full = "[" + typeText + "/" + tierText + "] " + entry.request;
            Component line = Component.literal(this.font.plainSubstrByWidth(full, EXT_PANEL_W));
            g.drawString(this.font, line, x, y, 0xFFE0E0E0, false);
            y += HISTORY_LINE_H;
        }
    }

    // ========================== 通用提示 ==========================

    private void renderButtonTooltips(GuiGraphics g, int mouseX, int mouseY) {
        if (typeButton.isMouseOver(mouseX, mouseY)) {
            g.renderTooltip(this.font,
                    Component.translatable("qianxiang.forge_table.type." + TYPES[typeIndex]), mouseX, mouseY);
        } else if (tierButton.isMouseOver(mouseX, mouseY)) {
            g.renderTooltip(this.font,
                    Component.translatable("qianxiang.forge_table.tier." + TIERS[tierIndex]), mouseX, mouseY);
        } else if (togglePanelButton.isMouseOver(mouseX, mouseY)) {
            g.renderTooltip(this.font, Component.translatable(extPanelExpanded
                    ? "qianxiang.forge_table.panel.collapse.tooltip"
                    : "qianxiang.forge_table.panel.expand.tooltip"), mouseX, mouseY);
        } else if (extPanelExpanded && guideButton.isMouseOver(mouseX, mouseY)) {
            g.renderTooltip(this.font,
                    Component.translatable("qianxiang.forge_table.button.guide.tooltip"), mouseX, mouseY);
        }
    }
}
