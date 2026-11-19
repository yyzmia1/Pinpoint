<div align="center">

# Pinpoint

**面向 Android 的离线极速全盘文件与文档正文检索引擎**

对标桌面端 *Everything* 查询语法 · 基于 SQLite FTS5 的任意中文片段毫秒级检索 · 普通 / Shizuku / Root 三级权限接入

<p>
  <a href="https://github.com/yyzmia1/Pinpoint/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/yyzmia1/Pinpoint?style=flat-square&color=0369A1" /></a>
  <img alt="Android API" src="https://img.shields.io/badge/Android-API%2026--35-34D399?style=flat-square&logo=android" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin" />
  <img alt="Package" src="https://img.shields.io/badge/Package-com.yyzm.pinpoint-1E293B?style=flat-square" />
</p>

<p>
  <a href="README.en.md">English</a> · <strong>简体中文</strong>
</p>

</div>

---

## 核心特性

- **完全离线 · 桌面级检索**：文件名、路径与文档正文全部本地建库检索，零网络依赖；支持桌面端 *Everything* 风格布尔运算、通配符、正则、字段限定与大小/日期筛选。
- **任意中文片段毫秒命中**：自研中文 N-gram 分词结合语法树二次校验，单字、跨词片段与精确短语 100% 精准召回、零误报。
- **普通 / Shizuku / Root 三级权限**：
  - **普通模式**：基于系统「所有文件访问权限」快速扫描内部与外置公开存储。
  - **Shizuku 模式**：免 Root 穿透 `/Android/data` 与 `/Android/obb`，内置三步引导向导。
  - **Root 模式**：从 `/` 全盘扫描，按需放行 `/data` 分区并自动跳过重复挂载与虚拟文件系统。
- **流式建库与自动增量同步**：边扫边开放搜索，每次切回前台或从系统文件选择器开启时自动增量更新；后台流式提取 `.txt` / `.md` / `.docx` / `.pdf` 正文，不限大文件体积且防 OOM。
- **空间透视与残留清理**：零重复扫盘呈现交互式矩形树图、MT 风格目录树（体积 / 文件数 / 时间 / 名称多维排序 + 占比条）、应用归属与已卸载残留榜、大文件榜；支持跨权限安全删除与一键排除出搜索库。
- **内置预览与系统选文集成**：内置文本/代码、PDF、缩放图片、压缩包与音视频预览；支持在微信、浏览器等外部应用的系统文件选择器中直接检索并选文。
- **莫奈动态取色与稳定更新通道**：支持 Android 12+ 莫奈动态取色与纯黑暗色模式；通过静态清单多源回退检查更新与浏览更新日志，告别 GitHub API 403 限流。

---

## 效果展示

![Pinpoint 功能动态演示 — 离线中文检索、QuickLook 预览与移动端 WizTree 空间透视](docs/media/pinpoint-demo.webp)

---

## 安装与使用

### 下载与环境要求

- **安装包获取**：前往 [Releases](https://github.com/yyzmia1/Pinpoint/releases) 页面下载最新正式版 `app-release.apk`（包名 `com.yyzm.pinpoint`；如需并行体验测试版，可下载 `app-debug.apk`，包名 `com.yyzm.pinpoint.debug`）。
- **系统要求**：Android 8.0（API 26）及以上，完整适配至 Android 15（API 35）。
- **旧包名卸载提示**：若设备上仍留存 `<= v1.2.1` 版本（旧包名 `com.pocketeverything.app`），安装新版前请先手动卸载旧包，避免桌面出现重复应用图标。

### 首次启动与三级权限开启

| 运行模式 | 适用范围 | 开启路径 |
| :--- | :--- | :--- |
| **普通模式** | 内部存储与外置公开目录 | 打开应用点击「授权」→ 在系统设置中开启 **所有文件访问权限**（`MANAGE_EXTERNAL_STORAGE`）→ 返回自动建库。 |
| **Shizuku 模式** | 免 Root 穿透 `/Android/data` 与 `/Android/obb` | 先启动 [Shizuku](https://shizuku.rikka.app/) 服务 → 点击首页顶栏模式胶囊切换为 **Shizuku** → 按内置三步向导一键授权并自动建库。 |
| **Root 模式** | `/` 全盘及分级 `/data` 索引 | 点击首页顶栏模式胶囊切换为 **Root** → 在 Magisk / KernelSU / APatch 中授予超级用户权限 → 按需在「设置 → 目录范围与排除」放行 `/data` 子目录。 |

### 进阶使用

- **复合查询语法**：支持桌面端 *Everything* 风格布尔运算、通配符、正则、字段限定与大小/日期筛选，详见下方 **[查询语法速查](#查询语法速查)**。
- **空间透视与清理**：点击首页顶栏饼图图标一键进入 **空间透视**（或在任意文件详情弹窗点击「在空间树中定位」），支持交互式矩形树图下钻、识别已卸载应用残留并执行物理真删或一键排除出搜索库。
- **外部应用选文**：在微信、浏览器等应用调用系统文件选择器时，展开「打开文档」左侧抽屉选择 **Pinpoint**，即可在自动增量更新后直接跨目录检索并回传文件。

---

## 系统架构

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/media/architecture-zh-dark.svg" />
  <source media="(prefers-color-scheme: light)" srcset="docs/media/architecture-zh-light.svg" />
  <img alt="Pinpoint 系统架构图" src="docs/media/architecture-zh-light.svg" width="100%" />
</picture>

### 关键工程设计

| 模块 | 实现要点 |
| :--- | :--- |
| **跨进程流式传输** | 通过 `IPrivilegedFsService` AIDL 配合 `ParcelFileDescriptor` 管道及 `suspend` `BinaryTreeStreamCodec.readStream` 流式消费批次记录，彻底规避 Binder 缓冲区上限与大目录树堆内存峰值。 |
| **挂载点与环路去重** | `PrivilegedTreeWalker` 使用 `File.list()` 减少对象分配，通过 `Os.lstat` 不跟随软链接，并维护 `(st_dev, st_ino)` 集合排除重复 bind/loop 挂载。 |
| **WAL 读写分离连接池** | `SqliteSearchDatabase` 启用 `WAL` 模式并严格隔离 `writeConn` 与 `query_only` `readConn`，后台建库与正文写入不会阻塞前台键入检索。 |
| **目录精准增量清理** | `pruneDirectorySubtree` 采用基于 `path` 唯一索引的 B 树范围查询（`path >= ? AND path < ?`）替代 `LIKE`，规避 `QQfile_recv` 等含 `_`/`%` 目录名通配误删问题。 |
| **正文压缩存储** | 正文经 Zlib（`Deflater` / `Inflater`）压缩后落库 `docs_raw`，命中时按需解压抽取高亮上下文片段。 |
| **跨权限安全唤起与系统选文** | `FileOpener` 对主存储可读路径直接使用 `FileProvider` / 只读描述符，对受限目录通过 `preview_bridge` 独立子目录桥接保留真实文件名；`PinpointDocumentsProvider`（SAF）与 `FilePickerActivity`（`ACTION_GET_CONTENT`）复用索引库安全向外部应用开放。 |

---

## 基准压测表现

基于 `SqliteSearchDatabaseAndBenchmarkTest` 在宿主机 JVM 环境对 **100,000 条文件记录 + 2,000 篇中文文档正文** 的实测延迟：

| 查询类型 | 测试样例 | 实测耗时 |
| :--- | :--- | :--- |
| 中文单字检索 | `税` | **1 ms** |
| 中文双字检索 | `合同` | **5 ms** |
| 跨词任意片段 | `充协议` | **1 ms** |
| 文档正文限定检索 | `content:违约金` | **0 ms** |
| 复合布尔与扩展名过滤 | `(采购 \| 预算) ext:docx -终版` | **23 ms** |

---

## 查询语法速查

由递归下降解析器 `QuerySyntaxParser` 提供完整支持：

| 语法 | 语义说明 | 示例 |
| :--- | :--- | :--- |
| 空格 (`a b`) | 隐式逻辑与 (`AND`) | `合同 2026` |
| `\|` 或大写 `OR` | 逻辑或 (`OR`) | `pdf \| docx` |
| `-` 或 `!` | 逻辑非（排除） | `report -draft` |
| `( ... )` | 分组优先级控制 | `(invoice \| receipt) ext:pdf` |
| `"..."` | 精确短语匹配 | `"补充协议"` |
| `*`, `?` | 严格全串通配符 | `IMG_2026*.jpg` |
| `regex:`、`re:`、`cregex:`、`/pattern/` | 严格正则（`cregex:` / `/pattern/c` 区分大小写） | `/^chapter_\d+\.md$/` |
| `name:`、`path:`、`content:`、`body:` | 限定匹配字段 | `content:违约金` |
| `ext:` | 扩展名过滤（支持 `;` 或 `,` 分隔） | `ext:pdf;docx` |
| `type:` | 类型限定（`file` / `folder`） | `type:folder cache` |
| `size:` | 文件大小区间（`b` / `kb` / `mb` / `gb`） | `size:>10mb` |
| `dm:` | 修改日期 / 相对时间窗口 | `dm:today` / `dm:7d` |

---

## 技术栈与工程结构

| 类别 | 选型与版本（见 `gradle/libs.versions.toml`） |
| :--- | :--- |
| **语言与构建** | Kotlin `2.1.0` · AGP `8.8.0` · JDK `17` · `compileSdk 35` / `minSdk 26` |
| **UI 框架** | Jetpack Compose（`2025.02.00` BOM）· Material 3 · 自适应昼夜主题 |
| **存储与检索引擎** | `androidx.sqlite:sqlite-bundled:2.6.2`（SQLite FTS5 + WAL） |
| **后台调度与特权 IPC** | `WorkManager 2.10.0` · `libsu 6.0.0` · `dev.rikka.shizuku 13.1.5` · Coroutines `1.10.1` |
| **文档与编码解析** | `PDFBox-Android 2.0.27.0` · 流式 `ZipInputStream` + SAX · 智能 BOM/GB18030 编码识别 |

<details>
<summary>展开查看源码目录结构</summary>

```text
.
├── .github/workflows/
│   ├── ci.yml                               # 日常推送/PR 自动运行单测、Lint 与 Debug 测试包构建
│   ├── release.yml                          # 推送 v* 标签时自动校验签名并发布正式 Release
│   └── sync-release-manifest.yml            # 发布 Release 后自动同步精简版 releases.json 至 release-manifest 分支
├── gradle/libs.versions.toml                # 统一依赖版本清单
├── settings.gradle.kts                      # rootProject.name = "Pinpoint"
├── README.md                                # 简体中文工程文档
├── README.en.md                             # 英文工程文档
└── app/
    ├── src/main/
    │   ├── aidl/com/pocketeverything/app/ipc/IPrivilegedFsService.aidl
    │   ├── java/com/pocketeverything/app/
    │   │   ├── analyzer/    # WizTreeEngine（SQLite 原生聚合 + Treemap）, AppStorageAttribution（应用归属与残留）
    │   │   ├── data/        # SettingsRepository（设置持久化）, UpdateChecker（静态多源版本检测）
    │   │   ├── di/          # AppContainer（依赖容器）
    │   │   ├── filter/      # FilterConfig, FilterEngine（目录过滤与白名单策略）
    │   │   ├── fs/          # PrivilegedTreeWalker, Root/Shizuku 服务, DeletionSafetyGuard, FileOpener
    │   │   ├── indexer/     # TwoTierIndexManager, IncrementalSyncWorker, RealtimeMonitorService, BootReceiver
    │   │   ├── parser/      # ExtractorDispatcher, TxtMdExtractor, DocxExtractor, PdfExtractor
    │   │   ├── picker/      # PinpointDocumentsProvider（SAF 根）, MimeFilterHelper（类型筛选）
    │   │   ├── search/      # QuerySyntaxParser, SearchQueryAst, CjkTokenizer, SqliteSearchDatabase, SnippetHighlighter
    │   │   ├── ui/          # SearchScreen, StorageAnalyzerScreen, SettingsScreen, ShizukuGuideScreen, ReaderPreviewSheet
    │   │   └── FilePickerActivity.kt # ACTION_GET_CONTENT 系统选文件界面（Pinpoint 选择）
    │   └── res/             # 自适应单色桌面图标、字符串资源、FileProvider 路径配置
    └── src/test/java/com/pocketeverything/app/
        ├── BinaryTreeStreamCodecTest.kt
        ├── CjkTokenizerTest.kt
        ├── DocumentExtractorTest.kt
        ├── FilePreviewLoaderTest.kt
        ├── FilterEngineTest.kt
        ├── FormatUtilsTest.kt
        ├── MagneticSnapSliderTest.kt
        ├── MimeFilterHelperTest.kt
        ├── PinpointPickerAndProviderTest.kt
        ├── QuerySyntaxParserTest.kt
        ├── SnippetHighlighterTest.kt
        ├── SqliteSearchDatabaseAndBenchmarkTest.kt
        ├── ThemeContrastTest.kt
        ├── TwoTierIndexManagerTest.kt
        └── UpdateCheckerTest.kt
```

</details>

---

## 构建、验证与 CI

### 环境准备

- JDK 17 或 JDK 21
- Android SDK Platform 35（`compileSdk = 35`，`minSdk = 26`）
- 在项目根目录配置 `local.properties`（`sdk.dir=/path/to/Android/Sdk`）

### 本地构建与检测命令

```bash
# 运行全部 JVM 单元测试与 Android Lint 静态检查
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain

# 编译 Debug 测试包（包名 com.yyzm.pinpoint.debug，可与正式版并存安装）
./gradlew :app:assembleDebug --console=plain

# 编译开启 R8 混淆裁剪的 Release 正式包（包名 com.yyzm.pinpoint）
./gradlew :app:assembleRelease --console=plain
```

### 双流水线 CI/CD 与发布签名配置

- **日常质量门禁（[ci.yml](.github/workflows/ci.yml)）**：在代码推送至 `main` 或发起 PR 时自动执行 `:app:testDebugUnitTest`、`:app:lintDebug` 与 `:app:assembleDebug`（跳过纯 `.md` 文档变更），Debug 测试包作为 Actions Artifact 留存，不改动正式 Release。
- **正式发布流水线（[release.yml](.github/workflows/release.yml)）**：仅在推送 `v*` 版本标签（或手动 `workflow_dispatch`）时触发，完成全量测试、Lint、R8 正式构建及 `aapt2`/`apksigner` 验签后自动创建并上传 GitHub 正式 Release。
- **正式发布所需 GitHub Secrets**：`RELEASE_KEYSTORE_BASE64`、`POCKET_KEYSTORE_PASS`、`POCKET_KEY_ALIAS`、`POCKET_KEY_PASS`。

---

> [!IMPORTANT]
> **包名迁移提示（`v1.2.2+`）**：自 `v1.2.2` 起正式包名变更为 `com.yyzm.pinpoint`（仓库由 `PocketEverything` 更名为 `Pinpoint`）。更早版本（`<= v1.2.1`）使用 `com.pocketeverything.app`，Android 会将两者视为独立应用；安装 `v1.2.2+` 前请先手动卸载旧包 `com.pocketeverything.app`，以免桌面出现重复图标。
