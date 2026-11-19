#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""示例：通过 MCP client 调用 table_compose —— 不是执行入口。

v0.2 起确定性抽取逻辑唯一位于 mcp/capability_server.py::compose_table。
本文件仅演示「Skill 如何引用 MCP 工具」，Agent 生产路径请用 source/capability-router/router.py。

stdin  : {"capability": {...}, "materials": [{"file":..., "text":...}]}
stdout : MCP 工具返回的结构化表格 JSON
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "mcp"))
from client import McpClient  # noqa: E402


def main():
    payload = json.load(sys.stdin)
    with McpClient("stdio", server=str(REPO / "mcp" / "capability_server.py")) as client:
        client.list_tools()
        out = client.call_tool("table_compose", {
            "capability": payload.get("capability", {}),
            "materials": payload.get("materials", []),
        })
    json.dump(out, sys.stdout, ensure_ascii=False)
    return 1 if out.get("error") else 0


if __name__ == "__main__":
    sys.exit(main())
