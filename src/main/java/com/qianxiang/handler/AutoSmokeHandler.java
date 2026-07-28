package com.qianxiang.handler;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangBlocks;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.QianxiangItems;
import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.block.RitualLogic;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.network.OpenSkillTreePayload;
import com.qianxiang.network.ScreenshotRequestPayload;
import com.qianxiang.phase.ForgeComposer;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * dev 自动视觉冒烟装置：客户端进世界后无人工操作跑一串动作并按节点截图。
 * <p>
 * <b>双重门控（正常游戏零行为变化）</b>：仅当环境变量 {@code QX_AUTOSMOKE=1}
 * 且运行端为客户端（{@code FMLEnvironment.dist == CLIENT}，GameTestServer 是
 * DEDICATED_SERVER 天然不触发）且单人世界时启用；build.gradle 的 client run
 * 同款门控追加 {@code --quickPlaySingleplayer}（存档名默认「新的世界」，
 * {@code QX_AUTOSMOKE_WORLD} 可覆盖）。
 * </p>
 * <p>
 * 步骤表（PlayerLoggedInEvent 起 tick 计数，ServerTickEvent 驱动）：
 * T+20 kit 领料 → T+40 传送平坦点 → T+60 面前放锻造台+炼金台 →
 * T+100 程序投料（铁/燧石/裂隙精髓）→ T+120 拍台子虚影 →
 * T+160 触发仪式 → T+180/200/230 连拍仪式 FLYING/FORMING →
 * T+280 拍 DONE 产物虚影 → T+300 主手持械拍第一人称 →
 * T+320 拍第三人称手持 → T+340 切回第一人称 →
 * T+344 起九形态循环（每形态 10t：装备第一人称拍 → +5t 第三人称拍，
 * 刃缘色板轮换 ignite/lifesteal/slow/holy）→
 * T+440/T+446 法杖/法术书第一人称单镜（3D 挤出排查）→
 * T+460 开启修行+学法术+开技能树 → T+480 拍技能树 →
 * T+500 关屏 → T+520 写 run/screenshots/AUTOSMOKE_DONE.txt。
 * 每步独立 try-catch 记 WARN 继续——冒烟装置绝不崩游戏。
 * </p>
 * <p>
 * <b>截图内容自查点</b>（验收时对照）：①台子上方漂浮材料虚影（材料环+产物自转）；
 * ②仪式 FLYING 材料飞入拖尾 / FORMING 锻打火花；③DONE 后产物虚影；
 * ④第一人称手持武器 3D 挤出；⑤第三人称背面手持；⑥技能树界面。
 * 背包图标屏不拍（图标验收靠手持+虚影，避免再引入 openInventory 语义）。
 * </p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class AutoSmokeHandler {

    /** 双重门控之一：环境变量。 */
    private static final boolean ENABLED = "1".equals(System.getenv("QX_AUTOSMOKE"));

    /** 序列主体（登录时捕获）；null = 未启动/已结束。 */
    private static ServerPlayer subject;
    /** 登录起的 tick 计数。 */
    private static int tick;

    private AutoSmokeHandler() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!ENABLED || FMLEnvironment.dist != Dist.CLIENT) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.getServer().isSingleplayer()) return; // 只在单人冒烟，不碰真服务器
        subject = player;
        tick = 0;
        Qianxiang.LOGGER.info("[Qianxiang] AUTOSMOKE 启动：玩家 {} 进世界，步骤表开始",
                player.getGameProfile().getName());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerPlayer player = subject;
        if (player == null) return;
        if (player.isRemoved()) { // 中途退出：静默终止，不崩不闹
            subject = null;
            return;
        }
        tick++;
        try {
            step(player, tick);
        } catch (Throwable t) {
            Qianxiang.LOGGER.warn("[Qianxiang] AUTOSMOKE 步骤 T+{} 失败（继续后续步骤）：{}",
                    tick, t.toString());
        }
    }

    /** 步骤表：按 tick 偏移分派；T+520 写完成标记并结束。 */
    private static void step(ServerPlayer player, int t) throws Exception {
        switch (t) {
            case 20 -> { runCommand(player, "qianxiang kit"); return; }
            case 40 -> { teleportToSafety(player); return; }
            case 60 -> { placeTables(player); return; }
            case 100 -> { fillMaterials(player); return; }
            case 160 -> { startRitual(player); return; }
            case 120, 180, 200, 230, 280 -> { shot(player, 0, false); return; }
            case 300 -> { equipWeapon(player); return; }
            case 320 -> { shot(player, 2, false); return; }  // 第三人称背面手持
            case 340 -> { shot(player, 1, false); return; }  // 切回第一人称
            default -> { }
        }
        // —— 九形态手持循环：T+344 起每形态 10t（装备+第一人称 → +5t 第三人称） ——
        for (int i = 0; i < FORM_IDS.length; i++) {
            int t0 = FORM_BASE_TICK + i * FORM_STRIDE_TICKS;
            if (t == t0) {
                equipFormWeapon(player, FORM_IDS[i], FORM_COLOR_FUNCS[i]);
                shot(player, 1, false);
                return;
            }
            if (t == t0 + 5) {
                shot(player, 2, false);
                return;
            }
        }
        // —— 法杖/法术书第一人称单镜（3D 挤出排查：实拍曾见一团紫色不可辨物） ——
        if (t == FORM_BASE_TICK + 96) { // T+440
            equipSpecial(player, true);
            shot(player, 1, false);
            return;
        }
        if (t == FORM_BASE_TICK + 102) { // T+446
            equipSpecial(player, false);
            shot(player, 1, false);
            return;
        }
        // —— 技能树与收尾 ——
        if (t == FORM_BASE_TICK + 116) { openSkillTree(player); return; }  // T+460
        if (t == FORM_BASE_TICK + 136) { shot(player, 0, false); return; }  // T+480
        if (t == FORM_BASE_TICK + 156) { shot(player, 0, true); return; }   // T+500 关屏
        if (t == FORM_BASE_TICK + 176) { finish(player); }                  // T+520
    }

    // ============================ 九形态手持循环 ============================

    /** 循环起点（T+340 切回第一人称之后）。 */
    private static final int FORM_BASE_TICK = 344;
    /** 每形态占用 tick（两镜间隔 5t ≥ 截图 2t 延迟 + 余量）。 */
    private static final int FORM_STRIDE_TICKS = 10;
    /** 九个推导形态 id（WeaponFormProfile.of 映射纹理 shape/EF 底座）。 */
    private static final String[] FORM_IDS = {
            "sword", "greatsword", "dagger", "katana", "spear",
            "axe", "hammer", "scythe", "mace"
    };
    /** 刃缘色板轮换（有色才看得出形态刃缘）：对应功能算子。 */
    private static final com.qianxiang.phase.PhaseFunction[] FORM_COLOR_FUNCS = {
            com.qianxiang.phase.PhaseFunction.IGNITE, com.qianxiang.phase.PhaseFunction.LIFESTEAL,
            com.qianxiang.phase.PhaseFunction.SLOW, com.qianxiang.phase.PhaseFunction.HEAL,
            com.qianxiang.phase.PhaseFunction.IGNITE, com.qianxiang.phase.PhaseFunction.LIFESTEAL,
            com.qianxiang.phase.PhaseFunction.SLOW, com.qianxiang.phase.PhaseFunction.HEAL,
            com.qianxiang.phase.PhaseFunction.IGNITE
    };

    /**
     * 构造某形态的武器并设为玩家主手：不走 compose（材料组合会被 spell_book 优先截胡——
     * 上一轮实拍的教训），直接 EMBER_BLADE + 写 COMPOSED_ATTRIBUTES
     * （数值经 AttributeScheme 出，form/baseFamily/效果等级按表单参数覆盖）。
     */
    private static void equipFormWeapon(ServerPlayer player, String form,
                                        com.qianxiang.phase.PhaseFunction colorFunc) {
        ItemStack stack = new ItemStack(QianxiangItems.EMBER_BLADE.get());
        stack.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                displayAttributes(form, colorFunc));
        player.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    /** 法杖（staff 形态）/法术书（book 形态）主手单镜用。 */
    private static void equipSpecial(ServerPlayer player, boolean staff) {
        ItemStack stack = new ItemStack(staff
                ? QianxiangItems.PHASE_STAFF.get() : QianxiangItems.SPELL_BOOK.get());
        stack.set(QianxiangDataComponents.COMPOSED_ATTRIBUTES.get(),
                displayAttributes(staff ? "staff" : "book", com.qianxiang.phase.PhaseFunction.MANA));
        player.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    /** 展示用属性：RARE 档 EDGE+基底+色板算子（数值合理、刃缘有色、tier≥1 有外框）。 */
    private static com.qianxiang.phase.ComposedAttributes displayAttributes(
            String form, com.qianxiang.phase.PhaseFunction colorFunc) {
        var attr = com.qianxiang.phase.AttributeScheme.compose(List.of(
                com.qianxiang.phase.AttributeScheme.MaterialInput.of(
                        com.qianxiang.phase.PhaseTier.RARE,
                        com.qianxiang.phase.PhaseFunction.EDGE,
                        com.qianxiang.phase.PhaseFunction.BASE_METAL,
                        colorFunc)));
        return attr.withAppearance(java.util.Set.of(), "", "")
                .withForm(form)
                .withBaseFamily("metal");
    }

    // ============================ 各步骤实现 ============================

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
                0.0f, 12.0f); // yaw 0 朝 +Z，俯视 12° 让台子入画
    }

    private static void placeTables(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        BlockPos forge = new BlockPos(base.getX(), findGroundY(level, base), base.getZ() + 3);
        level.setBlockAndUpdate(forge, QianxiangBlocks.FORGE_TABLE.get().defaultBlockState());
        level.setBlockAndUpdate(forge.east(), QianxiangBlocks.ALCHEMY_TABLE.get().defaultBlockState());
    }

    private static int findGroundY(ServerLevel level, BlockPos near) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near).getY();
    }

    private static void fillMaterials(ServerPlayer player) {
        ForgeTableBlockEntity be = forgeBe(player);
        if (be == null) return;
        be.setItem(12, new ItemStack(Items.IRON_INGOT));       // 核心：基底
        be.setItem(6, new ItemStack(Items.FLINT));             // 内圈：锋刃
        be.setItem(7, new ItemStack(Items.BLAZE_POWDER));      // 内圈：点燃（刃缘橙）
        // 注意：不放裂隙精髓——其 MANA 算子 + 精髓会触发 spell_book 优先（上一轮实拍
        // T+300 产物被截胡成法术书的根因），本序列要验的是武器。
        // 无菜单打开时 BE 不会自重算：借菜单跑一遍 compose 刷新产物预览槽
        new ForgeTableMenu(0, player.getInventory(), be).slotsChanged(be);
    }

    private static void startRitual(ServerPlayer player) {
        ForgeTableBlockEntity be = forgeBe(player);
        if (be == null) return;
        if (!RitualLogic.startRitual(be, player)) {
            Qianxiang.LOGGER.warn("[Qianxiang] AUTOSMOKE 仪式触发被拒（产物预览未就绪？）");
        }
    }

    private static void equipWeapon(ServerPlayer player) {
        // 与投料同配方的产物直接组合一份（仪式产物在台面供前一镜拍摄，这里拍手持用副本）
        List<ItemStack> mats = new ArrayList<>(25);
        for (int i = 0; i < 25; i++) mats.add(ItemStack.EMPTY);
        mats.set(12, new ItemStack(Items.IRON_INGOT));
        mats.set(6, new ItemStack(Items.FLINT));
        mats.set(7, new ItemStack(Items.BLAZE_POWDER)); // 与 fillMaterials 同步（无 MANA，必出武器）
        ForgeComposer.Composition comp = ForgeComposer.compose(mats);
        if (!comp.valid()) {
            Qianxiang.LOGGER.warn("[Qianxiang] AUTOSMOKE 手持武器组合无效");
            return;
        }
        player.setItemSlot(EquipmentSlot.MAINHAND, comp.result());
        shot(player, 1, false); // 第一人称手持
    }

    private static void openSkillTree(ServerPlayer player) {
        com.qianxiang.cap.ProficiencyHelper.unlock(player);
        // 学一个卷轴法术（法术栏不为空，轮盘/技能树画面更完整）
        CustomSpell spell = new CustomSpell(
                ResourceLocation.fromNamespaceAndPath("qianxiang", "autosmoke_fire_aoe"),
                "fire", "aoe", "damage", List.of(), 10, 0, 2);
        var attachment = com.qianxiang.cap.QianxiangAttachments.PLAYER_SPELL_DATA;
        player.setData(attachment, player.getData(attachment).learn(spell));
        PacketDistributor.sendToPlayer(player, new OpenSkillTreePayload());
    }

    private static void finish(ServerPlayer player) throws Exception {
        Path dir = Path.of("screenshots");
        Files.createDirectories(dir);
        String marker = "AUTOSMOKE DONE " + new java.util.Date() + "\n"
                + "步骤：kit→传送→放台→投料→拍虚影→仪式→连拍→DONE→手持→第三人称"
                + "→九形态循环(18 镜)→法杖/书(2 镜)→技能树→关屏\n"
                + "本目录下 qx_<时间戳>.png 为各节点截图（同秒连拍自动 _1/_2 去重）。\n";
        Files.writeString(dir.resolve("AUTOSMOKE_DONE.txt"), marker, StandardCharsets.UTF_8);
        player.sendSystemMessage(Component.literal("[autosmoke] done — 见 run/screenshots/"));
        Qianxiang.LOGGER.info("[Qianxiang] AUTOSMOKE 完成，标记文件已写 screenshots/AUTOSMOKE_DONE.txt");
        subject = null; // 序列结束
    }

    // ============================ 工具 ============================

    /** 发截图请求：perspective 0=不切 / 1=第一 / 2=第三背面；closeScreen 拍前关屏。 */
    private static void shot(ServerPlayer player, int perspective, boolean closeScreen) {
        PacketDistributor.sendToPlayer(player,
                new ScreenshotRequestPayload(perspective, closeScreen));
    }

    /** 玩家面前 3 格的锻造台 BE（placeTables 落点；地面不平允许 y±1 容差，找不到记 WARN 返回 null）。 */
    private static ForgeTableBlockEntity forgeBe(ServerPlayer player) {
        BlockPos base = player.blockPosition();
        for (int dz = 2; dz <= 4; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos pos = new BlockPos(base.getX(), base.getY() + dy, base.getZ() + dz);
                if (player.serverLevel().getBlockEntity(pos) instanceof ForgeTableBlockEntity be) {
                    return be;
                }
            }
        }
        Qianxiang.LOGGER.warn("[Qianxiang] AUTOSMOKE 找不到面前锻造台 BE（放置失败？）");
        return null;
    }
}
