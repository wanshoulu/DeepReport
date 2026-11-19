#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Capability Router — DeepReport Agent 编排层（MCP client 版，v0.2）。

闭环：
  模板 capabilities 声明（机读）
    -> MCP tools/list 真实发现（不硬编码工具名）
    -> 查 capability-routes.json 路由表
    -> 生产网关 probe（table_extract），依据工具真实返回调整决策
    -> MCP tools/call 执行能力工具（table_compose / chart_render）
    -> 回填 artifact.tables / artifact.charts
    -> 声明-交付对账门禁（第三道，与 numeric / citation 并列）

分层职责：
  模板      声明要什么，不写生成逻辑
  Agent     发现 + 路由 + 依据返回调整决策 + 回填
  Skill     方法论契约（何时用、参数怎么组织、失败怎么降级），不执行
  MCP Tool  原子执行，先注册再被 tools/list 发现、tools/call 调用
  门禁      required 声明未交付即 BLOCKING

生产端接线状态见 reviews/capability-mcp-live-run-1120.md。
"""
import argparse
import html
import json
import os
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "mcp"))
from client import McpClient, McpError  # noqa: E402


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


def skill_contract(name):
    """读 Skill 的 SKILL.md frontmatter（progressive disclosure 第一级）。
    v0.2 起 Skill 只声明方法论与所绑定的 MCP 工具，不再声明可执行 entry。"""
    p = REPO / "skills" / name / "SKILL.md"
    if not p.exists():
        return {}
    text = p.read_text(encoding="utf-8")
    meta = {}
    if text.startswith("---"):
        for line in text.split("---", 2)[1].splitlines():
            if ":" in line:
                k, v = line.split(":", 1)
                meta[k.strip()] = v.strip()
    return meta


def call_gateway(endpoint, tool_name, arguments, timeout=40):
    """调用生产网关 /v1/tools/call（MCP 风格），用于探测已有工具。"""
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    body = {"tool_name": tool_name, "arguments": arguments}
    req = urllib.request.Request(
        endpoint,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with op.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


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
    ap = argparse.ArgumentParser(description="DeepReport capability router（MCP client）")
    ap.add_argument("--template", default=str(REPO / "examples" / "capability-declared-template.md"))
    ap.add_argument("--materials-dir", required=True)
    ap.add_argument("--registry", default=str(REPO / "source" / "config" / "tools.json"))
    ap.add_argument("--routes", default=str(REPO / "source" / "config" / "capability-routes.json"))
    ap.add_argument("--tools-endpoint", default="http://192.144.137.185:18083/v1/tools/call")
    ap.add_argument("--mcp-transport", choices=["stdio", "http", "gateway"], default="stdio",
                    help="stdio=标准 MCP 本地子进程；http=标准 MCP HTTP；gateway=生产网关 /v1/tools 映射")
    ap.add_argument("--mcp-url", default="http://172.17.0.1:8090/mcp")
    ap.add_argument("--mcp-server", default=str(REPO / "mcp" / "capability_server.py"))
    ap.add_argument("--offline", action="store_true",
                    help="跳过生产网关 probe；MCP stdio 本地调用照常执行")
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
    if not materials:
        violations.append({"rule": "MATERIALS_EMPTY", "severity": "BLOCKING", "target": args.materials_dir})

    # 1) MCP tools/list 真实发现（不硬编码工具名）
    mcp_tools = []
    try:
        with McpClient(args.mcp_transport, server=args.mcp_server, url=args.mcp_url) as client:
            mcp_tools = client.list_tools()
            log_decision(decisions, "MCP_DISCOVERY",
                         "tools/list 返回 %d 个 MCP 工具：%s；server=%s transport=%s"
                         % (len(mcp_tools), [t.get("name") for t in mcp_tools],
                            client.server_info.get("name"), args.mcp_transport))
            log_decision(decisions, "DISCOVERY",
                         "生产网关注册表 %d 个工具：%s" % (len(registered), sorted(registered)))

            artifact = {"tables": [], "charts": []}
            for cap in caps:
                _dispatch(cap, args, client, mcp_tools, routes, registered, materials,
                          artifact, decisions, tool_calls, violations)
    except (McpError, OSError) as exc:
        violations.append({"rule": "MCP_UNAVAILABLE", "severity": "BLOCKING",
                           "target": args.mcp_transport, "detail": str(exc)[:300]})
        log_decision(decisions, "MCP_DISCOVERY", "tools/list 失败：%s" % str(exc)[:200])
        artifact = {"tables": [], "charts": []}

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
        "mcp_discovery": {"transport": args.mcp_transport, "tools": mcp_tools},
        "decision_log": decisions,
        "tool_calls": tool_calls,
        "artifact": artifact,
        "gate": gate,
        "limitations": [
            "Agent 编排层为 Python 原型；Java 六工位与 DeepSearch 引擎 agent 序列仍未调用本 router",
            "路由为配置驱动以求可审计，未实现 LLM 自主选工具（model-controlled）",
            "生产网关已注册同名 MCP 桥接工具，但六工位仍未按模板声明自动触发路由",
        ],
    }
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if args.markdown_out or args.html_out:
        md = ["# 能力路由产出的图表补强件", "",
              "> 由模板 capabilities 声明驱动，Agent 经 MCP tools/list 发现、tools/call 执行后产出；数字照抄材料原文。", ""]
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
                "<!doctype html><meta charset='utf-8'><title>DeepReport MCP capability artifacts</title>"
                "<body style='font-family:sans-serif;max-width:1000px;margin:24px'>" + "".join(body) + "</body>",
                encoding="utf-8")

    print(json.dumps({
        "status": status,
        "declared": len(declared),
        "delivered": len(delivered),
        "missing": missing,
        "mcp_tools": [t.get("name") for t in mcp_tools],
        "tables": len(artifact["tables"]),
        "table_rows": sum(len(t.get("rows", [])) for t in artifact["tables"]),
        "charts": len(artifact["charts"]),
        "chart_nodes_with_evidence": sum(
            len([n for n in c.get("nodes", []) if n.get("evidence")]) for c in artifact["charts"]),
        "tool_calls": len(tool_calls),
        "out": args.out,
    }, ensure_ascii=False))
    return 0 if status == "PASSED" else 1


def _dispatch(cap, args, client, mcp_tools, routes, registered, materials,
              artifact, decisions, tool_calls, violations):
    cid, kind = cap.get("id", ""), cap.get("kind", "")
    route = routes.get(kind)
    if not route:
        violations.append({"rule": "ROUTE_MISSING", "severity": "BLOCKING", "target": cid,
                           "detail": "路由表没有 kind=%s 的条目，Agent 不静默跳过" % kind})
        log_decision(decisions, "ROUTE", "%s -> 无路由，记 BLOCKING" % cid)
        return

    mcp_name = route.get("mcp_tool")
    # 2) 工具发现对账：路由表声明的 MCP 工具必须真的出现在 tools/list 里
    if not mcp_name or not any(t.get("name") == mcp_name for t in mcp_tools):
        violations.append({"rule": "MCP_TOOL_NOT_FOUND", "severity": "BLOCKING", "target": cid,
                           "detail": "路由表声明 %s，但 tools/list 未返回该工具" % mcp_name})
        log_decision(decisions, "MCP_DISCOVERY", "%s: %s 未被 tools/list 发现，记 BLOCKING" % (cid, mcp_name))
        return

    # Skill 方法论契约对齐检查（Skill 不执行，只校验它绑定的 MCP 工具与路由表一致）
    skill_name = route.get("methodology_skill")
    if skill_name:
        meta = skill_contract(skill_name)
        bound = meta.get("mcp_tool")
        if bound and bound != mcp_name:
            violations.append({"rule": "SKILL_CONTRACT_MISMATCH", "severity": "BLOCKING", "target": cid,
                               "detail": "Skill %s 声明 mcp_tool=%s，路由表为 %s" % (skill_name, bound, mcp_name)})
        log_decision(decisions, "SKILL_CONTRACT",
                     "%s: 方法论引用 %s（mcp_tool=%s，声明 entry 执行=%s）"
                     % (cid, skill_name, bound, "有" if meta.get("entry") else "无"))

    # 3) 生产网关 probe：依据工具真实返回调整决策
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
            resp = call_gateway(args.tools_endpoint, tool, {"bank": "live"})
            tool_calls.append({"capability_id": cid, "tool": tool, "via": "gateway",
                               "ok": resp.get("ok"), "table_count": resp.get("table_count")})
            count = resp.get("table_count", 0) or 0
            log_decision(decisions, "TOOL_CALL",
                         "%s: 网关 %s 返回 ok=%s table_count=%s" % (cid, tool, resp.get("ok"), count))
            if count > 0 and resp.get("tables"):
                source = cap.get("source_of_truth", "materials")
                if source == "materials":
                    # 模板声明以导入材料为准：快照库里的表（可能来自其它主体/其它报告）
                    # 不得回填，否则就是串数据。探测结果只作为路由依据。
                    log_decision(decisions, "SOURCE_GUARD",
                                 "%s: 网关 %s 返回 %s 张表，但模板 source_of_truth=materials，"
                                 "拒绝回填快照表（避免混合主体库串数据），改走 MCP 材料成表"
                                 % (cid, tool, count))
                else:
                    for t in resp["tables"]:
                        t["capability_id"] = cid
                        artifact["tables"].append(t)
                    probe_hit = True
                    log_decision(decisions, "DECISION",
                                 "%s: 模板 source_of_truth=%s，采纳网关 %s 张表，跳过 MCP 能力工具"
                                 % (cid, source, count))
        except Exception as exc:
            tool_calls.append({"capability_id": cid, "tool": tool, "error": str(exc)[:200]})
            log_decision(decisions, "TOOL_CALL", "%s: 网关 %s 调用失败 %s" % (cid, tool, str(exc)[:120]))

    if probe_hit:
        return

    if route.get("probe_tools"):
        log_decision(decisions, "DECISION",
                     "%s: 探测工具未产出表格，按 fallback=%s 改调 MCP 工具 %s（%s）"
                     % (cid, route.get("fallback"), mcp_name, route.get("fallback_reason", "")))
    else:
        log_decision(decisions, "DECISION",
                     "%s: 路由表 registered_tool=%s，直接调 MCP 工具 %s"
                     % (cid, route.get("registered_tool"), mcp_name))

    # 4) MCP tools/call 执行（唯一执行入口，不再本地跑 Skill 脚本）
    try:
        out = client.call_tool(mcp_name, {
            "capability": cap,
            "materials": materials,
            "tables": artifact["tables"],
        })
        if out.get("error"):
            raise McpError(out["error"])
        out.setdefault("capability_id", cid)
        out["produced_by"] = "mcp:%s" % mcp_name
        if kind == "table":
            artifact["tables"].append(out)
        elif kind == "chart":
            artifact["charts"].append(out)
        tool_calls.append({"capability_id": cid, "tool": mcp_name, "via": "mcp",
                           "rows_or_nodes": len(out.get("rows", out.get("nodes", [])))})
        log_decision(decisions, "MCP_CALL",
                     "%s: tools/call %s 返回 rows/nodes=%s" %
                     (cid, mcp_name, len(out.get("rows", out.get("nodes", [])))))
        log_decision(decisions, "BACKFILL",
                     "%s -> artifact.%s，produced_by=%s" %
                     (cid, "tables" if kind == "table" else "charts", out["produced_by"]))
    except Exception as exc:
        violations.append({"rule": "MCP_CALL_FAILED", "severity": "BLOCKING", "target": cid,
                           "detail": str(exc)[:300]})
        log_decision(decisions, "MCP_CALL", "%s: tools/call %s 失败 %s" % (cid, mcp_name, str(exc)[:150]))


if __name__ == "__main__":
    sys.exit(main())
