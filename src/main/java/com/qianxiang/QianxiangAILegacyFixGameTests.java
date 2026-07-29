package com.qianxiang;

import com.qianxiang.ai.AIClient;
import com.qianxiang.ai.AIConfig;
import com.qianxiang.ai.AIGateway;
import com.qianxiang.ai.MaterialLibrary;
import com.qianxiang.ai.MaterialRecall;
import com.qianxiang.phase.ComposedAttributes;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.phase.PhaseFunction;
import com.qianxiang.phase.PhaseTier;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * AI 尾账三项 GameTests（WQ-67④ / AIGateway 低危两项 / grantedEffects 通道补矩阵）。
 * <ul>
 *   <li>WQ-67④：召回先剥否定片段——「不要火的剑」的核心效果组不含 IGNITE 材料，
 *       基底组仍在场；肯定句「我要火的剑」对照组必须含 IGNITE（证明剔除由否定驱动）。</li>
 *   <li>AIGateway ①：BREAKER_SKIPS 在「新窗口开启 / 窗口结束探测放行 / 成功请求关闭熔断」
 *       三处归零（全程只走拦截与记账路径，不发 HTTP、不读真实端点配置）。</li>
 *   <li>AIGateway ②：catch 兜底直连的结果计入熔断——用本地桩端点（一律 500）驱动
 *       与兜底分支同一个落账方法，连续 3 次端点失败开熔断、中途成功清零。</li>
 *   <li>grantedEffects 矩阵：25 算子矩阵（{@link QianxiangFunctionMatrixGameTests}）只覆盖
 *       COMPOSED_ATTRIBUTES 的固定字段通道，这里补自由效果通道——效果 tag 14 行、
 *       杂项概念自带效果、数据包显式 effects、同效果取大合并、纯基底负对照，
 *       全部从产物组件读回断言。</li>
 * </ul>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangAILegacyFixGameTests {

    private QianxiangAILegacyFixGameTests() {}

    // ============================ WQ-67④：召回否定语义 ============================

    /**
     * 「不要火的剑」：核心效果材料组（第一组效果推荐）不得含 IGNITE 材料——
     * 修复前 wantedFunctions 裸 contains 命中「火」，把玩家明确拒绝的火材料顶进第一组。
     * 正向断言：「剑」的基底材料组在场且都是武器骨架料；
     * 对照：肯定句「我要火的剑」核心效果组必须含 IGNITE（证明剔除确实由否定驱动）。
     */
    @GameTest(template = "item_concept")
    public static void recallExcludesNegatedFunctions(GameTestHelper helper) {
        var lib = MaterialLibrary.snapshot();

        var groups = MaterialRecall.recall(lib, "不要火的剑", "weapon", PhaseTier.RARE, null);
        var effectGroup = groups.stream()
                .filter(g -> g.title().contains("核心效果材料"))
                .findFirst();
        helper.assertTrue(effectGroup.isPresent() && !effectGroup.get().entries().isEmpty(),
                "「不要火的剑」剥掉否定后仍有锋利/力量类需求，核心效果组不应为空，实际组 "
                        + groups.stream().map(MaterialRecall.Group::title).toList());
        for (var e : effectGroup.get().entries()) {
            helper.assertTrue(!e.functions().contains(PhaseFunction.IGNITE),
                    "被否定的 IGNITE 材料不应进核心效果组：" + e.registryName());
        }

        var baseGroup = groups.stream()
                .filter(g -> g.title().contains("基底材料"))
                .findFirst();
        helper.assertTrue(baseGroup.isPresent() && !baseGroup.get().entries().isEmpty(),
                "「剑」的基底材料组应在场");
        for (var e : baseGroup.get().entries()) {
            helper.assertTrue(e.functions().contains(PhaseFunction.BASE_METAL)
                            || e.functions().contains(PhaseFunction.BASE_BONE)
                            || e.functions().contains(PhaseFunction.BASE_WOOD),
                    "基底组材料应带武器骨架算子：" + e.registryName());
        }

        // 对照：同一句话去掉否定词，IGNITE 材料必须回到核心效果组
        var fireGroups = MaterialRecall.recall(lib, "我要火的剑", "weapon", PhaseTier.RARE, null);
        var fireEffect = fireGroups.stream()
                .filter(g -> g.title().contains("核心效果材料"))
                .findFirst();
        helper.assertTrue(fireEffect.isPresent() && fireEffect.get().entries().stream()
                        .anyMatch(e -> e.functions().contains(PhaseFunction.IGNITE)),
                "肯定句「我要火的剑」核心效果组应含 IGNITE 材料（剔除是由否定驱动的，不是全局屏蔽）");
        helper.succeed();
    }

    // ============================ AIGateway ①：BREAKER_SKIPS 归零 ============================

    /**
     * 拦截计数的三处归零：新开窗口从 0 起算、窗口结束探测放行时归零、成功请求关闭熔断时归零。
     * 拦截路径不发 HTTP（熔断打开期间 chat 直接返回 empty），与真实端点配置无关。
     */
    @GameTest(template = "item_concept")
    public static void breakerSkipsResetOnWindowEndAndSuccess(GameTestHelper helper) {
        AIGateway.resetBreakerForTest();
        try {
            for (int i = 0; i < 3; i++) {
                AIGateway.recordOutcomeForTest(false, AIGateway.FailureKind.CONNECT);
            }
            helper.assertTrue(AIGateway.breakerOpenForTest(), "连续 3 次连接失败应开熔断");
            helper.assertTrue(AIGateway.breakerSkipsForTest() == 0,
                    "新开的熔断窗口拦截计数应从 0 起算，实际 " + AIGateway.breakerSkipsForTest());

            // 窗口内两次请求被直接拦截（不发起 HTTP）
            AIGateway.chat("breaker-skips-拦截-1", "p");
            AIGateway.chat("breaker-skips-拦截-2", "p");
            helper.assertTrue(AIGateway.breakerSkipsForTest() == 2,
                    "窗口内两次拦截应计数 2，实际 " + AIGateway.breakerSkipsForTest());

            // 成功请求：熔断关闭 + 拦截计数归零
            AIGateway.recordOutcomeForTest(true, AIGateway.FailureKind.NONE);
            helper.assertTrue(!AIGateway.breakerOpenForTest(), "成功后熔断应关闭");
            helper.assertTrue(AIGateway.breakerSkipsForTest() == 0,
                    "成功请求后拦截计数应归零，实际 " + AIGateway.breakerSkipsForTest());

            // 窗口结束：探测放行时拦截计数归零
            for (int i = 0; i < 3; i++) {
                AIGateway.recordOutcomeForTest(false, AIGateway.FailureKind.CONNECT);
            }
            AIGateway.chat("breaker-skips-拦截-3", "p");
            helper.assertTrue(AIGateway.breakerSkipsForTest() == 1,
                    "第二个窗口内一次拦截应计数 1，实际 " + AIGateway.breakerSkipsForTest());
            AIGateway.expireBreakerWindowForTest();
            helper.assertTrue(AIGateway.admitProbeForTest(), "窗口已到，下一请求应作为探测放行");
            helper.assertTrue(AIGateway.breakerSkipsForTest() == 0,
                    "窗口结束探测放行时拦截计数应归零，实际 " + AIGateway.breakerSkipsForTest());
        } finally {
            AIGateway.resetBreakerForTest();
        }
        helper.succeed();
    }

    // ============================ AIGateway ②：兜底直连计入熔断 ============================

    /**
     * catch 兜底直连不再绕过熔断：与兜底分支同一个落账方法
     * （{@link AIGateway#accountFallbackOutcomeForTest}），用本地桩端点（一律 500）
     * 驱动真实 AIClient 失败分类——连续 3 次端点失败开熔断；中途一次成功清零重新计。
     */
    @GameTest(template = "item_concept")
    public static void fallbackDirectChatFeedsBreaker(GameTestHelper helper) {
        AIGateway.resetBreakerForTest();
        try (FailingEndpoint endpoint = new FailingEndpoint()) {
            AIConfig cfg = AIConfig.parse(
                    "{\"provider\":\"openai\",\"base_url\":\"http://127.0.0.1:" + endpoint.port() + "\","
                            + "\"api_key\":\"\",\"model\":\"stub-model\",\"timeout_seconds\":5,"
                            + "\"config_version\":1}",
                    Path.of("build/tmp/qianxiang-ai-legacy-gametest-unused.json"));

            fallbackOutcome(cfg); // ENDPOINT 失败 ×2
            fallbackOutcome(cfg);
            // 兜底拿到内容：计数清零（与正常路径同规则）
            AIGateway.accountFallbackOutcomeForTest(Optional.of("ok"));
            fallbackOutcome(cfg);
            fallbackOutcome(cfg);
            helper.assertTrue(!AIGateway.breakerOpenForTest(),
                    "成功清零后仅连续 2 次兜底失败，不应开熔断");
            fallbackOutcome(cfg);
            helper.assertTrue(AIGateway.breakerOpenForTest(),
                    "兜底直连连续 3 次端点失败应开熔断（修复前兜底绕过熔断、永不回账）");
        } catch (IOException e) {
            helper.fail("本地桩端点启动失败：" + e);
            return;
        } finally {
            AIGateway.resetBreakerForTest();
        }
        helper.succeed();
    }

    /** 对本地桩发起一次真实 AIClient 调用（500 → ENDPOINT 类失败），再走兜底落账。 */
    private static void fallbackOutcome(AIConfig cfg) {
        Optional<String> resp = AIClient.chat(cfg, "x", "y");
        AIGateway.accountFallbackOutcomeForTest(resp);
    }

    // ============================ grantedEffects 通道矩阵 ============================

    /**
     * 矩阵行：代表材料 → 产物 grantedEffects 里应出现的自由效果。
     * exactLevel 非空时额外断言精确等级（数据包显式等级 / 杂项概念固定等级）。
     */
    private record EffectRow(String label, ItemStack material, String effectPath, Integer exactLevel) {}

    private static List<EffectRow> effectMatrix() {
        List<EffectRow> rows = new ArrayList<>();
        // —— 效果 tag 通道（qianxiang:materials/effect/*，14 个 tag 各一行）——
        rows.add(new EffectRow("tag:wither", new ItemStack(Items.WITHER_ROSE), "wither", null));
        rows.add(new EffectRow("tag:blindness", new ItemStack(Items.INK_SAC), "blindness", null));
        rows.add(new EffectRow("tag:absorption", new ItemStack(Items.GOLDEN_APPLE), "absorption", null));
        rows.add(new EffectRow("tag:health_boost", new ItemStack(Items.ENCHANTED_GOLDEN_APPLE),
                "health_boost", null));
        rows.add(new EffectRow("tag:luck", new ItemStack(Items.TOTEM_OF_UNDYING), "luck", null));
        rows.add(new EffectRow("tag:night_vision", new ItemStack(Items.GOLDEN_CARROT),
                "night_vision", null));
        rows.add(new EffectRow("tag:regeneration", new ItemStack(Items.HONEY_BOTTLE),
                "regeneration", null));
        rows.add(new EffectRow("tag:slow_falling", new ItemStack(Items.PHANTOM_MEMBRANE),
                "slow_falling", null));
        rows.add(new EffectRow("tag:water_breathing", new ItemStack(Items.TURTLE_HELMET),
                "water_breathing", null));
        rows.add(new EffectRow("tag:weakness", new ItemStack(Items.FERMENTED_SPIDER_EYE),
                "weakness", null));
        rows.add(new EffectRow("tag:wind_charged", new ItemStack(Items.WIND_CHARGE),
                "wind_charged", null));
        rows.add(new EffectRow("tag:glowing", new ItemStack(Items.GLOW_BERRIES), "glowing", null));
        rows.add(new EffectRow("tag:dolphins_grace", new ItemStack(Items.HEART_OF_THE_SEA),
                "dolphins_grace", null));
        rows.add(new EffectRow("tag:conduit_power", new ItemStack(Items.NAUTILUS_SHELL),
                "conduit_power", null));
        // —— 杂项概念自带效果（「颅」凋零 ×1 / 「翼」缓降 ×2）——
        rows.add(new EffectRow("misc:颅", new ItemStack(Items.WITHER_SKELETON_SKULL), "wither", 1));
        rows.add(new EffectRow("misc:翼", new ItemStack(Items.ELYTRA), "slow_falling", 2));
        // —— 数据包显式 effects（phase_materials/*.json）——
        rows.add(new EffectRow("datapack:nether_star", new ItemStack(Items.NETHER_STAR),
                "regeneration", 3));
        rows.add(new EffectRow("datapack:warden_core", new ItemStack(QianxiangItems.WARDEN_CORE.get()),
                "regeneration", 2));
        // —— 同一物品上 tag + 数据包显式合并（海洋之心：declared conduit_power 2）——
        rows.add(new EffectRow("datapack:heart_of_the_sea", new ItemStack(Items.HEART_OF_THE_SEA),
                "conduit_power", 2));
        return rows;
    }

    /**
     * 自由效果落地矩阵：每行材料配铁锭基底走完整 {@link ForgeComposer#compose}，
     * 从产物 COMPOSED_ATTRIBUTES 组件的 grantedEffects 读回断言——
     * 缺失项一次性全部报告。另钉两条通道语义：同效果取大（海洋之心 2 vs 鹦鹉螺 1 → 2）、
     * 纯基底对照组不得产生自由效果。
     */
    @GameTest(template = "item_concept")
    public static void grantedEffectsLandOnProduct(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        for (EffectRow row : effectMatrix()) {
            ComposedAttributes attr = composeAttr(new ItemStack(Items.IRON_INGOT), row.material());
            ResourceLocation id = ResourceLocation.withDefaultNamespace(row.effectPath());
            if (attr == null) {
                missing.add(row.label() + "(组合无效，产物都没出)");
                continue;
            }
            Integer level = attr.grantedEffects().get(id);
            if (level == null) {
                missing.add(row.label() + "(grantedEffects 缺 " + id + "，实际 " + attr.grantedEffects().keySet() + ")");
            } else if (row.exactLevel() != null && level != row.exactLevel()) {
                missing.add(row.label() + "(" + id + " 等级应为 " + row.exactLevel() + "，实际 " + level + ")");
            }
        }
        helper.assertTrue(missing.isEmpty(),
                "grantedEffects 矩阵缺失 " + missing.size() + "/" + effectMatrix().size()
                        + "：" + String.join("；", missing));

        // 同效果取大：海洋之心（数据包显式 conduit_power 2）+ 鹦鹉螺壳（tag COMMON → 1）→ 2
        ComposedAttributes merged = composeAttr(new ItemStack(Items.HEART_OF_THE_SEA),
                new ItemStack(Items.NAUTILUS_SHELL));
        helper.assertTrue(merged != null, "海洋之心+鹦鹉螺壳应能出产物");
        ResourceLocation conduit = ResourceLocation.withDefaultNamespace("conduit_power");
        int mergedLevel = merged.grantedEffects().getOrDefault(conduit, -1);
        helper.assertTrue(mergedLevel == 2,
                "conduit_power 应同效果取大（2 vs 1 → 2），实际 " + mergedLevel);

        // 负向对照：纯铁锭（无效果 tag/概念效果）不得产生任何自由效果
        ComposedAttributes plain = composeAttr(new ItemStack(Items.IRON_INGOT), null);
        helper.assertTrue(plain != null, "铁锭应能单独出产物");
        helper.assertTrue(plain.grantedEffects().isEmpty(),
                "纯基底材料不应产生自由效果，实际 " + plain.grantedEffects().keySet());
        helper.succeed();
    }

    /** 25 格材料槽：主材料放 12 号位、可选搭档放 6 号位，组合并读产物组件（无效返回 null）。 */
    private static ComposedAttributes composeAttr(ItemStack main, ItemStack partner) {
        List<ItemStack> mats = new ArrayList<>(25);
        for (int i = 0; i < 25; i++) mats.add(ItemStack.EMPTY);
        mats.set(12, main.copy());
        if (partner != null) mats.set(6, partner.copy());
        ForgeComposer.Composition comp = ForgeComposer.compose(mats);
        if (!comp.valid()) return null;
        return comp.result().get(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get());
    }

    // ============================ 本地故障端点桩 ============================

    /**
     * 极简 HTTP 桩：完整读完请求后一律回 500（模拟持续故障端点）。
     * 必须读完请求体再回包——提前关连接会让客户端拿到连接类异常，
     * 失败分类就不是本用例要的 ENDPOINT 了。
     */
    private static final class FailingEndpoint implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile boolean running = true;

        FailingEndpoint() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "qianxiang-ai-legacy-stub");
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
                in.readNBytes(contentLength);

                byte[] payload = "{\"error\":{\"message\":\"stub endpoint down\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                String responseHead = "HTTP/1.1 500 Internal Server Error\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + payload.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = conn.getOutputStream();
                out.write(responseHead.getBytes(StandardCharsets.UTF_8));
                out.write(payload);
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
