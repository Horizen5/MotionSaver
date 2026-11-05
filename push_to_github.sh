#!/usr/bin/env bash
# 把本仓库推送到一个新的 GitHub 公开仓库。
#
# 安全约定：
#   - 本脚本不含任何 token / 密码，凭据只从环境变量 GITHUB_TOKEN 读取
#   - 绝不把 token 写入 .git/config、日志或任何文件（用完即 unset）
#   - 建议先在 GitHub 撤销任何曾出现在聊天/日志里的旧 PAT，再生成一个
#     最小权限（仅 repo）的新 PAT 用于本脚本
#
# 用法：
#   export GITHUB_TOKEN=<新建的 PAT>
#   ./push_to_github.sh <你的GitHub用户名> [仓库名]     # 自动创建公开仓库
#   或（仓库已在网页端建好）：
#   ./push_to_github.sh <用户名> <仓库名> --existing
#
set -euo pipefail
cd "$(dirname "$0")"

USER_NAME="${1:-}"
REPO="${2:-MotionSaver}"
MODE="${3:-create}"

if [[ -z "$USER_NAME" ]]; then
  echo "用法: GITHUB_TOKEN=xxx $0 <GitHub用户名> [仓库名] [--existing]" >&2
  exit 1
fi
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
  echo "错误: 请先 export GITHUB_TOKEN=<新建的 PAT>" >&2
  exit 1
fi

API="https://api.github.com"

if [[ "$MODE" != "--existing" ]]; then
  echo "→ 创建公开仓库 $USER_NAME/$REPO ..."
  CODE=$(curl -s -o /tmp/ms_gh_resp.json -w "%{http_code}" \
    -X POST "$API/user/repos" \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -d "{\"name\":\"$REPO\",\"description\":\"Kotlin/Compose app: Xiaomi Motion Photo + title-based media saving\",\"private\":false,\"license_template\":\"mit\"}")
  if [[ "$CODE" != "201" && "$CODE" != "422" ]]; then
    echo "创建失败 (HTTP $CODE):" >&2; cat /tmp/ms_gh_resp.json >&2; exit 1
  fi
  [[ "$CODE" == "422" ]] && echo "仓库已存在，继续推送。"
  rm -f /tmp/ms_gh_resp.json
fi

# 用带凭据的远程 URL，但只在本次命令内生效，不写入 .git/config
REMOTE="https://${USER_NAME}:${GITHUB_TOKEN}@github.com/${USER_NAME}/${REPO}.git"
echo "→ 推送 main 分支 ..."
git push -f "$REMOTE" HEAD:refs/heads/main
unset GITHUB_TOKEN REMOTE

echo "✓ 完成: https://github.com/${USER_NAME}/${REPO}"
echo "  提醒：推送完成后请到 GitHub 撤销/轮换本次使用的 PAT。"
