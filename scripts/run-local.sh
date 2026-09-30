#!/usr/bin/env bash
# ---------------------------------------------------------------
# 本地零基础设施运行：不需要 Docker、不需要 PostgreSQL、不需要云端 Key。
# 用法：
#   cp .env.example .env    # 按需填写 LLM_API_KEY
#   ./scripts/run-local.sh
# ---------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export EMBEDDING_PROVIDER="${EMBEDDING_PROVIDER:-transformers}"

if [ -z "${JAVA_HOME:-}" ]; then
  if command -v java >/dev/null 2>&1; then
    echo "提示：JAVA_HOME 未设置，使用系统默认 java"
  else
    echo "错误：未找到 JAVA_HOME，请先安装 JDK 21 并设置环境变量。" >&2
    exit 1
  fi
fi

# 如存在自定义 Maven settings（例如本机把仓库放在非系统盘），通过 MAVEN_SETTINGS 指定
MVN_ARGS=()
if [ -n "${MAVEN_SETTINGS:-}" ]; then
  MVN_ARGS+=("-s" "${MAVEN_SETTINGS}")
fi

echo "SPRING_PROFILES    : ${SPRING_PROFILES_ACTIVE}"
echo "EMBEDDING_PROVIDER : ${EMBEDDING_PROVIDER}"
echo "服务启动后访问      : http://localhost:8080/actuator/health"
echo

exec mvn "${MVN_ARGS[@]+"${MVN_ARGS[@]}"}" spring-boot:run
