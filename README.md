# PocketEverything

面向 Android 的离线极速全盘文件与文档正文检索引擎（对标桌面端 **Everything**），支持任意中文片段毫秒级命中、通配符与严格正则表达式。

## 核心特性

- **三模式全盘接入**：
  - **普通模式**：通过 `MANAGE_EXTERNAL_STORAGE` 索引内部与外置存储公开目录。
  - **Shizuku 模式**：免 Root 绑定 `UserService` 穿透 `/Android/data` 与 `/Android/obb`，内置独立交互式分步检测向导。
  - **Root 模式**：通过 `libsu` `RootService` 从 `/` 根目录全盘扫描，基于 `Os.lstat` 与 `(st_dev, st_ino)` 自动跳过虚拟文件系统并完成重复挂载去重。
- **两级异步建库**：
  - **一级库（路径与文件名库）**：极速流式遍历并立即支持搜索，后台持续补全。
  - **二级库（正文内容队列）**：低并发异步抽取 `.txt` / `.md`（UTF-8 / BOM / GB18030 自动识别）、`.docx`（流式 `ZipInputStream` + `SAXParserFactory`）、`.pdf`（`PDFBox-Android`，扫描版安全跳过）。
- **任意中文片段毫秒级命中与严格匹配**：
  - 基于 `androidx.sqlite:sqlite-bundled`（FTS5 + WAL 读写分离连接池）与自研 CJK 1-gram/2-gram 混合分词器，支持单字、双字及任意跨词片段毫秒检索，正文 Zlib 压缩按需解压并生成上下文高亮。
  - 支持严格全串锚定通配符（`*` / `?`）与严格正则表达式（`regex:`、`re:`、`cregex:`、`/pattern/`）。
- **完整 Everything 语法与内置定位预览**：
  - 支持空格 `AND`、`|` / `OR`、`-` / `!` 排除、`(...)`、`"精确短语"`、`ext:`、`path:`、`size:`、`dm:`、`type:`、`name:`、`content:`/`body:`。
  - 支持正文高亮全文定位预览、上下跳查命中处、跨权限安全唤起第三方应用打开。
- **现代界面与版本管理**：
  - 首次使用透明聚光灯引导、可配置空搜索栏显示开关、现代分类矢量图标与右对齐时间/体积排布。
  - Gradle 构建自动注入 Git Tag/Commit 版本信息，并在设置中提供开发者、GitHub 入口与 GitHub Releases 检测更新功能。

## 技术栈

- **UI**：Kotlin + Jetpack Compose + Material 3（Adaptive Light/Dark）
- **索引与检索**：`androidx.sqlite:sqlite-bundled` (SQLite FTS5 + WAL) + 自研 CJK N-gram 分词器
- **特权桥接**：`libsu` (`com.github.topjohnwu.libsu`) + `Shizuku` (`dev.rikka.shizuku`) + AIDL `ParcelFileDescriptor` 流式管道
- **文档解析**：`PDFBox-Android` + 原生流式 Zip/SAX + GB18030 编码探测

## 构建与测试

1. 准备 JDK 17/21 与 Android SDK（`compileSdk = 35`，`minSdk = 26`）。
2. 在项目根目录配置 `local.properties`（`sdk.dir=...`）。
3. 运行构建与测试：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```
