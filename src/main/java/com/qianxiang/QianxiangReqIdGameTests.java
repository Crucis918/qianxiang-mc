package com.qianxiang;

import com.qianxiang.ai.AIGateway;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiPlaceMaterialsHandler;
import com.qianxiang.network.AiPlaceMaterialsPayload;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * WQ-71 飞轮地基：reqId「请求 → 采纳」全链路 + 日志字段齐全 + adopt 只记实放材料。
 * <p>
 * 链路模拟（生产函数直调，不复制逻辑）：AI 线程侧取真 reqId 写 request 行 →
 * 主线程侧玩家点卡放料（wanted 2 件只放进 1 件）→ adopt 行 req_id 必须与请求一致、
 * 材料只含实际放入的（此前主线程读 ThreadLocal 现造假 id、全服共用一个；
 * 且放进 1 件也记整个请求名单，over-report）。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangReqIdGameTests {

    private QianxiangReqIdGameTests() {}

    @GameTest(template = "item_concept")
    public static void reqIdFlowsFromRequestToAdoption(GameTestHelper helper) throws Exception {
        // —— 「AI 线程」侧：真 reqId（本线程 ThreadLocal 赋值，证明传递不再靠跨线程读） ——
        String reqId = AIGateway.currentRequestId();
        String fakePlayerUuid = java.util.UUID.randomUUID().toString();
        AIGateway.logRequest(reqId, "会喷火的剑", fakePlayerUuid, 2,
                List.of("ghost_blade"), "AI 无响应（离线/超时/熔断）");

        // —— 「主线程」侧：点卡放料（wanted 铁锭+钻石，背包只有铁锭 → 只放进 1 件） ——
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);
        player.getInventory().clearContent();
        player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT));
        var container = new net.minecraft.world.SimpleContainer(ForgeTableMenu.MATERIAL_SLOTS);
        var payload = new AiPlaceMaterialsPayload(
                List.of("minecraft:iron_ingot", "minecraft:diamond"), true, reqId);
        var result = AiPlaceMaterialsHandler.placeMaterials(player, container,
                ForgeTableMenu.SLOT_FILL_ORDER, List.of(Items.IRON_INGOT, Items.DIAMOND), true);
        helper.assertTrue(result.placedCount() == 1,
                "只应放进铁锭 1 件，实际 " + result.placedCount());
        AiPlaceMaterialsHandler.logAdoption(player, payload, result); // 生产 adopt 调用

        AIGateway.flushLogForTest(); // jsonl 落盘已改异步（WQ-71 子项①），断言前排空

        // —— 断言：request / adopt 两行 req_id 一致，字段齐全 ——
        List<String> lines = Files.readAllLines(Path.of("logs/qianxiang-ai.jsonl"));
        String requestLine = lastLineContaining(lines, reqId, "\"event\":\"request\"");
        helper.assertTrue(!requestLine.isEmpty(), "应存在 req_id=" + reqId + " 的 request 行");
        helper.assertTrue(requestLine.contains("\"player_uuid\":\"" + fakePlayerUuid + "\""),
                "request 行应带 player_uuid，实际 " + requestLine);
        helper.assertTrue(requestLine.contains("\"proposal_count\":2"),
                "request 行应带 proposal_count=2，实际 " + requestLine);
        helper.assertTrue(requestLine.contains("ghost_blade"),
                "request 行应带 dropped_materials（ghost_blade），实际 " + requestLine);
        helper.assertTrue(requestLine.contains("AI 无响应"),
                "request 行应带 fallback_reason，实际 " + requestLine);

        String adoptLine = lastLineContaining(lines, reqId, "\"event\":\"adopt\"");
        helper.assertTrue(!adoptLine.isEmpty(),
                "应存在与请求同 req_id 的 adopt 行（链路对上）");
        helper.assertTrue(adoptLine.contains("\"req_id\":\"" + reqId + "\""),
                "adopt 行 req_id 应与请求行一致，实际 " + adoptLine);
        helper.assertTrue(adoptLine.contains("minecraft:iron_ingot"),
                "adopt 应记实际放入的铁锭，实际 " + adoptLine);
        helper.assertTrue(!adoptLine.contains("minecraft:diamond"),
                "adopt 不得记未放入的钻石（over-report 修复），实际 " + adoptLine);
        helper.succeed();
    }

    private static String lastLineContaining(List<String> lines, String a, String b) {
        String found = "";
        for (String line : lines) {
            if (line.contains(a) && line.contains(b)) found = line;
        }
        return found;
    }
}
