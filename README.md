# 东南大学课表 · SEU Timetable

一个面向东南大学的 Android 课表应用。用 Compose 写的界面，课表数据从 ehall 网上办事大厅拉取，
拉下来之后**就是本机资产**——离线可看、可改、可另存多张。

## 下载

到 [Releases](https://github.com/Haa1024/SEU-Timetable/releases/latest) 页面下载最新的 APK 安装。

App 内也自带检查更新（「我的 → 检查更新」），装的版本会直接走 GitHub Release 升级。

> **关于下载速度**：GitHub 的 Release 资产托管在 `release-assets.githubusercontent.com`，
> 国内直连的吞吐可能很低（实测出现过 1.4 KB/s 的情况）。如果下不动，
> 用代理或换网络环境，或者直接在 Release 页面点下载（浏览器有自己的重试）。
> 注意「页面能打开」不代表「包能下下来」——这两者走的是不同域名。

> **关于安装**：系统会要求授权「安装未知应用」，这是 Android 对非商店渠道应用的强制要求，
> 任何第三方的 App 都一样。

## 它解决什么问题

学校官方的课表入口是个网页微应用：每次看课表都要走一遍统一身份认证，页面在手机上也不好按。
这个 App 把课表**取回来放到本地**，于是：

- 打开即看，不用等网页加载，也不用重新登录（登录态由 App 在失效时自动续期）；
- 一学期一张，可以同时存多张课表并自由切换；
- 教务没排的课（讲座、辅导课）可以直接补进去，不会被下次同步抹掉；
- 桌面小组件直接显示今天的课。

## 功能

**课表**

- 周视图网格，支持 1–16 周及自定义周次范围
- 「今日」页按时间轴列出当天课程，含下一节课倒计时
- 点课程查看详情：时间、地点、教师、周次
- 本地增删改课程；改过的颜色 / 学分 / 备注在重新同步时**会保留**（教务没有这几个字段，不会被覆盖）

**多课表**

- 每学期一张课表，互不干扰
- 支持从教务新建（选学期导入），也支持完全自建的空白课表
- 导入过的课表可手动「从教务同步」——不会自动同步，只在你点击时执行

**登录**

- 两条路径：**表单登录**（App 在后台自动完成整条链路，你不用碰网页）与**网页登录**（需要自己处理验证码之类时用）
- 会话失效时若已保存账号密码，自动续期
- 密码用 Android Keystore 里的密钥加密后落盘，密钥不出硬件；学号明文保存仅用于界面显示

**其他**

- 桌面小组件「今日课程」，2×2 与 4×2 两种尺寸
- 上课提醒
- 浅色 / 深色 / 跟随系统
- 应用内检查更新（走 GitHub Release）

## AI 助手（实验功能）

课表页新增可收起的 AI 窗口，提供普通聊天和自然语言课表操作两种模式。在「我的 → AI 模型设置」配置自己的 API 地址、模型和 Key 后使用；接口需兼容 Chat Completions，图片识别还需要模型支持视觉输入。

- 自然语言添加、删除、调课、替换及补充课程信息；未说明教学周时先追问，成功操作可撤销。
- 普通聊天与每张课表的操作历史分别保存，长对话自动摘要压缩。
- 课表图片先生成待核对清单，经确认后追加、覆盖涉及周或覆盖整表。**图片识别仍可能误判星期列，导入前必须核对。**
- 安卓沿用 Compose 课表，AI 界面使用 APK 内置 WebView 资源，网络请求由原生 OkHttp 发送，不依赖电脑服务。发送消息时，对话、所附图片及课表操作所需的课程数据会提交给用户配置的 API 服务。
- AI 窗口展开时暂停外层课表纵向滚动，收起后恢复；底部导航可继续切换。

可独立安装的测试版本为 `test02 / 02.02`（包名 `com.seu.timetable.test02`），不会覆盖原版。构建方式：

```bash
./gradlew assembleTrialTwo testTrialTwoUnitTest lintTrialTwo
```

AI 网页源代码在 `preview/`。修改后执行 `npm ci --prefix preview` 和 `node scripts/sync-ai-assets.mjs` 更新 APK 资源；运行网页测试使用 `npm test --prefix preview`。真实 API 测试需显式提供本机密钥文件，默认跳过，不随源码或 APK 分发密钥。浏览器预览见 [preview/README.md](preview/README.md)，原生实现、验收范围和已知问题见 [TEST02.md](TEST02.md)。

## 技术栈

| | |
|---|---|
| 语言 | Kotlin |
| UI | Jetpack Compose（Material 3） |
| 最低版本 | Android 8.0（API 26） |
| 目标版本 | API 35 |
| 网络 | OkHttp |
| 序列化 | kotlinx.serialization |
| 本地存储 | DataStore Preferences |

`minSdk` 取 26 而非更低，是因为 API 26 起自带 `java.time`，可以省掉 desugaring 这一层。

## 构建

需要 JDK 17 和 Android SDK（compileSdk 35）。

```bash
# 1. 告诉 Gradle SDK 在哪
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 跑测试
./gradlew testDebugUnitTest

# 3. 出 debug 包
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/`。

### 出 Release 包

Release 包需要签名。在 `local.properties` 里补上（该文件已被 `.gitignore` 排除）：

```properties
RELEASE_STORE_FILE=keystore/your.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

再执行 `./gradlew assembleRelease`。

> **密钥库务必另行备份。** Android 的覆盖安装强绑定签名：换了钥匙，老用户就装不上新版本，
> 只能卸载重装（本地课表会一并丢失）。

## 项目结构

```
app/src/main/java/com/seu/timetable/
├── data/        数据层：ehall 接口、CAS 登录、会话管理、本地仓库
├── domain/      领域模型：课程、课表、学期、作息时间
├── ui/          界面：页面、组件、主题、登录页
├── widget/      桌面小组件（RemoteViews 路线）
├── reminder/    上课提醒
├── update/      应用内更新（检查 → 下载 → 授权 → 安装）
└── util/        日志等基础设施
```

## 测试

```bash
./gradlew testDebugUnitTest
```

覆盖解析逻辑（用匿名的真实接口响应做夹具）、版本比对、更新源降级链、
以及小组件布局契约（布局 XML 与渲染代码的常量必须一致）。


## 说明

这不是学校官方应用，是一个自用的第三方客户端，与东南大学无隶属关系。

接口行为随时可能被校方调整，届时需要相应更新解析逻辑。
使用时请遵守学校的账号与信息系统管理规定，不要用他人账号登录。

## 许可

[MIT](LICENSE)
