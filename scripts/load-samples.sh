#!/usr/bin/env bash
# ---------------------------------------------------------------
# 把 samples/ 下的示例企业文档导入指定知识库。
# 前置：服务已启动（./scripts/run-local.sh 或 docker compose up -d）
# 用法：
#   ./scripts/load-samples.sh
#   ./scripts/load-samples.sh kb-corp        # 指定知识库 ID
# ---------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost:8080}"
KB_ID="${1:-kb-corp}"

echo "目标服务 : ${BASE_URL}"
echo "知识库   : ${KB_ID}"
echo

if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
  echo "错误：服务未就绪，请先启动服务再导入数据。" >&2
  exit 1
fi

for file in samples/*.md; do
  [ -e "${file}" ] || continue
  title=$(basename "${file}" .md)
  echo -n "导入 ${title} ... "

  # 用 Python 安全地把正文转成 JSON 字符串，避免引号换行导致 JSON 破损
  body=$(python3 - "$file" "$KB_ID" "$title" <<'PY'
import json, sys
_, path, kb, title = sys.argv
with open(path, encoding='utf-8') as handle:
    print(json.dumps({
        "knowledgeBaseId": kb,
        "title": title,
        "content": handle.read(),
    }, ensure_ascii=False))
PY
)

  response=$(curl -s -X POST "${BASE_URL}/api/v1/ingest" \
    -H 'Content-Type: application/json' \
    -d "${body}")

  echo "${response}"
done

echo
echo "导入完成。可以开始提问："
echo "  curl -s -X POST ${BASE_URL}/api/v1/qa/ask \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"knowledgeBaseId\":\"${KB_ID}\",\"question\":\"一线城市住宿费标准是多少？\"}'"
