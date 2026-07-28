package com.qianxiang.compat;

import com.qianxiang.phase.MaterialSources;
import com.tiviacz.travelersbackpack.capability.AttachmentUtils;
import com.tiviacz.travelersbackpack.inventory.BackpackWrapper;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.Optional;

/**
 * 旅行背包（Traveler's Backpack）取料源——软依赖隔离类。
 * <p>
 * 全模组对 {@code com.tiviacz.travelersbackpack.*} 的引用只存在于本类，
 * 调用点一律先 {@code ModList.isLoaded("travelersbackpack")} 再碰本类
 * （EF 同款隔离模式：未装 TB 时本类方法体不会执行，第三方类型不触发解析）。
 * TB 无 capability，官方接法：{@link AttachmentUtils#getBackpackWrapper(Player)}
 * → {@link BackpackWrapper#getStorage()} 得 {@link ItemStackHandler}。
 * </p>
 */
public final class TravelersBackpackCompat {

    private TravelersBackpackCompat() {}

    /** 玩家背着的旅行背包作为取料源；没穿背包/无存储返回空。 */
    public static Optional<MaterialSources.Source> sourceOf(Player player) {
        if (player == null || !AttachmentUtils.isWearingBackpack(player)) {
            return Optional.empty();
        }
        BackpackWrapper wrapper = AttachmentUtils.getBackpackWrapper(player);
        if (wrapper == null) {
            return Optional.empty();
        }
        ItemStackHandler storage = wrapper.getStorage();
        if (storage == null) {
            return Optional.empty();
        }
        return MaterialSources.wrap(MaterialSources.handlerSource(storage));
    }
}
