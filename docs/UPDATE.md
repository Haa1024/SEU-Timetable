# 应用内更新 · 发布与配置说明

App 侧的更新逻辑已全部落地，**唯一还需要你做的**是把仓库地址填进去、并往仓库里放两个文件。

---

## 一、原理

更新走三步，每一步都不依赖具体托管平台：

```
App 拉 update.json  →  比 versionCode  →  下载 apkUrl 并拉起系统安装器
```

关键设计是**更新清单（几十字节的 JSON）与 APK 本体解耦**。将来换托管位置，只改
`update.json` 里的 `apkUrl`，App 一行代码都不用动、也不用重新发版。

---

## 二、你需要做的两件事

### 1. 在仓库里放 `update.json`

放在仓库根目录（或 `release` 分支，见下方说明），内容：

```json
{
  "versionCode": 2,
  "versionName": "1.0.1",
  "apkUrl": "https://github.com/Haa1024/SEU-Timetable/releases/download/v1.0.1/SEU-Timetable-v1.0.1.apk",
  "notes": "修复小组件教室显示",
  "mandatory": false
}
```

> **APK 资产名请用纯 ASCII**（如 `SEU-Timetable-v1.0.1.apk`），不要用中文名。
> 中文名在 URL 里要经过百分号编码，一旦哪一环漏了编码，下载就会 404；
> 而 GitHub 页面上能看到文件、直链却下不动，这种故障最难查。

字段说明：

| 字段 | 必填 | 说明 |
|---|---|---|
| `versionCode` | ✅ | **必须严格大于** App 里的 `versionCode` 才会提示更新 |
| `versionName` | | 展示用，缺了会回落成「版本 N」 |
| `apkUrl` | ✅ | APK 直链，必须是 `http(s)://` 开头的完整地址 |
| `notes` | | 更新说明，`\n` 分段 |
| `mandatory` | | 预留字段，当前未启用强制更新 |

### 2. 把仓库地址填进代码

编辑 `app/src/main/java/com/seu/timetable/update/UpdateChecker.kt`：

```kotlin
val MANIFEST_URLS: List<String> = listOf(
    "https://raw.githubusercontent.com/Haa1024/SEU-Timetable/main/update.json",
    "https://cdn.jsdelivr.net/gh/Haa1024/SEU-Timetable@main/update.json",
)
```

两个源**依次降级**：第一个失败才试第二个。建议用 `main` 分支——如果你不希望更新
清单随代码一起改，也可以专门开一个 `release` 分支只放 `update.json`。

> 未替换时地址里含 `OWNER/REPO`，App 会识别为占位符并直接跳过，不发无谓请求。

---

## 三、发版流程

```bash
# 1. 改版本号（app/build.gradle.kts）
#    versionCode 必须 +1，否则系统认为是同版本、不会走升级
#    versionName 只是展示，可以不改

# 2. 打包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# 3. 上传到 GitHub Release（tag 与 versionName 对应，如 v1.0.1；资产名用纯 ASCII）

# 4. 更新仓库里的 update.json（versionCode / versionName / apkUrl / notes）
```

第 4 步落地后，用户下次点「检查更新」即可看到新版本。

---

## 四、三个必须知道的约束

### 1. versionCode 只能增

安装器会拒绝 versionCode 更低或相等的包。所以：

- 发版前**务必确认 `versionCode` 比上一版大**；
- 若填错导致 `update.json` 里的版本比用户本机**更低**，App 会正确判定为「无更新」
  （不会误导用户去点一个必然失败的安装）。

### 2. 签名必须一致

`keystore/seu-timetable.jks` 与 `local.properties` 里的口令**务必离线备份**：

> 丢掉 keystore = 更新链彻底断裂。新包签名不同，系统会拒绝覆盖安装，
> 用户只能卸载重装（本地课表数据一并消失）。

### 3. 首次使用需要用户手动开一次开关

Android 不允许第三方应用静默安装。首次更新时系统会要求用户到
「设置 → 应用 → 特殊应用权限 → 安装未知应用」里打开开关。

App 已处理这个流程：下载完成后先检测权限，未授权则弹说明并直接跳转到该设置页
（各 ROM 页面路径不同，代码里带了回退到应用详情页的兜底）。

---

## 五、网络可达性（实测记录）

**这一节是排查「检查更新失败」的第一手资料。**

| 域名 | 用途 | 实测结果 |
|---|---|---|
| `api.github.com` | Release API | 通（0.4s） |
| `github.com` | 仓库页 / Release 页 / RELEASE 直链入口 | 通（约 1s） |
| `raw.githubusercontent.com` | 清单首选地址 | 通（直连 4.6s；走代理曾超时） |
| `cdn.jsdelivr.net` | 清单兜底地址 | 通，响应最快 |
| `release-assets.githubusercontent.com` | **APK 实际下载域名** | **能连上、能开始下，但吞吐极低** |

### 关键实测：清单能拿到，但 APK 下不动

v1.0.0 发布后的真机通路实测（2026-09-24）：

| 测试项 | 结果 |
|---|---|
| `update.json`（jsDelivr） | ✅ HTTP 200，内容完整 |
| `update.json`（raw 直连） | ✅ HTTP 200，4.6s |
| APK 直链（走代理） | ⚠️ HTTP 200，**60s 只收到 360KB / 1.63MB** |
| APK 直链（直连） | ⚠️ HTTP 200，**90s 只收到 128KB，1450 B/s** |

两个要点：

1. **HTTP 200 不等于能下完。** 上面两次都是 200、都开始传数据了，但吞吐低到不可用。
   直连那次停在 131072 字节（正好 128KB，一个 TCP 窗口边界）——典型的连接被限速/掐断。
   排查时**必须看总耗时与实际字节数**，只看状态码会被骗过去。
2. **GitHub 已不再使用 `objects.githubusercontent.com`**，现在 Release 资产 302 到
   `release-assets.githubusercontent.com`（底层 Azure Blob
   `releaseassetproduction.blob.core.windows.net`，URL 带 SAS 签名，有效期约 1 小时）。
   若配置 DNS 白名单要放这两个。

顺带说明：`update.json` 走 jsDelivr 是**明显更优**的路径——它响应最快，且托管在 CDN 上，
不受 `release-assets` 这个瓶颈影响。清单能拿到意味着「能正确判断有没有新版本」，
但**能不能把包装下来取决于用户自己的网络**，这是客户端无法解决的。

App 侧对此的应对：清单配了**两个源依次降级**，任何一个存活即可用；
全部失败则明确提示「暂时连不上更新服务器」，**不会**误报「已是最新」。
APK 下载交给系统 `DownloadManager`——它带断点续传和重试，
比 App 自己拉更能扛住这种低吞吐链路。

---

## 六、代码结构

| 文件 | 职责 |
|---|---|
| `update/AppVersion.kt` | 版本比较纯函数 |
| `update/UpdateManifest.kt` | `UpdateInfo` 数据类 + 宽松 JSON 解析 |
| `update/UpdateChecker.kt` | 多源降级检查（**改仓库地址的地方**） |
| `update/ApkInstaller.kt` | 下载（DownloadManager）、权限检查、拉起安装器 |
| `update/UpdateUiState.kt` | 四阶段状态机 |
| `update/UpdateFlow.kt` | 全部交互与提示 |
| `res/xml/file_paths.xml` | FileProvider 路径白名单（只开 Downloads 一个目录） |

入口在「设置 → 关于 → 检查更新」。**不自动检查**：更新检查要联网，
进设置页就自动请求会在用户无预期时消耗流量。
