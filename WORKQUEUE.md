# 牵响 协作工单队列

> **协议**：本文件由「侦察会话」（找 bug/出方案）维护，「修理会话」（写代码）消费。
> 修理会话领活时把状态从 `[ ]` 改为 `[~] 修理中`，完成后改 `[x] 完成(commit哈希)` 并保留原文。
> 除状态行外不要改动工单正文；有异议在工单末尾加「修理备注：」。
> 背景总纲见 `docs/next-milestone-plan.md`（其中 P0-1/4/5/7/8 已完成于 e3b66f9，勿重做；
> P0-3 调查已定论，只剩文档收尾=本队列 WQ-5）。
> 每单都写了验收标准；完成必须过 `./gradlew compileJava` + `./gradlew runGameTestServer` 全绿。

---

## WQ-1 [ ] 【高】法术 power 上限改为材料预算（"强度靠材料"的法术轨缺口）

**问题**：`phase/ForgeComposer.applyAiSpell` 用 `fromSpellJson(spellJson, maxTier + 1)`——材料
最高档位只决定 power **下限**；上限是 `CustomSpell.MAX_POWER = 10` 全局定死。垃圾材料 +
AI/客户端报 power=10 完全合法，法术强度与材料脱钩，材料经济被击穿。

**修法**：
1. `CustomSpell.fromSpellJson` 增加 maxPower 参数（保留旧签名重载=默认 10，兼容蓝图路径），
   钳制改为 `Mth.clamp(power, minPower, maxPower)`。
2. `applyAiSpell` 里 `maxPower = 2 + 2 * maxTier序数`（COMMON=4, UNCOMMON=6, RARE=8, EPIC=9, LEGENDARY=10；
   具体档位枚举看 `phase/PhaseTier`，若是 4 档制则改为 4/6/8/10）。
3. `ai/PhaseAIRecipeService` 的 prompt 强度段告知 AI 本次预算上限（找 appendFreeEffectSection/
   强度代价段追加一句），让 AI 在预算内塑形。
4. GameTest：COMMON 材料组合 + power=10 的 spellJson 锻造，断言产物 power<=4。

**验收**：新测试通过；现有 `spellJsonClampsOversizedPower`（上限语境=默认 10）仍绿。

## WQ-2 [ ] 【高】AI 提案信任边界重构：客户端只回传索引

**问题**：服务端算 AI → 发客户端 → 客户端回传**spellJson 全文**（`network/SpellJsonReportPayload`，
`movesetJson` 同理）。服务端不留提案副本，无法区分"选了方案二"和"自己编了合法 JSON"——
配合 WQ-1 之前的状态就是自助作弊口。另外 `block/ForgeTableBlockEntity` 的
`lastSpellJson/lastAiMoveset` 是方块级单暂存，多人同用一台锻造台互相串味。

**修法**：
1. 服务端生成 AI 提案时（`ai/ForgeTableAIHandler` 回主线程处），把提案列表存进
   `ForgeTableBlockEntity` 的 `Map<UUID, List<AiProposal>>`（AiProposal=spellJson+movesetJson+customName；
   不落盘，容器关闭/方块卸载清理该玩家条目）。
2. `SpellJsonReportPayload` 改为只携带 `int proposalIndex`（或 -1=不用 AI 结果）；
   handler 按发包玩家 UUID 查表取回真身。`AiResponsePayload`（S2C）需带上索引。
3. 客户端 `client/ClientForgeTableAI` 相应改为记索引。
4. 锻造读取处（`menu/ForgeTableMenu` / `ForgeComposer` 调用点）改为按操作玩家 UUID 取提案。
5. 网络协议变更注意 `network/QianxiangPayloads` 里的 StreamCodec 同步改。

**验收**：GameTest 覆盖"未收到提案的玩家回传索引 0 → 拒绝"；手测单人 AI 锻造全流程无回归。
WQ-1 先做可独立上线，本单动协议建议单独一个 commit。

## WQ-3 [ ] 【高】P0-2：Epic Fight 改真软依赖（现在不装 EF 进不了游戏）

`src/main/templates/META-INF/neoforge.mods.toml` EF 依赖 `type="required"` → `"optional"`；
`Qianxiang.java:37` 附近对 `QianxiangEFCompat::register` 的监听注册加
`ModList.get().isLoaded("epicfight")` 守卫，且 `Qianxiang.java` 不得 import 任何 `yesman.epicfight.*`
类型（EF 引用已隔离在 `compat/QianxiangEFCompat` 一个类里，靠惰性类加载保护）。
`combat/AnimationLibrary`、`compat/` 其他类若有 EF import 一并检查隔离。
**验收**：装 EF 启动 + 临时注释 dev runs 的 EF 依赖再启动，均能进世界、锻造、施法。README 的
"软依赖不装不崩"从此为真。

## WQ-4 [ ] 【中】P0-6：删除 compat/MovesetCompat.java 死脚手架 + 动作集语义统一

231 行反射机制（猜包名/反射取字段/扒泛型签名）服务于"动作集子代理尚未合并"这个已不成立的
前提（`MovesetCompat.java:198` 注释）。删类及全部调用点，统一走 `QianxiangEFCompat` 的类型化
直取。同时统一语义分裂：AI 链路"4 段+去重" vs 编辑器链路"6 段允许重复"
（`network/MovesetApplyHandler:26-29` 注释自认）→ 统一为 **6 段、允许重复**。
**验收**：编译绿；grep 无 MovesetCompat 残留引用；动作编辑器手测能应用 6 段含重复动作。

## WQ-5 [ ] 【中】P0-3 收尾：三处文档/注释更新（纯文档，结论已定，照抄即可）

EF 21.15.6 字节码分析定论：**EF 不覆盖物品 attribute_modifiers，只叠加**。
1. `docs/epic-fight-compat-brief.md` §8 未确认项 #2 替换为以下结论：
   > **2.（已定论：叠加，2026-07 对 21.15.6 字节码核实）** EF 不覆盖物品栈的
   > `minecraft:attribute_modifiers` 组件，材料强度差异在 EF 战斗下完整生效。证据：
   > ① EF 唯一挂进 vanilla 属性管线的钩子 `NeoForgeEntityEvent.epicfight$itemAttributeModifier`
   > 经 `VanillaItemEventHooks.onModifyItemAttribute` 只调用 `ItemAttributeModifierEvent.addModifier`，
   > 全 jar 无 `replaceModifier`/`clearModifiers`；且本版本 `CapabilityItem.getAttributeModifiers(null)`
   > 返回空 multimap，该钩子实为空操作。② `CapabilityItem.getAttributeModifiersAsWeapon` 先读
   > `ItemStack.getAttributeModifiers()`（栈组件）再追加 capability 的 style attributes。
   > ③ EF 主手攻击 `PlayerPatch.attack` 最终 `invokevirtual Player.attack`，伤害仍由玩家
   > `ATTACK_DAMAGE` 属性（含组件修饰符）结算；`setOffhandDamage` 对主手直接 return。
   > ④ 副手双持换入的修饰符列表同样由 `getAttributeModifiersAsWeapon` 生成。
   > 推论：**不要**把 ComposedAttributes 镜像进 EF 的 `addStyleAttibutes`（EF 方法名拼写如此），
   > 否则副手路径与 EF 武器面板双重计数；千相 preset 仅带 impact/armor_negation 的现状即正确形态。
   > 升级 EF 版本后的冒烟验证：/give 两把 attribute_modifiers 差异大的 ember_blade 各打盔甲架对比掉血。
2. `docs/capabilities-readme.md` 48-66 行"EF 接管战斗时原版 MC 攻击路径被旁路"的表述改正
   （攻击最终走 `Player.attack`，未被旁路）。
3. `combat/CombatEffectHandler.java:50` 的 `<h3>Epic Fight 兼容性(TODO,需统筹实测)</h3>`
   javadoc 改为已确认说明（`LivingDamageEvent.Post` 正常触发、attacker 是玩家）。

## WQ-6 [ ] 【高】森罗之核：Boss 独占掉落 + 成就终点闭环

前置：先 `git show a9b964b 467477f` 核对并行改动——Boss 已有冲击波/追踪弹/半血狂化，
成就树已扩三条支线；**但 Boss 仍无独占掉落**。按 `docs/next-milestone-plan.md` §3.2/3.3 实施：
1. 注册物品 `warden_core`（EPIC 稀有度；贴图可先复制 `textures/item/rift_essence.png` 改紫黑色调
   占位并在 commit 信息注明）；模型/中英 lang 键/创造标签齐备（保持资产零缺陷：现中英各 677+ 键一致）。
2. `data/qianxiang/phase_materials/warden_core.json`：LEGENDARY 档，算子 MANA+RESISTANCE+一个
   攻击向算子（schema 抄同目录现有文件）。
3. `loot_table/entities/myriad_warden.json` 加第 4 池：`warden_core` ×1、100%、**不受抢夺加成**。
   全游戏唯一来源——禁止加进任何配方/商人条目。
4. 核对 `advancement/story/legendary_material.json` 现条件；改为「持有 warden_core」。
5. 新增终点成就 `forge_legendary`（parent=legendary_material，challenge，100xp）：锻造材料含
   warden_core 时服务端主动授予——在 `ForgeComposer` 锻造成功处检测，授予写法抄
   `handler/MyriadWildsPortalHandler`（并行会话 a2095f9 已在里面加过授予逻辑，或看
   `QianxiangAdvancements.java`）。
6. `FallbackRecipes` 的 `case LEGENDARY` 8 处全映射 rift_essence——把攻击/法术向的 4 处改为
   材料库存在 warden_core 时优先选它。
**验收**：GameTest：击杀守望者掉落表包含 warden_core；含核锻造授予 forge_legendary。
手测全进程链成就顺序正确（新世界→kit→锻造→进维度→杀 Boss→才弹 legendary_material→用核锻造弹终点）。

## WQ-7 [ ] 【中】组件与分享码加版本号 + 迁移注册表

`CUSTOM_SPELL`/`CUSTOM_MOVESET`/`SPELLBOOK` 组件和蓝图分享码（`blueprint/BlueprintShareCodes`）
均无版本字段；schema 已发生过一次演化（spellJson 旧词折算）。趁字段还少：
1. 分享码 payload 加 `"v": 1`；解码时缺省视为 v0 走现有旧词折算，未知高版本给出
   「请更新模组」的聊天提示而不是静默失败。
2. 组件 codec 加 optional int version（缺省 0），并建一个 `SpellJsonMigrations` 静态注册表
   （v0→v1 就是现有 normalizeLegacyWords 逻辑的搬家）。
**验收**：旧格式分享码（不带 v 字段）仍能导入；GameTest 覆盖 v0 码 roundtrip。

## WQ-8 [ ] 【低·快赢】heal/buff 打怪物：扣蓝无反馈

`spell/SpellEffectEngine.resolveHit`（约 212-223 行）要求 isAlly，heal/buff 的 projectile/beam/touch
形式对怪物命中时什么都不发生，但法力已扣、冷却已进、无任何提示。修法：命中非友方时
退回法力（或干脆不进冷却）+ actionbar 提示「该法术只能作用于友方」（lang 键中英各 1）。
**验收**：手测 heal 弹体打僵尸出提示且蓝退回。

## WQ-9 [ ] 【中】法术冷却同步 + HUD 显示 + 同步风暴修复

三件事同一批文件一起修：
1. **同步风暴（先修）**：`SpellTickHandler:30-40` 的发包条件是 `next != data`，而
   `tickCooldowns()` 在冷却非空时每 tick 返回新实例 → **冷却期间每 tick 一个同步包**
   （20 包/秒/玩家），payload 却只带 mana 纯噪音。条件改为比较 currentMana/maxMana 实值。
2. **冷却同步+HUD**：`SpellDataSyncPayload` 加当前法术剩余冷却 tick；`SpellHudRenderer`
   在法力行下画 80×4 冷却条（灰底白条），满冷却不画。
3. **维度切换 desync**：全仓库无 `PlayerChangedDimensionEvent` 订阅，过门后客户端 attachment
   回落 empty(100/100) 虚高半秒。补一个事件订阅调 `SpellCastHandler.sync`。
**验收**：施法后 HUD 冷却条倒数消失；冷却期间抓包/日志确认不再每 tick 发包；过维度门 HUD 不闪错值。

---

# 第二批：bug 狩猎发现（2026-07-26 深夜，侦察代理实证核查过触发路径）

## WQ-10 [~] 修理中 【高·刷物品】漏斗接锻造台 = 零成本无限锻造

产物槽（槽 10）是**真实容器槽**：`ForgeTableMenu.slotsChanged` 直接
`container.setItem(RESULT_SLOT, composition.result())`，`removed()`（:260-263）只调 super
**不清产物槽**，还随 `saveAdditional` 落盘。`ForgeTableBlockEntity` 实现的是 `Container` 而非
`WorldlyContainer`，无 `canPlaceItem/getSlotsForFace`——漏斗对 0-10 全槽自由读写。
**触发**：放 10 材料开一次 GUI（产物生成、材料未耗）→ 关 GUI → 底下接漏斗：先抽走 10 个
材料再抽走产物，材料零消耗白得成品，无限重复。反向漏斗还能往槽 10 塞东西被 slotsChanged
静默覆盖删除。
**修法**：BE 实现 `WorldlyContainer`，`getSlotsForFace` 只暴露 0-9、产物槽禁抽禁塞；
`ForgeTableMenu.removed()` 清空 RESULT_SLOT（产物改纯预览、onTake 才实体化）。
**验收**：GameTest：漏斗放锻造台下抽不到产物槽；关 GUI 后 BE 槽 10 为空。

## WQ-11 [~] 修理中 【高·刷物品】蓝图放料"洗物品"：残耐久附魔装备变全新白板 + 可扒身上盔甲

`ForgeTableMenu.applyBlueprint`（:92-109）找材料只比 `s.is(item)` 忽略组件，取用后放入的是
`new ItemStack(item, 1)` **出厂新栈**；且扫描范围是 `playerInventory.getContainerSize()`=41，
**含 4 护甲槽和副手**。
**触发 A**：蓝图材料含钻石镐 → 背包放 1 耐久附魔镐 → 用蓝图 → 材料槽出现满耐久无附魔
新镐，Shift 取回（免费修复+洗附魔）。**触发 B**：蓝图材料含头盔 → 直接扒走玩家头上戴的。
**修法**：改为搬运原栈 `taken.split(1)`；扫描范围限 `Inventory.INVENTORY_SIZE`（36）。
同文件 `AiPlaceMaterialsHandler.findInInventory`（:88-94）也是 41 格扫描（那条路径保留组件，
危害仅"拿走身上装备当零件"），一并限 36。
**验收**：GameTest：低耐久物品走蓝图放料后组件保留；护甲槽物品不被取用。

## WQ-12 [~] 修理中 【高·吞物品】挖掉锻造台吞掉全部 11 格内容

`ForgeTableBlock` 无 `onRemove` 覆写，破坏方块时 BE 连同物品直接销毁。
**修法**：覆写 `onRemove`，`!state.is(newState.getBlock())` 时 `Containers.dropContents` 再 super。
**验收**：GameTest：放材料后破坏方块，断言掉落物包含材料。

## WQ-13 [ ] 【中·经济】声望折扣错误作用于收购单的 costA——高声望卖货投入减半

`QianxiangNPCBase.applyReputationPricing`（:152-168）对**每条** offer 的 costA 打折，但收购型
offer（碎片×2→5 绿宝石）的 costA 是玩家交出的货。声望 25% 时交货量 2→1，收益翻倍；
屠杀烙印反向双重惩罚。**修法**：只对 `getBaseCostA().is(Items.EMERALD)` 的售出型条目写
specialPriceDiff。（压到 0 白嫖不成立，已核实三重下限保险，勿改动那部分。）

## WQ-14 [ ] 【中·NPC】NPC 继承整套 Villager 大脑：会转职/被原版补货绕过/被僵尸转化消灭

`QianxiangNPCBase extends Villager` 未锁职业未剪 brain：①附近有讲台等 job-site 会转职，
`updateTrades` 是 append——8 条千相交易后面接原版职业交易；②转职后 brain 的 WorkAtPoi 每日
`restock()` 绕过 6eb1a04 的时间戳闸门（那闸门只在开 GUI 时查）；③僵尸打死千相 NPC 变原版
僵尸村民，治愈后 NPC 永久变原版村民。
**修法**：构造器锁 NITWIT 职业 + 覆写 `updateTrades()` 为空 + brain 去掉 job-site/work 活动；
僵尸转化需拦 `Zombie.killedEntity` 路径（覆写 die 或事件取消转化）。
**验收**：手测放讲台 NPC 不转职；僵尸杀 NPC 不出僵尸村民。

## WQ-15 [ ] 【中·卡死】AI 解析中关 GUI → 锻造台永久卡 STATE_PARSING（落盘持久）+ 线程池泄漏

`ForgeTableAIHandler` 复位依赖"玩家此刻仍开着菜单"（:56-68），关 GUI/下线后回包直接 return；
`tickServer` 只对 STATE_COMPLETE 倒计时，PARSING 无超时；状态还落盘。玩家中途开了另一台
锻造台还会把复位打到错的方块上。另外 `AI_EXECUTOR`（:23）static 永不 shutdown：单人游戏
AI 请求进行中退出世界时 `enqueueWork` 提交到已停机 server，lambda 持有
IPayloadContext→ServerPlayer→ServerLevel 整个对象图，反复进出世界叠加泄漏。
**修法**：①AI 任务捕获 `BlockPos`+维度 key，回调按坐标定位 BE 复位（不经 containerMenu）；
②`tickServer` 给 PARSING 加超时（200~600 tick）自动回 idle；③`loadAdditional` 把读到的
PARSING 归一为 IDLE；④监听 `ServerStoppingEvent` 对 executor `shutdownNow` 并丢弃队列。
**验收**：发起 AI 后立即关 GUI，超时后方块状态自动复位；重开存档无残留 PARSING 粒子。

## WQ-16 [ ] 【中·落点】传送门落点含流体高度图——amplified 地形可直接落岩浆/海面

`MyriadWildsPortalHandler:98-105` 用 `MOTION_BLOCKING_NO_LEAVES`（流体计入），落点无安全复检；
y<=min+1 时兜底 y=100 不查是否闷在石头里；`getHeightmapPos` 未生成区块时主线程同步生成
（amplified 很慢，可感知卡服）。
**修法**：`WORLD_SURFACE` 取高后向上扫两格可站立且脚下非流体的位置，找不到建 3×3 黑曜石基座。
**验收**：手测传送到海洋/岩浆湖坐标不落液体。

## WQ-17 [ ] 【低·反馈】蓝图使用失败也写相谱 + Blueprint 包无限流

`BlueprintServerHandler:79-81` 的 `withEntry` 在 `if (ok)` 之外——缺材料连点"使用"每次都写一条
相谱（500 上限会被垃圾挤掉真历史），且 handler 无冷却可被刷。
**修法**：`withEntry` 移进 ok 分支；handleUse/handleSave 加每玩家 ~10 tick 冷却。

## WQ-18 [ ] 【低·一致性】AI 放料同种材料堆同一槽 → 零件数少于 AI 承诺

`AiPlaceMaterialsHandler.findMaterialSlot`（:100-112）优先堆叠已有同种槽，而 compose 按槽计
零件（数量无关）——AI 报 [铁锭,铁锭,煤] 实际只算 2 零件；蓝图路径却逐槽铺开，两路径不一致。
**修法**：`findMaterialSlot` 改为优先空槽，无空槽才堆叠。
**验收**：GameTest：AI 放料含重复材料时占用不同槽位。

## WQ-19 [ ] 【低·经济】交易声望按笔计无节流——Shift 一键 12 笔直冲满折扣

`notifyTrade`（:90-108）每笔 +1 声望且每笔写一条相谱，Shift 批量成交两次点击即触顶 ±25%，
与 WQ-13 复合成"卖货涨声望→声望让卖货更赚"的印钞机。
**修法**：声望加成设每 NPC/玩家/MC 日上限（如 5）；相谱按会话合并一条。

## WQ-20 [ ] 【低·加固】上行包字段无长度上限（三处）

`AiPlaceMaterialsPayload:29-32` 的 `ByteBufCodecs.collection` 不带 maxSize，恶意包可带上万条
id 各触发注册表查询+41 格扫描；`AiRequestPayload.java:45,47` 与 `BlueprintSavePayload.java:25`
同病。**修法**：collection/字符串 codec 补 maxSize（材料列表 16、需求文本 1024、蓝图名 64 等
合理上限），handler 里再截断。

## WQ-21 [ ] 【低·软锁】回程锚点放置可静默失败 → 玩家困在维度

`MyriadWildsPortalHandler:86-96` 只试 2 个候选点，都不可替换就 return 无提示；玩家挖掉去程
裂隙岩后无法回程。**修法**：候选扩为 3×3 必要时强制放置，失败发聊天警示。

---

# 第三批：客户端/同步/性能狩猎发现（2026-07-26 深夜第二队侦察代理）

## WQ-22 [ ] 【高·多人】全部命令零权限门控

全仓库 `grep "requires("` 零命中：多人服任意玩家可 `/qianxiang kit`（无限刷全套装备/刷怪蛋）、
`/qianxiang dim`（无条件跨维度，绕过位格门控与裂隙精髓消耗）、`/qianxiang ask <串>`（往单线程
无界 AI 队列塞 HTTP 任务并写 jsonl 日志）。
**修法**：kit/dim/ai 子命令 `.requires(s -> s.hasPermission(2))`；saga/blueprint 保持玩家可用；
ask 若保留给玩家则加 per-player 冷却（如 100 tick）。
**验收**：非 OP 玩家 tab 补全看不到 kit/dim；OP 正常使用。

## WQ-23 [ ] 【高·多人】施法入口缺存活/观战校验 + 失败提示构成 1:1 包放大

`SpellCastHandler.handle`（:37-92）只判 `instanceof ServerPlayer`：观战者按 V 照常施法
（ender 元素还会 `connection.teleport`）；死亡瞬间同理。且冷却/法力不足路径每个被拒上行包
回一个 actionbar 下行包（:69/:73/:109/:113），改包客户端可用最小成本让服务端对自己单播海量
数据、全占主线程。
**修法**：入口加 `if (!p.isAlive() || p.isSpectator()) return;`；失败提示 per-player 20 tick 节流。
**验收**：观战模式按 V 无任何效果；GameTest 覆盖 spectator 拒绝。

## WQ-24 [ ] 【中·体验】伤害浮字寿命与帧率成反比——高刷屏上一闪即没

`ClientDamageNumbers:117,134` 用 `getGameTimeDeltaPartialTick(true)`（tick 内插值系数 0~1，
非帧间 delta）累加 age：30FPS 活 2 秒、144FPS 只活 0.42 秒、240FPS 0.25 秒。
**修法**：改用 `event.getPartialTick().getRealtimeDeltaTicks()`，或把年龄推进移到
`ClientTickEvent.Post` 渲染只读。顺带：`ACTIVE` 表在断线/换世界不清空，重进后旧 entityId
撞新实体会冒幽灵数字——在 LoggingOut/LevelUnload 清一次。

## WQ-25 [ ] 【中·性能】动态武器模型每帧全量重算 key + bake 失败静默重试风暴

`DynamicWeaponModel.getRenderPasses`（:62-68）每帧每栈重算变体 key：`shapeFromCustomName`
（toLowerCase+14 次 contains）、`hasGranted` 每次开 Stream 对每 key toString（Texture:473-474）、
`List.of(new DynamicPass)` 再分配——GUI 40 格×60FPS=每秒数千次分配纯 GC 压力。更糟：
`CACHE.computeIfAbsent(key, ::bake)`（:108）在 bake 抛异常时不留映射且 catch(Throwable) 吞异常
→ 该变体每帧重绘 16×16 并尝试注册纹理，持续卡顿零日志。
**修法**：变体 key 按组件哈希做一层缓存（或存 ItemStack 附带缓存）；bake 失败向 CACHE 写入
哨兵 Variant 阻止重试，并打一条 WARN（每变体一次）。
**背景**：变体总上界 720 个（shape20×color9×tier4），与锻造物数量无关——缓存本身设计没错，
问题只在 key 计算频率和失败路径。

## WQ-26 [ ] 【中·平衡】Shift 连锻一次点击灌 64 条相谱 + 位格直冲 100 越过维度门控

QUICK_MOVE 循环调 `quickMoveStack`（ForgeTableMenu:194-206），每轮 `recordForge` 追加一条
相谱 + `withBumpedPosition(1)`。材料槽各放 64 个 shift 一下：64 条相谱、位格 0→64+
（MAX=100），直接跳过 `WILDS_POSITION_THRESHOLD=3` 的门控设计。
**修法**：`recordForge` 按批次合并一条（带数量）；位格增长每次交互（或每 MC 日）限 +1。
**验收**：shift 连锻后 /qianxiang saga 只多一条记录；位格增幅受限。

## WQ-27 [ ] 【低·清理】客户端静态状态跨世界残留（两处）

①`ClientForgeTableAI.onResult/lastResult` 是 static，断线/崩溃不走 onClose → 持有整个 Screen
对象图；且 `receive`（:41）无条件回发 `SpellJsonReportPayload`（锻造台已关也发）。
②`ClientSpellInput`（:20-24）tick 事件无 `player == null` 守卫（主菜单触发理论可抛），且用
if 非 while 消费点击。
**修法**：监听 `ClientPlayerNetworkEvent.LoggingOut`/`LevelEvent.Unload` 清静态字段；
receive 校验当前 Screen 再回发（注意 WQ-2 重构会改这条链路，先做 WQ-2 的话本条①随之消解）；
ClientSpellInput 加守卫、if→while。

## WQ-28 [ ] 【低·性能】两处热路径开销

①homing 弹体每 tick `getEntitiesOfClass(inflate(10))`+sort（SpellProjectileEntity:105-113）——
改每 4 tick 重选目标并缓存目标 id。②锻造台任意一次容器点击（含背包无关格）全量重跑
`ForgeComposer.compose` 并 setItem 新产物栈触发同步（ForgeTableMenu:252-258，quickMoveStack
还会再调一次）——对材料槽内容做哈希，未变则跳过重算。

## WQ-29 [ ] 【低·防御】产物槽 shift 部分搬运会清空余量（当前不可触发，防未来）

`quickMoveStack` 产物分支 `moveItemStackTo` 部分成功也返回 true，随后无条件
`slot.set(EMPTY)`（:231）。今日 compose 产物恒 count=1 不可触发（第一队已核实），但产物
一旦支持堆叠即成吞物品 bug。**修法**：只在 `stack.isEmpty()` 时才清空。一行防御。

---

## 侦察员核查过没有问题的区域（修理时不必怀疑，改动时别破坏这些保证）
- `quickMoveStack` 产物分支/onTakeResult 时序/连锻确定性（compose 无 RNG）
- AiPlaceMaterialsHandler 物品守恒三路径（split/grow/撤销）不复制不造物
- DrawbackHandler 五代价无递归无双触发；ArmorEffectHandler magic 拦截防反伤循环成立
- ArmorPassiveHandler 脱甲效果 11 秒内自然消退，无永久残留
- SagaData 有 500 条上限+forgotten 计数，attachment 不上行客户端
- 定价三重下限保险（不可能 0 绿宝石白嫖）；两 NPC 无套利闭环
- WorkshopSavedData 配额按 UUID 不可绕过、setDirty 齐全
- 传送 1:1 无缩放、门控无"进得去回不来"死局（除 WQ-21）、无双手双倍消耗精髓
- 动态贴图变体有 720 上界（shape×color×tier），与锻造物数量无关，不会无限注册；
  F3+T 重载不套娃不紫黑
- GUI 打开时按 V 不会发施法包（KeyMapping.releaseAll 清点击队列）
- SpellHudRenderer 无 NPE 路径（attachment 有默认供给）；弹体 160 tick 超时销毁、落盘完整
- 浮字接收端有双层限流（单实体 8 条/全局 64 桶）不会无限增长
- 渲染器/实体尺寸/属性注册全部匹配；Boss 血条玩家增删正确
- AIGateway 熔断并发语义正确（ThreadLocal 同线程读写、探测标志 finally 释放）；
  仅两处小瑕疵：兜底分支绕过熔断直连一次、BREAKER_SKIPS 永不归零（低危可不修）
- GameTest 全部纯逻辑断言无随机/时序依赖，不会偶发红
- PlayerSpellData copyOnDeath 已配，死亡数据不丢

## 已完成（勿重做）
- P0-1 法术上行白名单+钳制、P0-4 调试栈打印、P0-5 en_us 中文污染、P0-7 AI 熔断、
  P0-8 防具映射（e3b66f9，侦察会话）
- Boss 三技能、成就三支线、位格门控、锻造/传送门音效、NPC 补货落盘、分享码 productType
  归一（2f443f0..be66c1a，修理会话）
- P0-3 调查（结论见 WQ-5，代码无需改动）
