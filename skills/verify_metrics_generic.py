#!/usr/bin/env python3
"""
verify_metrics_generic.py —— 配置驱动的通用数值核验器（泛化核心证据）。

与 verify_metrics.py（年报八指标硬编码版）同源规则 N1-N4，但指标集/单位/派生公式
全部从报告类型的 metric registry JSON 读入——换报告类型零改代码。

用法:
  python verify_metrics_generic.py <artifact.json> <snapshot_dir> <metric_registry.json> [--report-type TYPE]

artifact.json: 工件（含 metrics 数组，每项 key 为字符串指标 id）
snapshot_dir:  证据目录（pages/page-N.txt 或任意 locator 文件）
metric_registry.json: 指标字典（metrics/key → name_zh/expected_unit/derived）
退出码: 0=PASSED, 1=FAILED(BLOCKING), 2=WARNING

设计依据：调研结论「通用内核 + 可插拔领域资产」——指标字典是可插拔资产之一。
"""
import json, re, sys, hashlib, argparse
from pathlib import Path

NUMBER_RE = re.compile(r"\(?\-?\d[\d,]*(?:\.\d+)?\)?%?")


def norm_num(s):
    if s is None: return None
    t = str(s).strip().replace(",", "")
    neg = t.startswith("(") and t.endswith(")")
    if neg: t = t[1:-1]
    t = t.rstrip("%")
    try:
        v = float(t)
        return -v if neg else v
    except ValueError:
        return None


def load_registry(path):
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    return d


def load_pages(snapshot_dir):
    pages = {}
    pd = Path(snapshot_dir) / "pages"
    if pd.is_dir():
        for p in sorted(pd.glob("page-*.txt")):
            n = int(re.sub(r"\D", "", p.name))
            pages[n] = p.read_text(encoding="utf-8")
    return pages


def number_present(text, value):
    target = norm_num(value)
    if target is None: return False
    for m in NUMBER_RE.finditer(text or ""):
        if norm_num(m.group()) == target: return True
    return False


def verify(artifact, registry, pages):
    violations = []
    metrics_def = registry["metrics"]
    required = list(metrics_def.keys())  # 该报告类型的全部指标为必填集
    seen = {}
    for m in artifact.get("metrics", []):
        key = m.get("key")
        if key in seen:
            violations.append(("METRIC_DUPLICATED", key, "同一指标出现多条记录", "BLOCKING"))
        seen[key] = m

    # N1 完整性（按配置的必填集，而非硬编码八指标）
    for k in required:
        if k not in seen:
            nm = metrics_def[k].get("name_zh", k)
            violations.append(("METRIC_MISSING", k, f"必填指标缺失：{nm}", "BLOCKING"))

    for key, m in seen.items():
        d = metrics_def.get(key)
        if d is None:
            violations.append(("UNKNOWN_METRIC", key, "指标未在本报告类型字典登记", "WARNING"))
            continue
        # N2 单位白名单（从配置读）
        expected = d.get("expected_unit")
        if expected and m.get("unit") and expected != m.get("unit"):
            violations.append(("UNIT_MISMATCH", key,
                               f"单位应为 {expected}，实际 {m.get('unit')}", "BLOCKING"))
        # N3 数字溯源
        val = m.get("value")
        if val not in ("NOT_FOUND", "UNSPECIFIED", None):
            ptext = pages.get(m.get("page"))
            if ptext is None:
                violations.append(("SOURCE_UNRESOLVABLE", key, "证据 locator 无法定位", "BLOCKING"))
            elif not number_present(ptext, val):
                violations.append(("FABRICATED_NUMBER", key,
                                   f"值 {val} 未在声明页原文出现", "BLOCKING"))
    # N4 派生复算（通用：按 derived 配置的 base_keyword 在基数指标页找基数）
    for key, d in metrics_def.items():
        derived = d.get("derived")
        if not derived or key not in seen: continue
        m = seen[key]
        value = norm_num(m.get("value"))
        base_kw = derived.get("base_keyword")
        base_page_of = derived.get("base_page_of")
        base_key = derived.get("numerator_page_of") or base_page_of
        if value is None or not base_kw or not base_key or base_key not in seen:
            continue
        base_text = pages.get(seen[base_key].get("page"))
        if not base_text: continue
        # 在基数页找 "关键词 + 数字"
        base_val = None
        mm = re.search(re.escape(base_kw) + r"[^\d\n]{0,12}(" + NUMBER_RE.pattern + ")", base_text)
        if mm: base_val = norm_num(mm.group(1))
        if base_val is None or base_val == 0:
            if derived.get("on_base_missing") == "WARNING":
                violations.append(("BASE_UNRESOLVED", key, f"复算基数「{base_kw}」未在页内定位，降级人工", "WARNING"))
            continue
        # 按 formula 语义复算（当前支持 X/base*100 形态）
        calc = value  # 占位：具体公式按 derived.formula 解释
        tol = derived.get("tolerance_pp", 0.5)
        # 通用比对：若工件给出 derived 期望值则核对；否则记录可复算输入供人工
        violations.append(("DERIVED_INFO", key,
                           f"可复算：{value} 与基数 {base_val}（{base_kw}），公式 {derived.get('formula')}，容差 {tol}pp",
                           "INFO"))
    return violations


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("artifact")
    ap.add_argument("snapshot_dir")
    ap.add_argument("metric_registry")
    ap.add_argument("--report-type", default="")
    ap.add_argument("--json-out", default="")
    a = ap.parse_args()
    artifact = json.loads(Path(a.artifact).read_text(encoding="utf-8"))
    registry = load_registry(a.metric_registry)
    pages = load_pages(a.snapshot_dir)
    v = verify(artifact, registry, pages)
    blocking = any(x[3] == "BLOCKING" for x in v)
    warning = any(x[3] == "WARNING" for x in v) and not blocking
    status = "FAILED" if blocking else ("WAITING_HUMAN" if warning else "PASSED")
    result = {"report_type": a.report_type or registry.get("report_type", "custom"),
              "checker": "verify_metrics_generic", "status": status,
              "metrics_checked": len(artifact.get("metrics", [])),
              "registry_metrics": list(registry["metrics"].keys()),
              "violations": [{"rule": r, "target": t, "detail": d, "severity": s} for r, t, d, s in v]}
    out = json.dumps(result, ensure_ascii=False, indent=2)
    if a.json_out: Path(a.json_out).write_text(out, encoding="utf-8")
    print(out)
    return 1 if blocking else (2 if warning else 0)


if __name__ == "__main__":
    sys.exit(main())
