package com.qianxiang.client;

import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.combat.AnimationLibrary;
import com.qianxiang.combat.WeaponMoveset;
import com.qianxiang.item.QianxiangWeaponItem;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.MovesetApplyPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 连击编辑器（动作拼接器）：从 {@link AnimationLibrary} 逐段拼接 EF 攻击动作。
 * <p>
 * 布局：
 * <ul>
 *   <li>左面板：动画库浏览区——按语义类型（连击/突进/空斩/重劈/快攻/双持/拔刀/德剑/骑乘）
 *       分组列出全部动画，滚轮滚动，点击选中后可「插入到当前段」/「替换当前段」。</li>
 *   <li>右面板：当前连击序列，每段一行（段号 + 中文语义名 + 类型 + 动画 id），
 *       每行「<」「>」按钮在全库中循环切换该段动画，点行选中为当前段。上限 6 段。</li>
 *   <li>底部：+ 添加段 / - 删除末段 / 清空 / 应用 / 返回。</li>
 * </ul>
 * 应用：把序列组成 movesetJson 契约串（category 取目标武器既有动作集的类别，
 * 缺省 tachi；collider=category），发 {@link MovesetApplyPayload} 给服务端写入
 * 结果槽产物（优先）或主手武器的 CUSTOM_MOVESET 组件。
 * 从工作台「动作编辑」按钮进入，返回时回到工作台（容器不清理，
 * 沿用 {@link ForgeAIConfigScreen} 的子页模式）。
 */
public class MovesetEditorScreen extends Screen {

    /** 段数上限（与服务端 {@code MovesetApplyHandler.MAX_SEGMENTS} 一致）。 */
    private static final int MAX_SEGMENTS = 6;

    private static final int PANEL_TOP = 32;
    private static final int PANEL_GAP_BOTTOM = 52;
    private static final int LIB_ROW_H = 11;
    private static final int SEG_ROW_H = 20;
    private static final int SEG_ROW_GAP = 2;
    private static final int BORDER = 0x66FFD700;
    private static final int PANEL_BG = 0xC0101018;

    /** 返回目标（通常是 {@link ForgeTableScreen}）。 */
    private final Screen parent;

    /** 动画库平铺行：header=类型分组标题（info 为 null），否则为一条动画。 */
    private record LibRow(AnimationLibrary.AnimType header, AnimationLibrary.AnimInfo info) {}

    private final List<LibRow> libRows = new ArrayList<>();
    /** 全库动画（登记顺序），「<」「>」循环切换用。 */
    private final List<AnimationLibrary.AnimInfo> allAnims = new ArrayList<>();

    /** 当前编排的连击序列。 */
    private final List<AnimationLibrary.AnimInfo> segments = new ArrayList<>();
    /** 当前选中的段下标（-1 = 无）。 */
    private int currentSegment = -1;
    /** 动画库中选中的动画（null = 无）。 */
    private AnimationLibrary.AnimInfo librarySelection = null;
    /** 动画库滚动偏移（行）。 */
    private int libScroll = 0;

    /** 写入产物的 category（取目标武器既有动作集；无则按形态 profile 的 EF 底座；缺省 tachi）。 */
    private String category = WeaponMoveset.DEFAULT_CATEGORY;
    /** 写入产物的判定盒（取目标武器既有动作集；无则按形态 profile；缺省=category）。 */
    private String collider = WeaponMoveset.DEFAULT_CATEGORY;
    /** 目标武器显示名（标题下提示行）。 */
    private Component targetName = null;

    /** 每段一行的「<」「>」循环按钮（固定 6 行，按段数显隐）。 */
    private final Button[] segPrevButtons = new Button[MAX_SEGMENTS];
    private final Button[] segNextButtons = new Button[MAX_SEGMENTS];
    private Button insertButton;
    private Button replaceButton;
    private Button addButton;
    private Button removeButton;
    private Button clearButton;
    private Button applyButton;
    private Button backButton;

    /** 「AI 编排」输入框与按钮（自然语言 → 服务端编排 → 回包回填序列）。 */
    private net.minecraft.client.gui.components.EditBox aiBox;
    private Button aiComposeButton;

    /** 底部提示闪烁（「已应用」/「无目标」等）剩余 tick。 */
    private int flashTicks = 0;
    private Component flashText = Component.empty();
    private int flashColor = 0xFF55FF55;

    public MovesetEditorScreen(Screen parent) {
        super(Component.translatable("qianxiang.moveset_editor.title"));
        this.parent = parent;
        buildLibraryRows();
    }

    /** 是否已从目标武器读入初始序列（init 会随窗口尺寸重建，只读一次，避免覆盖编辑）。 */
    private boolean loadedFromTarget = false;

    /** 按类型分组把全库动画摊平成行（分组标题 + 条目）。 */
    private void buildLibraryRows() {
        allAnims.addAll(AnimationLibrary.all());
        for (AnimationLibrary.AnimType type : AnimationLibrary.AnimType.values()) {
            List<AnimationLibrary.AnimInfo> list = AnimationLibrary.byType(type);
            if (list.isEmpty()) continue;
            libRows.add(new LibRow(type, null));
            for (AnimationLibrary.AnimInfo info : list) {
                libRows.add(new LibRow(null, info));
            }
        }
    }

    /** 从目标武器（锻造台结果槽产物优先，其次主手）读入既有动作序列作初始值。 */
    private void loadFromTarget() {
        WeaponMoveset ms = null;
        ItemStack target = ItemStack.EMPTY;
        try {
            if (this.minecraft == null || this.minecraft.player == null) return;
            if (this.minecraft.player.containerMenu instanceof ForgeTableMenu menu) {
                ItemStack result = menu.getSlot(ForgeTableMenu.RESULT_SLOT).getItem();
                if (!result.isEmpty()) {
                    ms = result.get(QianxiangDataComponents.CUSTOM_MOVESET.get());
                    target = result;
                    this.targetName = result.getHoverName();
                }
            }
            if (this.targetName == null) {
                ItemStack hand = this.minecraft.player.getMainHandItem();
                if (!hand.isEmpty() && (hand.getItem() instanceof QianxiangWeaponItem
                        || hand.has(QianxiangDataComponents.CUSTOM_MOVESET.get()))) {
                    this.targetName = hand.getHoverName();
                    target = hand;
                    if (ms == null) ms = hand.get(QianxiangDataComponents.CUSTOM_MOVESET.get());
                }
            }
        } catch (Throwable ignored) {
            // 读取失败按空白编排处理
        }
        if (ms != null) {
            // 已有自定义动作集：继承其 category/collider
            this.category = ms.category();
            this.collider = ms.colliderPreset();
        } else {
            // 无自定义动作集：按形态事实源（form→profile）定底座与判定盒，缺省 tachi
            var profile = target.isEmpty() ? null
                    : com.qianxiang.combat.WeaponFormProfile.of(
                            target.getOrDefault(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                                    com.qianxiang.phase.ComposedAttributes.empty()).form());
            if (profile != null) {
                this.category = profile.efCategory();
                this.collider = profile.collider();
                ms = new WeaponMoveset(profile.efCategory(),
                        profile.defaultCombos().stream().map(AnimationLibrary::fullId).toList(),
                        profile.collider());
            } else {
                ms = WeaponMoveset.tachiDefault();
                this.category = ms.category();
                this.collider = ms.colliderPreset();
            }
        }
        // UI 显示底座名（标题下提示行尾部）
        if (this.targetName != null) {
            this.targetName = this.targetName.copy().append(" · 底座 " + this.category);
        }
        for (var id : ms.combos()) {
            AnimationLibrary.AnimInfo info = AnimationLibrary.byId(id);
            if (info == null || segments.size() >= MAX_SEGMENTS) continue;
            segments.add(info);
        }
        this.currentSegment = segments.isEmpty() ? -1 : 0;
    }

    @Override
    protected void init() {
        super.init();

        // Screen.minecraft 在 init 才就绪：首次 init 时从目标武器读入初始序列
        if (!loadedFromTarget) {
            loadedFromTarget = true;
            loadFromTarget();
        }

        int mid = this.width / 2;
        int panelBottom = this.height - PANEL_GAP_BOTTOM;

        // 每段一行的「<」「>」循环按钮（右面板）
        int segX0 = mid + 4;
        int segX1 = this.width - 8;
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            final int idx = i;
            int rowY = PANEL_TOP + 16 + i * (SEG_ROW_H + SEG_ROW_GAP) + 2;
            segPrevButtons[i] = Button.builder(Component.literal("<"), b -> cycleSegment(idx, -1))
                    .bounds(segX0 + 4, rowY, 16, 16).build();
            segNextButtons[i] = Button.builder(Component.literal(">"), b -> cycleSegment(idx, 1))
                    .bounds(segX1 - 20, rowY, 16, 16).build();
            this.addRenderableWidget(segPrevButtons[i]);
            this.addRenderableWidget(segNextButtons[i]);
        }

        // 左面板底部：插入 / 替换
        int libX0 = 8;
        int libX1 = mid - 4;
        int halfW = (libX1 - libX0 - 10) / 2;
        this.insertButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.insert"), b -> insertSelection())
                .bounds(libX0 + 4, panelBottom - 20, halfW, 16).build();
        this.replaceButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.replace"), b -> replaceSelection())
                .bounds(libX0 + 8 + halfW, panelBottom - 20, halfW, 16).build();
        this.addRenderableWidget(this.insertButton);
        this.addRenderableWidget(this.replaceButton);

        // 底部操作按钮两排：添加段/删除末段/清空，应用/返回
        int y1 = this.height - 46;
        int y2 = this.height - 26;
        int bw = 76;
        this.addButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.add"), b -> addSegment())
                .bounds(mid - bw - bw / 2 - 8, y1, bw, 16).build();
        this.removeButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.remove"), b -> removeLastSegment())
                .bounds(mid - bw / 2, y1, bw, 16).build();
        this.clearButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.clear"), b -> clearSegments())
                .bounds(mid + bw / 2 + 8, y1, bw, 16).build();
        this.applyButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.apply"), b -> apply())
                .bounds(mid - bw - 4, y2, bw, 16).build();
        this.backButton = Button.builder(
                        Component.translatable("qianxiang.moveset_editor.back"), b -> onClose())
                .bounds(mid + 4, y2, bw, 16).build();
        this.addRenderableWidget(this.addButton);
        this.addRenderableWidget(this.removeButton);
        this.addRenderableWidget(this.clearButton);
        this.addRenderableWidget(this.applyButton);
        this.addRenderableWidget(this.backButton);

        // 「AI 编排」行（底部两排按钮之上）：输入框 + 按钮
        this.aiBox = new net.minecraft.client.gui.components.EditBox(this.font,
                mid - 168, this.height - 66, 148, 16,
                Component.translatableWithFallback("qianxiang.moveset_editor.ai_hint", "描述想要的连招，如「三段快斩接一次重劈」"));
        this.aiBox.setMaxLength(60);
        this.aiBox.setHint(Component.translatableWithFallback("qianxiang.moveset_editor.ai_hint", "描述想要的连招，如「三段快斩接一次重劈」"));
        this.addRenderableWidget(this.aiBox);
        this.aiComposeButton = Button.builder(
                        Component.translatableWithFallback("qianxiang.moveset_editor.ai_compose", "AI 编排"),
                        b -> sendComposeRequest())
                .bounds(mid - 14, this.height - 66, 76, 16).build();
        this.addRenderableWidget(this.aiComposeButton);

        refreshWidgets();
    }

    // ========================== 段操作 ==========================

    /** 「<」「>」：在全库动画中循环切换该段。 */
    private void cycleSegment(int index, int delta) {
        if (index < 0 || index >= segments.size() || allAnims.isEmpty()) return;
        int cur = allAnims.indexOf(segments.get(index));
        int next = (cur + delta + allAnims.size()) % allAnims.size();
        segments.set(index, allAnims.get(next));
        currentSegment = index;
    }

    /** 「+ 添加段」：追加一个默认连击动画（库中第一个 COMBO），并选中它。 */
    private void addSegment() {
        if (segments.size() >= MAX_SEGMENTS) {
            flash(Component.translatable("qianxiang.moveset_editor.msg.max", MAX_SEGMENTS), 0xFFFFFF55);
            return;
        }
        List<AnimationLibrary.AnimInfo> combos = AnimationLibrary.byType(AnimationLibrary.AnimType.COMBO);
        segments.add(combos.isEmpty() ? allAnims.get(0) : combos.get(0));
        currentSegment = segments.size() - 1;
        refreshWidgets();
    }

    private void removeLastSegment() {
        if (segments.isEmpty()) return;
        segments.remove(segments.size() - 1);
        if (currentSegment >= segments.size()) currentSegment = segments.size() - 1;
        refreshWidgets();
    }

    private void clearSegments() {
        segments.clear();
        currentSegment = -1;
        refreshWidgets();
    }

    /** 「插入到当前段」：把动画库选中项插到当前段之后（无当前段则追加）。 */
    private void insertSelection() {
        if (librarySelection == null) return;
        if (segments.size() >= MAX_SEGMENTS) {
            flash(Component.translatable("qianxiang.moveset_editor.msg.max", MAX_SEGMENTS), 0xFFFFFF55);
            return;
        }
        int pos = currentSegment < 0 ? segments.size() : currentSegment + 1;
        segments.add(pos, librarySelection);
        currentSegment = pos;
        refreshWidgets();
    }

    /** 「替换当前段」：把当前段替换为动画库选中项。 */
    private void replaceSelection() {
        if (librarySelection == null || currentSegment < 0 || currentSegment >= segments.size()) return;
        segments.set(currentSegment, librarySelection);
        refreshWidgets();
    }

    /** 按段数/选中状态刷新行按钮显隐与底部按钮可用性。 */
    private void refreshWidgets() {
        for (int i = 0; i < MAX_SEGMENTS; i++) {
            boolean visible = i < segments.size();
            segPrevButtons[i].visible = visible;
            segNextButtons[i].visible = visible;
        }
        this.removeButton.active = !segments.isEmpty();
        this.clearButton.active = !segments.isEmpty();
        this.applyButton.active = !segments.isEmpty();
        this.insertButton.active = librarySelection != null;
        this.replaceButton.active = librarySelection != null && currentSegment >= 0;
    }

    // ========================== 应用 ==========================

    /** 客户端侧目标预检（与服务端 MovesetApplyHandler 的目标选择一致）。 */
    private boolean hasTarget() {
        try {
            if (this.minecraft == null || this.minecraft.player == null) return false;
            if (this.minecraft.player.containerMenu instanceof ForgeTableMenu menu
                    && !menu.getSlot(ForgeTableMenu.RESULT_SLOT).getItem().isEmpty()) {
                return true;
            }
            ItemStack hand = this.minecraft.player.getMainHandItem();
            return !hand.isEmpty() && (hand.getItem() instanceof QianxiangWeaponItem
                    || hand.has(QianxiangDataComponents.CUSTOM_MOVESET.get()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 「应用」：组 movesetJson 契约串发包给服务端写组件（走既有 movesetJson 链路格式）。 */
    private void apply() {
        if (segments.isEmpty()) return;
        if (!hasTarget()) {
            flash(Component.translatable("qianxiang.moveset_editor.msg.no_target"), 0xFFFF5555);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"category\":\"").append(category).append("\",\"combos\":[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(AnimationLibrary.shortPath(segments.get(i).id())).append('"');
        }
        sb.append("],\"collider\":\"").append(collider).append("\"}");
        PacketDistributor.sendToServer(new MovesetApplyPayload(sb.toString()));
        flash(Component.translatable("qianxiang.moveset_editor.msg.applied"), 0xFF55FF55);
    }

    /** 「AI 编排」按钮：发自然语言需求给服务端（AI/兜底双路径），回包在 render 里消费。 */
    private void sendComposeRequest() {
        String request = this.aiBox.getValue().trim();
        if (request.isEmpty()) return;
        PacketDistributor.sendToServer(new com.qianxiang.network.MovesetComposePayload(request));
        flash(Component.translatableWithFallback("qianxiang.moveset_editor.ai_composing", "编排中…"), 0xFFFFFFAA);
    }

    /** 回包回填：动作集 JSON 的 combos 全 id → 动画条目，整体替换当前序列（可再微调）。 */
    private void applyComposeResult(String movesetJson) {
        try {
            var obj = com.google.gson.JsonParser.parseString(movesetJson).getAsJsonObject();
            if (!obj.has("combos") || !obj.get("combos").isJsonArray()) return;
            List<AnimationLibrary.AnimInfo> next = new ArrayList<>();
            for (var el : obj.getAsJsonArray("combos")) {
                var info = AnimationLibrary.byId(
                        net.minecraft.resources.ResourceLocation.tryParse(el.getAsString()));
                if (info != null && next.size() < MAX_SEGMENTS) next.add(info);
            }
            if (next.isEmpty()) return;
            segments.clear();
            segments.addAll(next);
            currentSegment = 0;
            if (obj.has("category")) category = obj.get("category").getAsString();
            if (obj.has("collider")) collider = obj.get("collider").getAsString();
            refreshWidgets();
            flash(Component.translatableWithFallback("qianxiang.moveset_editor.ai_done", "已回填序列，可微调后应用"), 0xFF55FF55);
        } catch (Throwable t) {
            flash(Component.translatableWithFallback("qianxiang.moveset_editor.ai_failed", "编排失败，请换个说法"), 0xFFFF5555);
        }
    }

    private void flash(Component text, int color) {
        this.flashText = text;
        this.flashColor = color;
        this.flashTicks = 60;
    }

    // ========================== 交互 ==========================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 段行点击：选中为当前段（「<」「>」按钮由 widget 自己处理）
            int seg = hitTestSegment(mouseX, mouseY);
            if (seg >= 0) {
                currentSegment = seg;
                refreshWidgets();
                return true;
            }
            // 动画库条目点击：选中
            AnimationLibrary.AnimInfo hit = hitTestLibrary(mouseX, mouseY);
            if (hit != null) {
                librarySelection = hit;
                refreshWidgets();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int mid = this.width / 2;
        if (mouseX >= 8 && mouseX < mid - 4 && mouseY >= PANEL_TOP && mouseY < this.height - PANEL_GAP_BOTTOM) {
            int visible = libraryVisibleRows();
            int max = Math.max(0, libRows.size() - visible);
            libScroll = Math.clamp(libScroll - (int) Math.signum(scrollY) * 2, 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int libraryListTop() {
        return PANEL_TOP + 16;
    }

    private int libraryListBottom() {
        return this.height - PANEL_GAP_BOTTOM - 24;
    }

    private int libraryVisibleRows() {
        return Math.max(1, (libraryListBottom() - libraryListTop()) / LIB_ROW_H);
    }

    private int hitTestSegment(double mouseX, double mouseY) {
        int mid = this.width / 2;
        // 只命中文字区（两端各留 22px 给「<」「>」按钮，按钮点击交给 widget 处理）
        int x0 = mid + 4 + 22;
        int x1 = this.width - 8 - 22;
        if (mouseX < x0 || mouseX >= x1) return -1;
        for (int i = 0; i < segments.size(); i++) {
            int rowY = PANEL_TOP + 16 + i * (SEG_ROW_H + SEG_ROW_GAP);
            if (mouseY >= rowY && mouseY < rowY + SEG_ROW_H) return i;
        }
        return -1;
    }

    private AnimationLibrary.AnimInfo hitTestLibrary(double mouseX, double mouseY) {
        int mid = this.width / 2;
        if (mouseX < 8 || mouseX >= mid - 4) return null;
        int top = libraryListTop();
        int bottom = libraryListBottom();
        if (mouseY < top || mouseY >= bottom) return null;
        int row = libScroll + (int) (mouseY - top) / LIB_ROW_H;
        if (row < 0 || row >= libRows.size()) return null;
        return libRows.get(row).info();
    }

    @Override
    public void onClose() {
        // 返回工作台（容器保持打开，沿用 ForgeAIConfigScreen 的子页模式）
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    // ========================== 渲染 ==========================

    /**
     * 背景层：标题/目标行 + 两块面板底板画在 Screen 背景之后、widget（按钮）之前，
     * 避免底板盖住「<」「>」等按钮（Screen.render 顺序：renderBackground → widgets）。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);

        int mid = this.width / 2;
        int panelBottom = this.height - PANEL_GAP_BOTTOM;

        // 标题 + 目标行
        g.drawCenteredString(this.font, this.title, mid, 8, 0xFFFFD700);
        Component targetLine = this.targetName != null
                ? Component.translatable("qianxiang.moveset_editor.target", this.targetName)
                : Component.translatable("qianxiang.moveset_editor.target.none");
        g.drawCenteredString(this.font, targetLine, mid, 20, 0xFFAAAAAA);

        // 两块面板底板
        drawPanel(g, 8, PANEL_TOP, mid - 4, panelBottom);
        drawPanel(g, mid + 4, PANEL_TOP, this.width - 8, panelBottom);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        String composed = ClientMovesetCompose.consume();
        if (composed != null) applyComposeResult(composed);
        super.render(g, mouseX, mouseY, partialTick);

        renderLibrary(g, mouseX, mouseY);
        renderSequence(g);

        // 底部闪烁提示
        if (flashTicks > 0) {
            flashTicks--;
            g.drawCenteredString(this.font, flashText, this.width / 2, this.height - 58, flashColor);
        }
    }

    private void drawPanel(GuiGraphics g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x1, y1, PANEL_BG);
        g.fill(x0, y0, x1, y0 + 1, BORDER);
        g.fill(x0, y1 - 1, x1, y1, BORDER);
        g.fill(x0, y0, x0 + 1, y1, BORDER);
        g.fill(x1 - 1, y0, x1, y1, BORDER);
    }

    /** 左面板：动画库（分组标题 + 条目，滚轮滚动，选中高亮，右侧滚动条）。 */
    private void renderLibrary(GuiGraphics g, int mouseX, int mouseY) {
        int mid = this.width / 2;
        int x0 = 8;
        int x1 = mid - 4;
        g.drawString(this.font, Component.translatable("qianxiang.moveset_editor.library"),
                x0 + 4, PANEL_TOP + 4, 0xFFFFD700, false);

        int top = libraryListTop();
        int bottom = libraryListBottom();
        int visible = libraryVisibleRows();

        g.enableScissor(x0 + 1, top, x1 - 1, bottom);
        for (int v = 0; v < visible; v++) {
            int row = libScroll + v;
            if (row >= libRows.size()) break;
            LibRow libRow = libRows.get(row);
            int y = top + v * LIB_ROW_H;
            if (libRow.header() != null) {
                // 分组标题：【连击 Combo】
                g.drawString(this.font, Component.literal("【")
                                .append(Component.translatable(libRow.header().langKey()))
                                .append(" " + libRow.header().name() + "】"),
                        x0 + 4, y + 1, 0xFFFFD700, false);
            } else {
                AnimationLibrary.AnimInfo info = libRow.info();
                boolean selected = info == librarySelection;
                boolean hover = mouseX >= x0 && mouseX < x1 - 8 && mouseY >= y && mouseY < y + LIB_ROW_H;
                if (selected) {
                    g.fill(x0 + 2, y, x1 - 10, y + LIB_ROW_H, 0x44FFD700);
                } else if (hover) {
                    g.fill(x0 + 2, y, x1 - 10, y + LIB_ROW_H, 0x22FFFFFF);
                }
                // 中文语义名 + 灰色动画 id（分两截绘制，id 宽预留，名称按剩余宽度截断）
                String idText = AnimationLibrary.shortPath(info.id());
                int idW = this.font.width(idText);
                int nameX = x0 + 10;
                int nameMaxW = x1 - 12 - nameX - idW - 4;
                String name = nameMaxW > 0
                        ? this.font.plainSubstrByWidth(info.displayName(), nameMaxW)
                        : "";
                g.drawString(this.font, name, nameX, y + 1,
                        selected ? 0xFFFFEA00 : 0xFFE0E0E0, false);
                g.drawString(this.font, idText, nameX + this.font.width(name) + 4, y + 1,
                        0xFF888888, false);
            }
        }
        g.disableScissor();

        // 简易滚动条
        int total = libRows.size();
        if (total > visible) {
            int trackX = x1 - 6;
            g.fill(trackX, top, trackX + 3, bottom, 0xFF333340);
            int thumbH = Math.max(8, (bottom - top) * visible / total);
            int thumbY = top + (bottom - top - thumbH) * libScroll / Math.max(1, total - visible);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFFFD700);
        }
    }

    /** 右面板：当前连击序列（每段一行，当前段高亮；「<」「>」为 widget 按钮）。 */
    private void renderSequence(GuiGraphics g) {
        int mid = this.width / 2;
        int x0 = mid + 4;
        int x1 = this.width - 8;
        g.drawString(this.font,
                Component.translatable("qianxiang.moveset_editor.sequence",
                        segments.size(), MAX_SEGMENTS),
                x0 + 4, PANEL_TOP + 4, 0xFFFFD700, false);

        if (segments.isEmpty()) {
            g.drawString(this.font, Component.translatable("qianxiang.moveset_editor.hint.empty"),
                    x0 + 4, PANEL_TOP + 18, 0xFF888888, false);
            return;
        }

        for (int i = 0; i < segments.size(); i++) {
            AnimationLibrary.AnimInfo info = segments.get(i);
            int rowY = PANEL_TOP + 16 + i * (SEG_ROW_H + SEG_ROW_GAP);
            boolean selected = i == currentSegment;
            // 行底高亮只画文字区（避开两端「<」「>」按钮，不盖住 widget）
            g.fill(x0 + 22, rowY, x1 - 22, rowY + SEG_ROW_H,
                    selected ? 0x44FFD700 : 0x22FFFFFF);
            if (selected) {
                g.fill(x0 + 2, rowY, x0 + 3, rowY + SEG_ROW_H, 0xFFFFD700);
            }
            // 段号 + 中文语义名 + 类型 + 灰色动画 id（id 宽预留，前段按剩余宽度截断）
            String idText = AnimationLibrary.shortPath(info.id());
            int idW = this.font.width(idText);
            int textX = x0 + 24;
            int headMaxW = x1 - 26 - textX - idW - 4;
            String head = (i + 1) + ". " + info.displayName()
                    + " [" + Component.translatable(info.type().langKey()).getString() + "]";
            if (headMaxW > 0) head = this.font.plainSubstrByWidth(head, headMaxW);
            g.drawString(this.font, head, textX, rowY + 5,
                    selected ? 0xFFFFEA00 : 0xFFE0E0E0, false);
            g.drawString(this.font, idText, textX + this.font.width(head) + 4, rowY + 5,
                    0xFF888888, false);
        }
    }
}
