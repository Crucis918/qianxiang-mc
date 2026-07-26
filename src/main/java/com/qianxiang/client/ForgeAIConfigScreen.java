package com.qianxiang.client;

import com.qianxiang.ai.AIConfig;
import com.qianxiang.network.AiConfigSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 「AI 设置」配置小页面（从相之凝结台进入，返回时回到工作台）。
 * <p>
 * 内容：provider 切换按钮（ollama / openai）+ 4 个输入框
 * （baseUrl / apiKey / model / 超时秒数）+ 保存 / 返回按钮。
 * <p>
 * 配置真正存<b>服务端</b> {@code config/qianxiang-ai.json}：
 * 点保存 → 发 {@link AiConfigSyncPayload}（C2S）→ 服务端写盘并回发生效值。
 * 单机 = 整合服务端，本地生效。输入框初始值优先取服务端推送缓存
 * （{@link ClientAIConfigCache}），没收到过推送时回退本地配置/默认值。
 */
public class ForgeAIConfigScreen extends Screen {

    /** 返回目标（通常是 {@link ForgeTableScreen}）。 */
    private final Screen parent;

    private static final int PANEL_W = 240;
    private static final int BOX_W = 220;
    private static final int BOX_H = 16;
    private static final int LABEL_COLOR = 0xFFA0A0A0;

    private EditBox baseUrlBox;
    private EditBox apiKeyBox;
    private EditBox modelBox;
    private EditBox timeoutBox;
    private Button providerButton;
    private Button saveButton;
    private Button backButton;

    /** 当前界面内的 provider 选择（true = openai）。 */
    private boolean openAI;
    /** 「已保存」提示的剩余 tick 数。 */
    private int savedFlashTicks = 0;

    public ForgeAIConfigScreen(Screen parent) {
        super(Component.translatable("qianxiang.ai_config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // 初始值：优先服务端推送缓存，其次本地配置文件/默认值
        String provider = AIConfig.DEFAULT_PROVIDER;
        String baseUrl = AIConfig.DEFAULT_BASE_URL;
        String apiKey = AIConfig.DEFAULT_API_KEY;
        String model = AIConfig.DEFAULT_MODEL;
        int timeout = AIConfig.DEFAULT_TIMEOUT_SECONDS;
        AiConfigSyncPayload synced = ClientAIConfigCache.get();
        if (synced != null) {
            provider = synced.provider();
            baseUrl = synced.baseUrl();
            apiKey = synced.apiKey();
            model = synced.model();
            timeout = synced.timeoutSeconds();
        } else {
            try {
                AIConfig local = AIConfig.get();
                provider = local.provider;
                baseUrl = local.baseUrl;
                apiKey = local.apiKey;
                model = local.model;
                timeout = local.timeoutSeconds;
            } catch (Throwable ignored) {
                // 本地配置读不到就用默认值，界面照常可用
            }
        }
        this.openAI = AIConfig.PROVIDER_OPENAI.equals(AIConfig.sanitizeProvider(provider));

        int cx = this.width / 2;
        int y = 52;

        // provider 切换按钮
        this.providerButton = Button.builder(providerLabel(), b -> toggleProvider())
                .bounds(cx - BOX_W / 2, y, BOX_W, BOX_H + 4)
                .build();
        this.addRenderableWidget(this.providerButton);
        y += BOX_H + 4 + 14;

        // baseUrl
        this.baseUrlBox = addBox(cx, y, baseUrl, 256);
        y += BOX_H + 12;
        // apiKey
        this.apiKeyBox = addBox(cx, y, apiKey, 256);
        y += BOX_H + 12;
        // model
        this.modelBox = addBox(cx, y, model, 128);
        y += BOX_H + 12;
        // 超时（秒）：仅数字
        this.timeoutBox = addBox(cx, y, String.valueOf(timeout), 8);
        this.timeoutBox.setFilter(s -> s.matches("\\d{0,3}"));
        y += BOX_H + 16;

        // 保存 / 返回
        this.saveButton = Button.builder(Component.translatable("qianxiang.ai_config.save"), b -> save())
                .bounds(cx - BOX_W / 2, y, 106, BOX_H + 4)
                .build();
        this.addRenderableWidget(this.saveButton);
        this.backButton = Button.builder(Component.translatable("qianxiang.ai_config.back"), b -> onClose())
                .bounds(cx - BOX_W / 2 + 114, y, 106, BOX_H + 4)
                .build();
        this.addRenderableWidget(this.backButton);
    }

    private EditBox addBox(int cx, int y, String initial, int maxLength) {
        EditBox box = new EditBox(this.font, cx - BOX_W / 2, y, BOX_W, BOX_H, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(initial == null ? "" : initial);
        this.addRenderableWidget(box);
        return box;
    }

    private void toggleProvider() {
        this.openAI = !this.openAI;
        this.providerButton.setMessage(providerLabel());
    }

    private Component providerLabel() {
        return Component.translatable("qianxiang.ai_config.provider",
                Component.translatable(this.openAI
                        ? "qianxiang.ai_config.provider.openai"
                        : "qianxiang.ai_config.provider.ollama"));
    }

    /** 保存：组包发给服务端写盘；本地缓存乐观更新，界面立即提示。 */
    private void save() {
        int timeout;
        try {
            timeout = Integer.parseInt(this.timeoutBox.getValue().trim());
        } catch (NumberFormatException e) {
            timeout = AIConfig.DEFAULT_TIMEOUT_SECONDS;
        }
        timeout = Math.clamp(timeout, 1, 120);

        AiConfigSyncPayload payload = new AiConfigSyncPayload(
                this.openAI ? AIConfig.PROVIDER_OPENAI : AIConfig.PROVIDER_OLLAMA,
                this.baseUrlBox.getValue().trim(),
                this.apiKeyBox.getValue().trim(),
                this.modelBox.getValue().trim(),
                timeout);
        ClientAIConfigCache.update(payload);
        PacketDistributor.sendToServer(payload);
        this.savedFlashTicks = 60;
    }

    @Override
    public void onClose() {
        // 返回工作台（容器保持打开，见 ForgeTableScreen.removed 的切换保护）
        if (this.parent != null && this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        // 标题
        g.drawCenteredString(this.font, this.title, cx, 30, 0xFFFFD700);
        // 当前生效 provider 提示（默认 Ollama 体验不变）
        g.drawCenteredString(this.font,
                Component.translatable(openAI
                        ? "qianxiang.ai_config.hint.openai"
                        : "qianxiang.ai_config.hint.ollama"),
                cx, 42, 0xFF888888);

        // 输入框标签（画在各自框上方）
        int labelX = cx - BOX_W / 2;
        int y = 52 + BOX_H + 4 + 14;
        g.drawString(this.font, Component.translatable("qianxiang.ai_config.base_url"), labelX, y - 10, LABEL_COLOR, false);
        y += BOX_H + 12;
        g.drawString(this.font, Component.translatable("qianxiang.ai_config.api_key"), labelX, y - 10, LABEL_COLOR, false);
        y += BOX_H + 12;
        g.drawString(this.font, Component.translatable("qianxiang.ai_config.model"), labelX, y - 10, LABEL_COLOR, false);
        y += BOX_H + 12;
        g.drawString(this.font, Component.translatable("qianxiang.ai_config.timeout"), labelX, y - 10, LABEL_COLOR, false);

        // 「已保存」闪烁提示（画在保存按钮下方）
        if (savedFlashTicks > 0) {
            savedFlashTicks--;
            g.drawCenteredString(this.font,
                    Component.translatable("qianxiang.ai_config.saved"),
                    cx, y + BOX_H + 16 + 24, 0xFF55FF55);
        }
    }
}
