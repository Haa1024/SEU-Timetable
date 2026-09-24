#!/usr/bin/env bash
# 一键发版：登录 → 建仓库 → 推送 → 发 Release → 清 CDN 缓存
#
# 用法（在 Git Bash 里执行）：
#     cd /d/SEU_Timetable && bash release-to-github.sh
#
# 前提：先在 app/build.gradle.kts 抬高 versionCode（只能增不能减），
#      ./gradlew assembleRelease，把产物复制到 releases/<ASCII 名>.apk，
#      并同步改好仓库根目录的 update.json。
#
# 脚本可重复执行：已存在的仓库/标签会跳过。
#
# ⚠️ 代理说明：本机可能有多个代理端口，且状态会变。
#    若某次报 `CONNECT tunnel failed`，先 `netstat -ano | grep <port>`
#    确认哪个在 LISTENING，再用 GIT_PROXY 指定，例如：
#        GIT_PROXY=http://127.0.0.1:7897 bash release-to-github.sh
set -e

OWNER="Haa1024"
REPO="SEU-Timetable"

# 代理：默认不指定（走系统环境变量）；需要时用环境变量覆盖
PROXY="${GIT_PROXY:-}"
if [ -n "$PROXY" ]; then
  export http_proxy="$PROXY" https_proxy="$PROXY"
  GIT_P="-c http.proxy=$PROXY -c https.proxy=$PROXY"
else
  GIT_P=""
fi

# 从 build.gradle.kts 读版本号，避免手抄错（脚本与代码同源）
VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts)
TAG="v$VERSION_NAME"
APK="releases/${REPO}-${TAG}.apk"

echo "=================================================="
echo " 准备发布 $TAG （versionCode $VERSION_CODE）"
echo "=================================================="
echo "APK  : $APK"
echo "代理 : ${PROXY:-未指定（走环境变量）}"
echo

if [ ! -f "$APK" ]; then
  echo "❌ 找不到 $APK"
  echo "   请执行 ./gradlew assembleRelease 后，把 app/build/outputs/apk/release/app-release.apk"
  echo "   复制为 $APK（资产名用纯 ASCII，中文名在 URL 里要百分号编码，漏一处就 404）"
  exit 1
fi

echo "=================================================="
echo " 1/5  登录 GitHub"
echo "=================================================="
if gh auth status >/dev/null 2>&1; then
  echo "已登录，跳过。"
else
  echo "接下来 gh 会给你一个 8 位设备码，"
  echo "请在浏览器打开 https://github.com/login/device 并输入它。"
  echo
  gh auth login --hostname github.com --git-protocol https --web
fi

echo
echo "=================================================="
echo " 2/5  创建仓库并推送代码"
echo "=================================================="
if gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
  echo "仓库已存在，只做推送。"
  git remote get-url origin >/dev/null 2>&1 || \
    git remote add origin "https://github.com/$OWNER/$REPO.git"
  # 先 fetch：仓库可能被网页编辑器改过，直接 push 会 non-fast-forward
  git $GIT_P fetch origin 2>/dev/null || true
  git $GIT_P push -u origin main
else
  gh repo create "$REPO" --public --source=. --remote=origin \
    --description "东南大学课表 · Android 客户端（Kotlin + Compose）" --push
fi

echo
echo "=================================================="
echo " 3/5  推送标签 $TAG"
echo "=================================================="
if git rev-parse "$TAG" >/dev/null 2>&1; then
  git $GIT_P push origin "$TAG" 2>/dev/null || echo "标签已在远端。"
else
  echo "❌ 本地没有标签 $TAG。先打标签："
  echo "   git tag -a $TAG -m \"$TAG\""
  exit 1
fi

echo
echo "=================================================="
echo " 4/5  发布 Release 并上传 APK"
echo "=================================================="
gh release create "$TAG" "$APK" \
  --title "$VERSION_NAME" \
  --notes "见仓库 update.json 的 notes 字段。" 2>/dev/null || \
  echo "Release 可能已存在，请到网页确认。"

echo
echo "=================================================="
echo " 5/5  清除 jsDelivr 缓存"
echo "=================================================="
# 关键步骤：jsDelivr 会缓存 @main 分支的文件，不清的话 App 仍会读到旧的 update.json，
# 表现为「明明发了新版，App 却说已是最新」。
echo "purge: /gh/$OWNER/$REPO@main/update.json"
curl -sS --max-time 60 "https://purge.jsdelivr.net/gh/$OWNER/$REPO@main/update.json" 2>&1 | head -12 || \
  echo "（purge 失败不影响发布，但 App 可能要等 CDN 自然过期才看到新版）"

echo
echo "=================================================="
echo " 完成：$TAG"
echo "=================================================="
echo "Release : https://github.com/$OWNER/$REPO/releases/tag/$TAG"
echo
echo "发布后请核对："
echo "  1. 线上 update.json 的 versionCode 是否等于 $VERSION_CODE"
echo "     curl -s https://cdn.jsdelivr.net/gh/$OWNER/$REPO@main/update.json"
echo "  2. APK 直链能否下载（注意：HTTP 200 不代表能下完，要看实际收到的字节数）"
