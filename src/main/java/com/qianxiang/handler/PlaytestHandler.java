package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangBlocks;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangDimensions;
import com.qianxiang.QianxiangItems;
import com.qianxiang.QianxiangMaterials;
import com.qianxiang.block.AlchemyTableBlockEntity;
import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.block.RitualLogic;
import com.qianxiang.block.RitualState;
import com.qianxiang.cap.ProficiencyTrack;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.entity.QianxiangEntities;
import com.qianxiang.entity.QianxiangMyriadWarden;
import com.qianxiang.entity.QianxiangWanderingSage;
import com.qianxiang.menu.AlchemyTableMenu;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiPlaceMaterialsHandler;
import com.qianxiang.network.ScreenshotRequestPayload;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.spell.CustomSpell;
import com.qianxiang.spell.SpellCastHandler;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * dev 自主游玩检测装置：客户端进世界后无人工操作跑完整玩法链（A-G 场景），
 * 每个检查点记 PASS/FAIL，结束写 {@code run/playtest/PLAYTEST_REPORT.txt} 并自动退出游戏。
 * <p>
 * <b>门控（正常游戏零行为变化）</b>：仅当环境变量 {@code QX_PLAYTEST=1}
 * 且运行端为客户端（{@code value = Dist.CLIENT} + 代码内 {@code FMLEnvironment.dist} 双保险，
 * GameTestServer 是 DEDICATED_SERVER 天然不触发）且单人世界时启用；
 * build.gradle 的 client run 同款门控追加 {@code --quickPlaySingleplayer}
 * （存档名默认「新的世界」，{@code QX_PLAYTEST_WORLD} 可覆盖）。
 * 与 AUTOSMOKE 相互独立：两个 handler 各自门控、可同开（quickPlay 参数在 gradle 侧互斥）。
 * </p>
 * <p>
 * <b>场景框架</b>：场景 = 有序的 {@code (相对 tick, 名称, 动作)} 步骤表（{@link Step}/{@link Scenario}），
 * 场景间严格串行；检查 = 对可观测最终状态的断言（玩家 attachment/物品栏/世界方块/实体），
 * 经 {@link #check} 记账，FAIL 同时 ERROR 日志。每步独立 try-catch——装置绝不崩游戏。
 * </p>
 * <p>
 * <b>守规矩</b>：①断言只测可观测最终状态，不偷看内部中间量；
 * ②零真实 AI 外呼——炼金/锻造全走「材料模板兜底路径」（不发 AiRequestPayload，
 * AI 提案为空时 composer 用本地模板，AIConfig 默认也只打 localhost）；
 * ③跨维度/传送/仪式等待全部用真实状态机推进，不按墙钟猜。
 * </p>
 * <p>
 * 场景一览（相对 tick 从各场景启动计）：
 * <ul>
 *   <li>S 准备：调时间天气 → 生存模式 → 传送平坦点 → 面前放锻造台+炼金台。</li>
 *   <li>A 锻造全链：投料(铁/燧石/烈焰粉) → 预览非空 → 仪式 → DONE → 空手拾取 →
 *       产物 attackDamage&gt;0 → 主手装备 → 攻击假人掉血。</li>
 *   <li>B 炼金全链：投料(余烬晶体) → 卷轴预览 → 仪式 → DONE → 拾取 → 右键学习 → 已学+1。</li>
 *   <li>C 轮盘施法：先发 B 学到的 id（受理断言）→ 再发确定性 aoe 法术 id →
 *       法力减少 + 冷却写入 + 假人掉血（B 的卷轴法术形态由数据决定，不保证命中，
 *       故命中类断言用自带 fire/aoe/damage 法术，仍是真实「已学 id → castLearnedSpell」路径，
 *       与 CastSpellPayload 服务端入口同一条）。</li>
 *   <li>D 熟练度：相师 spawn → 潜行交互两次(5s 确认窗) → unlocked；
 *       击杀假人 → combat xp&gt;0；施法 → arcane xp&gt;0。</li>
 *   <li>E 维度往返：位格+3 → 持精髓右键裂隙岩(真实事件总线路径) → 万象森罗落点可站不埋不淹
 *       → 空手右键回程裂隙岩 → 回主世界。</li>
 *   <li>F Boss：spawn 森罗守望者 → 玩家伤害打死 → 掉落物含 warden_core → story/kill_warden 成就。</li>
 *   <li>G 存储取料：台旁箱子放铁锭(背包清空铁) → AiPlaceMaterialsHandler.placeMaterials →
 *       材料从箱子抽取入台、箱子扣减。</li>
 * </ul>
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID, value = Dist.CLIENT)
public final class PlaytestHandler {

    /** 双重门控之一：环境变量。 */
    private static final boolean ENABLED = "1".equals(System.getenv("QX_PLAYTEST"));

    /** 场景报告文件（相对游戏目录，gradle client run 下即 run/playtest/）。 */
    private static final Path REPORT_PATH = Path.of("playtest", "PLAYTEST_REPORT.txt");

    // ============================ 场景框架 ============================

    /** 一步：相对 tick + 名称 + 动作。动作抛异常 = 该步 FAIL 并继续。 */
    private record Step(int tick, String name, StepAction action) {}

    @FunctionalInterface
    private interface StepAction {
        void run(ServerPlayer player) throws Exception;
    }

    /** 场景：id + 标题 + 步骤表 + 超时（最后一步 + 余量，防状态机卡死把装置挂死）。 */
    private record Scenario(String id, String title, List<Step> steps, int lastTick, int timeout) {}

    /** 序列主体（登录时捕获）；null = 未启动/已结束。 */
    private static ServerPlayer subject;
    private static List<Scenario> scenarios = List.of();
    private static int scenarioIndex;
    private static int scenarioTick;

    /** 跨步骤传递的上下文（台子坐标、产物、假人等）。 */
    private static final Map<String, Object> CTX = new HashMap<>();
    /** 检查记账（报告行）；看门狗（客户端线程）也会写，用 synchronizedList。 */
    private static final List<String> REPORT =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private static int passCount;
    private static int failCount;
    /** 当前场景 id（check 记账用）。 */
    private static String currentScenario = "-";

    // ---- 墙钟混合驱动（防 tick 停摆把装置挂死；全部 volatile，看门狗在客户端线程读） ----
    /** 单步硬超时：tick 在走但 30s 没有任何一步执行 = 调度坏了。 */
    private static final long STEP_TIMEOUT_MS = 30_000L;
    /** 等待条件硬超时：单场景墙钟 60s（仪式等固定 tick 等待的正常上限 ~5s）。 */
    private static final long WAIT_TIMEOUT_MS = 60_000L;
    /** 全局硬超时：10 分钟，到点无条件收尾。 */
    private static final long GLOBAL_TIMEOUT_MS = 10 * 60_000L;
    /** tick 失速告警阈值/间隔：>10s 无 tick，每 10s WARN 一次。 */
    private static final long STALL_WARN_MS = 10_000L;
    /** tick 失速熔断：>60s 无 tick，记 FAIL 写报告退出，绝不无限挂。 */
    private static final long STALL_ABORT_MS = 60_000L;
    /** 看门狗评估间隔（独立线程唤醒周期）。 */
    private static final long WATCHDOG_INTERVAL_MS = 5_000L;
    /** tick 失速自愈阈值：>15s 且为界面暂停时尝试关暂停界面恢复。 */
    private static final long STALL_HEAL_MS = 15_000L;

    private static volatile long runStartMs;
    private static volatile long scenarioStartMs;
    /** 最近一次有步骤执行的时刻（单步 30s 超时基准）。 */
    private static volatile long lastStepMs;
    /** 最近一次 ServerTick 进入本 handler 的时刻（失速检测基准）。 */
    private static volatile long lastServerTickMs;
    private static volatile long lastStallWarnMs;
    /** 最近一次执行的步骤名（超时报告指认现场用）。 */
    private static volatile String lastStepName = "-";
    /** 收尾单发守卫（服务端线程与看门狗线程都可能进 finish）。 */
    private static volatile boolean finishing;
    /** 看门狗 daemon 线程（不依赖任何客户端事件；渲染限流/最小化/暂停都活着）。 */
    private static volatile Thread watchdogThread;

    private PlaytestHandler() {}

    // ============================ 事件入口 ============================

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ENABLED || FMLEnvironment.dist != Dist.CLIENT) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.getServer().isSingleplayer()) return; // 只在单人跑，不碰真服务器
        subject = player;
        scenarios = buildScenarios();
        scenarioIndex = 0;
        scenarioTick = 0;
        CTX.clear();
        REPORT.clear();
        passCount = 0;
        failCount = 0;
        long now = System.currentTimeMillis();
        runStartMs = now;
        scenarioStartMs = now;
        lastStepMs = now;
        lastServerTickMs = now;
        lastStallWarnMs = 0L;
        lastStepName = "-";
        finishing = false;
        startWatchdog();
        REPORT.add("---- [" + scenarios.get(0).id() + "] " + scenarios.get(0).title() + " ----");
        Qianxiang.LOGGER.info("[Qianxiang] PLAYTEST 启动：玩家 {} 进世界，{} 个场景开始",
                player.getGameProfile().getName(), scenarios.size());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerPlayer player = subject;
        if (player == null) return;
        if (player.isRemoved()) { // 中途退出：静默终止，不崩不闹
            subject = null;
            return;
        }
        long now = System.currentTimeMillis();
        lastServerTickMs = now;
        // —— 墙钟硬超时（tick 在走的前提下的三道闸；tick 不走由看门狗兜底） ——
        if (now - runStartMs > GLOBAL_TIMEOUT_MS) {
            check("全局超时", false, "总耗时 " + (now - runStartMs) / 1000 + "s > 600s，强制收尾");
            finish(player);
            return;
        }
        if (now - scenarioStartMs > WAIT_TIMEOUT_MS) {
            check("等待超时", false, "场景耗时 " + (now - scenarioStartMs) / 1000
                    + "s > 60s（等待条件未满足），上一步=" + lastStepName + "，跳到下一场景");
            advance(player);
            return;
        }
        if (now - lastStepMs > STEP_TIMEOUT_MS) {
            check("步骤超时", false, "超时：上一步=" + lastStepName + " 耗时="
                    + (now - lastStepMs) / 1000 + "s > 30s，跳到下一场景");
            advance(player);
            return;
        }
        Scenario sc = scenarios.get(scenarioIndex);
        currentScenario = sc.id();
        if (scenarioTick > sc.timeout()) {
            check("场景超时", false, "超过 " + sc.timeout() + " tick 未走完，跳到下一场景");
            advance(player);
            return;
        }
        for (Step step : sc.steps()) {
            if (step.tick() != scenarioTick) continue;
            lastStepName = step.name();
            lastStepMs = System.currentTimeMillis();
            try {
                step.action().run(player);
            } catch (Throwable t) {
                check(step.name(), false, "步骤异常：" + t);
                Qianxiang.LOGGER.warn("[Qianxiang] PLAYTEST [{}] 步骤 T+{} 异常（继续）：{}",
                        sc.id(), scenarioTick, t.toString());
            }
        }
        if (scenarioTick >= sc.lastTick()) {
            advance(player);
        } else {
            scenarioTick++;
        }
    }

    /**
     * 失速看门狗：<b>独立 daemon 线程</b>，不依赖任何客户端事件——
     * ClientTickEvent 被暂停掐掉（timer 暂停 → Minecraft.tick 不执行）、
     * RenderFrameEvent 被 dynamic_fps 失焦限流掐掉（glfwWaitEventsTimeout 帧率趋零），
     * 两轮教训后改为纯线程：每 {@value WATCHDOG_INTERVAL_MS}ms 醒一次看
     * {@link #lastServerTickMs}（volatile，服务端线程每 tick 写）。
     * <ul>
     *   <li>&gt;10s 无 tick：每 10s WARN 一次（含 serverPaused），首次写 OBSERVE；</li>
     *   <li>&gt;15s 且为界面暂停：<b>暂停自愈</b>——经 {@code Minecraft.execute} 关暂停界面
     *       （21.1 没有 server.setPaused：IntegratedServer.paused 每 tick 从 Minecraft.pause
     *       镜像，权威源是暂停界面，关屏后下一帧 pause 重算 false，服务端自动恢复）；
     *       每次自愈记 WARN + OBSERVE；</li>
     *   <li>&gt;60s（或全局 10min）：记 FAIL「tick 停摆」，本线程直接写报告（文件 IO
     *       无线程限制，finish 单发守卫防双写）并 quitToDesktop，绝不干等。</li>
     * </ul>
     * 线程安全：只读 volatile 字段 + synchronizedList 的 REPORT + LOGGER；
     * subject 变 null（序列结束/玩家移除）或 finishing 即自行退出。
     */
    private static void startWatchdog() {
        stopWatchdog();
        Thread thread = new Thread(PlaytestHandler::watchdogLoop, "qianxiang-playtest-watchdog");
        thread.setDaemon(true); // daemon：装置异常残留也不拖住 JVM 退出
        watchdogThread = thread;
        thread.start();
    }

    private static void stopWatchdog() {
        Thread thread = watchdogThread;
        watchdogThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 看门狗主循环（见 {@link #startWatchdog} 文档）。 */
    private static void watchdogLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(WATCHDOG_INTERVAL_MS);
            } catch (InterruptedException e) {
                return; // finish/stopWatchdog 唤醒退出
            }
            ServerPlayer player = subject;
            if (player == null || finishing) return;
            long now = System.currentTimeMillis();
            long stalled = now - lastServerTickMs;
            boolean globalBlow = now - runStartMs > GLOBAL_TIMEOUT_MS;
            if (stalled >= STALL_WARN_MS) {
                boolean paused = isServerPaused(player);
                if (now - lastStallWarnMs >= STALL_WARN_MS) {
                    boolean firstWarn = lastStallWarnMs == 0L;
                    lastStallWarnMs = now;
                    Qianxiang.LOGGER.warn("[Qianxiang] PLAYTEST tick stalled {}s, serverPaused={}",
                            stalled / 1000, paused);
                    if (firstWarn) {
                        observe("tick 失速 " + stalled / 1000 + "s，serverPaused=" + paused
                                + "（单人游戏 ESC/窗口失焦会暂停集成服务器）");
                    }
                }
                if (paused && stalled >= STALL_HEAL_MS) {
                    unpauseAttempt(player); // 自愈成功则 1~2s 内 tick 恢复、stalled 回落
                }
            }
            if (stalled < STALL_ABORT_MS && !globalBlow) continue;
            boolean paused = isServerPaused(player);
            String reason = globalBlow
                    ? "全局超时 " + (now - runStartMs) / 1000 + "s"
                    : "tick 停摆 " + stalled / 1000 + "s";
            check("服务端 tick 停摆", false, "超时：上一步=" + lastStepName + " " + reason
                    + "，serverPaused=" + paused
                    + (paused ? "（游戏处于暂停且无法自动解除，本次检测不完整）"
                              : "（未暂停，服务端疑似卡死，检测不完整）"));
            if (paused) {
                REPORT.add("说明: 游戏处于暂停状态（非暂停界面来源，无法自动解除），本次检测不完整。");
            }
            finish(player);
            return;
        }
    }

    private static boolean isServerPaused(ServerPlayer player) {
        try {
            return player.getServer().isPaused();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 暂停自愈：暂停来自暂停界面（ESC/失焦弹出的 PauseScreen）时，调度到渲染线程关屏解除。
     * 本方法在看门狗线程调用；{@code Minecraft.execute} 保证 setScreen 跑在渲染线程。
     */
    private static void unpauseAttempt(ServerPlayer player) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen == null || !mc.screen.isPauseScreen()) {
                return; // overlay 暂停等无法经界面解除，交给熔断
            }
            mc.execute(() -> {
                try {
                    if (mc.screen != null && mc.screen.isPauseScreen()) {
                        mc.setScreen(null); // 下一帧 Minecraft.pause 重算 false → 服务端自动解除
                    }
                } catch (Throwable ignored) { }
            });
            Qianxiang.LOGGER.warn("[Qianxiang] PLAYTEST 检测到游戏暂停，已自动关闭暂停界面恢复检测"
                    + "（dev 装置特权，仅 QX_PLAYTEST=1 生效）");
            observe("检测到游戏暂停（暂停界面），已自动解除暂停恢复检测");
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] PLAYTEST 自动解除暂停失败：{}", t.toString());
        }
    }

    /** 进入下一场景；全部走完则写报告并退出游戏。 */
    private static void advance(ServerPlayer player) {
        scenarioIndex++;
        scenarioTick = 0;
        long now = System.currentTimeMillis();
        scenarioStartMs = now;
        lastStepMs = now;
        if (scenarioIndex < scenarios.size()) {
            Scenario next = scenarios.get(scenarioIndex);
            REPORT.add("---- [" + next.id() + "] " + next.title() + " ----");
            return;
        }
        finish(player);
    }

    // ============================ 检查记账 ============================

    /** 记一条检查：PASS/FAIL + 细节；FAIL 同时 ERROR 日志（报告与日志双通道）。 */
    private static void check(String name, boolean pass, String detail) {
        String line = (pass ? "PASS" : "FAIL") + " [" + currentScenario + "] " + name
                + (detail == null || detail.isEmpty() ? "" : " — " + detail);
        REPORT.add(line);
        if (pass) {
            passCount++;
            Qianxiang.LOGGER.info("[Qianxiang] PLAYTEST {}", line);
        } else {
            failCount++;
            Qianxiang.LOGGER.error("[Qianxiang] PLAYTEST {}", line);
        }
    }

    // ============================ 报告与退出 ============================

    /** 写 run/playtest/PLAYTEST_REPORT.txt，随后自动安全退出游戏。单发（finishing 守卫）。 */
    private static void finish(ServerPlayer player) {
        synchronized (REPORT) {
            if (finishing) return;
            finishing = true;
        }
        List<String> reportSnapshot;
        synchronized (REPORT) {
            reportSnapshot = new ArrayList<>(REPORT);
        }
        List<String> lines = new ArrayList<>();
        lines.add("牵响(Qianxiang) 自主游玩检测报告");
        lines.add("时间: " + new java.util.Date());
        lines.add("玩家: " + player.getGameProfile().getName());
        lines.add("================================");
        lines.addAll(reportSnapshot);
        lines.add("================================");
        lines.add("汇总: " + (passCount + failCount) + " 项检查, "
                + passCount + " PASS, " + failCount + " FAIL");
        lines.add(failCount == 0 ? "结论: 全部通过" : "结论: 存在失败项（见上，亦见 ERROR 日志）");
        try {
            Files.createDirectories(REPORT_PATH.getParent());
            Files.write(REPORT_PATH, lines, StandardCharsets.UTF_8);
            player.sendSystemMessage(Component.literal("[playtest] 完成 "
                    + passCount + "/" + (passCount + failCount) + " — 见 run/playtest/PLAYTEST_REPORT.txt"));
            Qianxiang.LOGGER.info("[Qianxiang] PLAYTEST 完成：{} PASS / {} FAIL，报告已写 {}",
                    passCount, failCount, REPORT_PATH);
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] PLAYTEST 报告写入失败", t);
        }
        subject = null; // 序列结束（在 quit 之前，防 stop 过程再进 tick）
        stopWatchdog();
        // 安全退出整个游戏（minecraft.stop() 会先正常关停集成服务器再退进程）；仅客户端。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                com.qianxiang.client.PlaytestQuit.quitToDesktop();
            } catch (Throwable t) {
                Qianxiang.LOGGER.warn("[Qianxiang] PLAYTEST 自动退出失败（报告已写，可手动退出）：{}", t.toString());
            }
        }
    }

    // ============================ 场景定义 ============================

    private static List<Scenario> buildScenarios() {
        List<Scenario> list = new ArrayList<>();
        list.add(setup());
        list.add(forgeChain());
        list.add(alchemyChain());
        list.add(wheelCast());
        list.add(proficiency());
        list.add(dimensionTrip());
        list.add(boss());
        list.add(storagePull());
        return list;
    }

    /** S 准备：平坦点 + 双台。 */
    private static Scenario setup() {
        return sc("S", "准备（传送/放台）",
                st(1, "时间天气", p -> {
                    runCommand(p, "time set day");
                    runCommand(p, "weather clear");
                }),
                st(3, "重置会话状态", p -> {
                    // 每次运行从干净状态开始：LegacySpellMigration/历史会话可能已把预置法术
                    // 学过了（学习幂等 no-op，曾致 B5「已学 3→3」假 FAIL）；熟练度同理。
                    p.setData(QianxiangAttachments.PLAYER_SPELL_DATA,
                            com.qianxiang.cap.PlayerSpellData.empty());
                    p.setData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA,
                            com.qianxiang.cap.PlayerProficiencyData.empty());
                }),
                st(5, "传送平坦点", p -> {
                    p.setGameMode(GameType.SURVIVAL); // 承伤/击杀路径按真实生存口径
                    teleportToSafety(p);
                }),
                st(10, "放台", p -> {
                    ServerLevel level = p.serverLevel();
                    BlockPos base = p.blockPosition();
                    BlockPos forge = new BlockPos(base.getX(), findGroundY(level, base), base.getZ() + 3);
                    level.setBlockAndUpdate(forge, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
                    level.setBlockAndUpdate(forge.east(), QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
                    CTX.put("forgePos", forge);
                    CTX.put("alchPos", forge.east());
                    // 跨运行的世界残留复位：同一存档同坐标 setBlockAndUpdate 同种方块时
                    // 旧 BE（连同旧材料/旧产物/卡住的仪式）会被原样保留——旧运行留在台里的
                    // 料产出过 spell_book，A4 拾到旧货（本轮实锤）。必须连 BE 一起复位。
                    resetWorldResidue(p);
                }),
                st(15, "S1 台子就位", p -> {
                    boolean ok = forgeBe(p) != null && alchemyBe(p) != null;
                    check("S1 台子就位", ok, "forge=" + CTX.get("forgePos") + " alchemy=" + CTX.get("alchPos"));
                }));
    }

    /** A 锻造全链。仪式 FLYING 30t + FORMING 50t（无 adept 提速），DONE 检查留余量到 T+92。 */
    private static Scenario forgeChain() {
        return sc("A", "锻造全链",
                st(0, "投料", p -> {
                    ForgeTableBlockEntity be = requireForge(p);
                    be.setItem(12, new ItemStack(Items.IRON_INGOT));  // 核心：基底
                    be.setItem(6, new ItemStack(Items.FLINT));        // 内圈：锋刃
                    be.setItem(7, new ItemStack(Items.BLAZE_POWDER)); // 内圈：点燃
                    // 无菜单打开时 BE 不自重算：借菜单跑一遍 compose 刷新预览槽（同 AutoSmoke）
                    new ForgeTableMenu(0, p.getInventory(), be).slotsChanged(be);
                }),
                st(2, "A1 预览非空", p -> {
                    ItemStack preview = requireForge(p).getItem(ForgeTableMenu.RESULT_SLOT);
                    ComposedAttributes attr = preview.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
                    check("A1 预览非空", !preview.isEmpty() && attr != null,
                            "preview=" + preview.getItem() + " attackDamage="
                                    + (attr == null ? "?" : attr.attackDamage()));
                    shot(p, 0, false); // 台面材料虚影 + 产物预览
                }),
                st(4, "A2 仪式启动", p ->
                        check("A2 仪式启动", RitualLogic.startRitual(requireForge(p), p), "")),
                st(45, "仪式 FORMING 截图", p -> shot(p, 0, false)),
                st(92, "A3 仪式 DONE", p -> {
                    ForgeTableBlockEntity be = requireForge(p);
                    boolean done = be.ritualState() == RitualState.DONE && !be.getDisplayResult().isEmpty();
                    check("A3 仪式 DONE", done, "state=" + be.ritualState());
                    shot(p, 0, false); // DONE 产物虚影
                }),
                st(95, "拾取+属性", p -> {
                    ForgeTableBlockEntity be = requireForge(p);
                    pickupDisplayResult(be, p); // 空手右键拾取的等效 BE 操作（见 ForgeTableBlock:79-91）
                    int slot = findStackWith(p, QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
                    check("A4 空手拾取入包", slot >= 0, "slot=" + slot);
                    if (slot < 0) return;
                    ItemStack product = p.getInventory().getItem(slot);
                    ComposedAttributes attr = product.get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
                    check("A5 产物攻击>0", attr != null && attr.attackDamage() > 0,
                            "attackDamage=" + (attr == null ? "?" : attr.attackDamage()));
                    CTX.put("weaponSlot", slot);
                    CTX.put("weaponStack", product.copy()); // A6 装备用 A4 实拾的栈，不靠槽位假设
                }),
                st(98, "主手装备", p -> {
                    ItemStack stored = (ItemStack) CTX.get("weaponStack");
                    if (stored == null) return;
                    // 攻击结算读的是「当前选中热键槽」（inv.selected），不是 MAINHAND 槽语义——
                    // 产物在 slot=1 而 selected=0 时等于空手（上一轮 A6 只掉 1.0 的假 PASS 根因）。
                    // 显式写入选中槽，攻击留给 6 tick 后（属性修饰符随装备变化每 tick 重算）。
                    var inv = p.getInventory();
                    Integer slot = (Integer) CTX.get("weaponSlot");
                    if (slot != null && slot < inv.getContainerSize()) {
                        inv.removeItem(slot, 1); // 摘掉原入包栈（若还在），防双份
                    }
                    inv.setItem(inv.selected, stored.copy());
                    shot(p, 1, false); // 第一人称手持
                }),
                st(101, "召唤假人", p -> CTX.put("dummy", spawnPinnedDummy(p, 2.5))),
                st(104, "A6 攻击掉血", p -> {
                    Pig pig = (Pig) CTX.get("dummy");
                    if (pig == null || !pig.isAlive()) {
                        check("A6 攻击假人掉血", false, "假人不存在/已死");
                        return;
                    }
                    pinDummy(p, pig, 2.5); // 攻击前校验位置，偏了拉回（近距触及 3 格内）
                    fillAttackStrength(p);
                    float before = pig.getHealth();
                    p.attack(pig);
                    float lost = before - pig.getHealth();
                    // 空手一拳恰好 1.0：只掉 1.0 等于武器攻击修饰符没生效（用户「武器不到材料能力
                    // 万分之一」投诉的潜在病根）——按 FAIL 处理并附全部证据，绝不掩盖。
                    check("A6 攻击假人掉血", lost > 1.0f,
                            "掉血=" + lost + "（" + before + "→" + pig.getHealth() + "）"
                                    + weaponEvidence(p));
                }));
    }

    /** B 炼金全链（本地材料模板路径，零 AI 外呼）。 */
    private static Scenario alchemyChain() {
        return sc("B", "炼金全链",
                st(0, "投料", p -> {
                    AlchemyTableBlockEntity be = requireAlchemy(p);
                    be.setItem(0, new ItemStack(QianxiangItems.EMBER_CRYSTAL.get()));
                    be.recomputeResult(p.getUUID()); // viewer 的 AI 选择为空 → 材料模板路径
                }),
                st(2, "B1 卷轴预览", p -> {
                    ItemStack preview = requireAlchemy(p).getItem(AlchemyTableMenu.RESULT_SLOT);
                    CustomSpell spell = preview.get(QianxiangDataComponents.CUSTOM_SPELL.get());
                    check("B1 卷轴预览", !preview.isEmpty() && spell != null,
                            "spell=" + (spell == null ? "?" : spell.id()));
                }),
                st(4, "B2 仪式启动", p ->
                        check("B2 仪式启动", RitualLogic.startRitual(requireAlchemy(p), p), "")),
                st(92, "B3 仪式 DONE", p -> {
                    AlchemyTableBlockEntity be = requireAlchemy(p);
                    ItemStack display = be.getDisplayResult();
                    boolean done = be.ritualState() == RitualState.DONE
                            && display.has(QianxiangDataComponents.CUSTOM_SPELL.get());
                    check("B3 仪式 DONE", done, "state=" + be.ritualState());
                    shot(p, 0, false);
                }),
                st(95, "拾取+学习", p -> {
                    AlchemyTableBlockEntity be = requireAlchemy(p);
                    pickupDisplayResult(be, p);
                    int slot = findStackWith(p, QianxiangDataComponents.CUSTOM_SPELL.get());
                    check("B4 拾取卷轴入包", slot >= 0, "slot=" + slot);
                    if (slot < 0) return;
                    ItemStack scroll = p.getInventory().removeItem(slot, 1);
                    CustomSpell spell = scroll.get(QianxiangDataComponents.CUSTOM_SPELL.get());
                    p.setItemSlot(EquipmentSlot.MAINHAND, scroll);
                    int before = learnedCount(p);
                    scroll.getItem().use(p.serverLevel(), p, InteractionHand.MAIN_HAND); // 真实右键学习路径
                    int after = learnedCount(p);
                    check("B5 右键学习+1", after == before + 1, "已学 " + before + "→" + after);
                    if (spell != null) {
                        CTX.put("scrollSpellId", spell.id().toString());
                    }
                }));
    }

    /**
     * C 轮盘施法：已学 id → castLearnedSpell（CastSpellPayload 服务端入口同路径）。
     * <p>
     * C1（受理验证）与 C4（命中验证）<b>不能共用同一只假人</b>：B 的卷轴法术
     * （余烬晶体 → forged_fireball，fire/projectile/damage）在 T+2 命中钉在正前方的
     * 假人后，假人进入 20t 伤害冷却——T+4（仅 2t 后）的 aoe hurt() 被
     * {@code invulnerableTime > invulnerableDuration/2} 整段拒掉，C4 必然 0 掉血
     * （上一轮「掉血=0.0」的根因；未钉假人时反而 PASS 是因为火球打偏了）。
     * 所以 C1 侧头朝 -X 空放（受理不需要靶子），T+3 才转回 +Z 钉假人。
     * </p>
     */
    private static Scenario wheelCast() {
        return sc("C", "轮盘施法",
                st(0, "准备", p -> {
                    learn(p, new CustomSpell(
                            ResourceLocation.fromNamespaceAndPath("qianxiang", "playtest_fire_aoe"),
                            "fire", "aoe", "damage", List.of(), 10, 40, 2));
                    setMana(p, 100);
                }),
                st(2, "C1 卷轴法术受理", p -> {
                    String scrollId = (String) CTX.get("scrollSpellId");
                    if (scrollId == null) {
                        check("C1 已学 id 施法受理", false, "B 场景未产出已学 id");
                        return;
                    }
                    face(p, 90.0f); // 侧头朝 -X：弹体空放，self/aoe 形态此刻场上也无假人
                    boolean accepted = SpellCastHandler.castLearnedSpell(p, scrollId);
                    check("C1 已学 id 施法受理", accepted, "id=" + scrollId);
                    shot(p, 1, false);
                }),
                st(3, "复位朝向+召唤假人", p -> {
                    face(p, 0.0f);
                    // 钉在正前方 3 格：aoe 圆心 = 前方 4 格、半径 2+power=4，3 格处稳在结算半径内
                    CTX.put("dummy2", spawnPinnedDummy(p, 3.0));
                }),
                st(4, "C2-4 aoe 施法", p -> {
                    Pig pig = (Pig) CTX.get("dummy2");
                    if (pig != null && pig.isAlive()) {
                        pinDummy(p, pig, 3.0); // 施法前校验位置，偏了拉回
                    }
                    setMana(p, 100);
                    int manaBefore = manaOf(p);
                    float pigBefore = pig != null && pig.isAlive() ? pig.getHealth() : -1;
                    boolean accepted = SpellCastHandler.castLearnedSpell(p, "qianxiang:playtest_fire_aoe");
                    int manaAfter = manaOf(p);
                    int cooldown = p.getData(QianxiangAttachments.PLAYER_SPELL_DATA)
                            .cooldownOf(ResourceLocation.fromNamespaceAndPath("qianxiang", "playtest_fire_aoe"));
                    check("C2 法力减少", accepted && manaAfter < manaBefore,
                            "mana " + manaBefore + "→" + manaAfter + "（accepted=" + accepted + "）");
                    check("C3 冷却写入", cooldown > 0, "cooldown=" + cooldown + "t");
                    float lost = pigBefore > 0 && pig != null ? pigBefore - pig.getHealth() : 0;
                    check("C4 假人掉血", pig != null && lost > 0,
                            "掉血=" + lost + aoeEvidence(p, pig));
                }));
    }

    /** D 熟练度：相师确认窗两次潜行交互 → unlocked；击杀/施法攒 xp。 */
    private static Scenario proficiency() {
        return sc("D", "熟练度",
                st(0, "D1 相师开启", p -> {
                    QianxiangWanderingSage sage = QianxiangEntities.WANDERING_SAGE.get()
                            .spawn(p.serverLevel(), p.blockPosition().offset(2, 0, 0), MobSpawnType.COMMAND);
                    if (sage == null) {
                        check("D1 潜行交互开启", false, "相师 spawn 失败");
                        return;
                    }
                    p.setShiftKeyDown(true);
                    sage.mobInteract(p, InteractionHand.MAIN_HAND); // 首击：确认窗提示
                    sage.mobInteract(p, InteractionHand.MAIN_HAND); // 窗内再击：确认开启
                    p.setShiftKeyDown(false);
                    boolean unlocked = p.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA).unlocked();
                    check("D1 潜行交互开启", unlocked, "unlocked=" + unlocked);
                }),
                st(3, "D2 击杀 xp", p -> {
                    Pig pig = EntityType.PIG.spawn(p.serverLevel(),
                            p.blockPosition().offset(0, 0, 2), MobSpawnType.COMMAND);
                    int before = xpOf(p, ProficiencyTrack.COMBAT);
                    if (pig != null) {
                        pig.hurt(p.damageSources().playerAttack(p), 100.0f); // 真实击杀事件链
                    }
                    int after = xpOf(p, ProficiencyTrack.COMBAT);
                    check("D2 击杀假人 xp>0", after > before, "combat xp " + before + "→" + after);
                }),
                st(5, "D3 施法 xp", p -> {
                    learn(p, new CustomSpell(
                            ResourceLocation.fromNamespaceAndPath("qianxiang", "playtest_arcane_ping"),
                            "arcane", "self", "utility", List.of(), 8, 1, 1));
                    setMana(p, 100);
                    int before = xpOf(p, ProficiencyTrack.ARCANE);
                    SpellCastHandler.castLearnedSpell(p, "qianxiang:playtest_arcane_ping");
                    int after = xpOf(p, ProficiencyTrack.ARCANE);
                    check("D3 施法 xp>0", after > before, "arcane xp " + before + "→" + after);
                }));
    }

    /** E 维度往返：真实 RightClickBlock 事件总线路径（与玩家右键裂隙岩完全同路）。 */
    private static Scenario dimensionTrip() {
        return sc("E", "维度往返",
                st(0, "位格+放裂隙岩", p -> {
                    var saga = p.getData(QianxiangAttachments.SAGA_DATA);
                    p.setData(QianxiangAttachments.SAGA_DATA,
                            saga.withBumpedPosition(MyriadWildsPortalHandler.WILDS_POSITION_THRESHOLD));
                    ServerLevel level = p.serverLevel();
                    BlockPos base = p.blockPosition();
                    BlockPos stone = new BlockPos(base.getX(), findGroundY(level, base), base.getZ() + 2);
                    level.setBlockAndUpdate(stone, QianxiangBlocks.RIFT_STONE.get().defaultBlockState());
                    CTX.put("riftStone", stone);
                    p.setItemSlot(EquipmentSlot.MAINHAND,
                            new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get(), 2));
                }),
                st(2, "去程", p -> rightClickBlock(p, (BlockPos) CTX.get("riftStone"))),
                st(7, "E1/E2 抵达检查", p -> {
                    boolean inWilds = p.serverLevel().dimension() == QianxiangDimensions.MYRIAD_WILDS;
                    check("E1 抵达万象森罗", inWilds, "dim=" + p.serverLevel().dimension().location());
                    if (inWilds) {
                        BlockPos feet = p.blockPosition();
                        check("E2 落点可站不埋不淹", standable(p.serverLevel(), feet), "feet=" + feet);
                        shot(p, 0, false);
                    }
                }),
                st(10, "回程", p -> {
                    BlockPos stone = findRiftStoneNear(p.serverLevel(), p.blockPosition());
                    if (stone == null) {
                        check("E3 回程", false, "落点附近找不到回程裂隙岩");
                        return;
                    }
                    CTX.put("returnStone", stone);
                    p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // 回程空手即可（不消耗）
                    rightClickBlock(p, stone);
                }),
                st(15, "E3 回程检查", p ->
                        check("E3 回程主世界", p.serverLevel().dimension() == ServerLevel.OVERWORLD,
                                "dim=" + p.serverLevel().dimension().location())));
    }

    /** F Boss：守望者 → 打死 → warden_core 掉落 + 讨伐成就。 */
    private static Scenario boss() {
        return sc("F", "Boss 讨伐",
                st(0, "spawn 守望者", p -> {
                    // 西侧 -3：避开 z+3 的锻造台/炼金台与 G 场景台旁箱子的落点
                    QianxiangMyriadWarden warden = QianxiangEntities.MYRIAD_WARDEN.get()
                            .spawn(p.serverLevel(), p.blockPosition().offset(-3, 0, 0), MobSpawnType.COMMAND);
                    CTX.put("warden", warden);
                    CTX.put("wardenPos", p.blockPosition().offset(-3, 0, 0));
                }),
                st(2, "守望者截图", p -> {
                    shot(p, 0, false);
                    QianxiangMyriadWarden warden = (QianxiangMyriadWarden) CTX.get("warden");
                    if (warden != null && warden.isAlive()) {
                        warden.hurt(p.damageSources().playerAttack(p), 10000.0f); // 玩家伤害击杀（触发器口径）
                    }
                }),
                st(7, "F1/F2 掉落+成就", p -> {
                    BlockPos at = (BlockPos) CTX.get("wardenPos");
                    boolean coreDropped = false;
                    for (ItemEntity drop : p.serverLevel().getEntitiesOfClass(
                            ItemEntity.class, new AABB(at).inflate(5.0))) {
                        if (drop.getItem().is(QianxiangItems.WARDEN_CORE.get())) {
                            coreDropped = true;
                            break;
                        }
                    }
                    check("F1 掉落森罗之核", coreDropped, "");
                    check("F2 讨伐成就", advancementDone(p, "story/kill_warden"), "qianxiang:story/kill_warden");
                }));
    }

    /** G 存储取料：箱子在台旁 r=4 内，背包无铁，placeMaterials 必须从箱子抽取。 */
    private static Scenario storagePull() {
        return sc("G", "存储取料",
                st(0, "箱子放料+抽取", p -> {
                    ForgeTableBlockEntity be = requireForge(p);
                    ServerLevel level = p.serverLevel();
                    BlockPos forgePos = (BlockPos) CTX.get("forgePos");
                    // 台子的掉落物吸收（absorbAbove 每 5t）会把假人掉落的猪肉等吸进材料槽
                    // （上一轮 G3 slot12=porkchop 的根因）——先清空两台材料槽 + 清台面掉落物。
                    clearMaterialSlots(be, ForgeTableMenu.MATERIAL_SLOTS);
                    AlchemyTableBlockEntity alch = alchemyBe(p);
                    if (alch != null) {
                        clearMaterialSlots(alch, AlchemyTableMenu.MATERIAL_SLOTS);
                    }
                    for (ItemEntity drop : level.getEntitiesOfClass(
                            ItemEntity.class, new AABB(forgePos).inflate(3.0))) {
                        drop.discard();
                    }
                    BlockPos chestPos = forgePos.west(); // r=4 内
                    level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
                    if (!(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
                        check("G1 箱子抽取", false, "箱子放置失败");
                        return;
                    }
                    chest.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
                    removeAllFromInventory(p, Items.IRON_INGOT); // 背包无铁 → 只能来自箱子
                    var result = AiPlaceMaterialsHandler.placeMaterials(p, be,
                            ForgeTableMenu.SLOT_FILL_ORDER,
                            List.of(Items.IRON_INGOT, Items.IRON_INGOT), false);
                    check("G1 AI 放料从箱子抽取", result.placedCount() == 2,
                            "placed=" + result.placedCount() + " missing=" + result.missing());
                    check("G2 箱子扣减", chest.getItem(0).getCount() == 1,
                            "箱子剩余=" + chest.getItem(0).getCount());
                    // 铁锭在材料区某槽（填充序靠前）且总数正确，不锁死具体槽位
                    int ironInTable = 0;
                    StringBuilder slots = new StringBuilder();
                    for (int i = 0; i < ForgeTableMenu.MATERIAL_SLOTS; i++) {
                        ItemStack s = be.getItem(i);
                        if (s.isEmpty()) continue;
                        slots.append(i).append('=').append(s.getItem()).append('x').append(s.getCount()).append(' ');
                        if (s.is(Items.IRON_INGOT)) ironInTable += s.getCount();
                    }
                    check("G3 材料入台", ironInTable == 2, "铁锭数=" + ironInTable + " 槽位[" + slots + "]");
                    observe("台子掉落物吸收（absorbAbove）不分物品种类，会把玩家误扔/生物掉落在台边的"
                            + "任何东西吸进材料槽（本轮假人猪肉即被吸入）。建议后续版本加白名单"
                            + "（只吸带材料概念/功能算子的物品）；本次仅观察记录，不动实现。");
                }));
    }

    // ============================ 场景工具 ============================

    /** 观察项（不计 PASS/FAIL）：产品行为存疑、值得跟进但本次不断言的问题。 */
    private static void observe(String text) {
        REPORT.add("OBSERVE [" + currentScenario + "] " + text);
        Qianxiang.LOGGER.warn("[Qianxiang] PLAYTEST OBSERVE [{}] {}", currentScenario, text);
    }

    /** 清空功能台材料槽（不动产物预览槽——G 场景前的环境复位用）。 */
    private static void clearMaterialSlots(net.minecraft.world.Container container, int materialSlots) {
        for (int i = 0; i < materialSlots; i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
    }

    /**
     * 跨运行世界残留复位（S 放台后调用）：同一存档同坐标放同种方块时旧 BE 会被原样保留
     * （旧材料/旧产物/卡住的仪式全在）——清空两台材料槽+产物槽+ritualInputs/pendingResult
     * 并重算预览、清两台 4 格内掉落物、清空玩家背包，保证 A-G 只依赖本轮产出。
     */
    private static void resetWorldResidue(ServerPlayer player) {
        ForgeTableBlockEntity forge = forgeBe(player);
        if (forge != null) {
            clearMaterialSlots(forge, ForgeTableMenu.MATERIAL_SLOTS);
            resetTableRitual(forge);
            forge.setItem(ForgeTableMenu.RESULT_SLOT, ItemStack.EMPTY);
            new ForgeTableMenu(0, player.getInventory(), forge).slotsChanged(forge); // 空料重算 → 预览清空
        }
        AlchemyTableBlockEntity alch = alchemyBe(player);
        if (alch != null) {
            clearMaterialSlots(alch, AlchemyTableMenu.MATERIAL_SLOTS);
            resetTableRitual(alch);
            alch.setItem(AlchemyTableMenu.RESULT_SLOT, ItemStack.EMPTY);
            alch.recomputeResult(player.getUUID());
        }
        ServerLevel level = player.serverLevel();
        for (BlockPos pos : new BlockPos[]{(BlockPos) CTX.get("forgePos"), (BlockPos) CTX.get("alchPos")}) {
            if (pos == null) continue;
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4.0))) {
                drop.discard();
            }
        }
        player.getInventory().clearContent(); // 背包同样清零：A4/A6/G 只信本轮产出
    }

    /** 清一台的仪式残留：ritualInputs/pendingResult/displayResult 弃置、状态机归 NONE（dev 世界，材料不赔）。 */
    private static void resetTableRitual(com.qianxiang.block.RitualHost host) {
        host.ritualInputs().clear();
        host.setPendingResult(ItemStack.EMPTY);
        host.setDisplayResultFromRitual(ItemStack.EMPTY);
        host.clearRitualState();
    }

    /**
     * A6 取证串：武器是否真进了选中槽、攻击属性是否生效、修饰符组件内容、EF 是否在场。
     * 只掉 1.0（空手一拳）时，这串就是「武器攻击修饰符真机不生效」的定案证据。
     */
    private static String weaponEvidence(ServerPlayer player) {
        ItemStack mainhand = player.getMainHandItem();
        var mods = mainhand.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        return " | 证据: mainhand=" + mainhand.getItem()
                + " selected=" + player.getInventory().selected
                + " getAttributeValue(ATTACK_DAMAGE)="
                + player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                + " ATTRIBUTE_MODIFIERS=" + (mods == null ? "无" : mods)
                + " epicfight=" + (net.neoforged.fml.ModList.get().isLoaded("epicfight") ? "在场" : "不在");
    }

    private static Scenario sc(String id, String title, Step... steps) {
        int last = 0;
        for (Step s : steps) last = Math.max(last, s.tick());
        return new Scenario(id, title, List.of(steps), last, last + 300);
    }

    private static Step st(int tick, String name, StepAction action) {
        return new Step(tick, name, action);
    }

    private static void runCommand(ServerPlayer player, String cmd) {
        player.getServer().getCommands().performPrefixedCommand(
                player.getServer().createCommandSourceStack(), cmd);
    }

    private static void teleportToSafety(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos spawn = level.getSharedSpawnPos();
        BlockPos surface = level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
        player.connection.teleport(surface.getX() + 0.5, surface.getY(), surface.getZ() + 0.5,
                0.0f, 12.0f); // yaw 0 朝 +Z：假人/台子/裂隙岩都摆在 +Z 方向
    }

    private static int findGroundY(ServerLevel level, BlockPos near) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near).getY();
    }

    private static ForgeTableBlockEntity forgeBe(ServerPlayer player) {
        BlockPos pos = (BlockPos) CTX.get("forgePos");
        return pos != null && player.serverLevel().getBlockEntity(pos) instanceof ForgeTableBlockEntity be
                ? be : null;
    }

    private static AlchemyTableBlockEntity alchemyBe(ServerPlayer player) {
        BlockPos pos = (BlockPos) CTX.get("alchPos");
        return pos != null && player.serverLevel().getBlockEntity(pos) instanceof AlchemyTableBlockEntity be
                ? be : null;
    }

    private static ForgeTableBlockEntity requireForge(ServerPlayer player) {
        ForgeTableBlockEntity be = forgeBe(player);
        if (be == null) throw new IllegalStateException("锻造台 BE 不存在（放置失败？）");
        return be;
    }

    private static AlchemyTableBlockEntity requireAlchemy(ServerPlayer player) {
        AlchemyTableBlockEntity be = alchemyBe(player);
        if (be == null) throw new IllegalStateException("炼金台 BE 不存在（放置失败？）");
        return be;
    }

    /** 空手右键 DONE 台拾取产物的等效 BE 操作（与 ForgeTableBlock/AlchemyTableBlock 的 DONE 分支逐行一致）。 */
    private static void pickupDisplayResult(com.qianxiang.block.RitualHost host, ServerPlayer player) {
        ItemStack taken = host.getDisplayResult().copy();
        if (taken.isEmpty()) return;
        host.setDisplayResultFromRitual(ItemStack.EMPTY);
        host.clearRitualState();
        if (!player.getInventory().add(taken)) {
            player.drop(taken, false);
        }
    }

    /** 背包主 36 格内第一个带指定组件的物品槽；-1 = 没有。 */
    private static int findStackWith(ServerPlayer player,
                                     net.minecraft.core.component.DataComponentType<?> type) {
        for (int i = 0; i < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; i++) {
            if (player.getInventory().getItem(i).has(type)) return i;
        }
        return -1;
    }

    private static void removeAllFromInventory(ServerPlayer player, net.minecraft.world.item.Item item) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * 生成「钉住」的假人：NoAI（不游走）+ 落在玩家正前方 {@code forward} 格固定点。
     * 猪会游走，曾跑出 AoE 结算半径致 C4 掉血 0.0 的假 FAIL。
     */
    private static Pig spawnPinnedDummy(ServerPlayer player, double forward) {
        Pig pig = EntityType.PIG.spawn(player.serverLevel(),
                player.blockPosition().offset(0, 0, (int) Math.ceil(forward)), MobSpawnType.COMMAND);
        if (pig != null) {
            pig.setNoAi(true);
            pinDummy(player, pig, forward);
        }
        return pig;
    }

    /** 把假人钉回玩家正前方 {@code forward} 格固定点（yaw 0 → +Z）；偏离 >0.5 格才拉回。 */
    private static void pinDummy(ServerPlayer player, Pig pig, double forward) {
        double tx = player.getX();
        double tz = player.getZ() + forward;
        if (pig.distanceToSqr(tx, pig.getY(), tz) > 0.25) {
            pig.teleportTo(tx, player.getY(), tz);
        }
    }

    /** 只转朝向不挪位置（保持 pitch 12° 俯视，让台子/假人入画）。 */
    private static void face(ServerPlayer player, float yaw) {
        player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, 12.0f);
    }

    /**
     * C4 取证串（无论 PASS/FAIL 写入报告）：施法瞬间玩家 pos/yaw/pitch、AoE 圆心与半径
     * （按 SpellEffectEngine.castAoe 同口径现算）、假人 pos 与距圆心距离、伤害冷却余量
     * （invulnerableTime > 10 时 hurt 只结算超出 lastHurt 的部分——C4 曾因此 0 掉血）、法术四元组。
     */
    private static String aoeEvidence(ServerPlayer player, Pig pig) {
        var spell = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).findLearned(
                ResourceLocation.fromNamespaceAndPath("qianxiang", "playtest_fire_aoe")).orElse(null);
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        if (flat.lengthSqr() < 0.0025) {
            flat = new Vec3(0.0, 0.0, 1.0);
        }
        Vec3 center = player.position().add(flat.normalize().scale(4.0));
        double radius = 2.0 + (spell == null ? 0 : spell.power());
        String pigInfo = pig == null
                ? "假人=null"
                : String.format(java.util.Locale.ROOT,
                        "假人=(%.2f,%.2f,%.2f) 距圆心=%.2f invulnerableTime=%d",
                        pig.getX(), pig.getY(), pig.getZ(),
                        Math.sqrt(pig.distanceToSqr(center.x, player.getY(), center.z)),
                        pig.invulnerableTime);
        return String.format(java.util.Locale.ROOT,
                " | 证据: 玩家=(%.2f,%.2f,%.2f) yaw=%.1f pitch=%.1f 圆心=(%.2f,%.2f,%.2f) 半径=%.1f %s"
                        + " spell=%s(%s/%s/%s,power=%d)",
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                center.x, center.y, center.z, radius, pigInfo,
                spell == null ? "?" : spell.id(), spell == null ? "?" : spell.element(),
                spell == null ? "?" : spell.form(), spell == null ? "?" : spell.effect(),
                spell == null ? 0 : spell.power());
    }

    /** 灌满攻击蓄力槽（GameTest 同款反射），否则 player.attack 只有 ~10% 伤害。 */
    private static void fillAttackStrength(ServerPlayer player) throws Exception {
        var field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("attackStrengthTicker");
        field.setAccessible(true);
        field.setInt(player, 200);
    }

    private static int learnedCount(ServerPlayer player) {
        return player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).learnedSpells().size();
    }

    private static int manaOf(ServerPlayer player) {
        return player.getData(QianxiangAttachments.PLAYER_SPELL_DATA).currentMana();
    }

    private static void setMana(ServerPlayer player, int mana) {
        var data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data.withMana(mana));
    }

    private static void learn(ServerPlayer player, CustomSpell spell) {
        var data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        player.setData(QianxiangAttachments.PLAYER_SPELL_DATA, data.learn(spell));
    }

    private static int xpOf(ServerPlayer player, ProficiencyTrack track) {
        return player.getData(QianxiangAttachments.PLAYER_PROFICIENCY_DATA).xpOf(track);
    }

    /** 可观测成就检查（与 QianxiangAdvancements.grant 同一查询口径）。 */
    private static boolean advancementDone(ServerPlayer player, String path) {
        AdvancementHolder holder = player.server.getAdvancements()
                .get(ResourceLocation.fromNamespaceAndPath(Qianxiang.MOD_ID, path));
        return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
    }

    /** 走真实事件总线右键一个方块（与玩家右键完全同路，QianxiangUxFixGameTests 同款构造）。 */
    private static void rightClickBlock(ServerPlayer player, BlockPos pos) {
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, pos, hit));
    }

    /** 落点可站：脚下实心非流体（树叶算），身体两格无碰撞无流体——与传送门 findSafePos 同口径。 */
    private static boolean standable(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        var groundState = level.getBlockState(ground);
        boolean solidGround = groundState.isSolidRender(level, ground) || groundState.is(BlockTags.LEAVES);
        if (!solidGround || !groundState.getFluidState().isEmpty()) return false;
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet).getFluidState().isEmpty()
                && level.getBlockState(feet.above()).getFluidState().isEmpty();
    }

    private static BlockPos findRiftStoneNear(ServerLevel level, BlockPos center) {
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-8, -4, -8), center.offset(8, 4, 8))) {
            if (level.getBlockState(p).is(QianxiangBlocks.RIFT_STONE.get())) {
                return p.immutable();
            }
        }
        return null;
    }

    /** 发截图请求：perspective 0=不切 / 1=第一 / 2=第三背面（复用冒烟同一路由）。 */
    private static void shot(ServerPlayer player, int perspective, boolean closeScreen) {
        PacketDistributor.sendToPlayer(player,
                new ScreenshotRequestPayload(perspective, closeScreen));
    }
}
