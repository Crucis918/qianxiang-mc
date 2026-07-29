package com.qianxiang;

import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.block.RitualLogic;
import com.qianxiang.block.RitualState;
import com.qianxiang.network.MissingMaterialsPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 体验批修复（轮盘输入屏蔽 / 缺料中文化 / 战吼 HUD 图标 / 仪式中右键提示）的 GameTests。
 * <p>
 * 只断言可观测最终状态：网络包编解码契约、仪式中右键事件被拒 + 进度百分比。
 * 轮盘攻击屏蔽（{@code SpellWheelOverlay#onInteractionKey}）与战吼图标渲染
 * 是纯客户端输入/渲染路径，GameTest 覆盖不到，留待实机冒烟（验收点见提交说明）。
 * </p>
 */
@GameTestHolder(Qianxiang.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QianxiangUxFixGameTests {

    private QianxiangUxFixGameTests() {}

    // ============================ 缺料中文化：S2C payload 契约 ============================

    /** 缺料 payload 携带物品 id 列表并完整 roundtrip（客户端据此用本地语言渲染 hoverName）。 */
    @GameTest(template = "item_concept")
    public static void missingMaterialsPayloadRoundTripsItemIds(GameTestHelper helper) {
        var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());

        List<String> ids = List.of("minecraft:iron_ingot", "minecraft:diamond", "qianxiang:ember_crystal");
        MissingMaterialsPayload.STREAM_CODEC.encode(buf, new MissingMaterialsPayload(ids));
        MissingMaterialsPayload decoded = MissingMaterialsPayload.STREAM_CODEC.decode(buf);

        // 正向：三个 id 原序 roundtrip
        helper.assertTrue(decoded.itemIds().equals(ids),
                "缺料 id 列表应原序 roundtrip，实际 " + decoded.itemIds());
        // 反向：字段不得被挤错位（第一个 id 不应变成别的）
        helper.assertTrue(!"minecraft:diamond".equals(decoded.itemIds().get(0)),
                "首条 id 应为 iron_ingot 而非 diamond，字段疑似错位");

        // 空名单也是合法输入（全部放入时不发包，但 codec 层面不能崩/不能解出幽灵条目）
        MissingMaterialsPayload.STREAM_CODEC.encode(buf, new MissingMaterialsPayload(List.of()));
        helper.assertTrue(MissingMaterialsPayload.STREAM_CODEC.decode(buf).itemIds().isEmpty(),
                "空缺料名单应 roundtrip 为空列表");

        buf.release();
        helper.succeed();
    }

    // ============================ 仪式中右键：拦截 + 进度提示 ============================

    /**
     * 仪式进行中右键台子：{@link RitualLogic#onRightClickBlock} 取消事件（开 GUI/投料/取回
     * 分支端到端不可达），进度百分比按 FLYING/FORMING 折算；NONE/DONE 明确放行。
     */
    @GameTest(template = "item_concept")
    public static void ritualRightClickRejectedWithProgress(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(pos, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        if (!(level.getBlockEntity(pos) instanceof ForgeTableBlockEntity be)) {
            helper.fail("锻造台方块实体应存在");
            return;
        }
        var player = QianxiangCoreGameTests.mockServerPlayer(helper);

        // FLYING 15t（共 80t）：应拦截，进度 = 15*100/80 = 18
        be.setRitualState(RitualState.FLYING);
        be.setRitualProgress(15);
        helper.assertTrue(RitualLogic.progressPercent(be) == 18,
                "FLYING 15/80t 进度应为 18%，实际 " + RitualLogic.progressPercent(be));
        helper.assertTrue(postRightClick(player, pos).isCanceled(),
                "FLYING 中右键应被拦截（不再开 GUI/投料/取回）");
        helper.assertTrue(player.containerMenu == player.inventoryMenu,
                "FLYING 中右键后不得打开任何容器界面");

        // FORMING 25t：进度 = (30+25)*100/80 = 68
        be.setRitualState(RitualState.FORMING);
        be.setRitualProgress(25);
        helper.assertTrue(RitualLogic.progressPercent(be) == 68,
                "FORMING 25/50t 进度应为 68%，实际 " + RitualLogic.progressPercent(be));
        helper.assertTrue(postRightClick(player, pos).isCanceled(),
                "FORMING 中右键应被拦截");

        // 反向一：DONE 放行（空手拾取产物是仪式收尾唯一通路，拦截了就永远拿不到）
        be.setRitualState(RitualState.DONE);
        be.setRitualProgress(0);
        helper.assertTrue(RitualLogic.progressPercent(be) == -1,
                "DONE 无进行中进度，应为 -1");
        helper.assertTrue(!postRightClick(player, pos).isCanceled(),
                "DONE 右键不得拦截（拾取通路）");

        // 反向二：NONE 放行（无仪式时右键正常开 GUI 的路径不受影响）
        be.setRitualState(RitualState.NONE);
        helper.assertTrue(RitualLogic.progressPercent(be) == -1,
                "NONE 无进行中进度，应为 -1");
        helper.assertTrue(!postRightClick(player, pos).isCanceled(),
                "NONE 右键不得拦截（正常开 GUI）");

        level.removeBlock(pos, false);
        helper.succeed();
    }

    /** 在游戏事件总线上真实投递一次右键事件（走 {@link RitualLogic#onRightClickBlock} 订阅器）。 */
    private static PlayerInteractEvent.RightClickBlock postRightClick(
            net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        NeoForge.EVENT_BUS.post(event);
        return event;
    }
}
