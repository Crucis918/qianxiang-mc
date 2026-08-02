package com.qianxiang;

import com.qianxiang.client.AiChatLog;
import com.qianxiang.client.MaterialGrid;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天面板与材料网格的纯逻辑测试（UI 渲染不进 GameTest，逻辑全抽静态）。
 * 注意：AiChatLog 在 client 包但纯数据逻辑、无客户端类依赖，服务端测试可安全驱动。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangChatUiGameTests {

    private QianxiangChatUiGameTests() {}

    /** 聊天记录：追加/上限截断（留最新）/清空。 */
    @GameTest(template = "item_concept")
    public static void chatLogTrimsAndClears(GameTestHelper helper) {
        AiChatLog log = new AiChatLog();
        for (int i = 1; i <= 9; i++) {
            log.addUser("需求 " + i);
        }
        helper.assertTrue(log.msgs().size() == AiChatLog.MAX,
                "9 条后应截到上限 " + AiChatLog.MAX + "，实际 " + log.msgs().size());
        helper.assertTrue(log.msgs().get(0).text().equals("需求 " + (9 - AiChatLog.MAX + 1)),
                "截断应留最新：首条应为「需求 " + (9 - AiChatLog.MAX + 1) + "」，实际 "
                        + log.msgs().get(0).text());
        helper.assertTrue(log.msgs().stream().allMatch(AiChatLog.Msg::user),
                "用户气泡标记应保留");

        log.addAi("我理解你想要：火剑", true);
        helper.assertTrue(log.msgs().get(log.msgs().size() - 1).fallback(),
                "兜底气泡应带 fallback 标记");
        log.clear();
        helper.assertTrue(log.isEmpty(), "清空后应为空");

        // 静态 trim 正反：不足上限不动
        List<Integer> shortList = new ArrayList<>(List.of(1, 2, 3));
        AiChatLog.trim(shortList, 6);
        helper.assertTrue(shortList.size() == 3, "不足上限不应截断");
        helper.succeed();
    }

    /** 网格命中计算：格心命中对应槽位、格外返回 -1、越界下标不算命中。 */
    @GameTest(template = "item_concept")
    public static void materialGridHitTest(GameTestHelper helper) {
        int cell = MaterialGrid.CELL; // 18
        // 锻造台 5 列 25 槽：原点在 (8,17)
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 5, 8.0, 17.0, 25) == 0,
                "首格左上角应命中槽 0");
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 5, 8 + 4 * cell + 9.0, 17 + 4 * cell + 9.0, 25) == 24,
                "右下角格心应命中槽 24");
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 5, 8 + 2 * cell + 3.0, 17 + cell + 3.0, 25) == 7,
                "第 2 行第 3 列应命中槽 7（2×5+... 1×5+2=7）");
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 5, 7.9, 17.0, 25) == -1,
                "原点左侧应不命中");
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 5, 8.0, 16.9, 25) == -1,
                "原点上方应不命中");
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 5, 8 + 5 * cell + 1.0, 17.0, 25) == -1,
                "第 5 列右侧（第 6 列）应不命中");
        // 炼金台 3 列 6 槽：第 3 行不存在 → -1
        helper.assertTrue(MaterialGrid.slotAt(8, 17, 3, 8.0, 17 + 2 * cell + 1.0, 6) == -1,
                "超出槽位总数的格子不应命中（炼金 6 槽第 3 行）");
        helper.succeed();
    }
}
