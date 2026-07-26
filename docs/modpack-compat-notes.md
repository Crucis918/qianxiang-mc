# 千相 mod 适配兼容笔记（NeoForge 1.21.1）

> 维护说明：本文记录 dev 环境（`runClient`）实测的 mod 适配清单。
> 所有版本均通过 Modrinth API（`loaders=["neoforge"]&game_versions=["1.21.1"]`）查实，
> 且对应 jar 均验证可下载（HTTP 200）、并解压核实含 `META-INF/neoforge.mods.toml`。

## ⚠️ 重要坑：Modrinth maven 会发错加载器的 jar

`maven.modrinth:<slug>:<version>` 坐标在**同一 version_number 同时有 Fabric 和 NeoForge 文件**时，
maven 只发 primary 文件——通常是 **Fabric 版**。这种 jar 里没有 `neoforge.mods.toml`，
NeoForge 启动时**静默跳过**（不报错、不加载），极易误判为"装上了"。

实测中招的 5 个：entityculling、smooth-boot、dynamic-fps、first-person-model、not-enough-animations。
这些只能绕开 maven：从 Modrinth CDN 下载 NeoForge 专用文件放 `libs/` 直引（`localRuntime files(...)`）。
modernfix / saturn 的 maven 坐标发的是正确的 NeoForge 版，可正常用 maven 坐标。

## 已安装（localRuntime，运行时在场，不编译耦合）

### 硬依赖

| Mod | 版本 | 引入方式 | 说明 |
|---|---|---|---|
| Epic Fight | 21.15.6-mc1.21.1-neoforge | maven 坐标（`implementation`） | 战斗承载，编译依赖 |

### 性能优化

| Mod | 版本 | 引入方式 | 说明 |
|---|---|---|---|
| embeddium | 1.0.15+mc1.21.1 | maven 坐标 | 渲染优化（Sodium 的 NeoForge 移植） |
| lithium | mc1.21.1-0.15.4-neoforge | maven 坐标 | 逻辑/TPS 优化 |
| ferrite-core | 7.0.3-neoforge | maven 坐标 | 内存优化 |
| modernfix | 5.27.20+mc1.21.1 | maven 坐标 | 启动提速 + 内存占用优化（启动 ~10.4s 到主菜单） |
| saturn | mc1.21.1-0.1.5 | maven 坐标 | 内存泄漏修复 + GC 优化 |
| entityculling | 1.10.5 | libs/ 直引 | 实体渲染剔除；maven 发 Fabric 版，已绕开 |
| smooth-boot | 1.0.0 | libs/ 直引 | 启动期线程调度优化；maven 发 Fabric 版，已绕开 |
| dynamic-fps | 3.11.4 | libs/ 直引 | 窗口失焦/后台降帧省电；maven 发 Fabric 版，已绕开 |

### 客户端体验 / 战斗辅助

| Mod | 版本 | 引入方式 | 说明 |
|---|---|---|---|
| first-person-model | 2.7.2 | libs/ 直引 | 第一人称可见自身身体模型；maven 发 Fabric 版，已绕开。jar-in-jar 自带 TRansition/TRender |
| not-enough-animations | 1.12.4 | libs/ 直引 | 第三人称动作补全；maven 发 Fabric 版，已绕开 |

## 明确不装（含原因）

| Mod | 原因 |
|---|---|
| better-combat | 与 Epic Fight 战斗系统冲突（双战斗系统接管攻击判定），EF 是本 mod 硬依赖，不可共存 |
| iris | 已下载 jar 核实其 `neoforge.mods.toml`：声明 `embeddium` 为 incompatible，且 required `sodium [0.6,)`。与已装的 embeddium 互斥 |
| sodium | Fabric 出身；1.21.1 NeoForge 渲染优化由 embeddium 承担（二者二选一，维持 embeddium，避免 iris 类冲突链） |
| starlight / starlight-forge | 1.21 起 vanilla 光照引擎已重写，Starlight 不再支持/无 NeoForge 1.21.1 版本（API 查实） |
| c2me | 主力 Fabric；`c2me-neoforge` 仅 0.4.0-alpha（0.4.0-alpha.0.116+1.21.1），alpha 阶段不稳定，不引入 |
| exordium | Fabric 专用，无 NeoForge 1.21.1 版本（API 查实） |
| krypton | Fabric 专用，无 NeoForge 1.21.1 版本（API 查实；社区 fork 非官方不引入） |
| better-punch | Modrinth 无此 slug（404）；近似项目 `betterpunching` 无 NeoForge 1.21.1 版本 |
| weapon-master | 无 NeoForge 1.21.1 版本（API 查实） |
| combat-hud / combat-hud-indicator | 无 NeoForge 1.21.1 版本（API 查实） |
| torohealth / torohealth-continued | 原 `torohealth` slug 已 404；续作 `torohealth-continued` 1.4.0-beta 虽标注支持 neoforge 1.21.1，但下载核实其 jar 实为旧 Forge 格式（`META-INF/mods.toml`，且内部声明 `minecraft [1.20.4]` / `neoforge [20.3.0,)`），NeoForge 21.1 不识别、静默跳过。伤害显示功能暂缺，待找真正支持 1.21.1 的替代品 |

## 启动验证记录（2026-07-25）

- `./gradlew build`：BUILD SUCCESSFUL。
- `./gradlew runClient`：~10.4s 启动至主菜单（无 quickPlay 配置时停在主菜单属正常）。
- `run/logs/debug.log` 确认 16 个 mod 全部被发现并加载：qianxiang、epicfight、embeddium、lithium、ferritecore、modernfix、saturn、entityculling、smoothboot、dynamic_fps、firstperson、notenoughanimations（+ firstperson 内嵌的 transition/trender 库）。
- 无 `mixin apply failed` / `NoClassDefFoundError` / `ClassNotFoundException` / `Incompatible mod` / FATAL 报错，无崩溃报告。
- latest.log 中仅有的 ERROR 均为无害项：epicfight 音效字幕翻译缺失（dev 环境正常现象）、waveycapes 层文件（EF 自带的 dev-only 提示，官方注明可忽略）。
- 曾移除记录：torohealth-continued（原因见上表）；无其他 mod 因启动失败/冲突被移除。
