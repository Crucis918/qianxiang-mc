package com.qianxiang.client;

import com.qianxiang.Qianxiang;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.network.CastSpellPayload;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 轮盘施法 overlay（HUD 层，非 Screen，不暂停游戏）。
 * <p>
 * 交互状态机（按下/松开沿由 {@link ClientSpellInput} 检出后调 {@link #onPress}/{@link #onRelease}）：
 * <ul>
 *   <li>按下 V：已学 0 个 → actionbar 提示（去炼金台学卷轴）；1 个 → 直接施放；
 *       ≥2 个 → 开轮盘（释放鼠标指针，游戏不暂停）。</li>
 *   <li>轮盘开着：鼠标移出中心死区指向某扇区 = 选中（高亮）；滚轮翻页（每页 8 个）。</li>
 *   <li>松开 V：有选中 → 施放该法术；无选中且「按下到松开未指出死区且时长 &lt; {@link #TAP_MS}」
 *       → 点按语义，快速施放上次施放的法术（无上次记录则提示）；否则 = 取消。</li>
 *   <li>无论哪条路径，客户端都把<b>具体 spellId</b> 发给服务端（{@link CastSpellPayload}），
 *       服务端只认明确 id；「上次施放」纯客户端记忆，服务端无此语义。</li>
 * </ul>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class SpellWheelOverlay {

    private SpellWheelOverlay() {}

    /** 每页扇区数（圆环 8 等分）。 */
    private static final int SECTORS = 8;
    /** 中心死区半径（GUI 缩放像素）：进入此区域视为「未指向」。 */
    private static final double DEAD_RADIUS = 14.0;
    /** 扇区标签盒中心到轮盘中心的距离。 */
    private static final double RING_RADIUS = 56.0;
    /** 扇区标签盒尺寸。 */
    private static final int BOX_W = 44, BOX_H = 16;
    /** 「点按」判定时长上限（毫秒）：按下到松开短于此且未指出死区 = 快速施放上次。 */
    private static final long TAP_MS = 300L;

    private static boolean active;
    private static int page;
    private static long pressMillis;
    /** 本次按压期间鼠标是否曾指出死区（区分「点按」与「指向后回到中心取消」）。 */
    private static boolean movedOutsideDeadZone;

    // ============================ 按键沿（ClientSpellInput 调用） ============================

    /** 按下 V：按已学数量分派——0 提示 / 1 直接放 / ≥2 开轮盘。 */
    public static void onPress(Minecraft mc) {
        if (mc.player == null) return;
        List<CustomSpell> learned = learnedSpells(mc);
        if (learned.isEmpty()) {
            mc.player.displayClientMessage(Component.translatable("qianxiang.wheel.empty"), true);
            return;
        }
        if (learned.size() == 1) {
            cast(mc, learned.get(0));
            return;
        }
        active = true;
        page = 0;
        pressMillis = Util.getMillis();
        movedOutsideDeadZone = false;
        // 轮盘需要自由指针：松开鼠标抓取（相机停止跟随），关闭时重新抓取。
        mc.mouseHandler.releaseMouse();
    }

    /** 松开 V：结算选中/点按/取消（见类文档状态机）。 */
    public static void onRelease(Minecraft mc) {
        if (!active) return;
        active = false;
        mc.mouseHandler.grabMouse();
        if (mc.player == null) return;

        List<CustomSpell> spells = pageSpells(mc);
        int sel = selectedSector(mc);
        if (sel >= 0 && sel < spells.size()) {
            cast(mc, spells.get(sel));
            return;
        }
        if (!movedOutsideDeadZone && Util.getMillis() - pressMillis < TAP_MS) {
            castLast(mc);
        }
        // 其余情况（指出死区后回到中心松开）= 取消，什么都不发
    }

    /** 界面打开/世界切换等打断轮盘时取消（不施放、不抢鼠标——由 Screen 管指针）。 */
    public static void cancelIfActive() {
        active = false;
    }

    /** 切换世界/断线时重置（{@link ClientStateReset} 登记）。 */
    public static void resetForWorldChange() {
        active = false;
        page = 0;
        movedOutsideDeadZone = false;
    }

    public static boolean isActive() {
        return active;
    }

    // ============================ 渲染与输入 ============================

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            active = false;
            return;
        }
        // 指针曾指出死区即记录（区分点按与「指向后回中心取消」）
        if (mouseDistFromCenter(mc) > DEAD_RADIUS) {
            movedOutsideDeadZone = true;
        }

        GuiGraphics g = event.getGuiGraphics();
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        List<CustomSpell> spells = pageSpells(mc);
        int sel = selectedSector(mc);

        // 中心死区标记
        g.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0x90000000);

        for (int i = 0; i < SECTORS; i++) {
            double ang = Math.toRadians(-90 + i * (360.0 / SECTORS));
            int bx = cx + (int) Math.round(Math.cos(ang) * RING_RADIUS);
            int by = cy + (int) Math.round(Math.sin(ang) * RING_RADIUS);
            int x0 = bx - BOX_W / 2, y0 = by - BOX_H / 2, x1 = bx + BOX_W / 2, y1 = by + BOX_H / 2;

            boolean has = i < spells.size();
            if (!has) {
                g.fill(x0, y0, x1, y1, 0x30000000);
                continue;
            }
            CustomSpell spell = spells.get(i);
            boolean hovered = sel == i;
            boolean onCooldown = ClientSpellData.estimatedCooldownRemaining(mc.player, spell) > 0;

            int fill = onCooldown ? 0xC0444444
                    : hovered ? brighten(elementColor(spell.element()))
                    : withAlpha(elementColor(spell.element()), 0xB0);
            g.fill(x0, y0, x1, y1, fill);
            if (hovered) {
                // 高亮描边
                g.fill(x0, y0, x1, y0 + 1, 0xFFFFFFFF);
                g.fill(x0, y1 - 1, x1, y1, 0xFFFFFFFF);
                g.fill(x0, y0, x0 + 1, y1, 0xFFFFFFFF);
                g.fill(x1 - 1, y0, x1, y1, 0xFFFFFFFF);
            }

            String name = mc.font.plainSubstrByWidth(spellName(spell).getString(), BOX_W - 4);
            int textColor = onCooldown ? 0xFF888888 : 0xFFFFFFFF;
            g.drawString(mc.font, name, bx - mc.font.width(name) / 2, by - 4, textColor, true);
        }

        // 顶部操作提示
        Component hint = Component.translatable("qianxiang.wheel.hint");
        g.drawString(mc.font, hint, cx - mc.font.width(hint) / 2,
                cy - (int) RING_RADIUS - BOX_H - 12, 0xAAAAAA, true);

        // 页码（已学超过一页时）
        int totalPages = totalPages(mc);
        if (totalPages > 1) {
            Component pageText = Component.translatable("qianxiang.wheel.page", page + 1, totalPages);
            g.drawString(mc.font, pageText, cx - mc.font.width(pageText) / 2,
                    cy + (int) RING_RADIUS + BOX_H + 4, 0xAAAAAA, true);
        }
    }

    /** 滚轮翻页（仅轮盘开着时；吞掉事件防止快捷栏跟着滚）。 */
    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        int total = totalPages(mc);
        double delta = event.getScrollDeltaY();
        if (total > 1 && delta != 0) {
            page = Math.floorMod(page + (delta < 0 ? 1 : -1), total);
        }
        event.setCanceled(true);
    }

    // ============================ 内部 ============================

    /** 施放指定法术：记录上次施放 + 冷却估算起点，发明确 id 给服务端。 */
    private static void cast(Minecraft mc, CustomSpell spell) {
        ClientSpellData.recordCast(spell, mc.player);
        try {
            PacketDistributor.sendToServer(new CastSpellPayload(spell.id().toString()));
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] 发送施法包失败（不影响轮盘）", t);
        }
    }

    /** 点按语义：快速施放上次施放的法术（无记录/已忘记则提示）。 */
    private static void castLast(Minecraft mc) {
        String lastId = ClientSpellData.lastCastSpellId();
        if (lastId == null || mc.player == null) {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("qianxiang.wheel.no_last"), true);
            }
            return;
        }
        var last = mc.player.getData(QianxiangAttachments.PLAYER_SPELL_DATA)
                .findLearned(net.minecraft.resources.ResourceLocation.parse(lastId));
        if (last.isEmpty()) {
            mc.player.displayClientMessage(Component.translatable("qianxiang.wheel.no_last"), true);
            return;
        }
        cast(mc, last.get());
    }

    private static List<CustomSpell> learnedSpells(Minecraft mc) {
        if (mc.player == null) return List.of();
        return mc.player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).learnedSpells();
    }

    private static int totalPages(Minecraft mc) {
        return Math.max(1, (learnedSpells(mc).size() + SECTORS - 1) / SECTORS);
    }

    /** 当前页的法术（≤8 个）。 */
    private static List<CustomSpell> pageSpells(Minecraft mc) {
        List<CustomSpell> learned = learnedSpells(mc);
        int from = page * SECTORS;
        if (from >= learned.size()) return List.of();
        return learned.subList(from, Math.min(from + SECTORS, learned.size()));
    }

    /** 鼠标（GUI 缩放坐标）到屏幕中心的距离。 */
    private static double mouseDistFromCenter(Minecraft mc) {
        double scaleX = (double) mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double scaleY = (double) mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        double mx = mc.mouseHandler.xpos() * scaleX;
        double my = mc.mouseHandler.ypos() * scaleY;
        return Math.hypot(mx - mc.getWindow().getGuiScaledWidth() / 2.0,
                my - mc.getWindow().getGuiScaledHeight() / 2.0);
    }

    /** 鼠标方位命中的扇区下标（0=正上方，顺时针）；死区内返回 -1。 */
    private static int selectedSector(Minecraft mc) {
        if (mouseDistFromCenter(mc) < DEAD_RADIUS) return -1;
        double scaleX = (double) mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double scaleY = (double) mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        double dx = mc.mouseHandler.xpos() * scaleX - mc.getWindow().getGuiScaledWidth() / 2.0;
        double dy = mc.mouseHandler.ypos() * scaleY - mc.getWindow().getGuiScaledHeight() / 2.0;
        // atan2：0°=正东；转成「0°=正北、顺时针」后按 45° 扇区取整（+22.5° 让扇区以正北为中心）。
        double deg = (Math.toDegrees(Math.atan2(dy, dx)) + 90 + 360 + 22.5) % 360;
        return (int) (deg / (360.0 / SECTORS));
    }

    /**
     * 轮盘显示名：有 {@code spell.<ns>.<path>} 翻译键（预置/锻造成语）就用全名；
     * 否则「元素·效果」拼接（复用本地化键，AI 法术也不裸显翻译键）。
     */
    private static Component spellName(CustomSpell spell) {
        String key = "spell." + spell.id().getNamespace() + "." + spell.id().getPath();
        if (I18n.exists(key)) {
            return Component.translatable(key);
        }
        return Component.translatable("qianxiang.spell.wheel.name",
                CustomSpell.elementName(spell.element()),
                CustomSpell.effectName(spell.effect()));
    }

    /** 元素 → 扇区底色（ARGB）。 */
    private static int elementColor(String element) {
        return switch (element == null ? "" : element) {
            case "fire" -> 0xFFE25822;
            case "frost" -> 0xFF7FD4FF;
            case "lightning" -> 0xFFFFF45C;
            case "nature" -> 0xFF5CE892;
            case "shadow" -> 0xFF8E6BC8;
            case "holy" -> 0xFFFFF2B0;
            case "blood" -> 0xFFC02040;
            case "ender" -> 0xFFB678F0;
            default -> 0xFF4ECDC4; // arcane
        };
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    /** 提亮（悬停高亮用）：RGB 各通道向 255 靠 40%。 */
    private static int brighten(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (255 - r) * 2 / 5;
        g += (255 - g) * 2 / 5;
        b += (255 - b) * 2 / 5;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
