# 抖音视频下载器（Douyin Video Downloader）

一个 Android 客户端：解析抖音**分享链接 / 口令**，在线播放并下载视频，提供**下载任务管理**与**历史记录**。
解析层采用**多策略责任链**设计，任一通道失效时自动降级，并向用户展示中文友好错误（不泄露底层堆栈）。

> ⚠️ 本项目仅供个人学习、研究使用。请遵守抖音用户协议与相关版权法规，勿用于商业用途或批量爬取。

---

## 功能特性

| 分类 | 说明 |
|---|---|
| **多入口解析** | ① 剪贴板口令自动识别（App 前台时监听，识别后自动创建解析任务）<br>② 系统分享菜单（抖音 → 分享 → 本应用，`text/plain` SEND intent）<br>③ 应用内搜索页（`VideoSearchFragment`）<br>④ 历史记录一键重新解析 |
| **视频解析** | 多策略解析器责任链：短链重定向解析 `aweme_id` → 分享页 SSR JSON（`_ROUTER_DATA` / `_RENDER_DATA`）**深度搜索**视频节点 → 正则兜底提取 `playApi` / `play_addr` / `video_id` → iesdouyin / PC 详情页重试；全部失败时聚合为一条中文错误提示 |
| **播放** | 内置播放器页面（`VideoFragment` + `PlayerListener`），解析成功后直接在线播放、预览封面与作者信息 |
| **下载** | 基于 OkHttp 的 `Downloader` + `DownloadQueue` 下载队列：多任务并发、单任务/队列进度回调、取消 / 全部取消、断点文件复用（已存在文件直接标记完成） |
| **下载任务页** | 底部导航「下载」标签（`DownloadTaskFragment`）：进程级任务注册表 `DownloadTaskRegistry` 公开当前任务列表，支持取消、重试 |
| **历史记录** | 「历史」标签（`HistoryFragment`）：SQLite 持久化（`HistoryDBHelper`），新记录置顶，点击重新解析 |
| **UI** | 暗色主题；底部三标签（视频 / 下载 / 历史）；Toolbar + 全局进度条；双击返回退至后台 |
| **存储** | 视频 → 公共目录 `Download/Movies`；图片 / 头像缓存 → MD5 二级分目录；临时文件走应用缓存目录 |

---

## 使用方法

1. **口令方式**：在抖音复制分享口令 → 打开本应用 → 自动识别剪贴板内容并创建解析任务（提示"已检测到抖音链接，正在自动创建下载任务"）。
2. **分享方式**：抖音 → 分享 → 更多 → 选择本应用（分享菜单入口）。
3. **应用内方式**：在「视频」页粘贴链接 / 口令，或进入搜索页查询。
4. 解析成功后自动播放；点击下载按钮加入下载队列。
5. 切换「下载」标签查看各任务进度、取消或重试；「历史」标签查看并重新解析过往记录。

> 权限：首次启动请求存储 / 网络权限（EasyPermissions）。Android 10 及以下使用传统外部存储；更高版本受分区存储限制，下载目录可能不可写。

---

## 解析与下载架构

### 解析责任链

```
分享文本 / URL
   └─ Helpers.extractVideoUrl()  提取 douyin.com / iesdouyin.com 链接
        └─ AnalyzerTask（责任链调度，逐个 try/catch 降级）
             ├─ DouyinV6   短链重定向 → aweme_id → SSR JSON 深度搜索 → 正则兜底
             ├─ DouyinV5   分享页 / 接口重试
             ├─ DouyinV4   接口重试
             └─ AnyVideoV1 第三方通道兜底
                  └─ 全部失败 → VideoException（中文聚合错误，含最后一次失败原因）
```

- 所有解析器实现 `contract.VideoParser`；返回体先经 `ensureJson()` 防护（首字符非 `{` / `[` 时抛本地化异常），**杜绝 Jackson / OkHttp 原始异常外泄到 Toast**。
- **扩展新通道**：实现 `VideoParser` 接口，并加入 `content/analyzer/AnalyzerTask` 的链中即可，无需改动 UI 层。

### 下载链

```
VideoFragment（下载按钮）
   └─ Downloader（OkHttp 单任务：进度 / 完成 / 取消 / 错误回调）
        └─ DownloadQueue（多任务队列：进度聚合、cancel / cancelAll、文件已存在直接完成）
             └─ DownloadTaskRegistry（进程级注册表）
                  └─ DownloadTaskFragment（任务列表 UI：进度、取消、重试）
```

---

## 项目结构（主要包）

```
app/src/main/java/com/fly/video/downloader/
├── MainActivity.java               主界面：底部导航、剪贴板监听、分享 intent 分发、权限
├── bean/                           数据模型：Video、User、app/*（各通道响应模型）
├── content/
│   ├── Recv.java                   分享 intent 解析
│   ├── analyzer/                   解析责任链：AnalyzerTask + app/DouyinV3~V6、AnyVideoV1
│   └── history/                    历史读取：History、HistoryReadTask
├── contract/VideoParser.java       解析器契约（含 ensureJson 防护）
├── core/                           基础设施：JSON、HTTP 异常、存储、加密、剪贴板、系统栏
├── database/HistoryDBHelper.java   历史 SQLite
├── layout/fragment/                UI：Video / VideoSearch / History / DownloadTask Fragment 及适配器
└── util/
    ├── network/                    Downloader、DownloadQueue、DownloadTaskRegistry
    └── io/FileStorage.java         文件存储（DCIM / 缓存 / MD5 分目录）
```

---

## 构建

- **环境**：compileSdk 33 / minSdk 19 / targetSdk 33；Java 8；Android **Support Library 28**（非 AndroidX）。
- **依赖**：OkHttp 4.9、jsoup、Jackson、Glide(+okhttp3-integration)、ButterKnife、EasyPermissions、commons-lang3 / commons-codec、iconify（本地 `libs/*.aar`）。
- **命令**：

```bash
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（未开启混淆）
```

- 输出：`app/build/outputs/apk/debug/app-debug.apk`。
- 注意：`libs/` 目录下的 iconify AAR 为本地依赖，缺失时编译失败。

---

## 已知限制（如实说明）

1. **抖音风控**：当前抖音官方数据通道普遍要求 `a_bogus` / `ac_signature` 等签名（JS VM 逆向级别），**纯 HTTP 客户端无法稳定直解析**。本项目的应对是：多通道自动降级 + 中文友好报错 + 责任链易扩展；后续如需接入签名算法或自有解析服务，只需往 `AnalyzerTask` 链中加一个 `VideoParser` 实现。
2. **无水印地址**：能否合成无水印播放地址取决于解析结果中是否包含 `video_id` / `play_addr` 等字段；字段缺失时仅能提供有水印地址或报错。
3. **存储限制**：下载写入公共目录 `Download/Movies`（`requestLegacyExternalStorage`），Android 11+ 受分区存储约束可能失败。
4. **历史包袱**：minSdk 19 + Support Library 为遗留工程结构，未迁移 AndroidX。

---

## 免责声明

本项目与抖音官方无任何关联。使用者应自行确保行为符合当地法律法规与平台条款；因滥用产生的一切后果由使用者自行承担。
