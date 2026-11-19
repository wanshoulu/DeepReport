#!/usr/bin/env bash
# DeepReport 冒烟脚本：服务起来之后，从「导材料」走到「出报告」。
#
# 前置：w06-checker 容器已启动（宿主 18083 -> 容器 18095），/health 可达。
# 用法：DEEPREPORT_BASE=http://your-host:18083 bash examples/quickstart.sh
#
# 注意：这里用 mode=brief 做冒烟（比 long_form 快）；long_form 实测约 5 分钟。
#       脚本会轮询等待，不是秒回。
set -euo pipefail

BASE="${DEEPREPORT_BASE:-http://localhost:18083}"
PY="$(command -v python3 || command -v python || true)"
if [ -z "$PY" ]; then echo "需要 python3 或 python 来解析 JSON" >&2; exit 1; fi

echo "[1/4] health"
curl -fsS "$BASE/health"; echo

echo "[2/4] 导入一份材料"
curl -fsS -X POST "$BASE/v1/documents/import" \
  -H 'Content-Type: application/json' \
  -d '{"title":"示例材料","authority":"PRIMARY_OFFICIAL","text":"2025年，本集团实现营业收入3,374.88亿元。"}'
echo

echo "[3/4] 启动异步编制（六工位）"
RUN_JSON="$(curl -fsS -X POST "$BASE/v1/pipeline/run?async=1" \
  -H 'Content-Type: application/json' \
  -d '{"bank":"live","mode":"brief","report_type":"research","subject_hint":"示例标的"}')"
echo "$RUN_JSON"

RUN_ID="$(printf '%s' "$RUN_JSON" | "$PY" -c \
  'import json,sys; d=json.load(sys.stdin); print(d.get("run_id") or d.get("task_id") or d.get("id") or "")')"
if [ -z "$RUN_ID" ]; then echo "未取到 run_id，请检查上面的返回" >&2; exit 1; fi
echo "run_id=$RUN_ID"

echo "[4/4] 轮询状态（每 10 秒，最多 60 次）"
for i in $(seq 1 60); do
  STATUS_JSON="$(curl -fsS "$BASE/v1/pipeline/status?run_id=$RUN_ID")"
  STATE="$(printf '%s' "$STATUS_JSON" | "$PY" -c \
    'import json,sys; d=json.load(sys.stdin); print(d.get("status") or d.get("state") or "")')"
  echo "[$i] status=${STATE:-<none>}"

  # checks 出现即表示双检已跑完；顶层状态显式失败也立即退出
  if printf '%s' "$STATUS_JSON" | grep -q '"checks"'; then
    echo "$STATUS_JSON" | "$PY" -c \
      'import json,sys
d=json.load(sys.stdin)
c=d.get("checks") or {}
print("numeric :", (c.get("numeric") or {}).get("status"))
print("citation:", (c.get("citation") or {}).get("status"))
print("stages  :", len(d.get("stages") or []))'
    exit 0
  fi
  case "${STATE,,}" in
    *fail*|*error*|*done*|*complete*|*succeed*) echo "$STATUS_JSON"; exit 0 ;;
  esac
  sleep 10
done

echo "超时：60 次轮询后仍未拿到双检结论" >&2
exit 1
