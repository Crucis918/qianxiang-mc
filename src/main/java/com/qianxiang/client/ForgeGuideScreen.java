package com.qianxiang.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import com.qianxiang.phase.EffectGlossary;

import java.util.ArrayList;
import java.util.List;

/**
 * 锻造说明书界面（多页分页）。
 * <p>
 * 从 {@link ForgeTableScreen} 的「说明」按钮打开；按 ESC / 「返回」切回工作台界面
 * （直接 {@code setScreen(parent)}，容器不关闭，服务端材料槽状态不受影响）。
 * <p>
 * 排版：金色标题 + 白色正文 + 黄色小节标题 + 青色示例。
 * 模板页（weapon/armor/tool/magic）的青色条目可点击：点击后把描述模板
 * 自动填入工作台输入框（并切换对应产物类型），然后关闭说明书返回工作台。
 * <p>
 * 全部文本走本地化键 {@code qianxiang.guide.p<页>.title / .line.<行>}，
 * 模板回填文本走 {@code qianxiang.guide.tpl.fill.<编号>}。
 */
public class ForgeGuideScreen extends Screen {
    /** 面板尺寸（逻辑像素）。 */
    private static final int PANEL_W = 248;
    private static final int PANEL_H = 196;
    /** 行高与正文区上下留白。 */
    private static final int LINE_H = 10;
    private static final int BODY_TOP = 26;

    private static final int COLOR_TITLE = 0xFFFFD700;   // 页标题：金
    private static final int COLOR_BODY = 0xFFE0E0E0;    // 正文：白
    private static final int COLOR_SECTION = 0xFFFFFF55; // 小节标题：黄
    private static final int COLOR_EXAMPLE = 0xFF55FFFF; // 示例/模板：青
    private static final int COLOR_EXAMPLE_HOVER = 0xFFCCFFFF;
    private static final int COLOR_NOTE = 0xFF9AA0AE;    // 脚注：灰
    private static final int COLOR_DEBUFF = 0xFFFF5555;  // 效果词典·负面：红
    private static final int COLOR_BUFF = 0xFF55FF55;    // 效果词典·增益：绿

    /**
     * 说明书一行。{@code fillKey} 非空表示可点击模板条目：
     * 点击后把 {@code fillKey} 的本地化文本填入工作台输入框，并切换产物类型为 {@code type}。
     * {@code component} 非空表示直接使用预制文本（效果词典页从 {@link EffectGlossary} 生成），
     * 此时 {@code key} 为 null。
     */
    private record GuideLine(String key, int color, String fillKey, String type, Component component) {
        static GuideLine body(String key) { return new GuideLine(key, COLOR_BODY, null, null, null); }
        static GuideLine section(String key) { return new GuideLine(key, COLOR_SECTION, null, null, null); }
        static GuideLine note(String key) { return new GuideLine(key, COLOR_NOTE, null, null, null); }
        static GuideLine template(String key, String fillKey, String type) {
            return new GuideLine(key, COLOR_EXAMPLE, fillKey, type, null);
        }
        /** 预制文本行（不走 lang 键），用于从效果词典现场生成的条目。 */
        static GuideLine generated(Component component, int color) {
            return new GuideLine(null, color, null, null, component);
        }
        boolean clickable() { return fillKey != null; }
    }

    private record GuidePage(String titleKey, List<GuideLine> lines) {}

    private static final List<GuidePage> PAGES = buildPages();

    /** 打开本说明书的工作台界面（返回时切回它，容器保持打开）。 */
    private final ForgeTableScreen parent;
    private int page = 0;
    private int leftPos;
    private int topPos;
    private Button prevButton;
    private Button nextButton;

    public ForgeGuideScreen(ForgeTableScreen parent) {
        super(Component.translatable("qianxiang.guide.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - PANEL_W) / 2;
        this.topPos = (this.height - PANEL_H) / 2;
        int by = topPos + PANEL_H - 20;

        this.prevButton = Button.builder(Component.translatable("qianxiang.guide.button.prev"), b -> flipPage(-1))
                .pos(leftPos + 8, by).size(56, 16).build();
        this.nextButton = Button.builder(Component.translatable("qianxiang.guide.button.next"), b -> flipPage(1))
                .pos(leftPos + PANEL_W - 64, by).size(56, 16).build();
        Button backButton = Button.builder(Component.translatable("qianxiang.guide.button.back"), b -> onClose())
                .pos(leftPos + PANEL_W / 2 - 28, by).size(56, 16).build();

        addRenderableWidget(this.prevButton);
        addRenderableWidget(this.nextButton);
        addRenderableWidget(backButton);
        updateNavButtons();
    }

    /** ESC / 返回按钮：切回工作台界面（不关闭容器）。 */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void flipPage(int delta) {
        page = Math.clamp(page + delta, 0, PAGES.size() - 1);
        updateNavButtons();
    }

    private void updateNavButtons() {
        this.prevButton.active = page > 0;
        this.nextButton.active = page < PAGES.size() - 1;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        // 说明书面板：半透明底板 + 金色描边，风格与工作台扩展面板一致
        int x0 = leftPos;
        int y0 = topPos;
        int x1 = leftPos + PANEL_W;
        int y1 = topPos + PANEL_H;
        g.fill(x0, y0, x1, y1, 0xE0101018);
        int border = 0x88FFD700;
        g.fill(x0, y0, x1, y0 + 1, border);
        g.fill(x0, y1 - 1, x1, y1, border);
        g.fill(x0, y0, x0 + 1, y1, border);
        g.fill(x1 - 1, y0, x1, y1, border);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        GuidePage p = PAGES.get(page);
        // 页标题（金色，居中）
        g.drawCenteredString(this.font, Component.translatable(p.titleKey()),
                leftPos + PANEL_W / 2, topPos + 8, COLOR_TITLE);
        // 页码（右上角，灰色）
        Component indicator = Component.translatable("qianxiang.guide.page", page + 1, PAGES.size());
        g.drawString(this.font, indicator,
                leftPos + PANEL_W - 10 - this.font.width(indicator), topPos + 8, 0xFF888888, false);

        GuideLine hovered = hitTestTemplate(mouseX, mouseY);
        List<GuideLine> lines = p.lines();
        for (int i = 0; i < lines.size(); i++) {
            GuideLine line = lines.get(i);
            int ly = topPos + BODY_TOP + i * LINE_H;
            int color = line.color();
            String prefix = "";
            if (line.clickable()) {
                if (line == hovered) {
                    g.fill(leftPos + 9, ly - 1, leftPos + PANEL_W - 9, ly + LINE_H - 1, 0x33FFFFFF);
                    color = COLOR_EXAMPLE_HOVER;
                    prefix = "▶ ";
                } else {
                    prefix = "  ";
                }
            }
            String text = line.component() != null
                    ? line.component().getString()
                    : Component.translatable(line.key()).getString();
            text = this.font.plainSubstrByWidth(prefix + text, PANEL_W - 24);
            g.drawString(this.font, text, leftPos + 12, ly, color, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            GuideLine hit = hitTestTemplate(mouseX, mouseY);
            if (hit != null) {
                // 点击模板：把描述模板填入工作台输入框（切类型），关闭说明书返回工作台
                String fill = Component.translatable(hit.fillKey()).getString();
                this.parent.applyGuideTemplate(fill, hit.type());
                if (this.minecraft != null) {
                    this.minecraft.setScreen(this.parent);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 命中检测：当前页鼠标下的可点击模板条目，无则返回 null。 */
    private GuideLine hitTestTemplate(double mouseX, double mouseY) {
        List<GuideLine> lines = PAGES.get(page).lines();
        for (int i = 0; i < lines.size(); i++) {
            GuideLine line = lines.get(i);
            if (!line.clickable()) continue;
            int ly = topPos + BODY_TOP + i * LINE_H;
            if (mouseX >= leftPos + 9 && mouseX < leftPos + PANEL_W - 9
                    && mouseY >= ly - 1 && mouseY < ly + LINE_H - 1) {
                return line;
            }
        }
        return null;
    }

    // ========================== 页面内容（文本全在 lang 文件） ==========================

    private static List<GuidePage> buildPages() {
        List<GuidePage> pages = new ArrayList<>();

        // 第 1 页：这是什么（核心理念）
        pages.add(new GuidePage("qianxiang.guide.p1.title", List.of(
                GuideLine.body("qianxiang.guide.p1.line.1"),
                GuideLine.body("qianxiang.guide.p1.line.2"),
                GuideLine.body("qianxiang.guide.p1.line.3"),
                GuideLine.body("qianxiang.guide.p1.line.4"),
                GuideLine.body("qianxiang.guide.p1.line.5"),
                GuideLine.body("qianxiang.guide.p1.line.6"),
                GuideLine.body("qianxiang.guide.p1.line.7"),
                GuideLine.body("qianxiang.guide.p1.line.8"),
                GuideLine.body("qianxiang.guide.p1.line.9"),
                GuideLine.note("qianxiang.guide.p1.line.10"),
                GuideLine.note("qianxiang.guide.p1.line.11")
        )));

        // 第 2 页：怎么用（操作流程 + AI 修改确认 + 蓝图）
        pages.add(new GuidePage("qianxiang.guide.p2.title", List.of(
                GuideLine.body("qianxiang.guide.p2.line.1"),
                GuideLine.body("qianxiang.guide.p2.line.2"),
                GuideLine.body("qianxiang.guide.p2.line.3"),
                GuideLine.body("qianxiang.guide.p2.line.4"),
                GuideLine.body("qianxiang.guide.p2.line.5"),
                GuideLine.body("qianxiang.guide.p2.line.6"),
                GuideLine.body("qianxiang.guide.p2.line.7"),
                GuideLine.body("qianxiang.guide.p2.line.8"),
                GuideLine.body("qianxiang.guide.p2.line.9"),
                GuideLine.body("qianxiang.guide.p2.line.10")
        )));

        // 第 3 页：速查①自定义相材料
        pages.add(new GuidePage("qianxiang.guide.p3.title", List.of(
                GuideLine.section("qianxiang.guide.p3.line.1"),
                GuideLine.body("qianxiang.guide.p3.line.2"),
                GuideLine.body("qianxiang.guide.p3.line.3"),
                GuideLine.body("qianxiang.guide.p3.line.4"),
                GuideLine.body("qianxiang.guide.p3.line.5"),
                GuideLine.body("qianxiang.guide.p3.line.6"),
                GuideLine.body("qianxiang.guide.p3.line.7"),
                GuideLine.body("qianxiang.guide.p3.line.8"),
                GuideLine.body("qianxiang.guide.p3.line.9"),
                GuideLine.body("qianxiang.guide.p3.line.10"),
                GuideLine.body("qianxiang.guide.p3.line.11"),
                GuideLine.note("qianxiang.guide.p3.line.12")
        )));

        // 第 4 页：速查②常用原版材料
        pages.add(new GuidePage("qianxiang.guide.p4.title", List.of(
                GuideLine.section("qianxiang.guide.p4.line.1"),
                GuideLine.body("qianxiang.guide.p4.line.2"),
                GuideLine.body("qianxiang.guide.p4.line.3"),
                GuideLine.body("qianxiang.guide.p4.line.4"),
                GuideLine.body("qianxiang.guide.p4.line.5"),
                GuideLine.body("qianxiang.guide.p4.line.6"),
                GuideLine.body("qianxiang.guide.p4.line.7"),
                GuideLine.body("qianxiang.guide.p4.line.8"),
                GuideLine.body("qianxiang.guide.p4.line.9"),
                GuideLine.body("qianxiang.guide.p4.line.10"),
                GuideLine.body("qianxiang.guide.p4.line.11"),
                GuideLine.body("qianxiang.guide.p4.line.12"),
                GuideLine.body("qianxiang.guide.p4.line.13"),
                GuideLine.body("qianxiang.guide.p4.line.14")
        )));

        // 第 5 页：速查③功能算子（基底与攻击）
        pages.add(new GuidePage("qianxiang.guide.p5.title", List.of(
                GuideLine.section("qianxiang.guide.p5.line.1"),
                GuideLine.body("qianxiang.guide.p5.line.2"),
                GuideLine.body("qianxiang.guide.p5.line.3"),
                GuideLine.body("qianxiang.guide.p5.line.4"),
                GuideLine.body("qianxiang.guide.p5.line.5"),
                GuideLine.body("qianxiang.guide.p5.line.6"),
                GuideLine.body("qianxiang.guide.p5.line.7"),
                GuideLine.body("qianxiang.guide.p5.line.8"),
                GuideLine.body("qianxiang.guide.p5.line.9"),
                GuideLine.body("qianxiang.guide.p5.line.10"),
                GuideLine.body("qianxiang.guide.p5.line.11"),
                GuideLine.body("qianxiang.guide.p5.line.12"),
                GuideLine.body("qianxiang.guide.p5.line.13")
        )));

        // 第 6 页：速查④功能算子（防御与辅助）
        pages.add(new GuidePage("qianxiang.guide.p6.title", List.of(
                GuideLine.section("qianxiang.guide.p6.line.1"),
                GuideLine.body("qianxiang.guide.p6.line.2"),
                GuideLine.body("qianxiang.guide.p6.line.3"),
                GuideLine.body("qianxiang.guide.p6.line.4"),
                GuideLine.body("qianxiang.guide.p6.line.5"),
                GuideLine.body("qianxiang.guide.p6.line.6"),
                GuideLine.body("qianxiang.guide.p6.line.7"),
                GuideLine.body("qianxiang.guide.p6.line.8"),
                GuideLine.body("qianxiang.guide.p6.line.9"),
                GuideLine.body("qianxiang.guide.p6.line.10"),
                GuideLine.body("qianxiang.guide.p6.line.11"),
                GuideLine.body("qianxiang.guide.p6.line.12"),
                GuideLine.body("qianxiang.guide.p6.line.13"),
                GuideLine.body("qianxiang.guide.p6.line.14")
        )));

        // 第 7 页：速查⑤状态效果①（effect tag：攻击与生存向）
        pages.add(new GuidePage("qianxiang.guide.p7.title", List.of(
                GuideLine.section("qianxiang.guide.p7.line.1"),
                GuideLine.body("qianxiang.guide.p7.line.2"),
                GuideLine.body("qianxiang.guide.p7.line.3"),
                GuideLine.body("qianxiang.guide.p7.line.4"),
                GuideLine.body("qianxiang.guide.p7.line.5"),
                GuideLine.body("qianxiang.guide.p7.line.6"),
                GuideLine.body("qianxiang.guide.p7.line.7"),
                GuideLine.body("qianxiang.guide.p7.line.8"),
                GuideLine.note("qianxiang.guide.p7.line.9")
        )));

        // 第 8 页：速查⑥状态效果②（effect tag：探索与稀有向）
        pages.add(new GuidePage("qianxiang.guide.p8.title", List.of(
                GuideLine.section("qianxiang.guide.p8.line.1"),
                GuideLine.body("qianxiang.guide.p8.line.2"),
                GuideLine.body("qianxiang.guide.p8.line.3"),
                GuideLine.body("qianxiang.guide.p8.line.4"),
                GuideLine.body("qianxiang.guide.p8.line.5"),
                GuideLine.body("qianxiang.guide.p8.line.6"),
                GuideLine.body("qianxiang.guide.p8.line.7"),
                GuideLine.body("qianxiang.guide.p8.line.8"),
                GuideLine.note("qianxiang.guide.p8.line.9"),
                GuideLine.note("qianxiang.guide.p8.line.10")
        )));

        // 第 9 页：描述模板①武器与防具（青色条目可点击填入）
        pages.add(new GuidePage("qianxiang.guide.p9.title", List.of(
                GuideLine.section("qianxiang.guide.p9.line.1"),
                GuideLine.template("qianxiang.guide.p9.line.2", "qianxiang.guide.tpl.fill.1", "weapon"),
                GuideLine.template("qianxiang.guide.p9.line.3", "qianxiang.guide.tpl.fill.2", "weapon"),
                GuideLine.template("qianxiang.guide.p9.line.4", "qianxiang.guide.tpl.fill.3", "weapon"),
                GuideLine.section("qianxiang.guide.p9.line.5"),
                GuideLine.template("qianxiang.guide.p9.line.6", "qianxiang.guide.tpl.fill.4", "armor"),
                GuideLine.template("qianxiang.guide.p9.line.7", "qianxiang.guide.tpl.fill.5", "armor"),
                GuideLine.template("qianxiang.guide.p9.line.8", "qianxiang.guide.tpl.fill.6", "armor"),
                GuideLine.template("qianxiang.guide.p9.line.9", "qianxiang.guide.tpl.fill.7", "armor"),
                GuideLine.section("qianxiang.guide.p9.line.10"),
                GuideLine.template("qianxiang.guide.p9.line.11", "qianxiang.guide.tpl.fill.13", "weapon"),
                GuideLine.template("qianxiang.guide.p9.line.12", "qianxiang.guide.tpl.fill.14", "weapon"),
                GuideLine.note("qianxiang.guide.tpl.hint")
        )));

        // 第 10 页：描述模板②工具、魔法与强度话术
        pages.add(new GuidePage("qianxiang.guide.p10.title", List.of(
                GuideLine.section("qianxiang.guide.p10.line.1"),
                GuideLine.template("qianxiang.guide.p10.line.2", "qianxiang.guide.tpl.fill.8", "tool"),
                GuideLine.template("qianxiang.guide.p10.line.3", "qianxiang.guide.tpl.fill.9", "tool"),
                GuideLine.section("qianxiang.guide.p10.line.4"),
                GuideLine.template("qianxiang.guide.p10.line.5", "qianxiang.guide.tpl.fill.10", "magic"),
                GuideLine.template("qianxiang.guide.p10.line.6", "qianxiang.guide.tpl.fill.11", "magic"),
                GuideLine.template("qianxiang.guide.p10.line.7", "qianxiang.guide.tpl.fill.12", "magic"),
                GuideLine.section("qianxiang.guide.p10.line.8"),
                GuideLine.body("qianxiang.guide.p10.line.9"),
                GuideLine.body("qianxiang.guide.p10.line.10"),
                GuideLine.note("qianxiang.guide.tpl.hint")
        )));

        // 第 9 页：效果词典·负面状态（EffectGlossary.DEBUFFS，负面红）
        pages.add(new GuidePage("qianxiang.guide.glossary.debuff.title",
                glossaryLines(EffectGlossary.DEBUFFS,
                        "qianxiang.guide.glossary.debuff.intro",
                        "qianxiang.guide.glossary.debuff.note", COLOR_DEBUFF)));

        // 第 10 页：效果词典·增益状态（EffectGlossary.BUFFS，增益绿）
        pages.add(new GuidePage("qianxiang.guide.glossary.buff.title",
                glossaryLines(EffectGlossary.BUFFS,
                        "qianxiang.guide.glossary.buff.intro",
                        "qianxiang.guide.glossary.buff.note", COLOR_BUFF)));

        return List.copyOf(pages);
    }

    // ========================== 效果词典页（从 EffectGlossary 现场生成） ==========================

    /**
     * 词典页行集：小节引言 + 每条效果一行「中文名(id) ← 典型材料」+ 脚注。
     * 效果名复用官方译名（{@code effect.minecraft.*}；灼烧/冻伤走 {@code qianxiang.phasefn.*}），
     * 材料名取物品注册表的显示名，保证 zh_cn/en_us 都正确。
     */
    private static List<GuideLine> glossaryLines(List<EffectGlossary.EffectInfo> glossary,
                                                 String introKey, String noteKey, int color) {
        List<GuideLine> lines = new ArrayList<>();
        lines.add(GuideLine.section(introKey));
        for (EffectGlossary.EffectInfo info : glossary) {
            MutableComponent line = Component.translatable(info.displayKey());
            line.append(" ← ");
            if (info.typicalMaterials().isEmpty()) {
                line.append(Component.translatable("qianxiang.glossary.no_material"));
            } else {
                for (int i = 0; i < info.typicalMaterials().size(); i++) {
                    if (i > 0) line.append("/");
                    line.append(materialName(info.typicalMaterials().get(i)));
                }
            }
            lines.add(GuideLine.generated(line, color));
        }
        lines.add(GuideLine.note(noteKey));
        return lines;
    }

    /** 材料 registryName → 物品显示名；注册表查不到时退化为原始 id 文本。永不抛异常。 */
    private static Component materialName(String registryName) {
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(registryName));
            if (item != null && item != Items.AIR) {
                return new ItemStack(item).getHoverName();
            }
        } catch (Throwable t) {
            // 退化到 literal
        }
        return Component.literal(registryName);
    }
}
