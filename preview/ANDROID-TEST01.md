# test01 安卓测试版

网页验收后，在原 Compose 应用上添加 AI 基础聊天。本轮没有课表操作工具，聊天模块不读取课表或校园账号。

- 桌面显示名称：`test01`。
- 安装包名：`com.seu.timetable.test01`，与原版 `com.seu.timetable` 并存；各自使用独立应用数据。
- 文件：`releases/test01.apk`。
- 内部构建类型：`trial`（Android Gradle Plugin 保留了以 `test` 开头的构建类型名）。正式版名称、包名和签名配置保持原样。
- 测试包用 Android 调试签名，可安装和后续覆盖同签名的 test01。原版不会被覆盖。

## 手机试用

1. 安装 test01。因为与原版隔离，首次打开不会读取原版课表；可通过原有「导入 / 新建课表」建立一张空白课表进行测试，无需教务登录。
2. 在「我的 → AI 模型设置」输入 API Key。默认 `https://api.deepseek.com` / `deepseek-flash`。先测试连接，再保存。
3. 在「课表」页点击 AI 气泡。拖动气泡或窗口顶栏移动；右下角拖动缩放，也有加减号和放大/还原按钮。窗口限制在可用区域内，输入时避让系统键盘。
4. 照片按钮使用系统图片选择器，最多四张；自动处理旋转方向、缩至最长边 1600 像素并转为 JPEG。原图上限 8 MB。点击发送才上传。
5. 可停止生成、复制回答、重试/重新生成、清空聊天。退出窗口不会停止生成；关闭进程后会恢复本机记录并标记未完成回复。

没有内置测试 API Key。勾选「在本机记住」后用 Android Keystore 加密保存，否则只存在当前进程中。聊天、草稿、照片和设置都放在应用私有的 `noBackupFilesDir`，不随系统云备份外传。模型仍会收到用户明确发送的对话和图片上下文。

## 构建和检查

本机命令行工具位于 `%LOCALAPPDATA%/CodexAndroid`，不需要 Android Studio 或模拟器。构建脚本将源码同步到其 `builds/seu-test01` 目录，避开 Windows/JDK 17 对中文测试类路径的兼容问题，不移动原仓库。

```powershell
.\scripts\build-test01.ps1 -RunTests
# 可选：付费真实 API 测试；只传本机文件路径，不把 Key 写进源码
.\scripts\build-test01.ps1 -RunTests -TestKeyFile '本机测试 Key 文件路径'
```

脚本调用 `assembleTrial` 和可选的 `testTrialUnitTest`；成功后复制到 `releases/test01.apk`。新 AI 单元测试覆盖请求边界、图片上下文、流式解码、错误脱敏、取消和窗口边界；`AiLiveTest` 直接使用 APK 中同一个 Kotlin/OkHttp 客户端测试真实服务。

原有代码接入点仅为 `AppRoot.kt`、`ProfilePage.kt` 和新增依赖/测试构建配置；AI 实现位于 `app/src/main/java/com/seu/timetable/ai/`。其他原有业务代码保持不变。Android 实现以原生 Compose 运行，不封装网页预览。

## 本次交付验证（2026-09-26）

- 交付副本：桌面 `课表/test01.apk`，11,290,437 字节。
- SHA-256：`274893aa4d76eb44fd38721ffc5eb2f3d4b5240f2bfda5ae3bdf88ab8e261224`。
- 已核验 APK 的桌面名称、独立包名、FileProvider / Startup authorities 和 v2 签名；源码镜像与仓库一致。
- 127 项离线测试通过；真实 API 测试另行通过连接、流式文字、图片识别和连续追问。最终仅调整图片加载 UI 后重跑离线检查，未重复付费调用；真实测试所用客户端与最终源码的 SHA-256 一致。
- 新代码无 lint Error / Fatal；原仓库 `TimetablePage.kt` 的 `SuspiciousIndentation` 仍存在，未改动该业务文件。
- 已扫描源码和 APK 内容，测试密钥未包含在其中；图片测试素材未打入 APK。
- 未连接 Android 真机，拖动、键盘避让、系统图片选择及安装体验仍需手机验收。
