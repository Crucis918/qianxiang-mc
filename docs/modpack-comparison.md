# 整合包对比调研:牵响坐标系(2026-07-29)

> 调研对象:RLCraft、DawnCraft、Prominence II、Medieval MC 系、ATM9、BMC4 + 设计方法论
> (FTB Quests 文档/GTNH/SevTech/Vault Hunters)。四个代理并行调研,来源链接见各节。
> 本文回答一个问题:**牵响离"好玩的整合包"还差什么**,并给出按性价比排序的行动清单。

## 一、头部包都在做同三件事

1. **可见的下一步**(防迷失):ATM9 用 60+ 章节把 400 个 mod 切成小径、主线从砍树排到
   ATM 之星;BMC4 每个 Boss 任务副标题直接写攻略("在哪/怎么召唤/解锁什么");
   Prominence 战役分章节+教程任务线+隐藏任务。**任务书是老师,不是清单。**
2. **节奏感(软锁为主,硬锁点睛)**:软锁=距离/维度难度缩放、推荐等级铭牌、可选支线;
   硬锁只给节奏点睛处——Prominence 阶段等级锁(打 Boss 才解锁下一段等级上限)、
   End Remastered 12 眼开末地(收集式硬锁)、SevTech 配方显隐。共识:锁"功率"不锁
   "方向",每个锁必须配可见的解锁路径,难度墙不是锁。
3. **终局 = 收集门 + 无限纵深**:Vault Hunters 集 25 件 artifact 开最终宝库;ATM 之星=
   全 mod 毕业文凭+天文数字消耗+标志性合成仪式;Prominence 神器自带升级树陪全程,
   Mythic Boss 无限缩放难度。通关事件与通关后可刷循环缺一不可。

## 二、牵响现状对表

| 维度 | 头部包做法 | 牵响现状 | 差距 |
|---|---|---|---|
| 引导 | 任务书章节+副标题攻略 | 成就树+首进聊天一句 | **中**:成就树是骨架但没有"下一步该干嘛"的密度 |
| 软锁 | 距离/维度难度缩放、推荐铭牌 | 位格门控(≥3 进维度) | 小:有骨架,缺"推荐位格"的信息透明 |
| 硬锁 | Boss 解锁等级段、收集门 | 守望者→森罗之核→传奇锻造 | 小:结构同构,只单层 |
| 终局 | 收集门+升级树+无限缩放 | 传奇装备=终点 | **大**:拿到传奇即剧终,无升级树无可刷循环 |
| Boss 设计 | 机制要求(只吃近战/远程)、全体参战解锁、禁放置 | 三阶段+独占掉落 | 中:缺 build 检验与多人规则 |
| 装备循环 | 稀有度+重铸 RNG+升级树 | 材料即零件(已差异化) | 小:可加"品质重铸"层 |
| 中期横向 | 声望目击制、悬赏板、收集支线 | 声望烙印+两 NPC | 中:声望是账本不是玩法 |
| 差异化 | — | AI 配方大脑、相谱传记、UGC 材料、蓝图分享 | 别家没有,别丢 |

## 三、行动清单(按性价比排序)

1. **传奇装备升级树**(Prominence 神器模式):森罗之核锻出的传奇装备可以"喂材料升级"
   (吞噬算子提升词条),与"材料即零件"天然同构——一举解决终局断点。
2. **可重复挑战**:裂隙遗迹做成可重复刷的点,难度随完成次数缩放+随机词缀(带词缀的
   稀有怪掉词缀材料,直接进锻造台当零件)——DawnCraft 稀有度梯队已验证低成本高回报。
3. **引导加密**:成就树每个节点补"副标题即攻略"一行(在哪/怎么解锁/给什么);
   主线加实物奖励(材料/蓝图位)。照 BMC4 模式,纯数据活。
4. **Boss 规则包**:守望者加推荐位格铭牌;多人参战全体解锁进度(服务器工坊场景);
   可选:二阶段选一路径(近战检验/法术检验),照顾三轨偏科玩家。
5. **声望玩法化**:烙印改"目击制"——无目击者不留痕、睡觉结算、可赎(DawnCraft 声望
   三原则),让声望从账本变系统。
6. **蓝图 gating 完整性审查**:外部蓝图只共享"配方结构",材料必须本存档自获取——
   防止分享码绕过整个进度锁(SevTech 配方显隐思路)。
7. **相谱升维**:相谱从记录器变成 AI 委托链的输入("你曾在裂隙败北,带 X 复仇")——
   两个头部包都没有的差异化,AI 配方大脑的自然延伸。
8. **避坑**:不做上千格跑图的分散结构(DawnCraft 最大差评);毕业仪式要"截图即传播"
   (ATM 之星的 55 机械手星形给我们的合成仪式提了个醒:仪式已经是我们的强项)。

## 来源
- RLCraft Wiki(Skills/Nether/Lost Cities): https://rlcraft.wiki.gg
- DawnCraft Wiki(Progression/Classes/Reputation): https://dawncraft.fandom.com
- Prominence II Modrinth + 版本日志(3.0.0 天赋树/3.0.20 阶段锁/3.0.30 任务大修):
  https://modrinth.com/modpack/prominence-2-fabric
- ATM9 官方仓库(任务书/KubeJS/ATM Star): https://github.com/AllTheMods/ATM-9
- BMC4 任务书(v59 mrpack 一手): https://modrinth.com/modpack/better-mc-forge-bmc4
- FTB Quests 开发者文档: https://docs.feed-the-beast.com/mod-docs/mods/suite/Quests/
- GTNH Beginner Tips: https://wiki.gtnewhorizons.com/wiki/Beginner_Tips
- Vault Hunters: https://www.curseforge.com/minecraft/modpacks/vault-hunters-official-modpack
- Cisco's RPG Wiki(同生态对照): https://ciscos-rpg.fandom.com/wiki/Progression
