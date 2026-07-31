package com.qianxiang.cap;

import com.qianxiang.spell.CustomSpell;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 主职业内核的效果结算与设定/转职（数据查询纯函数 + 写入入口）。
 * <p>
 * <b>调平常量（全部集中在类顶部）</b>：
 * 内核元素法术 伤害 ×{@link #CORE_SPELL_DAMAGE}、蓝耗 ×{@link #CORE_SPELL_MANA}；
 * 非内核元素法术 伤害 ×{@link #OFF_SPELL_DAMAGE}、蓝耗 ×{@link #OFF_SPELL_MANA}（可用但弱）；
 * 内核形态武器 近战 ×{@link #CORE_MELEE_DAMAGE}；
 * 非内核形态武器 近战 ×{@link #OFF_MELEE_DAMAGE}（形态读产物 AppearanceData.form，
 * 空串视为非内核）；未设内核（旧存档）全部 ×1.0。
 * </p>
 * <p>
 * 设定/转职：首次免费，之后每次 {@link #RESPEC_GOLD_COST} 金粒；
 * 写入后记相谱一条并全量同步（与洗点同范式）。
 * </p>
 */
public final class ClassCoreHelper {

    private ClassCoreHelper() {}

    // ===================== 调平常量区 =====================

    /** 内核元素法术伤害倍率。 */
    public static final double CORE_SPELL_DAMAGE = 1.15;
    /** 内核元素法术蓝耗倍率。 */
    public static final double CORE_SPELL_MANA = 0.9;
    /** 非内核元素法术伤害倍率。 */
    public static final double OFF_SPELL_DAMAGE = 0.45;
    /** 非内核元素法术蓝耗倍率。 */
    public static final double OFF_SPELL_MANA = 1.25;
    /** 内核形态武器近战伤害倍率。 */
    public static final double CORE_MELEE_DAMAGE = 1.05;
    /** 非内核形态武器近战伤害倍率。 */
    public static final double OFF_MELEE_DAMAGE = 0.6;
    /** 转职费用（金粒；首次设定免费。黄金经济：金粒是唯一服务费，绿宝石不再收）。 */
    public static final int RESPEC_GOLD_COST = 45;

    // ===================== 招牌被动调平常量区（24 职业，按模板 id 分派） =====================

    /** 剑客：连招每段额外 +2%（perStack 0.04→0.06）。 */
    public static final double SWORDSMAN_COMBO_PER_STACK = 0.06;
    /** 战斗法师：连招上限 12（默认 10）、每段 +5%（默认 +4%）。 */
    public static final int BATTLE_MAGE_COMBO_CAP = 12;
    public static final double BATTLE_MAGE_COMBO_PER_STACK = 0.05;
    /** 魔剑士：施法后近战平增窗口（3s）与幅度。 */
    public static final long SPELLSWORD_WINDOW_TICKS = 60;
    public static final double SPELLSWORD_MELEE_BONUS = 1.15;
    /** 狂战士：血量 <50% 时伤害 +15%（近战与法术同享）。 */
    public static final double BERSERKER_LOW_HP_BONUS = 1.15;
    /** 阵鬼：对减速/冻结目标 +20%。 */
    public static final double REAPER_SLOWED_BONUS = 1.2;
    /** 神枪手：弹体法术 +20% 伤害 + 弹速 ×1.5。 */
    public static final double SHARPSHOOTER_PROJECTILE_DAMAGE = 1.2;
    public static final double SHARPSHOOTER_PROJECTILE_SPEED = 1.5;
    /** 弹药专家：AoE 半径 +15%。 */
    public static final double SAPPER_AOE_RADIUS = 1.15;
    /** 枪炮师：AoE 法术 +25% 伤害。 */
    public static final double ARTILLERY_AOE_DAMAGE = 1.25;
    /** 拳法家：近战攻速 +10%（攻击蓄力每秒多充 10%，见 CompanionHandler tick）。 */
    public static final double PUGILIST_ATTACK_SPEED = 0.10;
    /** 柔道家：近战击退 ×2（命中后补一次同向击退）+ 缓慢 2s。 */
    public static final double JUDOKA_EXTRA_KNOCKBACK = 0.5;
    public static final int JUDOKA_SLOW_TICKS = 40;
    /** 气功师：buff/utility 法术时长 ×1.5。 */
    public static final double QIGONG_DURATION = 1.5;
    /** 流氓：命中上毒 3s + 对中毒目标 +20%。 */
    public static final int ROGUE_POISON_TICKS = 60;
    public static final double ROGUE_POISONED_BONUS = 1.2;
    /** 元素法师：内核元素法术再 +10%（与内核 1.15 叠乘）。 */
    public static final double ELEMENTALIST_CORE_BONUS = 1.1;
    /** 魔道学者：utility 法术冷却 ×0.7。 */
    public static final double SCHOLAR_UTILITY_COOLDOWN = 0.7;
    /** 刺客：背刺（与目标视线夹角 >120°）+30%。 */
    public static final double ASSASSIN_BACKSTAB_BONUS = 1.3;
    public static final double ASSASSIN_MIN_DOT = -0.5; // cos(120°)，目标视线与攻击方向的点积阈值
    /** 盗贼：击杀后隐身 3s。 */
    public static final int THIEF_INVIS_TICKS = 60;
    /** 死灵术士：对非亡灵 +10% 法伤 + 击杀回血 2。 */
    public static final double NECRO_LIVING_BONUS = 1.1;
    public static final float NECRO_KILL_HEAL = 2.0f;
    /** 忍者：潜行状态 +20% 伤害。 */
    public static final double NINJA_SNEAK_BONUS = 1.2;
    /** 牧师：治疗量 +30%。 */
    public static final double PRIEST_HEAL_BONUS = 1.3;
    /** 圣骑士：受伤 -10%。 */
    public static final double PALADIN_INCOMING = 0.9;
    /** 驱魔师：对亡灵 +25% 伤害。 */
    public static final double EXORCIST_UNDEAD_BONUS = 1.25;
    /** 复仇者：击杀回血 3。 */
    public static final float AVENGER_KILL_HEAL = 3.0f;
    /** 伙伴重生等待（60s）。 */
    public static final long COMPANION_RESPAWN_TICKS = 1200;

    // ===================== 效果查询（未设内核全 1.0） =====================

    private static ClassCore coreOf(Player player) {
        if (player == null) return ClassCore.EMPTY;
        return player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA).classCore();
    }

    /** 法术伤害倍率：内核元素 ×1.15 / 非内核 ×0.45 / 未设内核 ×1.0。 */
    public static double spellDamageMult(Player player, CustomSpell spell) {
        ClassCore core = coreOf(player);
        if (!core.isSet() || spell == null) return 1.0;
        return core.hasElement(spell.element()) ? CORE_SPELL_DAMAGE : OFF_SPELL_DAMAGE;
    }

    /** 法术蓝耗倍率：内核元素 ×0.9 / 非内核 ×1.25 / 未设内核 ×1.0。 */
    public static double spellManaCostMult(Player player, CustomSpell spell) {
        ClassCore core = coreOf(player);
        if (!core.isSet() || spell == null) return 1.0;
        return core.hasElement(spell.element()) ? CORE_SPELL_MANA : OFF_SPELL_MANA;
    }

    /** 近战形态倍率：内核形态 ×1.05 / 非内核（含空串形态）×0.6 / 未设内核 ×1.0。 */
    public static double meleeFormMult(Player player, String form) {
        ClassCore core = coreOf(player);
        if (!core.isSet()) return 1.0;
        return core.hasForm(form == null ? "" : form) ? CORE_MELEE_DAMAGE : OFF_MELEE_DAMAGE;
    }

    // ===================== 招牌被动查询（未设内核/自定义不匹配 = 全 1.0 无被动） =====================

    /** 玩家当前职业模板 id：优先取设定时写入的模板 id（swordsman/battle_mage
     *  同内核三元组靠它区分）；空串（自定义/旧存档）回退按三元组首个匹配。 */
    public static String signatureOf(Player player) {
        if (player == null) return "";
        String stored = player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA).classTemplateId();
        if (!stored.isEmpty() && ClassCore.TEMPLATES.containsKey(stored)) return stored;
        var t = ClassCore.templateOf(coreOf(player));
        return t == null ? "" : t.id();
    }

    /** 法术形式伤害被动：神枪手 projectile ×1.2 / 枪炮师 aoe ×1.25 / 元素法师内核元素再 ×1.1
     *  / 狂战士低血 ×1.15（法术侧同享）。 */
    public static double spellFormSignatureMult(Player player, CustomSpell spell) {
        String sig = signatureOf(player);
        if (sig.isEmpty() || spell == null) return 1.0;
        double mult = 1.0;
        if ("sharpshooter".equals(sig) && "projectile".equals(spell.form())) {
            mult *= SHARPSHOOTER_PROJECTILE_DAMAGE;
        } else if ("artillery".equals(sig) && "aoe".equals(spell.form())) {
            mult *= ARTILLERY_AOE_DAMAGE;
        }
        if ("elementalist".equals(sig) && coreOf(player).hasElement(spell.element())) {
            mult *= ELEMENTALIST_CORE_BONUS;
        }
        if ("berserker".equals(sig) && player.getHealth() < player.getMaxHealth() * 0.5f) {
            mult *= BERSERKER_LOW_HP_BONUS;
        }
        return mult;
    }

    /** 目标条件法术被动：死灵术士对非亡灵 ×1.1（resolveDamage 结算层调用）。 */
    public static double spellTargetSignatureMult(Player player,
                                                    net.minecraft.world.entity.LivingEntity target) {
        if (!"necro".equals(signatureOf(player)) || target == null) return 1.0;
        return target.isInvertedHealAndHarm() ? 1.0 : NECRO_LIVING_BONUS;
    }

    /** 弹速被动：神枪手 ×1.5（castProjectile 的速度参数乘区）。 */
    public static float projectileSpeedMult(Player player) {
        return "sharpshooter".equals(signatureOf(player))
                ? (float) SHARPSHOOTER_PROJECTILE_SPEED : 1.0f;
    }

    /** AoE 半径被动：弹药专家 ×1.15。 */
    public static double aoeRadiusMult(Player player) {
        return "sapper".equals(signatureOf(player)) ? SAPPER_AOE_RADIUS : 1.0;
    }

    /** buff/utility 时长被动：气功师 ×1.5（其余职业原值）。 */
    public static int effectDuration(Player player, int baseTicks) {
        return "qigong".equals(signatureOf(player))
                ? (int) Math.round(baseTicks * QIGONG_DURATION) : baseTicks;
    }

    /** utility 冷却被动：魔道学者 ×0.7。 */
    public static double utilityCooldownMult(Player player, CustomSpell spell) {
        return "scholar".equals(signatureOf(player)) && spell != null
                && "utility".equals(spell.effect()) ? SCHOLAR_UTILITY_COOLDOWN : 1.0;
    }

    /** 治疗量被动：牧师 ×1.3（heal 结算乘区）。 */
    public static double healMult(Player player) {
        return "priest".equals(signatureOf(player)) ? PRIEST_HEAL_BONUS : 1.0;
    }

    /** 受伤被动：圣骑士 ×0.9。 */
    public static double incomingSignatureMult(Player player) {
        return "paladin".equals(signatureOf(player)) ? PALADIN_INCOMING : 1.0;
    }

    /** 连招参数：上限（战斗法师 12 / 默认 10）。 */
    public static int comboCap(Player player) {
        return "battle_mage".equals(signatureOf(player)) ? BATTLE_MAGE_COMBO_CAP
                : com.qianxiang.spell.ComboTracker.MAX_COMBO;
    }

    /** 连招参数：每段加成（剑客 0.06 / 战斗法师 0.05 / 默认 0.04）。 */
    public static double comboPerStack(Player player) {
        String sig = signatureOf(player);
        if ("swordsman".equals(sig)) return SWORDSMAN_COMBO_PER_STACK;
        if ("battle_mage".equals(sig)) return BATTLE_MAGE_COMBO_PER_STACK;
        return com.qianxiang.spell.ComboTracker.BONUS_PER_LEVEL;
    }

    /** 魔剑士：记录一次成功施法（SpellCastHandler 调用），开 3s 近战增益窗。 */
    public static void noteCast(Player player) {
        if (player != null && "spellsword".equals(signatureOf(player))) {
            SPELLSWORD_LAST_CAST.put(player.getUUID(), player.level().getGameTime());
        }
    }

    private static final java.util.Map<java.util.UUID, Long> SPELLSWORD_LAST_CAST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 近战招牌被动乘区（ProficiencyCombatHooks 近战路径调用，相互叠乘）：
     * 魔剑士窗口 / 狂战士低血 / 阵鬼对减速冻结 / 刺客背刺 / 忍者潜行 /
     * 流氓对中毒 / 驱魔师对亡灵。
     */
    public static double meleeSignatureMult(ServerPlayer attacker,
                                              net.minecraft.world.entity.LivingEntity target) {
        String sig = signatureOf(attacker);
        if (sig.isEmpty()) return 1.0;
        double mult = 1.0;
        switch (sig) {
            case "spellsword" -> {
                Long last = SPELLSWORD_LAST_CAST.get(attacker.getUUID());
                if (last != null && attacker.level().getGameTime() - last <= SPELLSWORD_WINDOW_TICKS) {
                    mult *= SPELLSWORD_MELEE_BONUS;
                }
            }
            case "berserker" -> {
                if (attacker.getHealth() < attacker.getMaxHealth() * 0.5f) {
                    mult *= BERSERKER_LOW_HP_BONUS;
                }
            }
            case "reaper" -> {
                if (target != null && (target.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)
                        || target.getTicksFrozen() > 0)) {
                    mult *= REAPER_SLOWED_BONUS;
                }
            }
            case "assassin" -> {
                // 背刺：目标视线方向与「目标→攻击者」方向点积 < cos(120°)（攻击者在目标背后半球）
                if (target != null) {
                    var look = target.getLookAngle().normalize();
                    var toAttacker = attacker.position().subtract(target.position()).normalize();
                    if (look.dot(toAttacker) < ASSASSIN_MIN_DOT) {
                        mult *= ASSASSIN_BACKSTAB_BONUS;
                    }
                }
            }
            case "ninja" -> {
                if (attacker.isShiftKeyDown()) mult *= NINJA_SNEAK_BONUS;
            }
            case "rogue" -> {
                if (target != null && target.hasEffect(net.minecraft.world.effect.MobEffects.POISON)) {
                    mult *= ROGUE_POISONED_BONUS;
                }
            }
            case "exorcist" -> {
                if (target != null && target.isInvertedHealAndHarm()) {
                    mult *= EXORCIST_UNDEAD_BONUS;
                }
            }
            default -> { }
        }
        return mult;
    }

    /** 命中附加（LivingDamageEvent.Post 近战路径）：流氓上毒 / 柔道家缓慢 + 补击退。 */
    public static void onMeleeHitPost(ServerPlayer attacker,
                                      net.minecraft.world.entity.LivingEntity target) {
        if (attacker == null || target == null || !target.isAlive()) return;
        String sig = signatureOf(attacker);
        if ("rogue".equals(sig)) {
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.POISON, ROGUE_POISON_TICKS, 0));
        } else if ("judoka".equals(sig)) {
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, JUDOKA_SLOW_TICKS, 0));
            // 击退 ×2：原版击退已在 attack 内结算，这里补一次同向等额（0.5 与原版基底一致）
            target.knockback(JUDOKA_EXTRA_KNOCKBACK,
                    net.minecraft.util.Mth.sin(attacker.getYRot() * ((float) Math.PI / 180F)),
                    -net.minecraft.util.Mth.cos(attacker.getYRot() * ((float) Math.PI / 180F)));
        }
    }

    /** 击杀附加（LivingDeathEvent，攻击者是玩家）：盗贼隐身 / 死灵回血 / 复仇者回血。 */
    public static void onKill(ServerPlayer attacker) {
        if (attacker == null) return;
        switch (signatureOf(attacker)) {
            case "thief" -> attacker.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.INVISIBILITY, THIEF_INVIS_TICKS, 0));
            case "necro" -> attacker.heal(NECRO_KILL_HEAL);
            case "avenger" -> attacker.heal(AVENGER_KILL_HEAL);
            default -> { }
        }
    }

    /** 拳法家攻速被动标记（CompanionHandler tick 驱动蓄力加速）。 */
    public static boolean isPugilist(Player player) {
        return "pugilist".equals(signatureOf(player));
    }

    /** 伙伴职业：召唤师=狼 / 机械师=铁傀儡；否则 ""。 */
    public static String companionOf(Player player) {
        String sig = signatureOf(player);
        if ("summoner".equals(sig)) return "wolf";
        if ("mechanic".equals(sig)) return "iron_golem";
        return "";
    }

    // ===================== 设定 / 转职 =====================

    /**
     * 设定或更换主职业（首次免费，转职扣 {@link #RESPEC_GOLD_COST} 金粒）。
     * 校验：已开启修行 + 内核合法（两元素互不相同且在白名单、形态在白名单）；
     * 成功后写入 attachment、记相谱、全量同步。
     *
     * @return true = 已写入
     */
    public static boolean setClassCore(ServerPlayer player, ClassCore core) {
        return setClassCore(player, core, "");
    }

    /**
     * 设定或更换主职业（带模板 id：模板路径的职业身份——同内核三元组的职业
     * （swordsman/battle_mage）靠存储的模板 id 区分招牌被动）。
     */
    public static boolean setClassCore(ServerPlayer player, ClassCore core, String templateId) {
        if (player == null || core == null || !core.valid()) {
            return false;
        }
        PlayerProficiencyData d = player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
        if (!d.unlocked()) {
            player.displayClientMessage(Component.translatable("qianxiang.classcore.locked"), true);
            return false;
        }
        boolean firstTime = !d.classCore().isSet();
        if (!firstTime && !takeGoldNuggets(player, RESPEC_GOLD_COST)) {
            player.displayClientMessage(
                    Component.translatable("qianxiang.classcore.no_gold"), true);
            return false;
        }
        player.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA,
                d.withClassCore(core, templateId));
        var saga = player.getData(QianxiangAttachments.SAGA_DATA);
        player.setData(QianxiangAttachments.SAGA_DATA, saga.withEntry(
                (firstTime ? "§e[主职业] §r立下内核：" : "§e[转职] §r改立内核：")
                        + core.elementA() + " + " + core.elementB() + " · " + core.form()));
        player.displayClientMessage(Component.translatable("qianxiang.classcore.set_done"), true);
        ProficiencyHelper.sync(player);
        return true;
    }

    /** 从背包精确扣除 count 个金粒（不足则一颗不扣），返回是否扣成。 */
    private static boolean takeGoldNuggets(ServerPlayer player, int count) {
        var inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            var s = inv.getItem(i);
            if (s.is(net.minecraft.world.item.Items.GOLD_NUGGET)) have += s.getCount();
        }
        if (have < count) return false;
        int remaining = count;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE && remaining > 0; i++) {
            var s = inv.getItem(i);
            if (!s.is(net.minecraft.world.item.Items.GOLD_NUGGET)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            inv.setItem(i, s);
            remaining -= take;
        }
        return true;
    }
}
