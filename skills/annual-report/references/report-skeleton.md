# Report Skeleton（年报简报骨架方法）

## 方法原则：名称保留、深度收缩

真实年报动辄两三百页、十几章。简报不发明自己的目录，而是：

1. **名称保留**：章节命名沿用目标机构公开披露的真实 TOC（章号、节号、节名），让读者能按图索骥回到原文。
2. **深度收缩**：每个真实章节在简报里收缩为一节——数据分析章节保留定量结论与引用，叙述性章节（公司简介、ESG、治理、重要事项、股份变动、财务报告）收缩为一段通用说明，明确指引「详见官方披露原文」。

## 唯一事实源：report-structure.json

骨架的全部差异是**数据配置**，不是代码。任何生成器、服务端、前端都不得硬编码：

- `section_order` — 14 节输出顺序；
- `sections` — 每节 `role`（`static` 静态说明 / `analysis` 数据分析）与责任 `agent`；
- `static_bodies` — 静态节正文模板，支持占位符 `{company}`、`{snapshot_version}`、`{primary_pages}`、`{comparison_pages}`（取自 manifest）；
- `banks.<bank>.titles` — 该机构每节的完整标题（含真实章号）。

**新增一家银行** = 在 `banks` 下加一组 `titles`，零代码改动。**调整章号** = 改 JSON。装配器（gen 归一化 / 服务端 pipeline writing 阶段 / 前端渲染）一律读配置：

- 标题必须来自 `banks.<bank>.titles`，缺配置即报错（fail-closed），不允许回退到素材文件里的内部描述名；
- 静态节正文必须经 `static_bodies` 模板填充，产物中不得残留 `{` 占位符或 `undefined`；
- 节序必须与 `section_order` 完全一致，缺 `analysis` 节视为素材缺失。

## 数据分析节（role=analysis）的成稿纪律

- 每节正文为若干陈述句，每句结论恰好对应一条引用（claim → citation，`[C-n]` 协议见 citation-style.md）；
- 图表挂载在语义匹配的节上（如收入结构 pie 挂收入结构节），挂载关系随图表数据文件走；
- 数值全部来自快照页字面文本（N3），派生口径可复算（N4），单位与变动口径合规（N2/N5/N6/N7）。

## 与核验的关系

骨架一致性本身是构建期自检项：节序 = `section_order`、标题 = 配置标题、静态节占位符全替换。任何一项失败即生成失败，不进入交付链。
