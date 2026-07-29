package com.qianxiang.network;

import com.qianxiang.Qianxiang;
import com.qianxiang.ai.ForgeTableAIHandler;
import com.qianxiang.blueprint.BlueprintServerHandler;
import com.qianxiang.client.ClientAIConfigCache;
import com.qianxiang.client.ClientBlueprintCache;
import com.qianxiang.client.ClientDamageNumbers;
import com.qianxiang.client.ClientForgeTableAI;
import com.qianxiang.client.ClientSpellData;
import com.qianxiang.spell.SpellCastHandler;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 千相网络包注册中心。
 * <p>
 * 1.21.1 NeoForge 新网络协议：在 MOD 总线上监听
 * {@link RegisterPayloadHandlersEvent}，拿 {@link PayloadRegistrar} 注册
 * {@link DamageNumberPayload} 为 play-to-client（服务端发、客户端收）。
 * </p>
 * <p>API 查证（javap PayloadRegistrar）：</p>
 * <ul>
 *   <li>{@code registrar.playToClient(Type, StreamCodec, IPayloadHandler)}
 *       —— 三参签名，StreamCodec 是 {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}，
 *       我们的 {@link DamageNumberPayload#STREAM_CODEC} 基于 FriendlyByteBuf，兼容。</li>
 *   <li>{@link IPayloadHandler#handle(Object, IPayloadContext)}，context 提供
 *       {@link IPayloadContext#enqueueWork(Runnable)} 把渲染工作排进客户端主线程。</li>
 * </ul>
 * <p>这里只注册 + 转发：真正画字在 {@link ClientDamageNumbers}。</p>
 */
@EventBusSubscriber(modid = Qianxiang.MOD_ID)
public final class QianxiangPayloads {

    private QianxiangPayloads() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        // 版本字符串仅用于协议兼容性校验，本地开发用 "1" 即可。
        PayloadRegistrar registrar = event.registrar("1");

        // 服务端→客户端：伤害浮字。handler 里再判流向，避免服务端意外加载 client 类。
        // javap PacketFlow：只有枚举常量 CLIENTBOUND/SERVERBOUND，无 isClientbound() 方法。
        IPayloadHandler<DamageNumberPayload> handler = (payload, context) ->
                context.enqueueWork(() -> {
                    // 仅客户端应处理；playToClient 注册下服务端不会收到，这层判据作双保险。
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        ClientDamageNumbers.receive(payload);
                    }
                });

        registrar.playToClient(DamageNumberPayload.TYPE, DamageNumberPayload.STREAM_CODEC, handler);

        // 客户端→服务端：功能台 AI 请求。按「玩家当前打开的菜单类型」分派：
        // 炼金台 → AlchemyTableAIHandler；其余（锻造台）→ ForgeTableAIHandler（原有行为不变）。
        registrar.playToServer(AiRequestPayload.TYPE, AiRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player().containerMenu instanceof com.qianxiang.menu.AlchemyTableMenu) {
                        com.qianxiang.ai.AlchemyTableAIHandler.handleOnMainThread(payload, context);
                    } else {
                        ForgeTableAIHandler.handle(payload, context);
                    }
                }));

        // 服务端→客户端：AI 推荐结果。按当前打开的界面分派给对应的监听器。
        registrar.playToClient(AiResponsePayload.TYPE, AiResponsePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        if (net.minecraft.client.Minecraft.getInstance().screen
                                instanceof com.qianxiang.client.AlchemyTableScreen) {
                            com.qianxiang.client.ClientAlchemyTableAI.receive(payload);
                        } else {
                            ClientForgeTableAI.receive(payload);
                        }
                    }
                }));

        // 客户端→服务端：把 AI 推荐材料放入锻造台材料槽。
        registrar.playToServer(AiPlaceMaterialsPayload.TYPE, AiPlaceMaterialsPayload.STREAM_CODEC,
                (payload, context) -> AiPlaceMaterialsHandler.handle(payload, context));

        // 服务端→客户端：AI 放料缺料名单（物品 id 列表，客户端按本地语言渲染 hoverName）。
        registrar.playToClient(MissingMaterialsPayload.TYPE, MissingMaterialsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        com.qianxiang.client.ClientMissingMaterialsNotice.receive(payload);
                    }
                }));

        // 客户端→服务端：回传最近一次 AI 响应的 spellJson + 自定义名（产物生成侧消费）。
        registrar.playToServer(SpellJsonReportPayload.TYPE, SpellJsonReportPayload.STREAM_CODEC,
                (payload, context) -> SpellJsonReportHandler.handle(payload, context));

        // 客户端→服务端：功能台材料列表点选取回（锻造/炼金按 openMenu 分派）。
        registrar.playToServer(TableRetrievePayload.TYPE, TableRetrievePayload.STREAM_CODEC,
                (payload, context) -> TableRetrieveHandler.handle(payload, context));

        // 客户端→服务端：GUI 背包左键投入材料（锻造/炼金按 openMenu 分派）。
        registrar.playToServer(TableInsertPayload.TYPE, TableInsertPayload.STREAM_CODEC,
                (payload, context) -> TableInsertHandler.handle(payload, context));

        // 客户端→服务端：「开始创作」按钮请求启动合成仪式（按 openMenu 分派两台）。
        registrar.playToServer(RitualStartPayload.TYPE, RitualStartPayload.STREAM_CODEC,
                (payload, context) -> RitualStartHandler.handle(payload, context));

        // 客户端→服务端：熟练度——分配节点 / 洗点 / 主动技能（战吼/涌动）。
        registrar.playToServer(AllocateNodePayload.TYPE, AllocateNodePayload.STREAM_CODEC,
                (payload, context) -> ProficiencyHandlers.handleAllocate(payload, context));
        registrar.playToServer(RespecPayload.TYPE, RespecPayload.STREAM_CODEC,
                (payload, context) -> ProficiencyHandlers.handleRespec(payload, context));
        registrar.playToServer(ActivateSkillPayload.TYPE, ActivateSkillPayload.STREAM_CODEC,
                (payload, context) -> ProficiencyHandlers.handleActivateSkill(payload, context));

        // 服务端→客户端：全量同步熟练度；打开技能树界面（下一步客户端接 GUI）。
        registrar.playToClient(ProficiencySyncPayload.TYPE, ProficiencySyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        com.qianxiang.client.ClientProficiencyData.receive(payload);
                    }
                }));
        registrar.playToClient(OpenSkillTreePayload.TYPE, OpenSkillTreePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        com.qianxiang.client.ClientProficiencyData.onOpenSkillTree();
                    }
                }));

        // 服务端→客户端：「能做啥」主动建议（材料防抖后的本地分析一行）。
        registrar.playToClient(TableSuggestionPayload.TYPE, TableSuggestionPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        com.qianxiang.client.ClientTableSuggestion.receive(payload);
                    }
                }));

        // 服务端→客户端：/qianxiang shot 与 dev 自动冒烟装置的截图请求（抓整窗存 run/screenshots）。
        registrar.playToClient(ScreenshotRequestPayload.TYPE, ScreenshotRequestPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        com.qianxiang.client.ClientScreenshot.grab(payload);
                    }
                }));

        // 客户端→服务端：蓝图保存 / 使用 / 列表请求。
        registrar.playToServer(BlueprintSavePayload.TYPE, BlueprintSavePayload.STREAM_CODEC,
                (payload, context) -> BlueprintServerHandler.handleSave(payload, context));
        registrar.playToServer(BlueprintUsePayload.TYPE, BlueprintUsePayload.STREAM_CODEC,
                (payload, context) -> BlueprintServerHandler.handleUse(payload, context));
        registrar.playToServer(BlueprintListRequestPayload.TYPE, BlueprintListRequestPayload.STREAM_CODEC,
                (payload, context) -> BlueprintServerHandler.handleListRequest(payload, context));

        // 服务端→客户端：同步蓝图列表。
        registrar.playToClient(BlueprintSyncPayload.TYPE, BlueprintSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        ClientBlueprintCache.receive(payload);
                    }
                }));

        // 客户端→服务端：玩家按下施法键（默认 B）请求施法。
        registrar.playToServer(CastSpellPayload.TYPE, CastSpellPayload.STREAM_CODEC,
                (payload, context) -> SpellCastHandler.handle(payload, context));

        // 服务端→客户端：同步 mana 给 HUD。
        registrar.playToClient(SpellDataSyncPayload.TYPE, SpellDataSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        ClientSpellData.receive(payload, context.player());
                    }
                }));

        // 客户端→服务端：连击编辑器应用编排好的动作序列（写结果槽产物/主手武器的 CUSTOM_MOVESET）。
        registrar.playToServer(MovesetApplyPayload.TYPE, MovesetApplyPayload.STREAM_CODEC,
                (payload, context) -> MovesetApplyHandler.handle(payload, context));

        // 服务端→客户端：数据驱动相材料整表同步（登录 / reload 时推送）。
        registrar.playToClient(PhaseMaterialSyncPayload.TYPE, PhaseMaterialSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.flow() == PacketFlow.CLIENTBOUND) {
                        com.qianxiang.phase.PhaseMaterialRegistry.setSynced(payload.entries());
                    }
                }));

        // AI 服务配置：双向包。C2S=保存配置到服务端 config/qianxiang-ai.json；
        // S2C=登录推送/保存回执，客户端缓存供「AI 设置」界面回填。
        // 同一 TYPE 不能 playToServer+playToClient 重复注册，必须 playBidirectional 一次注册按 flow 分发。
        registrar.playBidirectional(AiConfigSyncPayload.TYPE, AiConfigSyncPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.flow() == PacketFlow.SERVERBOUND) {
                        AiConfigSyncHandler.handleSave(payload, context);
                    } else {
                        context.enqueueWork(() -> ClientAIConfigCache.receive(payload));
                    }
                });
    }
}
