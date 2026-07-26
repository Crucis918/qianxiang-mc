package com.qianxiang.spell;

import com.qianxiang.Qianxiang;
import com.qianxiang.QianxiangDataComponents;
import com.qianxiang.cap.PlayerSpellData;
import com.qianxiang.cap.QianxiangAttachments;
import com.qianxiang.network.CastSpellPayload;
import com.qianxiang.network.SpellDataSyncPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端处理玩家施法请求。
 * <p>
 * 流程：
 * <ol>
 *   <li>读取玩家主手物品上的 {@code qianxiang:spell} 组件。</li>
 *   <li>无法术、法力不足、冷却中 → 给玩家提示并返回。</li>
 *   <li>扣除法力、写入冷却、调用 {@link SpellEffects#cast} 产生效果。</li>
 *   <li>同步 mana 到客户端。</li>
 * </ol>
 * <p>
 * 扩展（自由法术）：主手没有旧 {@code spell} 组件但有 {@code spellbook} 组件
 * （千相法术书）时，改走 {@link #castCustomSpell} 施放当前选中的自定义法术。
 * 旧 {@link Spell} 路径完全保留，存档兼容。
 * </p>
 */
public final class SpellCastHandler {

    private SpellCastHandler() {}

    /** 施法失败提示的最小间隔：每个被拒上行包回一个下行包，否则是 1:1 的流量放大面。 */
    private static final long FAIL_NOTICE_COOLDOWN_MS = 1_000L;

    /**
     * 节流版 actionbar 提示。冷却/法力不足是玩家按住键就会连续触发的高频路径，
     * 逐包回消息等于让改造过的客户端用最小成本让服务端对它单播海量数据。
     */
    private static void notifyThrottled(ServerPlayer player, String translationKey) {
        if (com.qianxiang.util.PlayerRateLimiter.tryAcquire(
                player, "spell_fail_notice", FAIL_NOTICE_COOLDOWN_MS)) {
            player.displayClientMessage(Component.translatable(translationKey), true);
        }
    }

    public static void handle(CastSpellPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            // 观战者/尸体不得施法：此前只判类型，观战模式按 V 照样能放
            // （ender 元素还会 connection.teleport 把观战者传走），死亡瞬间同理。
            if (!serverPlayer.isAlive() || serverPlayer.isSpectator()) {
                return;
            }

            ItemStack stack = serverPlayer.getMainHandItem();

            // 优先级 ①：AI 锻造相杖上的自由法术（CUSTOM_SPELL 组件）。
            CustomSpell custom = stack.get(QianxiangDataComponents.CUSTOM_SPELL.get());
            if (custom != null) {
                castCustomSpell(custom, serverPlayer);
                return;
            }

            ResourceLocation spellId = stack.get(QianxiangDataComponents.SPELL.get());
            if (spellId == null) {
                // 优先级 ③：主手法术书 → 施放当前选中的 CustomSpell。
                com.qianxiang.spell.SpellBookData book =
                        stack.get(QianxiangDataComponents.SPELLBOOK.get());
                if (book != null && book.selected() != null) {
                    castCustomSpell(book.selected(), serverPlayer);
                }
                return;
            }
            // 优先级 ②：旧硬编码法术（存档兼容路径）。

            Spell spell = Spell.byId(spellId);
            PlayerSpellData data = serverPlayer.getData(QianxiangAttachments.PLAYER_SPELL_DATA);

            if (data.isOnCooldown(spellId)) {
                notifyThrottled(serverPlayer, "qianxiang.spell.cooldown");
                return;
            }
            if (data.currentMana() < spell.manaCost()) {
                notifyThrottled(serverPlayer, "qianxiang.spell.no_mana");
                return;
            }

            try {
                SpellEffects.cast(spell, serverPlayer);
            } catch (Throwable t) {
                Qianxiang.LOGGER.error("[Qianxiang] 施法效果执行失败 spell={}", spellId, t);
                return;
            }

            PlayerSpellData next = data
                    .withMana(data.currentMana() - spell.manaCost())
                    .setCooldown(spellId, spell.cooldownTicks());
            if (next != data) {
                serverPlayer.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
            }
            sync(serverPlayer);
        });
    }

    /**
     * 施放一个自定义法术（自由法术核心路径）。
     * <p>
     * 供 {@link com.qianxiang.item.SpellBookItem} 右键与 V 键（法术书在主手时）调用：
     * 先做冷却/法力校验，通过后调 {@link SpellEffectEngine#cast} 兑现效果，
     * 再扣法力、写冷却、同步客户端。法术数据直接来自物品组件，
     * 因此锻造生成的法术（不在预置注册表）也能施放。
     * </p>
     *
     * @return true = 实际施放成功
     */
    public static boolean castCustomSpell(CustomSpell spell, ServerPlayer serverPlayer) {
        PlayerSpellData data = serverPlayer.getData(QianxiangAttachments.PLAYER_SPELL_DATA);

        if (data.isOnCooldown(spell.id())) {
            notifyThrottled(serverPlayer, "qianxiang.spell.cooldown");
            return false;
        }
        if (data.currentMana() < spell.manaCost()) {
            notifyThrottled(serverPlayer, "qianxiang.spell.no_mana");
            return false;
        }

        try {
            SpellEffectEngine.cast(spell, serverPlayer);
        } catch (Throwable t) {
            Qianxiang.LOGGER.error("[Qianxiang] 自定义法术效果执行失败 spell={}", spell.id(), t);
            return false;
        }

        PlayerSpellData next = data
                .withMana(data.currentMana() - spell.manaCost())
                .setCooldown(spell.id(), spell.cooldownTicks());
        if (next != data) {
            serverPlayer.setData(QianxiangAttachments.PLAYER_SPELL_DATA, next);
        }
        sync(serverPlayer);
        com.qianxiang.QianxiangAdvancements.grant(serverPlayer,
                com.qianxiang.QianxiangAdvancements.FIRST_CAST);
        return true;
    }

    /** 把当前 mana 同步给指定玩家。 */
    public static void sync(ServerPlayer player) {
        PlayerSpellData data = player.getData(QianxiangAttachments.PLAYER_SPELL_DATA);
        PacketDistributor.sendToPlayer(player, new SpellDataSyncPayload(data.currentMana(), data.maxMana()));
    }
}
