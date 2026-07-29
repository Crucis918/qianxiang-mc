package com.qianxiang.client;

import com.qianxiang.network.ProficiencySyncPayload;

import java.util.Set;

/**
 * 客户端熟练度缓存（技能树 GUI 下一步读取的数据源，本步建壳）。
 * <p>字段与 {@link ProficiencySyncPayload} 一一对应；
 * 登录/重生/换维度/每次变化由服务端全量推送覆盖，断线清空（见 {@code ClientStateReset}）。</p>
 */
public final class ClientProficiencyData {

    private ClientProficiencyData() {}

    public static boolean unlocked;
    public static int combatXp, arcaneXp, craftXp;
    public static int combatLevel, arcaneLevel, craftLevel;
    public static int combatPoints, arcanePoints, craftPoints;
    public static Set<String> allocated = Set.of();

    /** 是否有待打开的技能树界面请求（OpenSkillTreePayload 到达置位，GUI 步消费后清）。 */
    public static boolean pendingOpenTree;

    /** 涌动剩余冷却（毫秒快照，0 = 就绪）。 */
    public static int surgeCooldownMs;
    /** 战吼剩余冷却（毫秒快照，0 = 就绪）。 */
    public static int warcryCooldownMs;
    /** 战吼生效剩余（毫秒快照，0 = 未生效；HUD 显示用）。 */
    public static int warcryActiveMs;

    /** 主职业内核（未设定 = 全空串，职业效果全 ×1.0）。 */
    public static String classElementA = "", classElementB = "", classForm = "";

    /** 内核是否已设定（轮盘压暗非内核法术用）。 */
    public static boolean classCoreSet() {
        return !classElementA.isEmpty() && !classElementB.isEmpty() && !classForm.isEmpty();
    }

    /** 元素是否在内核内（未设定恒 false——未设定时轮盘不压暗任何东西）。 */
    public static boolean isCoreElement(String element) {
        return classCoreSet() && (classElementA.equals(element) || classElementB.equals(element));
    }

    public static void receive(ProficiencySyncPayload payload) {
        unlocked = payload.unlocked();
        combatXp = payload.combatXp();
        arcaneXp = payload.arcaneXp();
        craftXp = payload.craftXp();
        combatLevel = payload.combatLevel();
        arcaneLevel = payload.arcaneLevel();
        craftLevel = payload.craftLevel();
        combatPoints = payload.combatPoints();
        arcanePoints = payload.arcanePoints();
        craftPoints = payload.craftPoints();
        allocated = payload.allocated();
        surgeCooldownMs = payload.surgeCooldownMs();
        warcryCooldownMs = payload.warcryCooldownMs();
        warcryActiveMs = payload.warcryActiveMs();
        classElementA = payload.classElementA();
        classElementB = payload.classElementB();
        classForm = payload.classForm();
    }

    /** OpenSkillTreePayload 到达（GUI 步在此打开技能树界面）。 */
    public static void onOpenSkillTree() {
        pendingOpenTree = true;
    }

    /** 切换世界/断线时清空（ClientStateReset 登记）。 */
    public static void clear() {
        unlocked = false;
        combatXp = arcaneXp = craftXp = 0;
        combatLevel = arcaneLevel = craftLevel = 0;
        combatPoints = arcanePoints = craftPoints = 0;
        allocated = Set.of();
        pendingOpenTree = false;
        surgeCooldownMs = 0;
        warcryCooldownMs = 0;
        warcryActiveMs = 0;
        classElementA = classElementB = classForm = "";
    }
}
