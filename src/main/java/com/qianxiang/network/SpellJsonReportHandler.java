package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.block.ForgeTableBlockEntity;
import com.qianxiang.menu.ForgeTableMenu;
import com.qianxiang.spell.CustomSpell;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理「AI 输出（spellJson + 自定义名）回传」。
 * <p>
 * 回传内容来自客户端，一律按不可信输入处理：非空 spellJson 必须先过
 * {@link CustomSpell#fromSpellJson}（白名单 + power 夹取的唯一校验入口），
 * 被拒时整包丢弃并记 WARN（不回发反馈）；自定义名经 {@link CustomSpell#sanitizeCustomName} 截断。
 * 校验玩家当前打开的是锻造台菜单后，把 AI 输出暂存到底层方块实体，
 * 并触发一次 slotsChanged 让结果槽按新 spellJson 立即重算产物。
 * 玩家没打开锻造台（比如用命令问 AI）时静默忽略。
 */
public final class SpellJsonReportHandler {

    private SpellJsonReportHandler() {}

    public static void handle(SpellJsonReportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player == null) return;
            if (player.level().isClientSide) return;
            if (!(player.containerMenu instanceof ForgeTableMenu menu)) return;
            if (!(menu.getContainer() instanceof ForgeTableBlockEntity be)) return;

            String spellJson = payload.spellJson();
            if (spellJson != null && !spellJson.isBlank()
                    && CustomSpell.fromSpellJson(spellJson, 1) == null) {
                Qianxiang.LOGGER.warn("[Qianxiang] 玩家 {} 回传了非法 spellJson，已丢弃",
                        player.getName().getString());
                return;
            }

            be.setLastAiSpell(spellJson == null ? "" : spellJson,
                    CustomSpell.sanitizeCustomName(payload.customName()));
            be.setLastAiMoveset(payload.movesetJson());
            menu.slotsChanged(menu.getContainer());
        });
    }
}
