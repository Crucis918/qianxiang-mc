package com.qianxiang.cap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 熟练度节点注册表：3 轨 × 3 阶 × 3 = 27 节点。
 * <p>
 * 节点 id 全局唯一；T1 无前置，T2 需本轨任一 T1 + 轨等级 ≥ 4，T3 需本轨任一 T2 + 轨等级 ≥ 8。
 * 效果数值的<b>唯一实现口径</b>在 {@link ProficiencyHelper} 的查询方法里——
 * 这里只登记结构（id/轨/阶），平衡参数集中在 Helper 顶部常量（注释「供调平」）。
 * </p>
 */
public final class ProficiencyNodes {

    private ProficiencyNodes() {}

    /** 一个技能节点：id（lang 键后缀）、所属轨、阶（1/2/3）。 */
    public record Node(String id, ProficiencyTrack track, int tier) {}

    /** T2 解锁所需轨等级。 */
    public static final int TIER2_LEVEL_REQ = 4;
    /** T3 解锁所需轨等级。 */
    public static final int TIER3_LEVEL_REQ = 8;

    private static final Map<String, Node> NODES = new LinkedHashMap<>();
    static {
        // ---- 战斗 combat ----
        reg("blade1", ProficiencyTrack.COMBAT, 1);   // +5% 近战伤
        reg("swift1", ProficiencyTrack.COMBAT, 1);   // +5% 攻速
        reg("harvest", ProficiencyTrack.COMBAT, 1);  // 击杀回 2 血
        reg("blade2", ProficiencyTrack.COMBAT, 2);   // +10% 近战伤（叠 blade1）
        reg("pierce", ProficiencyTrack.COMBAT, 2);   // 对高甲再 +4%
        reg("combo", ProficiencyTrack.COMBAT, 2);    // 3s 内连击递增 4%/段，上限 3 段
        reg("execute", ProficiencyTrack.COMBAT, 3);  // 目标 <20% 血 +25%
        reg("warcry", ProficiencyTrack.COMBAT, 3);   // 主动：10s +20% 攻速移速，60s 冷却
        reg("bulwark", ProficiencyTrack.COMBAT,3);   // -12% 受伤

        // ---- 法术 arcane ----
        reg("mana1", ProficiencyTrack.ARCANE, 1);    // -8% 蓝耗
        reg("focus1", ProficiencyTrack.ARCANE, 1);   // +8% 法术强度
        reg("well", ProficiencyTrack.ARCANE, 1);     // +10 法力上限
        reg("mana2", ProficiencyTrack.ARCANE, 2);    // -15% 蓝耗
        reg("quickcool", ProficiencyTrack.ARCANE, 2);// -15% 冷却
        reg("focus2", ProficiencyTrack.ARCANE, 2);   // +15% 法术强度
        reg("ripple", ProficiencyTrack.ARCANE, 3);   // 10% 概率施法免蓝
        reg("surge", ProficiencyTrack.ARCANE, 3);    // 主动：瞬回 40 蓝，90s 冷却
        reg("overload", ProficiencyTrack.ARCANE, 3); // power≥6 法术 +20%

        // ---- 技艺 craft ----
        reg("thrift", ProficiencyTrack.CRAFT, 1);    // 锻造 6% 返一件材料
        reg("adept", ProficiencyTrack.CRAFT, 1);     // 仪式提速 25%
        reg("network", ProficiencyTrack.CRAFT, 1);   // 交易 5% 折扣
        reg("artisan", ProficiencyTrack.CRAFT, 2);   // 产物 powerScore +5%
        reg("lore", ProficiencyTrack.CRAFT, 2);      // 蓝图保存位 +4
        reg("inspire", ProficiencyTrack.CRAFT, 2);   // AI 请求冷却减半
        reg("thrift2", ProficiencyTrack.CRAFT, 3);   // 返还提升至 12%
        reg("master", ProficiencyTrack.CRAFT, 3);    // 产物耐久 +15%
        reg("midas", ProficiencyTrack.CRAFT, 3);     // 炼金卷轴 10% power+1
    }

    private static void reg(String id, ProficiencyTrack track, int tier) {
        NODES.put(id, new Node(id, track, tier));
    }

    /** 按 id 查节点；未知 id 返回 null。 */
    public static Node byId(String id) {
        return NODES.get(id);
    }

    /** 全部节点（注册序，只读）。 */
    public static Map<String, Node> all() {
        return Collections.unmodifiableMap(NODES);
    }

    /** 该轨某阶是否已有任一已分配节点（T2/T3 前置判定的组成）。 */
    public static boolean anyAllocated(PlayerProficiencyData data, ProficiencyTrack track, int tier) {
        for (Node node : NODES.values()) {
            if (node.track() == track && node.tier() == tier && data.hasAllocated(node.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分配前置校验（不含「有点/未点过/unlocked」——那三条在 {@link ProficiencyHelper#allocate}）：
     * T1 恒真；T2 需本轨任一 T1 + 轨等级 ≥ {@link #TIER2_LEVEL_REQ}；
     * T3 需本轨任一 T2 + 轨等级 ≥ {@link #TIER3_LEVEL_REQ}。
     */
    public static boolean prereqMet(PlayerProficiencyData data, Node node) {
        return switch (node.tier()) {
            case 1 -> true;
            case 2 -> anyAllocated(data, node.track(), 1)
                    && data.levelOf(node.track()) >= TIER2_LEVEL_REQ;
            default -> anyAllocated(data, node.track(), 2)
                    && data.levelOf(node.track()) >= TIER3_LEVEL_REQ;
        };
    }
}
