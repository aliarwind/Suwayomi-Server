# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repo Layout

This is a **dual-repo project** — two repositories sit side by side:

```
D:\MyProject\ai-my-todo\
├── Suwayomi-Server/       # Kotlin backend (this repo)
└── Suwayomi-WebUI/        # React/TypeScript frontend (forked from Suwayomi/Suwayomi-WebUI)
```

The Server is forked from [Suwayomi/Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server). The WebUI is forked from [Suwayomi/Suwayomi-WebUI](https://github.com/Suwayomi-WebUI), remote `origin` set to `git@github.com:aliarwind/Suwayomi-WebUI.git`.

## Build

One command builds both frontend and backend:

```bash
cd Suwayomi-Server && bash build-release.sh
```

### What it does

1. **build_webui()** — Always runs. Builds WebUI from `../Suwayomi-WebUI/` local repo:
   - Auto-detects new/changed `.tsx` files (newer than `en.po`) → runs `npx lingui extract --clean` to update all locale PO files
   - Runs `pnpm run build` (Vite production build)
   - Packages `build/` into `server/src/main/resources/WebUI.zip`（构建时写入 `revision` 文件内容 `r99999`）
2. **build_jar()** — Runs Gradle `:server:shadowJar` (fat JAR, ~180MB)
3. **assemble** — Copies JAR + Zulu JRE 25.0.3 + Electron v37.2.5 + `Suwayomi-Launcher.jar` + 启动脚本 → `dist/Suwayomi-Server-vX.X.X-windows-x64/`
4. **package** — Zips the directory

Output: `dist/Suwayomi-Server-v2.4.2495-windows-x64.zip`（370,303,982 B ≈ 353 MiB）。

版本号来自 `getTachideskVersion() = "v2.4.<git commit count>"`，所以每次提交后版本号都会变化；`dist/` 会同时保留历史版本的 zip 和目录。

### Build flags

| Flag | Effect |
|------|--------|
| `--skip-gradle` | 跳过 Gradle 编译，复用 `server/build/*.jar` 重新组装（WebUI 仍会重建；找不到 jar 时自动回退到完整编译） |
| `--clean` | Clear `build-tools/` cache and `dist/` before rebuilding |

### Prerequisites

- **JDK 21** (Zulu JDK 21.0.6 is bundled at `build-tools/zulu21.40.17-ca-jdk21.0.6-win_x64/`; script auto-detects)
- **pnpm** (for WebUI build)
- Git Bash (Windows)
- **TLS caveat**: If Gradle fails with SSL errors, copy JDK 15's `cacerts` over JDK 21's at `C:\Program Files\Java\jdk-15.0.1\lib\security\cacerts` → `<jdk21>/lib/security/cacerts`

### WebUI version management

`webUIRevisionTag` in `buildSrc/src/main/kotlin/Constants.kt` is set to `"r99999"`. This ensures the server always extracts the bundled (locally-built) WebUI rather than downloading a release. When deploying, delete the server's cached WebUI:
```
rm -rf ~/AppData/Local/Suwayomi/webUI
```

## 本地运行环境（开发机）

### 数据库

开发机实际跑的是 Docker 里的 PostgreSQL 17（H2 只是上游默认值）：

- 容器：`suwayomi-postgres`（镜像 `postgres:17`），端口 5432
- 库：`suwayomi_db`，schema：`suwayomi`，用户/密码：`postgres` / `postgres`
- 配置文件：`%LOCALAPPDATA%\Tachidesk\server.conf`（首次启动由启动器生成/合并）

查看迁移状态（注意 PG 下表名是小写未加引号的）：

```bash
docker exec -i suwayomi-postgres psql -U postgres -d suwayomi_db \
  -c "SELECT * FROM suwayomi.migrations ORDER BY version;"
```

在 PowerShell 里不要写成 `suwayomi."Migrations"`——未加引号的小写名才匹配，否则会报 `unterminated quoted identifier`。

### 数据库备份 / 恢复（跑迁移或升级前必做）

备份放在仓库外的 `../db-backups/`：

```bash
# custom 格式（推荐，可选择性恢复）
docker exec suwayomi-postgres pg_dump -U postgres -d suwayomi_db -Fc \
  > ../db-backups/suwayomi_db_$(date +%F).dump

# 纯 SQL（可读、可 grep）
docker exec suwayomi-postgres pg_dump -U postgres -d suwayomi_db \
  | gzip > ../db-backups/suwayomi_db_$(date +%F).sql.gz

# 校验：TOC 应包含全部 TABLE DATA；gzip 应无损坏
pg_restore -l ../db-backups/xxx.dump | head
gzip -t ../db-backups/xxx.sql.gz

# 恢复
docker exec -i suwayomi-postgres pg_restore -U postgres -d suwayomi_db \
  --clean --if-exists < ../db-backups/xxx.dump
```

### 停止正在运行的服务

```bash
netstat -ano | findstr :4567      # 找到 PID
taskkill /PID <pid> /F
# 或直接运行 dist 目录里的 killtask4567.bat
```

## 上游合并与本地差异（2026-11）

- 合并点：`develop` 分支上的 commit `074019a34`（Merge upstream/Suwayomi-Server master）
- 上游 2.4.x 的关键变化：
  - 多用户支持：`MangaUserTable`（inLibrary / inLibraryAt）与 `ChapterUserTable`（章节已读等按用户存储），M0066 负责建表+回填+删除旧列
  - 删除了大量旧配置项：`autoDownload*`、`exclude*`、`opds*`、`syncYomi*`、`koreaderSync*` —— 旧版启动器读这些 key 会直接崩溃，必须用 release 235+
- 本地新增的迁移：`M0069_AddPerformanceIndexes`、`M0070_AddForeignKeyIndexes`（见下方迁移章节）
- 合并后每次都要检查：① 迁移编号连续（M0001..MN 无缺号）；② `server-reference.conf` 的 key 与启动器版本匹配；③ WebUI 仍从 `../Suwayomi-WebUI` 本地构建，`webUIRevisionTag` 保持 `r99999`

## Tech Stack

### Backend (Suwayomi-Server)
- **Language**: Kotlin 2.4.21, targets JVM 21
- **HTTP**: Javalin 7.2.4 (port 4567)
- **API**: GraphQL at `/api/graphql` (primary); REST `/api/v1` (deprecated)
- **ORM**: Exposed v1.4.0 (Kotlin DSL for SQL)
- **DB**: H2 (default, file-based) or PostgreSQL（本项目实际使用 PG）
- **Build**: Gradle 9.8.2 + ShadowJar
- **Migrations**: Numbered classes in `server/.../database/migration/` (M0001 through M0070), auto-run on startup
- **Extensions**: Loads Mihon APK extensions via AndroidCompat compatibility layer

### Frontend (Suwayomi-WebUI)
- **Language**: TypeScript 6 + React 19
- **UI**: MUI 9.3.1 + Emotion CSS-in-JS (`sx` prop)
- **Build**: Vite 8.0.14
- **Package manager**: pnpm 11
- **State/Data**: Apollo Client 4.2 (GraphQL cache + network)
- **i18n**: Lingui v6 with `@lingui/react/macro` (compile-time `t` macro via SWC plugin)
- **Routing**: react-router-dom
- **Virtual scroll**: react-virtuoso (infinite-scroll mode only)
- **Notifications**: notistack (snackbar/toast)

## Architecture

### Backend key paths

```
server/src/main/kotlin/suwayomi/tachidesk/
├── Main.kt                                    # Entry point
├── server/ServerConfig.kt                     # All server configuration
├── server/JavalinSetup.kt                     # HTTP server + route setup
├── graphql/
│   ├── TachideskGraphQLServer.kt              # GraphQL engine init
│   ├── queries/MangaQuery.kt                  # manga query (pagination: first/offset)
│   ├── mutations/MangaMutation.kt             # manga mutations (including download cleanup)
│   ├── types/                                 # GraphQL type mappings
│   └── filter/Filter.kt                       # StringFilter, IntFilter, etc.
├── manga/
│   ├── impl/Library.kt                        # Library business logic
│   ├── model/table/                           # Exposed ORM table definitions
│   │   ├── MangaTable.kt                      # Manga table
│   │   ├── MangaUserTable.kt                  # Per-user manga state (inLibrary, inLibraryAt)
│   │   ├── ChapterTable.kt                    # Chapter table (indexes on manga, isDownloaded)
│   │   └── CategoryMangaTable.kt              # Join table (indexes on category, manga)
│   └── model/dataclass/                       # Data classes
└── server/database/migration/
    ├── M0069_AddPerformanceIndexes.kt         # Performance indexes for 20k+ manga libraries
    └── M0070_AddForeignKeyIndexes.kt          # Upstream M0061 FK indexes (skipped DBs only)
```

### Frontend key paths

```
src/
├── features/
│   ├── library/
│   │   ├── screens/
│   │   │   ├── Library.tsx                    # Wrapper: branches to infinite-scroll or paginated view
│   │   │   ├── LibraryInfiniteScroll          # (inline in Library.tsx) Original Virtuoso-based view
│   │   │   ├── PaginatedLibraryView.tsx       # Separate paginated view (no Virtuoso)
│   │   │   └── LibrarySettings.tsx            # Settings page with pagination options
│   │   ├── components/
│   │   │   ├── PaginationControls.tsx         # Page nav + jump-to-page + per-page size selector
│   │   │   ├── LibraryMangaGrid.tsx           # Virtuoso grid (infinite-scroll mode only)
│   │   │   └── LibraryOptionsPanel.tsx        # Display tab with pagination toggle + page size
│   │   ├── hooks/useGetVisibleLibraryMangas.ts # Client-side filter + sort
│   │   └── Library.types.ts                   # MetadataLibrarySettings (paginationEnabled, paginationSize)
│   └── manga/components/
│       ├── MangaDownloadButton.tsx            # Hover download button on manga covers
│       └── cards/
│           ├── MangaGridCard.tsx              # Grid card with download button + hover state
│           └── MangaListCard.tsx              # List card with download button + hover state
├── lib/
│   ├── requests/RequestManager.ts             # Apollo client wrapper. useGetCategoryMangas() handles
│   │                                          # category 0 specially — uses GET_MANGAS_LIBRARY for pagination
│   └── graphql/
│       ├── manga/MangaQuery.ts                # GET_MANGAS_LIBRARY (supports first/offset/filter)
│       └── category/CategoryQuery.ts          # GET_CATEGORY_MANGAS (no pagination — legacy)
└── i18n/locales/                              # 34 locale PO files (zh-Hans.po is the primary Chinese translation)
```

### Library pagination architecture

The library has **two completely separate rendering paths**, selected at the component level to keep React hooks stable:

```
Library (wrapper, reads paginationEnabled from settings)
├── settingsLoading → LoadingPlaceholder
├── paginationEnabled=true  → PaginatedLibraryView
│   ├── Pure MUI Grid (responsive: xs:6 sm:4 md:3 lg:2)
│   ├── Server-side pagination: first/offset via GET_MANGAS_LIBRARY
│   ├── Server-side search: filter { or: [title, author, artist, description, genre] }
│   ├── Server-side filters: status, source, chapter stats (affect totalCount/pages)
│   ├── Client-side filters for the rest (tracker, "started") on the current page
│   ├── Entries open in a new tab (target="_blank")
│   ├── PaginationControls (always visible, with jump-to-page input)
│   └── fetchPolicy: 'network-only' (no stale cache issues)
│
└── paginationEnabled=false → LibraryInfiniteScroll
    ├── VirtuosoGridPersisted with useWindowScroll
    ├── Fetches ALL manga (no first/offset)
    └── Client-side filtering + sorting via useGetVisibleLibraryMangas
```

The wrapper pattern avoids React hooks conditional-call errors (Error #185).

### Paginated library filters

- Filters expressible in the GraphQL `MangaFilter` are sent server-side so they apply to the whole category (and therefore change `totalCount`/page count), not just the fetched page: search, `hasStatus`, `hasSource`, `hasDownloadedChapters`, `hasUnreadChapters`, `hasBookmarkedChapters`, `hasDuplicateChapters`.
- `hasSource` keeps the tri-state semantics of the infinite-scroll view: included sources → `sourceId: { in: [...] }`, excluded sources → `sourceId: { notIn: [...] }` (both may be combined). `getVisibleSourceFilters()` is shared with `useGetVisibleLibraryMangas` and drops filters for sources no longer present in the library.
- Tracker and "started" (`hasReadChapters`) filters have no `MangaFilter` counterpart yet and are still applied client-side, so they only filter the current page.
- If a server-side filter is active and the page is empty, the empty state shows "No manga matches this filter" instead of "The category is empty".

### Library entries open in a new tab

`MangaCard` has an `openInNewTab` prop which sets `target="_blank" rel="noreferrer"` on the card link (grid and list layouts). Both `LibraryMangaGrid` (infinite scroll) and `PaginatedLibraryView` enable it, so opening a manga keeps the library tab (and its page/scroll position) intact. Selection/long-press handling is unaffected.

### Category 0 (Default "All") handling

Category ID 0 is special — manga are NOT mapped to it in the database (`CategoryMangaTable` has no entries for category 0). The `useGetCategoryMangas()` method handles this:

- **Before fix**: Used `GET_CATEGORY_MANGAS` query which doesn't support `first`/`offset`, causing ALL 20k+ manga to load even in paginated mode
- **After fix**: Uses `GET_MANGAS_LIBRARY` with `condition: { inLibrary: true }` (no `categoryIds` filter) so `first`/`offset` work correctly

```typescript
// RequestManager.ts useGetCategoryMangas()
if (id === 0) {
    return this.useGetMangas(GET_MANGAS_LIBRARY, {
        condition: { inLibrary: true },  // all library manga, with pagination support
        first, offset, filter,
    });
}
// For real categories: condition: { inLibrary: true, categoryIds: [id] }
```

### Server-side MangaQuery pagination flow

In `MangaQuery.kt`:
1. Build subquery: select manga IDs with conditions (LEFT JOIN CategoryManga only if `categoryIds` filter present)
2. `applyOps(condition, filter)` → WHERE clauses
3. `MangaTable.selectAll().where { id inSubQuery mangaIdsQuery }` → main query
4. Apply ORDER BY
5. **`res.count()`** → totalCount (before LIMIT, uses `inLibrary` index from M0069)
6. Apply `limit(first).offset(offset)`
7. Return results + totalCount + pageInfo

## Custom Features (vs upstream)

| Feature | Server | WebUI |
|---------|--------|-------|
| **Download button on manga covers** | — | `MangaDownloadButton.tsx` + hover state on cards |
| **Auto-add-to-library on download** | — | Button triggers `updateManga(inLibrary: true)` before download |
| **Cleanup downloads on library removal** | `MangaMutation.kt`: deletes chapters + dequeues downloads when `inLibrary` set to false | — |
| **Paginated library mode** | — | `PaginatedLibraryView.tsx` + `PaginationControls.tsx` |
| **Jump-to-page** | — | Editable page number input in PaginationControls |
| **Server-side search for paginated view** | — | Multi-field OR filter (title/author/artist/description/genre) |
| **Server-side source filter for paginated view** | — | `PaginatedLibraryView.tsx` maps `hasSource` to `sourceId { in/notIn }`; shared prune logic in `getVisibleSourceFilters()` |
| **Library entries open in new tab** | — | `openInNewTab` prop on `MangaCard` (`target="_blank"`), enabled by `LibraryMangaGrid` + `PaginatedLibraryView` |
| **Performance indexes (M0069 + M0070)** | 12 个索引：inLibrary(MangaUser)、category、manga、chapter、isDownloaded，以及上游 M0061 的 7 个外键索引（M0070 补偿） | — |
| **Category 0 pagination fix** | — | `RequestManager.ts` useGetCategoryMangas() |
| **i18n for custom features** | — | Chinese translations in `zh-Hans.po` |

## i18n

- Uses **Lingui v6** with compile-time `t` macro (`@lingui/react/macro`)
- All user-visible strings in custom components must use `` t`Chinese text` `` syntax
- After adding new `t` calls, run `npx lingui extract --clean` (the build script auto-detects and does this)
- Primary Chinese locale: `src/i18n/locales/zh-Hans.po`
- Other 33 locales auto-extracted but need manual translation
- **Critical**: The `t` macro is a compile-time transformation — never use `t` outside a component/hook, never use template expressions inside `t` (e.g., `` t`Page ${n}` `` won't work; use `plural` or pass as separate elements)

## Database Migrations

Migrations are in `server/.../database/migration/`, numbered M0001–M0070. They extend `SQLMigration` and run sequentially on startup.

### 迁移框架的两个坑（必读）

1. **只比较版本号，不比较类名。** `de.neonew.exposed.migrations` 用类名解析出版本号（`^M(\d+)_?(.*)$`），执行判断是 `migration.version > currentVersion`，记录表里存了 name 但从不比对。所以如果数据库里某个版本号已被本地迁移占用，上游同名/同号的迁移会被永久跳过。2026-11 合并时就是这样：本地旧迁移 `M0061_AddPerformanceIndexesV2` 占了 61 号，上游 `M0061_CreateForeignKeyIndexes` 的 10 个索引从未执行。补偿方式是新增 `M0070_AddForeignKeyIndexes`（带 `IF NOT EXISTS`，在真跑过 M0061 的库上是 no-op）。**以后新增迁移只能追加在末尾，不要复用/改号旧版本。**
2. **版本号必须连续。** `checkVersions()` 要求加载到的迁移版本是 1..N 连续，缺号会导致启动失败。

另外 `DBManager.kt` 对 M0059 有一段历史修正：把记录里旧名字 `AddPerformanceIndexes` 改写为 `AddJarUrlToExtensionTable`，并手动执行 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS jar_url`。改迁移相关代码时不要删掉它。

To add a new migration:
```kotlin
class M0071_YourMigration : SQLMigration() {
    override val sql: String
        get() {
            val tableName = "TableName".toSqlName()
            val colName = "column".toSqlName()
            return """
                ALTER TABLE $tableName ADD COLUMN $colName ...;
            """.trimIndent()
        }
}
```

Use `toSqlName()` on table/column names (handles H2 vs PostgreSQL casing differences). Table names match the Exposed object name (e.g., `"CategoryManga"` not `"CATEGORY_MANGA"`).

## Common Issues

### WebUI changes not visible after deploy
Delete the server's WebUI cache and restart:
```bash
rm -rf ~/AppData/Local/Suwayomi/webUI
```
The server extracts `WebUI.zip` on startup. If the bundled revision matches the cached revision, no re-extraction happens. `webUIRevisionTag = "r99999"` ensures the bundled version is always treated as newer.

### i18n showing garbled characters instead of Chinese
The `t` macro compiles to message IDs at build time. If PO files don't have the message, the raw ID is displayed. Run `npx lingui extract --clean` to update all locale PO files after adding new strings.

### Gradle OOM
```bash
export GRADLE_HEAP=5120m
./gradlew :server:shadowJar -Dorg.gradle.jvmargs="-Xmx5120m" --no-daemon
```

### 启动器报 `Invalid path 'autoDownloadNewChapters'`
`build-tools/Suwayomi-Launcher.jar` 是旧版，仍在读上游已删除的配置项（autoDownload\*、exclude\*、opds\* 等）。`download_if_missing()` 只要文件存在就跳过下载，所以会一直复用旧缓存。修复：

```bash
rm -f build-tools/Suwayomi-Launcher.jar
bash build-release.sh          # 重新拉取 latest release 并组装
```

已验证可用：release 235（`Suwayomi-Launcher.jar` = 16,893,862 B，2026-10-08）。

### 发布包校验（打包后必做）
```bash
cd dist
unzip -l Suwayomi-Server-vX-windows-x64.zip \
  | grep -E "bin/Suwayomi-Server.jar|Suwayomi-Launcher.jar|Suwayomi Launcher.bat"

# jar 校验：本地 jar 与 zip 内 jar 的 SHA256 必须一致
sha256sum Suwayomi-Server-vX-windows-x64/bin/Suwayomi-Server.jar
unzip -p Suwayomi-Server-vX-windows-x64.zip \
  Suwayomi-Server-vX-windows-x64/bin/Suwayomi-Server.jar | sha256sum
```

参考值（v2.4.2495）：`bin/Suwayomi-Server.jar` = 188,902,029 B，`Suwayomi-Launcher.jar` = 16,893,862 B，zip = 370,303,982 B。

> 打包时会先删旧 zip 再压缩（约 370MB，耗时 1 分钟左右）。中途 `ls` 看到 zip 不存在是正常的，等打包结束；不要同时跑两个打包命令。

### GraphQL queries
Use the GraphiQL playground at `http://localhost:4567/api/graphql` (when server is running in dev mode) to explore the schema and test queries.

### Exposed ORM patterns
- Table objects are `object` singletons extending `IntIdTable()`
- `reference("column", OtherTable, ReferenceOption.CASCADE)` creates FK
- `init { index(false, column) }` adds non-unique indexes
- Queries use `transaction { ... }` blocks
- `selectAll()`, `select(columns)`, `.where { }`, `.orderBy()`, `.limit()`, `.offset()`, `.count()`
