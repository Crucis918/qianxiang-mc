package com.qianxiang;

import com.mojang.logging.LogUtils;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.compat.QianxiangEFCompat;
import com.qianxiang.QianxiangCreativeTab;
import com.qianxiang.entity.QianxiangEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * 《千相》MC 版主类。
 * 灵魂：自定义（材料即零件，AI 当配方大脑）。战斗承载：Epic Fight。
 * MOD_ID = "qianxiang"。
 */
@Mod(Qianxiang.MOD_ID)
public final class Qianxiang {
    public static final String MOD_ID = "qianxiang";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Qianxiang(IEventBus modEventBus) {
        // DataComponents 必须在 Items/Materials 之前注册（它们引用其 component）
        QianxiangDataComponents.DATA_COMPONENTS.register(modEventBus);
        QianxiangMaterials.MATERIALS.register(modEventBus); // 相材料（零件库），引用 PHASE_DATA
        QianxiangAttachments.ATTACHMENT_TYPES.register(modEventBus); // 玩家相谱+位格（律二不可逆铭刻）
        QianxiangEntities.ENTITIES.register(modEventBus); // 千相 NPC
        QianxiangItems.ITEMS.register(modEventBus);
        QianxiangArmorMaterials.ARMOR_MATERIALS.register(modEventBus); // 千相护甲材质（防具兜底壳）
        QianxiangBlocks.BLOCKS.register(modEventBus);
        QianxiangBlocks.BLOCK_ITEMS.register(modEventBus);
        QianxiangCreativeTab.CREATIVE_TABS.register(modEventBus); // 创造分组「千相」
        QianxiangBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        QianxiangMenus.MENUS.register(modEventBus);
        // Epic Fight 动态武器动作适配：锻造产物按特征（重/轻/长柄/杖/盾）实时选动作类型。
        // 需先于 EF 注册（mods.toml 对 epicfight 声明 ordering = "BEFORE"），静态 JSON 兜底。
        modEventBus.addListener(QianxiangEFCompat::register);
        LOGGER.info("[Qianxiang] 相之凝结已加载 / phase-driven mod loaded.");
    }
}
