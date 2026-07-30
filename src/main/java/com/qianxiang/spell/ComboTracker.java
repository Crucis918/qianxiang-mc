package com.qianxiang.spell;

import com.qianxiang.Qianxiang;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 荣耀连招系统（服务端核心）：交替施放<b>不同 id</b> 的法术形成连击。
 * <p>
 * 规则（由 {@link SpellCastHandler#castCustomSpell} 在通过冷却/法力校验后调用）：
 * </p>
 * <ul>
 *   <li>{@value #WINDOW_TICKS} tick（5 秒）内换上另一个 id 的法术 → combo+1，封顶 {@value #MAX_COMBO}；</li>
 *   <li>同 id 重复 → combo 重置为 1；超窗 → 计数清零，本次施法从 1 重计；</li>
 *   <li>断连只清计数，不回滚已打出的加成；</li>
 *   <li>combo≥{@value #MIN_BONUS_COMBO} 起每级 +4% 法术伤害（乘区，
 *       见 {@link #damageMultiplier(int)}，与增幅器/熟练度乘区叠乘）。</li>
 * </ul>
 * <p>
 * 状态是纯内存的（连击本质上是 5 秒窗口的瞬态机制，不值得落盘），
 * 玩家登出时清理。combo 每次成功施法都会变化，由调用方负责同步客户端。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class ComboTracker {

    private ComboTracker() {}

    /** 连击窗口：5 秒 = 100 tick。 */
    public static final int WINDOW_TICKS = 100;
    /** 连击等级上限。 */
    public static final int MAX_COMBO = 10;
    /** 伤害加成生效的最低连击数。 */
    public static final int MIN_BONUS_COMBO = 3;
    /** 每级连击的伤害加成（乘区）。 */
    public static final double BONUS_PER_LEVEL = 0.04;

    private record State(ResourceLocation lastSpellId, long lastCastGameTime, int combo) {}

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /**
     * 记录一次成功施法并返回最新连击数（1 = 新连击起点）。
     * <p>只记计数、不碰网络——调用方（施法链路）拿返回值算伤害倍率并同步客户端。</p>
     */
    public static int onCast(ServerPlayer player, ResourceLocation spellId) {
        long now = player.level().getGameTime();
        State prev = STATES.get(player.getUUID());
        int combo;
        if (prev == null
                || spellId.equals(prev.lastSpellId())
                || now - prev.lastCastGameTime() > WINDOW_TICKS) {
            // 首次 / 同 id 重按 / 超窗：一律回到 1（超窗即「清零后本次重计」）
            combo = 1;
        } else {
            // 上限按职业：战斗法师 12，其余默认 10（ClassCoreHelper 查询，未设内核默认）
            combo = Math.min(com.qianxiang.cap.ClassCoreHelper.comboCap(player), prev.combo() + 1);
        }
        STATES.put(player.getUUID(), new State(spellId, now, combo));
        return combo;
    }

    /**
     * 当前连击数（懒过期：超窗直接视为 0，不需要服务端主动 tick 清理）。
     * <p>HUD 同步与 GameTest 断言都读这里。</p>
     */
    public static int comboOf(ServerPlayer player) {
        State state = STATES.get(player.getUUID());
        if (state == null) return 0;
        if (player.level().getGameTime() - state.lastCastGameTime() > WINDOW_TICKS) return 0;
        return state.combo();
    }

    /**
     * 连击伤害乘区：combo≥{@value #MIN_BONUS_COMBO} 起为
     * {@code 1 + 0.04 × (combo - 2)}，combo={@value #MAX_COMBO} 时 ×1.32；
     * 低于阈值一律 ×1.0（无加成）。与增幅器/熟练度乘区叠乘。
     * <p>旧签名（客户端估算/默认参数）：上限 10、每段 +4%。</p>
     */
    public static double damageMultiplier(int combo) {
        int clamped = Math.min(combo, MAX_COMBO);
        if (clamped < MIN_BONUS_COMBO) return 1.0;
        return 1.0 + BONUS_PER_LEVEL * (clamped - (MIN_BONUS_COMBO - 1));
    }

    /**
     * 服务端权威乘区（按玩家职业参数化）：上限与每段加成走
     * {@code ClassCoreHelper.comboCap/comboPerStack}——
     * 剑客每段 +6%、战斗法师上限 12 且每段 +5%，其余职业默认。
     */
    public static double damageMultiplier(net.minecraft.world.entity.player.Player player, int combo) {
        int clamped = Math.min(combo, com.qianxiang.cap.ClassCoreHelper.comboCap(player));
        if (clamped < MIN_BONUS_COMBO) return 1.0;
        return 1.0 + com.qianxiang.cap.ClassCoreHelper.comboPerStack(player)
                * (clamped - (MIN_BONUS_COMBO - 1));
    }

    /** 登出清理：内存表不随连接泄漏。 */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }
}
