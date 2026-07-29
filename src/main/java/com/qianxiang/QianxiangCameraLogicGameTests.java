package com.qianxiang;

import com.qianxiang.client.CombatCameraConfig;
import com.qianxiang.client.CombatCameraLogic;
import com.qianxiang.client.CombatCameraLogic.CameraGoal;
import com.qianxiang.client.CombatCameraLogic.CombatEventType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/**
 * 战斗镜头状态机 {@link CombatCameraLogic} 的单测（纯客户端功能，GameTest 只测
 * 抽出来的纯逻辑类与配置解析，不碰镜头本身——镜头写入无法在服务端观测）。
 * <p>
 * 断言对象 = 状态机的输出契约（每次调用返回的 {@link Optional}：非空即「此刻应切到
 * 该镜头」，空即「无切换」），这是该纯逻辑类唯一可观测的最终状态。
 * 单向否定断言（不应切换）一律配「该触发的切换确实触发」的正向断言。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangCameraLogicGameTests {

    private QianxiangCameraLogicGameTests() {}

    private static final long T0 = 1000L;

    /** PVP 事件 → 切第一人称；窗口内重复 PVP 事件不重复发切换信号（配 isPvpActive 正向观测）。 */
    @GameTest(template = "item_concept")
    public static void pvpEventSwitchesToFirstPersonOnce(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        Optional<CameraGoal> first = logic.onCombatEvent(CombatEventType.PVP, T0);
        helper.assertTrue(first.isPresent() && first.get() == CameraGoal.FIRST_PERSON,
                "首次 PVP 事件应输出切第一人称，实际 " + first);

        Optional<CameraGoal> repeat = logic.onCombatEvent(CombatEventType.PVP, T0 + 10);
        helper.assertTrue(repeat.isEmpty(),
                "PVP 窗口内重复事件不应重复发切换信号，实际 " + repeat);
        helper.assertTrue(logic.isPvpActive(), "重复事件后 PVP 态应仍挂着");
        helper.succeed();
    }

    /** PVP 态消失满 4 秒才回第三人称：截止时刻前不发（负向）+ 过线即发（正向）。 */
    @GameTest(template = "item_concept")
    public static void pvpExitOnlyAfterWindowPlusDelay(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        logic.onCombatEvent(CombatEventType.PVP, T0);

        long deadline = T0 + CombatCameraLogic.PVP_WINDOW_TICKS + CombatCameraLogic.EXIT_DELAY_TICKS;
        Optional<CameraGoal> before = logic.onTick(deadline);
        helper.assertTrue(before.isEmpty(),
                "PVP 窗口+冷却截止时刻前不应退出，实际 " + before);
        helper.assertTrue(logic.isPvpActive(), "截止时刻前 PVP 态应仍挂着");

        Optional<CameraGoal> after = logic.onTick(deadline + 1);
        helper.assertTrue(after.isPresent() && after.get() == CameraGoal.THIRD_PERSON,
                "PVP 态消失满 4 秒后应输出回第三人称，实际 " + after);
        helper.assertTrue(!logic.isPvpActive(), "退出后 PVP 态应已清除");
        helper.succeed();
    }

    /** 窗口内新的 PVP 事件刷新 6 秒计时：按旧时刻到点不退（负向）+ 按新时刻到点才退（正向）。 */
    @GameTest(template = "item_concept")
    public static void pvpEventRefreshesWindow(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        logic.onCombatEvent(CombatEventType.PVP, T0);
        logic.onCombatEvent(CombatEventType.PVP, T0 + 100);

        long oldDeadline = T0 + CombatCameraLogic.PVP_WINDOW_TICKS + CombatCameraLogic.EXIT_DELAY_TICKS;
        Optional<CameraGoal> atOldDeadline = logic.onTick(oldDeadline + 1);
        helper.assertTrue(atOldDeadline.isEmpty(),
                "PVP 计时已被刷新，按旧时刻到点不应退出，实际 " + atOldDeadline);

        long newDeadline = T0 + 100 + CombatCameraLogic.PVP_WINDOW_TICKS + CombatCameraLogic.EXIT_DELAY_TICKS;
        Optional<CameraGoal> atNewDeadline = logic.onTick(newDeadline + 1);
        helper.assertTrue(atNewDeadline.isPresent() && atNewDeadline.get() == CameraGoal.THIRD_PERSON,
                "按刷新后时刻到点应退出回第三人称，实际 " + atNewDeadline);
        helper.succeed();
    }

    /** PVE 事件在 PVP 窗口外：立刻把挂着的 PVP 镜头退回第三人称（正向），此后不再发信号（负向配观测）。 */
    @GameTest(template = "item_concept")
    public static void pveOutsidePvpWindowExitsImmediately(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        logic.onCombatEvent(CombatEventType.PVP, T0);

        long outsideWindow = T0 + CombatCameraLogic.PVP_WINDOW_TICKS + 1; // 窗口外、4 秒冷却未满
        Optional<CameraGoal> pve = logic.onCombatEvent(CombatEventType.PVE, outsideWindow);
        helper.assertTrue(pve.isPresent() && pve.get() == CameraGoal.THIRD_PERSON,
                "窗口外的 PVE 事件应立刻回第三人称，实际 " + pve);

        Optional<CameraGoal> later = logic.onTick(outsideWindow + 1000);
        helper.assertTrue(later.isEmpty(),
                "PVE 退场后 tick 不应再发重复切换信号，实际 " + later);
        helper.assertTrue(!logic.isPvpActive(), "PVE 退场后 PVP 态应已清除");
        helper.succeed();
    }

    /** PVE 事件在 PVP 窗口内：不抢镜头（负向），PVP 照常走超时退出（正向）。 */
    @GameTest(template = "item_concept")
    public static void pveInsidePvpWindowKeepsFirstPerson(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        logic.onCombatEvent(CombatEventType.PVP, T0);

        Optional<CameraGoal> pve = logic.onCombatEvent(CombatEventType.PVE, T0 + 60);
        helper.assertTrue(pve.isEmpty(),
                "PVP 窗口内的 PVE 事件不应抢镜头，实际 " + pve);
        helper.assertTrue(logic.isPvpActive(), "窗口内 PVE 后 PVP 态应仍挂着");

        long deadline = T0 + CombatCameraLogic.PVP_WINDOW_TICKS + CombatCameraLogic.EXIT_DELAY_TICKS;
        Optional<CameraGoal> after = logic.onTick(deadline + 1);
        helper.assertTrue(after.isPresent() && after.get() == CameraGoal.THIRD_PERSON,
                "PVP 超时退出不应受窗口内 PVE 影响，实际 " + after);
        helper.succeed();
    }

    /** 纯 PVE（打怪/被怪打）永不主动切镜头（负向），配「之后 PVP 仍能正常切入」正向断言。 */
    @GameTest(template = "item_concept")
    public static void pveAloneNeverSwitchesCamera(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        Optional<CameraGoal> pve = logic.onCombatEvent(CombatEventType.PVE, T0);
        helper.assertTrue(pve.isEmpty(), "纯 PVE 事件不应发任何切换信号，实际 " + pve);
        Optional<CameraGoal> tick = logic.onTick(T0 + 10000);
        helper.assertTrue(tick.isEmpty(), "纯 PVE 后 tick 不应发任何切换信号，实际 " + tick);

        Optional<CameraGoal> pvp = logic.onCombatEvent(CombatEventType.PVP, T0 + 10001);
        helper.assertTrue(pvp.isPresent() && pvp.get() == CameraGoal.FIRST_PERSON,
                "纯 PVE 后 PVP 事件仍应正常切第一人称，实际 " + pvp);
        helper.succeed();
    }

    /** 换世界复位：PVP 态被 reset 清掉（负向不再退出），复位后新 PVP 照常切入（正向）。 */
    @GameTest(template = "item_concept")
    public static void resetClearsPvpState(GameTestHelper helper) {
        CombatCameraLogic logic = new CombatCameraLogic();
        logic.onCombatEvent(CombatEventType.PVP, T0);
        logic.reset();

        Optional<CameraGoal> tick = logic.onTick(T0 + 10000);
        helper.assertTrue(tick.isEmpty(), "reset 后不应再发退出信号，实际 " + tick);
        helper.assertTrue(!logic.isPvpActive(), "reset 后 PVP 态应已清除");

        Optional<CameraGoal> pvp = logic.onCombatEvent(CombatEventType.PVP, T0 + 10001);
        helper.assertTrue(pvp.isPresent() && pvp.get() == CameraGoal.FIRST_PERSON,
                "reset 后新 PVP 事件应照常切第一人称，实际 " + pvp);
        helper.succeed();
    }

    /** 配置解析：缺字段回退默认（正向）、越界 transition_ticks 收敛到上限（正向）、坏 json 整体回退默认。 */
    @GameTest(template = "item_concept")
    public static void configParseDefaultsAndClamp(GameTestHelper helper) {
        CombatCameraConfig empty = CombatCameraConfig.parse("{}");
        helper.assertTrue(empty.autoPvpFirstPerson == CombatCameraConfig.DEFAULT_AUTO_PVP_FIRST_PERSON,
                "空配置的开关应回退默认值 true");
        helper.assertTrue(empty.transitionTicks == CombatCameraConfig.DEFAULT_TRANSITION_TICKS,
                "空配置的过渡时长应回退默认值，实际 " + empty.transitionTicks);

        CombatCameraConfig custom = CombatCameraConfig.parse(
                "{ \"auto_pvp_first_person\": false, \"transition_ticks\": 999 }");
        helper.assertTrue(!custom.autoPvpFirstPerson, "配置的 false 开关应被尊重");
        helper.assertTrue(custom.transitionTicks == CombatCameraConfig.MAX_TRANSITION_TICKS,
                "越界 transition_ticks 应收敛到上限 " + CombatCameraConfig.MAX_TRANSITION_TICKS
                        + "，实际 " + custom.transitionTicks);

        CombatCameraConfig broken = CombatCameraConfig.parse("这不是 json");
        helper.assertTrue(broken.autoPvpFirstPerson == CombatCameraConfig.DEFAULT_AUTO_PVP_FIRST_PERSON
                        && broken.transitionTicks == CombatCameraConfig.DEFAULT_TRANSITION_TICKS,
                "坏 json 应整体回退默认值");
        helper.succeed();
    }
}
