<div align="center">

# Pinpoint

**Offline Full-Disk File & Document Content Search Engine for Android**

Desktop *Everything*-style query syntax · Sub-10 ms CJK substring retrieval over SQLite FTS5 · Tiered `Normal` / `Shizuku` / `Root` access

<p>
  <a href="https://github.com/yyzmia1/Pinpoint/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/yyzmia1/Pinpoint?style=flat-square&color=0369A1" /></a>
  <img alt="Android API" src="https://img.shields.io/badge/Android-API%2026--35-34D399?style=flat-square&logo=android" />
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?style=flat-square&logo=kotlin" />
  <img alt="Package" src="https://img.shields.io/badge/Package-com.yyzm.pinpoint-1E293B?style=flat-square" />
</p>

<p>
  <strong>English</strong> · <a href="README.md">简体中文</a>
</p>

</div>

---

## Highlights

- **Zero-Network, Everything-Grade Local Search**: Indexes filenames, paths, and extracted document bodies fully offline with millisecond response times.
- **Arbitrary Chinese Substring Precision**: Combines a custom CJK 1-gram/2-gram hybrid tokenizer (`CjkTokenizer`) with strict AST verification to guarantee 100% recall and zero false positives across single characters, cross-word fragments, phrases, wildcards (`*`, `?`), and regular expressions.
- **Tiered Privilege Backends (`Normal` / `Shizuku` / `Root`)**:
  - **Normal**: Standard storage traversal via `MANAGE_EXTERNAL_STORAGE`.
  - **Shizuku**: Non-root `UserService` IPC penetration into restricted `/Android/data` and `/Android/obb` directories, paired with an interactive 3-step setup wizard.
  - **Root**: Full-tree traversal from `/` via `libsu` `RootService`, using POSIX `Os.lstat` and `(st_dev, st_ino)` deduplication to skip symlinks, virtual filesystems, and duplicate bind mounts.
- **Two-Tier Streaming Pipeline**: Separates instant Tier-1 path/metadata indexing from bounded asynchronous Tier-2 document extraction (`.txt`, `.md`, `.docx`, `.pdf`), backed by `ParcelFileDescriptor` pipe streaming so large scans run with bounded heap memory.
- **Universal QuickLook Preview (`ReaderPreviewSheet`)**: Built-in text/code/log viewer (UTF-8/BOM/GB18030 detection), hardware `PdfRenderer` page viewer (including image-only scanned PDFs), zoomable image canvas, `ZIP`/`APK` archive inspector, media metadata viewer, directory drill-down, and hex head inspector.
- **System File Picker Integration (`ACTION_GET_CONTENT` + SAF `DocumentsProvider`)**: Exposes both a SAF `Pinpoint` root inside Android `DocumentsUI` and a dedicated `Pinpoint 选择` picker (`FilePickerActivity`) with MIME filtering, folder drill-down, multi-select, and FTS5/CJK search; restricted files bridge cleanly via `preview_bridge` while preserving filenames, toggleable in Settings.
- **Adaptive Material You (Monet) UI & Safe In-App Updater**: Supports Android 12+ wallpaper-driven Monet dynamic color alongside System / Light / Dark modes, 24h throttled silent startup update checks, strict SemVer comparison, version ignore blacklist, system `DownloadManager` APK installation with browser fallback, and an in-app multi-release **Changelog** sheet with Markdown rendering.

---

## Screenshots & Demo

![Pinpoint Motion Demo — Offline CJK Search, Shizuku Penetration & QuickLook](docs/media/pinpoint-demo.webp)



---

## Architecture

```mermaid
flowchart TB
    UI["Compose UI: SearchScreen / ReaderPreviewSheet / SettingsScreen / ShizukuGuide"]
    VM["MainViewModel & UpdateChecker"]
    IDX["TwoTierIndexManager & FilterEngine"]
    DB["SqliteSearchDatabase (FTS5 + WAL R/W Split + Zlib Body)"]
    PAR["ExtractorDispatcher: TxtMd / Docx / Pdf"]
    PROV["FileSystemProvider: Normal / Shizuku / Root"]
    WALK["PrivilegedTreeWalker + BinaryTreeStreamCodec (PFD Pipe)"]

    UI --> VM
    VM --> DB
    VM --> IDX
    IDX --> PAR
    IDX --> DB
    IDX --> PROV
    PROV --> WALK
```

### Core Engineering Design

| Component | Implementation Details |
| :--- | :--- |
| **IPC & Streaming Traversal** | `IPrivilegedFsService` AIDL + `ParcelFileDescriptor` pipe streaming via `suspend` `BinaryTreeStreamCodec.readStream`, avoiding Binder size caps and full-tree heap buffering. |
| **Mount & Loop Safety** | `PrivilegedTreeWalker` reads raw filenames via `File.list()`, checks `Os.lstat` without following symlinks, and tracks `(st_dev, st_ino)` to eliminate duplicate bind/loop mounts. |
| **Lock-Free Search UX** | `SqliteSearchDatabase` configures bundled SQLite in `WAL` mode with isolated `writeConn` / `query_only` `readConn`, ensuring background indexing never blocks typing. |
| **Directory Pruning** | `pruneDirectorySubtree` executes an indexed B-tree prefix range query (`path >= ? AND path < ?`) rather than `LIKE`, preventing wildcard collisions on `_`/`%` folder names. |
| **Body Compression** | Extracted text is compressed with Zlib (`Deflater` / `Inflater`) in `docs_raw` and decompressed on demand for contextual snippet highlighting. |
| **Cross-Boundary Handoff & Picker** | `FileOpener` serves readable paths directly via `FileProvider` / read-only descriptors and bridges restricted paths through clean `preview_bridge` subdirectories to preserve filenames; `PinpointDocumentsProvider` (SAF) and `FilePickerActivity` (`ACTION_GET_CONTENT`) expose indexed search and browsing to external apps. |

---

## Performance Snapshot

Measured in `SqliteSearchDatabaseAndBenchmarkTest` on host JVM across **100,000 indexed file entries** and **2,000 Chinese documents**:

| Query Pattern | Representative Input | Observed Latency |
| :--- | :--- | :--- |
| Single CJK Character | `税` | **1 ms** |
| Two-Char CJK Term | `合同` | **5 ms** |
| Cross-Word CJK Substring | `充协议` | **1 ms** |
| Document Body Field Query | `content:违约金` | **0 ms** |
| Boolean + Extension + Exclusion | `(采购 \| 预算) ext:docx -终版` | **23 ms** |

---

## Query Syntax

Implemented by recursive-descent `QuerySyntaxParser`:

| Syntax | Semantics | Example |
| :--- | :--- | :--- |
| Space (`a b`) | Implicit logical `AND` | `合同 2026` |
| `\|` or uppercase `OR` | Logical `OR` | `pdf \| docx` |
| `-` or `!` | Logical `NOT` | `report -draft` |
| `( ... )` | Grouping precedence | `(invoice \| receipt) ext:pdf` |
| `"..."` | Exact phrase match | `"补充协议"` |
| `*`, `?` | Strict full-string wildcard | `IMG_2026*.jpg` |
| `regex:`, `re:`, `cregex:`, `/pattern/` | Strict regex (`cregex:` / `/pattern/c` case-sensitive) | `/^chapter_\d+\.md$/` |
| `name:`, `path:`, `content:`, `body:` | Field restriction | `content:违约金` |
| `ext:` | Extension filter (`;` or `,` delimited) | `ext:pdf;docx` |
| `type:` | Entry type filter (`file` / `folder`) | `type:folder cache` |
| `size:` | Byte or unit range (`b`, `kb`, `mb`, `gb`) | `size:>10mb` |
| `dm:` | Modified date / relative window | `dm:today` / `dm:7d` |

---

## Tech Stack & Project Layout

| Category | Stack / Version (`gradle/libs.versions.toml`) |
| :--- | :--- |
| **Language & Toolchain** | Kotlin `2.1.0` · AGP `8.8.0` · JDK `17` · `compileSdk 35` / `minSdk 26` |
| **UI** | Jetpack Compose (`2025.02.00` BOM) · Material 3 · Adaptive Light/Dark Theme |
| **Storage & Search** | `androidx.sqlite:sqlite-bundled:2.6.2` (SQLite FTS5 + WAL) |
| **Background & IPC** | `WorkManager 2.10.0` · `libsu 6.0.0` · `dev.rikka.shizuku 13.1.5` · Coroutines `1.10.1` |
| **Document Parsing** | `PDFBox-Android 2.0.27.0` · Streaming `ZipInputStream` + SAX · Smart BOM/GB18030 decoder |

<details>
<summary>Repository Structure</summary>

```text
.
├── .github/workflows/
│   ├── ci.yml                               # Automatic unit tests, Lint & Debug APK on push/PR
│   └── release.yml                          # Signed Release & Debug APK publication on v* tags
├── gradle/libs.versions.toml                # Centralized version catalog
├── settings.gradle.kts                      # rootProject.name = "Pinpoint"
├── README.md                                # Simplified Chinese documentation
├── README.en.md                             # English documentation
└── app/
    ├── src/main/
    │   ├── aidl/com/pocketeverything/app/ipc/IPrivilegedFsService.aidl
    │   ├── java/com/pocketeverything/app/
    │   │   ├── data/        # SettingsRepository, UpdateChecker
    │   │   ├── di/          # AppContainer
    │   │   ├── filter/      # FilterConfig, FilterEngine
    │   │   ├── fs/          # PrivilegedTreeWalker, Root/Shizuku services, BinaryTreeStreamCodec, FileOpener
    │   │   ├── indexer/     # TwoTierIndexManager, IncrementalSyncWorker, RealtimeMonitorService, BootReceiver
    │   │   ├── parser/      # ExtractorDispatcher, TxtMdExtractor, DocxExtractor, PdfExtractor
    │   │   ├── picker/      # PinpointDocumentsProvider (SAF), MimeFilterHelper
    │   │   ├── search/      # QuerySyntaxParser, SearchQueryAst, CjkTokenizer, SqliteSearchDatabase, SnippetHighlighter
    │   │   ├── ui/          # SearchScreen, SettingsScreen, ShizukuGuideScreen, ReaderPreviewSheet, FormatUtils
    │   │   └── FilePickerActivity.kt # ACTION_GET_CONTENT picker UI (Pinpoint 选择)
    │   └── res/             # Adaptive monochrome launcher icons, strings, FileProvider paths
    └── src/test/java/com/pocketeverything/app/
        ├── BinaryTreeStreamCodecTest.kt
        ├── CjkTokenizerTest.kt
        ├── DocumentExtractorTest.kt
        ├── FilePreviewLoaderTest.kt
        ├── FilterEngineTest.kt
        ├── FormatUtilsTest.kt
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

## Build, Verification & CI

### Prerequisites

- JDK 17 or JDK 21
- Android SDK Platform 35 (`compileSdk = 35`, `minSdk = 26`)
- `local.properties` at repository root with `sdk.dir=/path/to/Android/Sdk`

### Local Commands

```bash
# Run all JVM unit tests and Android Lint checks
./gradlew :app:testDebugUnitTest :app:lintDebug --console=plain

# Assemble Debug APK (applicationId: com.yyzm.pinpoint.debug, coexists with Release)
./gradlew :app:assembleDebug --console=plain

# Assemble R8-minified Release APK (applicationId: com.yyzm.pinpoint)
./gradlew :app:assembleRelease --console=plain
```

### Dual CI/CD Workflows & Release Secrets

- **Continuous Integration ([ci.yml](.github/workflows/ci.yml))**: Automatically runs `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` on pushes/PRs to `main` (ignoring `*.md` changes) and uploads Debug APKs as workflow artifacts without touching Releases.
- **Release Pipeline ([release.yml](.github/workflows/release.yml))**: Triggers exclusively on `v*` tag pushes (or manual `workflow_dispatch`), runs full tests + Lint + R8 release build, verifies APK signatures with `aapt2` and `apksigner`, and publishes both Release & Debug APKs to GitHub Releases.
- **Required GitHub Secrets for `release.yml`**: `RELEASE_KEYSTORE_BASE64`, `POCKET_KEYSTORE_PASS`, `POCKET_KEY_ALIAS`, `POCKET_KEY_PASS`.

---

> [!IMPORTANT]
> **Package ID Migration (`v1.2.2+`)**: Releases starting from `v1.2.2` use application ID `com.yyzm.pinpoint` (repository renamed from `PocketEverything` to `Pinpoint`). Prior versions (`<= v1.2.1`) used `com.pocketeverything.app` and will not be overwritten automatically by Android—uninstall any older `com.pocketeverything.app` build before installing `v1.2.2+` to avoid duplicate launcher icons.
