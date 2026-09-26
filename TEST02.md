# test02

应用名 `test02`，包名 `com.seu.timetable.test02`，版本 `02.02`（versionCode 5）。与原版、test01 分别安装和保存数据，不会自动迁移或覆盖它们的课表与密钥。此版本沿用 test02 的包名和签名，可直接覆盖更新，无需卸载。

## 02.02 周次追问循环修复

AI 聊天展开时暂停外层课表的纵向滚动（包括已有惯性），聊天区仍可滚动，底部今日/课表/我的可正常切换；收起或返回关闭聊天后，课表在原位置恢复滚动。打开状态由主界面统一保存，切回课表时窗口与滚动锁保持一致。本轮无连接真机，触控体验仍需安装后验收。

用户在 02.01 手机上已经能打开 AI 聊天，但说“星期天 1-12节 每一周”后仍反复收到“请先说明上课周次”。使用真实 API 重现：旧提示同时介绍多种默认策略，并在添加示例用 `weeks:null`；模型用 null 表示每周，而本地 ask 校验将它判定为未提供周次。失败消息又被历史构建过滤，下一轮缺少失败反馈。

- 按当前课表策略生成唯一生效的周次说明，列出教学周范围；每周由模型展开为整数数组，缺周次仍先追问。纠正历史助手“默认全学期”的错误承诺，不能把助手承诺当成用户授权。
- 执行前检查模型添加/组合添加提案的周次格式；错误时最多让模型纠正一次，再由原执行器完整校验。此过程不会写入课表，不解析用户关键词，不通过放宽 ask 校验绕过追问。
- 将课表操作历史中的失败结果传给模型，包括 02.01 已保存的旧错误，便于在原对话继续。
- `preview/test/weeks-live.mjs` 通过真实 API 验证缺周次、截图原话、自然语言改述、限定周次、隔周以及旧失败对话恢复；`weeks.test.mjs` 验证纠正次数、取消、组合、历史反馈和无重复写入。普通聊天、删除的 null/all 语义保持不变。

验收补充：实际 APK 页面配合真实 API 已完成“课程名 → 时间 → 每一周 → 一次添加 → 撤销”，原生 `AiWeeksLiveTest` 单独重放相同请求。额外全流程图片回归发现两门周二课程误读为周三；原来的 `AiNativeLiveTest` 图片星期断言也失败，该问题没有作为已修复/通过。02.02 聚焦周次循环；图片导入仍必须人工核对清单，不能将其识别结果视为已保证准确。

## 02.01 启动修复

用户在小米 15 Pro / HyperOS 3.0.308.0 上报告 AI 聊天及设置均为空白，空课表和教务课表都能复现；test01 原生 AI 界面正常。当前没有连接该手机，因此尚未确定其 WebView 的具体报错，也不能把桌面测试作为该设备修复验收。

- 将 AI 文档加载推迟到 WebView 挂载、尺寸有效后执行；使用 APK 直接读取的 HTML 配合相同 HTTPS base URL，保留原有存储域与桥接来源。
- 页面启动后等待 `postVisualStateCallback` 首帧就绪，再移除原生加载提示。
- 放行自己的入口导航，禁止 APK 资源缓存，避免覆盖安装后继续使用旧的脚本。
- 增加独立启动脚本，捕获模块加载、语法和初始化异常；原生界面显示加载状态、15 秒超时、重试/返回、WebView 版本。脚本彻底不能运行时仍有原生提示。
- 处理渲染进程退出及加载期间返回，诊断不输出密钥或聊天内容。
- 新增 `preview/test/native-startup.py`：未配置 Key 的聊天/设置、缺失模块、语法错误、桥接异常、重试、反复切页。仍需手机覆盖安装后确认两个窗口实际显示。

实现参考：[Android 本地页面加载](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content)、[Compose 与 WebView 生命周期](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/wrap-webview-in-compose)。未采用关闭硬件加速的猜测性补丁。

## 使用

创建或导入课表后，在「我的 → AI 模型设置」填写接口与 API Key，测试连接并保存。在课表页点击 AI 气泡，可切换普通聊天和课表操作。窗口顶部短横条可拖动，右下角可缩放。设置勾选记住 Key 时使用 Android Keystore 加密，不勾选则只在当前应用进程保留；APK 不包含测试密钥。

支持自然语言添加、删除、调课、替换、组合操作、补充新课程信息和整次撤销。未给周次必须追问。相对日期使用手机当天日期，查看周单独显示。操作成功后原有 Compose 课表刷新。

普通聊天及每张课表的操作对话分别保存完整记录和摘要。默认保留最近12轮原文，较长内容提前压缩，压缩失败不丢弃原文。图片导入先识别/独立复核，存在差异再核对；完整清单展示后由用户选择追加、覆盖涉及周或覆盖整表，最终确认才写入。

## 实现与边界

- `scripts/sync-ai-assets.mjs` 将已验证的 AI 模块、请求构建器和提示词打包到 `app/src/main/assets/ai`，生成 SHA-256 清单。不会打包演示课表、电脑服务端、用户测试照片或 Key。
- AI 窗口使用系统 WebView 渲染本地 APK 资源，其他页面仍是原有 Compose。网络由 `AiNativeTransport` 的 OkHttp 发送，不依赖电脑服务器；仅允许远程 HTTPS，禁止跨站重定向转发凭据。
- WebView 禁止外部文档进入桥接环境，关闭文件访问，CSP 禁止网页自身联网/框架嵌入；上传图片使用系统文件选择器，不需要授予整个相册访问权限。
- `AiBoardCodec` 保留课程代码、教学班号、颜色及未排课扩展字段。模型输出先经共享操作引擎校验，再经 Kotlin 校验；只传课表白名单，不含校园账号。
- `TimetableLibrary.compareAndSetActiveContent` 在锁内核对当前课表、元信息和完整内容，再将课程、幂等标识、撤销记录通过单次原子替换写入。存储错误、过期快照、切换课表、取消请求均不得显示已执行。
- 原库原子写入失败时会吞异常并降级覆盖，已改为同步数据后原子替换，失败传播且保留旧文件。
- test02 关闭系统备份；完整对话与图片保存在应用私有 WebView 存储。卸载/清除数据会删除本机记录。原版和 test01 的数据独立。

## 可重复验证

```powershell
node --test preview/test/*.test.mjs
node scripts/sync-ai-assets.mjs
python preview/test/native-shell-guards.py
python preview/test/native-startup.py
node preview/test/weeks-live.mjs '本机测试密钥文件'
python preview/test/native-shell-browser.py --key-file '本机测试密钥文件' --image '用户课表照片'
./scripts/build-test02.ps1 -RunTests -Lint -TestKeyFile '本机测试密钥文件' -TestRequestsFile 'preview/.runtime/native-prepared-requests.json'
# 本次周次专项原生回归（不替代上面的图片完整回归）
./scripts/build-test02.ps1 -RunTests -Lint -TestKeyFile '本机测试密钥文件' -TestWeeksRequestsFile 'preview/.runtime/weeks-native-prepared-requests.json'
```

构建脚本使用本机便携 JDK17/SDK35/Gradle8.9，将源码镜像到 ASCII 路径以规避 Windows 中文编译路径问题。只有构建与指定测试全部成功才更新 `releases/test02.apk`；失败不会发布新包。

真实 API 浏览器回归使用实际 APK 资源，验证普通聊天压缩后记忆、缺周次追问、添加/元数据/调课/替换/删除，以及用户官方截图的9门课程12个时间段、确认导入、覆盖周、撤销。桥接故障测试覆盖写入失败、取消、过期快照、重载撤销、图片数量显示、确认取消、整表覆盖与小屏/深色显示。

原生 JVM 测试覆盖数据无损往返、非法课程时间、原子落盘与失败恢复、课表/作息切换冲突和网络传输；真实原生 OkHttp 调用复放同一 UI 生成的文字、压缩、添加及图片请求。原生执行不依赖浏览器测试中的网络代替层。

本轮未连接真机或模拟器。JVM、移动浏览器和构建验证不能替代真机安装、系统图片选择器、键盘及触控体验验收。测试证据位于 Git 忽略的 `preview/.runtime`，Android 测试和 lint 报告位于便携构建镜像的 `app/build` 下。
