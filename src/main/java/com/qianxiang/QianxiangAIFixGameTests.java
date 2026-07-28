package com.qianxiang;

import com.qianxiang.ai.AIClient;
import com.qianxiang.ai.AIConfig;
import com.qianxiang.ai.MaterialLibrary;
import com.qianxiang.ai.MaterialRecall;
import com.qianxiang.ai.PhaseAIRecipeService;
import com.qianxiang.phase.PhaseMaterialRegistry;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI 链路返工（WQ-64/65/66/67）GameTests。
 * <p>
 * 覆盖四件事的可观测最终状态：
 * <ul>
 *   <li>WQ-64：不认 {@code response_format} 的端点 400 时，去字段重发并记住降级
 *       （用本地 HTTP 桩看 wire 上实际发出去的请求体，不看内部判据）；</li>
 *   <li>WQ-65：顶层数组的两个方案都保留（修复前静默只剩第一个）、字段名大小写不敏感、
 *       寒暄花括号不污染解析——断言解析管线的最终方案列表；</li>
 *   <li>WQ-66：EF 动画库段/自由法术段/功能性指引按条件裁剪，few-shot 与【输出格式】在位；</li>
 *   <li>WQ-67：白名单空池有保底、confirm 当前材料并入候选、数据包注册材料置顶可见。</li>
 * </ul>
 * 复用 {@code qianxiang:item_concept} 空场地模板（同 {@link QianxiangCoreGameTests}）。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangAIFixGameTests {

    private QianxiangAIFixGameTests() {}

    // ============================ WQ-64：response_format 降级 ============================

    /**
     * 桩端点对带 {@code response_format} 的请求一律 400。期望的 wire 行为：
     * 第一次 chat 发两发请求（首发带字段被 400 → 去字段重发成功）；
     * 第二次 chat 只发一发且不再带字段（进程内记忆生效）。
     */
    @GameTest(template = "item_concept")
    public static void responseFormatDropsAfterUnsupported400(GameTestHelper helper) {
        AIClient.resetResponseFormatSupportForTest();
        try (StubOpenAiServer stub = new StubOpenAiServer()) {
            AIConfig cfg = AIConfig.parse(
                    "{\"provider\":\"openai\",\"base_url\":\"http://127.0.0.1:" + stub.port() + "\","
                            + "\"api_key\":\"\",\"model\":\"stub-model\",\"timeout_seconds\":10,"
                            + "\"config_version\":1}",
                    Path.of("build/tmp/qianxiang-ai-gametest-unused.json"));

            var first = AIClient.chat(cfg, "给我一把剑", "测试 prompt");
            helper.assertTrue(first.isPresent(),
                    "端点 400 拒绝 response_format 后应降级重发成功，实际返回空");
            helper.assertTrue(stub.requestBodies.size() == 2,
                    "第一次 chat 应发出 2 发请求（400 后去字段重发），实际 " + stub.requestBodies.size());
            helper.assertTrue(stub.requestBodies.get(0).contains("response_format"),
                    "首发请求应带 response_format 字段");
            helper.assertTrue(!stub.requestBodies.get(1).contains("response_format"),
                    "400 后的重发请求不应再带 response_format 字段");

            var second = AIClient.chat(cfg, "给我一把斧", "测试 prompt");
            helper.assertTrue(second.isPresent(), "第二次 chat 应直接成功，实际返回空");
            helper.assertTrue(stub.requestBodies.size() == 3,
                    "进程内已记住端点不支持结构化输出，第二次 chat 应只发 1 发请求，实际共 "
                            + stub.requestBodies.size());
            helper.assertTrue(!stub.requestBodies.get(2).contains("response_format"),
                    "第二次 chat 不应再携带 response_format 字段");
        } catch (IOException e) {
            helper.fail("本地桩端点启动失败：" + e);
            return;
        } finally {
            AIClient.resetResponseFormatSupportForTest();
        }
        helper.succeed();
    }

    // ============================ WQ-65：解析容错 ============================

    /**
     * 顶层数组必须保留全部方案（修复前 firstBalanced 先命中数组内第一个对象，
     * 静默只留第一个方案且无日志）；字段名大小写不敏感；寒暄花括号不污染结果。
     */
    @GameTest(template = "item_concept")
    public static void aiResponseParsingKeepsAllProposals(GameTestHelper helper) {
        // 顶层数组：两个方案都必须存活（工单点名：断言 proposals 长度 == 2）
        var fromArray = PhaseAIRecipeService.parseProposalsForTest(
                "[{\"materials\":[\"minecraft:iron_ingot\",\"minecraft:gold_ingot\"],\"summary\":\"甲\"},"
                        + "{\"materials\":[\"qianxiang:ember_iron\",\"minecraft:redstone\"],\"summary\":\"乙\"}]",
                "weapon");
        helper.assertTrue(fromArray.size() == 2,
                "顶层数组的两个方案都应保留，实际 " + fromArray.size());
        if (fromArray.size() == 2) {
            helper.assertTrue(fromArray.get(0).materialNames().contains("minecraft:iron_ingot"),
                    "第一个方案应含铁锭，实际 " + fromArray.get(0).materialNames());
            helper.assertTrue(fromArray.get(1).materialNames().contains("qianxiang:ember_iron"),
                    "第二个方案应含烬铁（证明没被静默丢掉），实际 " + fromArray.get(1).materialNames());
        }

        // 字段名大小写混合："Proposals"/"Materials"/"Summary"
        var mixedCase = PhaseAIRecipeService.parseProposalsForTest(
                "{\"Proposals\":[{\"Materials\":[\"minecraft:iron_ingot\",\"minecraft:coal\"],"
                        + "\"Summary\":\"大小写混搭\"}]}",
                "weapon");
        helper.assertTrue(mixedCase.size() == 1,
                "大小写混搭的字段名应能读出 proposals，实际方案数 " + mixedCase.size());
        if (!mixedCase.isEmpty()) {
            helper.assertTrue(mixedCase.get(0).materialNames().size() == 2,
                    "大小写混搭的 Materials 应读出 2 个材料，实际 " + mixedCase.get(0).materialNames());
        }

        // 前置寒暄带花括号：应跳过「{构思中}」取到真正含 proposals 的对象
        var chatty = PhaseAIRecipeService.parseProposalsForTest(
                "好的，让我想想 {构思中}\n"
                        + "{\"proposals\":[{\"materials\":[\"minecraft:iron_ingot\",\"minecraft:coal\"],"
                        + "\"summary\":\"ok\"}]}",
                "weapon");
        helper.assertTrue(chatty.size() == 1,
                "寒暄花括号不应污染解析，应读出 1 个方案，实际 " + chatty.size());
        helper.succeed();
    }

    // ============================ WQ-66：prompt 预算 ============================

    /**
     * 大段说明书按需插入：普通武器需求不背 EF 动画库（≈3.5K）与自由法术段；
     * 动作描述/ magic 需求才各插各的；confirm 砍掉【功能性需求指引】；
     * few-shot 与独立【输出格式】段必须在位。
     */
    @GameTest(template = "item_concept")
    public static void promptSectionsAreBudgeted(GameTestHelper helper) {
        String plain = PhaseAIRecipeService.buildPromptForTest(
                "一把锋利的剑", "weapon", "rare", List.of(), "recommend");
        helper.assertTrue(plain.contains("【候选材料】"), "候选材料段必须在位");
        helper.assertTrue(plain.contains("【输出格式】"), "独立【输出格式】段必须在位");
        helper.assertTrue(plain.contains("【示例】"), "few-shot 示例段必须在位");
        // 用段正文的特征串而不是段标题断言——规则条文里有「（见【动作定制】）」式的
        // 交叉引用，只查标题会被引用文字蒙混（断言要钉住的是整段说明书有没有进去）。
        helper.assertTrue(!plain.contains("【动作定制（Epic Fight 连击）】"),
                "无动作描述的普通武器需求不应插入动作定制段");
        helper.assertTrue(!plain.contains("本作法术不限制类型"),
                "非 magic 需求不应插入自由法术段");

        // 动画库段确实按条件插入（差的体量就是整个库，不是标题字符串有无的文字游戏）
        String action = PhaseAIRecipeService.buildPromptForTest(
                "一把能三段连斩的太刀", "weapon", "rare", List.of(), "recommend");
        helper.assertTrue(action.contains("【动作定制（Epic Fight 连击）】"),
                "描述了攻击动作时应插入动作定制段");
        helper.assertTrue(action.length() - plain.length() > 3000,
                "动作需求应额外带上动画库（≈3.5K 字符），实际差值 "
                        + (action.length() - plain.length()));

        String magic = PhaseAIRecipeService.buildPromptForTest(
                "追踪火球法杖", "magic", "rare", List.of(), "recommend");
        helper.assertTrue(magic.contains("本作法术不限制类型"), "magic 需求应插入自由法术段");
        helper.assertTrue(!magic.contains("【动作定制（Epic Fight 连击）】"),
                "无动作描述的 magic 需求不应插入动作定制段");

        String confirm = PhaseAIRecipeService.buildPromptForTest(
                "帮我看看这组材料", "weapon", "rare", List.of("minecraft:iron_ingot"), "confirm");
        helper.assertTrue(!confirm.contains("【功能性需求指引】"),
                "confirm 模式应砍掉【功能性需求指引】（评价任务用不上推荐向指引）");
        helper.assertTrue(confirm.contains("评价玩家已经放入"),
                "confirm 模式的任务声明（评价而非重新推荐）必须保留在最前");
        helper.succeed();
    }

    // ============================ WQ-67：召回三边缘 ============================

    /**
     * ① 白名单内全是无算子概念物品时召回不能全空（prompt 写着「只能从下列挑」）；
     * ② confirm 模式当前材料强制并入候选；③ 数据包注册材料（UGC）无条件置顶可见。
     */
    @GameTest(template = "item_concept")
    public static void materialRecallEdgeCases(GameTestHelper helper) {
        // ① 白名单空池保底
        var syntheticLib = List.of(
                new MaterialLibrary.MaterialEntry("minecraft:dirt", "泥土",
                        PhaseTier.COMMON, Set.of(), Set.of()),
                new MaterialLibrary.MaterialEntry("minecraft:sand", "沙子",
                        PhaseTier.COMMON, Set.of(), Set.of()),
                new MaterialLibrary.MaterialEntry("minecraft:gravel", "沙砾",
                        PhaseTier.COMMON, Set.of(), Set.of()));
        Set<String> noOperatorWhitelist = Set.of("minecraft:dirt", "minecraft:sand", "minecraft:gravel");
        var emptyPoolGroups = MaterialRecall.recall(syntheticLib, "随便给我来一把武器",
                "weapon", PhaseTier.RARE, noOperatorWhitelist);
        int fallbackTotal = emptyPoolGroups.stream().mapToInt(g -> g.entries().size()).sum();
        helper.assertTrue(fallbackTotal > 0,
                "白名单内全是无算子物品时召回应有保底（不能全空），实际 0 条");
        helper.assertTrue(emptyPoolGroups.stream().flatMap(g -> g.entries().stream())
                        .anyMatch(e -> e.registryName().equals("minecraft:dirt")),
                "保底材料应来自白名单内部（应含 minecraft:dirt）");
        helper.assertTrue(emptyPoolGroups.stream().flatMap(g -> g.entries().stream())
                        .allMatch(e -> noOperatorWhitelist.contains(e.registryName())),
                "保底材料不得越出玩家勾选的白名单");

        // ② confirm 当前材料并入候选
        var lib = MaterialLibrary.snapshot();
        var confirmGroups = MaterialRecall.recall(lib, "评价一下", "weapon", PhaseTier.RARE, null,
                List.of("minecraft:iron_ingot"));
        helper.assertTrue(!confirmGroups.isEmpty(), "confirm 召回不应为空");
        helper.assertTrue(confirmGroups.getFirst().entries().stream()
                        .anyMatch(e -> e.registryName().equals("minecraft:iron_ingot")),
                "confirm 模式下当前已放入材料应并入候选（置顶组），首组实际 "
                        + confirmGroups.getFirst().entries());

        // ③ 数据包注册材料（PhaseMaterialRegistry.all()）无条件置顶——
        //    minecraft:heart_of_the_sea 这类长 id 非 qianxiang 命名空间的 UGC 材料
        //    此前被「qianxiang: 优先 + 短名优先」的排序系统性压低
        Set<String> registered = new LinkedHashSet<>();
        PhaseMaterialRegistry.all().keySet().forEach(id -> registered.add(id.toString()));
        helper.assertTrue(!registered.isEmpty(), "测试环境应加载了 phase_materials 数据包材料");
        var groups = MaterialRecall.recall(lib, "我要一把剑", "weapon", PhaseTier.RARE, null);
        helper.assertTrue(!groups.isEmpty(), "召回不应为空");
        var pinned = groups.getFirst();
        helper.assertTrue(!pinned.entries().isEmpty(), "数据包注册材料置顶组不应为空");
        for (var e : pinned.entries()) {
            helper.assertTrue(registered.contains(e.registryName()),
                    "置顶组应全部是数据包注册材料，实际混入 " + e.registryName());
        }
        helper.assertTrue(pinned.entries().stream()
                        .anyMatch(e -> e.registryName().equals("minecraft:heart_of_the_sea")),
                "UGC 样例材料 minecraft:heart_of_the_sea 应被置顶可见，实际 " + pinned.entries());
        helper.succeed();
    }

    // ============================ 本地 OpenAI 兼容桩 ============================

    /**
     * 极简 HTTP 桩：对带 {@code response_format} 的请求回 400（模拟旧版 llama.cpp
     * server / 自建代理一类「OpenAI 兼容但不认结构化输出」的端点），否则回 200
     * 与合法 OpenAI 应答体。记录每个请求体供断言 wire 行为。
     */
    private static final class StubOpenAiServer implements AutoCloseable {
        final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        private final ServerSocket server;
        private final Thread thread;
        private volatile boolean running = true;

        StubOpenAiServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "qianxiang-ai-stub");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (running) {
                try {
                    handle(server.accept());
                } catch (IOException e) {
                    return; // server.close() 触发的退出
                }
            }
        }

        private void handle(Socket conn) {
            try (conn) {
                conn.setSoTimeout(10_000);
                InputStream in = conn.getInputStream();
                StringBuilder head = new StringBuilder();
                int[] window = new int[4];
                int b;
                while ((b = in.read()) != -1) {
                    head.append((char) b);
                    window[0] = window[1];
                    window[1] = window[2];
                    window[2] = window[3];
                    window[3] = b;
                    if (window[0] == '\r' && window[1] == '\n' && window[2] == '\r' && window[3] == '\n') {
                        break;
                    }
                }
                int contentLength = 0;
                for (String line : head.toString().split("\r\n")) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                String body = new String(in.readNBytes(contentLength), StandardCharsets.UTF_8);
                requestBodies.add(body);

                boolean reject = body.contains("response_format");
                String payload = reject
                        ? "{\"error\":{\"message\":\"unsupported parameter: response_format\"}}"
                        : "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"{\\\"proposals\\\":[]}\"}}]}";
                byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
                String responseHead = "HTTP/1.1 " + (reject ? "400 Bad Request" : "200 OK") + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + payloadBytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = conn.getOutputStream();
                out.write(responseHead.getBytes(StandardCharsets.UTF_8));
                out.write(payloadBytes);
                out.flush();
            } catch (IOException ignored) {
                // 客户端中断连接：桩无状态，直接丢弃
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            server.close();
        }
    }
}
