#!/usr/bin/env bash
# ---------------------------------------------------------------
# 把本地主分支推送到 GitHub。
#
# 用法：
#   ./scripts/push-to-github.sh <GitHub 用户名>
#   GITHUB_REPO=my-repo ./scripts/push-to-github.sh <用户名>
#
# 前置：
#   1. 已在 GitHub 网页端创建一个「空的」仓库（不要勾选 README / .gitignore / License）
#   2. 本机已具备推送凭据：SSH key 或已配置 credential helper
#
# 设计原则：本脚本只做「安全检查 + 设置 remote + push」，
# 不执行 git add / git commit —— 以免把已经整理好的提交历史打散。
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

# ---- 安全检查 1：敏感文件绝不能被 git 跟踪 ----
for f in .env .env.local; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    echo "错误：${f} 已被 git 跟踪，请先执行 git rm --cached ${f}" >&2
    exit 1
  fi
done

# ---- 安全检查 2：被跟踪文件里不能出现敏感路径 ----
SENSITIVE="$(git ls-files | grep -E '(^|/)\.env($|\.)|secret' || true)"
if [ -n "${SENSITIVE}" ]; then
  echo "错误：被跟踪的文件里包含疑似敏感文件，已中止。" >&2
  echo "${SENSITIVE}" >&2
  exit 1
fi

# ---- 安全检查 3：工作区必须干净，避免推送残缺状态 ----
if [ -n "$(git status --porcelain)" ]; then
  echo "错误：工作区存在未提交的变更，请先提交或 stash 后再推送。" >&2
  git status --short >&2
  exit 1
fi

BRANCH="$(git branch --show-current)"
if [ -z "${BRANCH}" ]; then
  echo "错误：当前处于游离 HEAD 状态，无法推送。" >&2
  exit 1
fi

if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "${REMOTE_URL}"
else
  git remote add origin "${REMOTE_URL}"
fi

# ---- 安全检查 4：远端必须是空仓库，否则首次推送会被拒 ----
if git ls-remote --heads origin 2>/dev/null | grep -q .; then
  echo "错误：远端仓库已存在分支，不是空仓库。" >&2
  echo "请删除后在网页端重建一个空仓库（不要勾选任何初始化文件），或手动处理。" >&2
  exit 1
fi

echo "待推送提交："
git log --oneline -10
echo

echo "推送到 origin/${BRANCH} ..."
git push -u origin "${BRANCH}"
echo
echo "完成：https://github.com/${USER_NAME}/${REPO_NAME}"