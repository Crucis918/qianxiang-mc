package com.qianxiang.cap;

import com.qianxiang.Qianxiang;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熟练度服务端核心：xp 累计/升级发点、节点分配校验、洗点、效果查询、主动技能。
 * <p>
 * 全部查询先判 {@code unlocked && allocated}，未开启/未分配一律返回中性值——
 * 未找相师开启的玩家完全感知不到本系统。平衡参数集中在类顶部（注释「供调平」）。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class ProficiencyHelper {

    private ProficiencyHelper() {}

    // ===================== 调平常量区（改这里即可调平衡，不动既有基底公式） =====================

    /** 升级曲线：n-1 → n 级所需 xp = (int)(XP_BASE * n^XP_EXPONENT)。 */
    public static final double XP_BASE = 80.0;
    public static final double XP_EXPONENT = 1.6;
    /** 轨等级上限。 */
    public static final int MAX_LEVEL = 15;

    // XP 产出（挂点口径，见各调用处）
    public static final double KILL_XP_PER_MAX_HEALTH = 0.5;
    public static final int WARDEN_XP_EACH_TRACK = 100;
    public static double castXpOf(int manaCost) {
        return manaCost * 0.6;
    }
    public static final int FORGE_XP = 15;
    public static final int ALCHEMY_XP = 15;
    public static final int TRADE_XP = 5;

    // 节点效果数值（实现口径唯一在这里；节点表只管结构）
    public static final double BLADE1_BONUS = 0.05;
    public static final double BLADE2_BONUS = 0.10;
    public static final double SWIFT1_BONUS = 0.05;
    public static final int HARVEST_HEAL = 2;
    public static final double PIERCE_BONUS = 0.04;
    public static final double COMBO_BONUS_PER_HIT = 0.04;
    public static final int COMBO_MAX_HITS = 3;
    public static final long COMBO_WINDOW_MS = 3_000L;
    public static final double EXECUTE_THRESHOLD = 0.20;
    public static final double EXECUTE_BONUS = 0.25;
    public static final double BULWARK_MULT = 0.88;
    public static final double MANA1_MULT = 0.92;
    public static final double MANA2_MULT = 0.85;
    public static final double FOCUS1_BONUS = 0.08;
    public static final double FOCUS2_BONUS = 0.15;
    public static final int WELL_BONUS = 10;
    public static final double QUICKCOOL_MULT = 0.85;
    public static final double RIPPLE_CHANCE = 0.10;
    public static final int OVERLOAD_MIN_POWER = 6;
    public static final double OVERLOAD_BONUS = 0.20;
    public static final double THRIFT_CHANCE = 0.06;
    public static final double THRIFT2_CHANCE = 0.12;
    public static final double ADEPT_SPEED_MULT = 0.75;  // 仪式时长 ×0.75（提速 25%）
    public static final int NETWORK_DISCOUNT_PCT = 5;
    public static final double ARTISAN_MULT = 1.05;
    public static final int LORE_BONUS_SLOTS = 4;
    public static final double INSPIRE_COOLDOWN_MULT = 0.5;
    public static final double MASTER_DURABILITY_MULT = 1.15;
    public static final double MIDAS_CHANCE = 0.10;

    // 主动技能
    public static final int SURGE_MANA = 40;
    public static final long SURGE_COOLDOWN_MS = 90_000L;
    public static final int WARCRY_DURATION_TICKS = 200;   // 10s
    public static final double WARCRY_SPEED_BONUS = 0.20;
    public static final long WARCRY_COOLDOWN_MS = 60_000L;
    private static final net.minecraft.resources.ResourceLocation WARCRY_MODIFIER_ID =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, "warcry_attack_speed");

    /** 洗点价格：10 绿宝石。 */
    public static final int RESPEC_EMERALD_COST = 10;

    /** 蓝图库基础保存位（lore 节点在之上 +4）。 */
    public static final int BASE_BLUEPRINT_SLOTS = 8;

    // ============================ 数据读写 ============================

    private static PlayerProficiencyData data(Player player) {
        return player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA);
    }

    /** 是否已分配某节点（未开启/未分配一律 false）。 */
    public static boolean has(Player player, String nodeId) {
        PlayerProficiencyData d = data(player);
        return d.unlocked() && d.hasAllocated(nodeId);
    }

    /** 相师开启（下一步挂 NPC 对话）：置 unlocked 并同步；幂等。 */
    public static void unlock(ServerPlayer player) {
        PlayerProficiencyData d = data(player);
        if (d.unlocked()) return;
        player.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA, d.withUnlocked());
        player.sendSystemMessage(Component.translatable("qianxiang.proficiency.unlocked"));
        com.qianxiang.QianxiangAdvancements.grant(player, com.qianxiang.QianxiangAdvancements.CULTIVATION);
        sync(player);
    }

    /** n-1 → n 级所需 xp。 */
    public static int xpForLevel(int n) {
        return (int) (XP_BASE * Math.pow(n, XP_EXPONENT));
    }

    /**
     * 记 xp：未开启不攒（静默）。满阈值连续升级，每级 +1 技能点，封顶 {@link #MAX_LEVEL}。
     * 任何变化都触发一次全量同步。
     */
    public static void addXp(ServerPlayer player, ProficiencyTrack track, int amount) {
        if (amount <= 0) return;
        PlayerProficiencyData d = data(player);
        if (!d.unlocked()) return;
        int xp = d.xpOf(track) + amount;
        int level = d.levelOf(track);
        int points = d.pointsOf(track);
        while (level < MAX_LEVEL && xp >= xpForLevel(level + 1)) {
            xp -= xpForLevel(level + 1);
            level++;
            points++;
            player.sendSystemMessage(Component.translatable(
                    "qianxiang.proficiency.levelup",
                    Component.translatable("qianxiang.proficiency.track." + track.id()), level));
        }
        PlayerProficiencyData next = d.withXp(track, xp).withLevel(track, level).withPoints(track, points);
        if (next != d) {
            player.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA, next);
            sync(player);
        }
    }

    /**
     * 分配节点：unlocked + 节点存在 + 未点过 + 本轨有点 + 前置（见 {@link ProficiencyNodes#prereqMet}）。
     * 非法一律 WARN 返回 false（客户端无从伪造，见 AllocateNodePayload handler）。
     */
    public static boolean allocate(ServerPlayer player, String nodeId) {
        PlayerProficiencyData d = data(player);
        ProficiencyNodes.Node node = ProficiencyNodes.byId(nodeId);
        if (!d.unlocked() || node == null || d.hasAllocated(nodeId)) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 非法分配节点 {}（未开启/未知/已点过）",
                    player.getName().getString(), nodeId);
            return false;
        }
        if (d.pointsOf(node.track()) <= 0 || !ProficiencyNodes.prereqMet(d, node)) {
            Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 分配节点 {} 前置不足（点 {} / 前置未满足）",
                    player.getName().getString(), nodeId, d.pointsOf(node.track()));
            return false;
        }
        java.util.Set<String> next = new java.util.HashSet<>(d.allocated());
        next.add(nodeId);
        player.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA,
                d.withPoints(node.track(), d.pointsOf(node.track()) - 1).withAllocated(next));
        // 首个 T3 节点：相谱铭刻（进程标志，只记一次——此前本轨无 T3）
        if (node.tier() == 3 && !ProficiencyNodes.anyAllocated(d, node.track(), 3)) {
            var saga = player.getData(QianxiangAttachments.SAGA_DATA);
            player.setData(QianxiangAttachments.SAGA_DATA,
                    saga.withEntry("§6[登堂] §r点亮首个三阶节点「"
                            + nodeId + "」（" + node.track().id() + "轨）"));
        }
        sync(player);
        return true;
    }

    /**
     * 洗点：扣 10 绿宝石 → 清空 allocated、三轨点数=轨等级全额返还、记相谱、同步。
     */
    public static boolean respec(ServerPlayer player) {
        PlayerProficiencyData d = data(player);
        if (!d.unlocked() || d.allocated().isEmpty()) return false;
        var inv = player.getInventory();
        int emeralds = 0;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            var s = inv.getItem(i);
            if (s.is(net.minecraft.world.item.Items.EMERALD)) emeralds += s.getCount();
        }
        if (emeralds < RESPEC_EMERALD_COST) {
            player.displayClientMessage(
                    Component.translatable("qianxiang.proficiency.respec.no_emerald"), true);
            return false;
        }
        int remaining = RESPEC_EMERALD_COST;
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE && remaining > 0; i++) {
            var s = inv.getItem(i);
            if (!s.is(net.minecraft.world.item.Items.EMERALD)) continue;
            int take = Math.min(remaining, s.getCount());
            s.shrink(take);
            inv.setItem(i, s);
            remaining -= take;
        }
        PlayerProficiencyData next = d
                .withPoints(ProficiencyTrack.COMBAT, d.combatLevel())
                .withPoints(ProficiencyTrack.ARCANE, d.arcaneLevel())
                .withPoints(ProficiencyTrack.CRAFT, d.craftLevel())
                .withAllocated(java.util.Set.of());
        player.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA, next);
        var saga = player.getData(QianxiangAttachments.SAGA_DATA);
        player.setData(QianxiangAttachments.SAGA_DATA,
                saga.withEntry("§e[洗点] §r以 10 绿宝石重置熟练度，技能点全部归还"));
        player.displayClientMessage(Component.translatable("qianxiang.proficiency.respec.done"), true);
        sync(player);
        return true;
    }

    // ============================ 效果查询（未分配返回中性值） ============================

    public static double meleeDamageMult(Player player) {
        double mult = 1.0;
        if (has(player, "blade1")) mult += BLADE1_BONUS;
        if (has(player, "blade2")) mult += BLADE2_BONUS;
        if (has(player, "pierce")) mult += PIERCE_BONUS;
        return mult;
    }

    public static double attackSpeedMult(Player player) {
        return has(player, "swift1") ? 1.0 + SWIFT1_BONUS : 1.0;
    }

    /** 法术强度倍率：focus1 + focus2；power ≥ {@link #OVERLOAD_MIN_POWER} 时 overload 再叠。 */
    public static double spellDamageMult(Player player, int spellPower) {
        double mult = 1.0;
        if (has(player, "focus1")) mult += FOCUS1_BONUS;
        if (has(player, "focus2")) mult += FOCUS2_BONUS;
        if (spellPower >= OVERLOAD_MIN_POWER && has(player, "overload")) mult += OVERLOAD_BONUS;
        return mult;
    }

    public static double manaCostMult(Player player) {
        double mult = 1.0;
        if (has(player, "mana1")) mult *= MANA1_MULT;
        if (has(player, "mana2")) mult *= MANA2_MULT;
        return mult;
    }

    public static double cooldownMult(Player player) {
        return has(player, "quickcool") ? QUICKCOOL_MULT : 1.0;
    }

    public static double rippleFreeChance(Player player) {
        return has(player, "ripple") ? RIPPLE_CHANCE : 0.0;
    }

    public static int maxManaBonus(Player player) {
        return has(player, "well") ? WELL_BONUS : 0;
    }

    public static double incomingDamageMult(Player player) {
        return has(player, "bulwark") ? BULWARK_MULT : 1.0;
    }

    public static int killHeal(Player player) {
        return has(player, "harvest") ? HARVEST_HEAL : 0;
    }

    /** execute 斩杀加成：目标生命比例 < {@link #EXECUTE_THRESHOLD} 时 +{@link #EXECUTE_BONUS}。 */
    public static double executeThresholdBonus(Player player, net.minecraft.world.entity.LivingEntity target) {
        if (!has(player, "execute") || target == null) return 0.0;
        float max = target.getMaxHealth();
        return max > 0 && target.getHealth() / max < EXECUTE_THRESHOLD ? EXECUTE_BONUS : 0.0;
    }

    // ---- combo 连击（transient，3s 窗口内递增，上限 3 段） ----

    private static final Map<UUID, long[]> COMBO_STREAK = new ConcurrentHashMap<>(); // [hitCount, windowStartMs]

    /** 当前连击倍率（1 + 4%×段数，上限 3 段；窗口外为 1）。 */
    public static double comboBonus(Player player) {
        if (!has(player, "combo")) return 1.0;
        long[] s = COMBO_STREAK.get(player.getUUID());
        if (s == null || System.currentTimeMillis() - s[1] > COMBO_WINDOW_MS) return 1.0;
        return 1.0 + COMBO_BONUS_PER_HIT * Math.min((int) s[0], COMBO_MAX_HITS);
    }

    /** 近战命中后记一段连击（CombatEffectHandler 调用）。 */
    public static void noteMeleeHit(Player player) {
        if (!has(player, "combo")) return;
        long now = System.currentTimeMillis();
        long[] s = COMBO_STREAK.computeIfAbsent(player.getUUID(), k -> new long[]{0, 0});
        if (now - s[1] > COMBO_WINDOW_MS) {
            s[0] = 1;
        } else {
            s[0] = Math.min(s[0] + 1, COMBO_MAX_HITS);
        }
        s[1] = now;
    }

    // ---- 技艺 ----

    public static double forgeRefundChance(Player player) {
        if (has(player, "thrift2")) return THRIFT2_CHANCE;
        return has(player, "thrift") ? THRIFT_CHANCE : 0.0;
    }

    /** 仪式时长倍率（adept 提速 25% → ×0.75）。 */
    public static double ritualSpeedMult(Player player) {
        return has(player, "adept") ? ADEPT_SPEED_MULT : 1.0;
    }

    public static int tradeDiscount(Player player) {
        return has(player, "network") ? NETWORK_DISCOUNT_PCT : 0;
    }

    public static double powerScoreMult(Player player) {
        return has(player, "artisan") ? ARTISAN_MULT : 1.0;
    }

    public static double durabilityMult(Player player) {
        return has(player, "master") ? MASTER_DURABILITY_MULT : 1.0;
    }

    public static double scrollPowerUpChance(Player player) {
        return has(player, "midas") ? MIDAS_CHANCE : 0.0;
    }

    public static int blueprintBonusSlots(Player player) {
        return has(player, "lore") ? LORE_BONUS_SLOTS : 0;
    }

    public static double aiCooldownMult(Player player) {
        return has(player, "inspire") ? INSPIRE_COOLDOWN_MULT : 1.0;
    }

    /** midas 点金：10% 卷轴 power+1（封顶 MAX_POWER）。 */
    public static com.qianxiang.spell.CustomSpell applyMidas(Player player,
                                                             com.qianxiang.spell.CustomSpell spell) {
        if (spell == null || !has(player, "midas")) return spell;
        if (player.level().getRandom().nextDouble() >= MIDAS_CHANCE) return spell;
        int power = Math.min(spell.power() + 1, com.qianxiang.spell.CustomSpell.MAX_POWER);
        if (power == spell.power()) return spell;
        return new com.qianxiang.spell.CustomSpell(spell.id(), spell.element(), spell.form(),
                spell.effect(), spell.modifiers(), spell.manaCost(), spell.cooldownTicks(), power);
    }

    /** 锻造属性加成应用点（菜单/BE compose 后调用）：powerScore ×artisan，耐久 ×master。 */
    public static com.qianxiang.phase.ComposedAttributes applyCraftBonuses(
            com.qianxiang.phase.ComposedAttributes attr, Player player) {
        if (attr == null || player == null) return attr;
        double powerMult = powerScoreMult(player);
        if (powerMult != 1.0) {
            attr = attr.withPowerScore(attr.powerScore() * powerMult);
        }
        double durMult = durabilityMult(player);
        if (durMult != 1.0) {
            attr = attr.withDurability((int) Math.round(attr.durability() * durMult));
        }
        return attr;
    }

    // ============================ 主动技能 ============================

    private static final Map<UUID, Long> SURGE_LAST_USE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WARCRY_LAST_USE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> WARCRY_EXPIRE_TICK = new ConcurrentHashMap<>();

    /** 涌动剩余冷却（毫秒，0 = 就绪；同步包与 GUI 状态行用）。 */
    public static long surgeCooldownRemainingMs(Player player) {
        Long last = SURGE_LAST_USE.get(player.getUUID());
        if (last == null) return 0;
        return Math.max(0, SURGE_COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    /** 战吼剩余冷却（毫秒，0 = 就绪）。 */
    public static long warcryCooldownRemainingMs(Player player) {
        Long last = WARCRY_LAST_USE.get(player.getUUID());
        if (last == null) return 0;
        return Math.max(0, WARCRY_COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    /** 战吼生效剩余（毫秒，0 = 未生效；HUD 显示用）。 */
    public static long warcryActiveRemainingMs(Player player) {
        Long expire = WARCRY_EXPIRE_TICK.get(player.getUUID());
        if (expire == null || player.level() == null) return 0;
        return Math.max(0, (expire - player.level().getGameTime()) * 50L);
    }

    /** 涌动：瞬回 40 蓝（钳到有效上限），90s 冷却。 */
    public static boolean activateSurge(ServerPlayer player) {
        if (!has(player, "surge")) return false;
        Long last = SURGE_LAST_USE.get(player.getUUID());
        long now = System.currentTimeMillis();
        if (last != null && now - last < SURGE_COOLDOWN_MS) {
            player.displayClientMessage(
                    Component.translatable("qianxiang.proficiency.skill.cooldown"), true);
            return false;
        }
        var data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        int cap = com.qianxiang.spell.AmplifierHelper.effectiveMaxMana(player, data);
        player.setData(QianxiangAttachments.PLAYER_SPELL_DATA,
                data.withMana(data.currentMana() + SURGE_MANA, cap));
        SURGE_LAST_USE.put(player.getUUID(), now);
        com.qianxiang.spell.SpellCastHandler.sync(player);
        player.displayClientMessage(Component.translatable("qianxiang.proficiency.surge.used"), true);
        return true;
    }

    /** 战吼：10s +20% 攻速/移速（属性 transient modifier），60s 冷却。 */
    public static boolean activateWarCry(ServerPlayer player) {
        if (!has(player, "warcry")) return false;
        Long last = WARCRY_LAST_USE.get(player.getUUID());
        long now = System.currentTimeMillis();
        if (last != null && now - last < WARCRY_COOLDOWN_MS) {
            player.displayClientMessage(
                    Component.translatable("qianxiang.proficiency.skill.cooldown"), true);
            return false;
        }
        var modifier = new AttributeModifier(WARCRY_MODIFIER_ID, WARCRY_SPEED_BONUS,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        var attackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
        var moveSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attackSpeed != null) attackSpeed.addOrUpdateTransientModifier(modifier);
        if (moveSpeed != null) moveSpeed.addOrUpdateTransientModifier(modifier);
        WARCRY_LAST_USE.put(player.getUUID(), now);
        WARCRY_EXPIRE_TICK.put(player.getUUID(),
                player.level().getGameTime() + WARCRY_DURATION_TICKS);
        player.displayClientMessage(Component.translatable("qianxiang.proficiency.warcry.used"), true);
        return true;
    }

    /** 战吼到期清理（每 tick 检查一次到期玩家）。 */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Long expire = WARCRY_EXPIRE_TICK.get(player.getUUID());
        if (expire == null || player.level().getGameTime() < expire) return;
        WARCRY_EXPIRE_TICK.remove(player.getUUID());
        var attackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
        var moveSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attackSpeed != null) attackSpeed.removeModifier(WARCRY_MODIFIER_ID);
        if (moveSpeed != null) moveSpeed.removeModifier(WARCRY_MODIFIER_ID);
    }

    // ============================ 同步 ============================

    /** 全量同步熟练度给指定玩家（登录/重生/换维度/任何变化时调用）。 */
    public static void sync(ServerPlayer player) {
        try {
            PacketDistributor.sendToPlayer(player,
                    com.qianxiang.network.ProficiencySyncPayload.of(player));
        } catch (Throwable t) {
            Qianxiang.LOGGER.debug("[Qianxiang] 熟练度同步发包失败（不影响结算）：{}", t.toString());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sync(player);
    }
}
