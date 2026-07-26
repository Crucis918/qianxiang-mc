package com.qianxiang.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangBlocks;
import com.qianxiang.QianxiangItems;
import com.qianxiang.QianxiangMaterials;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * /qianxiang kit 测试包命令。
 * <p>
 * 一键给执行玩家塞一套测试物（锻造台 + 全材料各 4 + 三把武器各 1），
 * 省去测试时手动 /give 一堆材料、放台子的麻烦。
 * 直接塞进背包，背包满了自动掉脚边。
 * <p>
 * 自注册：靠 {@link EventBusSubscriber} 挂 GAME 总线，<b>不修改任何现有文件</b>。
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangKitCommand {

    /** 每种材料发放数量。 */
    private static final int MATERIAL_COUNT = 4;

    private QianxiangKitCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("qianxiang")
                        .then(Commands.literal("kit")
                                // 作弊性测试命令：无限发放全套装备与 Boss 刷怪蛋，
                                // 多人服必须限 OP（单机存档主人天然有 2 级权限，不受影响）。
                                .requires(src -> src.hasPermission(2))
                                .executes(QianxiangKitCommand::handleKit)));
        Qianxiang.LOGGER.info("[Qianxiang] 测试包命令已注册：/qianxiang kit");
    }

    /** /qianxiang kit 的执行体。返回发放物品种数作为命令结果。 */
    private static int handleKit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();

        List<ItemStack> kits = buildKit();

        for (ItemStack stack : kits) {
            // 先塞背包；满了（add 返回 false）就掉脚边
            if (!player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false);
            }
        }

        src.sendSuccess(() -> Component.literal("§a【千相】§r已发放千相测试包"), false);
        return kits.size();
    }

    /** 组装测试包：功能方块 + 全部 18 种材料各 4 + 武器/防具/工具/法术书 + 刷怪蛋 + 维度方块。 */
    private static List<ItemStack> buildKit() {
        List<ItemStack> list = new ArrayList<>();

        // 锻造台（相之凝结台）×1
        list.add(new ItemStack(QianxiangBlocks.FORGE_TABLE_ITEM.get()));

        // 万象森罗传送门框 ×16（方便测试）
        list.add(new ItemStack(QianxiangBlocks.RIFT_STONE_ITEM.get(), 16));

        // 全部材料各 4 个（15 在 QianxiangMaterials，4 在 QianxiangItems）
        list.add(new ItemStack(QianxiangMaterials.GLIMMER_WOOD_SAP.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.SHADOWHIDE_PATCH.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.EMBER_IRON.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.BLOODROOT.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.ABYSS_IRON.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.SALAMANDER_GLAND.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.DRAGON_BONE.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.RIFT_ESSENCE.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.FROST_CRYSTAL.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.THUNDER_STONE.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.VENOM_GLAND.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.SHADOW_DUST.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.HOLY_SHARD.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.NATURE_BREATH.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangMaterials.VOID_SHARD.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangItems.EMBER_CRYSTAL.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangItems.BEAST_FANG.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangItems.MYRIAD_FRAGMENT.get(), MATERIAL_COUNT));
        list.add(new ItemStack(QianxiangItems.REVERSE_CORE.get(), MATERIAL_COUNT));

        // 武器 4 + 防具 5 + 工具 2 各 1（方便对比属性差异）
        list.add(new ItemStack(QianxiangItems.EMBER_BLADE.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_STAFF.get()));
        list.add(new ItemStack(QianxiangItems.BONE_BLADE.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_SHIELD.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_HELMET.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_CHESTPLATE.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_LEGGINGS.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_BOOTS.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_HOE.get()));
        list.add(new ItemStack(QianxiangItems.PHASE_WATERING_CAN.get()));

        // 法术书（带 3 个入门预置法术）
        list.add(new ItemStack(QianxiangItems.SPELL_BOOK.get()));

        // NPC 刷怪蛋（流浪相师 / 深渊商人）
        list.add(new ItemStack(QianxiangItems.WANDERING_SAGE_SPAWN_EGG.get()));
        list.add(new ItemStack(QianxiangItems.ABYSS_MERCHANT_SPAWN_EGG.get()));
        list.add(new ItemStack(QianxiangItems.MYRIAD_WARDEN_SPAWN_EGG.get()));

        // 维度装饰/资源方块（微光原木、微光树叶、野光草、虚空矿石）
        list.add(new ItemStack(QianxiangBlocks.GLIMMER_LOG_ITEM.get(), 8));
        list.add(new ItemStack(QianxiangBlocks.GLIMMER_LEAVES_ITEM.get(), 8));
        list.add(new ItemStack(QianxiangBlocks.WILDLIGHT_GRASS_ITEM.get(), 8));
        list.add(new ItemStack(QianxiangBlocks.VOID_ORE_ITEM.get(), 8));

        return list;
    }
}
