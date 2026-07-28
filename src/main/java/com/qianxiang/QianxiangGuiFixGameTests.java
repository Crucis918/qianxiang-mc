package com.qianxiang;

import com.qianxiang.ai.PhaseAIRecipeService;
import com.qianxiang.client.ClientForgeTableAI;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.AiPlaceMaterialsPayload;
import com.qianxiang.network.AiRequestPayload;
import com.qianxiang.network.AiResponsePayload;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * GUI 批修复（WQ-73/75/76/79/80）的 GameTests。
 * <p>
 * 只断言可观测最终状态：容器/背包物品栈、网络包编解码契约、客户端结果缓存
 * （即界面实际显示的数据源）。复用 {@code qianxiang:item_concept} 空场地模板。
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangGuiFixGameTests {

    private QianxiangGuiFixGameTests() {}

    // ============================ WQ-75：replace 语义 ============================

    /** 点整张方案卡（replace=true）：台上现有材料退回背包，新方案材料独占材料槽。 */
    @GameTest(template = "item_concept")
    public static void aiPlaceReplaceReturnsExistingMaterials(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        // 台上已有「方案 A」的两件材料（填充序槽 12/6）
        be.setItem(12, new ItemStack(Items.COAL));
        be.setItem(6, new ItemStack(Items.EMERALD));

        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var inv = player.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_INGOT));
        inv.setItem(1, new ItemStack(Items.DIAMOND));

        var result = com.qianxiang.network.AiPlaceMaterialsHandler.placeMaterials(player, be,
                ForgeTableMenu.SLOT_FILL_ORDER,
                List.of(Items.IRON_INGOT, Items.DIAMOND), true);

        helper.assertTrue(result.placedCount() == 2,
                "replace 放料应放入 2 件，实际 " + result.placedCount());
        helper.assertTrue(be.getItem(12).is(Items.IRON_INGOT) && be.getItem(6).is(Items.DIAMOND),
                "新方案材料应独占材料槽（槽 12 铁锭/槽 6 钻石），实际 "
                        + be.getItem(12) + " / " + be.getItem(6));
        // 负向断言（旧材料不在台上）必须配正向断言（旧材料回到背包），
        // 防止「旧材料被吞掉」也能蒙混过关。
        helper.assertTrue(countInContainer(be, Items.COAL) == 0 && countInContainer(be, Items.EMERALD) == 0,
                "replace 后台上不应残留旧方案材料");
        helper.assertTrue(countInInventory(player, Items.COAL) == 1
                        && countInInventory(player, Items.EMERALD) == 1,
                "旧方案材料应退回玩家背包（不丢不复制），实际 煤="
                        + countInInventory(player, Items.COAL) + " 绿宝石="
                        + countInInventory(player, Items.EMERALD));
        helper.succeed();
    }

    /** 点单个材料条目（replace=false）：叠加语义不变，台上现有材料保留。 */
    @GameTest(template = "item_concept")
    public static void aiPlaceWithoutReplaceKeepsExistingMaterials(GameTestHelper helper) {
        var be = new com.qianxiang.block.ForgeTableBlockEntity(
                net.minecraft.core.BlockPos.ZERO,
                QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        be.setItem(12, new ItemStack(Items.COAL));

        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var inv = player.getInventory();
        inv.clearContent();
        inv.setItem(0, new ItemStack(Items.IRON_INGOT));

        var result = com.qianxiang.network.AiPlaceMaterialsHandler.placeMaterials(player, be,
                ForgeTableMenu.SLOT_FILL_ORDER,
                List.of(Items.IRON_INGOT), false);

        helper.assertTrue(result.placedCount() == 1,
                "叠加放料应放入 1 件，实际 " + result.placedCount());
        helper.assertTrue(be.getItem(12).is(Items.COAL),
                "非 replace 放料不应动台上现有材料，实际 " + be.getItem(12));
        helper.assertTrue(be.getItem(6).is(Items.IRON_INGOT),
                "新材料应进下一个空槽（槽 6），实际 " + be.getItem(6));
        helper.assertTrue(countInInventory(player, Items.IRON_INGOT) == 0,
                "已放材料应从背包扣除");
        helper.succeed();
    }

    // ============================ WQ-75/76：网络包编解码契约 ============================

    /** seq（WQ-76）与 replace（WQ-75）字段随包上线并完整 roundtrip；兼容构造取默认值。 */
    @GameTest(template = "item_concept")
    public static void aiPayloadCodecsCarrySeqAndReplace(GameTestHelper helper) {
        var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());

        AiRequestPayload.STREAM_CODEC.encode(buf,
                new AiRequestPayload(7, "一把剑", "weapon", "rare",
                        List.of("minecraft:iron_ingot"), "recommend", List.of()));
        AiRequestPayload req = AiRequestPayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(req.seq() == 7, "AiRequestPayload.seq 应 roundtrip，实际 " + req.seq());
        helper.assertTrue("一把剑".equals(req.request()) && "weapon".equals(req.targetType()),
                "AiRequestPayload 其余字段不应被新增字段挤错位");

        AiResponsePayload.STREAM_CODEC.encode(buf,
                new AiResponsePayload(9, List.of(), "", List.of()));
        AiResponsePayload resp = AiResponsePayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(resp.seq() == 9, "AiResponsePayload.seq 应 roundtrip，实际 " + resp.seq());

        AiPlaceMaterialsPayload.STREAM_CODEC.encode(buf,
                new AiPlaceMaterialsPayload(List.of("minecraft:iron_ingot"), true));
        AiPlaceMaterialsPayload place = AiPlaceMaterialsPayload.STREAM_CODEC.decode(buf);
        helper.assertTrue(place.replace(), "AiPlaceMaterialsPayload.replace=true 应 roundtrip");
        helper.assertTrue(place.materialNames().size() == 1
                        && "minecraft:iron_ingot".equals(place.materialNames().get(0)),
                "AiPlaceMaterialsPayload 材料列表不应被新增字段挤错位");
        // 兼容旧构造：replace 默认 false（叠加）、seq 默认 0
        helper.assertTrue(!new AiPlaceMaterialsPayload(List.of("minecraft:coal")).replace(),
                "旧单参构造的 replace 应为 false（叠加语义不变）");
        helper.assertTrue(new AiRequestPayload("x", "weapon", "rare", List.of(), "recommend").seq() == 0,
                "旧五参构造的 seq 应为 0");

        buf.release();
        helper.succeed();
    }

    // ============================ WQ-76：客户端丢弃落后响应 ============================

    /** 序号更小的响应（被限流空包插队的真实响应）不得覆盖已接受的更新结果。 */
    @GameTest(template = "item_concept")
    public static void clientDiscardsStaleAiResponses(GameTestHelper helper) {
        ClientForgeTableAI.resetForWorldChange();
        var proposal = new PhaseAIRecipeService.RecipeProposal(
                List.of("minecraft:iron_ingot"), 1.5, "第一版", "", "");

        ClientForgeTableAI.receive(new AiResponsePayload(2, List.of(proposal), "", List.of()));
        helper.assertTrue(ClientForgeTableAI.getLastResult() != null
                        && ClientForgeTableAI.getLastResult().proposals().size() == 1
                        && "第一版".equals(ClientForgeTableAI.getLastResult().proposals().get(0).summary()),
                "seq=2 的响应应被接受并展示");

        // 负向断言（旧方案不被覆盖）+ 正向断言（当前展示的仍是 seq=2 的结果）
        ClientForgeTableAI.receive(new AiResponsePayload(1, List.of(), "", List.of()));
        helper.assertTrue(ClientForgeTableAI.getLastResult() != null
                        && ClientForgeTableAI.getLastResult().proposals().size() == 1
                        && "第一版".equals(ClientForgeTableAI.getLastResult().proposals().get(0).summary()),
                "seq=1 的落后响应应被丢弃，不得覆盖 seq=2 的结果");

        ClientForgeTableAI.receive(new AiResponsePayload(3, List.of(), "", List.of()));
        helper.assertTrue(ClientForgeTableAI.getLastResult() != null
                        && ClientForgeTableAI.getLastResult().proposals().isEmpty(),
                "seq=3 的更新响应应正常生效（空结果覆盖旧结果）");

        ClientForgeTableAI.resetForWorldChange();
        helper.succeed();
    }

    // ============================ 工具 ============================

    private static int countInInventory(net.minecraft.world.entity.player.Player player,
                                        net.minecraft.world.item.Item item) {
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) total += s.getCount();
        }
        return total;
    }

    private static int countInContainer(net.minecraft.world.Container container,
                                        net.minecraft.world.item.Item item) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack s = container.getItem(i);
            if (!s.isEmpty() && s.is(item)) total += s.getCount();
        }
        return total;
    }
}
