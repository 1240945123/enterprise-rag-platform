#!/usr/bin/env bash
# ---------------------------------------------------------------
# 推送到 GitHub。
#
# 用法：
#   ./scripts/push-to-github.sh <你的 GitHub 用户名>
#   GITHUB_REPO=my-repo ./scripts/push-to-github.sh <用户名>
#
# 前置：
#   1. 已在 GitHub 网页端创建一个「空的」仓库（不要勾选 README / .gitignore）
#   2. 本机已具备推送凭据：SSH key 或已配置 credential helper
#
# 安全说明：本脚本不会收集也不会写入任何密钥；凭据完全由本机 git 管理。
# ---------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if [ $# -lt 1 ]; then
  echo "用法：$0 <GitHub 用户名> [仓库名]" >&2
  exit 1
fi

USER_NAME="$1"
REPO_NAME="${GITHUB_REPO:-${2:-enterprise-rag-platform}}"
REMOTE_URL="git@github.com:${USER_NAME}/${REPO_NAME}.git"

echo "用户名   : ${USER_NAME}"
echo "仓库名   : ${REPO_NAME}"
echo "远端地址 : ${REMOTE_URL}"
echo

# 安全检查：确保 .env 等敏感文件不会被提交
if git ls-files --error-unmatch .env >/dev/null 2>&1; then
  echo "错误：.env 已被 git 跟踪，请先执行 git rm --cached .env" >&2
  exit 1
fi

if [ ! -d .git ]; then
  echo "初始化本地仓库..."
  git init
fi

git add -A

# 检查是否有未提交的忽略文件命中了敏感路径
STAGED=$(git diff --cached --name-only)
if echo "${STAGED}" | grep -E '(^|/)\.env$|\.env\.local|application.*secret' >/dev/null 2>&1; then
  echo "错误：暂存区包含疑似敏感文件，已中止。" >&2
  echo "${STAGED}" >&2
  exit 1
fi

if [ -z "${STAGED}" ]; then
  echo "没有需要提交的变更。"
else
  git commit -m "feat: 企业级 AI 知识库与智能问答平台（Spring Boot 3 + Spring AI + PGvector）"
fi

git branch -M main

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "${REMOTE_URL}"
else
  git remote add origin "${REMOTE_URL}"
fi

echo "推送到 origin/main ..."
git push -u origin main
echo
echo "完成：https://github.com/${USER_NAME}/${REPO_NAME}"
