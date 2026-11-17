#!/usr/bin/env python3
"""
Phase 0 冒烟验证脚本（远端 DeepSearch 部署后使用）

用法:
    python phase0_smoke.py --base http://127.0.0.1:8000 --space s0 \
        [--llm-base URL --llm-key KEY --llm-model MODEL]
        [--search-engine bocha --search-key KEY]
        [--template-file path/to/annual_report.md]
        [--query "研究问题"]
        [--run]      # 配置完成后真正跑一次报告(SSE, 5-20分钟)

步骤:
  1. /api/health 健康检查
  2. 注册联网搜索引擎(web_search API, POST /api/v1/agent/deepsearch/web_search/{space_id})
  3. 导入报告模板(template API)
  4. (可选 --run) 跑一次 query-only 报告, SSE 收流, 存报告
  5. 汇总结果输出 JSON 摘要

LLM 配置说明: DeepSearch 的 LLM 通过模板导入 llm_config 与请求 metadata 传入,
若服务端有 model_manager API 则优先用它(启动后看 /docs Swagger)。
"""
import argparse
import base64
import json
import sys
import time
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8000"
SPACE = "s0"


def req(method, url, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            txt = resp.read().decode()
            return resp.status, (json.loads(txt) if txt else {})
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode()[:300]}
    except Exception as e:
        return 0, {"error": str(e)}


def sse_run(url, body, out_path, max_seconds=1500):
    """SSE 流式跑报告, 收集最终报告内容"""
    data = json.dumps(body).encode()
    r = urllib.request.Request(url, data=data, method="POST",
                               headers={"Content-Type": "application/json", "Accept": "text/event-stream"})
    report_chunks = []
    t0 = time.time()
    with urllib.request.urlopen(r, timeout=max_seconds) as resp, open(out_path, "w", encoding="utf-8") as f:
        event_name = None
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").rstrip("\n")
            if line.startswith("event:"):
                event_name = line.split(":", 1)[1].strip()
            elif line.startswith("data:"):
                payload = line.split(":", 1)[1].strip()
                f.write(payload + "\n")
                try:
                    d = json.loads(payload)
                except Exception:
                    continue
                content = d.get("content") or {}
                agent = d.get("agent", "")
                if isinstance(content, dict) and content.get("report"):
                    report_chunks.append((agent, content["report"]))
                elif isinstance(content, str) and agent in ("end", "END", "report"):
                    report_chunks.append((agent, content))
            if time.time() - t0 > max_seconds:
                break
    return report_chunks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--space", default=SPACE)
    ap.add_argument("--llm-base", default="")
    ap.add_argument("--llm-key", default="")
    ap.add_argument("--llm-model", default="")
    ap.add_argument("--search-engine", default="bocha")
    ag = ap.add_mutually_exclusive_group()
    ap.add_argument("--search-key", default="")
    ap.add_argument("--template-file", default="")
    ap.add_argument("--template-name", default="annual-report-28sec")
    ap.add_argument("--template-id", default="1")
    ap.add_argument("--web-search-id", default="1")
    ap.add_argument("--query", default="2025年中国大型商业银行经营状况与风险表现分析")
    ap.add_argument("--run", action="store_true")
    ap.add_argument("--out", default="phase0_report.md")
    args = ap.parse_args()

    results = {"steps": [], "base": args.base, "space": args.space}

    # 1. 健康检查
    code, body = req("GET", f"{args.base}/api/health")
    results["steps"].append({"step": "health", "code": code, "ok": code == 200,
                             "detail": body if code != 200 else "healthy"})
    print(f"[1] health: {code} {'OK' if code == 200 else body}")
    if code != 200:
        print(json.dumps(results, ensure_ascii=False, indent=2))
        sys.exit(1)

    # 2. 注册搜索引擎 (POST /api/v1/agent/deepsearch/web_search/, space_id 在 body)
    if args.search_key:
        url = f"{args.base}/api/v1/agent/deepsearch/web_search/"
        payload = {
            "space_id": args.space,
            "search_engine_name": args.search_engine,
            "search_api_key": args.search_key,
            "search_url": "",
            "is_active": True,
        }
        code, body = req("POST", url, payload)
        results["steps"].append({"step": "web_search", "code": code, "ok": code in (200, 201),
                                 "detail": body if code not in (200, 201) else "registered"})
        print(f"[2] web_search({args.search_engine}): {code} {'OK' if code in (200, 201) else body}")

    # 3. 导入模板
    if args.template_file:
        content_b64 = base64.b64encode(
            open(args.template_file, "rb").read()).decode()
        llm_config = {
            "model_name": args.llm_model or "qwen3.7-flash",
            "model_type": "openai",
            "base_url": args.llm_base,
            "api_key": args.llm_key,
        }
        payload = {
            "space_id": args.space,
            "file_name": args.template_file.split("/")[-1].split("\\")[-1],
            "file_stream": content_b64,
            "is_template": True,
            "template_name": args.template_name,
            "template_desc": "Converted from w06 report-structure.json (28 sections)",
            "llm_config": llm_config,
        }
        code, body = req("POST", f"{args.base}/api/v1/agent/deepsearch/template", payload)
        results["steps"].append({"step": "template", "code": code, "ok": code in (200, 201),
                                 "detail": body if code not in (200, 201) else "imported",
                                 "template_id": body.get("template_id") if isinstance(body, dict) else None})
        print(f"[3] template: {code} {'OK' if code in (200, 201) else body}")

    # 4. 跑报告 (POST /api/v1/agent/deepsearch/run/, space_id 在 body)
    if args.run:
        url = f"{args.base}/api/v1/agent/deepsearch/run/"
        payload = {
            "space_id": args.space,
            "message": args.query,
            "conversation_id": f"s0-{int(time.time())}",
            "report_type": "professional",
            "search_mode": "research",
            "workflow_human_in_the_loop": False,
            "llm_config": {
                "general": {
                    "model_name": args.llm_model or "qwen3.7-flash",
                    "model_type": "openai",
                    "base_url": args.llm_base,
                    "api_key": args.llm_key,
                }
            },
            "web_search_config": {"web_search_config_id": int(args.web_search_id)},
            "template_id": int(args.template_id) if args.template_id else -1,
        }
        print(f"[4] run: {args.query[:50]}... (SSE, 可能需要 5-20 分钟)")
        try:
            chunks = sse_run(url, payload, args.out + ".events")
            final = [c for a, c in chunks if "report" in str(a).lower() or a in ("end", "END")]
            report_text = final[-1] if final else (chunks[-1][1] if chunks else "")
            open(args.out, "w", encoding="utf-8").write(report_text)
            results["steps"].append({
                "step": "run", "ok": bool(report_text),
                "report_chars": len(report_text),
                "out": args.out,
            })
            print(f"[4] run: report_chars={len(report_text)} -> {args.out}")
        except Exception as e:
            results["steps"].append({"step": "run", "ok": False, "error": str(e)[:300]})
            print(f"[4] run FAILED: {e}")

    ok_all = all(s.get("ok") for s in results["steps"])
    results["overall"] = "PASS" if ok_all else "FAIL"
    print(json.dumps({"overall": results["overall"], "steps": [(s['step'], s.get('ok'), s.get('code', '')) for s in results['steps']]},
                     ensure_ascii=False, indent=1))
    open("phase0_results.json", "w", encoding="utf-8").write(
        json.dumps(results, ensure_ascii=False, indent=2))
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
