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

## WQ-10 [x] 完成(8055448) 【高·刷物品】漏斗接锻造台 = 零成本无限锻造

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

## WQ-11 [x] 完成(8055448) 【高·刷物品】蓝图放料"洗物品"：残耐久附魔装备变全新白板 + 可扒身上盔甲

`ForgeTableMenu.applyBlueprint`（:92-109）找材料只比 `s.is(item)` 忽略组件，取用后放入的是
`new ItemStack(item, 1)` **出厂新栈**；且扫描范围是 `playerInventory.getContainerSize()`=41，
**含 4 护甲槽和副手**。
**触发 A**：蓝图材料含钻石镐 → 背包放 1 耐久附魔镐 → 用蓝图 → 材料槽出现满耐久无附魔
新镐，Shift 取回（免费修复+洗附魔）。**触发 B**：蓝图材料含头盔 → 直接扒走玩家头上戴的。
**修法**：改为搬运原栈 `taken.split(1)`；扫描范围限 `Inventory.INVENTORY_SIZE`（36）。
同文件 `AiPlaceMaterialsHandler.findInInventory`（:88-94）也是 41 格扫描（那条路径保留组件，
危害仅"拿走身上装备当零件"），一并限 36。
**验收**：GameTest：低耐久物品走蓝图放料后组件保留；护甲槽物品不被取用。

## WQ-12 [x] 完成(8055448) 【高·吞物品】挖掉锻造台吞掉全部 11 格内容

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

## WQ-18 [x] 完成(8055448) 【低·一致性】AI 放料同种材料堆同一槽 → 零件数少于 AI 承诺

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

# 第四批：数据 json 审计发现（2026-07-27 凌晨第三队侦察代理，成就/loot/worldgen/recipe/tags 全查）

## WQ-30 [ ] 【P0·数据】rift_stone 与 void_ore 缺 mineable 标签——挖掉永不掉落

仓库 `data/` 下没有 `tags/block/` 目录，两方块又都 `.requiresCorrectToolForDrops()`
（QianxiangBlocks.java:33/:68）。1.21 规则：不在任何 `minecraft:mineable/*` 标签的方块对所有
工具 correctForDrops=false → `loot_table/blocks/rift_stone.json`、`void_ore.json` 永不执行。
裂隙岩（传送门框）拆一次永久损失；虚痕矿挖不出 void_shard（仅剩 Boss 掉落兜底）。
**修法**：新建 `data/minecraft/tags/block/mineable/pickaxe.json` 收录两方块；按设计意图可再加
`needs_iron_tool.json`（void_ore 建议铁镐档）。**验收**：生存模式镐挖两方块有掉落。

## WQ-31 [ ] 【高·数据】legendary_material 在 kill_warden 同一瞬间必然自动弹出

守望者战利品第 1 池 100% 必掉 void_shard 1-2，而该成就条件正是"持有 void_shard 或
reverse_core"——两个 challenge 成就同帧弹出，终局沦为击杀附赠。
**修法**：与 WQ-6 联动——WQ-6 落地后条件改"持有 warden_core"（首选，一并解决）；
若先行止血，可临时收窄为只认 reverse_core（需 void_shard+rift_essence+ender_eye 再合成一步）。
**领此单前先看 WQ-6 状态，避免改两次。**

## WQ-32 [ ] 【中·数据】first_forge 可在工作台达成（从未碰过锻造台）

`advancement/story/first_forge.json` 的 items 列表含 `qianxiang:spell_book`，而 spell_book 有
纯原版材料的工作台配方（recipe/spell_book.json）——工作台合一本书同时点亮 first_forge 和
子节点 story/spell_book，"初铸"文案与条件背离。
**修法**：first_forge 的 items 删掉 spell_book 一项。

## WQ-33 [ ] 【中·平衡】void_ore 生成密度约为原版钻石 8 倍——LEGENDARY 材料白菜价

`placed_feature/void_ore.json`：count 8/区块 × size 8，y∈[-64,32]，无 rarity_filter。挖矿
10 分钟即可架空 Boss 掉落线（与 WQ-6 的独占性设计冲突）。
**修法**：count 降 1-2、size 降 4、加 rarity_filter；与 P3 观察项（rift_essence_from_void_shard
1 换 2 的兑换曲线）一起通盘调平。

## WQ-34 [ ] 【中·体验】守望者自然刷新无 spawn_costs——多只 Boss 血条叠 HUD

`biome/myriad_wilds.json` monster 池守望者权重 3、spawn_costs 为空对象，同屏可游荡多只
各带 ServerBossEvent 的守望者。
**修法**：spawn_costs 加 `"qianxiang:myriad_warden": {"energy_budget": 0.12, "charge": 1.0}`，
权重降 1。

## WQ-35 [ ] 【中·可发现性】22 个配方全部没有 recipe advancement——配方书完全隐身

`advancement/` 下无 `recipes/` 目录，不装 JEI 的玩家无法在配方书里看到任何千相配方
（包括入口方块锻造台）。
**修法**：至少给 forge_table、rift_essence、spell_book、reverse_core 等关键配方补
`advancement/recipes/<name>.json`（has_item criteria + rewards.recipes），其余可批量生成。

## WQ-36 [ ] 【低·EF 数据】item_keyword 正则与显式文件打架 + phase_staff 兜底类型漂移

①`capabilities/weapons/item_keyword/qianxiang_blades.json` 的正则 `qianxiang:.*_blade` 只能
命中已有显式 json 的两把刀，且把声明 dagger 的 bone_blade 导向 tachi 连段——删掉该 keyword
文件（types 文件留作参考）。②`capabilities/weapons/phase_staff.json` 静态兜底写
`epicfight:sword`，Java 动态分类是 DAGGER——静态 json 改 `epicfight:dagger` 对齐。

## WQ-37 [ ] 【低·数据】三个空功能标签 + biome 废弃字段

①`tags/item/materials/defense.json`/`reflect.json`/`slow.json` 全空——DEFENSE/REFLECT 是防具
体系仅有的纯防御算子，datapack 扩展入口是哑的。建议填充：defense=shield/turtle_scute/
iron_block、reflect=cactus/nautilus_shell、slow=cobweb/soul_sand/honey_block（注意 WQ 已把
DEFENSE 映射到护腿原型，填充后玩家可用原版材料锻护腿）。②`biome/myriad_wilds.json` 残留
1.19.4 已废弃的 `"precipitation": "rain"` 字段（静默无效），删除。

## WQ-38 [ ] 【低·worldgen】荒光草悬浮空中 + 微光树叶徒手必掉

①`configured_feature/wildlight_patch.json` 谓词只查目标位为空气不查脚下，y_spread 3 →
实心发光块悬浮半空。改 `would_survive` 谓词或补下方方块检查。②`loot_table/blocks/
glimmer_leaves.json` 无剪刀/精准分支，徒手打叶必掉方块，且让 myriad_fragment 零成本可刷。
包 alternatives 加 shears/silk_touch 条件。

**第四批观察项（暂不开单）**：rift_essence 三条配方 + void_shard 1 换 2 的兑换关系把档位
曲线压平（随 WQ-6/33 通盘调）；story/rift_essence 支线可先于父节点完成（仅显示顺序怪，
无碍）；`src/main/resources` 无显式 pack.mcmeta（NeoForge 自动合成，可跑，未钉 pack_format）。

**第四批已核查无问题**：成就树 11 节点无孤儿无环、icon/lang/实体 id 全对；loot_table 全部
1.21 新 schema 正确；worldgen 引用闭合、11 个 feature 步骤索引全对、dimension_type 自洽；
22 配方无冲突、id 全存在（含 1.20.5+ 改名物品）；phase_materials 字段与解析代码零偏差；
25/26 功能标签齐（缺 REVERSE 属设计意图）；EF 数据包三层目录名与 jar 内常量逐字节一致、
动画 id 真实存在；无幽灵物品（39 物品全有获取途径）；创造标签全收录。

---

# 第五批：AI 集成质量审计（2026-07-27 凌晨第四队侦察代理，含 Gson 实测解析矩阵）

> 总判断：**"AI 当配方大脑"在默认配置下从未真正工作过**——玩家一直在用关键词兜底而不自知。
> 本批按依赖顺序修：WQ-39/40/41 是让 AI 真正上线的三件套，先做。

## WQ-39 [ ] 【P0·AI】prompt 塞全量材料库 ≈4 万 token——qwen2.5:7b 根本装不下

`PhaseAIRecipeService.buildSystemPrompt:306-312` 对材料库无截断逐条 append，而
`MaterialLibrary.snapshot()`（:152-165）因 `ItemConceptResolver` 的兜底分支（万物皆有概念）
实际收录**全部已注册物品**（原版 1391 件≈114KB）。Ollama 默认 num_ctx 2048~4096，
`AIClient:100-105` 又不下发 `options.num_ctx` → 新版 Ollama 直接 500，旧版截断且玩家需求
（prompt 第 2 行）最先被丢。
**修法**：①检索式召回——用 FallbackRecipes 已有关键词表+目标 type/tier 从 snapshot 筛
Top-48，分【核心效果材料】【基底材料】【辅料】三组写入 prompt（~4KB）；②AIClient 的
Ollama 分支下发 `options.num_ctx=8192`；③删掉"万物皆可当辅料/泥土=万物之基"两句（与召回
策略冲突）；④加一段 few-shot（完整输入→输出示例 ×2：weapon+模糊需求演示 questions、
magic+明确需求演示 spell）；⑤输出模板从规则第 4 条提升为独立【输出格式】段放 prompt 末尾。
**验收**：日志确认 prompt 长度 <8KB；本地 Ollama 实测一次 AI 推荐真实生效（非兜底摘要）。

## WQ-40 [ ] 【高·AI】默认 5 秒超时必超 + 超时不入熔断 = 每次白等 10 秒

`AIConfig.DEFAULT_TIMEOUT_SECONDS=5`（:44），大 prompt 下 7B 模型必超时；`HttpTimeoutException`
被 `AIClient.isConnectionIssue` 有意排除（只算连接类），熔断永不开 → `AIGateway:119-127`
每次都 5s+重试 5s=10 秒后才兜底。
**修法**：默认超时提到 30s（已有配置的存档要迁移默认值）；熔断加第二计数器"连续请求超时"
（阈值 3，与连接失败分开计数、分开日志文案）。
**验收**：断网/慢模型场景下第 4 次请求起零延迟落兜底。

## WQ-41 [ ] 【高·AI】两个材料 id 处理 bug——AI 选对了材料也会被剔除或放料失败

①`normalizeName:724-728` 给无冒号名强加 `qianxiang:` 前缀，"iron_ingot"→"qianxiang:iron_ingot"
永不匹配，`MaterialLibrary.find` 的短名容错成死代码——小模型省 namespace 是最高频瑕疵，
方案整条被清空静默落兜底。改法：无冒号时不加前缀交给 find 短名匹配，命中取
`entry.registryName()`。②`:582-587` 用 equalsIgnoreCase 匹配但 `picks.add(normalized)` 存的是
AI 原样大小写，"Minecraft:Iron_Ingot" 一路传到客户端图标与放料的 `ResourceLocation.tryParse`
全返 null（MC 不接受大写）——改 `picks.add(entry.registryName())`。
**验收**：GameTest：喂含裸名/混合大小写材料的 AI JSON，断言解析结果全为规范 registryName。

## WQ-42 [ ] 【中·AI】未开结构化输出 + temperature 0.7 过高

`AIClient` Ollama 分支加 `body.addProperty("format","json")`，OpenAI 分支加
`response_format:{"type":"json_object"}`；temperature 0.7→0.2（照抄 registryName 类任务）。
两行配置消掉一半解析失败面。

## WQ-43 [ ] 【中·AI】extractJson 四类必败输入 + 解析失败零日志

实测矩阵（Gson 2.13.1）：围栏/闲聊/单引号/裸字段名/数组尾逗号 ✅ 容错；**对象尾逗号、
字段名大小写（"Proposals"）、deepseek-r1 的 <think> 块、顶层数组** ❌ 整体失败——失败后
无任何 WARN，玩家只看到状态灯变黄和"（基础配方）"。
**修法**：extractJson 先剥 ``` 围栏与 <think> 块再括号配平取最后一个完整对象，顶层数组包一层
`{"proposals":...}`；字段读取走大小写不敏感 helper；解析失败记 WARN+原文前 300 字进 jsonl；
prompt 加"summary 用与玩家输入相同语言，≤20 字"（gpt 类模型摘要语言错乱）。

## WQ-44 [ ] 【中·AI】confirm 模式三处失准

①差异化信息"当前已放入材料"埋在 prompt 最末尾，前面全是推荐向段落——7B 注意力被稀释，
评价模式答非所问变成重新推荐。改法：confirm 模式把当前材料+任务声明（"你的任务是评价
不是重新推荐"）提到 prompt 最前，砍掉【功能性需求指引】【宽泛需求语义】【效果词典】三段。
②`ask():186-192` 不判 confirm 模式下材料槽为空——空槽点确认 AI 对空气做评价。改法：
`mode==confirm && mats.isEmpty()` 直接走 `FallbackRecipes.proposeForConfirm`。
③`isFallback():107-111` 漏 `confirm.summary` 前缀——confirm 兜底时状态灯仍绿，玩家误以为
是 AI 评价。补前缀或改判 `RecipeResult.fallback` 字段。
另：【EF 动画库】段改为命中动作关键词才插入；【自由法术】段只在 type==magic 时插入。

## WQ-45 [ ] 【中·兜底】FallbackRecipes 质量包：否定语义/单字误触/六组缺失/七材料不可达/档位塌缩

①`matchesAny:967-972` 裸 contains——"不要火"命中"火"塞火材料；先剥否定片段
（不要X/无X/抗X/防X/no X/without X），单字键（火/血/防/护/术/甲/链）升 2 字词或加边界。
②关键词组缺失：**雷电整组没有**（thunder_stone 永远选不到）、凋零/隐身/失明/虚弱/幸运
无分支（prompt 却宣传了"凋零刀/隐身斗篷/幸运工具"，AI 挂时全落空）；"寒"不在 frost 组
（suggestQuestions 有、keywordPicks 无，两表不同步）。③**7 个模组材料在全部兜底路径不可达**：
frost_crystal/thunder_stone/venom_gland/shadow_dust/holy_shard/nature_breath/void_shard 零引用；
EffectGlossary 的 typicalMaterials 里它们永远被 `addIfExists...break` 的首位原版材料抢走——
改为按目标档位选或模组材料排首位。④tier 塌缩第二类：16 格 (type×tier) 里 8 格 base==effect
撞同一材料，去重后靠 defaultFillers（RARE+COMMON）凑数——"传奇武器标准方案"实际均档 RARE，
"普通武器"反而给 RARE 材料。base/effect 选择器改为按 (type,tier) 从 MaterialLibrary 筛池选
两个不同材料，defaultFillers 随档位变化。
**验收**：GameTest：「不要火的剑」不含 ember 系；「雷电剑」含 thunder_stone；LEGENDARY 方案
averageTier==LEGENDARY。

## WQ-46 [ ] 【低·性能】MaterialLibrary 每次 find 全表扫 + 同一 JSON 解析三遍 + 死代码

`find()` 每调用重跑 snapshot()（1391 个 ItemStack+概念推导），被六处逐材料调用，一次"全部
增益"兜底 ≈3.6 万次概念推导（服务端后台线程，给玩家叠秒级延迟）——snapshot 加按
PhaseMaterialRegistry 版本号失效的缓存，find 查预建 Map。`ask()` 里 extractConfirmMessage/
extractQuestions/parseProposals 三次重复解析——解析一次传引用。删死代码
`MaterialLibrary.buildFromTags:209-234`（零调用方且逻辑已分叉）。

## WQ-47 [ ] 【低·数据资产】jsonl 日志补齐"建议 vs 采纳"链路（DP-3 飞轮的地基）

现在只记 ts/provider/model/cached/ok/latency/want——解析失败与成功在日志里长一样，采纳
事件完全没记。补：每请求 req_id + raw_response 前 500 字 + proposal_count + dropped_materials
+ fallback_reason + player_uuid；`AiPlaceMaterialsHandler` 收到放料时回写
`{"req_id","event":"adopt","materials":[...]}`；轮转从单代 .old 改三代 .1/.2/.3。
关联 `docs/design-proposals.md` DP-3。

**第五批已核查无问题**：24 组兜底材料 id 全真实（scute 改名有双 id 兜底）；"寒冰/冰霜"命中
正常；数据驱动材料链路通（/reload 后 AI 立即可见新材料、缓存 key 自动失效）；缓存 key 覆盖
type/tier/mode/白名单不会跨需求错配；parseSpellJson/parseMovesetJson 校验闭合；ask() 永不抛
异常契约成立；熔断并发语义正确；AI 不占主线程；jsonl 写入不影响主流程。
另：ClientMaterialFilter 默认全选使"按库存筛选"设计意图落空（AI 必然推荐玩家没有的材料）——
属产品决策，见 DP-3 的"选择题化"提案，暂不开单。

---

# 复核报告：侦察会话对修复提交的对抗性审查（2026-07-27 凌晨，审 8055448 与 6bdc4c4）

**结单裁定**：
- **WQ-10 ✅ 修复成立**——WorldlyContainer 参数语义正确、漏斗/投掷器/管道 capability/
  quickMoveStack/THROW/SWAP 全部绕过路径逐一验证被堵；老存档产物残留可自愈；onTake 正常
  取产物未破坏。放心保持 [x]。
- **WQ-12 ✅ 修复成立**——onRemove 签名/顺序/无重复掉落/活塞不可达全对。`items.clear()`
  顺带封死了"A 开界面 B 炸台"的抢跑窗口，**这行有双重职责，勿删**（建议加注释）。
- **WQ-18 ✅ 代码正确**但验收 GameTest 未补（"AI 放两个铁锭占两个槽"）——补上即可结单。
- **WQ-11 ⚠️ 需返工**，见 WQ-48。
- **6bdc4c4 耐久链 ✅ 注册层正确**：与 getMaxDamage override 不冲突不双重生效、无除零、
  无注册期崩溃、stacksTo(1) 副作用全查无碍。但暴露护甲线缺口，见 WQ-49。
- 复核期间新落的 cc6f1ab/1b44305/dfa7641 仅抽查回归：WQ-10/11 修复未被冲掉；发现一处
  语义问题记为 WQ-50。

## WQ-48 [ ] 【中·返工】WQ-11 蓝图选料仍组件盲——会吃掉玩家高价值同款装备，且蓝图不可复现

split(1) 搬原栈和限 36 格都改对了，但 `:99` 匹配仍是 `s.is(item)` 取背包**首个命中**：
①玩家的经验修补附魔镐排在垃圾镐前面会被直接吃掉，无确认；②蓝图作者用白板附魔书存的
方案，使用者拿修补附魔书去配，ForgeComposer 读真实栈 → 产物属性与蓝图预览不一致。
**修法**：选料改为"最不值钱的同 id 匹配"（无附魔、无自定义名、耐久最低优先）；
另补 WQ-11 验收点名的 GameTest（低耐久组件保留 + 护甲槽不被取用）。
顺带：`:102` 中途失败不回滚——前几件材料已进台（不丢但体验差），失败时把已放材料退回背包更好。

## WQ-49 [ ] 【中·玩法】护甲线"耐久靠材料"不成立——BASE_HIDE 不产 durability，皮甲恒定 150

`AttributeScheme:238-256` 只有 BASE_METAL/WOOD/BONE 累加 durability，`case BASE_HIDE` 只给
armor 和 powerScore。纯皮革组合锻出的四件护甲 durability==0 → override 回落固定 150，
与材料档位完全无关。且这是**已生效的平衡变更**（此前护甲因缺组件永不磨损，现在 150 次碎）。
**修法**：BASE_HIDE 分支补 durability 系数（建议按皮革基底 ~120 + 档位乘数走现有公式）；
新增 GameTest 覆盖纯皮革护甲 durability 随档位变化。
**附**：`DrawbackHandler` 的 frail 代价（每命中 hurtAndBreak）在耐久修复后**首次真正上线**，
数值是在恒 no-op 路径上调的——实机冒烟一次 frail 武器的磨损速度是否合理。

## WQ-50 [ ] 【低·语义】recordForge 3 秒限流误伤"3 秒内锻两件不同产物"

1b44305 的限流在读产物属性**之前**就返回——快速手动锻造两件不同物品，第二件既不写相谱
也不涨位格。若非 WQ-26 的预期取舍，应改为按产物 id 去重/合并计数，而不是纯时间窗拦截。

## WQ-51 [ ] 【低·纵深】三个加固点（不阻塞任何结单）

①`ForgeTableBlockEntity` 补覆写 `canPlaceItem(int,ItemStack)` 返回 `slot < MATERIAL_SLOTS`——
当前无 capability 注册所以不可利用，但这是产物槽最后一个默认 true 的口子；
②`ForgeTableMenu.removed()` 清产物槽后调一次 `updateCraftingState()`，避免方块滞留
STATE_READY 粒子；③`dropContentsOnRemove` 的 `items.clear()` 加注释说明其防抢跑职责。
另记：8055448 与 6bdc4c4 是同批拆分提交，前者包含依赖后者的测试（单独 checkout 8055448
测试必红），bisect 时注意。

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

---

# 第五批：修理会话侦察代理补充发现（编号 40+ 避开第四批）（2026-07-26 深夜，均已亲自打开源码复核）

## WQ-40 [x] 完成(6bdc4c4) 【严重·系统性失效】产物注册缺 Properties.durability，整条耐久链是死代码

`QianxiangItems` 全部 10 个产物只写了 `.rarity(...)`，栈上没有 MAX_DAMAGE/DAMAGE 组件 →
`isDamageableItem()` 恒 false。后果：①三个 Item 子类的 `getMaxDamage(ItemStack)` override
永不被消费，`ComposedAttributes.durability` 一整套计算白算；②`hurtAndBreak` 全程空转 →
**frail（易碎）代价 100% 失效**、相锄/水壶永不磨损；③装备可 64 个一摞、无耐久条。
**已修**：10 个产物补 `.durability(各子类 DEFAULT_DURABILITY)`，override 随即接管为动态耐久；
新增 2 个 GameTest（全产物可损坏且 stacksTo=1、锻造耐久等于组合耐久）。
**注**：`AttributeScheme` 那段"durability 由 override 提供"的 javadoc 此前由修理会话写下但
漏了注册侧前提，已一并纠正。

## WQ-41 [ ] 【严重·凭据泄露】服务端 apiKey 明文推送给每个进服玩家

`network/AiConfigSyncHandler.java:52-60`（`onPlayerLogin` → `PacketDistributor.sendToPlayer`）
经 `:64-68 currentPayload()` 把 `cfg.apiKey` 放进包；`AiConfigSyncPayload.java:37` 照发；
客户端 `ClientAIConfigCache` 缓存后回填进 AI 设置界面输入框。
**C2S 保存路径的 OP 校验（`:33-38`）是对的，读路径完全没有门控**——权限模型只做了一半。
单人存档无害；专用服务器上服主的付费 Key 泄露给全服每一个人。
**修法**：登录同步时把 apiKey 换成占位（如 `""` 或 `"********"`），仅在
`hasPermissions(2) || isSingleplayerOwner` 时发真值；客户端界面对占位值不回填、
保存时若仍是占位则不覆盖服务端已有 Key。
**验收**：非 OP 玩家进服抓包无明文 Key；OP 打开界面仍能看到并修改真值。

## WQ-42 [ ] 【高·客户端卡死】锻造台每帧 12 次全物品注册表扫描

`client/ForgeTableScreen.java:572`（render 内）→ `:1307` → `:1448 resolveItemStack`
→ `ai/MaterialLibrary.java:440 find()` → `:126 snapshot()`。`find()` 每次调用都重跑
`snapshot()`，而 `snapshot()` **两遍**遍历 `BuiltInRegistries.ITEM`，第二遍对每个物品
`new ItemStack(item)` + `ItemConceptResolver.resolve` + tag 查询，**无任何缓存**
（其注释"命令路径，量小，可接受"已不成立）。
拿到 AI 推荐后 3 张卡 × 4 材料图标 = 每帧 12 次全表扫描；整合包上万物品时客户端假死。
同一热路径也在 `PhaseAIRecipeService:733,772` 与 `FallbackRecipes` 6 处。
**修法**：`snapshot()` 加缓存（注册表冻结后内容不变，按 `PhaseMaterialRegistry` 版本号失效即可）；
`find()` 改查预建 Map 而非线性扫描；`resolveItemStack` 结果在 Screen 里按提案缓存。
**验收**：装整合包打开锻造台+AI 推荐，帧率无可感下降。

## WQ-43 [ ] 【高·可打死服务端】AI 请求包无前置校验、无限流、执行器队列无界

`ai/ForgeTableAIHandler.java:31-53`：`enqueueWork` 只用来设粒子状态，**AI 任务无条件
`AI_EXECUTOR.submit`**——不校验玩家是否真的开着锻造台，无冷却；`:23` 的
`newSingleThreadExecutor` 是无界 `LinkedBlockingQueue`。改过的客户端循环发
`AiRequestPayload` 即可：每包 = 一次真实 LLM HTTP（烧服主 token）+ 一次全表扫描 + 队列堆积。
`AIGateway` 缓存按 prompt 哈希，改一个字符即绕过；熔断只在连接失败时开，端点正常时不拦。
**修法**：提交前校验 `containerMenu instanceof ForgeTableMenu`；每玩家冷却（如 3 秒）+
队列深度上限（满则直接回兜底）；`AiRequestPayload` 的 collection/字符串 codec 补 maxSize
（与 WQ-20 合并做）。

## WQ-44 [ ] 【中】Boss 冲击波对无敌目标仍施加击退

`entity/QianxiangMyriadWarden.java:159-162`（修理会话本人所写）：过滤器只排除自身/同类/
裂隙蠹，`hurt()` 对创造与旁观模式玩家是空操作，但紧随其后的 `knockback()` **无条件执行**
→ 旁观模式玩家会被 Boss 推着走。项目里法术 AOE 都有 `isAlly` 过滤
（`SpellEffectEngine:317,348`），Boss 这里漏了同等严谨度。
**修法**：过滤器加 `!(e instanceof Player p && (p.isSpectator() || p.isCreative()))`，
或在 hurt 返回 false 时跳过击退。

## 修理会话核查后判定为**误报**的项（勿再排查）
- Boss 血条在实体死亡/卸载时"泄漏"：原版 `ChunkMap.TrackedEntity` 移除时会对每个跟踪玩家
  调 `stopSeenByPlayer`，血条自动清理，写法与原版 Wither/末影龙一致。

---

## 已完成（勿重做）
- P0-1 法术上行白名单+钳制、P0-4 调试栈打印、P0-5 en_us 中文污染、P0-7 AI 熔断、
  P0-8 防具映射（e3b66f9，侦察会话）
- WQ-10/11/12/18 锻造台三条刷/吞物品链与放料一致性（8055448，修理会话）
- WQ-40 耐久链彻底失效（6bdc4c4，修理会话）
- Boss 三技能、成就三支线、位格门控、锻造/传送门音效、NPC 补货落盘、分享码 productType
  归一（2f443f0..be66c1a，修理会话）
- P0-3 调查（结论见 WQ-5，代码无需改动）
