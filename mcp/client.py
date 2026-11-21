#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 MCP client（JSON-RPC 2.0），供 DeepReport Agent 编排层使用。

两种 transport 对上层接口完全一致：
  stdio : 以子进程拉起 capability_server.py，逐行 JSON-RPC（本地 Agent 默认）
  http  : POST /mcp（生产网关桥接 / 远端直连场景）

零第三方依赖。刻意不硬编码工具名：可用工具一律来自 tools/list。
"""
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_SERVER = HERE / "capability_server.py"


class McpError(RuntimeError):
    pass


def _gateway_schema(tool_def):
    """把生产网关 tools.json 的 params[] 转成 MCP inputSchema，供 Agent 统一按 schema 推理。"""
    props, required = {}, []
    for p in tool_def.get("params", []):
        props[p.get("name")] = {"type": p.get("type", "string"),
                               "description": p.get("desc", "")}
        if p.get("required"):
            required.append(p.get("name"))
    schema = {"type": "object", "properties": props}
    if required:
        schema["required"] = required
    return schema


class McpClient:
    def __init__(self, transport="stdio", server=None, url=None, timeout=60, python=None):
        self.transport = transport
        default_url = ("http://192.144.137.185:18083" if transport == "gateway"
                       else "http://172.17.0.1:8090/mcp")
        self.url = (url or default_url).rstrip("/")
        self.timeout = timeout
        self.server = Path(server) if server else DEFAULT_SERVER
        self.python = python or sys.executable
        self.proc = None
        self._rid = 0
        self.tools = []

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.close()

    def start(self):
        if self.transport == "gateway":
            # 生产网关不是 JSON-RPC 端点：tools/list 映射 GET /v1/tools，
            # tools/call 映射 POST /v1/tools/call（ToolGateway 自称 MCP 风格）。
            self.server_info = {"name": "w06-tool-gateway", "transport": "gateway"}
            return self.server_info
        if self.transport == "stdio":
            if not self.server.exists():
                raise McpError("MCP Server 不存在: %s" % self.server)
            self.proc = subprocess.Popen(
                [self.python, str(self.server), "--transport", "stdio"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                # stderr 继承父进程，避免 PIPE 未读导致子进程阻塞
                env=dict(os.environ, PYTHONIOENCODING="utf-8"),
                bufsize=1, text=True, encoding="utf-8",
            )
        info = self.request("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "deepreport-capability-router", "version": "0.2.0"},
        })
        self.server_info = info.get("serverInfo", {})
        self.notify("notifications/initialized", {})
        return info

    def close(self):
        if self.proc is not None:
            try:
                self.proc.stdin.close()
                self.proc.terminate()
                self.proc.wait(timeout=5)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass
            self.proc = None

    def _next_id(self):
        self._rid += 1
        return self._rid

    def _exchange(self, payload):
        if self.transport == "gateway":
            raise McpError("gateway transport 不走 JSON-RPC，请用 list_tools / call_tool")
        if self.transport == "stdio":
            if self.proc is None or self.proc.poll() is not None:
                raise McpError("MCP Server 进程未运行或已退出")
            self.proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
            self.proc.stdin.flush()
            line = self.proc.stdout.readline()
            if not line:
                raise McpError("MCP Server 无响应（stdout 关闭）")
            return json.loads(line)
        req = urllib.request.Request(
            self.url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with op.open(req, timeout=self.timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def request(self, method, params=None):
        payload = {"jsonrpc": "2.0", "id": self._next_id(),
                   "method": method, "params": params or {}}
        resp = self._exchange(payload)
        if "error" in resp:
            raise McpError("%s -> %s" % (method, resp["error"].get("message")))
        return resp.get("result", {})

    def notify(self, method, params=None):
        """notification 不期待响应帧；stdio 下也不读取，避免错帧。"""
        if self.transport == "gateway":
            return
        payload = {"jsonrpc": "2.0", "method": method, "params": params or {}}
        if self.transport == "stdio":
            if self.proc is None:
                raise McpError("MCP Server 进程未运行")
            self.proc.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
            self.proc.stdin.flush()
        else:
            try:
                self._exchange(payload)
            except Exception:
                pass

    def list_tools(self):
        if self.transport == "gateway":
            op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with op.open(self.url + "/v1/tools", timeout=self.timeout) as resp:
                raw = json.loads(resp.read().decode("utf-8")).get("tools", [])
            self.tools = [{
                "name": t.get("tool_id"),
                "description": t.get("description", ""),
                "inputSchema": _gateway_schema(t),
                "executor": t.get("executor", "java_native"),
            } for t in raw]
            return self.tools
        self.tools = self.request("tools/list").get("tools", [])
        return self.tools

    def has_tool(self, name):
        return any(t.get("name") == name for t in self.tools)

    def call_tool(self, name, arguments):
        if self.transport == "gateway":
            body = {"tool_name": name, "arguments": arguments}
            req = urllib.request.Request(
                self.url + "/v1/tools/call",
                data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with op.open(req, timeout=self.timeout) as resp:
                out = json.loads(resp.read().decode("utf-8"))
            if not out.get("ok"):
                raise McpError("网关工具 %s 返回: %s" % (name, str(out.get("error"))[:300]))
            return out
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        if result.get("isError"):
            detail = ""
            for c in result.get("content", []):
                if c.get("type") == "text":
                    detail = c.get("text", "")
                    break
            raise McpError("工具 %s 返回 isError: %s" % (name, detail[:300]))
        if isinstance(result.get("structuredContent"), dict):
            return result["structuredContent"]
        for c in result.get("content", []):
            if c.get("type") == "text":
                return json.loads(c["text"])
        raise McpError("工具 %s 未返回可解析结果" % name)
