# AGENTS.md

## Project Overview

**Suwayomi-Server** — 一个免费开源的漫画阅读器服务器，可运行 Mihon (Tachiyomi) 的扩展。提供 GraphQL API，配合 WebUI 前端使用。多平台支持（Windows / Linux / macOS / Docker）。

- **上游仓库**: [Suwayomi/Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
- **技术栈**: Kotlin 2.4.0 + Javalin (HTTP) + Exposed (ORM) + GraphQL Kotlin + JTE 模板
- **构建系统**: Gradle 9.5.1 + ShadowJar (fat JAR)
- **JVM 目标**: JDK 21+
- **CI**: GitHub Actions，打包发布各平台 bundle

## Directory Structure

```
Suwayomi-Server/
├── server/                          # 主服务模块
│   ├── src/main/kotlin/suwayomi/tachidesk/
│   │   ├── Main.kt                  # 入口点
│   │   ├── server/                  # Javalin HTTP 服务器配置
│   │   ├── graphql/                 # GraphQL API（queries / mutations / subscriptions）
│   │   ├── manga/                   # 漫画核心逻辑（下载 / 更新 / 追踪 / 同步）
│   │   ├── global/                  # 全局功能（WebView / SyncYomi / JWT）
│   │   ├── opds/                    # OPDS 1.2 支持
│   │   ├── i18n/                    # 国际化辅助
│   │   └── util/                    # 工具类
│   └── build.gradle.kts             # 服务端构建配置
├── AndroidCompat/                   # Android API 兼容层
├── buildSrc/                        # Gradle 构建脚本
├── scripts/
│   ├── bundler.sh                   # 多平台发布打包脚本（仅 Linux）
│   └── resources/                   # 启动脚本 / .desktop / systemd / MSI 模板
├── build-release.sh                 # ★ Windows 本地发布构建脚本
├── gradle/                          # Gradle Wrapper + 版本目录
└── docs/                            # 文档
```

## Key Architecture

- **HTTP 层**: Javalin (端口 4567)
- **API**: 主接口 GraphQL (`/api/graphql`)，REST (`/api/v1`) 即将废弃
- **数据库**: H2 (默认) / PostgreSQL (可选)，通过 Exposed ORM 访问
- **扩展系统**: 加载 Mihon APK 扩展（通过 AndroidCompat 兼容层）
- **前端**: WebUI 作为 ZIP 资源内置在 JAR 中（构建时从 GitHub Release 下载）
- **桌面模式**: 内嵌 JCEF/Electron WebView
- **同步**: 支持 SyncYomi 协议与 Mihon/Neko 客户端同步

## Build & Development

### Prerequisites

- **JDK 21+**（推荐 Zulu JDK 21）
- Git Bash（Windows）或 Linux/macOS 终端
- 网络访问 GitHub / Maven Central / Gradle Plugin Portal

### Commands

```bash
# 编译 fat JAR（包含 WebUI）
./gradlew :server:shadowJar

# 仅编译（跳过 WebUI 下载）
./gradlew :server:compileKotlin

# 运行服务
./gradlew :server:run

# 运行测试
./gradlew :server:test

# 运行全量测试（含慢测试）
./gradlew :server:test -Pmasstest

# 代码格式化
./gradlew ktlintFormat

# Windows 本地构建发布包
./build-release.sh                  # 完整构建（编译 + 打包）
./build-release.sh --skip-gradle    # 跳过编译，仅重新打包
./build-release.sh --clean          # 清理缓存后重建
```

### Gradle 内存

编译是内存密集型任务。如果遇到 OOM：
```bash
export GRADLE_HEAP=5120m
./gradlew :server:shadowJar -Dorg.gradle.jvmargs="-Xmx5120m" --no-daemon
```

### build-release.sh 工作流

```
check_env → download_components → build_jar → assemble → package
                                          ↓
                              Suwayomi-Launcher.jar (GitHub API)
                              Zulu JRE 25.0.3 (cdn.azul.com)
                              Electron v37.2.5 (GitHub)
```

产物输出到 `dist/`:
- `dist/Suwayomi-Server-vX.X.X-windows-x64/` — 可执行目录
- `dist/Suwayomi-Server-vX.X.X-windows-x64.zip` — 发布包

### 依赖缓存

首次运行下载 ~200MB（JDK 21 / JRE 25 / Electron），后续运行命中 `build-tools/` 缓存，仅增量编译。

## Key Configuration

- **版本号**: 由 Git tag 自动生成（`getTachideskVersion()`）
- **构建类型**: `Preview`（push） / `Stable`（tag），通过 `ProductBuildType` 环境变量控制
- **WebUI 版本**: `webUIRevisionTag` 在构建脚本中定义
- **主类**: `suwayomi.tachidesk.MainKt`

## Module Graph

```
server
├── server:i18n              # 多语言资源（Kotlin Multiplatform）
├── server:server-config     # 配置模块
└── server:server-config-generate  # 配置生成

AndroidCompat
└── AndroidCompat:Config     # Android 配置兼容
```

## Coding Conventions

- 使用 ktlint 进行代码格式化（CI 中强制检查）
- 包名遵循 `suwayomi.tachidesk.<domain>` 结构
- GraphQL schema 定义在 `graphql/types/`，操作在 `queries/` / `mutations/` / `subscriptions/`
- ORM 表定义在各自模块的 `model/table/` 下
- 跟踪上游 Mihon (Tachiyomi) 功能设计，不随意添加超出范围的特性

## Notes for AI Agents

1. **JDK 路径**: 本地构建 JDK 位于 `build-tools/zulu21.40.17-ca-jdk21.0.6-win_x64/`，脚本会自动使用
2. **TLS 问题**: 如果 Gradle 下载依赖时报 SSL 错误，用 JDK 15 的 `cacerts` 覆盖 JDK 21 的同名文件
3. **配置模型**: 服务器配置定义在 `server/src/main/kotlin/suwayomi/tachidesk/server/ServerConfig.kt`，通过类型安全配置读取
4. **GraphQL 入口**: `TachideskGraphQLServer.kt` 是 GraphQL 引擎初始化点
5. **不要直接运行 bundler.sh**: 该脚本是 CI Linux 环境专用，Windows 下用 `build-release.sh`
6. **WebUI 更新**: 修改 `webUIRevisionTag` 并运行 `downloadWebUI` 任务即可切换 WebUI 版本
