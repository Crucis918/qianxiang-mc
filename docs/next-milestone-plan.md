# 牵响 v0.2「闭环」开发计划

> **状态说明（2026-07-27）**：法术系统玩法改造（已学法术存储升级 + 法杖/魔法书增幅器化 + 炼金台卷轴 + 轮盘施法 + 锻造台 25 格）已完成并通过全量测试；详见 `WORKQUEUE.md` 末尾「法术玩法改造」工单。下方正文为历史计划，未回改。

> 本文写给一个**对本项目完全不知情**的实施者（人或 AI）。按顺序读完第 0、1 节即可获得足够上下文；
> 第 2 节是必须先做的修复包；第 3 节是本里程碑的主体；第 4、5 节是后续里程碑的概述。
> 所有文件路径均相对仓库根目录，所有「证据」均为 `文件:行号`，写下时经过实际核对（2026-07-26）。

---

## 0. 给实施者的项目速览

**是什么**：牵响（Qianxiang）是一个 NeoForge **1.21.1** 模组（Java 21，NeoForge 21.1.219）。
核心理念：**材料即零件，AI 当配方大脑，强度靠材料**。玩家把任意物品丢进「锻造台」，
系统根据材料的品阶（PhaseTier）、相性（Phase）、功能算子（PhaseFunction）动态组合出
武器/防具/工具/法术书；可以用自然语言向 AI（Ollama 或 OpenAI 兼容端点）描述需求，
AI 不在线时退关键词兜底配方。另有独立维度「万象森罗」（myriad_wilds）、漫游 Boss
「森罗守望者」、两个价值观相反的 NPC 商人、以及记录玩家生平的「相谱」系统。

**如何跑**：`./gradlew runClient`（开发客户端）、`./gradlew runGameTestServer`（跑全部 GameTest）、
`./gradlew build`（出 jar）。游戏内调试命令：`/qianxiang kit`（领全套材料）、`/qianxiang dim`
（往返维度）、`/qianxiang ai status`（AI 健康度）、`/qianxiang saga`（相谱）。

**代码地图**（`src/main/java/com/qianxiang/`）：

| 包 | 职责 | 核心文件 |
|---|---|---|
| `phase/` | 锻造引擎：材料→属性合成 | `ForgeComposer`（产物仲裁）、`AttributeScheme`（数值）、`PhaseFunction`（25 算子）、`ItemConceptResolver`（原版物品概念推导） |
| `spell/` | 法术引擎：9元素×5形式×5效果×5修饰 | `SpellEffectEngine`（效果实现）、`CustomSpell`（数据模型+白名单）、`SpellCastHandler`（施法入口）、`SpellTickHandler`（mana 回复/同步） |
| `ai/` | AI 配方大脑 | `PhaseAIRecipeService`（prompt+解析）、`FallbackRecipes`（关键词兜底，1087 行）、`AIGateway`（缓存/重试/日志） |
| `entity/` | Boss + 2 NPC + 法术弹体 | `QianxiangMyriadWarden`、`QianxiangWanderingSage`、`QianxiangAbyssMerchant`、`QianxiangNPCBase` |
| `combat/` | 命中效果、代价、动作集 | `CombatEffectHandler`（13 种效果）、`DrawbackHandler`、`WeaponMoveset` |
| `compat/` | Epic Fight 适配 | `QianxiangEFCompat`（真实现，344 行）、`MovesetCompat`（死脚手架，见 P0-6） |
| `blueprint/` | 蓝图分享码 + 全服工坊 | `BlueprintShareCodes`（消毒）、`WorkshopSavedData`（SavedData） |
| `cap/` | 玩家附件数据 | `PlayerSpellData`（mana/冷却）、`PlayerFactionData`（烙印/好感）、`SagaData`（相谱） |
| `network/` | 21 个 payload | `QianxiangPayloads` 注册处 |
| `client/` | 全部 GUI/HUD/渲染 | `ForgeTableScreen`（主界面）、`SpellHudRenderer`、`MovesetEditorScreen` |

数据侧：`src/main/resources/data/qianxiang/` 下有 advancement/story（7 节点成就链）、
worldgen（1 生物群系 + 4 特性）、dimension、loot_table、recipe、phase_materials（数据驱动材料）。
资产侧引用完整性目前是**零缺陷**（39 物品模型齐全、中英 lang 各 677 键完全一致），改动时请保持。

**测试**：30 个 `@GameTest` 分布在 `ItemConceptGameTests.java`（19 个）和
`QianxiangCoreGameTests.java`（11 个），全部复用空场地模板 `qianxiang:item_concept`
（`data/qianxiang/structure/item_concept.nbt`）。CI（`.github/workflows/build.yml`）跑
build + runGameTestServer。**新增功能必须配 GameTest**，照抄现有测试的写法即可。

---

## 1. 现状诊断：为什么下一步是「闭环」

对全仓库的系统扫描结论（各条均有文件级证据，正文引用）：

1. **进程链在终点断掉**。成就树是 7 节点单链（`advancement/story/`），终点
   `legendary_material.json` 的条件是「持有 void_shard 或 reverse_core」——这两样是普通
   挖矿/合成产物，玩家杀 Boss **之前**就必然满足，终点成就形同自动达成。Boss 战后没有任何内容。
2. **守望者不是一场战斗**。`QianxiangMyriadWarden.java` 全文 118 行，没有覆写
   `registerGoals()`（AI 完全继承原版僵尸近战），1 阶段 0 技能，贴图是放大 1.6 倍的原版溺尸；
   掉落的三样东西（void_shard/rift_essence/myriad_fragment）**全部另有来源**，没有独占奖励。
3. **万象森罗是可跳过的空地图**。1 个生物群系（地形直接借 `minecraft:amplified`）、
   0 结构、0 箱子战利品表；且 `recipe/rift_essence.json` 全用原版材料，玩家不进维度也能
   拿到所有材料档位——维度目前是装饰，不是进程阶段。
4. **一个真实的多人安全漏洞**（P0-1，详见下节）。
5. 法术组合矩阵大面积空转、NPC 全硬编码、零自定义音效——这些留给里程碑二/三。

**结论**：v0.2 的目标是把「锻造→远征→击败→更强锻造」这个循环真正闭合：
Boss 值得打、掉落独一无二、维度必须去、打完还有下一步。外加一批必须先处理的修复。

---

## 2. P0 修复包（先做完再开里程碑，预计 0.5~1 天）

### P0-1 【安全】AI 法术上行链路服务端零校验（可一击秒杀任何实体）

**问题**：AI 生成法术的链路是「服务端算 AI → 发客户端 → **客户端回传**选中的 spellJson」。
回传包 `network/SpellJsonReportPayload.java:51` 是三个裸 `STRING_UTF8`；
`SpellJsonReportHandler.java:19-31` 只校验「玩家开着锻造台」就直接
`be.setLastAiSpell(...)`；随后 `ForgeComposer.applyAiSpell`（`ForgeComposer.java:225`）走的
`CustomSpell.fromSpellJson`（`CustomSpell.java:225-264`）**不查 element/form/effect 白名单、
power 只有 `Math.max` 没有上限**。严格校验（白名单 + power 夹 1~10）只存在于
`PhaseAIRecipeService.parseSpellJson`（`:633-685`），而那是服务端本地解析路径，改造客户端可绕过。

**后果**：发 `{"element":"x","form":"y","effect":"damage","power":2147483647}` →
`manaCost = 10 + 5*power` 整数溢出为负 → 绕过 `SpellCastHandler.java:190` 的法力检查 →
`resolveDamage` 的 `dmg = power * 2.0f ≈ 4.3e9`，一击秒杀。`customName` 也无长度上限。

**修法**（项目里已有正确范本：`MovesetApplyHandler.java:55-61` 过白名单、
`BlueprintShareCodes.java:25-26` 双层消毒——照这个思路做）：

1. 在 `CustomSpell` 里新增静态方法 `sanitize(...)` 或直接改 `fromSpellJson`：
   - element 必须 ∈ 9 元素白名单、form ∈ 5、effect ∈ 5（白名单常量已在 `CustomSpell.java:148-155`，直接复用）；不合法**整条拒绝**（返回 empty），与 `parseSpellJson:643` 行为对齐。
   - modifier 逐个过白名单，非法的丢弃。
   - `power` 夹到 `[1, 10]`（`Mth.clamp`）。
   - `customName` 截断到 40 字符以内。
2. 让 `PhaseAIRecipeService.parseSpellJson` 也改为调用同一个 sanitize，消灭双轨。
3. `SpellJsonReportHandler` 入口再加一道：解析失败/拒绝时丢弃并打一条 WARN 日志（含玩家名），不给客户端反馈（作弊者不需要反馈）。

**验收**：新增 GameTest：构造越界 spellJson 字符串调 `CustomSpell.fromSpellJson`，断言返回 empty；
构造 power=999 的合法结构 json，断言产物 power==10。

### P0-2 【一致性】Epic Fight 依赖声明与 README 直接矛盾

**问题**：README 和 `docs/epic-fight-compat-brief.md:244` 都说 EF 是「软依赖，不装不崩」，
但 `src/main/templates/META-INF/neoforge.mods.toml:29-36` 写的是 `type = "required"`，
`build.gradle:84` 用 `implementation`，`Qianxiang.java:37` 无条件注册 `QianxiangEFCompat`。
**现状是硬依赖，不装 EF 直接进不去游戏。**

**修法**（选真软依赖路线，与文档意图一致）：
1. `neoforge.mods.toml` 中 EF 依赖改 `type = "optional"`。
2. `Qianxiang.java` 注册 EF 监听前加 `if (ModList.get().isLoaded("epicfight"))` 守卫；
   `QianxiangEFCompat` 里所有 `yesman.epicfight.*` 的 import 必须只在该守卫内被类加载
   （现有类结构已把 EF 引用集中在 `QianxiangEFCompat` 一个类里，守卫放在「引用该类」的位置即可，不要在 `Qianxiang.java` 顶部 import 它的任何 EF 类型）。
3. `build.gradle` 保持 `implementation`（dev 环境要跑 EF），但确认 jar 不 shade EF。
4. 手工验证两次启动：装 EF、不装 EF（把 dev runs 的 EF 依赖临时注释）都能进世界、能锻造。

### P0-3 【体验+核心假设】实测 EF 战斗下「强度靠材料」是否失效

**问题**：`docs/epic-fight-compat-brief.md:263-268`（未确认项 #2）、`docs/capabilities-readme.md:48-66`、
`CombatEffectHandler.java:50` 三处独立记录同一风险：EF 接管战斗时，物品自带的
`ATTRIBUTE_MODIFIERS` 可能被 EF capability **覆盖**而非叠加——若为真，不同材料锻出的武器
攻击力毫无差异，整个锻造系统的存在理由被抹平。从未实测。

**修法**：`runClient` 装 EF，锻两把攻击力差异极大的武器（例：纯朽木 vs 传奇材料堆满），
分别对训练假人（或猪）普攻，用 `ClientDamageNumbers` 浮字读数对比。
- 若有差异 → 在 `docs/epic-fight-compat-brief.md` §8 把未确认项 #2 划掉并记录实测数据。
- 若无差异 → 按 `docs/capabilities-readme.md:60` 的 v2 方向，在 `QianxiangEFCompat` 构建
  capability 时把 `ComposedAttributes` 镜像进 EF 的 attribute 体系（EF 的
  `WeaponCapability.Builder` 支持 `addStyleAttributes`）。这是本项目的灵魂，值得花时间。

### P0-4 【清理】删除锻造台 GUI 的调试栈打印

`ForgeTableScreen.onClose`（`ForgeTableScreen.java:449,453` 附近）和
`ForgeTableMenu.removed`（`ForgeTableMenu.java:207` 附近）里有主动 `new Throwable()` 打调用栈的
调试日志（最近一次运行日志里刷了 22 条完整栈）。整段删除。

### P0-5 【文案】`assets/qianxiang/lang/en_us.json:430` 英文值混入中文

`"...if you have the筹码."` → 改为 `"...if you have the chips."`（全库唯一一处 CJK 污染，改完后中英键集仍须保持 677 键一致）。

### P0-6 【删码】`compat/MovesetCompat.java` 是 231 行死脚手架

它整套反射机制（猜 4 个包名、反射取字段、递归扒泛型签名）服务于注释里「动作集子代理尚未合并」
（`:198`）这个**已不存在的前提**——`combat/WeaponMoveset`、`QianxiangDataComponents.CUSTOM_MOVESET`
早已在同一仓库，`QianxiangEFCompat:124` 已在做类型化直取。删除该类及其调用点，统一走类型化路径。
顺手统一两条链路的语义分裂：AI 链路限 4 段+去重、编辑器链路 6 段允许重复
（`MovesetApplyHandler:26-29` 注释自己承认不一致）——统一为 **6 段、允许重复**（编辑器语义）。

### P0-7 【体验】AI 端点不可达时的体验劣化

最近一次运行日志里 AI(openai) 连接超时十余次，每次都等满 5 秒超时才退兜底。
在 `AIGateway` 加**熔断**：连续 3 次连接失败后，接下来 60 秒内的请求直接走兜底（不发起 HTTP），
并在锻造台 AI 面板显示一行「AI 离线，使用关键词配方」状态（复用现有 `/qianxiang ai status` 的
健康计数即可判断）。熔断窗口结束后放一个探测请求。

### P0-8 【产物缺口】护腿锻不出来 + 5 个防具算子落错产物

- `PHASE_LEGGINGS` 已注册但 `ForgeComposer.pickArchetype`（`ForgeComposer.java:287-310`）永远不会产出它（`QianxiangItems.java:67` 注释自认）。
- `JUMP_BOOST/FIRE_RESIST/WATER_BREATH/REGENERATION/DEFENSE` 五个算子在 `PhaseFunction.java:42-46` 标为 ARMOR 用途，但 `pickArchetype` 防具分支只认 `NIGHT_VISION/RESISTANCE/SPEED_BOOST`，其余全落到盾——玩家要「抗火靴」拿到的是盾。

**修法**：扩展 `pickArchetype` 防具分支的算子→槽位映射：

| 算子 | 产物 |
|---|---|
| NIGHT_VISION、WATER_BREATH | 头盔（已有/新增） |
| RESISTANCE、FIRE_RESIST | 胸甲（已有/新增） |
| REGENERATION、DEFENSE | **护腿**（新增，救活死物品） |
| SPEED_BOOST、JUMP_BOOST | 靴子（已有/新增） |
| 无以上算子但有 BASE_HIDE | 盾（维持现状） |

**验收**：GameTest ×4——分别放入含 WATER_BREATH/FIRE_RESIST/REGENERATION/JUMP_BOOST 算子的材料组合，断言产物 item 分别为头盔/胸甲/护腿/靴子。

---

## 3. 里程碑一：终局闭环（本计划主体，预计 3~5 天）

目标一句话：**杀守望者变成一场真正的 Boss 战，它掉全游戏唯一的传奇核心，
用核心锻出传奇装备是新的成就终点；维度里出现值得探索的遗迹。**

### 3.1 森罗守望者 Boss 重做

改 `entity/QianxiangMyriadWarden.java`（现 118 行，Zombie 子类，保持继承关系不变，避免动渲染器）。
全部技能用**服务端逻辑 + 原版粒子/音效**实现，不需要新动画、不依赖 EF。

**基础数值调整**：HP 120→**200**（`QianxiangEntityAttributes`），攻击 12 维持，
新增 `setPersistenceRequired()`（防 despawn 丢 Boss）。

**三阶段设计**（在 `aiStep()`/`tick()` 里按 `getHealth()/getMaxHealth()` 判定，
用一个 `int phase` 字段防止重复触发转阶段特效）：

- **阶段一（100%~60%）「威压」**：原版近战之外，每 160 tick（8 秒）释放**裂隙震荡**：
  以自身为中心半径 5 格，对所有非同类 LivingEntity 造成 8 点魔法伤害 + 水平击退 1.2；
  释放前 20 tick 播放预警（`ParticleTypes.SONIC_BOOM` 环形 + `SoundEvents.WARDEN_ROAR`，
  原版音效直接用，本项目暂无自定义音效）。实现为一个字段计时器即可，不必写 Goal。
- **阶段二（60%~30%）「裂地」**：新增两个能力，与震荡计时器并行：
  1. **虚空尖刺**：每 100 tick 在当前仇恨目标脚下标记 3×3 区域，20 tick 预警
     （`ParticleTypes.SCULK_SOUL` 上涌）后爆发：区域内实体受 10 点伤害 + 缓慢 II 3 秒。
     用一个 `List<PendingSpike>(pos, triggerTick)` 字段管理，`tick()` 里结算。
  2. **召唤余响**：转入阶段二瞬间 + 之后每 300 tick，召唤 2 只「裂隙余响」——
     直接 spawn 原版 Zombie，`setCustomName("裂隙余响")`、加速度 buff、
     `addTag("qianxiang_echo")`，Boss 死亡时把带该 tag 的余响全部 discard（防刷怪残留）。
     不新增实体类型，零资产成本。
- **阶段三（<30%）「狂怒」**：转入瞬间全身 `ParticleTypes.FLASH` + `WARDEN_SONIC_BOOM` 音效；
  攻击间隔缩短（给自己上无限时长隐藏的 Speed I + 攻速通过 `ATTACK_SPEED` attribute modifier +30%）；
  新增**裂隙步**：若与仇恨目标距离 > 6 格持续 40 tick，闪现到目标身后 2 格
  （`teleportTo` + `ENDERMAN_TELEPORT` 音效 + 传送前后两团 `PORTAL` 粒子）。

**其他**：
- Boss 血条（已有紫色 bossEvent）在转阶段时改名：「森罗守望者」→「·裂地·」→「·狂怒·」后缀，给玩家阶段感知。lang 加 `entity.qianxiang.myriad_warden.phase2/3` 中英键。
- 免疫列表（现悬浮/中毒）追加凋零，防止毒杀流跳过战斗强度。

**验收**：GameTest 至少 2 个——①spawn 守望者并把血量 setHealth 到 50%，跑 200 tick，
断言世界里出现了带 `qianxiang_echo` tag 的实体；②血量设到 20%，断言其
`ATTACK_SPEED` modifier 已生效。手测：`/qianxiang kit` + `/qianxiang dim` 全程打一遍，三阶段表现符合上述描述。

### 3.2 独占掉落「森罗之核」与传奇锻造

1. **新物品 `warden_core`（森罗之核）**：在 `QianxiangItems` 注册（照抄 `rift_essence` 的注册写法），
   稀有度 EPIC。需要新贴图 `textures/item/warden_core.png`（16×16，紫黑基调裂隙核心；
   若实施者无法作画，可先用 `rift_essence.png` 改色作占位，并在 PR 描述里注明）。
   模型/lang（中英各 1 键）/创造标签一并补齐——**保持资产零缺陷的现状**。
2. **相数据**：`data/qianxiang/phase_materials/warden_core.json`——tier 定为 LEGENDARY，
   算子给 `MANA` + `RESISTANCE` + 一个攻击向算子（参考现有 phase_materials json 的 schema，
   目录下有现成例子）。设计意图：它是「万能传奇催化剂」，加进任何配方都显著提升产物。
3. **掉落**：`loot_table/entities/myriad_warden.json` 新增第 4 个池：`warden_core` ×1，100%，
   不受 looting 影响（防刷）。**全游戏唯一来源**——不得加入任何配方/商人条目。
4. **兜底关键词配方顺手修**：`FallbackRecipes.java:705-761` 把 `case LEGENDARY` 8 处全部映射成
   `rift_essence`（传奇档毫无多样性）。把其中攻击向/法术向的 4 处改为优先选
   `warden_core`（材料库里有才选，没有退回 rift_essence——`MaterialLibrary` 有物品存在性查询）。

### 3.3 成就树延长（把断掉的终点接上）

改 `data/qianxiang/advancement/story/`：

1. **`legendary_material.json` 条件改掉**：从「持有 void_shard/reverse_core」改为
   「持有 `qianxiang:warden_core`」。这一改自动修复「终点在杀 Boss 前自动达成」的问题。
2. **新增终点 `forge_legendary.json`**：parent 为 legendary_material，challenge 类型，100 经验。
   条件：锻出一件含森罗之核的产物。实现：现有 `first_forge.json:12-17` 用的是物品触发器，
   而「产物含核」需要自定义 criterion 或退而求其次——**推荐简单路线**：在 `ForgeComposer`
   完成锻造处（有 ServerPlayer 上下文）检测材料含 `warden_core` 时直接
   `AdvancementHolder` 授予（项目里 `MyriadWildsPortalHandler` 有服务端主动授予成就的现成写法可抄）。
3. lang 补 4 键（新成就标题+描述 × 中英）。

**验收**：手测全链路：新世界 → kit → 锻造 → 进维度 → 杀守望者（此时才弹 legendary_material）→
用核锻造（弹 forge_legendary）。确认每一步成就按顺序弹出、无提前达成。

### 3.4 万象森罗维度充实：裂隙遗迹 + 群系分化

**A. 裂隙遗迹（有战利品的探索点）**——采用**自定义 Java Feature** 路线（项目已有
`rift_spire` 特性先例，且比 jigsaw/NBT 结构管线简单可靠、可控性强）：

1. 新建 `worldgen/RiftRuinFeature.java`（`Feature<NoneFeatureConfiguration>` 子类，注册方式
   参考现有 feature 的注册处）。程序化生成一座 7×7 左右的破损石制小祭坛：
   深板岩瓦+裂纹石砖混合墙体（用 `RandomSource` 做 30% 缺损）、中央一个
   `minecraft:chest`，放置后通过 `RandomizableContainer.setBlockEntityLootTable`
   挂战利品表 `qianxiang:chests/rift_ruin`。
2. 新建 `data/qianxiang/loot_table/chests/rift_ruin.json`（**这是全项目第一张箱子战利品表**）：
   2~4 抽——rift_essence（权重 30）、myriad_fragment 2~5（25）、void_shard 1~2（20）、
   reverse_core（10）、绿宝石 3~8（10）、附魔金苹果（5）。**不放 warden_core**（独占性）。
3. configured_feature + placed_feature json：稀疏放置（`rarity_filter` chance 约 48，
   heightmap WORLD_SURFACE_WG），加入 `biome/myriad_wilds.json` 的 features 列表
   （参照 rift_spire 所在的 step）。
4. **验收**：GameTest——在测试场地直接调 `RiftRuinFeature.place(...)`，断言范围内存在
   带 loot table 的箱子方块实体。手测：`/qianxiang dim` 后创造飞行找到至少 2 座遗迹。

**B. 群系分化（1 → 3）**：维度现在用 `minecraft:fixed` biome source
（`dimension/myriad_wilds.json:5-9`）。改为 `minecraft:multi_noise`，新增两个群系 json
（复制 `myriad_wilds.json` 改参数）：
- `myriad_wilds`（现有，中性）：参数占据 temperature 中段。
- `myriad_scorch`（灼界）：暖色雾/天空色，地表刷 magma 相关特性，怪物池偏烈焰系（blaze 加入），
  void_ore 权重更高。
- `myriad_gloom`（幽界）：深蓝紫雾，glimmer_tree 密度翻倍，怪物池偏潜影/蠹虫，
  wildlight_patch 更多。
三个群系的 multi_noise 参数只需在 temperature 轴上错开（-1~-0.3 / -0.3~0.3 / 0.3~1）即可，
不必调 humidity 等其他轴。**注意**：两个新群系的 lang 键（`biome.qianxiang.*`）中英都要补。

**C. 维度不可跳过**：`recipe/rift_essence.json` 目前用末影珍珠+紫水晶+荧石粉即可在主世界合成，
这条**保留**（它是入场券，本来就该在主世界能做）；真正的门槛由 3.2/3.3 完成——传奇终点
只能靠维度里的 Boss。无需额外改动，此处只是说明设计意图，防止实施者误删该配方。

### 3.5 音效低成本补课（可选，1 小时内）

全项目零自定义音效，但**复用原版音效零成本**：Boss 技能已在 3.1 指定原版音效；
另在锻造成功处（`ForgeComposer` 出产物后的方块实体上下文）播 `SoundEvents.ANVIL_USE` +
`PLAYER_LEVELUP`（音量 0.6），传送门使用处播 `PORTAL_TRIGGER`。自定义 ogg 留给以后。

---

## 4. 里程碑二概述：法术反馈与填格（下一个周期再做，先不动工)

> **状态说明（2026-07-27）**：法术填格/反馈层/伤害对标已于本轮完成——debuff 九元素不再塌缩、
> utility 空转格已补全（含 blood 血换蓝）、heal/buff 无效目标已有退款+提示、单发伤害对标同档武器
> （power×3.0）、每元素专属施法音/命中音、弹体轨迹加密、冷却条 HUD 与轮盘均已落地。
> 召唤物/学派成长等更大方向仍留待后续。下方为原始扫描记录，未回改。

扫描发现的核心问题，供排期参考：
- 「9×5×5×5」宣传矩阵实际大面积空转：`debuff` 的 9 元素全塌缩成 WEAKNESS+SLOWDOWN
  （`SpellEffectEngine.java:224-229`）；`utility` 有 7 个元素纯粒子（`:324-328`）；
  `damage` 的 nature/holy/arcane 无元素附加（`:277`）；heal/buff 对怪物施放时
  **扣了法力却无事发生也无提示**（`isAlly:367-372`）。
- 修饰词作用面窄：homing/piercing 只对弹体形式生效，chain 只在雷电+伤害组合下生效。
- 反馈层缺失：HUD 只有一行文字法力值（`SpellHudRenderer.java:58-69`），
  **冷却完全无显示**且 `SpellDataSyncPayload` 不同步 cooldown（`SpellCastHandler.java:215`）。
- 死代码：`spell/Spell.java` 旧硬编码 4 法术双轨（无人写入 `SPELL` 组件，仅 3 处读，纯存档兼容）。

建议顺序：先做反馈层（蓝条 HUD + 冷却环 + 「无效目标」提示且不扣蓝），再按元素填格子，
每格配一条 GameTest。

## 5. 里程碑三概述：活的世界（NPC/位格）

> 进展注记（2026-07-28）：熟练度技能树（自由加点 27 节点 / 流浪相师潜行右键开启与洗点）
> 已于本轮落地（见 WORKQUEUE WQ-62）；NPC 对话树扩充仍留待本里程碑。

- NPC 交易表从 Java 硬编码（`QianxiangWanderingSage.java:27-47` 等）迁到 datapack json，
  支持整合包自定义；补货时间戳落盘（`QianxiangNPCBase.java:43-44` 现在明确不落盘）。
- 流浪相师按文档所述在主世界游荡：补 `data/neoforge/biome_modifier/` json（现在完全没有，
  文档与实现不符，`QianxiangWanderingSage.java:16`）。
- 位格系统接线：`SagaData.withPosition()` 目前零调用者（`SagaData.java:13` 声明它是探索门控）——
  让高位格解锁遗迹深层/特殊交易。
- 对话树扩充（现每 NPC 3 句）。

## 6. 完成定义（DoD）

1. `./gradlew build && ./gradlew runGameTestServer` 全绿（含本计划新增的 ≥8 个 GameTest）。
2. 装/不装 Epic Fight 都能启动进世界（P0-2）。
3. 手测全进程链（3.3 验收步骤）通过，成就顺序正确。
4. 资产完整性保持零缺陷：新增物品/群系/成就的模型、贴图、中英 lang 键全部齐备
   （中英键集数量一致），无孤儿引用。
5. P0-1 的越界 spellJson 在 GameTest 中被拒绝。
6. 更新 README「核心玩法」第 4 条（维度描述加遗迹/群系）与 Epic Fight 依赖表述；
   在 `docs/epic-fight-compat-brief.md` §8 记录 P0-3 的实测结论。

## 7. 明确不做的事（防跑偏）

- 不新增自定义实体模型/贴图/动画（Boss 继续用放大溺尸，本里程碑靠机制不靠美术）。
- 不做自定义 ogg 音效、不做自定义天空渲染。
- 不动 AI prompt 主体结构（`PhaseAIRecipeService` 的 prompt 拼接保持现状）。
- 不做 jigsaw/NBT 结构管线（遗迹走程序化 Feature）。
- 不重构 `spell/Spell.java` 旧双轨（留到里程碑二一起清）。
- 工作目录里有一处未提交改动（`AttributeScheme.java` 的注释更新，无害），实施前先
  `git add -A && git commit` 或纳入首个提交，保持工作区干净。
