package com.qianxiang.client;

import com.qianxiang.cap.ProficiencyNodes;
import com.qianxiang.cap.ProficiencyTrack;
import com.qianxiang.network.ActivateSkillPayload;
import com.qianxiang.network.AllocateNodePayload;
import com.qianxiang.network.RespecPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 熟练度技能树界面（无容器 Screen，纯代码绘制，复古深木风——与两个台子 GUI 同色系）。
 * <p>
 * 打开方式：O 键（{@code ClientSpellInput} 边沿）或相师发来的 OpenSkillTreePayload
 * （pendingOpenTree）。三轨页签、3 阶 × 3 节点网格、底部主动技能冷却与洗点按钮。
 * 数据源 = {@link ClientProficiencyData}（服务端全量同步快照）；点击节点只发
 * {@link AllocateNodePayload}，不做本地乐观高亮，等 sync 回推。
 * </p>
 */
public class SkillTreeScreen extends Screen {

    // —— 复古配色（与台子 GUI 一致：深木底 / 铜点缀 / 米白字）——
    private static final int WOOD_DARK = 0xFF3A2A1A;
    private static final int WOOD = 0xFF4A3423;
    private static final int WOOD_LIGHT = 0xFF5C442E;
    private static final int COPPER_DARK = 0xFF8A5A2B;
    private static final int COPPER = 0xFFB87333;
    private static final int CREAM = 0xFFD8CDB0;
    private static final int SLOT_DARK = 0xFF241A12;
    private static final int GREEN = 0xFF55CC55;
    private static final int GRAY = 0xFF666666;

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 176;
    private static final int CELL = 26;
    private static final int TAB_W = 60;
    private static final int TAB_H = 12;

    /** 当前选中页签（0/1/2 = combat/arcane/craft，3 = 主职业）。 */
    private int tabIndex = 0;
    /** 洗点二次确认武装的截止时刻（System.currentTimeMillis；0 = 未武装）。 */
    private long respecArmUntilMs = 0L;

    /** 主职业页签下标。 */
    private static final int CLASS_TAB = 3;
    /** 自定义选择器的暂存选择（打开页签时从当前内核初始化）。 */
    private String pickElementA = "", pickElementB = "", pickForm = "";

    private static final ProficiencyTrack[] TRACKS = {
            ProficiencyTrack.COMBAT, ProficiencyTrack.ARCANE, ProficiencyTrack.CRAFT};

    public SkillTreeScreen() {
        super(Component.translatable("qianxiang.proficiency.tree.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 不暂停游戏
    }

    private int x0() { return (this.width - PANEL_W) / 2; }
    private int y0() { return (this.height - PANEL_H) / 2; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x0 = x0(), y0 = y0();
        // 面板：深木底 + 细金属边 + 四角包铁
        g.fill(x0, y0, x0 + PANEL_W, y0 + PANEL_H, WOOD_DARK);
        border(g, x0, y0, PANEL_W, PANEL_H, 0xFF565656);
        for (int[] c : new int[][]{{x0 + 1, y0 + 1}, {x0 + PANEL_W - 5, y0 + 1},
                {x0 + 1, y0 + PANEL_H - 5}, {x0 + PANEL_W - 5, y0 + PANEL_H - 5}}) {
            g.fill(c[0], c[1], c[0] + 4, c[1] + 4, 0xFF3C3C3C);
        }

        if (!ClientProficiencyData.unlocked) {
            // 未开启：一行指引
            g.drawCenteredString(this.font,
                    Component.translatable("qianxiang.proficiency.tree.locked_hint"),
                    x0 + PANEL_W / 2, y0 + PANEL_H / 2 - 4, CREAM);
            return;
        }

        renderTabs(g, x0, y0, mouseX, mouseY);
        if (tabIndex == CLASS_TAB) {
            renderClassCore(g, x0, y0, mouseX, mouseY);
        } else {
            renderTrackHeader(g, x0, y0);
            renderNodes(g, x0, y0, mouseX, mouseY);
        }
        renderFooter(g, x0, y0, mouseX, mouseY);
    }

    // ============================ 页签 ============================

    private void renderTabs(GuiGraphics g, int x0, int y0, int mouseX, int mouseY) {
        for (int i = 0; i < TRACKS.length + 1; i++) {
            int tx = x0 + 8 + i * (TAB_W + 1);
            int ty = y0 + 6;
            boolean selected = i == tabIndex;
            boolean hover = mouseX >= tx && mouseX < tx + TAB_W && mouseY >= ty && mouseY < ty + TAB_H;
            g.fill(tx, ty, tx + TAB_W, ty + TAB_H, selected ? WOOD_LIGHT : SLOT_DARK);
            border(g, tx, ty, TAB_W, TAB_H, selected ? COPPER : 0xFF565656);
            g.drawCenteredString(this.font,
                    Component.translatable(i == CLASS_TAB
                            ? "qianxiang.classcore.tab" : "qianxiang.proficiency.track." + TRACKS[i].id()),
                    tx + TAB_W / 2, ty + 2, selected || hover ? CREAM : GRAY);
        }
    }

    private ProficiencyTrack track() {
        return TRACKS[tabIndex];
    }

    // ============================ 轨头：等级 + 进度条 + 点数 ============================

    private void renderTrackHeader(GuiGraphics g, int x0, int y0) {
        ProficiencyTrack track = track();
        int level = levelOf(track);
        int points = pointsOf(track);
        int xp = xpOf(track);
        int nextCost = com.qianxiang.cap.ProficiencyHelper.xpForLevel(level + 1);
        String head = Component.translatable("qianxiang.proficiency.track." + track.id()).getString()
                + "  Lv." + level + "  ·  "
                + Component.translatable("qianxiang.proficiency.tree.points", points).getString();
        g.drawString(this.font, head, x0 + 8, y0 + 24, CREAM, false);
        // xp 进度条（深棕槽 + 铜条）
        int barX = x0 + 8, barY = y0 + 34, barW = PANEL_W - 16, barH = 5;
        g.fill(barX, barY, barX + barW, barY + barH, SLOT_DARK);
        if (level < com.qianxiang.cap.ProficiencyHelper.MAX_LEVEL && nextCost > 0) {
            int fill = Math.min(barW, barW * xp / nextCost);
            g.fill(barX, barY, barX + fill, barY + barH, COPPER);
        } else {
            g.fill(barX, barY, barX + barW, barY + barH, COPPER_DARK);
        }
        border(g, barX, barY, barW, barH, 0xFF565656);
    }

    private static int levelOf(ProficiencyTrack track) {
        return switch (track) {
            case COMBAT -> ClientProficiencyData.combatLevel;
            case ARCANE -> ClientProficiencyData.arcaneLevel;
            case CRAFT -> ClientProficiencyData.craftLevel;
        };
    }

    private static int pointsOf(ProficiencyTrack track) {
        return switch (track) {
            case COMBAT -> ClientProficiencyData.combatPoints;
            case ARCANE -> ClientProficiencyData.arcanePoints;
            case CRAFT -> ClientProficiencyData.craftPoints;
        };
    }

    private static int xpOf(ProficiencyTrack track) {
        return switch (track) {
            case COMBAT -> ClientProficiencyData.combatXp;
            case ARCANE -> ClientProficiencyData.arcaneXp;
            case CRAFT -> ClientProficiencyData.craftXp;
        };
    }

    // ============================ 节点网格 ============================

    private List<ProficiencyNodes.Node> trackNodes() {
        List<ProficiencyNodes.Node> out = new ArrayList<>();
        for (ProficiencyNodes.Node node : ProficiencyNodes.all().values()) {
            if (node.track() == track()) out.add(node);
        }
        return out;
    }

    /** 节点状态：0=未满足前置(暗灰) 1=可点(金框呼吸) 2=已点(绿框) 3=前置满足但无点(暗)。 */
    private int nodeState(ProficiencyNodes.Node node) {
        if (ClientProficiencyData.allocated.contains(node.id())) return 2;
        if (!prereqMetLocal(node)) return 0;
        return pointsOf(node.track()) > 0 ? 1 : 3;
    }

    /** 客户端侧前置判定（与 {@code ProficiencyNodes.prereqMet} 同构，读本地缓存）。 */
    private static boolean prereqMetLocal(ProficiencyNodes.Node node) {
        return switch (node.tier()) {
            case 1 -> true;
            case 2 -> anyAllocatedLocal(node.track(), 1)
                    && levelOf(node.track()) >= ProficiencyNodes.TIER2_LEVEL_REQ;
            default -> anyAllocatedLocal(node.track(), 2)
                    && levelOf(node.track()) >= ProficiencyNodes.TIER3_LEVEL_REQ;
        };
    }

    private static boolean anyAllocatedLocal(ProficiencyTrack track, int tier) {
        for (ProficiencyNodes.Node node : ProficiencyNodes.all().values()) {
            if (node.track() == track && node.tier() == tier
                    && ClientProficiencyData.allocated.contains(node.id())) {
                return true;
            }
        }
        return false;
    }

    private void renderNodes(GuiGraphics g, int x0, int y0, int mouseX, int mouseY) {
        List<ProficiencyNodes.Node> nodes = trackNodes();
        int startX = x0 + (PANEL_W - (3 * CELL + 2 * 46)) / 2;
        int startY = y0 + 46;
        long pulse = System.currentTimeMillis() % 1000;
        for (int i = 0; i < nodes.size(); i++) {
            ProficiencyNodes.Node node = nodes.get(i);
            int col = i % 3;
            int row = i / 3;
            int cx = startX + col * (CELL + 46);
            int cy = startY + row * (CELL + 8);
            int state = nodeState(node);
            int borderColor = switch (state) {
                case 2 -> GREEN;
                case 1 -> pulse < 500 ? COPPER : CREAM;   // 金框呼吸
                default -> 0xFF3C3C3C;
            };
            g.fill(cx, cy, cx + CELL, cy + CELL, state == 0 ? 0x80101010 : SLOT_DARK);
            border(g, cx, cy, CELL, CELL, borderColor);
            g.renderItem(nodeIcon(node), cx + 5, cy + 5);
            // hover tooltip：名称 / 效果 / 前置 / 消耗
            if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                g.fill(cx, cy, cx + CELL, cy + CELL, 0x30FFFFFF);
                List<Component> lines = new ArrayList<>();
                lines.add(Component.translatable("qianxiang.proficiency.node." + node.id() + ".name"));
                lines.add(Component.translatable("qianxiang.proficiency.node." + node.id() + ".desc")
                        .copy().withStyle(net.minecraft.ChatFormatting.GRAY));
                lines.add(prereqLine(node));
                if (state != 2) {
                    lines.add(Component.translatable("qianxiang.proficiency.tree.cost", 1)
                            .withStyle(net.minecraft.ChatFormatting.GOLD));
                }
                g.renderTooltip(this.font, lines, java.util.Optional.empty(), mouseX, mouseY);
            }
        }
    }

    private static Component prereqLine(ProficiencyNodes.Node node) {
        return switch (node.tier()) {
            case 1 -> Component.translatable("qianxiang.proficiency.tree.prereq_none")
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
            case 2 -> Component.translatable("qianxiang.proficiency.tree.prereq_t2",
                            ProficiencyNodes.TIER2_LEVEL_REQ).withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
            default -> Component.translatable("qianxiang.proficiency.tree.prereq_t3",
                            ProficiencyNodes.TIER3_LEVEL_REQ).withStyle(net.minecraft.ChatFormatting.DARK_GRAY);
        };
    }

    /** 节点图标：以原版/千相物品代形（每节点一个贴切图标）。 */
    private static ItemStack nodeIcon(ProficiencyNodes.Node node) {
        return switch (node.id()) {
            case "blade1" -> new ItemStack(Items.IRON_SWORD);
            case "swift1" -> new ItemStack(Items.FEATHER);
            case "harvest" -> new ItemStack(Items.GOLDEN_APPLE);
            case "blade2" -> new ItemStack(Items.DIAMOND_SWORD);
            case "pierce" -> new ItemStack(Items.IRON_AXE);
            case "combo" -> new ItemStack(Items.CHAIN);
            case "execute" -> new ItemStack(Items.NETHERITE_SWORD);
            case "warcry" -> new ItemStack(Items.GOAT_HORN);
            case "bulwark" -> new ItemStack(Items.SHIELD);
            case "mana1" -> new ItemStack(Items.LAPIS_LAZULI);
            case "focus1" -> new ItemStack(Items.AMETHYST_SHARD);
            case "well" -> new ItemStack(Items.WATER_BUCKET);
            case "mana2" -> new ItemStack(Items.DIAMOND);
            case "quickcool" -> new ItemStack(Items.CLOCK);
            case "focus2" -> new ItemStack(Items.NETHER_STAR);
            case "ripple" -> new ItemStack(Items.PRISMARINE_SHARD);
            case "surge" -> new ItemStack(Items.HEART_OF_THE_SEA);
            case "overload" -> new ItemStack(Items.FIRE_CHARGE);
            case "thrift" -> new ItemStack(Items.IRON_INGOT);
            case "adept" -> new ItemStack(Items.ANVIL);
            case "network" -> new ItemStack(Items.EMERALD);
            case "artisan" -> new ItemStack(Items.SMITHING_TABLE);
            case "lore" -> new ItemStack(Items.BOOK);
            case "inspire" -> new ItemStack(Items.WRITABLE_BOOK);
            case "thrift2" -> new ItemStack(Items.GOLD_INGOT);
            case "master" -> new ItemStack(Items.NETHERITE_INGOT);
            case "midas" -> new ItemStack(Items.GOLD_BLOCK);
            default -> new ItemStack(Items.BOOK);
        };
    }

    // ============================ 主职业页签 ============================

    /** 元素图标（9 元素，原版物品代形）。 */
    private static ItemStack elementIcon(String element) {
        return new ItemStack(switch (element) {
            case "fire" -> Items.BLAZE_POWDER;
            case "frost" -> Items.SNOWBALL;
            case "lightning" -> Items.LIGHTNING_ROD;
            case "nature" -> Items.OAK_SAPLING;
            case "shadow" -> Items.BLACK_DYE;
            case "holy" -> Items.GLOWSTONE_DUST;
            case "blood" -> Items.SPIDER_EYE;
            case "ender" -> Items.CHORUS_FRUIT;
            default -> Items.AMETHYST_SHARD; // arcane
        });
    }

    /** 形态图标（9 形态 + staff，原版物品代形）。 */
    private static ItemStack formIcon(String form) {
        return new ItemStack(switch (form) {
            case "greatsword" -> Items.DIAMOND_SWORD;
            case "dagger" -> Items.STONE_SWORD;
            case "katana" -> Items.WOODEN_SWORD;
            case "spear" -> Items.TRIDENT;
            case "axe" -> Items.IRON_AXE;
            case "hammer" -> Items.ANVIL;
            case "scythe" -> Items.IRON_HOE;
            case "mace" -> Items.MACE;
            case "staff" -> Items.STICK;
            default -> Items.IRON_SWORD; // sword
        });
    }

    private static Component elementName(String element) {
        return Component.translatable("qianxiang.spell.element." + element);
    }

    private static Component formName(String form) {
        return Component.translatable("qianxiang.weapon_form." + form);
    }

    /** 主职业页签内容：当前内核 / 8 预设模板 / 自定义两行选择器 + 确认。 */
    private void renderClassCore(GuiGraphics g, int x0, int y0, int mouseX, int mouseY) {
        // 当前内核行
        boolean set = ClientProficiencyData.classCoreSet();
        Component current = set
                ? Component.translatable("qianxiang.classcore.current",
                        elementName(ClientProficiencyData.classElementA),
                        elementName(ClientProficiencyData.classElementB),
                        formName(ClientProficiencyData.classForm))
                : Component.translatable("qianxiang.classcore.unset");
        g.drawString(this.font, current, x0 + 8, y0 + 24, set ? CREAM : GRAY, false);

        // 预设模板（4 列 × 2 行）
        g.drawString(this.font, Component.translatable("qianxiang.classcore.templates"),
                x0 + 8, y0 + 36, GRAY, false);
        int i = 0;
        for (var entry : com.qianxiang.cap.ClassCore.TEMPLATES.entrySet()) {
            int col = i % 4, row = i / 4;
            int bx = x0 + 8 + col * 61, by = y0 + 46 + row * 16;
            boolean hover = mouseX >= bx && mouseX < bx + 58 && mouseY >= by && mouseY < by + 14;
            g.fill(bx, by, bx + 58, by + 14, hover ? WOOD_LIGHT : SLOT_DARK);
            border(g, bx, by, 58, 14, hover ? COPPER : COPPER_DARK);
            g.drawCenteredString(this.font,
                    Component.translatable("qianxiang.classcore.template." + entry.getKey()),
                    bx + 29, by + 3, hover ? CREAM : CREAM);
            i++;
        }

        // 自定义：元素 9 选 2 + 形态 9 选 1 + 确认
        g.drawString(this.font, Component.translatable("qianxiang.classcore.element_pick"),
                x0 + 8, y0 + 82, GRAY, false);
        for (int e = 0; e < com.qianxiang.cap.ClassCore.ELEMENTS.size(); e++) {
            String element = com.qianxiang.cap.ClassCore.ELEMENTS.get(e);
            int cx = x0 + 8 + e * 18, cy = y0 + 92;
            boolean picked = element.equals(pickElementA) || element.equals(pickElementB);
            g.fill(cx, cy, cx + 16, cy + 16, SLOT_DARK);
            border(g, cx, cy, 16, 16, picked ? GREEN : 0xFF565656);
            g.renderItem(elementIcon(element), cx, cy);
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                g.renderTooltip(this.font, elementName(element), mouseX, mouseY);
            }
        }
        g.drawString(this.font, Component.translatable("qianxiang.classcore.form_pick"),
                x0 + 8, y0 + 114, GRAY, false);
        for (int f = 0; f < com.qianxiang.cap.ClassCore.FORMS.size(); f++) {
            String form = com.qianxiang.cap.ClassCore.FORMS.get(f);
            int cx = x0 + 8 + f * 18, cy = y0 + 124;
            boolean picked = form.equals(pickForm);
            g.fill(cx, cy, cx + 16, cy + 16, SLOT_DARK);
            border(g, cx, cy, 16, 16, picked ? GREEN : 0xFF565656);
            g.renderItem(formIcon(form), cx, cy);
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                g.renderTooltip(this.font, formName(form), mouseX, mouseY);
            }
        }

        // 确认按钮（凑齐 2 元素 + 1 形态才亮；费用提示）
        boolean ready = !pickElementA.isEmpty() && !pickElementB.isEmpty() && !pickForm.isEmpty();
        int bx = x0 + PANEL_W - 76, by = y0 + 128, bw = 68, bh = 12;
        boolean hover = ready && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        g.fill(bx, by, bx + bw, by + bh, ready ? (hover ? WOOD_LIGHT : SLOT_DARK) : 0x80101010);
        border(g, bx, by, bw, bh, ready ? COPPER : 0xFF3C3C3C);
        g.drawCenteredString(this.font, Component.translatable("qianxiang.classcore.confirm"),
                bx + bw / 2, by + 2, ready ? CREAM : GRAY);
        g.drawString(this.font, Component.translatable(set
                        ? "qianxiang.classcore.respec_cost" : "qianxiang.classcore.first_free"),
                x0 + 8, y0 + 144, GRAY, false);
    }

    /** 主职业页签点击：模板一键选 / 元素双选切换 / 形态单选 / 确认。 */
    private boolean classCoreClicked(double mouseX, double mouseY, int x0, int y0) {
        // 模板
        int i = 0;
        for (var entry : com.qianxiang.cap.ClassCore.TEMPLATES.entrySet()) {
            int col = i % 4, row = i / 4;
            int bx = x0 + 8 + col * 61, by = y0 + 46 + row * 16;
            if (mouseX >= bx && mouseX < bx + 58 && mouseY >= by && mouseY < by + 14) {
                PacketDistributor.sendToServer(
                        com.qianxiang.network.SetClassCorePayload.template(entry.getKey()));
                return true;
            }
            i++;
        }
        // 元素格（双选：点已选的取消，点未选的填进较旧槽位）
        for (int e = 0; e < com.qianxiang.cap.ClassCore.ELEMENTS.size(); e++) {
            String element = com.qianxiang.cap.ClassCore.ELEMENTS.get(e);
            int cx = x0 + 8 + e * 18, cy = y0 + 92;
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                if (element.equals(pickElementA)) {
                    pickElementA = pickElementB;
                    pickElementB = "";
                } else if (element.equals(pickElementB)) {
                    pickElementB = "";
                } else {
                    pickElementA = pickElementB;
                    pickElementB = element;
                }
                return true;
            }
        }
        // 形态格（单选）
        for (int f = 0; f < com.qianxiang.cap.ClassCore.FORMS.size(); f++) {
            String form = com.qianxiang.cap.ClassCore.FORMS.get(f);
            int cx = x0 + 8 + f * 18, cy = y0 + 124;
            if (mouseX >= cx && mouseX < cx + 16 && mouseY >= cy && mouseY < cy + 16) {
                pickForm = form;
                return true;
            }
        }
        // 确认
        boolean ready = !pickElementA.isEmpty() && !pickElementB.isEmpty() && !pickForm.isEmpty();
        int bx = x0 + PANEL_W - 76, by = y0 + 128, bw = 68, bh = 12;
        if (ready && mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh) {
            PacketDistributor.sendToServer(com.qianxiang.network.SetClassCorePayload.custom(
                    pickElementA, pickElementB, pickForm));
            return true;
        }
        return false;
    }

    // ============================ 底部：主动技能冷却 + 洗点 ============================

    private void renderFooter(GuiGraphics g, int x0, int y0, int mouseX, int mouseY) {
        int fy = y0 + PANEL_H - 20;
        // 主动技能冷却读秒（快照毫秒 → 秒；就绪显示✓）
        String warcry = skillLine("warcry",
                ClientProficiencyData.warcryCooldownMs, ClientProficiencyData.warcryActiveMs);
        String surge = skillLine("surge", ClientProficiencyData.surgeCooldownMs, 0);
        g.drawString(this.font, warcry, x0 + 8, fy, CREAM, false);
        g.drawString(this.font, surge, x0 + 78, fy, CREAM, false);

        // 洗点按钮（二次确认；未开启/全未用时置灰）
        int bx = x0 + PANEL_W - 96, bw = 88, bh = 12;
        boolean canRespec = ClientProficiencyData.unlocked
                && !ClientProficiencyData.allocated.isEmpty();
        boolean armed = respecArmUntilMs > System.currentTimeMillis();
        boolean hover = canRespec && mouseX >= bx && mouseX < bx + bw && mouseY >= fy - 1 && mouseY < fy - 1 + bh;
        g.fill(bx, fy - 1, bx + bw, fy - 1 + bh, canRespec ? (hover ? WOOD_LIGHT : SLOT_DARK) : 0x80101010);
        border(g, bx, fy - 1, bw, bh, canRespec ? COPPER_DARK : 0xFF3C3C3C);
        g.drawCenteredString(this.font,
                Component.translatable(armed
                        ? "qianxiang.proficiency.tree.respec_confirm" : "qianxiang.proficiency.tree.respec"),
                bx + bw / 2, fy + 1, canRespec ? (armed ? 0xFFFF5555 : CREAM) : GRAY);
    }

    private static String skillLine(String skillId, int cooldownMs, int activeMs) {
        String name = Component.translatable("qianxiang.proficiency.node." + skillId + ".name").getString();
        if (activeMs > 0) {
            return name + " §a" + (activeMs + 999) / 1000 + "s";
        }
        return cooldownMs > 0 ? name + " §7" + (cooldownMs + 999) / 1000 + "s" : name + " §a✓";
    }

    // ============================ 交互 ============================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x0 = x0(), y0 = y0();
        if (ClientProficiencyData.unlocked && button == 0) {
            // 页签（三轨 + 主职业）
            for (int i = 0; i < TRACKS.length + 1; i++) {
                int tx = x0 + 8 + i * (TAB_W + 1);
                int ty = y0 + 6;
                if (mouseX >= tx && mouseX < tx + TAB_W && mouseY >= ty && mouseY < ty + TAB_H) {
                    tabIndex = i;
                    if (i == CLASS_TAB) {
                        // 打开时从当前内核初始化选择器（已设的项预填，便于微调）
                        pickElementA = ClientProficiencyData.classElementA;
                        pickElementB = ClientProficiencyData.classElementB;
                        pickForm = ClientProficiencyData.classForm;
                    }
                    return true;
                }
            }
            if (tabIndex == CLASS_TAB) {
                return classCoreClicked(mouseX, mouseY, x0, y0) || true;
            }
            // 节点
            List<ProficiencyNodes.Node> nodes = trackNodes();
            int startX = x0 + (PANEL_W - (3 * CELL + 2 * 46)) / 2;
            int startY = y0 + 46;
            for (int i = 0; i < nodes.size(); i++) {
                int cx = startX + (i % 3) * (CELL + 46);
                int cy = startY + (i / 3) * (CELL + 8);
                if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                    ProficiencyNodes.Node node = nodes.get(i);
                    if (nodeState(node) == 1) {
                        PacketDistributor.sendToServer(new AllocateNodePayload(node.id()));
                    }
                    return true;
                }
            }
            // 洗点按钮（二次确认：5s 内再点一次才发）
            int bx = x0 + PANEL_W - 96, bw = 88, bh = 12;
            int fy = y0 + PANEL_H - 20;
            if (ClientProficiencyData.unlocked && !ClientProficiencyData.allocated.isEmpty()
                    && mouseX >= bx && mouseX < bx + bw && mouseY >= fy - 1 && mouseY < fy - 1 + bh) {
                if (respecArmUntilMs > System.currentTimeMillis()) {
                    respecArmUntilMs = 0L;
                    PacketDistributor.sendToServer(new RespecPayload());
                } else {
                    respecArmUntilMs = System.currentTimeMillis() + 5_000L;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // K 同键关闭（与打开同键位）
        if (SpellKeybinds.SKILL_TREE.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }
}
