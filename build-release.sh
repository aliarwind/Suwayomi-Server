#!/bin/bash
#
# Suwayomi-Server Windows Release Builder
# ========================================
# 在 Windows Git Bash 下构建 windows-x64 发布包。
#
# 用法:
#   ./build-release.sh              # 完整构建（编译 + 打包）
#   ./build-release.sh --skip-gradle  # 跳过 Gradle 编译，仅重新打包
#   ./build-release.sh --clean        # 清理后重新构建
#
# 产物输出到 dist/ 目录

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# ============================================================
# 配置
# ============================================================
JDK_DIR="$SCRIPT_DIR/build-tools/zulu21.40.17-ca-jdk21.0.6-win_x64"
# 始终使用内置 JDK 21 编译，不受系统 JAVA_HOME 影响
JAVA_HOME="$JDK_DIR"
JAVA_BIN="$JAVA_HOME/bin/java"

JRE_ZIP="zulu25.34.17-ca-jre25.0.3-win_x64.zip"
JRE_URL="https://cdn.azul.com/zulu/bin/$JRE_ZIP"
JRE_DIR_NAME="zulu25.34.17-ca-jre25.0.3-win_x64"

ELECTRON_VERSION="v37.2.5"
ELECTRON_ZIP="electron-${ELECTRON_VERSION}-win32-x64.zip"
ELECTRON_URL="https://github.com/electron/electron/releases/download/${ELECTRON_VERSION}/${ELECTRON_ZIP}"

BUILD_TOOLS="$SCRIPT_DIR/build-tools"
DIST_DIR="$SCRIPT_DIR/dist"

# ============================================================
# 参数解析
# ============================================================
SKIP_GRADLE=false
CLEAN=false

for arg in "$@"; do
    case "$arg" in
        --skip-gradle) SKIP_GRADLE=true ;;
        --clean)       CLEAN=true ;;
        -h|--help)
            echo "用法: $0 [--skip-gradle] [--clean]"
            echo ""
            echo "  --skip-gradle  跳过 Gradle 编译，只重新打包已有 JAR"
            echo "  --clean        清理 build-tools 缓存和 dist 目录后重新构建"
            exit 0
            ;;
    esac
done

# ============================================================
# 工具函数
# ============================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

check_cmd() {
    command -v "$1" >/dev/null 2>&1 || log_error "找不到命令 '$1'，请先安装"
}

# ============================================================
# 环境检查
# ============================================================
check_env() {
    log_info "检查构建环境..."

    check_cmd unzip
    check_cmd curl
    check_cmd zip

    # 检查 JDK
    if [ ! -f "$JAVA_BIN" ]; then
        log_error "找不到 JDK: $JAVA_BIN"
        log_error "请确保 Zulu JDK 21 已解压到 $JDK_DIR"
    fi

    local java_ver
    java_ver=$("$JAVA_BIN" -version 2>&1 | head -1)
    log_info "JDK: $java_ver"
}

# ============================================================
# WebUI 构建（始终执行）
# ============================================================
build_webui() {
    local WEBUI_DIR="${WEBUI_DIR:-$SCRIPT_DIR/../Suwayomi-WebUI}"
    if [ ! -f "$WEBUI_DIR/package.json" ]; then
        log_warn "未找到本地 WebUI 仓库 ($WEBUI_DIR)，将使用已有的 WebUI.zip"
        return 0
    fi

    # 检查是否需要 i18n 提取
    local PO_FILE="$WEBUI_DIR/src/i18n/locales/en.po"
    local NEWER_TSX
    NEWER_TSX=$(find "$WEBUI_DIR/src" -name "*.tsx" -newer "$PO_FILE" 2>/dev/null | head -1)
    if [ -n "$NEWER_TSX" ]; then
        log_info "检测到新增或修改的 UI 文本，提取 i18n 消息..."
        cd "$WEBUI_DIR" && npx lingui extract --clean || log_warn "i18n:extract 失败，继续构建..."
        cd "$SCRIPT_DIR"
    fi

    log_info "从本地仓库构建 WebUI: $WEBUI_DIR"
    cd "$WEBUI_DIR"
    if [ ! -d "node_modules" ]; then
        log_info "安装 WebUI 依赖..."
        pnpm install || log_error "pnpm install 失败"
    fi
    log_info "编译 WebUI..."
    pnpm run build || log_error "pnpm build 失败"
    cd "$SCRIPT_DIR"

    # Package WebUI build into zip
    log_info "打包 WebUI.zip..."
    local WEBUI_BUILD_DIR="$WEBUI_DIR/build"
    local WEBUI_ZIP="$SCRIPT_DIR/server/src/main/resources/WebUI.zip"
    rm -f "$WEBUI_ZIP"
    # Write revision file so the server can identify the WebUI version
    echo "r99999" > "$WEBUI_BUILD_DIR/revision"
    (cd "$WEBUI_BUILD_DIR" && zip -r -9 "$WEBUI_ZIP" .) || log_error "WebUI zip 失败"
    log_info "WebUI.zip 已更新: $(du -h "$WEBUI_ZIP" | cut -f1)"
}

# ============================================================
# Gradle 编译
# ============================================================
build_jar() {
    if [ "$SKIP_GRADLE" = true ]; then
        local jar_count
        jar_count=$(ls "$SCRIPT_DIR/server/build/"*.jar 2>/dev/null | wc -l)
        if [ "$jar_count" -eq 0 ]; then
            log_warn "--skip-gradle 但未找到已有 JAR，强制重新编译"
        else
            log_info "跳过 Gradle 编译（已有 JAR: $(ls "$SCRIPT_DIR/server/build/"*.jar)）"
            return 0
        fi
    fi

    log_info "开始 Gradle 编译 shadowJar..."

    export JAVA_HOME="$JDK_DIR"
    export PATH="$JAVA_HOME/bin:$PATH"

    # 清理旧 JAR，确保 assemble 只找到本次构建产物
    rm -f "$SCRIPT_DIR/server/build/"*.jar 2>/dev/null || true

    # 如果编译 OOM 可调大这个值
    local HEAP="${GRADLE_HEAP:-4096m}"

    ./gradlew :server:shadowJar \
        -Dorg.gradle.jvmargs="-Xmx$HEAP" \
        --no-daemon \
        --stacktrace || log_error "Gradle 编译失败"

    log_info "Gradle 编译完成"
    ls -lh "$SCRIPT_DIR/server/build/"*.jar
}

# ============================================================
# 下载组件
# ============================================================
download_if_missing() {
    local url="$1"
    local dest="$2"
    local label="$3"

    if [ -f "$dest" ]; then
        log_info "$label 已存在，跳过下载"
        return 0
    fi

    log_info "下载 $label ..."
    curl -fL --progress-bar "$url" -o "$dest" || log_error "下载 $label 失败"

    log_info "$label 下载完成 ($(du -h "$dest" | cut -f1))"
}

download_components() {
    mkdir -p "$BUILD_TOOLS"

    # Suwayomi Launcher
    log_info "获取最新的 Suwayomi-Launcher 版本..."
    local launcher_url
    launcher_url=$(curl -sf "https://api.github.com/repos/Suwayomi/Suwayomi-Launcher/releases/latest" \
        | grep "browser_download_url" \
        | grep ".jar" \
        | head -n 1 \
        | cut -d '"' -f 4)

    if [ -z "$launcher_url" ]; then
        if [ -f "$BUILD_TOOLS/Suwayomi-Launcher.jar" ]; then
            log_info "无法获取 Suwayomi-Launcher 下载地址，使用缓存版本"
        else
            log_error "无法获取 Suwayomi-Launcher 下载地址且无缓存版本"
        fi
    else
        download_if_missing "$launcher_url" \
            "$BUILD_TOOLS/Suwayomi-Launcher.jar" \
            "Suwayomi-Launcher"
    fi

    # Zulu JRE
    download_if_missing "$JRE_URL" \
        "$BUILD_TOOLS/$JRE_ZIP" \
        "Zulu JRE 25"

    # Electron
    download_if_missing "$ELECTRON_URL" \
        "$BUILD_TOOLS/$ELECTRON_ZIP" \
        "Electron $ELECTRON_VERSION"
}

# ============================================================
# 组装发布目录
# ============================================================
assemble() {
    # 找到最新的 JAR
    local JAR_FILE
    JAR_FILE=$(ls "$SCRIPT_DIR/server/build/"*.jar 2>/dev/null | tail -n1)
    if [ -z "$JAR_FILE" ]; then
        log_error "找不到编译产物 JAR，请先运行 ./gradlew :server:shadowJar"
    fi

    local JAR_BASENAME
    JAR_BASENAME=$(basename "$JAR_FILE" .jar)
    local RELEASE_NAME="${JAR_BASENAME}-windows-x64"

    log_info "组装发布目录: $RELEASE_NAME"

    # 清理旧目录（处理 Windows 下文件被占用的情况）
    if [ -d "$DIST_DIR/$RELEASE_NAME" ]; then
        rm -rf "$DIST_DIR/$RELEASE_NAME" 2>/dev/null || {
            log_warn "旧目录部分文件被占用，尝试重命名旧目录..."
            mv "$DIST_DIR/$RELEASE_NAME" "$DIST_DIR/${RELEASE_NAME}.old" 2>/dev/null || true
        }
    fi
    rm -rf "$DIST_DIR/${RELEASE_NAME}.old" 2>/dev/null || true
    sleep 0.5

    # 创建目录结构
    mkdir -p "$DIST_DIR/$RELEASE_NAME/bin"
    mkdir -p "$DIST_DIR/$RELEASE_NAME/electron"
    mkdir -p "$DIST_DIR/$RELEASE_NAME/jre"

    # 解压 JRE
    log_info "解压 JRE..."
    unzip -qo "$BUILD_TOOLS/$JRE_ZIP" -d "$BUILD_TOOLS/jre-tmp/"
    cp -r "$BUILD_TOOLS/jre-tmp/$JRE_DIR_NAME/"* "$DIST_DIR/$RELEASE_NAME/jre/"
    rm -rf "$BUILD_TOOLS/jre-tmp/"

    # 解压 Electron
    log_info "解压 Electron..."
    unzip -qo "$BUILD_TOOLS/$ELECTRON_ZIP" -d "$DIST_DIR/$RELEASE_NAME/electron/"

    # 复制 JAR
    log_info "复制 JAR..."
    cp "$JAR_FILE" "$DIST_DIR/$RELEASE_NAME/bin/Suwayomi-Server.jar"
    cp "$BUILD_TOOLS/Suwayomi-Launcher.jar" "$DIST_DIR/$RELEASE_NAME/"

    # 复制启动脚本
    cp "$SCRIPT_DIR/scripts/resources/Suwayomi Launcher.bat" "$DIST_DIR/$RELEASE_NAME/"
    cp "$SCRIPT_DIR/killtask4567.bat" "$DIST_DIR/$RELEASE_NAME/" 2>/dev/null || log_warn "killtask4567.bat 未找到，跳过"

    log_info "目录组装完成: $DIST_DIR/$RELEASE_NAME/"

    # 将 RELEASE_NAME 写入临时文件供 package() 使用
    echo "$RELEASE_NAME" > "$DIST_DIR/.last_release_name"
}

# ============================================================
# 打包 zip
# ============================================================
package() {
    local RELEASE_NAME
    if [ -f "$DIST_DIR/.last_release_name" ]; then
        RELEASE_NAME=$(cat "$DIST_DIR/.last_release_name")
        log_info "打包 $RELEASE_NAME.zip ..."
    else
        # 兼容旧行为：如果没有临时文件，回退到自动发现
        RELEASE_NAME=$(ls -d "$DIST_DIR"/Suwayomi-Server-*-windows-x64 2>/dev/null | head -1 | xargs basename)
        if [ -z "$RELEASE_NAME" ]; then
            log_error "找不到发布目录"
        fi
        log_info "打包 $RELEASE_NAME.zip (auto-detected)..."
    fi
    cd "$DIST_DIR"
    rm -f "${RELEASE_NAME}.zip"
    zip -9 -r "${RELEASE_NAME}.zip" "$RELEASE_NAME/" > /dev/null
    cd "$SCRIPT_DIR"

    log_info "打包完成: $DIST_DIR/${RELEASE_NAME}.zip"
}

# ============================================================
# 清理
# ============================================================
do_clean() {
    log_info "清理构建缓存..."
    rm -rf "$BUILD_TOOLS"/*.zip
    rm -rf "$BUILD_TOOLS/Suwayomi-Launcher.jar"
    rm -rf "$DIST_DIR"
    log_info "清理完成"
}

# ============================================================
# 主流程
# ============================================================
main() {
    echo ""
    echo "=========================================="
    echo "  Suwayomi-Server Release Builder"
    echo "  Platform: windows-x64"
    echo "=========================================="
    echo ""

    if [ "$CLEAN" = true ]; then
        do_clean
    fi

    mkdir -p "$DIST_DIR"

    check_env
    download_components
    build_webui
    build_jar
    assemble
    package

    echo ""
    echo "=========================================="
    echo "  构建完成！"
    echo "=========================================="
    echo ""
    echo "产物目录: $DIST_DIR/"
    ls -lh "$DIST_DIR"/*.zip 2>/dev/null
    echo ""
    echo "可执行目录: $(ls -d "$DIST_DIR"/Suwayomi-Server-*-windows-x64 2>/dev/null)"
    echo "启动方式: 进入上述目录，双击 Suwayomi Launcher.bat"
    echo ""
}

main "$@"
