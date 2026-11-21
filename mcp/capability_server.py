#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DeepReport Capability MCP Server.

把确定性原子能力注册为标准 MCP 工具，供 Agent 通过 tools/list 发现、tools/call 调用。

双 transport（同一套工具定义，无重复实现）：
  stdio : JSON-RPC 2.0，一行一消息；stdout 只输出协议帧，日志全部走 stderr。
          用于本地 Agent（MCP client 以子进程方式拉起）。
  http  : POST /mcp 收 JSON-RPC 2.0，GET /health 探活。
          用于生产网关 ToolGateway 的 mcp_bridge 分支（容器经 172.17.0.1 访问）。

零第三方依赖（仅标准库）；不调用模型、不访问公网、不落盘用户材料。
工具定义来自同目录 tools.json —— 唯一事实源。
"""
import argparse
import hashlib
import html
import json
import re
import sys
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONTRACT = json.loads((HERE / "tools.json").read_text(encoding="utf-8"))
PROTOCOL_VERSION = CONTRACT["protocol_version"]
SERVER_INFO = {
    "name": CONTRACT["server"]["name"],
    "version": CONTRACT["server"]["version"],
}
TOOL_DEFS = [
    {"name": t["name"], "description": t["description"], "inputSchema": t["inputSchema"]}
    for t in CONTRACT["tools"]
]
EXECUTORS = {t["name"]: t["executor"] for t in CONTRACT["tools"]}


def stderr(msg):
    print("[mcp] %s" % msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------
# executor: table_compose —— 从材料原文确定性成表（逻辑上移自 table-composition Skill）
# ---------------------------------------------------------------------------
METRIC_RULES = [
    ("核心一级资本充足率", r"核心一级资本充足率不得低于\s*(\d+(?:\.\d+)?)%", "第二十六条"),
    ("一级资本充足率", r"(?<!核心)一级资本充足率不得低于\s*(\d+(?:\.\d+)?)%", "第二十六条"),
    ("资本充足率", r"(?<!核心一级)(?<!一级)资本充足率不得低于\s*(\d+(?:\.\d+)?)%", "第二十六条"),
    ("储备资本要求", r"储备资本要求为风险加权资产的\s*(\d+(?:\.\d+)?)%", "第二十七条"),
    ("杠杆率", r"杠杆率不得低于\s*(\d+(?:\.\d+)?)%", "第三十条"),
]

TECH_TERMS = [
    "风险管理平台", "规则策略", "模型算法", "集中统一管理", "全流程管理",
    "大数据", "人工智能", "风险监测预警", "数据治理", "数据质量", "数据标准",
    "交叉验证", "压力情景", "模拟校验", "可解释性", "可审计性", "算法歧视",
    "消费者权益保护", "模型管理核心环节", "数据分级分类", "脱敏", "隐私保护",
    "灾备", "实时", "规则引擎", "联邦学习", "图计算",
]

CLAUSE_RE = re.compile(r"(（[一二三四五六七八九十百]+）|第[一二三四五六七八九十百]+条)")


def fingerprint(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def _metric_table(cap, materials):
    rows, miss, sources = [], [], []
    for label, pattern, clause in METRIC_RULES:
        found = False
        for m in materials:
            for hit in re.finditer(pattern, m["text"]):
                start = max(0, m["text"].rfind("。", 0, hit.start()) + 1)
                end = m["text"].find("。", hit.end())
                end = len(m["text"]) if end < 0 else end + 1
                excerpt = m["text"][start:end].strip()
                rows.append([label, hit.group(1) + "%", clause, excerpt, m["file"]])
                sources.append({"file": m["file"], "sha256_12": fingerprint(m["text"])})
                found = True
                break
            if found:
                break
        if not found:
            miss.append({"metric": label, "reason": "原文未命中字面数值，未补全"})
    return {
        "capability_id": cap["id"],
        "headers": ["监管指标", "最低要求（原文）", "条款", "原文摘录", "来源文件"],
        "rows": rows,
        "sources": list({s["file"]: s for s in sources}.values()),
        "extraction_rule": "指标词 + 不得低于/要求为 + 字面百分比；数字照抄原文，不做换算",
        "extraction_miss": miss,
    }


def _clause_matrix(cap, materials):
    rows, sources = [], []
    for m in materials:
        text = m["text"]
        marks = list(CLAUSE_RE.finditer(text))
        for i, hit in enumerate(marks):
            seg_end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
            segment = text[hit.start():seg_end].strip()
            first = re.split(r"[。\n]", segment, maxsplit=1)[0].strip()
            if len(first) < 8:
                continue
            terms = [t for t in TECH_TERMS if t in segment]
            rows.append([hit.group(1), first[:60],
                         "、".join(terms[:5]) if terms else "（未命中技术关键词）", m["file"]])
            sources.append({"file": m["file"], "sha256_12": fingerprint(text)})
    seen, uniq = set(), []
    for r in rows:
        key = (r[0], r[1][:20], r[3])
        if key not in seen:
            seen.add(key)
            uniq.append(r)
    return {
        "capability_id": cap["id"],
        "headers": ["条款", "要求摘要（原文首句）", "技术落点关键词", "来源文件"],
        "rows": uniq[:24],
        "sources": list({s["file"]: s for s in sources}.values()),
        "extraction_rule": "中文条款号切段 + 首句摘要 + 领域关键词命中；不生成新论断",
        "extraction_miss": [] if uniq else [{"reason": "材料中未识别到中文条款号"}],
    }


def compose_table(args):
    cap = args.get("capability", {})
    materials = [m for m in args.get("materials", []) if isinstance(m, dict) and m.get("text")]
    if not cap.get("id"):
        return {"error": "capability.id 必填", "rows": []}
    if not materials:
        return {"error": "materials 为空，拒绝产出空表壳", "rows": [], "capability_id": cap.get("id")}
    spec = str(cap.get("spec", ""))
    if "条款" in spec or "矩阵" in spec or "clause" in str(cap.get("id", "")):
        return _clause_matrix(cap, materials)
    return _metric_table(cap, materials)


# ---------------------------------------------------------------------------
# executor: chart_render —— 确定性渲染内联 SVG（逻辑上移自 chart-composition Skill）
# ---------------------------------------------------------------------------
STAGES = [
    ("数据层", ["数据治理", "数据质量", "数据标准", "企业级大数据平台", "外部数据", "数据分级分类", "统一风险视图"]),
    ("模型层", ["模型算法", "集中统一管理", "规则策略", "评分卡", "联邦学习", "模型开发", "压力情景", "交叉验证"]),
    ("决策层", ["规则引擎", "风控决策引擎", "风险监测预警", "实时", "嵌入式", "业务流程", "一键上线"]),
    ("审计层", ["可解释性", "可审计性", "算法歧视", "消费者权益保护", "退出", "评价", "自主掌控"]),
]

SVG_W, SVG_H = 920, 230
BOX_W, BOX_H, GAP = 200, 130, 20
FILLS = ["#E8F1FB", "#E6F4EA", "#FFF4E5", "#F3E8FB"]
STROKES = ["#2F6FB5", "#2E7D4F", "#B26A16", "#7A4CA3"]


def _find_evidence(materials, terms):
    for m in materials:
        text = m["text"]
        for term in terms:
            idx = text.find(term)
            if idx < 0:
                continue
            start = max(0, text.rfind("。", 0, idx) + 1)
            end = text.find("。", idx)
            end = len(text) if end < 0 else end + 1
            return term, text[start:end].strip(), m["file"]
    return None, "", ""


def render_chart(args):
    cap = args.get("capability", {})
    materials = [m for m in args.get("materials", []) if isinstance(m, dict) and m.get("text")]
    if not cap.get("id"):
        return {"error": "capability.id 必填", "nodes": [], "svg": ""}
    ctype = cap.get("chart_type", "flow")
    if ctype != "flow":
        return {"error": "unsupported chart_type: %s" % ctype,
                "capability_id": cap.get("id"), "nodes": [], "svg": ""}

    nodes, miss = [], []
    for stage, terms in STAGES:
        term, evidence, src = _find_evidence(materials, terms)
        if not evidence:
            miss.append({"stage": stage, "reason": "材料原文未命中该层关键词"})
            nodes.append({"stage": stage, "label": "", "evidence": "", "source_file": ""})
        else:
            nodes.append({"stage": stage, "label": term,
                          "evidence": evidence[:120], "source_file": src})

    parts = [
        '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
        'role="img" aria-label="风控系统分层架构图">' % (SVG_W, SVG_H, SVG_W, SVG_H),
        '<rect x="0" y="0" width="%d" height="%d" fill="#FFFFFF"/>' % (SVG_W, SVG_H),
        '<text x="16" y="24" font-size="14" fill="#1F2933" font-family="sans-serif">'
        '风控系统分层架构（节点文字取自材料原文，见 evidence）</text>',
    ]
    for i, n in enumerate(nodes):
        x = 16 + i * (BOX_W + GAP)
        y = 50
        parts.append('<rect x="%d" y="%d" width="%d" height="%d" rx="8" fill="%s" stroke="%s" stroke-width="2"/>'
                     % (x, y, BOX_W, BOX_H, FILLS[i], STROKES[i]))
        parts.append('<text x="%d" y="%d" font-size="15" font-weight="bold" fill="#12212F" '
                     'font-family="sans-serif">%s</text>' % (x + 14, y + 30, html.escape(n["stage"])))
        label = n["label"] if n["label"] else "（材料未覆盖）"
        parts.append('<text x="%d" y="%d" font-size="13" fill="#33404D" font-family="sans-serif">%s</text>'
                     % (x + 14, y + 60, html.escape(label[:16])))
        if n["evidence"]:
            ev = html.escape(n["evidence"][:10])
            parts.append('<text x="%d" y="%d" font-size="11" fill="#5A6875" font-family="sans-serif">%s…</text>'
                         % (x + 14, y + 86, ev))
            parts.append('<text x="%d" y="%d" font-size="10" fill="#7A8794" font-family="sans-serif">来自 %s</text>'
                         % (x + 14, y + 108, html.escape(n["source_file"])))
        if i < len(nodes) - 1:
            ax, cy = x + BOX_W, y + BOX_H // 2
            parts.append('<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#8A97A5" stroke-width="2"/>'
                         % (ax + 2, cy, ax + GAP - 10, cy))
            parts.append('<polygon points="%d,%d %d,%d %d,%d" fill="#8A97A5"/>'
                         % (ax + GAP - 4, cy, ax + GAP - 12, cy - 5, ax + GAP - 12, cy + 5))
    parts.append('</svg>')
    return {"capability_id": cap["id"], "chart_type": "flow",
            "svg": "".join(parts), "nodes": nodes, "extraction_miss": miss}


EXEC_IMPL = {"compose_table": compose_table, "render_chart": render_chart}


# ---------------------------------------------------------------------------
# JSON-RPC 2.0 / MCP
# ---------------------------------------------------------------------------
def handle_rpc(req):
    """返回响应 dict；notification 返回 None（不回帧）。"""
    if not isinstance(req, dict):
        return {"jsonrpc": "2.0", "id": None,
                "error": {"code": -32600, "message": "Invalid Request"}}
    method = req.get("method", "")
    rid = req.get("id")
    is_notification = "id" not in req
    params = req.get("params") or {}

    def result(value):
        return None if is_notification else {"jsonrpc": "2.0", "id": rid, "result": value}

    def error(code, message):
        return None if is_notification else {
            "jsonrpc": "2.0", "id": rid, "error": {"code": code, "message": message}}

    if method == "initialize":
        return result({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": SERVER_INFO,
            "instructions": CONTRACT["server"]["description"],
        })
    if method in ("notifications/initialized", "notifications/cancelled"):
        return None
    if method == "ping":
        return result({})
    if method == "tools/list":
        return result({"tools": TOOL_DEFS})
    if method == "tools/call":
        name = params.get("name", "")
        arguments = params.get("arguments") or {}
        if name not in EXECUTORS:
            return error(-32602, "未知 MCP 工具: %s（tools/list 查看可用工具）" % name)
        try:
            out = EXEC_IMPL[EXECUTORS[name]](arguments)
            failed = bool(out.get("error"))
            text = json.dumps(out, ensure_ascii=False)
            return result({
                "content": [{"type": "text", "text": text}],
                "structuredContent": out,
                "isError": failed,
            })
        except Exception as exc:
            stderr("tools/call %s 异常: %s" % (name, traceback.format_exc(limit=3)))
            return result({
                "content": [{"type": "text", "text": json.dumps(
                    {"error": "executor_failed: %s" % exc}, ensure_ascii=False)}],
                "isError": True,
            })
    if method in ("resources/list", "prompts/list"):
        return result({"resources": []} if method.startswith("resources") else {"prompts": []})
    return error(-32601, "Method not found: %s" % method)


def serve_stdio():
    stderr("stdio transport ready (tools=%s)" % ", ".join(EXECUTORS))
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception as exc:
            sys.stdout.write(json.dumps({
                "jsonrpc": "2.0", "id": None,
                "error": {"code": -32700, "message": "Parse error: %s" % exc}},
                ensure_ascii=False) + "\n")
            sys.stdout.flush()
            continue
        resp = handle_rpc(req)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


class Handler(BaseHTTPRequestHandler):
    server_version = "DeepReportMCP/0.1"

    def log_message(self, fmt, *args):
        stderr("http %s - %s" % (self.address_string(), fmt % args))

    def _send(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.split("?")[0] in ("/health", "/"):
            self._send({"status": "healthy", "server": SERVER_INFO,
                        "transport": "http", "tools": list(EXECUTORS)})
        else:
            self._send({"error": "not_found"}, 404)

    def do_POST(self):
        if self.path.split("?")[0] != "/mcp":
            self._send({"error": "not_found", "endpoint": "POST /mcp"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception as exc:
            self._send({"jsonrpc": "2.0", "id": None,
                        "error": {"code": -32700, "message": "Parse error: %s" % exc}})
            return
        if isinstance(req, list):
            resps = [r for r in (handle_rpc(x) for x in req) if r is not None]
            self._send(resps if resps else {})
        else:
            resp = handle_rpc(req)
            self._send(resp if resp is not None else {})


def serve_http(host, port):
    srv = ThreadingHTTPServer((host, port), Handler)
    srv.daemon_threads = True
    stderr("http transport listening on http://%s:%d/mcp (tools=%s)"
           % (host, port, ", ".join(EXECUTORS)))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()


def main():
    ap = argparse.ArgumentParser(description="DeepReport capability MCP Server")
    ap.add_argument("--transport", choices=["stdio", "http"], default="stdio")
    ap.add_argument("--host", default="172.17.0.1",
                    help="http 监听地址；默认仅 docker bridge 网关，避免公网暴露")
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--selftest", action="store_true",
                    help="本地自检 tools/list 与 tools/call（不发网络）")
    args = ap.parse_args()
    if args.selftest:
        print(json.dumps(handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}),
                         ensure_ascii=False, indent=2))
        return 0
    if args.transport == "http":
        serve_http(args.host, args.port)
    else:
        serve_stdio()
    return 0


if __name__ == "__main__":
    sys.exit(main())
