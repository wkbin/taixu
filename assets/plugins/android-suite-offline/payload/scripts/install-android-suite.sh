#!/bin/sh
set -eu

PAYLOAD="${TAIXU_PLUGIN_PAYLOAD:?missing TAIXU_PLUGIN_PAYLOAD}"
ARCHIVES="$PAYLOAD/archives"
CHECKSUMS="$PAYLOAD/checksums/SHA256SUMS"
TOOL_DIR="${TAIXU_TOOL_DIR:?missing TAIXU_TOOL_DIR}"
ANDROID_HOME="/opt/android-sdk"
TOOLCHAIN_ROOT="/opt/taixu/toolchains/android"
JDK_HOME="$TOOLCHAIN_ROOT/jdk"
NDK_HOME="$TOOLCHAIN_ROOT/ndk"
GRADLE_VERSION="8.14.2"
BUILD_TOOLS_VERSION="35.0.0"

need() {
    resource_path="${1:-}"
    resource_name="${2:-$resource_path}"
    test -n "$resource_path" && test -s "$resource_path" || {
        echo "missing offline resource: ${resource_name:-unknown}" >&2
        exit 2
    }
}

progress() {
    progress_percent="$1"
    shift
    printf '[TAIXU_PROGRESS:%s] %s\n' "$progress_percent" "$*"
}

# Read ELF headers directly instead of depending on the optional `file` package.
# e_machine is stored at offset 18; AArch64 is EM_AARCH64 (183 / 0x00b7).
elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_elf() { test "$(elf_bytes -N 4 "$1")" = "7f454c46"; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }

mkdir -p "$TOOL_DIR/bin" "$ANDROID_HOME" "$TOOLCHAIN_ROOT" /opt/taixu/bin /opt/taixu/locks
need "$CHECKSUMS"

# 构建进程持有本锁的共享端 (flock -s)，装配必须持有独占端：杜绝“边构建边
# 改写工具链目录”造成的 JDK 目录闪断、半成品状态与运行中 JVM 文件被删。
command -v flock >/dev/null 2>&1 || {
    echo "missing flock: refusing to mutate the toolchain without a lock" >&2
    exit 8
}
exec 9>/opt/taixu/locks/android-toolchain.lock
flock -x -w 300 9 || {
    echo "android toolchain is busy (a build may be running); lock wait timed out" >&2
    exit 8
}

# Validate every bundled archive before changing the runtime.
progress 2 "[VERIFY] 正在校验全部离线归档：sha256sum -c checksums/SHA256SUMS"
(cd "$PAYLOAD" && (sha256sum -c checksums/SHA256SUMS 2>/dev/null || sed 's|^payload/||' checksums/SHA256SUMS | sha256sum -c -))
progress 6 "[VERIFY] 离线归档校验完成"

# JDK 17 ARM64.
progress 8 "[EXTRACT] 正在解压 JDK 17：jdk-17-aarch64-linux.tar.gz"
need "$ARCHIVES/jdk-17-aarch64-linux.tar.gz"
rm -rf "$JDK_HOME.staging"
mkdir -p "$JDK_HOME.staging"
tar -xzf "$ARCHIVES/jdk-17-aarch64-linux.tar.gz" -C "$JDK_HOME.staging"
JDK_BIN=$(find "$JDK_HOME.staging" -type f -path '*/bin/java' -print -quit)
need "$JDK_BIN"
JDK_SOURCE=$(dirname "$(dirname "$JDK_BIN")")
rm -rf "$JDK_HOME"
mv "$JDK_SOURCE" "$JDK_HOME"
rm -rf "$JDK_HOME.staging"
# 启动器必须是真正的 AArch64 ELF。包装脚本一旦与其 exec 目标形成回环
# （脚本 → 软链接 → 脚本），PRoot 下每轮 exec 都要过 ptrace 翻译，
# java 进程只烧 CPU、零输出。在跑 -version 之前先做静态断言。
is_aarch64_elf "$JDK_HOME/bin/java" || {
    echo "JDK java launcher is not an ARM64 ELF: $JDK_HOME/bin/java" >&2
    exit 7
}
is_aarch64_elf "$JDK_HOME/bin/javac" || {
    echo "JDK javac launcher is not an ARM64 ELF: $JDK_HOME/bin/javac" >&2
    exit 7
}
"$JDK_HOME/bin/java" -version >/dev/null 2>&1
progress 15 "[COMMAND] JDK 17 安装完成：java -version"

# ZIP is not a required system package for this offline plugin. Prefer native
# unzip when available; otherwise use the JDK jar tool installed above.
extract_zip() {
    archive="$1"
    destination="$2"
    mkdir -p "$destination"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$archive" -d "$destination"
    elif [ -x "$JDK_HOME/bin/jar" ]; then
        (cd "$destination" && "$JDK_HOME/bin/jar" xf "$archive")
    else
        echo "missing ZIP extractor: unzip and JDK jar are unavailable" >&2
        exit 6
    fi
}

# Flutter 工具链自身依赖 unzip 解压引擎缓存（bin/cache/downloads/*.zip）。
# 精简 rootfs 不含 unzip，且构建 PATH 只保证 /opt/taixu/bin 可见——把基于
# JDK jar 的常驻兼容层部署到那里，避免 "Missing unzip tool" 中断 Flutter 构建。
if ! command -v unzip >/dev/null 2>&1; then
    printf '%s\n' \
        '#!/bin/sh' \
        'archive=' \
        'dest=.' \
        'while [ "$#" -gt 0 ]; do' \
        '  case "$1" in' \
        '    -q|-qq|-o) shift ;;' \
        '    -d) dest="$2"; shift 2 ;;' \
        '    -*) shift ;;' \
        '    *) archive="$1"; shift ;;' \
        '  esac' \
        'done' \
        '[ -n "$archive" ] || exit 2' \
        'mkdir -p "$dest"' \
        "(cd \"\$dest\" && '$JDK_HOME/bin/jar' xf \"\$archive\")" \
        > /opt/taixu/bin/unzip
    chmod 755 /opt/taixu/bin/unzip
    progress 16 "[COMMAND] unzip 兼容层已部署（基于 JDK jar）：/opt/taixu/bin/unzip"
fi

# Gradle.
progress 17 "[EXTRACT] 正在解压 Gradle $GRADLE_VERSION：gradle-$GRADLE_VERSION-bin.zip"
need "$ARCHIVES/gradle-8.14.2-bin.zip"
rm -rf "/opt/gradle-$GRADLE_VERSION.staging"
mkdir -p "/opt/gradle-$GRADLE_VERSION.staging"
extract_zip "$ARCHIVES/gradle-$GRADLE_VERSION-bin.zip" "/opt/gradle-$GRADLE_VERSION.staging"
GRADLE_SOURCE=$(find "/opt/gradle-$GRADLE_VERSION.staging" -type f -name "gradle-launcher-$GRADLE_VERSION.jar" -print -quit | xargs -r dirname | xargs -r dirname)
need "$GRADLE_SOURCE/bin/gradle"
rm -rf "/opt/gradle-$GRADLE_VERSION"
mv "$GRADLE_SOURCE" "/opt/gradle-$GRADLE_VERSION"
rm -rf "/opt/gradle-$GRADLE_VERSION.staging"
chmod 755 "/opt/gradle-$GRADLE_VERSION/bin/gradle"
progress 23 "[COMMAND] Gradle $GRADLE_VERSION 安装完成"

# Android Platform 34.
progress 25 "[EXTRACT] 正在解压 Android Platform 34：platform-34-ext7_r03.zip"
need "$ARCHIVES/platform-34-ext7_r03.zip"
rm -rf /tmp/taixu-android-platform
mkdir -p /tmp/taixu-android-platform
extract_zip "$ARCHIVES/platform-34-ext7_r03.zip" /tmp/taixu-android-platform
PLATFORM_SOURCE=$(find /tmp/taixu-android-platform -type f -name android.jar -print -quit | xargs -r dirname)
need "$PLATFORM_SOURCE/android.jar"
rm -rf "$ANDROID_HOME/platforms/android-34"
mkdir -p "$ANDROID_HOME/platforms"
mv "$PLATFORM_SOURCE" "$ANDROID_HOME/platforms/android-34"
rm -rf /tmp/taixu-android-platform
progress 30 "[COMMAND] Android Platform 34 安装完成"

# Java Build-Tools 35 and ARM64 native SDK tools.
progress 32 "[EXTRACT] 正在解压 Build Tools $BUILD_TOOLS_VERSION：build-tools_r35_linux.zip"
need "$ARCHIVES/build-tools_r35_linux.zip"
rm -rf /tmp/taixu-build-tools
mkdir -p /tmp/taixu-build-tools
extract_zip "$ARCHIVES/build-tools_r35_linux.zip" /tmp/taixu-build-tools
BUILD_SOURCE=$(find /tmp/taixu-build-tools -type f -name source.properties -print -quit | xargs -r dirname)
need "$BUILD_SOURCE/lib/d8.jar"
rm -rf "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
mkdir -p "$ANDROID_HOME/build-tools"
mv "$BUILD_SOURCE" "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
rm -rf /tmp/taixu-build-tools

# The Google archive may contain x86 host ELF helpers. Keep Java/JAR assets,
# then remove every non-AArch64 ELF before installing the ARM64 replacements.
find "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION" -type f |
    while IFS= read -r file_path; do
        if is_elf "$file_path" && ! is_aarch64_elf "$file_path"; then
            rm -f "$file_path"
        fi
    done

progress 38 "[EXTRACT] 正在解压 ARM64 SDK 工具：android-sdk-tools-static-aarch64.zip"
need "$ARCHIVES/android-sdk-tools-static-aarch64.zip"
rm -rf /tmp/taixu-arm64-tools
mkdir -p /tmp/taixu-arm64-tools
extract_zip "$ARCHIVES/android-sdk-tools-static-aarch64.zip" /tmp/taixu-arm64-tools
AAPT2=$(find /tmp/taixu-arm64-tools -type f -name aapt2 -print -quit)
need "$AAPT2"
is_aarch64_elf "$AAPT2" || {
    echo "AAPT2 is not ARM64: $AAPT2" >&2
    exit 3
}
cp -a "$(dirname "$AAPT2")/." "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/"
for executable in aapt aapt2 aidl zipalign d8 apksigner dexdump split-select llvm-rs-cc; do
    if [ -f "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/$executable" ]; then
        chmod 755 "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/$executable"
    fi
done
rm -rf /tmp/taixu-arm64-tools
progress 45 "[VERIFY] AAPT2 ARM64 校验完成"

# ARM64 NDK. Find llvm tools dynamically; do not assume an x86 directory name.
progress 47 "[EXTRACT] 正在解压 Android NDK r29：android-ndk-r29-aarch64.tar.gz"
need "$ARCHIVES/android-ndk-r29-aarch64.tar.gz"
rm -rf "$NDK_HOME.staging"
mkdir -p "$NDK_HOME.staging"
tar -xzf "$ARCHIVES/android-ndk-r29-aarch64.tar.gz" -C "$NDK_HOME.staging"
NDK_SOURCE=$(find "$NDK_HOME.staging" -type f -name source.properties -print -quit | xargs -r dirname)
NDK_CLANG=$(find "$NDK_SOURCE/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit)
NDK_STRIP=$(find "$NDK_SOURCE/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name llvm-strip -print -quit)
need "$NDK_SOURCE/source.properties"
need "$NDK_CLANG" "NDK clang"
need "$NDK_STRIP" "NDK llvm-strip"
if ! is_aarch64_elf "$NDK_CLANG" || ! is_aarch64_elf "$NDK_STRIP"; then
    echo "NDK tools are not ARM64" >&2
    exit 4
fi
rm -rf "$NDK_HOME"
mv "$NDK_SOURCE" "$NDK_HOME"
rm -rf "$NDK_HOME.staging"
progress 61 "[VERIFY] Android NDK r29 ARM64 工具链校验完成"

# Linux AArch64 CMake and Ninja.
progress 63 "[EXTRACT] 正在解压 CMake ARM64：cmake-linux-aarch64.tar.gz"
need "$ARCHIVES/cmake-linux-aarch64.tar.gz"
rm -rf /tmp/taixu-cmake
mkdir -p /tmp/taixu-cmake
tar -xzf "$ARCHIVES/cmake-linux-aarch64.tar.gz" -C /tmp/taixu-cmake
CMAKE_BIN=$(find /tmp/taixu-cmake -type f -path '*/bin/cmake' -print -quit)
need "$CMAKE_BIN"
cp -a "$(dirname "$(dirname "$CMAKE_BIN")")" "$TOOL_DIR/cmake"
ln -sfn "$TOOL_DIR/cmake/bin/cmake" "$TOOL_DIR/bin/cmake"
rm -rf /tmp/taixu-cmake

progress 69 "[EXTRACT] 正在解压 Ninja ARM64：ninja-linux-aarch64.zip"
need "$ARCHIVES/ninja-linux-aarch64.zip"
extract_zip "$ARCHIVES/ninja-linux-aarch64.zip" "$TOOL_DIR/bin"
chmod +x "$TOOL_DIR/bin/ninja"
progress 73 "[COMMAND] CMake 与 Ninja 安装完成"

# ADB from the Debian/Termux aarch64 package. Extract it without apt/network.
progress 75 "[EXTRACT] 正在解包 ADB：android-tools_aarch64.deb"
need "$ARCHIVES/android-tools_aarch64.deb"
if [ -s "$ARCHIVES/android-tools_aarch64.deb" ]; then
    rm -rf /tmp/taixu-adb
    mkdir -p /tmp/taixu-adb
    if command -v dpkg-deb >/dev/null 2>&1; then
        dpkg-deb -x "$ARCHIVES/android-tools_aarch64.deb" /tmp/taixu-adb
    elif command -v ar >/dev/null 2>&1; then
        (cd /tmp/taixu-adb && ar x "$ARCHIVES/android-tools_aarch64.deb" && tar -xf data.tar.* 2>/dev/null)
    fi
    ADB_SOURCE=$(find /tmp/taixu-adb -type f -name adb -print -quit)
    need "$ADB_SOURCE"
    is_aarch64_elf "$ADB_SOURCE" || { echo "ADB is not ARM64" >&2; exit 5; }
    cp "$ADB_SOURCE" "$TOOL_DIR/bin/adb"
    chmod +x "$TOOL_DIR/bin/adb"
    rm -rf /tmp/taixu-adb
fi
progress 80 "[VERIFY] ADB ARM64 校验完成"

# Flutter 工具的 locateAndroidSdk 只有在 $ANDROID_HOME/platform-tools/adb
# （或 cmdline-tools/sdkmanager）存在时才认这个 SDK，缺了直接报
# "No Android SDK found. Try setting the ANDROID_HOME environment variable"。
# 套件只装了 build-tools + platform，这里补齐 platform-tools 布局与 licenses。
mkdir -p "$ANDROID_HOME/platform-tools" "$ANDROID_HOME/licenses"
if [ -x "$TOOL_DIR/bin/adb" ] && [ ! -e "$ANDROID_HOME/platform-tools/adb" ]; then
    ln -sfn "$TOOL_DIR/bin/adb" "$ANDROID_HOME/platform-tools/adb"
fi
# 常规 SDK 许可签名（Android SDK License r8+），Gradle/Flutter 静默检查用。
if [ ! -f "$ANDROID_HOME/licenses/android-sdk-license" ]; then
    printf '\x89\x50\x41\x59\x0d\x0a\x1a\x0a\xd0\x4a\x87\x95\x6d\x7d\x3c\xcf\x9d\nd56f5187d9450ff8409f4ab7c8ab84e9\n' \
        > "$ANDROID_HOME/licenses/android-sdk-license"
fi
if [ ! -f "$ANDROID_HOME/licenses/android-sdk-preview-license" ]; then
    printf '\x89\x50\x41\x59\x0d\x0a\x1a\x0a\xd0\x4a\x87\x95\x6d\x7d\x3c\xcf\x9d\n84831b9409646a918db3050d3fba6e9c\n' \
        > "$ANDROID_HOME/licenses/android-sdk-preview-license"
fi

progress 82 "[EXTRACT] 正在解压 Flutter Android ARM64 SDK"
need "$ARCHIVES/flutter-linux-arm64-android-only-slim.tar.gz"
if [ -s "$ARCHIVES/flutter-linux-arm64-android-only-slim.tar.gz" ]; then
    rm -rf /tmp/taixu-flutter
    mkdir -p /tmp/taixu-flutter
    tar -xzf "$ARCHIVES/flutter-linux-arm64-android-only-slim.tar.gz" -C /tmp/taixu-flutter
    FLUTTER_SOURCE=$(find /tmp/taixu-flutter -type f -path '*/bin/flutter' -print -quit | xargs -r dirname | xargs -r dirname)
    need "$FLUTTER_SOURCE/bin/flutter"
    rm -rf /opt/flutter
    mv "$FLUTTER_SOURCE" /opt/flutter
    rm -rf /tmp/taixu-flutter
    ln -sfn /opt/flutter/bin/flutter "$TOOL_DIR/bin/flutter"
    ln -sfn /opt/flutter/bin/dart "$TOOL_DIR/bin/dart"

    # 补齐 Flutter Engine Android ARM64 原生 gen_snapshot 路径
    ENGINE_DIR="/opt/flutter/bin/cache/artifacts/engine"
    if [ -x "$ENGINE_DIR/linux-arm64/gen_snapshot" ]; then
        for mode in release profile; do
            target_dir="$ENGINE_DIR/android-arm64-$mode/linux-arm64"
            mkdir -p "$target_dir" 2>/dev/null || true
            if [ ! -e "$target_dir/gen_snapshot" ]; then
                ln -sf "$ENGINE_DIR/linux-arm64/gen_snapshot" "$target_dir/gen_snapshot" 2>/dev/null || true
            fi
        done
    fi
fi
progress 85 "[COMMAND] Flutter Android ARM64 SDK 安装完成"

# Rust ARM64 独立工具链与 aarch64-linux-android 交叉编译目标库
RUST_STANDALONE=$(find "$ARCHIVES" -maxdepth 1 -type f -name 'rust-*-aarch64-unknown-linux-gnu.tar.*' -print -quit 2>/dev/null || true)
RUST_STD_ANDROID=$(find "$ARCHIVES" -maxdepth 1 -type f -name 'rust-std-*-aarch64-linux-android.tar.*' -print -quit 2>/dev/null || true)
RUST_HOME="/opt/taixu/toolchains/rust"
if [ -n "$RUST_STANDALONE" ] && [ -f "$RUST_STANDALONE" ]; then
    progress 86 "[EXTRACT] 正在解压 Rust ARM64 独立开发工具链：$(basename "$RUST_STANDALONE")"
    rm -rf /tmp/taixu-rust "$RUST_HOME"
    mkdir -p /tmp/taixu-rust "$RUST_HOME"
    tar -xf "$RUST_STANDALONE" -C /tmp/taixu-rust --strip-components=1 2>/dev/null || tar -xzf "$RUST_STANDALONE" -C /tmp/taixu-rust --strip-components=1
    if [ -x /tmp/taixu-rust/install.sh ]; then
        sh /tmp/taixu-rust/install.sh --prefix="$RUST_HOME" --components=rustc,cargo,rust-std-aarch64-unknown-linux-gnu --disable-ldconfig >/dev/null 2>&1
    fi
    rm -rf /tmp/taixu-rust

    if [ -n "$RUST_STD_ANDROID" ] && [ -f "$RUST_STD_ANDROID" ]; then
        progress 87 "[EXTRACT] 正在配置 Rust aarch64-linux-android 交叉编译目标库：$(basename "$RUST_STD_ANDROID")"
        mkdir -p /tmp/taixu-rust-android
        tar -xf "$RUST_STD_ANDROID" -C /tmp/taixu-rust-android --strip-components=1 2>/dev/null || tar -xzf "$RUST_STD_ANDROID" -C /tmp/taixu-rust-android --strip-components=1
        if [ -x /tmp/taixu-rust-android/install.sh ]; then
            sh /tmp/taixu-rust-android/install.sh --prefix="$RUST_HOME" --disable-ldconfig >/dev/null 2>&1
        fi
        rm -rf /tmp/taixu-rust-android
    fi

    if [ -x "$RUST_HOME/bin/rustc" ]; then
        chmod 755 "$RUST_HOME/bin/"* 2>/dev/null || true
        for cmd in rustc cargo rustdoc; do
            if [ -x "$RUST_HOME/bin/$cmd" ]; then
                ln -sfn "$RUST_HOME/bin/$cmd" "$TOOL_DIR/bin/$cmd"
                ln -sfn "$RUST_HOME/bin/$cmd" "/opt/taixu/bin/$cmd"
            fi
        done
        mkdir -p /root/.cargo
        cat << 'EOF' > /root/.cargo/config.toml
[target.aarch64-linux-android]
linker = "/opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
ar = "/opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"

[source.crates-io]
replace-with = 'tuna'

[source.tuna]
registry = "sparse+https://mirrors.tuna.tsinghua.edu.cn/crates.io-index/"
EOF
        progress 88 "[COMMAND] Rust 编译器与 Android 交叉编译配置完成"
    fi
fi

# Ripgrep (rg) ARM64: 毫秒级极速搜索
if [ -f "$ARCHIVES/ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz" ]; then
    progress 89 "[EXTRACT] 正在解压 Ripgrep 极速代码搜索工具：ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz"
    rm -rf /tmp/taixu-ripgrep
    mkdir -p /tmp/taixu-ripgrep
    tar -xzf "$ARCHIVES/ripgrep-15.2.0-aarch64-unknown-linux-musl.tar.gz" -C /tmp/taixu-ripgrep
    RG_BIN=$(find /tmp/taixu-ripgrep -type f -name rg -print -quit)
    need "$RG_BIN" "ripgrep binary"
    is_aarch64_elf "$RG_BIN" || { echo "Ripgrep is not ARM64" >&2; exit 5; }
    cp "$RG_BIN" "$TOOL_DIR/bin/rg"
    chmod 755 "$TOOL_DIR/bin/rg"
    ln -sfn "$TOOL_DIR/bin/rg" "/opt/taixu/bin/rg"
    rm -rf /tmp/taixu-ripgrep
    progress 90 "[COMMAND] Ripgrep (rg) ARM64 安装完成"
fi

# JADX 1.5.0 CLI: Java 反编译工具
if [ -f "$ARCHIVES/jadx-1.5.0.zip" ]; then
    progress 91 "[EXTRACT] 正在解压 JADX 1.5.0 反编译套件：jadx-1.5.0.zip"
    rm -rf "$TOOL_DIR/jadx" /tmp/taixu-jadx
    mkdir -p /tmp/taixu-jadx
    extract_zip "$ARCHIVES/jadx-1.5.0.zip" /tmp/taixu-jadx
    JADX_BIN=$(find /tmp/taixu-jadx -type f -name jadx -print -quit)
    need "$JADX_BIN" "jadx launcher"
    mkdir -p "$TOOL_DIR/jadx"
    cp -a "$(dirname "$(dirname "$JADX_BIN")")/." "$TOOL_DIR/jadx/"
    chmod +x "$TOOL_DIR/jadx/bin/jadx" "$TOOL_DIR/jadx/bin/jadx-gui" 2>/dev/null || true
    ln -sfn "$TOOL_DIR/jadx/bin/jadx" "$TOOL_DIR/bin/jadx"
    ln -sfn "$TOOL_DIR/jadx/bin/jadx" "/opt/taixu/bin/jadx"
    rm -rf /tmp/taixu-jadx
    progress 92 "[COMMAND] JADX 1.5.0 安装完成"
fi

# Apktool 2.10.0: 资源与 Smali 处理
if [ -f "$ARCHIVES/apktool_2.10.0.jar" ]; then
    progress 93 "[INSTALL] 正在配置 Apktool 2.10.0..."
    mkdir -p "$TOOL_DIR/lib"
    cp "$ARCHIVES/apktool_2.10.0.jar" "$TOOL_DIR/lib/apktool.jar"
    printf '#!/usr/bin/env sh\nexec "%s/bin/java" -jar "%s/lib/apktool.jar" "$@"\n' "$JDK_HOME" "$TOOL_DIR" > "$TOOL_DIR/bin/apktool"
    chmod 755 "$TOOL_DIR/bin/apktool"
    ln -sfn "$TOOL_DIR/bin/apktool" "/opt/taixu/bin/apktool"
    progress 94 "[COMMAND] Apktool 2.10.0 安装完成"
fi

# dex-tools 2.4 (dex2jar): DEX 转换与字节码工具
if [ -f "$ARCHIVES/dex-tools-v2.4.zip" ]; then
    progress 95 "[EXTRACT] 正在解压 dex-tools 2.4 (dex2jar)：dex-tools-v2.4.zip"
    rm -rf "$TOOL_DIR/dex-tools" /tmp/taixu-d2j
    mkdir -p /tmp/taixu-d2j
    extract_zip "$ARCHIVES/dex-tools-v2.4.zip" /tmp/taixu-d2j
    D2J_SCRIPT=$(find /tmp/taixu-d2j -type f -name "d2j-dex2jar.sh" -print -quit)
    need "$D2J_SCRIPT" "d2j-dex2jar script"
    mkdir -p "$TOOL_DIR/dex-tools"
    cp -a "$(dirname "$D2J_SCRIPT")/." "$TOOL_DIR/dex-tools/"
    chmod +x "$TOOL_DIR/dex-tools/"*.sh 2>/dev/null || true
    for script in "$TOOL_DIR/dex-tools/"*.sh; do
        if [ -f "$script" ]; then
            base_cmd=$(basename "$script" .sh)
            ln -sfn "$script" "$TOOL_DIR/bin/$base_cmd"
            ln -sfn "$script" "$TOOL_DIR/bin/$base_cmd.sh"
            ln -sfn "$script" "/opt/taixu/bin/$base_cmd"
            ln -sfn "$script" "/opt/taixu/bin/$base_cmd.sh"
        fi
    done
    rm -rf /tmp/taixu-d2j
    progress 96 "[COMMAND] dex-tools (d2j-dex2jar) 安装完成"
fi

# Android Build-Tools 35 工具链命令链接（apksigner, zipalign, aapt, aapt2, dexdump, d8, aidl）
for sdk_cmd in aapt aapt2 aidl zipalign apksigner dexdump d8; do
    if [ -f "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/$sdk_cmd" ]; then
        chmod 755 "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/$sdk_cmd"
        ln -sfn "$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/$sdk_cmd" "$TOOL_DIR/bin/$sdk_cmd"
    fi
done

# JDK 核心签名与密钥工具（jarsigner, keytool）
for jdk_cmd in jarsigner keytool; do
    if [ -x "$JDK_HOME/bin/$jdk_cmd" ]; then
        ln -sfn "$JDK_HOME/bin/$jdk_cmd" "$TOOL_DIR/bin/$jdk_cmd"
    fi
done

# NDK LLVM 原生二进制逆向与分析工具（readelf, objdump, nm, strings, c++filt）
NDK_BIN_DIR=$(find "$NDK_HOME/toolchains/llvm/prebuilt" \( -type f -o -type l \) -name clang -print -quit 2>/dev/null | xargs -r dirname)
if [ -n "$NDK_BIN_DIR" ] && [ -d "$NDK_BIN_DIR" ]; then
    for llvm_tool in llvm-readelf llvm-objdump llvm-nm llvm-strings llvm-cxxfilt; do
        if [ -x "$NDK_BIN_DIR/$llvm_tool" ]; then
            ln -sfn "$NDK_BIN_DIR/$llvm_tool" "$TOOL_DIR/bin/$llvm_tool"
        fi
    done
    if [ -x "$NDK_BIN_DIR/llvm-readelf" ]; then ln -sfn "$NDK_BIN_DIR/llvm-readelf" "$TOOL_DIR/bin/readelf"; fi
    if [ -x "$NDK_BIN_DIR/llvm-objdump" ]; then ln -sfn "$NDK_BIN_DIR/llvm-objdump" "$TOOL_DIR/bin/objdump"; fi
    if [ -x "$NDK_BIN_DIR/llvm-nm" ]; then ln -sfn "$NDK_BIN_DIR/llvm-nm" "$TOOL_DIR/bin/nm"; fi
    if [ -x "$NDK_BIN_DIR/llvm-strings" ]; then ln -sfn "$NDK_BIN_DIR/llvm-strings" "$TOOL_DIR/bin/strings"; fi
    if [ -x "$NDK_BIN_DIR/llvm-cxxfilt" ]; then
        ln -sfn "$NDK_BIN_DIR/llvm-cxxfilt" "$TOOL_DIR/bin/cxxfilt"
        ln -sfn "$NDK_BIN_DIR/llvm-cxxfilt" "$TOOL_DIR/bin/c++filt"
        ln -sfn "$NDK_BIN_DIR/llvm-cxxfilt" "/opt/taixu/bin/c++filt"
    fi
fi

# uber-apk-signer: 独立一键对齐与签名套件（如果归档存在）
UBER_SIGNER_JAR=$(find "$ARCHIVES" -maxdepth 1 -type f -name 'uber-apk-signer*.jar' -print -quit 2>/dev/null || true)
if [ -n "$UBER_SIGNER_JAR" ] && [ -f "$UBER_SIGNER_JAR" ]; then
    mkdir -p "$TOOL_DIR/lib"
    cp "$UBER_SIGNER_JAR" "$TOOL_DIR/lib/uber-apk-signer.jar"
    printf '#!/usr/bin/env sh\nexec "%s/bin/java" -jar "%s/lib/uber-apk-signer.jar" "$@"\n' "$JDK_HOME" "$TOOL_DIR" > "$TOOL_DIR/bin/uber-apk-signer"
    chmod 755 "$TOOL_DIR/bin/uber-apk-signer"
fi

# 官方原版 smali / baksmali 独立工具（如果归档存在）
SMALI_JAR=$(find "$ARCHIVES" -maxdepth 1 -type f -name 'smali*.jar' -print -quit 2>/dev/null || true)
BAKSMALI_JAR=$(find "$ARCHIVES" -maxdepth 1 -type f -name 'baksmali*.jar' -print -quit 2>/dev/null || true)
if [ -n "$SMALI_JAR" ] && [ -f "$SMALI_JAR" ]; then
    mkdir -p "$TOOL_DIR/lib"
    cp "$SMALI_JAR" "$TOOL_DIR/lib/smali.jar"
    printf '#!/usr/bin/env sh\nexec "%s/bin/java" -jar "%s/lib/smali.jar" "$@"\n' "$JDK_HOME" "$TOOL_DIR" > "$TOOL_DIR/bin/smali"
    chmod 755 "$TOOL_DIR/bin/smali"
fi
if [ -n "$BAKSMALI_JAR" ] && [ -f "$BAKSMALI_JAR" ]; then
    mkdir -p "$TOOL_DIR/lib"
    cp "$BAKSMALI_JAR" "$TOOL_DIR/lib/baksmali.jar"
    printf '#!/usr/bin/env sh\nexec "%s/bin/java" -jar "%s/lib/baksmali.jar" "$@"\n' "$JDK_HOME" "$TOOL_DIR" > "$TOOL_DIR/bin/baksmali"
    chmod 755 "$TOOL_DIR/bin/baksmali"
fi

# taixu-apk-sign: 内置一键自动化回包对齐与签名命令
cat << 'EOF' > "$TOOL_DIR/bin/taixu-apk-sign"
#!/bin/sh
set -eu
if [ $# -lt 1 ]; then
    echo "用法: taixu-apk-sign <输入未签名APK> [输出已签名APK]"
    echo "示例: taixu-apk-sign app-unsigned.apk app-signed.apk"
    exit 1
fi
INPUT_APK="$1"
OUTPUT_APK="${2:-${INPUT_APK%.apk}-signed.apk}"
if [ ! -f "$INPUT_APK" ]; then
    echo "错误: 输入文件不存在: $INPUT_APK" >&2
    exit 2
fi
KS_DIR="/root/.android"
KS_PATH="$KS_DIR/debug.keystore"
if [ ! -f "$KS_PATH" ]; then
    mkdir -p "$KS_DIR"
    echo "==> [taixu-apk-sign] 正在生成标准 Android Debug Keystore..."
    keytool -genkeypair -v -keystore "$KS_PATH" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
TMP_ALIGNED="/tmp/taixu-aligned-$$.apk"
echo "==> [taixu-apk-sign] 正在执行 4-byte 字节对齐 (zipalign)..."
zipalign -p -f 4 "$INPUT_APK" "$TMP_ALIGNED"
echo "==> [taixu-apk-sign] 正在执行 v1/v2/v3 签名 (apksigner)..."
apksigner sign --ks "$KS_PATH" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out "$OUTPUT_APK" "$TMP_ALIGNED"
rm -f "$TMP_ALIGNED"
echo "==> [taixu-apk-sign] 正在验证签名..."
apksigner verify -v "$OUTPUT_APK" | grep -E "Verifies|Signer #" || true
echo "==> [taixu-apk-sign] ✅ 签名回包完成: $OUTPUT_APK"
EOF
chmod 755 "$TOOL_DIR/bin/taixu-apk-sign"

progress 97 "[COMMAND] 正在创建全局开发、逆向与签名命令链接"
ln -sfn "$JDK_HOME/bin/java" "$TOOL_DIR/bin/java"
ln -sfn "$JDK_HOME/bin/javac" "$TOOL_DIR/bin/javac"
ln -sfn "/opt/gradle-$GRADLE_VERSION/bin/gradle" "$TOOL_DIR/bin/gradle"
for command in java javac gradle cmake ninja adb flutter dart rustc cargo rustdoc apksigner zipalign jarsigner keytool aapt aapt2 dexdump readelf objdump nm strings cxxfilt uber-apk-signer smali baksmali taixu-apk-sign jadx apktool d2j-dex2jar d2j-baksmali d2j-smali rg; do
    if [ "$command" = "adb" ] && [ -f "/opt/taixu/bin/adb" ] && ! [ -L "/opt/taixu/bin/adb" ]; then
        # 保留 /opt/taixu/bin/adb 智能连接包装脚本
        continue
    fi
    if [ -e "$TOOL_DIR/bin/$command" ]; then ln -sfn "$TOOL_DIR/bin/$command" "/opt/taixu/bin/$command"; fi
done

# 兼容旧版探针路径与系统标准 PATH
mkdir -p /usr/local/bin /opt 2>/dev/null || true
if [ -d "$TOOL_DIR/jadx" ]; then
    rm -rf /opt/jadx
    ln -sfn "$TOOL_DIR/jadx" /opt/jadx
fi
for common_cmd in jadx apktool rg d2j-dex2jar d2j-baksmali d2j-smali cmake ninja rustc cargo flutter dart aapt aapt2 zipalign apksigner taixu-apk-sign; do
    if [ -e "$TOOL_DIR/bin/$common_cmd" ]; then
        ln -sfn "$TOOL_DIR/bin/$common_cmd" "/usr/local/bin/$common_cmd" 2>/dev/null || true
    fi
done

# 链路终验：java 命令入口经全部软链解析后必须回到 JDK 的 AArch64 ELF
# 启动器。任何中间环节被替换成包装脚本（脚本 exec 软链、软链又指回脚本）
# 都会形成 PRoot 下的无限 exec 回环，这里在离开安装事务前彻底排除。
JAVA_CHAIN_TARGETS="$JDK_HOME/bin/java"
for chain_entry in "$TOOL_DIR/bin/java" "/opt/taixu/bin/java"; do
    resolved=$(readlink -f "$chain_entry" 2>/dev/null || echo "$chain_entry")
    case "$resolved" in
        "$JAVA_CHAIN_TARGETS") ;;
        *)
            echo "java command chain does not resolve to the JDK launcher: $chain_entry -> $resolved" >&2
            exit 9
            ;;
    esac
done
is_aarch64_elf "$JDK_HOME/bin/java" || {
    echo "JDK java launcher is not an ARM64 ELF after linking: $JDK_HOME/bin/java" >&2
    exit 9
}

mkdir -p /root/.gradle /root/.gradle/init.d
if [ -f "$PAYLOAD/config/gradle.properties" ]; then cp "$PAYLOAD/config/gradle.properties" /root/.gradle/gradle.properties; fi
if [ -f "$PAYLOAD/config/taixu-android-ndk.gradle" ]; then cp "$PAYLOAD/config/taixu-android-ndk.gradle" /root/.gradle/init.d/taixu-android-ndk.gradle; fi
printf '%s\n' 'android.builder.sdkDownload=false' >> /root/.gradle/gradle.properties

# 持久化环境变量（供终端、PTY与沙箱全生命周期共享）
mkdir -p /etc/profile.d
cat << EOF > /etc/profile.d/taixu-android.sh
# TaiXu Android development environment (managed by android-suite-offline plugin)
export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_HOME="/opt/gradle-$GRADLE_VERSION"
export TAIXU_AAPT2_PATH="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2"
export TAIXU_NDK_PATH="$NDK_HOME"
export TAIXU_NDK_VERSION="r29"
export ANDROID_NDK_HOME="$NDK_HOME"
export ANDROID_NDK_ROOT="$NDK_HOME"
export TAIXU_CMAKE_HOME="$TOOL_DIR/cmake"
export TAIXU_NINJA_HOME="$TOOL_DIR/bin"
export RUSTUP_HOME="/opt/taixu/toolchains/rust"
export CARGO_HOME="/root/.cargo"
export PATH="/opt/taixu/bin:/opt/taixu/toolchains/rust/bin:$TOOL_DIR/bin:$TOOL_DIR/cmake/bin:\$JAVA_HOME/bin:\$GRADLE_HOME/bin:/opt/flutter/bin:\$PATH"
export _JAVA_OPTIONS="-Djava.security.egd=file:/dev/urandom"
EOF
chmod 644 /etc/profile.d/taixu-android.sh 2>/dev/null || true

cat << EOF > /etc/environment
JAVA_HOME=$JDK_HOME
ANDROID_HOME=$ANDROID_HOME
ANDROID_SDK_ROOT=$ANDROID_HOME
GRADLE_HOME=/opt/gradle-$GRADLE_VERSION
TAIXU_AAPT2_PATH=$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION/aapt2
TAIXU_NDK_PATH=$NDK_HOME
TAIXU_NDK_VERSION=r29
ANDROID_NDK_HOME=$NDK_HOME
ANDROID_NDK_ROOT=$NDK_HOME
RUSTUP_HOME=/opt/taixu/toolchains/rust
CARGO_HOME=/root/.cargo
PATH=/opt/taixu/bin:/opt/taixu/toolchains/rust/bin:$TOOL_DIR/bin:$TOOL_DIR/cmake/bin:$JDK_HOME/bin:/opt/gradle-$GRADLE_VERSION/bin:/opt/flutter/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
_JAVA_OPTIONS=-Djava.security.egd=file:/dev/urandom
EOF

if [ -f /root/.bashrc ] && ! grep -q "taixu-android" /root/.bashrc 2>/dev/null; then
    echo '. /etc/profile.d/taixu-android.sh 2>/dev/null || true' >> /root/.bashrc
fi
if [ -f /etc/bash.bashrc ] && ! grep -q "taixu-android" /etc/bash.bashrc 2>/dev/null; then
    echo '. /etc/profile.d/taixu-android.sh 2>/dev/null || true' >> /etc/bash.bashrc
fi

progress 98 "[VERIFY] 正在执行 Android 全栈开发套件最终验证"
/bin/sh "$PAYLOAD/scripts/verify-android-suite.sh"
progress 100 "[VERIFY] Android 全栈开发套件验证完成"
