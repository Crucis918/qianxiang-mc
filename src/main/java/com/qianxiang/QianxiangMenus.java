package com.qianxiang;

import com.qianxiang.menu.ForgeTableMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.MenuType.MenuSupplier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class QianxiangMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Qianxiang.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<ForgeTableMenu>> FORGE_TABLE =
            MENUS.register("forge_table", () -> {
                MenuSupplier<ForgeTableMenu> factory = (id, inv) -> new ForgeTableMenu(id, inv);
                // 1.21.1 MenuType 构造器需 (MenuSupplier, FeatureFlagSet)
                return new MenuType<>(factory, FeatureFlagSet.of());
            });
}
