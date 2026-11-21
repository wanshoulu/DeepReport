#!/bin/bash
# 幂等技能同步（对齐 swarm skill_installer 的 idempotent 语义）：
# checksum 比对后同步 skills/ 到外部 skill-app-agent /data/skills/，
# 变更才 docker cp（避免无谓写入触发指纹重建），变更即触发一次热重建探测。
set -e
SRC="$(cd "$(dirname "$0")/.." && pwd)/skills"
DST_CONTAINER="${1:-skill-app-agent}"
DST_DIR="${2:-/data/skills}"
changed=0
for skill_dir in "$SRC"/*/; do
  name=$(basename "$skill_dir")
  remote_sum=$(docker exec "$DST_CONTAINER" sh -c "cd $DST_DIR/$name 2>/dev/null && LC_ALL=C find . -type f | LC_ALL=C sort | xargs md5sum 2>/dev/null | md5sum" 2>/dev/null | cut -d" " -f1)
  local_sum=$(cd "$skill_dir" && LC_ALL=C find . -type f | LC_ALL=C sort | xargs md5sum 2>/dev/null | md5sum | cut -d" " -f1)
  if [ "$remote_sum" != "$local_sum" ]; then
    echo "sync: $name (changed)"
    docker exec "$DST_CONTAINER" mkdir -p "$DST_DIR/$name"
    docker cp "$skill_dir." "$DST_CONTAINER:$DST_DIR/$name/"
    docker exec "$DST_CONTAINER" chmod -R a+rX "$DST_DIR/$name"
    changed=$((changed+1))
  else
    echo "skip: $name (identical)"
  fi
done
if [ $changed -gt 0 ]; then
  echo "provoking fingerprint rebuild probe..."
  docker exec "$DST_CONTAINER" python3 -c "
import urllib.request, json
req = urllib.request.Request(\"http://127.0.0.1:8310/v1/chat\", data=json.dumps({\"message\":\"ping\",\"session_id\":\"sync-probe\"}).encode(), headers={\"Content-Type\":\"application/json\"})
try: urllib.request.urlopen(req, timeout=60).read(100)
except Exception: pass
print(\"probe sent\")
" || true
  echo "synced $changed skill(s)"
else
  echo "all skills identical, nothing to do"
fi
