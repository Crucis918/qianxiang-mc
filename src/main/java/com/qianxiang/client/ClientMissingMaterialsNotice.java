package com.qianxiang.client;

import com.qianxiang.network.MissingMaterialsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「还缺：X、Y」actionbar 的客户端渲染端（{@link MissingMaterialsPayload} 到达时调用）。
 * <p>
 * 物品名在<b>客户端</b>语言环境下解析（{@link ItemStack#getHoverName()}），
 * 中文客户端看到「还缺：铁锭、钻石」而非英文 id 名；lang 框架键
 * {@code qianxiang.table.missing} 由客户端翻译表提供，与服务端语言无关。
 * </p>
 */
public final class ClientMissingMaterialsNotice {

    private ClientMissingMaterialsNotice() {}

    public static void receive(MissingMaterialsPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        MutableComponent names = Component.literal("");
        int shown = 0;
        for (String raw : payload.itemIds()) {
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) continue;
            Item item = BuiltInRegistries.ITEM.get(id);
            // 注册表 get 对未知 id 返回默认 AIR：校验反查键，未知条目回退显示原始 id
            boolean known = item != null && BuiltInRegistries.ITEM.getKey(item).equals(id);
            if (shown++ > 0) names.append(", ");
            names.append(known ? new ItemStack(item).getHoverName() : Component.literal(raw));
        }
        if (shown == 0) return;

        mc.player.displayClientMessage(
                Component.translatable("qianxiang.table.missing", names), true);
    }
}
