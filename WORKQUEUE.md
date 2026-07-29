# 牵响 协作工单队列

> **协议**：本文件由「侦察会话」（找 bug/出方案）维护，「修理会话」（写代码）消费。
> 修理会话领活时把状态从 `[ ]` 改为 `[~] 修理中`，完成后改 `[x] 完成(commit哈希)` 并保留原文。
> 除状态行外不要改动工单正文；有异议在工单末尾加「修理备注：」。
> 背景总纲见 `docs/next-milestone-plan.md`（其中 P0-1/4/5/7/8 已完成于 e3b66f9，勿重做；
> P0-3 调查已定论，只剩文档收尾=本队列 WQ-5）。
> 每单都写了验收标准；完成必须过 `./gradlew compileJava` + `./gradlew runGameTestServer` 全绿。

---

# 分诊索引（2026-07-27 02:30 更新 · 80 张单：61 完成 / 19 待修 / 0 进行中）

**基线**：HEAD `9a7792d` 上 `runGameTestServer` **58 个 GameTest 全绿、构建成功**（侦察会话
亲自验证，非提交信息声称）。但注意：绿灯不代表正确——已连续四次出现"断言太弱恰好被错误
路径满足"，见下方测试硬规矩。

## 建议开工顺序

**第一梯队 · 三张严重单，都是"改了但等于没改"**（共同特征：代码写了，运行时不生效）
1. **WQ-68** 退款被过期快照整体覆盖 → 退款从未到账（`SpellCastHandler:124/143`）
2. **WQ-62** 默认超时 30 秒对所有玩家不生效（`AIConfig:73` 模板仍写死 5）
3. **WQ-71** req_id 是 ThreadLocal 却跨线程读 → 全服采纳事件共用同一假 id，飞轮地基没打上

**第二梯队 · 四张 GUI 高危，玩家每次开锻造台都会遇到**
4. **WQ-73** 「清空」按钮不生效（静态缓存被回放）且不撤销服务端选择
5. **WQ-76** 无请求序号 + 无条件重置索引 → 产物法术与玩家所点卡片不符，且界面无选中高亮
6. **WQ-75** 连点两张方案卡是叠加不是替换 → 产物与卡片强度对不上
7. **WQ-74** 请求被静默限流后状态条永久卡死，只能关界面重开（**与 WQ-62 联动，一起做**）

**第三梯队 · 其余返工与中危**：WQ-63（熔断两计数器互相清零 + 非 2xx 不计数）、
WQ-72（档位塌缩子项零改动）、WQ-69（去程两类地形塌陷）、WQ-65（顶层数组分支死代码）、
WQ-66（prompt 仍约 19K 字符超 num_ctx）、WQ-64（response_format 无降级）、
WQ-77/78/79（GUI 中危三项）

**收尾**：WQ-67、WQ-70、WQ-80（低危/文案/渲染细节）

## 跨单联动提醒
- WQ-62 + WQ-74 一起做（超时提到 30s 会让卡死窗口更长更明显）
- WQ-66 + WQ-64 同属 prompt 瘦身，改同一批段落
- WQ-71 建议复用 WQ-2 已建立的"客户端回传索引"链路传 reqId，不要用 ThreadLocal
- WQ-79② 与已有的 WQ-24（浮字寿命与帧率成反比）是同一类"按帧递减"问题，可一并处理

## 测试硬规矩（连续四次踩坑后确立，新增测试必须遵守）
1. **只断言可观测的最终状态**：玩家数据 / 物品栈 / 世界方块 / 用户可见输出。
   **禁止**断言内部判据函数或测试专用镜像方法（WQ-70 那例：镜像函数把生产条件抄了一遍，
   于是退款被完全覆盖的严重缺陷照样绿灯）。
2. **单向断言必须配正向断言**：只验证"某物不存在"会被"什么都没产出"蒙混过关
   （WQ-72 那例：`不要火的剑` 若退化成空结果，"不含 ember"恒真）。
3. **测到用户可见层**：别只测内部 helper——`keywordPicks` 干净不代表 `propose3` 的最终
   方案里没有那个材料（WQ-72 实测：玩家看到的结果里仍然有烬铁）。

---

## WQ-1 [x] 完成(b766474) 【高】法术 power 上限改为材料预算（"强度靠材料"的法术轨缺口）

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

## WQ-2 [x] 完成(fd41ec0) 【高】AI 提案信任边界重构：客户端只回传索引

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

## WQ-3 [x] 完成(d403e1a) 【高】P0-2：Epic Fight 改真软依赖（现在不装 EF 进不了游戏）

`src/main/templates/META-INF/neoforge.mods.toml` EF 依赖 `type="required"` → `"optional"`；
`Qianxiang.java:37` 附近对 `QianxiangEFCompat::register` 的监听注册加
`ModList.get().isLoaded("epicfight")` 守卫，且 `Qianxiang.java` 不得 import 任何 `yesman.epicfight.*`
类型（EF 引用已隔离在 `compat/QianxiangEFCompat` 一个类里，靠惰性类加载保护）。
`combat/AnimationLibrary`、`compat/` 其他类若有 EF import 一并检查隔离。
**验收**：装 EF 启动 + 临时注释 dev runs 的 EF 依赖再启动，均能进世界、锻造、施法。README 的
"软依赖不装不崩"从此为真。

## WQ-4 [x] 完成(d403e1a) 【中】P0-6：删除 compat/MovesetCompat.java 死脚手架 + 动作集语义统一

231 行反射机制（猜包名/反射取字段/扒泛型签名）服务于"动作集子代理尚未合并"这个已不成立的
前提（`MovesetCompat.java:198` 注释）。删类及全部调用点，统一走 `QianxiangEFCompat` 的类型化
直取。同时统一语义分裂：AI 链路"4 段+去重" vs 编辑器链路"6 段允许重复"
（`network/MovesetApplyHandler:26-29` 注释自认）→ 统一为 **6 段、允许重复**。
**验收**：编译绿；grep 无 MovesetCompat 残留引用；动作编辑器手测能应用 6 段含重复动作。

## WQ-5 [x] 完成(d403e1a) 【中】P0-3 收尾：三处文档/注释更新（纯文档，结论已定，照抄即可）

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

## WQ-6 [x] 完成(d8a0070) 【高】森罗之核：Boss 独占掉落 + 成就终点闭环

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

## WQ-7 [x] 完成(6044047) 【中】组件与分享码加版本号 + 迁移注册表

`CUSTOM_SPELL`/`CUSTOM_MOVESET`/`SPELLBOOK` 组件和蓝图分享码（`blueprint/BlueprintShareCodes`）
均无版本字段；schema 已发生过一次演化（spellJson 旧词折算）。趁字段还少：
1. 分享码 payload 加 `"v": 1`；解码时缺省视为 v0 走现有旧词折算，未知高版本给出
   「请更新模组」的聊天提示而不是静默失败。
2. 组件 codec 加 optional int version（缺省 0），并建一个 `SpellJsonMigrations` 静态注册表
   （v0→v1 就是现有 normalizeLegacyWords 逻辑的搬家）。
**验收**：旧格式分享码（不带 v 字段）仍能导入；GameTest 覆盖 v0 码 roundtrip。

## WQ-8 [x] 完成(1b44305) 【低·快赢】heal/buff 打怪物：扣蓝无反馈

`spell/SpellEffectEngine.resolveHit`（约 212-223 行）要求 isAlly，heal/buff 的 projectile/beam/touch
形式对怪物命中时什么都不发生，但法力已扣、冷却已进、无任何提示。修法：命中非友方时
退回法力（或干脆不进冷却）+ actionbar 提示「该法术只能作用于友方」（lang 键中英各 1）。
**验收**：手测 heal 弹体打僵尸出提示且蓝退回。

## WQ-9 [x] 完成(c0fa031) 【中】法术冷却同步 + HUD 显示 + 同步风暴修复

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

## WQ-13 [x] 完成(1b44305) 【中·经济】声望折扣错误作用于收购单的 costA——高声望卖货投入减半

`QianxiangNPCBase.applyReputationPricing`（:152-168）对**每条** offer 的 costA 打折，但收购型
offer（碎片×2→5 绿宝石）的 costA 是玩家交出的货。声望 25% 时交货量 2→1，收益翻倍；
屠杀烙印反向双重惩罚。**修法**：只对 `getBaseCostA().is(Items.EMERALD)` 的售出型条目写
specialPriceDiff。（压到 0 白嫖不成立，已核实三重下限保险，勿改动那部分。）

## WQ-14 [x] 完成(de9ddee) 【中·NPC】NPC 继承整套 Villager 大脑：会转职/被原版补货绕过/被僵尸转化消灭

`QianxiangNPCBase extends Villager` 未锁职业未剪 brain：①附近有讲台等 job-site 会转职，
`updateTrades` 是 append——8 条千相交易后面接原版职业交易；②转职后 brain 的 WorkAtPoi 每日
`restock()` 绕过 6eb1a04 的时间戳闸门（那闸门只在开 GUI 时查）；③僵尸打死千相 NPC 变原版
僵尸村民，治愈后 NPC 永久变原版村民。
**修法**：构造器锁 NITWIT 职业 + 覆写 `updateTrades()` 为空 + brain 去掉 job-site/work 活动；
僵尸转化需拦 `Zombie.killedEntity` 路径（覆写 die 或事件取消转化）。
**验收**：手测放讲台 NPC 不转职；僵尸杀 NPC 不出僵尸村民。

## WQ-15 [x] 完成(de9ddee) 【中·卡死】AI 解析中关 GUI → 锻造台永久卡 STATE_PARSING（落盘持久）+ 线程池泄漏

`ForgeTableAIHandler` 复位依赖"玩家此刻仍开着菜单"（:56-68），关 GUI/下线后回包直接 return；
`tickServer` 只对 STATE_COMPLETE 倒计时，PARSING 无超时；状态还落盘。玩家中途开了另一台
锻造台还会把复位打到错的方块上。另外 `AI_EXECUTOR`（:23）static 永不 shutdown：单人游戏
AI 请求进行中退出世界时 `enqueueWork` 提交到已停机 server，lambda 持有
IPayloadContext→ServerPlayer→ServerLevel 整个对象图，反复进出世界叠加泄漏。
**修法**：①AI 任务捕获 `BlockPos`+维度 key，回调按坐标定位 BE 复位（不经 containerMenu）；
②`tickServer` 给 PARSING 加超时（200~600 tick）自动回 idle；③`loadAdditional` 把读到的
PARSING 归一为 IDLE；④监听 `ServerStoppingEvent` 对 executor `shutdownNow` 并丢弃队列。
**验收**：发起 AI 后立即关 GUI，超时后方块状态自动复位；重开存档无残留 PARSING 粒子。

## WQ-16 [x] 完成(1b44305) 【中·落点】传送门落点含流体高度图——amplified 地形可直接落岩浆/海面

`MyriadWildsPortalHandler:98-105` 用 `MOTION_BLOCKING_NO_LEAVES`（流体计入），落点无安全复检；
y<=min+1 时兜底 y=100 不查是否闷在石头里；`getHeightmapPos` 未生成区块时主线程同步生成
（amplified 很慢，可感知卡服）。
**修法**：`WORLD_SURFACE` 取高后向上扫两格可站立且脚下非流体的位置，找不到建 3×3 黑曜石基座。
**验收**：手测传送到海洋/岩浆湖坐标不落液体。

## WQ-17 [x] 完成(1b44305) 【低·反馈】蓝图使用失败也写相谱 + Blueprint 包无限流

`BlueprintServerHandler:79-81` 的 `withEntry` 在 `if (ok)` 之外——缺材料连点"使用"每次都写一条
相谱（500 上限会被垃圾挤掉真历史），且 handler 无冷却可被刷。
**修法**：`withEntry` 移进 ok 分支；handleUse/handleSave 加每玩家 ~10 tick 冷却。

## WQ-18 [x] 完成(8055448) 【低·一致性】AI 放料同种材料堆同一槽 → 零件数少于 AI 承诺

`AiPlaceMaterialsHandler.findMaterialSlot`（:100-112）优先堆叠已有同种槽，而 compose 按槽计
零件（数量无关）——AI 报 [铁锭,铁锭,煤] 实际只算 2 零件；蓝图路径却逐槽铺开，两路径不一致。
**修法**：`findMaterialSlot` 改为优先空槽，无空槽才堆叠。
**验收**：GameTest：AI 放料含重复材料时占用不同槽位。

## WQ-19 [x] 完成(1b44305) 【低·经济】交易声望按笔计无节流——Shift 一键 12 笔直冲满折扣

`notifyTrade`（:90-108）每笔 +1 声望且每笔写一条相谱，Shift 批量成交两次点击即触顶 ±25%，
与 WQ-13 复合成"卖货涨声望→声望让卖货更赚"的印钞机。
**修法**：声望加成设每 NPC/玩家/MC 日上限（如 5）；相谱按会话合并一条。

## WQ-20 [x] 完成(cc6f1ab) 【低·加固】上行包字段无长度上限（三处）

`AiPlaceMaterialsPayload:29-32` 的 `ByteBufCodecs.collection` 不带 maxSize，恶意包可带上万条
id 各触发注册表查询+41 格扫描；`AiRequestPayload.java:45,47` 与 `BlueprintSavePayload.java:25`
同病。**修法**：collection/字符串 codec 补 maxSize（材料列表 16、需求文本 1024、蓝图名 64 等
合理上限），handler 里再截断。

## WQ-21 [x] 完成(1b44305) 【低·软锁】回程锚点放置可静默失败 → 玩家困在维度

`MyriadWildsPortalHandler:86-96` 只试 2 个候选点，都不可替换就 return 无提示；玩家挖掉去程
裂隙岩后无法回程。**修法**：候选扩为 3×3 必要时强制放置，失败发聊天警示。

---

# 第三批：客户端/同步/性能狩猎发现（2026-07-26 深夜第二队侦察代理）

## WQ-22 [x] 完成(cc6f1ab) 【高·多人】全部命令零权限门控

全仓库 `grep "requires("` 零命中：多人服任意玩家可 `/qianxiang kit`（无限刷全套装备/刷怪蛋）、
`/qianxiang dim`（无条件跨维度，绕过位格门控与裂隙精髓消耗）、`/qianxiang ask <串>`（往单线程
无界 AI 队列塞 HTTP 任务并写 jsonl 日志）。
**修法**：kit/dim/ai 子命令 `.requires(s -> s.hasPermission(2))`；saga/blueprint 保持玩家可用；
ask 若保留给玩家则加 per-player 冷却（如 100 tick）。
**验收**：非 OP 玩家 tab 补全看不到 kit/dim；OP 正常使用。

## WQ-23 [x] 完成(cc6f1ab) 【高·多人】施法入口缺存活/观战校验 + 失败提示构成 1:1 包放大

`SpellCastHandler.handle`（:37-92）只判 `instanceof ServerPlayer`：观战者按 V 照常施法
（ender 元素还会 `connection.teleport`）；死亡瞬间同理。且冷却/法力不足路径每个被拒上行包
回一个 actionbar 下行包（:69/:73/:109/:113），改包客户端可用最小成本让服务端对自己单播海量
数据、全占主线程。
**修法**：入口加 `if (!p.isAlive() || p.isSpectator()) return;`；失败提示 per-player 20 tick 节流。
**验收**：观战模式按 V 无任何效果；GameTest 覆盖 spectator 拒绝。

## WQ-24 [x] 完成(c0fa031) 【中·体验】伤害浮字寿命与帧率成反比——高刷屏上一闪即没

`ClientDamageNumbers:117,134` 用 `getGameTimeDeltaPartialTick(true)`（tick 内插值系数 0~1，
非帧间 delta）累加 age：30FPS 活 2 秒、144FPS 只活 0.42 秒、240FPS 0.25 秒。
**修法**：改用 `event.getPartialTick().getRealtimeDeltaTicks()`，或把年龄推进移到
`ClientTickEvent.Post` 渲染只读。顺带：`ACTIVE` 表在断线/换世界不清空，重进后旧 entityId
撞新实体会冒幽灵数字——在 LoggingOut/LevelUnload 清一次。

## WQ-25 [x] 完成(5f57a97) 【中·性能】动态武器模型每帧全量重算 key + bake 失败静默重试风暴

`DynamicWeaponModel.getRenderPasses`（:62-68）每帧每栈重算变体 key：`shapeFromCustomName`
（toLowerCase+14 次 contains）、`hasGranted` 每次开 Stream 对每 key toString（Texture:473-474）、
`List.of(new DynamicPass)` 再分配——GUI 40 格×60FPS=每秒数千次分配纯 GC 压力。更糟：
`CACHE.computeIfAbsent(key, ::bake)`（:108）在 bake 抛异常时不留映射且 catch(Throwable) 吞异常
→ 该变体每帧重绘 16×16 并尝试注册纹理，持续卡顿零日志。
**修法**：变体 key 按组件哈希做一层缓存（或存 ItemStack 附带缓存）；bake 失败向 CACHE 写入
哨兵 Variant 阻止重试，并打一条 WARN（每变体一次）。
**背景**：变体总上界 720 个（shape20×color9×tier4），与锻造物数量无关——缓存本身设计没错，
问题只在 key 计算频率和失败路径。

## WQ-26 [x] 完成(1b44305) 【中·平衡】Shift 连锻一次点击灌 64 条相谱 + 位格直冲 100 越过维度门控

QUICK_MOVE 循环调 `quickMoveStack`（ForgeTableMenu:194-206），每轮 `recordForge` 追加一条
相谱 + `withBumpedPosition(1)`。材料槽各放 64 个 shift 一下：64 条相谱、位格 0→64+
（MAX=100），直接跳过 `WILDS_POSITION_THRESHOLD=3` 的门控设计。
**修法**：`recordForge` 按批次合并一条（带数量）；位格增长每次交互（或每 MC 日）限 +1。
**验收**：shift 连锻后 /qianxiang saga 只多一条记录；位格增幅受限。

## WQ-27 [x] 完成(c0fa031) 【低·清理】客户端静态状态跨世界残留（两处）

①`ClientForgeTableAI.onResult/lastResult` 是 static，断线/崩溃不走 onClose → 持有整个 Screen
对象图；且 `receive`（:41）无条件回发 `SpellJsonReportPayload`（锻造台已关也发）。
②`ClientSpellInput`（:20-24）tick 事件无 `player == null` 守卫（主菜单触发理论可抛），且用
if 非 while 消费点击。
**修法**：监听 `ClientPlayerNetworkEvent.LoggingOut`/`LevelEvent.Unload` 清静态字段；
receive 校验当前 Screen 再回发（注意 WQ-2 重构会改这条链路，先做 WQ-2 的话本条①随之消解）；
ClientSpellInput 加守卫、if→while。

## WQ-28 [x] 完成(5f57a97) 【低·性能】两处热路径开销

①homing 弹体每 tick `getEntitiesOfClass(inflate(10))`+sort（SpellProjectileEntity:105-113）——
改每 4 tick 重选目标并缓存目标 id。②锻造台任意一次容器点击（含背包无关格）全量重跑
`ForgeComposer.compose` 并 setItem 新产物栈触发同步（ForgeTableMenu:252-258，quickMoveStack
还会再调一次）——对材料槽内容做哈希，未变则跳过重算。

## WQ-29 [x] 完成(1b44305) 【低·防御】产物槽 shift 部分搬运会清空余量（当前不可触发，防未来）

`quickMoveStack` 产物分支 `moveItemStackTo` 部分成功也返回 true，随后无条件
`slot.set(EMPTY)`（:231）。今日 compose 产物恒 count=1 不可触发（第一队已核实），但产物
一旦支持堆叠即成吞物品 bug。**修法**：只在 `stack.isEmpty()` 时才清空。一行防御。

---

# 第四批：数据 json 审计发现（2026-07-27 凌晨第三队侦察代理，成就/loot/worldgen/recipe/tags 全查）

## WQ-30 [x] 完成(cc6f1ab) 【P0·数据】rift_stone 与 void_ore 缺 mineable 标签——挖掉永不掉落

仓库 `data/` 下没有 `tags/block/` 目录，两方块又都 `.requiresCorrectToolForDrops()`
（QianxiangBlocks.java:33/:68）。1.21 规则：不在任何 `minecraft:mineable/*` 标签的方块对所有
工具 correctForDrops=false → `loot_table/blocks/rift_stone.json`、`void_ore.json` 永不执行。
裂隙岩（传送门框）拆一次永久损失；虚痕矿挖不出 void_shard（仅剩 Boss 掉落兜底）。
**修法**：新建 `data/minecraft/tags/block/mineable/pickaxe.json` 收录两方块；按设计意图可再加
`needs_iron_tool.json`（void_ore 建议铁镐档）。**验收**：生存模式镐挖两方块有掉落。

## WQ-31 [x] 完成(d8a0070) 【高·数据】legendary_material 在 kill_warden 同一瞬间必然自动弹出

守望者战利品第 1 池 100% 必掉 void_shard 1-2，而该成就条件正是"持有 void_shard 或
reverse_core"——两个 challenge 成就同帧弹出，终局沦为击杀附赠。
**修法**：与 WQ-6 联动——WQ-6 落地后条件改"持有 warden_core"（首选，一并解决）；
若先行止血，可临时收窄为只认 reverse_core（需 void_shard+rift_essence+ender_eye 再合成一步）。
**领此单前先看 WQ-6 状态，避免改两次。**

## WQ-32 [x] 完成(dfa7641) 【中·数据】first_forge 可在工作台达成（从未碰过锻造台）

`advancement/story/first_forge.json` 的 items 列表含 `qianxiang:spell_book`，而 spell_book 有
纯原版材料的工作台配方（recipe/spell_book.json）——工作台合一本书同时点亮 first_forge 和
子节点 story/spell_book，"初铸"文案与条件背离。
**修法**：first_forge 的 items 删掉 spell_book 一项。

## WQ-33 [x] 完成(dfa7641) 【中·平衡】void_ore 生成密度约为原版钻石 8 倍——LEGENDARY 材料白菜价

`placed_feature/void_ore.json`：count 8/区块 × size 8，y∈[-64,32]，无 rarity_filter。挖矿
10 分钟即可架空 Boss 掉落线（与 WQ-6 的独占性设计冲突）。
**修法**：count 降 1-2、size 降 4、加 rarity_filter；与 P3 观察项（rift_essence_from_void_shard
1 换 2 的兑换曲线）一起通盘调平。

## WQ-34 [x] 完成(dfa7641) 【中·体验】守望者自然刷新无 spawn_costs——多只 Boss 血条叠 HUD

`biome/myriad_wilds.json` monster 池守望者权重 3、spawn_costs 为空对象，同屏可游荡多只
各带 ServerBossEvent 的守望者。
**修法**：spawn_costs 加 `"qianxiang:myriad_warden": {"energy_budget": 0.12, "charge": 1.0}`，
权重降 1。

## WQ-35 [x] 完成(dfa7641) 【中·可发现性】22 个配方全部没有 recipe advancement——配方书完全隐身

`advancement/` 下无 `recipes/` 目录，不装 JEI 的玩家无法在配方书里看到任何千相配方
（包括入口方块锻造台）。
**修法**：至少给 forge_table、rift_essence、spell_book、reverse_core 等关键配方补
`advancement/recipes/<name>.json`（has_item criteria + rewards.recipes），其余可批量生成。

## WQ-36 [x] 完成(dfa7641) 【低·EF 数据】item_keyword 正则与显式文件打架 + phase_staff 兜底类型漂移

①`capabilities/weapons/item_keyword/qianxiang_blades.json` 的正则 `qianxiang:.*_blade` 只能
命中已有显式 json 的两把刀，且把声明 dagger 的 bone_blade 导向 tachi 连段——删掉该 keyword
文件（types 文件留作参考）。②`capabilities/weapons/phase_staff.json` 静态兜底写
`epicfight:sword`，Java 动态分类是 DAGGER——静态 json 改 `epicfight:dagger` 对齐。

## WQ-37 [x] 完成(dfa7641) 【低·数据】三个空功能标签 + biome 废弃字段

①`tags/item/materials/defense.json`/`reflect.json`/`slow.json` 全空——DEFENSE/REFLECT 是防具
体系仅有的纯防御算子，datapack 扩展入口是哑的。建议填充：defense=shield/turtle_scute/
iron_block、reflect=cactus/nautilus_shell、slow=cobweb/soul_sand/honey_block（注意 WQ 已把
DEFENSE 映射到护腿原型，填充后玩家可用原版材料锻护腿）。②`biome/myriad_wilds.json` 残留
1.19.4 已废弃的 `"precipitation": "rain"` 字段（静默无效），删除。

## WQ-38 [x] 完成(dfa7641) 【低·worldgen】荒光草悬浮空中 + 微光树叶徒手必掉

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

## WQ-39 [x] 完成(9007d69) 【P0·AI】prompt 塞全量材料库 ≈4 万 token——qwen2.5:7b 根本装不下

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

## WQ-40 [x] 完成(9007d69) 【高·AI】默认 5 秒超时必超 + 超时不入熔断 = 每次白等 10 秒

`AIConfig.DEFAULT_TIMEOUT_SECONDS=5`（:44），大 prompt 下 7B 模型必超时；`HttpTimeoutException`
被 `AIClient.isConnectionIssue` 有意排除（只算连接类），熔断永不开 → `AIGateway:119-127`
每次都 5s+重试 5s=10 秒后才兜底。
**修法**：默认超时提到 30s（已有配置的存档要迁移默认值）；熔断加第二计数器"连续请求超时"
（阈值 3，与连接失败分开计数、分开日志文案）。
**验收**：断网/慢模型场景下第 4 次请求起零延迟落兜底。

## WQ-41 [x] 完成(9007d69) 【高·AI】两个材料 id 处理 bug——AI 选对了材料也会被剔除或放料失败

①`normalizeName:724-728` 给无冒号名强加 `qianxiang:` 前缀，"iron_ingot"→"qianxiang:iron_ingot"
永不匹配，`MaterialLibrary.find` 的短名容错成死代码——小模型省 namespace 是最高频瑕疵，
方案整条被清空静默落兜底。改法：无冒号时不加前缀交给 find 短名匹配，命中取
`entry.registryName()`。②`:582-587` 用 equalsIgnoreCase 匹配但 `picks.add(normalized)` 存的是
AI 原样大小写，"Minecraft:Iron_Ingot" 一路传到客户端图标与放料的 `ResourceLocation.tryParse`
全返 null（MC 不接受大写）——改 `picks.add(entry.registryName())`。
**验收**：GameTest：喂含裸名/混合大小写材料的 AI JSON，断言解析结果全为规范 registryName。

## WQ-42 [x] 完成(9007d69) 【中·AI】未开结构化输出 + temperature 0.7 过高

`AIClient` Ollama 分支加 `body.addProperty("format","json")`，OpenAI 分支加
`response_format:{"type":"json_object"}`；temperature 0.7→0.2（照抄 registryName 类任务）。
两行配置消掉一半解析失败面。

## WQ-43 [x] 完成(9007d69) 【中·AI】extractJson 四类必败输入 + 解析失败零日志

实测矩阵（Gson 2.13.1）：围栏/闲聊/单引号/裸字段名/数组尾逗号 ✅ 容错；**对象尾逗号、
字段名大小写（"Proposals"）、deepseek-r1 的 <think> 块、顶层数组** ❌ 整体失败——失败后
无任何 WARN，玩家只看到状态灯变黄和"（基础配方）"。
**修法**：extractJson 先剥 ``` 围栏与 <think> 块再括号配平取最后一个完整对象，顶层数组包一层
`{"proposals":...}`；字段读取走大小写不敏感 helper；解析失败记 WARN+原文前 300 字进 jsonl；
prompt 加"summary 用与玩家输入相同语言，≤20 字"（gpt 类模型摘要语言错乱）。

## WQ-44 [x] 完成(2854d57) 【中·AI】confirm 模式三处失准

①差异化信息"当前已放入材料"埋在 prompt 最末尾，前面全是推荐向段落——7B 注意力被稀释，
评价模式答非所问变成重新推荐。改法：confirm 模式把当前材料+任务声明（"你的任务是评价
不是重新推荐"）提到 prompt 最前，砍掉【功能性需求指引】【宽泛需求语义】【效果词典】三段。
②`ask():186-192` 不判 confirm 模式下材料槽为空——空槽点确认 AI 对空气做评价。改法：
`mode==confirm && mats.isEmpty()` 直接走 `FallbackRecipes.proposeForConfirm`。
③`isFallback():107-111` 漏 `confirm.summary` 前缀——confirm 兜底时状态灯仍绿，玩家误以为
是 AI 评价。补前缀或改判 `RecipeResult.fallback` 字段。
另：【EF 动画库】段改为命中动作关键词才插入；【自由法术】段只在 type==magic 时插入。

## WQ-45 [x] 完成(8663a70) 【中·兜底】FallbackRecipes 质量包：否定语义/单字误触/六组缺失/七材料不可达/档位塌缩

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

## WQ-46 [x] 完成(2854d57) 【低·性能】MaterialLibrary 每次 find 全表扫 + 同一 JSON 解析三遍 + 死代码

`find()` 每调用重跑 snapshot()（1391 个 ItemStack+概念推导），被六处逐材料调用，一次"全部
增益"兜底 ≈3.6 万次概念推导（服务端后台线程，给玩家叠秒级延迟）——snapshot 加按
PhaseMaterialRegistry 版本号失效的缓存，find 查预建 Map。`ask()` 里 extractConfirmMessage/
extractQuestions/parseProposals 三次重复解析——解析一次传引用。删死代码
`MaterialLibrary.buildFromTags:209-234`（零调用方且逻辑已分叉）。

## WQ-47 [x] 完成(HEAD) 【低·数据资产】jsonl 日志补齐"建议 vs 采纳"链路（DP-3 飞轮的地基）

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

## WQ-48 [x] 完成(250cd7e) 【中·返工】WQ-11 蓝图选料仍组件盲——会吃掉玩家高价值同款装备，且蓝图不可复现

split(1) 搬原栈和限 36 格都改对了，但 `:99` 匹配仍是 `s.is(item)` 取背包**首个命中**：
①玩家的经验修补附魔镐排在垃圾镐前面会被直接吃掉，无确认；②蓝图作者用白板附魔书存的
方案，使用者拿修补附魔书去配，ForgeComposer 读真实栈 → 产物属性与蓝图预览不一致。
**修法**：选料改为"最不值钱的同 id 匹配"（无附魔、无自定义名、耐久最低优先）；
另补 WQ-11 验收点名的 GameTest（低耐久组件保留 + 护甲槽不被取用）。
顺带：`:102` 中途失败不回滚——前几件材料已进台（不丢但体验差），失败时把已放材料退回背包更好。

## WQ-49 [x] 完成(250cd7e) 【中·玩法】护甲线"耐久靠材料"不成立——BASE_HIDE 不产 durability，皮甲恒定 150

`AttributeScheme:238-256` 只有 BASE_METAL/WOOD/BONE 累加 durability，`case BASE_HIDE` 只给
armor 和 powerScore。纯皮革组合锻出的四件护甲 durability==0 → override 回落固定 150，
与材料档位完全无关。且这是**已生效的平衡变更**（此前护甲因缺组件永不磨损，现在 150 次碎）。
**修法**：BASE_HIDE 分支补 durability 系数（建议按皮革基底 ~120 + 档位乘数走现有公式）；
新增 GameTest 覆盖纯皮革护甲 durability 随档位变化。
**附**：`DrawbackHandler` 的 frail 代价（每命中 hurtAndBreak）在耐久修复后**首次真正上线**，
数值是在恒 no-op 路径上调的——实机冒烟一次 frail 武器的磨损速度是否合理。

## WQ-50 [x] 完成(250cd7e) 【低·语义】recordForge 3 秒限流误伤"3 秒内锻两件不同产物"

1b44305 的限流在读产物属性**之前**就返回——快速手动锻造两件不同物品，第二件既不写相谱
也不涨位格。若非 WQ-26 的预期取舍，应改为按产物 id 去重/合并计数，而不是纯时间窗拦截。

## WQ-51 [x] 完成(8663a70) 【低·纵深】三个加固点（不阻塞任何结单）

①`ForgeTableBlockEntity` 补覆写 `canPlaceItem(int,ItemStack)` 返回 `slot < MATERIAL_SLOTS`——
当前无 capability 注册所以不可利用，但这是产物槽最后一个默认 true 的口子；
②`ForgeTableMenu.removed()` 清产物槽后调一次 `updateCraftingState()`，避免方块滞留
STATE_READY 粒子；③`dropContentsOnRemove` 的 `items.clear()` 加注释说明其防抢跑职责。
另记：8055448 与 6bdc4c4 是同批拆分提交，前者包含依赖后者的测试（单独 checkout 8055448
测试必红），bisect 时注意。

---

# 复核报告二：cc6f1ab / 1b44305 / dfa7641 / d8a0070 对抗性审查（2026-07-27）

**结单确认（证据充分、无残留）**：WQ-30、WQ-22（requires 正确加在 kit/dim/ai 子节点，
saga/blueprint 保持玩家可用）、WQ-23（守卫位置正确，节流 key 含 playerId 无跨玩家污染）、
WQ-53、WQ-20（三处上限均高于合法客户端实际值，不会误踢）、WQ-13（非绿宝石 offer 显式
清零 specialPriceDiff，印钞链断开）、WQ-17、WQ-29、WQ-56、WQ-32~38 全部七项
（recipe advancement 22/22 精确对应；wildlight_patch 的 offset 谓词经核实不会哑火）。

**有条件结单**：WQ-19（实现是 30 秒墙钟窗口而非"每 MC 日 5 次"，跨重启清零，且 action
键基数无界永不清理——若接受此口径可结单，否则返工）；WQ-55（校验+限流已前置，但
"队列深度上限、满则回兜底"未做，AI_EXECUTOR 仍无界）；WQ-6（物品/资产/独占性达成，
lang 683/683 双向零差集，但 GameTest 只断言了"无配方产出"，商人表/其它 loot/kit 三条
回归面无护栏，建议补断言）。

## WQ-57 [x] 完成(HEAD) 【高·返工】WQ-8 的退蓝实现是无限法力电池——比原 bug 更糟

`refundOnInvalidTarget` 挂在 `SpellEffectEngine.resolveHit` 的 per-target 分支（:222/:230），
而 resolveHit 被 AoE 与链式循环**逐目标**调用（:118/:141/:326）。后果三条：①一发 AoE
heal 命中 5 只怪退 5 次；②退款额是写死的 `10 + 5*1 = 15`（:390-392）与实际 manaCost 无关，
cost<15 的法术每次净赚；③命中集合里同时有友方时**治疗照常生效还额外退蓝**——补偿变奖励。
**修法**：退款上提到 cast 层，一次施法只结算一次、退实际 manaCost、且仅当零个目标生效时退。
**验收**：GameTest：AoE heal 命中 N 个非友方，断言法力净变化 == -manaCost（不是 +）。

## WQ-58 [x] 完成(HEAD) 【中·返工】WQ-21 锚点候选未排除玩家头部格——会把裂隙岩放进玩家头里

`MyriadWildsPortalHandler:97-99` 的候选框 `betweenClosed(arrival.offset(-1,0,-1),
arrival.offset(1,2,1))` 只跳过 arrival 自身，未跳过 `arrival.above()`。脚边一圈不可替换而
头顶是空气时（1×2 竖井/洞穴落点）锚点放进玩家头部造成窒息。**一行修法**：
`if (candidate.equals(arrival) || candidate.equals(arrival.above())) continue;`
另低危：强制兜底 :106 的 `canBeReplaced() || !isCollisionShapeFullBlock(...)` 会覆盖
箱子/台阶/楼梯——等于破坏玩家容器，条件应收紧为只覆盖可替换方块。

## WQ-59 [x] 完成(HEAD) 【中·返工】WQ-16 落点算法三类地形塌陷 + 污染主世界地形

①全空气柱（虚空上方）：heightmap 返回 minBuildHeight → clamp → 玩家被放到**世界最底层**
y=min+2，不掉虚空但等同活埋；②树冠/雪层/台阶：`isSolidRender` 对树叶 false，落在树上会
在树冠上方凭空生成悬空裂隙岩；③**回程去主世界也走同一函数**（:71 无分支）→ 主世界树顶/
海面被凭空放一块 `qianxiang:rift_stone`，旧实现从不改地形。④工单点名的"主线程同步生成
区块"未处理，`ensureReturnAnchor` 的 1183 次 getBlockState 还可能顺带同步加载相邻区块。
**修法**：落点失败时改用"回退到维度出生点附近"而非就地造块；回程路径不建基座；
基座材质用黑曜石且只在目标维度内建；heightmap 查询前先判区块是否已加载。

## WQ-60 [x] 完成(HEAD) 【中·返工】WQ-31 未达成目标——warden_core 同样 100% 掉落，双成就仍同帧弹

`loot_table/entities/myriad_warden.json` 末池 warden_core 是 `rolls 1.0` 无 conditions 无
looting，与原来的 void_shard 第 1 池**触发时机完全等价**：kill_warden 与 legendary_material
（parent 正是 kill_warden）仍在同一帧各弹一个 challenge 框。病因未消除，只是附赠品改了名。
**修法（二选一）**：①`legendary_material` 条件改为"锻出含 warden_core 的 LEGENDARY 产物"
（与 forge_legendary 拆开：前者 goal 帧、后者 challenge 终点）；②legendary_material 降为
goal，challenge 唯一留给 forge_legendary。
附带低危：forge_legendary 只检查材料槽有核就授予，不校验产物是否真为 LEGENDARY，与成就
文案"锻造出一件传奇相器"错配。

## WQ-61 [x] 完成(HEAD) 【低·返工】FallbackRecipes.legendaryCatalyst() 的判据恒为真

`:699-703` 用 `MaterialLibrary.exists("qianxiang:warden_core")` 判断——exists 查的是注册表
索引，装着 mod 就恒 true，不是"玩家有核"。后果：所有攻击/法术向 LEGENDARY 兜底方案一律
要求 Boss 独占材料，没打过 Boss 的玩家在 AI 掉线时拿到永远配不齐的方案（AI 放料静默失败）。
**修法**：判据改为"玩家背包/材料槽中存在 warden_core"，或保留 rift_essence 方案作为并列
备选让玩家二选一。

**流程提醒（给修理会话）**：本批 20 余张单在队列里的复选框大多仍是 `[ ]`，请领活/完成时
及时回填状态+提交号；cc6f1ab/1b44305/dfa7641 三个提交新增 GameTest 数为 0，而
WQ-22/26/30/16 的验收条款要求实测或断言——补测或在工单里注明"人工冒烟已做"。

---

# 复核报告三：AI 链路五项（9007d69 / 2854d57）对抗性审查（2026-07-27）

**结单确认**：WQ-41 ✅（四类输入矩阵全通、picks 存规范 registryName、下游无残留 null 路径）、
WQ-46 ✅（三次解析合一、缓存以 `PhaseMaterialRegistry.all()` 身份键失效——"/reload 后新材料
立即可见"的保证在库层仍成立、buildFromTags 已删）。建议顺手清 `buildSystemPrompt` 6 参重载
（已无调用方）。

**返工清单（按优先级）**：

## WQ-62 [x] 完成(7780628) 【严重·返工 WQ-40】默认超时 30 秒对所有玩家都不生效（侦察会话已亲自核实）

> 修理备注：模板 `timeout_seconds` 已改 30 并引入 `config_version`；`AIConfig.parse` 一次性迁移
> （无版本号且 ≤10 → 抬 30 写回 + INFO），GameTest `aiConfigMigratesLegacyTimeout` 覆盖读旧文件→写回 30。

`AIConfig.java:48` 常量改成 30，但 `:73` 的 `DEFAULT_FILE` 模板里仍写死
`"timeout_seconds": 5`——首次启动把模板写盘，下次读回来就是 5；常量 30 只在"文件缺该字段"
时才生效。老存档也零迁移（`:161-163` 只 clamp）。**WQ-40 的核心改动等于没落地。**
**修法**：①模板同步改 30；②加一次性迁移（读到 `timeout_seconds<=10` 且无 version 字段时
抬到 30 并写回，写一条 INFO）；③配套：30s+重试=最坏 60 秒白等，而 `ForgeTableAIHandler:34`
冷却只有 3 秒，等待期还能堆任务——建议 UI 侧加"AI 思考中/可取消"或把冷却提到超时同量级。

## WQ-63 [x] 完成(7780628) 【高·返工 WQ-40】熔断两处计数缺陷：交替故障永不开、非 2xx 完全不计数

> 修理备注：`AIGateway.recordOutcomeClassified` 三类故障（CONNECT/TIMEOUT/ENDPOINT）各自独立计数、
> 成功或「有响应但不可用」才清零；`AIClient` 非 2xx 置 LAST_ENDPOINT_ERROR 进 ENDPOINT 计数（阈值 3 开熔断）。
> GameTest `breakerOpensOnAlternatingFailures` / `breakerOpensOnEndpointErrors` 覆盖。

①`AIGateway.java:207` 连接失败分支 `TIMEOUT_FAILS.set(0)`、`:216` 超时分支
`CONNECT_FAILS.set(0)`——"连一次不上、超一次时"交替出现时两个计数器永远回不到阈值 3，
熔断永不开。②`AIClient.java:227-231` 的非 2xx 返回 null 而非抛异常，`recordOutcome` 落到
"两个计数器都清零"分支 → **端点持续 400/500 时永不熔断**，每次请求两发 HTTP。
这条与 WQ-64 的 `response_format` 兼容风险叠加会很难看（端点全挂且看不出原因）。
**修法**：两个计数器改为各自独立衰减（成功才清零，另一类失败不清）；非 2xx 计入独立的
"端点错误"计数并同样能触发熔断。

## WQ-64 [x] 完成(78d675e) 【中·返工 WQ-42】response_format 无降级路径，会让部分兼容端点整条 AI 全挂

位置和 temperature=0.2 都对，但不支持 `response_format` 的"OpenAI 兼容"端点（旧版
llama.cpp server、部分自建代理、一些国产兼容层）会 400 → `send()` 返回 null → 静默兜底，
叠加 WQ-63 的"非 2xx 永不熔断"→ 每次请求白发两次 HTTP，玩家只看到一行 WARN。
**修法**：400 且 body 提及 response_format/unsupported 时，去掉该字段重发一次，并把
"本端点不支持结构化输出"记进内存开关（进程内不再重试该字段）。

## WQ-65 [x] 完成(78d675e) 【中·返工 WQ-43】extractJson 顶层数组分支是死代码，且测试掩盖了缺陷

`PhaseAIRecipeService.java:808-813`：`firstBalanced(text,'{','}')` 先执行，对
`[{...},{...}]` 会命中数组内**第一个对象**并直接返回，第 ③ 行的 `{"proposals":...}` 包装
永远走不到。结果不是解析失败而是**静默只保留第一个方案**（被 `:563-568` 的"兼容旧版单方案"
分支收下），其余方案丢失无日志。新增的 GameTest 只断言"能抽出 JsonObject"，恰好被这条
错误路径满足而通过——**测试掩盖了缺陷**。
**另**：工单点名的"字段名大小写（"Proposals"）"未做（无大小写不敏感 helper）、对象尾逗号
仍失败；实现取"第一个"完整片段而非工单要求的"最后一个"，模型前置寒暄带花括号时整体失败。
**修法**：改为"优先取包含 proposals 键的完整对象"；补大小写不敏感字段读取；尾逗号修补或
在工单里明确放弃；数组用例的断言改成校验 `proposals` 数组长度==2。
（做对的部分勿动：`firstBalanced` 的字符串字面量与转义处理经逐字符核对正确，
`{"name":"a{b}c"}` 不会算错；剥 `<think>` 用精确标签匹配不误伤正文尖括号；解析失败 WARN
+前 300 字已补。）

## WQ-66 [x] 完成(78d675e) 【中·返工 WQ-39/44】prompt 仍约 19K 字符，超过刚设的 num_ctx 8192

材料段确实从 ~114KB 降到 ~4.5KB（`MaterialRecall` 三组召回 24/12/48 上限，关键词不命中时
按产物类型退回常用算子——**"我要一把剑"有米下锅，这点做对了**）。但静态说明书没削：
头部+功能性指引+强度代价+规则+自由法术+动作定制+宽泛语义+效果材料 ≈8.3K 字符，加上
`AnimationLibrary.promptSummary()` ≈3.5K（crossIndex 重复列出）、EffectGlossary ≈2K、
候选材料 4.5K → **recommend 模式约 19K 字符、confirm 约 16K**，按 qwen 分词 10~13K token，
仍超 8192。病根从"材料库淹没需求"变成"六段说明书淹没需求"。
**修法**（与 WQ-44 的"另两条"是同一处改动，一起做）：①EF 动画库段按动作关键词命中才插；
②自由法术段仅 `type==magic` 时插；③confirm 模式补砍【功能性需求指引】（工单点名的第三段，
现仍在）；④功能性指引与 typeDescription 去重；⑤**补 prompt 长度日志**——验收第一条
"日志确认 <8KB"目前无从执行；⑥补 few-shot 与独立【输出格式】段（工单④⑤未做，
现有内联示例本身 JSON 合法，不污染输出，可保留）。

## WQ-67 [x] 完成(78d675e) 【低·返工 WQ-39】召回三处边缘：白名单空池、confirm 当前材料缺席、UGC 材料被压低

①`MaterialRecall.java:97` 补足组要求 `!functions().isEmpty()`——玩家勾选的材料若全是无算子
概念物品，三组全空而 prompt 仍写"只能从下列材料中挑选"，缺"实在没有就取前 N 条"保底。
②`recall()` 不接收 `currentMaterials`（`:337`），confirm 模式下 prompt 一边说"只能从下列挑"
一边要 AI 评价可能不在列表里的材料——把当前材料强制并入候选。
③`:168-169` 排序 `startsWith("qianxiang:")?0:1` + 名称短优先，而官方 phase_materials 样例
恰是 `minecraft:heart_of_the_sea` 这类长 id 非 qianxiang 命名空间——**UGC 材料可见性被系统性
压低**，"数据驱动材料 /reload 后 AI 立即可见"的保证在 prompt 层弱化成了"相关才可见"。
修法：`PhaseMaterialRegistry.all()` 的材料无条件置顶保底（数量有界）。
④与未修的 WQ-45 耦合：`wantedFunctions` 是裸 contains，"不要火的剑"会把 IGNITE 材料塞进
标题为"核心效果材料（优先从这里挑）"的第一组——否定语义 bug 从兜底传染到了召回
（WQ-45 若已修则此项自动消解，领单前先查）。

**测试缺口提醒**：本批新增 3 个 GameTest，但召回测试用的是带效果关键词的需求（"会喷火的
剑"），**没测无关键词退化路径**；数组用例断言不到位（见 WQ-65）；confirm 空槽兜底、
超时熔断计数、字段名大小写三处新逻辑无测试。

**遗留观察（非本批引入，c0fa031 遗留）**：`MaterialLibrary` 缓存两个洞——(a) 空→空时
`Map.of()`/`Map.copyOf(empty)` 是同一 EMPTY_MAP 单例，纯 tag 改动的 /reload 不会让缓存失效；
(b) `invalidateCache()`（`:68`）零调用方。另 `effectMaterialIndex()`（`:451-461`）每次构建
prompt 全表遍历+逐物品 new ItemStack，未走缓存，与 WQ-46 想根治的问题同源。

---

# 复核报告四：返工提交 8ca37d9（WQ-57~61）审查（2026-07-27）

**结单确认**：WQ-58 ✅（`arrival.above()` 已双排除、兜底收紧为仅 `canBeReplaced()`，
箱子/台阶不再被覆盖）、WQ-60 ✅（采方案②降 goal，全树仅 kill_warden 与 forge_legendary
两个 challenge，父链完整、lang 683 键齐）、WQ-61 ✅（判据降为常量 rift_essence，兜底
"一定配得齐"成立）。

## WQ-68 [x] 完成(78d675e) 【严重·返工 WQ-57·已核实结单】退款代码全程是死的——被过期快照整体覆盖（侦察会话已亲自核实）

`SpellCastHandler.castCustomSpell:124` 在调 `cast()` **之前**读 `PlayerSpellData data`；
`cast()` 内 `settleCast` 用 `setData` 写回退款；返回后 `:143-146` 又用**过期快照**
`data.withMana(data.currentMana() - manaCost).setCooldown(...)` 覆盖整个 attachment
（record 语义，整体替换）。**退款写入被无条件丢弃**，只剩 actionbar 提示。
旧硬编码法术路径 `:85/:103` 同样结构。
**修法**：扣蓝移到 `cast()` 之前，或结算后重新 `serverPlayer.getData(...)` 再叠加冷却。

**修理备注（主会话核实结单）**：法术改造轮已按后一方案修复——`SpellCastHandler:123-130` 结算后重读 attachment（`after`）再扣蓝，settleCast 退款与血换蓝 +20 不再被旧快照覆盖；`castWhitelistRejectsUnlearned` 等测试锁定法力净变化口径。

**同时更正我方此前的判断（重要）**：复核报告二里说"WQ-8 的退蓝是无限法力电池"——
在同步形式（AoE/beam/touch）上**从未真实发生**，正是被这同一处覆盖吃掉了。真正会退到账的
只有 projectile 延迟命中那条路径，而本次返工恰好把 projectile 排除了。结论不变（仍需返工），
但病因不是"退太多"而是"根本没退"，修的时候按本单描述来。

**附带问题（同单一并处理）**：
①projectile 是 `SpellEffectEngine:76` 的 default 分支、也是最常见形式，命中非友方现在
**既不退款也不提示**——WQ-8 的原始诉求（消除静默无反馈）在该形式上被完全回退，
注释里以"天然不满足条件"带过。需明确取舍并写进工单。
②`CAST_TALLY` 是 ThreadLocal 且 projectile 的标记会滞留到下次 `cast()` 的 `resetTally`
才清；将来若出现嵌套施法会互踩（低危）。

## WQ-69 [x] 完成(78d675e) 【中·返工 WQ-59】去程两类塌陷仍在、区块加载判断未做，单据状态与实现不符

已达成：基座材质换黑曜石；回程 `mayBuildPlatform=false` 不再改主世界地形。
**未达成**：①全空气柱（虚空上方）去程仍在 `clamp(startY)=minBuildHeight+1` 铺基座，玩家
落在世界最底层 y=min+2——"活埋"原样存在，只是材质变了；②树冠仍在上方凭空生成悬空黑曜石
（`isSolidRender` 对树叶仍 false）；③工单点名的"heightmap 查询前判区块加载"完全没做
（`:139`/`:144` 两处无 `hasChunk` 保护），却把单标记为完成。
**另**：回程回退到 `getSharedSpawnPos()` 时 `spawnSurface.above()` 未过 `isStandable`，
出生点在水面/树冠上仍会落水；且玩家被扔到世界出生点而非原坐标附近，是较大的行为变更，
需确认是否为有意设计。
遗留低危：`ensureReturnAnchor:93` 的 1183 次 `getBlockState` 全量扫描原样保留。

## WQ-70 [x] 完成(78d675e) 【低】forge_legendary 的 powerScore>=12 门槛形同虚设 + 两处文案矛盾

①`ForgeTableMenu:41/:199-203` 新增的 `powerScore >= 12.0` 判据：warden_core 单件
（LEGENDARY×3.2 + mana/resistance/strength）自身就贡献约 15.6~18，**恒过阈值**，
且 `ForgeComposer:175` 对任何非空产物都写 COMPOSED_ATTRIBUTES 故 attrs 也不为 null——
新判据实际永远等价于旧的 `usedCore`，"与文案对齐"是名义上的。
②`legendary_material.desc` 文案仍写"获得虚空裂片或逆相之核"，与已改的 criteria
（warden_core）和图标不符（非本次引入）。
③`FallbackRecipes:740-743` 的 javadoc 仍写"材料库里有森罗之核就选它"，与 WQ-61 改后的
实现直接矛盾，误导后续维护。

**测试掩盖缺陷第三例（务必重视）**：`ineffectiveTallyRefundsAtMostOnce`
（`QianxiangCoreGameTests:204-233`）只调 `markIneffectiveForTest/markEffectiveForTest` 数数，
再断言 `shouldRefundForTest`——而后者是把 `settleCast:443` 的条件**抄了一遍**的测试专用镜像，
既不走 `settleCast`、不走 `cast()`、也不测法力值。所以 WQ-68 那个"退款全程被覆盖"的严重
缺陷，这个测试照样绿。工单验收明写"断言法力净变化 == -manaCost"，未实现。
**要求**：删掉自我印证的镜像断言，改为构造真实玩家、走完整 cast 路径、断言法力实际净变化。
这已是连续第三次出现"断言太弱恰好被错误路径满足"——建议此后新增测试一律断言**可观测的
最终状态**（玩家数据/物品/世界），不要断言内部判据函数。

---

# 复核报告五：8663a70 / 6140674（WQ-45/47/51）审查（2026-07-27）

**结单确认**：WQ-51 ✅ 三项全对（`canPlaceItem` 覆写返回值正确且与 face 版语义一致；
`removed()` 先清槽再 `updateCraftingState` 必落 IDLE；`items.clear()` 防抢跑注释已加）。
**隐私红线 ✅ 通过**：新日志落盘字段不含 apiKey，key 只进 Authorization 头、不入 URL，
非 200 的错误体走 latest.log 不进 jsonl——WQ-53 的凭据泄露没有从新日志漏回来。
WQ-45 的子项②③（六组关键词补齐、七材料全部可达）与 WQ-47 的三代轮转已达标，返工时不必重做。

## WQ-71 [x] 完成(78d675e) 【严重·返工 WQ-47】req_id 跨线程失效，飞轮地基没打上（侦察会话已亲自核实）

> 修理备注：①reqId 走 WQ-2 回传链路——`AiResponsePayload` 加 reqId（AI 线程读出真值下发）、
> 客户端 ClientForgeTableAI/ClientAlchemyTableAI 暂存、`AiPlaceMaterialsPayload` 加 reqId 回传，
> handler 采纳日志用回传真 id，ThreadLocal 不再跨线程传递；②jsonl 全部落盘改投
> `AIGateway.LOG_EXECUTOR`（qianxiang-ai-log 单线程），主线程/AI 线程零文件 IO；
> ③请求行拆两层：HTTP 层 event="http"，业务层 `AIGateway.logRequest` event="request"
> 带 player_uuid/proposal_count/dropped_materials/fallback_reason（PhaseAIRecipeService
> 同线程 ThreadLocal 追踪）；④adopt 只记实际放入的材料（placed 名单，非请求名单）。
> GameTest `QianxiangReqIdGameTests.reqIdFlowsFromRequestToAdoption` 覆盖全链路。


`AIGateway:100/120` 的 `CURRENT_REQ_ID` 是 **ThreadLocal**，在 AI 执行线程（单线程
`qianxiang-ai`）赋值；而采纳回写 `AiPlaceMaterialsHandler:81-85` 在 `enqueueWork` 里、
跑在**服务端主线程**——取到 null，于是 `currentRequestId():101-108` 现造一个 id 并缓存进
主线程 ThreadLocal。后果：①adopt 行的 req_id 与任何 request 行都对不上，"建议 vs 采纳"
根本连不起来；②**全服所有玩家的所有采纳事件共用同一个假 id**，数据比没有更误导。
**修法**：把 reqId 随提案下发（`AiResponsePayload`）再随 `AiPlaceMaterialsPayload` 回传
——这与 WQ-2 已建立的"客户端回传索引"机制天然契合，可复用那条链路，不要用 ThreadLocal。
**同单一并修**：
①**违反了已核查保证"jsonl 写入不影响主流程"**——`logAdoption`→`appendLine:352-372` 在
主线程做 `Files.createDirectories`+`Files.size`+`Files.writeString`，还与 AI 线程的 5MB
轮转 `Files.move` 抢同一把 `LOG_LOCK`。此前所有落盘都在 AI 线程。改为投递到 AI 线程写。
②工单点名的字段缺一半：`proposal_count`/`dropped_materials`/`fallback_reason`/请求行的
`player_uuid` 全未实现，所以"解析失败与成功在日志里长一样"这个动机仍未解决（`ok` 只反映
HTTP 层）。
③低危：`anyPlaced` 只要放进 1 件就记录**整个** materialNames，over-report。

## WQ-72 [x] 完成(78d675e) 【中·返工 WQ-45】档位塌缩子项零改动，两条验收实测不成立

工单子项④在 diff 里**一行未改**：`pickBaseByTier:754-780`/`pickEffectByTier:792-818`/
`defaultFillers:847` 原样，16 格里仍有 8 格 base==effect（magic RARE/LEGENDARY、armor 全四档、
tool/weapon LEGENDARY）。实测：weapon+LEGENDARY → `buildExactTier` 得 size-1 集合 →
`ensureTierCoverage` 提前 return → 落 `defaultFillers`(ember_iron RARE + beast_fang COMMON)
→ **averageTier = RARE，不是 LEGENDARY**；"普通武器给 RARE 材料"也照旧（weapon COMMON 的
base 就是 RARE 档的 ember_iron）。
**另**：①`免疫` 被列入否定前缀（`:1078`）造成**反向误伤**——"免疫火焰的靴子"被剥成"的靴子"，
玩家明确的 FIRE_RESIST 需求被吃掉（`去除诅咒` 同理）；②单字键升 2 字词/加边界未做
（`血/防/护/甲/术/骨` 仍裸 contains，英文 `"ice"` 会被 `a nice sword` 命中）。
好消息：`抗/防/护` 不在否定前缀表里，"防御向武器/护甲/抗性装备"**不会**被误伤，这点做对了。

**测试掩盖缺陷第四例**：`fallbackRespectsNegation`（`QianxiangCoreGameTests:207-220`）
只断言"结果里没有 ember"——若 normalize 退化成"删掉否定词后面 N 个字符"导致 picks 为空，
`noneMatch` 恒真、测试照样绿。缺"形状词 `剑` 必须存活"的**正向断言**。更关键：测试只覆盖
`keywordPicksForTest` 没走 `propose3`，而 propose3 的方案 2/3 必含 `ember_iron`——
**玩家实际看到的"不要火的剑"结果里仍然有烬铁**，验收原文"不含 ember 系"在用户可见层面
未达成。验收要求的 LEGENDARY averageTier 用例根本没写。
（`fallbackCoversLightningAndModMaterials` 断言强度 OK，逐条 contains 具体材料 id，不会被
错误路径蒙对——这是本轮唯一一个写得好的新测试。）

**低危**：`FallbackRecipes:13` 新增的 `import java.util.Map` 全文件零使用。

**给修理会话的流程建议**：WQ-45/47 的 `[x] 完成` 应回退为返工态。另外连续四轮出现
"断言太弱恰好被错误路径满足"，建议立一条硬规矩：**新增测试一律断言可观测的最终状态**
（玩家数据/物品栈/世界方块/用户可见输出），不断言内部判据函数、不写只验证"某物不存在"
的单向断言（必须配一条"该在的东西还在"的正向断言）。

---

# 第七批：客户端 GUI 交互链侦察（2026-07-27，首次系统覆盖 ForgeTableScreen）

> 前六轮只顺带提过 GUI。本批聚焦锻造台主界面与 `ClientForgeTableAI` 的交互，一次出 12 项。
> **另：lang 格式串已由侦察会话用脚本全量验过，零问题**（683 键中 49 个含格式串：中英占位符
> 零不匹配、零处原版不支持的格式符——原版只认 `%s`/`%d`，`%f`/`%x` 会退化成显示原始模板、
> 零处实参不足）。只有 `qianxiang.forge_table.msg.recommend` 与 `qianxiang.spell.custom`
> 两个带格式串的死键（全库无引用），可删可留。**此项无需再侦察。**

## WQ-73 [x] 完成(78d675e) 【高】「清空」按钮清不掉推荐卡片，且不撤销服务端已记的提案选择（已亲自核实）

①`ForgeTableScreen:705-716` 的 `clearRequest()` 把 `lastAiResult=null` 后立刻
`ClientForgeTableAI.setListener(...)`，而 `setListener`（`ClientForgeTableAI:47-52`）会
**同步回放仍非 null 的 static `lastResult`**，把旧结果原样塞回、status 也改回 READY——
卡片一帧都没消失。**修法**：`clearRequest()` 不重注册 listener（`init` 注册的那个从未被
移除），或给 `setListener` 加 `replay` 开关。
②同一函数从不发 `SpellJsonReportPayload.NONE`，服务端 `selectionByPlayer` 里的
spellJson/customName 一直生效到关界面——玩家"清空"后手动改材料，产物仍带着已被清空的
AI 法术和自定义名。**修法**：`clearRequest()` 末尾补 `reportProposalIndex(NONE)`。

## WQ-74 [x] 完成(7780628) 【高】AI 请求被服务端静默限流 → 状态条永久卡在「思考中」，只能关界面重开

> 修理备注：两台 handler 限流命中即回空 AiResponsePayload（客户端空提案→退出 PARSING）+ actionbar
> 「AI 正在思考中」（lang `qianxiang.ai.rate_limited`）；两 Screen 加 70s 本地超时兜底（30s×2+10s，
> 替换炼金台按旧 5s 拍的 8s）。GameTest `aiRateLimitRejectsSecondCall` 覆盖闸门。

`ForgeTableAIHandler:46-49` 的 `PlayerRateLimiter` 命中时直接 `return`，不回包不提示；而
客户端 `ForgeTableScreen:636` 已置 `status=PARSING`，`updateStatusFromSlots()` 第一行就
`if (status==PARSING) return`，**且无任何客户端超时兜底**。触发：点「问 AI」后 3 秒内再点
一次（或先「问 AI」再「确认」、连点两个反问 chip）。状态条永远显示"已等待 N.Ns"且一直涨，
产物槽即使已出结果也不显示 COMPLETE。
**修法**：客户端加 3s 本地冷却 + 按钮置灰；`updateStatusFlash` 里对
`aiRequestStartMillis` 超过 AI 超时上限时强制退出 PARSING。（与 WQ-62 的 30 秒超时联动：
超时提到 30s 后这个卡死窗口会更长更明显，两单建议一起做。）

## WQ-75 [x] 完成(78d675e) 【高】连点两张方案卡 → 材料叠加而非替换，产物与卡片写的强度对不上

`ForgeTableScreen:1424-1438` 的 `applyProposal` 只在"一个空槽都没有"时拒绝，服务端
`AiPlaceMaterialsHandler:50-78` 的 `findMaterialSlot` 一路找空槽塞。点方案 A（3 个料）再点
方案 B（4 个料）= 7 格大杂烩；而 `ForgeComposer` 按占用槽数计零件，**产出与卡片上的
强度/摘要完全对不上，玩家会以为 AI 算错了**。点同一张两次同理。
**修法**：`AiPlaceMaterialsPayload` 加 `replace` 标志，服务端先把现有材料退回背包再放。

## WQ-76 [x] 完成(78d675e) 【高】请求无序号 + receive 无条件重置索引 → 产物法术与玩家点的卡片不符（已亲自核实）

`ClientForgeTableAI:32-44` 全链路无 requestId/时间戳，`receive()` 一律覆盖 `lastResult`
并**无条件 `reportProposalIndex(0)`**。触发：问 AI → 点第 3 张卡（已报索引 2 并放料）→
改需求再问 AI → 新响应到达，服务端选择被改成新列表第 0 条，而材料槽里还是第 3 条的料。
界面只有 hover 高亮、**没有"已选中"高亮**，玩家完全看不见这个错位。
**修法**：`AiRequestPayload`/`AiResponsePayload` 加自增 seq，客户端丢弃落后响应；
`receive()` 只在无有效选择时才报 0；顺手给选中卡片加边框高亮（本单最便宜的可见性改进）。

## WQ-77 [x] 完成(78d675e) 【中】从子页面返回 / 改窗口大小 → 需求文本丢失、蓝图选中项被打回第 0 条

`ForgeTableScreen:295-299,443`：`init()` 新建 `EditBox` 从不回填旧值（只有说明书模板路径经
`pendingGuideText` 特判），且 `if (!clientBlueprints.isEmpty()) selectedBlueprint = 0;`。
触发：输入长需求、选中第 5 条蓝图 → 进「材料筛选」勾几个 → 「完成」返回（`setScreen(parent)`
触发 `init()`）→ 需求没了、接着点「使用蓝图」会用错蓝图。F11 全屏切换同样触发。
**修法**：`init()` 开头存 `requestBox.getValue()` 末尾回填；`selectedBlueprint` 只在 `<0` 时置 0。

## WQ-78 [x] 完成(78d675e) 【中】蓝图超过 8 条时选中项滚出可视区，仍可被误用

`ForgeTableScreen:1490-1496,1579-1596`：`renderBlueprintPanel` 死画前
`BLUEPRINT_MAX_VISIBLE=8` 条，而 `cycleBlueprint` 在全表取模——第 9 条起面板里没有任何一行
高亮，玩家看不出当前选的是谁就点了「使用蓝图」。
**修法**：加 `scrollOffset`，`cycleBlueprint` 后把 `selectedBlueprint` 夹进可视窗口再渲染。

## WQ-79 [x] 完成(78d675e) 【中】静态缓存跨世界残留未登记 + 蓝图失败提示仍能刷屏

①`ForgeTableScreen.HISTORY`（:277）与 `ClientMaterialFilter.UNCHECKED`（:62）都是 static
且**没登记进 `ClientStateReset.resetAll()`**（该类注释本身就写了"任何新增静态客户端缓存都应
在这里登记"）。后果：进新世界后历史面板显示上个世界的记录，更麻烦的是白名单仍在**悄悄裁剪
新世界的 AI 可用材料**，玩家会以为 AI 不认识这些料。
②`BlueprintServerHandler:33-45` 的 500ms 只是节流不是去重，材料无效时按住连点仍能
2 条/秒往聊天栏刷 `blueprint.save.invalid`，10 秒 20 条。**修法**：改走 actionbar
（`displayClientMessage(...,true)`）或"相同 key 5 秒内只发一次"。

## WQ-80 [x] 完成(78d675e) 【低】渲染细节三项

①`ForgeTableScreen:1273-1279` vs `1355-1364`：只有反问没有方案时（需求很模糊），
`cards.empty` 文字与 chips 都画在 x=`leftPos+8`、y≈91，**完全重叠**，文字被 chip 底板压住
——`proposals` 为空时 chips 换行或无条件加标题宽度偏移。
②`:803,1470-1471`：`highlightInventoryTicks=60` 和 `statusFlashTicks` 在 `render()` 里
**每帧递减**，144FPS 下 0.4 秒就没了——"响应到达闪绿/兜底闪黄"这个唯一的成功反馈基本
一闪即逝。改用 `Util.getMillis()` 截止时刻，或挪到 `containerTick()`。
（同类问题在 `ClientDamageNumbers` 已开过 WQ-24，可一并处理。）
③`:1586,1640` 的 `substring(0,7)`/`substring(0,10)` 按 char 截断会劈开 emoji 代理对渲染成
乱码——换 `font.plainSubstrByWidth(...)`，本文件其余截断点都已经用它了。

**本批已核查无问题（勿重复侦察）**：`keyPressed` 输入框劫持逻辑正确（ESC 只失焦、E 键不误关
容器）；需求长度链路安全（EditBox 限 80 « 上行包 1024，粘贴超长只被静默截断不会断线）；
`onClose`/`removed` 时序完整（四个 switching 标志各消费一次即复位、两个 listener 成对清理、
`ForgeTableMenu.removed` 清产物槽+清 AI 选择+更新状态三件齐全）；**提案索引的信任边界重构
本身是干净的**（只带 int、越界即清、按玩家 UUID 分桶不串味，问题只在客户端何时报索引）；
`updatePreview` 有组件指纹缓存不会每帧跑 ForgeComposer；卡片/面板溢出与点击热区一致。

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

# 第六批：修理会话侦察代理补充发现（原编号 40~44 与第五批撞号，2026-07-27 已重编为 WQ-52~56）

> **编号消歧记录**：本批原用 WQ-40~44，与第五批 AI 质量工单同号，导致 cc6f1ab/1b44305
> 的提交信息里的"WQ-41/43/44"指向本批而非第五批。现重编为 **WQ-52~56**，对应关系：
> 旧 40→**52**（耐久链）、旧 41→**53**（凭据泄露）、旧 42→**54**（每帧全表扫）、
> 旧 43→**55**（AI 请求包加固）、旧 44→**56**（Boss 击退）。
> **第五批的 WQ-39~47（AI 质量九单）一张都还没开工**，勿因提交信息误判为已完成。

## WQ-52 [x] 完成(6bdc4c4) 【严重·系统性失效】产物注册缺 Properties.durability，整条耐久链是死代码

`QianxiangItems` 全部 10 个产物只写了 `.rarity(...)`，栈上没有 MAX_DAMAGE/DAMAGE 组件 →
`isDamageableItem()` 恒 false。后果：①三个 Item 子类的 `getMaxDamage(ItemStack)` override
永不被消费，`ComposedAttributes.durability` 一整套计算白算；②`hurtAndBreak` 全程空转 →
**frail（易碎）代价 100% 失效**、相锄/水壶永不磨损；③装备可 64 个一摞、无耐久条。
**已修**：10 个产物补 `.durability(各子类 DEFAULT_DURABILITY)`，override 随即接管为动态耐久；
新增 2 个 GameTest（全产物可损坏且 stacksTo=1、锻造耐久等于组合耐久）。
**注**：`AttributeScheme` 那段"durability 由 override 提供"的 javadoc 此前由修理会话写下但
漏了注册侧前提，已一并纠正。

## WQ-53 [x] 完成(cc6f1ab) 【严重·凭据泄露】服务端 apiKey 明文推送给每个进服玩家

`network/AiConfigSyncHandler.java:52-60`（`onPlayerLogin` → `PacketDistributor.sendToPlayer`）
经 `:64-68 currentPayload()` 把 `cfg.apiKey` 放进包；`AiConfigSyncPayload.java:37` 照发；
客户端 `ClientAIConfigCache` 缓存后回填进 AI 设置界面输入框。
**C2S 保存路径的 OP 校验（`:33-38`）是对的，读路径完全没有门控**——权限模型只做了一半。
单人存档无害；专用服务器上服主的付费 Key 泄露给全服每一个人。
**修法**：登录同步时把 apiKey 换成占位（如 `""` 或 `"********"`），仅在
`hasPermissions(2) || isSingleplayerOwner` 时发真值；客户端界面对占位值不回填、
保存时若仍是占位则不覆盖服务端已有 Key。
**验收**：非 OP 玩家进服抓包无明文 Key；OP 打开界面仍能看到并修改真值。

## WQ-54 [x] 完成(c0fa031) 【高·客户端卡死】锻造台每帧 12 次全物品注册表扫描

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

## WQ-55 [x] 完成(cc6f1ab) 【高·可打死服务端】AI 请求包无前置校验、无限流、执行器队列无界

`ai/ForgeTableAIHandler.java:31-53`：`enqueueWork` 只用来设粒子状态，**AI 任务无条件
`AI_EXECUTOR.submit`**——不校验玩家是否真的开着锻造台，无冷却；`:23` 的
`newSingleThreadExecutor` 是无界 `LinkedBlockingQueue`。改过的客户端循环发
`AiRequestPayload` 即可：每包 = 一次真实 LLM HTTP（烧服主 token）+ 一次全表扫描 + 队列堆积。
`AIGateway` 缓存按 prompt 哈希，改一个字符即绕过；熔断只在连接失败时开，端点正常时不拦。
**修法**：提交前校验 `containerMenu instanceof ForgeTableMenu`；每玩家冷却（如 3 秒）+
队列深度上限（满则直接回兜底）；`AiRequestPayload` 的 collection/字符串 codec 补 maxSize
（与 WQ-20 合并做）。

## WQ-56 [x] 完成(1b44305) 【中】Boss 冲击波对无敌目标仍施加击退

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

## WQ-81 [x] 完成(4d36072) 【大·玩法改造】法术系统六步改造：已学列表 + 增幅器 + 炼金台卷轴 + 轮盘施法 + 锻造台 25 格

**范围**：①`PlayerSpellData.learned`（id 列表死字段）升级为 `learnedSpells`（存完整
`CustomSpell`，CODEC 双向兼容旧 NBT，未知 id 丢弃），`LegacySpellMigration` 登录时把背包里
旧法杖/法术书的法术组件迁入已学列表并剥离；②法杖/魔法书**增幅器化**——`ComposedAttributes`
新增 `spellPowerPercent`/`manaBonus`（MANA 算子 ×8、攻击向算子 ×6%，均乘档位系数），
不再承载/施放法术，锻造台法术铭刻链路（默认法术/applyAiSpell/buildSpellBook/SPELL_TEMPLATES）
整体迁往炼金台；③新增魔法卷轴（`magic_scroll`，CUSTOM_SPELL 单法术，右键消耗学习）与
炼金台（`alchemy_table`，6 材料槽 + 1 卷轴槽，AI 链路复用锻造台四包按菜单类型分派）；
④**轮盘施法**——`CastSpellPayload(spellId)`，按住 V 出 8 扇区轮盘（滚轮翻页），
松开指向即施放、点按快速施放上次法术，服务端只认已学列表里的明确 id；
⑤锻造台材料格 10 → 25（5×5：中心核心/内圈辅助/外圈基底，GUI 重排）；
⑥成就/文档/GameTest 收口（本单）。

**关键决策**：
- 增幅器主手+副手同时生效、效果叠加（一手杖一手书双倍收益），伤害倍率只放大
  damage/heal 结算，不影响效果时长；法力回复与 HUD 用「有效上限」（含换手检测同步）。
- 轮盘「松开即施放」：客户端永远发具体 spellId，服务端无空包/快捷语义；
  「上次施放」与逐法术冷却置灰均为客户端本地估算，服务端权威。
- 25 格软化护栏：超过 10 件的零件 powerScore 按 50% 权重计入
  （`FORGE_PART_SOFT_CAP=10` / `FORGE_EXCESS_WEIGHT=0.5`，ForgeComposer 常量，调平只动这两个值）。
- AI 提案材料上限独立于槽位数（`MAX_PROPOSAL_MATERIALS=12`）——25 槽是摆放自由度，
  不是让 AI 一次推 24 个。
- AI 网络包零新增：四个现有 payload 按「玩家当前 openMenu/screen 类型」分派锻造台/炼金台。

**遗留观察项**：
- 轮盘开启期间未屏蔽左键攻击（指针释放后点击仍会挥武器），需要时加 InputEvent 拦截。
- 轮盘冷却置灰是客户端估算：服务端拒放（如蓝不够）后该法术可能短暂误置灰，无功能影响。
- WQ-2/18 那批旧单的验收语境已变：法术不再写在产物组件上（CUSTOM_SPELL/SPELLBOOK 的
  写入路径只剩炼金台卷轴），旧单中「产物带法术」类措辞按新语义（增幅器 + 已学列表）理解；
  服务端权威提案表（WQ-2 信任边界）在两台子上均保留。
- 旧 SPELL 双轨（`spell/Spell.java`）仍保留只读兼容，施法入口已不再读它。

---

## WQ-82 [x] 完成(20ca59b) 【中·表现层+数值对标】法术引擎填格、伤害对标武器、模板扩容与元素音效

**范围**：①伤害标定——`resolveDamage` power×2.0 → **power×3.0**（对标武器 EDGE 件 3.0×档倍），
heal 两处 ×2.0 → ×2.5；卷轴模板 power `maxTier+1` → **`2×(maxTier+1)`**（COMMON 2/RARE 4/
EPIC 6/LEGENDARY 8，材料预算钳制不变），10 个预置法术 power 同步上调（manaCost 按
「现值符合 10+5×power 口径才重算」逐条处理）；②debuff 塌缩修复——抽 `resolveDebuff`
按 9 元素分派（点燃/冻结/减速虚弱/毒+反胃/致盲+黑暗/发光+虚弱/凋零/漂浮/反胃+虚弱）；
③utility 空转格补全——fire/frost/lightning/shadow/arcane 自身状态 + **blood 血换蓝**
（>4 血自伤 4 换 20 蓝，≤4 血提示不发动）；④卷轴模板 10 → 26：单算子 +8（血怒/石肤/
疾风/轻羽/鹰眼/再生/潮汐/余烬披风），新增**双算子组合表** 8 条（先扫组合再扫单算子）；
⑤法术真名：26 条模板全配 `qianxiang.spell.name.*`（中英），`ClientSpellNames` 三级回退
（真名 > spell.* 键 > 元素·效果），轮盘与卷轴 tooltip 接入；⑥每元素专属施法音/命中音
（form 微调音高）、弹体轨迹加密（power>6 加层）。

**关键决策**：
- `castCustomSpell` 扣蓝改为重读引擎结算后的 attachment——旧快照扣减会把 blood +20
  与 settleCast 退款整个抹掉（GameTest 抓出的真实 bug）。
- 音效字段按 1.21.1 实测修正：frost=GLASS_BREAK（无 BLOCK_GLASS_BREAK）、
  shadow 施法=SCULK_CLICKING（无 SCULK_CLICK）、命中=SCULK_BLOCK_BREAK（无 SCULK_BREAK）、
  lightning=TRIDENT_THUNDER.value()（该字段是 Holder）。

**遗留观察项**：
- 血换蓝 HP 扣减在 GameTest 环境不可观测：Epic Fight 接管 LivingIncomingDamageEvent
  取消 vanilla hurt，对无 EF 状态的 mock 玩家不实际扣血（法力侧已锁断言）；真实服务端
  经 EF 结算路径正常扣血。不做 hurt 失败回退 setHealth——真机会双重扣血。
- 「IGNITE+LIGHTNING→雷炎弹」实际用 `SPEED_BOOST` 代表雷侧：`PhaseFunction` 无
  LIGHTNING 枚举（雷石=STRENGTH+SPEED_BOOST，STRENGTH 已分配给烈焰打击），代码注释已注明。
- 法术 DPS 仍低于武器平砍连击（单发对标 ×3.0，但有冷却与耗蓝），当前靠元素附加/范围/
  功能补偿，待实机调平（平衡常量在 AttributeScheme/SpellScrollComposer 注释处）。
- 音效音量分级（雷 0.4/0.3、图腾 0.5）是拍脑袋值，待实机听感调。

---

## WQ-83 [x] 完成(1673651) 【中·交互与表现】工作台去格子化 + 自定义粒子 + 复古贴图

**范围**：①锻造台/炼金台「去格子化」——手持右键投料（潜行投整组，`TableInteractions`
共用插入/取回/吸收逻辑）、扔到台面的掉落物被 BE 每 5tick 扫描吸收、空手右键直接拿产物
（`afterTakeResult` 静态抽取，menu/block 两路径共用防双计）、潜行+空手取回全部材料；
GUI 材料网格退役改文字列表（槽位坐标挪屏外，quickMove 按索引不受影响）；
②漂浮材料虚影 BER（`FloatingItemsRenderer` 泛型共用）：材料环公转 + bob + billboard、
产物中心自转、从无到有转变动画（螺旋内收→升起）、PARSING ×3 速；BE update tag 扩展
（材料槽排除产物槽 + displayResult，同 tick 合并 sendBlockUpdated）；
③自定义粒子 spark/shockwave（学 Iron's Spells 技法）：spark 白→元素色 lerp + 落地弹跳、
shockwave 贴地双面环缓出扩散；引擎施法/aoe/命中/弹体轨迹高光层接入（原版粒子底层保留）；
④工作台贴图复古重画（深木+深灰金属+少量铜，炼金台加暗绿珐琅区分），
脚本重跑落盘，GUI 布局坐标一律未动（硬约束）。

**关键决策**：
- 产物取出与清槽由调用方完成、`afterTakeResult` 不碰产物槽——天然防双计；
  GameTest 抓出「产物落主手后第二次点击被当材料投回」是**正确行为**（测试已修正路径）。
- spark 喷泉逐颗发包：`sendParticles` 批量模式所有粒子共享同一速度，喷泉要散开初速。
- 粒子贴图必须白色系（着色走 rCol/gCol/bCol 乘法，底色会污染元素色）。

**遗留观察项**：
- 虚影是真物品渲染非半透明投影，数量多时（25 材料）同屏实体渲染开销待观察。
- GUI 网格退役后蓝图面板仍是右侧外挂扩展面板，复古贴图只重画了主窗口；
  面板贴图统一留待后续 UI 轮次。
- 掉落物吸收是 5tick 扫描而非事件驱动，成组扔料最多延迟 0.25s，可接受。
- BER 客户端动画状态用 Map<BlockPos, AnimState> 持有（>256 清空重建），
  长期大规模同屏台子未实测。

---

## WQ-84 [x] 完成(aaa9199) 【大·存储适配 + 合成仪式】自动取料四源存储 + 工作台创作仪式

**范围**：①自动取料存储适配——`MaterialSources` 抽象（`count/extract` 两操作，
严格物品守恒），AI 放料按序翻：玩家背包 → 旅行背包（TB，`compileOnly` 软依赖，
`AttachmentUtils.getBackpackWrapper().getStorage()`）→ 台子 r=4 内
`Capabilities.ItemHandler.BLOCK` 容器 → Refined Storage 2 网络（`compileOnly` 软依赖，
NetworkNodeContainer→StorageNetworkComponent）；TB/RS2 类型引用全部隔离在
`compat/` 两个类里，调用点 `ModList.isLoaded` 守卫（EF 同款模式，不装不崩）；
mods.toml 两条 optional 声明；②合成仪式——产物就绪点「开始创作」（产物槽点击/
空手右键同效）→ `RitualLogic` 状态机 FLYING(30t)→FORMING(50t)→DONE：材料先锁进
`ritualInputs`（不销毁）、`pendingResult` 记录产物、`closeContainer` 关 GUI；
FLYING 材料 ghost 从玩家位置抛物线飞入拖 spark 尾迹；FORMING 炼金台画双层反向
同心环+内接五边形魔法阵（产物法术元素色），锻造台每 10t 锻打火花+ANVIL_LAND；
DONE 时 ritualInputs 才真正消耗、displayResult 就位、记相谱，产物留台面空手拾取；
仪式中投料/取回/再触发一律拒绝；挖台 ritualInputs 照常掉落不吞。

**关键决策**：
- SSN（简易储存网络）零专门代码——其 Exchange 接口方块把整网代理成标准
  `Capabilities.ItemHandler.BLOCK`，被通用附近容器扫描天然覆盖。
- RS2 只认台子 r=4 内的网络方块（避免隔空抽全网）；
  抽取按 `extract(EXECUTE)` 返回数量重建栈，严格守恒。
- 材料在 DONE 转态才真正消耗（此前锁在 ritualInputs，挖台/崩服不吞料；
  仪式状态落盘，读回非 NONE 统一归 NONE 并退回材料槽）。
- 产物槽 `mayPickup=false`，点击/Shift 点击改为触发仪式；相谱记录时机从
  「取产物」改为「DONE 转态」（仪式完成即算创作成功，拾取只是拿取）。
- menu 指纹短路修复：产物槽被外部清空但材料指纹未变时不再跳过重算
  （仪式后再放同样材料卡空预览，GameTest 抓出的真实 bug）。

**遗留观察项**：
- RS2 用裸 `ItemResource.ofItemStack(白板栈)` 匹配：带 phase_data 组件的千相材料
  存进 RS 后是带组件条目，裸 key 可能匹配不到——组件级匹配待实机验证
  （箱子/背包/TB 路径不受影响）。
- 仪式中重开 GUI 可见空材料槽与新预览，与 DONE 的 displayResult 两路径并存，
  语义自洽但视觉上待 UI 轮次统一。
- BER 仪式 VFX 进度用客户端本地时间推进（服务端 progress 仅作校准），
  跨维度/卡顿下可能与服务器差几 tick，纯视觉无影响。
- r=4 存储扫描（容器+RS2）是 9×9×9 的 capability 查询，仅在放料点击时触发；
  后续若接自动化取料需加缓存。

---

## WQ-85 [x] 完成(cb2ae32) 【大·武器形态统一】定形事实源 + 动作底座 + 画工 + 手持 3D

**范围**：①「填充顺序即布局」——`SLOT_FILL_ORDER`（中心 12=核心→内圈→外圈）
统一手持/扔料/AI 放料/蓝图四条投料路径，第一个料永远落核心槽；
②`WeaponForm` 9 推导形态（剑/巨剑/匕首/太刀/长枪/战斧/重锤/镰刀/钉锤）
+ STAFF/BOOK 物品固有，`AppearanceData` 新增 `form`/`baseFamily` 字段
（optionalFieldOf 缺省空串，旧存档兼容）；`WeaponFormProfile` 统一
form→（纹理 shape / EF category / 默认连段 / collider / 3D 剖面）五元映射，
`DynamicWeaponTexture.shapeFor`、`QianxiangEFCompat.provide`、`MovesetEditorScreen`
三处默认底座改为先读组件 form，旧阈值仅作无 form 回退；
③11 张字符画模板精修（三段结构分明）+ `baseFamily` 族色刃体
（钢灰/象牙白/深棕/革棕，VariantKey 进 hash 防串色）；
④手持 3D 化——`bakeQuads3D` 按形态剖面参数分区挤出（刃体/柄部各自厚度），
`DynamicPass` 按 ItemDisplayContext 分叉（手持 3D、GUI/掉落/展示框 2D 片），
11 个武器类静态 json parent 统一 handheld，零新资产。

**关键决策**：
- 布局=填充顺序而非坐标系（材料槽坐标已挪屏外），所有取料/放料路径同一约定，
  形态/核心族推导以槽位下标为准。
- katana↔tachi 名正言顺进表（katana 形状 → tachi 底座/连段/判定盒）；
  骨刃可达性由 `BASE_BONE→KATANA+1` + itemPath 回退双保险。
- MACE 仅映射表/编辑器可达（推导规则无产出路径，钉锤靠自定义名纹理路径）。
- 3D 几何零新资产：逐像素按角色（H/W 柄区 vs 刃体区）分区厚度挤出，
  侧条与正面共用像素 UV。

**遗留观察项**：
- 3D 侧条 UV 是整像素压缩到 1px 面，实机若刺眼需按方向拆 UV（待实机验收，
  其余实机项：手持厚度观感、图标零偏移、族色/刃缘色一致性）。
- 旧产物组件无 form/baseFamily 字段，走回退路径（纹理形状/EF 分类/编辑器底座
  均保留原逻辑兜底）。
- AI 摆法教学（提示玩家「第一个料是核心」的引导文案/教程）留待后续 UX 轮次。

---

## WQ-86 [x] 完成(0c5038a+2018bf9) 【大·熟练度技能树 + 32×32 手绘武器贴图】

**范围**：①三轨熟练度——战斗/法术/技艺三轨 XP，行为养成（近战击杀涨战斗、
施法涨法术、锻造/炼金/交易涨技艺），升级得点；②27 节点技能树自由加点，
服务端权威（节点加成全走乘法钩子）；③流浪相师**潜行+右键**开启技能树，
同入口**洗点**（10 绿宝石，点数全返）；④技能树 GUI（K 键打开），
J / 潜行+J 释放主动技（战吼/奥术涌动）；⑤动态武器贴图 16×16 → **32×32**：
`SIZE=32` + 新符号词表（o 显式深描边 / D·B·L 刃体三档色阶 / e·E·X 刃缘三档），
13 张形态模板（sword/greatsword/dagger/katana/spear/axe/hammer/scythe/mace/
staff/book/shield/hoe）逐张手绘并经预览目检迭代，其余 7 张（bone/pan/
cleaver/bow/wand/pickaxe/shovel）由 `upscale16to32` 最近邻 ×2 兜底；
尺度校准——手持 3D 挤出坐标/UV 按 16/SIZE 缩放、角星/宝石闪光/飘浮粒子/
稀有度光晕按 s=SIZE/16 放大。

**关键决策**：
- 未开启不攒 XP：相师开启前三轨行为零收益，先找相师是修行第一步
  （避免"开了发现白玩两小时"的追溯纠纷，也强化相师的存在感）。
- 节点加成全走乘法钩子、不动基底数值——加点多层叠乘，卸下即还原，
  与材料体系（强度靠材料）正交不打架。
- 洗点 10 绿宝石全返：有成本但不吞点，鼓励试错构建。
- 手持 3D 挤出厚度单位是**模型格**（0..16 空间），不随贴图分辨率变——
  32×32 下 EXTRUSIONS 厚度表数值保持不变，只校准 texel 域的特效尺度。

**遗留观察项**：
- 7 张 ×2 兜底模板（bone/pan/cleaver/bow/wand/pickaxe/shovel）仍是旧 16×16
  放大观感，待逐张手绘重制（入口见 `DynamicWeaponTexture.template()` 注释）。
- axe 头部、hoe 锄板形状可再精雕；scythe 镰刃弧度辨识度一般，实机如难认
  优先重做这三张。
- 32×32 动态图标与 16×16 原版物品并排（背包/创造栏混放）的观感待实机验收。
- 战吼 HUD 只有倒计时数字无图标，主动技可见性留待后续 UI 轮次。

---

## WQ-87 [x] 完成(78d675e) 【大·交互重做 + 键位根治】合成台 GUI 完整操作面 + 键位避让 EF

> 编号备注：本单初次登记误用 WQ-63（与旧熔断单撞号）改 WQ-81，又与 fc749c3 已回填的
> WQ-81（法术六步改造）撞号，现统一重编为 WQ-87。

**范围**：①GUI 背包左键投入——两 Screen `mouseClicked` 拦截背包/快捷栏槽位左键
（非 Shift），发新 C2S `TableInsertPayload(menuSlotIndex, wholeStack)`；handler 按
openMenu 分派、校验槽位属玩家背包区（RESULT_SLOT+1 起 36 格）、材料区未满、非仪式，
复用 `TableInteractions.insert` 守恒 split，200ms 节流；Shift+左键不拦截保持 quickMove；
②材料行取回语义扩展——`TableRetrievePayload` 加 `all` 字段：左键取 1 个 / Shift 取该槽
全部，行 hover tooltip 加操作提示；③「全部取回」按钮（材料区标题旁，
`slotIndex=-1+all=true` 走 `TableInteractions.retrieveAll`，材料为空/仪式启动窗口期置灰）；
④提示行重写为三行 0.6 缩放小字（`hint_insert`/`_2`/`_3`，中英）；⑤仪式中投入/取回
客户端禁用（awaitingRitual 窗口期标志）+ 服务端权威拒判不变；
⑥键位根治——cast_spell 默认 G→**B**、skill_tree K→**O**（activate_skill 保持 J），
`SpellKeybinds` 注释写明 EF 占用键清单（R/K/G/LAlt/Y/鼠标左右/空格/Shift）；
⑦轮盘 5 秒看门狗——按下后收不到释放沿（键被抢/焦点丢失）自动取消不发包、收回指针。

**关键决策**：
- 左键投入不走原版 slotClicked（避免拿起物品），直接发包、服务端按 menu.slots 下标
  校验——客户端坐标不可信，区间外（材料槽/越界）一律 warn+拒绝。
- 取回/投入共用 openMenu 分派范式（与 AI 放料一致），零新增菜单代码。
- 仪式中禁用做两层：客户端 awaitingRitual 窗口期（点「开始创作」到服务端关 GUI 之间），
  服务端 RitualHost.ritualState().active() 权威拒判——后者才是护栏。
- 轮盘看门狗放在 RenderGuiEvent.Post（每帧必跑），超时取消时主动 grabMouse
  （与 cancelIfActive 不同——那是 Screen 接管指针的路径，不能抢）。

**⚠️ 部署迁移（父会话执行）**：`run/options.txt` 里存有旧绑定
`key_key.qianxiang.cast_spell:key.keyboard.v`——代码默认值对已有实例不生效，
需手动改为 `key.keyboard.b`（skill_tree/activate_skill 无残留条目，自动吃新默认 O/J）。

**遗留观察项**：
- 背包左键被拦截为「投入」后，台子 GUI 开着时无法从背包拿起物品整理——
  与快捷栏交换（数字键）/拖出丢弃仍可用；实机若嫌霸道可加修饰键反转。
- 锻造台三行提示与 8 行材料列表全满时的「…+N」溢出指示有轻微视觉邻近，
  实机确认可读性。
- 旧 options.txt 实例在迁移前按 V 仍会施法（旧绑定优先于默认），属预期。

---

## WQ-88 [x] 完成(78d675e) 【大·AI 健壮性 + 主动建议】WQ-62/63/74 三联修复 + 「能做啥」提示条

**范围**：①WQ-62——`AIConfig` 默认文件模板 timeout 5→30、引入 `config_version`，
`parse()` 一次性迁移（无版本号且 ≤10 → 抬 30 写回 + INFO，只迁移一次）；
②WQ-63——`AIGateway.recordOutcomeClassified` 三类故障（CONNECT/TIMEOUT/ENDPOINT）
各自独立计数（成功/「有响应但不可用」才清零，不再互清），`AIClient` 非 2xx 置
LAST_ENDPOINT_ERROR 进 ENDPOINT 计数（阈值 3 开熔断）；
③WQ-74——两台 AI handler 限流命中即回空 AiResponsePayload（客户端空提案→退出
PARSING）+ actionbar「AI 正在思考中」，两 Screen 加 70s 本地超时兜底（30s×2+10s，
替换炼金台按旧 5s 超时拍的 8s）；
④AI 主动建议——两 menu 重写 `broadcastChanges`：材料指纹变化 → 30t（1.5s）防抖 →
`TableSuggestion` 本地分析（复用 ForgeComposer/SpellScrollComposer 同一事实源，
零 HTTP 零副作用）→ 内容变了才发 `TableSuggestionPayload`；客户端
`ClientTableSuggestion` 缓存，锻造台渲染在状态条下、炼金台渲染在标题行右侧
（状态条下空间不足，见遗留）。

**关键决策**：
- 主动建议走 menu.broadcastChanges（每 tick、仅容器开着）而非 BE tick——
  天然拿到「正在看这台子的玩家」，单机暂停时计时天然暂停。
- 本地分析永远先显示且**不调 HTTP、不占 AI 限流**；AI 在线增强（confirm 模式）
  是可选项，留待后续（见遗留）。
- 建议内容按 key 去重（itemId|form|value），同内容不重复发包；
  材料清空发空 itemId 清行。

**遗留观察项**：
- AI 增强建议（在线且非熔断时 confirm 模式替换本地行）未做——本地行已覆盖
  「能做啥」核心诉求，增强属锦上添花。
- 炼金台建议行放在标题行右侧（x=104）而非任务设想的「状态条下」——
  状态条下被三行操作提示与「开始创作」按钮占满，实机确认可见性。
- WQ-74 客户端「收到空响应退出 PARSING」依赖既有监听映射（空提案→IDLE），
  服务端无客户端类可测，实机点两次「问 AI」验证状态条不再卡死。
- 广播每 tick 计算 25/6 槽指纹（整数混合），开销可忽略；若后续加自动取料
  自动化再考虑缓存。

---

## WQ-89 [x] 完成(78d675e) 【大·审计 + 工具】算子落地矩阵 + 手持生效链 + 数值复核 + 截图眼

**范围**：①算子落地矩阵 GameTest（`QianxiangFunctionMatrixGameTests`）——25 个
PhaseFunction 各挑代表物品（功能 tag/PhaseData），单材料走完整 compose，
从产物栈 COMPOSED_ATTRIBUTES 组件逐字段断言，缺失一次性全报；
②手持生效链——铁+燧石锻武器 → mock 玩家主手 → ATTACK_DAMAGE 1+4=5 /
ATTACK_SPEED 4+0.4=4.4 落属性图，满力一击假人掉血 == 5.0；
③数值复核——EDGE_ATTACK_DAMAGE 3.0→4.0、IGNITE_ATTACK_DAMAGE_BONUS 0.5→0.75
（单件 COMMON 成品总攻 5，仍低于钻剑 7；powerScore 经 POWER_WEIGHT_DAMAGE 自动同步）；
④`/qianxiang shot`（OP）——服务端发 ScreenshotRequestPayload，客户端
`ClientScreenshot` 用原版 Screenshot.grab 抓整窗存 run/screenshots/qx_<时间戳>.png。

**关键结论**：
- 「吞功能」在 resolver→compose→产物组件链路**不成立**：矩阵 25/25 全部落地。
  实机观感的主因更可能是 EDGE 3.0 数值偏弱（已 buff）与效果等级的消费者
  （护甲被动/事件钩子）感知度低，而非属性丢失。
- 1.21.1 装备属性刷新走 tick 的 detectEquipmentUpdates 私有管线且
  lastHandItems 首调用即播种——测试需反射调两次（空手播种→装备检出），
  setItemSlot/onEquipItem 都不改属性（onEquipItem 只管音效/游戏事件）。

**遗留观察项**：
- grantedEffects（自由效果 tag）通道未进矩阵（属效果 tag 消费者侧，
  与 25 算子不同通道），如需同等审计再开单。
- 效果等级（ignite 等）的武器侧消费者强度（点燃秒数/吸血比例）未做数值审计，
  「材料能力感知弱」的另一半可能在这里，建议实机打完再定调平。
- 截图命令发的是 S2C 请求，客户端渲染线程抓帧；无头/专用服务端环境无客户端
  可抓（预期，开发工具）。

---

## WQ-90 [x] 完成(78d675e) 【大·可达性】相师/商人自然刷新 + 首进世界指引 + 编号整顿

**范围**：①流浪相师主世界自然刷新——`data/neoforge/biome_modifier/wandering_sage_overworld.json`
（neoforge:add_spawns，五大村庄群系 plains/desert/savanna/taiga/snowy_plains，
weight 3，creature 类别随实体 MobCategory）；②深渊商人下界自然刷新——
`abyss_merchant_nether.json`（nether_wastes/crimson_forest/warped_forest，weight 2，
同为 creature 类别，下界 creature 池有炽足兽先例）；③首进世界指引——
`FirstJoinGuideHandler`（PlayerLoggedInEvent + PersistentData `qianxiang:greeted` 标志，
每玩家一次，零新 attachment）：聊天提示找相师开修行（O/B/J 键位）；
④文档收口——README 操作说明（左键投入/Shift 整组/材料行取回/全部取回按钮/
AI 主动建议条/键位 B·O·J/shot 命令）+ 队列编号整顿。

**关键决策**：
- 两 NPC 刷新类别保持 CREATURE（实体 MobCategory 如此，add_spawns 类别随实体类型）：
  改 MONSTER 会被 despawn 逻辑清掉——NPC 必须不消失；下界 creature 池有炽足兽先例可刷。
- 刷新群系显式列村庄五群系而不用 `#minecraft:is_overworld`：全主世界太泛，
  「云游四方但遇得到」的密度平衡是村庄周边。
- 指引判定用 PersistentData 标志而非 SagaData entries 为空：后者对
  「从不锻造的纯探索玩家」会每次登录重复刷屏。
- 编号整顿：前几轮新单 WQ-81/82/83 与 fc749c3 已回填的旧 WQ-81~86 撞号，
  统一重编为 WQ-87（交互重做）/WQ-88（AI 建议）/WQ-89（矩阵审计），
  交叉引用已同步；WQ-62/63/74 的完成回填提交号留父会话。

**遗留观察项**：
- 相师 weight 3 是拍脑袋值：creature 池上限 10 易被动物占满，
  实机若仍难遇可提到 5 或加村庄结构内定点刷新（需 structure_processor，复杂度高）。
- 商人在下界 creature 池的实际刷新率未实机验证（下界 creature 刷新区块
  依赖玩家附近可站立地表，玄武岩三角洲未列入）。
- 首进指引只有一条聊天，玩家可能略过；后续 UX 轮次可考虑成就引导强化。

---

## WQ-91 [x] 完成(610d402) 【大·实机冒烟与验收闭环】自动视觉冒烟装置 + NeoForge 升级 + 存储模组安装

**范围**：① `handler/AutoSmokeHandler`(QX_AUTOSMOKE=1 门控 + quickPlay 直进世界 + ServerTick 步骤表:kit/传送/放台/投料/仪式连拍/九形态手持双视角/杖书单镜/技能树,~26s 出全套图);② `/qianxiang shot` 截图链(ScreenshotRequestPayload 扩 perspective/closeScreen,客户端延迟 2t 抓帧);③ 台面虚影可读性(双圈 r=0.28/0.55、scale 0.45、逐件伪随机倾角、产物上移至 1.35);④ NeoForge 21.1.219→21.1.244(SSN 1.13.x 需要 ≥21.1.235);⑤ run/mods 安装 travelersbackpack 10.1.37 + storagenetwork 1.13.1 + flib 0.2.8(均核实 NeoForge jar);⑥ run/config/qianxiang-ai.json 端点修复(dashscope 标准 URL + qwen-plus,key 未动);⑦ run/options.txt cast_spell 迁移 V→B。

**冒烟实拍结论(两轮共 26 张)**:复古台贴图 ✓;虚影双圈后逐件可辨 ✓;仪式飞材/火花 ✓;技能树界面 ✓;手持 3D 剑/锤/杖形态可辨 ✓;键位提示 B/O/J ✓;首进指引 ✓。第一轮发现"投料触发 spell_book 优先截胡导致拍不到武器"已修(换烈焰粉)。

**遗留观察**:① 16 张/轮的同秒去重导致九形态未拍全,需要全量时重跑;② 轮盘施法画面未入序列(需输入模拟);③ 效果等级消费者强度(点燃时长/吸血比)未审计;④ 相师刷新 weight 3 实机遭遇率待观察;⑤ Wayland 下无法脚本驱动窗口,截图链路是唯一"眼睛",勿删。

**验收**:`./gradlew compileJava` 绿;21.1.244 下 runGameTestServer 105/105 全绿;两轮冒烟 AUTOSMOKE_DONE 正常写出。

---

## WQ-92 [x] 完成(0a027cf) 【中·尾账清扫】AI 尾账 + 体验残留 + RS 组件匹配 + 7 模板手绘

**范围**:① MaterialRecall 召回剥否定(「不要火的剑」不再把 IGNITE 塞进核心组,复用
FallbackRecipes.stripNegatedSegments/negatedEffectKeywords);② AIGateway BREAKER_SKIPS 三处归零
+ 兜底直连计入熔断账;③ grantedEffects 自由效果通道补全矩阵(19 行);④ 轮盘开启期间屏蔽
攻击/使用输入(InteractionKeyMappingTriggered 取消+压挥臂);⑤ 缺料名单改 S2C 走客户端语言
环境渲染(中文名);⑥ 战吼 HUD 铁剑图标+冷却置灰;⑦ 仪式中右键拦 GUI,actionbar 报「创作
进行中…N%」(RightClickBlock 双端取消);⑧ RS2 组件级匹配(遍历 getAll+组件覆盖谓词,
extract 带出组件);⑨ bone/pan/cleaver/bow/wand/pickaxe/shovel 七张 32×32 手绘,20 模板全齐,
upscale16to32 删除;⑩ ForgeComposer grantedEffects javadoc 失真更正。

**遗留观察**:① 熔断兜底测试有 wiring 盲点(catch→落账无法端到端驱动,评审盯 AIGateway:209);
② RS count/extract 实机验证需装 RS2 按 javadoc 三步走;③ 轮盘输入屏蔽/仪式进度条为纯客户端
路径,实机验收点见修理备注;④ gameTestServer 与 dev 共用 run/,AI 测试禁止走真实端点(全部
拦截/本地桩),后续 AI 测试须守同一规矩。

**验收**:131/131 GameTest 全绿(新增 10 个);7 张模板逐张预览目检迭代;lang 850/850 零差集。

---

## WQ-93 [x] 完成(6cf560f) 【大·基建】自主游玩检测装置 + 整合包对比调研

**范围**:① `handler/PlaytestHandler`(QX_PLAYTEST=1 门控+quickPlay 直进+8 场景 27 检查:
锻造/炼金/轮盘施法/熟练度/维度往返/Boss/存储取料,关键节点截图,PLAYTEST_REPORT.txt,
结束自动退出);② 看门狗三次迭代终定稿:独立 daemon 线程(>10s WARN/>15s 关暂停界面
自愈/>60s 熔断出报告)——ClientTickEvent 与 RenderFrameEvent 在暂停/限流下都会失声,
唯有后台线程可靠;③ S 场景世界残留全复位(旧 BE 材料会被 setBlock 保留,是跨运行
flake 根因);④ 假人 NoAI 钉位+C1 空放避原版伤害冷却(invulnerableTime 撞冷却掉血 0
的根因);⑤ `docs/modpack-comparison.md`:RLCraft/DawnCraft/Prominence/ATM9 等六包+
方法论对比,行动清单 8 条(传奇升级树/可重复挑战/引导加密/Boss 规则包/声望玩法化/
蓝图 gating/相谱委托链/避坑)。

**实跑记录**:八轮迭代抓到——选中槽未设(掉血 1.0 假信号)、跨运行残留、伤害冷却、
服务器暂停致 tick 停摆(两种看门狗均失声)、session.lock 残留三例;终轮 27/27 全 PASS。

**遗留观察**:① 吸收器无白名单(OBSERVE 已记录);② 游玩检测只覆盖单人+无真实 AI
外呼;③ 对比调研行动清单待排期(建议先做传奇升级树+可重复挑战,均为终局断点)。

**验收**:compileJava 绿;runGameTestServer 131/131;QX_PLAYTEST=1 终轮 27/27 全 PASS。

---

## WQ-94 [x] 完成(86b4cee) 【大·终局内容】传奇装备升级树 + 裂隙试炼词缀怪

**范围**:① 传奇升级树——powerScore≥12 产物进核心槽进升级模式,喂料折经验
(warden_core 8/COMMON 1/RARE 2/EPIC 3/LEGENDARY 5),阈值 L2=10/L3=25/L4=45/L5=70,
每级主属性 ×(1+0.06L);**防复利=显式 L0 基底**(ForgeComposer 锻造时落盘,升级永远
从基底重算,弃反推除法);走现有仪式流程零改动;tooltip「传奇 +N/MAX」+建议行提示。
② 裂隙试炼——万象森罗内持非精髓物品右键裂隙岩触发,环形刷 3+min(wins,7) 只词缀怪
(燃焰/坚岩/疾风/噬血/雷霆:怪增益+命中效果+掉落带 AFFIX 组件的材料),HP 与词缀率随
wins 缩放,60s 冷却,死亡/离维判负清场;**AFFIX 组件注入算子**(PhaseFunctionResolver 钩子:
任何物品带词缀都能当零件)。③ 游玩检测装置两修:看门狗竞态根治(finish 不再盲打断,
线程死亡全留痕)+ Xvfb 虚拟显示定为标准跑法(免用户干扰,30s 出报告)。

**遗留观察**:① 升级只在锻造台(炼金产物不可升,卷轴是消耗品语义自洽);② 试炼触发
依赖玩家找到裂隙岩,引导待行动清单第 3 条;③ 词缀怪用原版模型(零资产原则)。

**验收**:139/139 GameTest(新增 8);Xvfb 下 QX_PLAYTEST=1 回归 27/27 全 PASS。

---

## WQ-95 [x] 完成(待提交) 【大·荣耀风战斗】PVP 镜头 + 连招 + 千机伞形态转换 + 华丽特效

**范围**(全职高手·荣耀风四件套,玩法全保留):
① **PVP 自动第一人称**——CombatCameraHandler/Logic:与玩家互相伤害/攻击 6s 窗口切第一
人称,脱战 4s 回第三人称;PVE 不抢镜头,只在切换瞬间写一次(尊重手动 F5);
config/qianxiang-camera.json 开关。
② **连招施法**——ComboTracker:5s 内交替不同法术 combo+1(封顶 10),combo≥3 每级
+4% 法伤(×1.32 封顶,与增幅器/熟练度叠乘);HUD 大字号「N 连击!」金色弹出缩放、
断连灰淡出;ComboSyncPayload 同步。
③ **千机伞·形态转换**——L5 传奇武器右键循环切换 9 形态,属性/耐久/升级全保留;
EF FORM_CACHE 键从 efCategory 改 form id(同底座不同形态不再共享错误 capability)。
④ **华丽特效**——刀光弧(攻击方向 90° spark 弧,刃缘色随武器)、施法脚下法阵环、
命中双环+弹体爆炸喷泉、击杀金色大喷泉(近 2s 玩家伤害台账判定)、浮字分档
(≥10 金色暴击风加大,缓出上抛);三层防刷屏。
⑤ 游玩检测加 PLAYTEST-DIAG 诊断(心跳/驱动/仪式等待全留痕,isRemoved 静默杀
进程空洞堵死)。

**验收**:155/155 GameTest(新增 16);Xvfb 下 QX_PLAYTEST=1 回归 27/27 全 PASS。

**遗留观察**:① 刀光只画本地玩家(无网络包,他人攻击客户端不可见);EF 战斗模式
若旁路原版攻击预测刀光可能不触发(javadoc 注明);② PVP 镜头连专用服务器时被打侧
检测退化(攻击侧仍生效);③ 连招记数在引擎异常时照记(下次施法自纠正)。

---

## 已完成（勿重做）
- P0-1 法术上行白名单+钳制、P0-4 调试栈打印、P0-5 en_us 中文污染、P0-7 AI 熔断、
  P0-8 防具映射（e3b66f9，侦察会话）
- WQ-10/11/12/18 锻造台三条刷/吞物品链与放料一致性（8055448，修理会话）
- WQ-40 耐久链彻底失效（6bdc4c4，修理会话）
- Boss 三技能、成就三支线、位格门控、锻造/传送门音效、NPC 补货落盘、分享码 productType
  归一（2f443f0..be66c1a，修理会话）
- P0-3 调查（结论见 WQ-5，代码无需改动）
