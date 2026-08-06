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
   - Packages `build/` into `server/src/main/resources/WebUI.zip` via ant zip
2. **build_jar()** — Runs Gradle `:server:shadowJar` (fat JAR, ~166MB)
3. **assemble** — Copies JAR + Zulu JRE 25 + Electron + launcher scripts → `dist/Suwayomi-Server-vX.X.X-windows-x64/`
4. **package** — Zips the directory

Output: `dist/Suwayomi-Server-v2.3.2240-windows-x64.zip` (~341MB).

### Build flags

| Flag | Effect |
|------|--------|
| `--skip-gradle` | Skip Gradle compilation, just repackage (useful when only WebUI changed) |
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

## Tech Stack

### Backend (Suwayomi-Server)
- **Language**: Kotlin 2.4.0, targets JVM 21
- **HTTP**: Javalin (port 4567)
- **API**: GraphQL at `/api/graphql` (primary); REST `/api/v1` (deprecated)
- **ORM**: Exposed v1 (Kotlin DSL for SQL)
- **DB**: H2 (default, file-based) or PostgreSQL
- **Build**: Gradle 9.5.1 + ShadowJar
- **Migrations**: Numbered classes in `server/.../database/migration/` (M0001 through M0061), auto-run on startup
- **Extensions**: Loads Mihon APK extensions via AndroidCompat compatibility layer

### Frontend (Suwayomi-WebUI)
- **Language**: TypeScript 6 + React 19
- **UI**: MUI v6/v9 + Emotion CSS-in-JS (`sx` prop)
- **Build**: Vite 8
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
│   │   ├── MangaTable.kt                      # Manga table (index on inLibrary)
│   │   ├── ChapterTable.kt                    # Chapter table (indexes on manga, isDownloaded)
│   │   └── CategoryMangaTable.kt              # Join table (indexes on category, manga)
│   └── model/dataclass/                       # Data classes
└── server/database/migration/
    └── M0061_AddPerformanceIndexes.kt         # Performance indexes for 20k+ manga libraries
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
│   ├── PaginationControls (always visible, with jump-to-page input)
│   └── fetchPolicy: 'network-only' (no stale cache issues)
│
└── paginationEnabled=false → LibraryInfiniteScroll
    ├── VirtuosoGridPersisted with useWindowScroll
    ├── Fetches ALL manga (no first/offset)
    └── Client-side filtering + sorting via useGetVisibleLibraryMangas
```

The wrapper pattern avoids React hooks conditional-call errors (Error #185).

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
5. **`res.count()`** → totalCount (before LIMIT, uses `inLibrary` index from M0061)
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
| **Performance indexes (M0061)** | 5 indexes: inLibrary, category, manga, isDownloaded | — |
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

Migrations are in `server/.../database/migration/`, numbered M0001–M0061. They extend `SQLMigration` and run sequentially on startup.

To add a new migration:
```kotlin
class M0062_YourMigration : SQLMigration() {
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

### GraphQL queries
Use the GraphiQL playground at `http://localhost:4567/api/graphql` (when server is running in dev mode) to explore the schema and test queries.

### Exposed ORM patterns
- Table objects are `object` singletons extending `IntIdTable()`
- `reference("column", OtherTable, ReferenceOption.CASCADE)` creates FK
- `init { index(false, column) }` adds non-unique indexes
- Queries use `transaction { ... }` blocks
- `selectAll()`, `select(columns)`, `.where { }`, `.orderBy()`, `.limit()`, `.offset()`, `.count()`
