#!/usr/bin/env bash
# 一键：登录 GitHub → 建仓库 → 推送 → 发 v1.0.0 Release
#
# 用法（在 Git Bash 里执行）：
#     cd /d/SEU_Timetable && bash release-to-github.sh
#
# 只有第一步需要你操作：浏览器里输入一个设备码、点一下授权。
set -e

OWNER="Haa1024"
REPO="SEU-Timetable"
TAG="v1.0.0"
APK="releases/SEU-Timetable-v1.0.0.apk"

echo "=================================================="
echo " 1/4  登录 GitHub"
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
echo " 2/4  创建仓库并推送"
echo "=================================================="
if gh repo view "$OWNER/$REPO" >/dev/null 2>&1; then
  echo "仓库已存在，只做推送。"
  git remote get-url origin >/dev/null 2>&1 || \
    git remote add origin "https://github.com/$OWNER/$REPO.git"
  git push -u origin main
  git push origin "$TAG" 2>/dev/null || true
else
  gh repo create "$REPO" --public --source=. --remote=origin \
    --description "东南大学课表 · Android 客户端（Kotlin + Compose）" --push
fi

echo
echo "=================================================="
echo " 3/4  推送标签"
echo "=================================================="
git push origin "$TAG" 2>/dev/null || echo "标签已推送。"

echo
echo "=================================================="
echo " 4/4  发布 $TAG 并上传 APK"
echo "=================================================="
if [ ! -f "$APK" ]; then
  echo "找不到 $APK —— 请先执行 ./gradlew assembleRelease 并复制产物到 releases/。"
  exit 1
fi

gh release create "$TAG" "$APK" \
  --title "v1.0.0 — 首个公开版本" \
  --notes "首个公开版本。

**课表**
• 周视图网格（1–16 周）、今日时间轴、下一节课倒计时
• 课表落为本机资产：离线可看、可增删改、可同时存多张
• 教务没排的课可手动补进去，重新同步时不会被抹掉

**登录**
• 双通路：表单模式在后台自动完成整条链路；网页模式留给需手动处理的场景
• 会话失效时按已保存的账号自动续期（密码经 Android Keystore 加密落盘）

**其他**
• 桌面小组件「今日课程」（2×2 / 4×2）、上课提醒、浅色深色主题
• App 内检查更新，走 GitHub Release 升级

---
本仓库不含签名密钥库与构建产物。详见 README。" 2>/dev/null || \
  echo "Release 可能已存在，请到网页确认。"

echo
echo "=================================================="
echo " 完成"
echo "=================================================="
gh repo view "$OWNER/$REPO" --web 2>/dev/null || \
  echo "仓库地址：https://github.com/$OWNER/$REPO"
