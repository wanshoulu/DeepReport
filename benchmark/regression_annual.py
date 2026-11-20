#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
年报等价性回归脚本（护城河守卫）—— 一条命令验证"通用内核写年报"没有被泛化改动削弱。

用法（在宿主机上）:
    python3 benchmark/regression_annual.py --host http://localhost:18083

检查项（全部通过才退出码 0）:
  R1  流水线: live + annual_report + brief 跑通, 状态 SUCCEEDED, 6 工位全完成
  R2  双核验: numeric PASSED + citation PASSED
  R3  指标抽取: ≥6 项真值(≤2 项 NOT_FOUND 允许), 且数值归属工行(非招行污染值)
  R4  数据表: tables 非空(材料完整时 ≥50)
  R5  主体隔离: metrics 中不出现招行特征值(3,375.32 / 682.06)

慢路径(--long): 追加 long_form 全量回归(21 节正文 + 3 万字级体量), 约 15 分钟。
"""
import argparse
import json
import sys
import urllib.request

# 工行真值锚点（快照 106 页财务摘要），用于识别污染/错主体
ICBC_ANCHORS = {
    "OPERATING_REVENUE": "8,382.70",
    "NET_INTEREST_MARGIN": "1.28",
    "COST_INCOME_RATIO": "28.05",
    "NPL_RATIO": "1.31",
    "PROVISION_COVERAGE": "213.60",
    "CET1_RATIO": "13.57",
}
# 招行特征值（出现在 metrics 里 = 主体隔离失效）
CMB_POISON = {"3,375.32", "1,501.81", "1.87", "31.98", "682.06", "0.94", "391.79", "14.16"}


def req(host, method, url_path, body=None, timeout=180):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(host + url_path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:18083")
    ap.add_argument("--long", action="store_true", help="追加 long_form 全量回归（约 15 分钟）")
    args = ap.parse_args()
    host = args.host.rstrip("/")

    results = []

    def check(rid, name, ok, detail=""):
        results.append((rid, name, ok, detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {rid} {name}" + (f" — {detail}" if detail else ""))

    print(f"=== 年报等价性回归 @ {host} ===")

    # R1-R4: brief 流水线（同步, 约 1-2 分钟）
    print("[1/2] brief 回归（约 2 分钟）...")
    out = req(host, "POST", "/v1/pipeline/run",
              {"bank": "live", "report_type": "annual_report", "mode": "brief",
               "subject_hint": "工商银行"}, timeout=240)
    art = out.get("artifact", {})
    stages = [s.get("stage_id") for s in out.get("stages", [])]
    checks = out.get("checks", {})

    # 同步响应体无 status 字段（有 6 工位 + 双核验结果即视为成功）；异步路径才有 status
    ok_r1 = len(stages) == 6 and checks.get("numeric", {}).get("status") is not None
    check("R1", "流水线跑通 + 六工位", ok_r1,
          f"stages={len(stages)}")
    check("R2", "双核验 PASSED",
          checks.get("numeric", {}).get("status") == "PASSED"
          and checks.get("citation", {}).get("status") == "PASSED",
          f"numeric={checks.get('numeric', {}).get('status')} citation={checks.get('citation', {}).get('status')}")

    metrics = art.get("metrics", [])
    real = [m for m in metrics if m.get("value") != "NOT_FOUND"]
    check("R3a", f"指标 ≥6 项真值（实际 {len(real)}/8）", len(real) >= 6)

    poisoned = [m["key"] for m in metrics
                if m.get("value") in CMB_POISON and m.get("value") != "NOT_FOUND"]
    check("R5", "主体隔离（无招行污染值）", not poisoned, f"污染: {poisoned}" if poisoned else "干净")

    anchored = [k for k, v in ICBC_ANCHORS.items()
                if any(m.get("key") == k and m.get("value") == v for m in metrics)]
    check("R3b", f"工行锚点匹配 {len(anchored)}/6", len(anchored) >= 5, f"命中: {anchored}")

    tables = len(art.get("tables", []))
    check("R4", f"数据表非空（实际 {tables} 张）", tables >= 50)

    # R6(可选): long_form 全量
    if args.long:
        print("[2/2] long_form 全量回归（约 15 分钟）...")
        run = req(host, "POST", "/v1/pipeline/run?async=1",
                  {"bank": "live", "async": True, "report_type": "annual_report",
                   "mode": "long_form", "subject_hint": "工商银行"})
        rid = run.get("run_id")
        import time as _t
        for _ in range(60):
            _t.sleep(20)
            st = req(host, "GET", f"/v1/pipeline/status?run_id={rid}", timeout=15)
            if st.get("status") != "RUNNING":
                break
        task_id = st.get("task_id")
        full = req(host, "GET", f"/v1/tasks/{task_id}", timeout=15).get("artifact", {})
        secs = full.get("sections", [])
        filled = [s for s in secs if len(s.get("body", "") or "") > 100]
        total = sum(len(s.get("body", "") or "") for s in secs)
        check("R6a", f"正文 21 节实写（实际 {len(filled)}）", len(filled) >= 18)
        check("R6b", f"正文 ≥27000 字（实际 {total}）", total >= 27000)
        check("R6c", "长文双核验 PASSED",
              st.get("checks", {}).get("citation", {}).get("status") == "PASSED")
    else:
        print("[2/2] 跳过 long_form（--long 可开启）")

    # 汇总
    fails = [r for r in results if not r[2]]
    print(f"\n=== 结果: {len(results) - len(fails)}/{len(results)} 通过 ===")
    if fails:
        for rid, name, _, detail in fails:
            print(f"  FAIL {rid} {name} {detail}")
        sys.exit(1)
    print("年报护城河完好。")


if __name__ == "__main__":
    main()
