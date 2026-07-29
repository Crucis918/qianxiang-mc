package com.qianxiang.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * PLAYTEST 收尾的客户端退出入口（{@code minecraft.stop()}）。
 * <p>
 * 单独成类的原因：{@code PlaytestHandler} 跑在服务端线程（集成服务器），
 * 直接碰 {@link Minecraft} 会把客户端类拽进服务端链接面；隔离到本类后，
 * 只有 handler 末尾的 dist==CLIENT 守卫路径才会触达，GameTestServer 永不加载。
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public final class PlaytestQuit {

    private PlaytestQuit() {}

    /**
     * 安全退出整个游戏：切回渲染线程调 {@link Minecraft#stop()}——
     * 它会先正常关停集成服务器（存档落盘）再退出进程，与玩家手动点「退出游戏」同路。
     */
    public static void quitToDesktop() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(minecraft::stop);
    }
}
