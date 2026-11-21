# skills/ —— 技能组合包（仓库内组织，非发布包）

本目录是 DeepReport 的技能资产组织：`annual-report/` 与 `general-report/` 两个真 Skill。
每个 Skill 目录含 `SKILL.md` 入口（name=slug=目录名）与 `references/` 按需加载资源。

## 发布口径

本目录是**仓库内组合包**，不是发布单元。上架 openJiuwen Agentic Hub 时须逐个发布：

```bash
agentichub pack skills/annual-report   # 发布 annual-report
agentichub pack skills/general-report  # 发布 general-report
```

发布根只允许一个含 SKILL.md 的子目录（skillhub 校验规则），`invalid_plugin_structure` 会拒绝多根裸包。
