#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Capability Router — DeepReport Agent 编排层的最小可运行原型。

闭环：模板 capabilities 声明 -> 查路由表 -> MCP 工具发现/探测 -> 依据返回调整决策
      -> 绑定 Skill（读其 SKILL.md 的 entry）-> 执行 -> 回填 artifact -> 声明-交付对账门禁。

这不是生产端接线：Java CheckerHttpServer 六工位与 DeepSearch 引擎尚未调用本模块。
设计见 docs/capability-routing-design.md。
"""
import argparse
import html
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def log_decision(decisions, step, detail):
    decisions.append({"step": step, "detail": detail})


def parse_frontmatter(path):
    """极简 YAML frontmatter 解析：只支持本契约需要的键值与 capabilities 列表，零依赖。"""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "---":
        raise ValueError("模板缺少 YAML frontmatter，无法读取能力声明")
    end = None
    for i in range(1, len(lines)):
        if lines[i].strip() == "---":
            end = i
            break
    if end is None:
        raise ValueError("模板 frontmatter 未闭合")

    def scalar(v):
        v = v.strip().strip('"').strip("'")
        if v.lower() == "true":
            return True
        if v.lower() == "false":
            return False
        return v

    top, caps, cur = {}, [], None
    for raw in lines[1:end]:
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        if s.startswith("- "):
            if cur:
                caps.append(cur)
            cur = {}
            body = s[2:]
            if ":" in body:
                k, v = body.split(":", 1)
                cur[k.strip()] = scalar(v)
        elif ":" in s:
            k, v = s.split(":", 1)
            if cur is not None:
                cur[k.strip()] = scalar(v)
            else:
                top[k.strip()] = scalar(v)
    if cur:
        caps.append(cur)
    top["capabilities"] = caps
    return top


def skill_meta(name):
    """读 Skill 的 SKILL.md frontmatter：name/description/entry（progressive disclosure 第一级）。"""
    p = REPO / "skills" / name / "SKILL.md"
    if not p.exists():
        raise FileNotFoundError("Skill 未安装: %s" % p)
    text = p.read_text(encoding="utf-8")
    meta = {}
    if text.startswith("---"):
        for line in text.split("---", 2)[1].splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                meta[k.strip()] = v.strip()
    if "entry" not in meta:
        raise ValueError("Skill %s 的 SKILL.md 未声明 entry" % name)
    return meta, REPO / "skills" / name / meta["entry"]


def call_tool(endpoint, tool_name, arguments, timeout=40):
    """真实调用 MCP 风格工具网关 /v1/tools/call。"""
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    body = {"tool_name": tool_name, "arguments": arguments}
    req = urllib.request.Request(
        endpoint,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with op.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def run_skill(script, payload):
    """确定性执行 Skill 脚本；显式 stdin/stdout 管道，避免继承坏 stdin。"""
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    proc = subprocess.run(
        [sys.executable, str(script)],
        input=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        timeout=90,
    )
    out = proc.stdout.decode("utf-8", errors="replace").strip()
    if proc.returncode != 0:
        raise RuntimeError("Skill %s 退出码 %s: %s" % (
            script.name, proc.returncode,
            proc.stderr.decode("utf-8", errors="replace")[:300]))
    return json.loads(out)


def load_materials(dirpath):
    items = []
    for p in sorted(Path(dirpath).glob("*.txt")):
        items.append({"file": p.name, "text": p.read_text(encoding="utf-8", errors="replace")})
    return items


def markdown_table(t):
    head = "| " + " | ".join(t["headers"]) + " |"
    sep = "| " + " | ".join(["---"] * len(t["headers"])) + " |"
    rows = ["| " + " | ".join(str(c).replace("|", "\\|") for c in r) + " |" for r in t["rows"]]
    return "\n".join([head, sep] + rows)


def main():
    ap = argparse.ArgumentParser(description="DeepReport capability router 原型")
    ap.add_argument("--template", default=str(REPO / "examples" / "capability-declared-template.md"))
    ap.add_argument("--materials-dir", required=True)
    ap.add_argument("--registry", default=str(REPO / "source" / "config" / "tools.json"))
    ap.add_argument("--routes", default=str(REPO / "source" / "config" / "capability-routes.json"))
    ap.add_argument("--tools-endpoint", default="http://192.144.137.185:18083/v1/tools/call")
    ap.add_argument("--offline", action="store_true", help="跳过真实工具调用（用于离线自检）")
    ap.add_argument("--out", default="capability_run_result.json")
    ap.add_argument("--markdown-out", default="")
    ap.add_argument("--html-out", default="")
    args = ap.parse_args()

    decisions, tool_calls, violations = [], [], []
    tmpl = parse_frontmatter(args.template)
    caps = tmpl.get("capabilities", [])
    routes = json.loads(Path(args.routes).read_text(encoding="utf-8"))
    registry = json.loads(Path(args.registry).read_text(encoding="utf-8"))
    registered = {t.get("tool_id") or t.get("id") or t.get("name")
                  for t in registry.get("tools", [])}
    materials = load_materials(args.materials_dir)

    log_decision(decisions, "DECLARATION",
                 "从模板 frontmatter 解析到 %d 条能力声明：%s" % (len(caps), [c.get("id") for c in caps]))
    log_decision(decisions, "DISCOVERY",
                 "工具注册表已登记 %d 个工具（模拟 MCP tools/list）：%s" % (len(registered), sorted(registered)))
    if not materials:
        violations.append({"rule": "MATERIALS_EMPTY", "severity": "BLOCKING", "target": args.materials_dir})

    artifact = {"tables": [], "charts": []}

    for cap in caps:
        cid, kind = cap.get("id", ""), cap.get("kind", "")
        route = routes.get(kind)
        if not route:
            violations.append({"rule": "ROUTE_MISSING", "severity": "BLOCKING", "target": cid,
                               "detail": "路由表没有 kind=%s 的条目，Agent 不静默跳过" % kind})
            log_decision(decisions, "ROUTE", "%s -> 无路由，记 BLOCKING" % cid)
            continue

        # 1) MCP 工具发现与探测
        probe_hit = False
        for tool in route.get("probe_tools", []):
            if tool not in registered:
                violations.append({"rule": "TOOL_UNREGISTERED", "severity": "BLOCKING", "target": cid,
                                   "detail": "%s 未在 tools.json 注册" % tool})
                log_decision(decisions, "DISCOVERY", "%s: 工具 %s 未注册，不静默降级" % (cid, tool))
                continue
            if args.offline:
                tool_calls.append({"capability_id": cid, "tool": tool, "skipped": "offline"})
                continue
            try:
                resp = call_tool(args.tools_endpoint, tool, {"bank": "live"})
                tool_calls.append({"capability_id": cid, "tool": tool,
                                   "ok": resp.get("ok"), "table_count": resp.get("table_count")})
                count = resp.get("table_count", 0) or 0
                log_decision(decisions, "TOOL_CALL",
                             "%s: %s 返回 ok=%s table_count=%s" % (cid, tool, resp.get("ok"), count))
                if count > 0 and resp.get("tables"):
                    for t in resp["tables"]:
                        t["capability_id"] = cid
                        artifact["tables"].append(t)
                    probe_hit = True
                    log_decision(decisions, "DECISION", "%s: 工具已产出表格，跳过 Skill" % cid)
            except Exception as exc:
                tool_calls.append({"capability_id": cid, "tool": tool, "error": str(exc)[:200]})
                log_decision(decisions, "TOOL_CALL", "%s: %s 调用失败 %s" % (cid, tool, str(exc)[:120]))

        if probe_hit:
            continue

        # 2) 依据工具返回调整决策：无表 -> 按路由表 fallback 绑定 Skill
        if route.get("probe_tools"):
            log_decision(decisions, "DECISION",
                         "%s: 探测工具未产出表格，按 fallback=%s 转 Skill %s（%s）"
                         % (cid, route.get("fallback"), route.get("skill"), route.get("fallback_reason", "")))
        else:
            log_decision(decisions, "DECISION",
                         "%s: 路由表 registered_tool=null，%s；绑定 Skill %s"
                         % (cid, route.get("note", ""), route.get("skill")))

        # 3) 绑定并执行 Skill
        try:
            meta, script = skill_meta(route["skill"])
            log_decision(decisions, "SKILL_BINDING",
                         "%s -> %s（%s）entry=%s" % (cid, meta.get("name"),
                                                    str(meta.get("description", ""))[:60], meta.get("entry")))
            out = run_skill(script, {"capability": cap, "materials": materials,
                                     "tables": artifact["tables"]})
            out.setdefault("capability_id", cid)
            if kind == "table":
                artifact["tables"].append(out)
            elif kind == "chart":
                artifact["charts"].append(out)
            log_decision(decisions, "BACKFILL",
                         "%s -> artifact.%s，rows/nodes=%s" %
                         (cid, "tables" if kind == "table" else "charts",
                          len(out.get("rows", out.get("nodes", [])))))
        except Exception as exc:
            violations.append({"rule": "SKILL_FAILED", "severity": "BLOCKING", "target": cid,
                               "detail": str(exc)[:300]})
            log_decision(decisions, "SKILL_BINDING", "%s: Skill 执行失败 %s" % (cid, str(exc)[:150]))

    # 4) 声明-交付对账门禁
    declared = [c["id"] for c in caps if c.get("required", False)]
    delivered = []
    for t in artifact["tables"]:
        if t.get("rows"):
            delivered.append(t.get("capability_id"))
    for c in artifact["charts"]:
        if any(n.get("evidence") for n in c.get("nodes", [])):
            delivered.append(c.get("capability_id"))
    missing = [x for x in declared if x not in delivered]
    for cid in missing:
        violations.append({"rule": "CAPABILITY_MISSING", "severity": "BLOCKING", "target": cid,
                           "detail": "模板声明 required=true，工件未交付"})
    blocking = [v for v in violations if v.get("severity") == "BLOCKING"]
    status = "FAILED" if (missing or blocking) else "PASSED"
    gate = {"status": status, "declared": declared, "delivered": delivered,
            "missing": missing, "violations": violations, "blocking_count": len(blocking),
            "note": "并列于 numeric / citation 的第三道门禁；本原型未调用那两道"}

    result = {
        "template": os.path.basename(args.template),
        "report_type": tmpl.get("report_type"),
        "capabilities": caps,
        "decision_log": decisions,
        "tool_calls": tool_calls,
        "artifact": artifact,
        "gate": gate,
        "limitations": [
            "原型未接入 Java CheckerHttpServer 六工位，也未接入 DeepSearch 引擎 agent 序列",
            "w06-charts 未注册为 MCP 工具，chart 由 Skill 内确定性脚本承载",
            "未实现 LLM 自主选工具（model-controlled），路由为配置驱动以求可审计",
        ],
    }
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.markdown_out or args.html_out:
        md = ["# 能力路由产出的图表补强件", "",
              "> 由模板 capabilities 声明驱动，Agent 路由到 Skill / MCP 后产出；数字照抄材料原文。", ""]
        for t in artifact["tables"]:
            md += ["## %s" % t.get("capability_id"), "", markdown_table(t), ""]
            if t.get("extraction_miss"):
                md += ["未抽出项：%s" % json.dumps(t["extraction_miss"], ensure_ascii=False), ""]
        if args.markdown_out:
            Path(args.markdown_out).write_text("\n".join(md), encoding="utf-8")
        if args.html_out:
            body = []
            for t in artifact["tables"]:
                body.append("<h2>%s</h2>" % html.escape(str(t.get("capability_id"))))
                body.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse'>")
                body.append("<tr>" + "".join("<th>%s</th>" % html.escape(str(h)) for h in t["headers"]) + "</tr>")
                for r in t["rows"]:
                    body.append("<tr>" + "".join("<td>%s</td>" % html.escape(str(c)) for c in r) + "</tr>")
                body.append("</table>")
            for c in artifact["charts"]:
                body.append("<h2>%s</h2>" % html.escape(str(c.get("capability_id"))))
                body.append(c.get("svg", ""))
            Path(args.html_out).write_text(
                "<!doctype html><meta charset='utf-8'><title>DeepReport capability artifacts</title>"
                "<body style='font-family:sans-serif;max-width:1000px;margin:24px'>" + "".join(body) + "</body>",
                encoding="utf-8")

    print(json.dumps({
        "status": status,
        "declared": len(declared),
        "delivered": len(delivered),
        "missing": missing,
        "tables": len(artifact["tables"]),
        "table_rows": sum(len(t.get("rows", [])) for t in artifact["tables"]),
        "charts": len(artifact["charts"]),
        "chart_nodes_with_evidence": sum(
            len([n for n in c.get("nodes", []) if n.get("evidence")]) for c in artifact["charts"]),
        "tool_calls": len(tool_calls),
        "out": args.out,
    }, ensure_ascii=False))
    return 0 if status == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
