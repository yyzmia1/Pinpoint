# Pinpoint

Offline full-disk file and document content search engine for Android, featuring desktop Everything-style query syntax, millisecond CJK N-gram retrieval over SQLite FTS5, and tiered privileged storage access (`Normal` / `Shizuku` / `Root`).

- **Application ID**: `com.yyzm.pinpoint` (Debug builds install side-by-side as `com.yyzm.pinpoint.debug`)
- **Platform**: Android `minSdk 26` / `targetSdk 35` / `compileSdk 35` · Kotlin 2.1 · Jetpack Compose (Material 3 Light/Dark)
- **Releases**: [GitHub Releases](https://github.com/yyzmia1/Pinpoint/releases)

---

## Architecture Overview

```
+-----------------------------------------------------------------------------+
|                               Compose UI Layer                              |
|  SearchScreen  |  ReaderPreviewSheet  |  SettingsScreen  |  ShizukuGuide    |
+--------------------------------------+--------------------------------------+
                                       | StateFlow / Intent / FileProvider
+--------------------------------------v--------------------------------------+
|                      Two-Tier Index & Query Engine                          |
|  QuerySyntaxParser -> SearchQueryAst -> SqliteSearchDatabase (FTS5 + WAL)   |
|  TwoTierIndexManager -> CjkTokenizer -> Deflater/Inflater body storage      |
|  ExtractorDispatcher -> TxtMdExtractor | DocxExtractor | PdfExtractor       |
+--------------------------------------+--------------------------------------+
                                       | PrivilegedFileProvider / Binder IPC
        +------------------------------+------------------------------+
        v                              v                              v
+---------------+            +--------------------+         +-----------------+
| Normal Mode   |            | Shizuku Mode       |         | Root Mode       |
| MANAGE_EXT_   |            | ShizukuFsService   |         | RootFsService   |
| STORAGE       |            | (Shell/UserService)|         | (libsu daemon)  |
+---------------+            +--------------------+         +-----------------+
        \                              |                              /
         +-----------------> PrivilegedTreeWalker <------------------+
                    POSIX Os.lstat + (st_dev, st_ino) dedup
                      BinaryTreeStreamCodec over PFD pipe
```

---

## Engineering Specifications

### 1. Tiered Filesystem & IPC Backends

| Access Mode | Privilege Backend | Scan Scope | Transport & Deduplication |
| :--- | :--- | :--- | :--- |
| **Normal** | `MANAGE_EXTERNAL_STORAGE` / scoped storage | Public internal & external storage | Direct traversal via `NormalFileProvider` |
| **Shizuku** | `dev.rikka.shizuku` (`ShizukuFsService` UserService, UID 2000/0) | Public storage + `/Android/data` & `/Android/obb` | `IPrivilegedFsService` AIDL + `ParcelFileDescriptor` pipe streaming via `BinaryTreeStreamCodec` |
| **Root** | `com.github.topjohnwu.libsu` (`RootFsService`, UID 0) | Full tree from `/` | `PrivilegedTreeWalker` with POSIX `Os.lstat`, non-symlink traversal, and `(st_dev, st_ino)` bind-mount deduplication |

### 2. Two-Tier Indexing & Search Pipeline

- **Tier 1 (Immediate Metadata & Path Index)**: Streams directory records (`path`, `parentPath`, `name`, `ext`, `size`, `mtime`, `isDir`) over pipe and commits in batches so filename/path queries work while background scanning completes.
- **Tier 2 (Bounded Document Content Extraction)**: Asynchronous low-concurrency worker queue extracting text from `.txt` / `.md` (UTF-8 / BOM / GB18030 detection), `.docx` (streaming `ZipInputStream` + `SAXParserFactory`), and `.pdf` (`PDFBox-Android`, safely flagging image-only scanned PDFs). Extracted body text is Zlib-compressed (`Deflater`/`Inflater`) and decompressed on demand for hit snippets.
- **Read/Write Connection Separation (`SqliteSearchDatabase`)**: Built on `androidx.sqlite:sqlite-bundled` with WAL mode enabled; separate locked read and write connections prevent background indexing transactions from blocking interactive typing queries.
- **CJK Substring & Strict Matching (`CjkTokenizer` + `QuerySyntaxParser`)**: Combines custom CJK 1-gram/2-gram FTS5 indexing with AST pushdown and strict in-memory verification for quoted phrases, wildcards, and regular expressions.

### 3. In-App Preview & File Bridging

- **QuickLook-style `ReaderPreviewSheet`**: Supports highlighted text/code/config/log viewing (UTF-8/BOM/GB18030), native `PdfRenderer` page rendering (including scanned PDFs), zoomable image preview with light/dark canvas toggle, archive entry listing (`ZIP`/`APK`), media metadata/frame inspection, directory drill-down, and hex head inspection for unknown binaries.
- **Cross-Privilege Opening (`FileOpener`)**: Normal-accessible paths open directly via `androidx.core.content.FileProvider` (`${applicationId}.fileprovider`); Shizuku/Root-only paths stage safely through internal cache bridge before handoff.

---

## Query Syntax

Implemented by recursive-descent `QuerySyntaxParser`:

| Syntax | Semantics | Example |
| :--- | :--- | :--- |
| Space (`a b`) | Implicit `AND` | `合同 2026` |
| `\|` or uppercase `OR` | Logical `OR` | `pdf \| docx` |
| `-` or `!` | Logical `NOT` | `report -draft` |
| `( ... )` | Grouping | `(invoice \| receipt) ext:pdf` |
| `"..."` | Exact phrase | `"补充协议"` |
| `*`, `?` | Strict full-string wildcard | `IMG_2026*.jpg` |
| `regex:`, `re:`, `cregex:`, `/pattern/` | Strict regex (`cregex:` case-sensitive) | `/^chapter_\d+\.md$/` |
| `name:`, `path:`, `content:`, `body:` | Field restriction | `content:违约金` |
| `ext:` | Extension filter (`;` or `,` separated) | `ext:pdf;docx` |
| `type:` | `file` / `folder` | `type:folder cache` |
| `size:` | Byte/unit range (`b`, `kb`, `mb`, `gb`) | `size:>10mb` |
| `dm:` | Modified date / relative window | `dm:today` |

---

## Repository Structure

```text
.
├── .github/workflows/ci-build-release.yml   # Manual workflow_dispatch CI & nightly pre-release
├── gradle/libs.versions.toml                # Centralized dependency catalog (AGP 8.8, Kotlin 2.1, Compose BOM 2025.02)
├── settings.gradle.kts                      # rootProject.name = "Pinpoint"
└── app/
    ├── src/main/
    │   ├── aidl/com/pocketeverything/app/ipc/IPrivilegedFsService.aidl
    │   ├── java/com/pocketeverything/app/
    │   │   ├── data/        # SettingsRepository, UpdateChecker
    │   │   ├── di/          # AppContainer
    │   │   ├── filter/      # FilterConfig, FilterEngine
    │   │   ├── fs/          # PrivilegedTreeWalker, Root/Shizuku services, BinaryTreeStreamCodec, FileOpener
    │   │   ├── indexer/     # TwoTierIndexManager, IncrementalSyncWorker, RealtimeMonitorService
    │   │   ├── parser/      # ExtractorDispatcher, TxtMdExtractor, DocxExtractor, PdfExtractor
    │   │   ├── search/      # QuerySyntaxParser, SearchQueryAst, CjkTokenizer, SqliteSearchDatabase, SnippetHighlighter
    │   │   └── ui/          # Jetpack Compose screens, ShizukuGuideScreen, ReaderPreviewSheet, Adaptive Light/Dark theme
    │   └── res/             # Adaptive monochrome launcher icons, strings, FileProvider paths
    └── src/test/java/com/pocketeverything/app/  # Unit tests, Contrast WCAG checks & 100k-entry FTS5 benchmarks
```

---

## Build, Verification & CI

### Prerequisites

- JDK 17 or JDK 21
- Android SDK Platform 35 (`compileSdk = 35`, `minSdk = 26`)
- `local.properties` at repository root with `sdk.dir=/path/to/Android/Sdk`

### Local Gradle Commands

```bash
# Run JVM unit tests (includes CJK tokenizer, parser, extractor, WCAG contrast, and 100k-row FTS5 benchmark)
./gradlew :app:testDebugUnitTest --console=plain

# Build Debug APK (applicationId: com.yyzm.pinpoint.debug, coexists with Release)
./gradlew :app:assembleDebug --console=plain

# Build R8-minified Release APK (applicationId: com.yyzm.pinpoint)
./gradlew :app:assembleRelease --console=plain
```

### CI Workflow & Release Signing

- [ci-build-release.yml](.github/workflows/ci-build-release.yml) uses `workflow_dispatch` (manual trigger) so normal pushes do not block on cloud build queues.
- Optional repository secrets for release signing in CI:
  - `RELEASE_KEYSTORE_BASE64`
  - `POCKET_KEYSTORE_PASS`
  - `POCKET_KEY_ALIAS`
  - `POCKET_KEY_PASS`

---

## Package ID Migration Note

- **`v1.2.2+`**: Uses application ID `com.yyzm.pinpoint` (repository renamed from `PocketEverything` to `Pinpoint`).
- **`<= v1.2.1`**: Used `com.pocketeverything.app`. Android treats the new package ID as a distinct app; uninstall any prior `com.pocketeverything.app` installation before upgrading to `v1.2.2+` to avoid duplicate launcher icons.
