#!/bin/sh
set -eu

elf_bytes() { od -An -t x1 "$@" 2>/dev/null | tr -d ' \n'; }
is_aarch64_elf() { test "$(elf_bytes -j 18 -N 2 "$1")" = "b700"; }

fail() {
    echo "Android suite verification failed: $*" >&2
    exit 7
}

require_executable() { test -x "$1" || fail "missing executable: $1"; }
require_file() { test -f "$1" || fail "missing file: $1"; }
require_directory() { test -d "$1" || fail "missing directory: $1"; }
require_aarch64() { is_aarch64_elf "$1" || fail "not an ARM64 ELF: $1"; }
require_command() {
    command_path="$1"
    shift
    "$command_path" "$@" >/dev/null 2>&1 || fail "command failed: $command_path $*"
}
require_property() {
    expected="$1"
    grep -Fqx "$expected" /root/.gradle/gradle.properties ||
        fail "missing Gradle property: $expected"
}

require_executable /opt/taixu/bin/java
require_executable /opt/taixu/bin/javac
require_executable /opt/taixu/bin/gradle
require_executable /opt/taixu/bin/cmake
require_executable /opt/taixu/bin/ninja
# 静态断言先于任何 -version 执行：java 启动器若被换成包装脚本并与软链
# 形成回环，require_command 会挂死在无限 exec 上；先用 ELF 魔数拦下。
# od 会跟随软链，符号链接链最终必须落在 JDK 的 AArch64 ELF 上。
require_aarch64 /opt/taixu/bin/java
require_aarch64 /opt/taixu/bin/javac
require_file /opt/android-sdk/platforms/android-34/android.jar
require_file /opt/android-sdk/build-tools/35.0.0/lib/d8.jar
require_executable /opt/android-sdk/build-tools/35.0.0/aapt2
require_aarch64 /opt/android-sdk/build-tools/35.0.0/aapt2
NDK_STRIP=$(find /opt/taixu/toolchains/android/ndk/toolchains/llvm/prebuilt \( -type f -o -type l \) -name llvm-strip -print -quit)
test -n "$NDK_STRIP" || fail "missing NDK llvm-strip"
require_executable "$NDK_STRIP"
require_aarch64 "$NDK_STRIP"
require_executable /opt/taixu/bin/adb
if is_aarch64_elf /opt/taixu/bin/adb; then
    :
else
    # /opt/taixu/bin/adb 为智能连接包装脚本，校验底层真实 ELF
    REAL_ADB="${TAIXU_TOOL_DIR:-/opt/taixu/tools/android-suite-offline}/bin/adb"
    test -x "$REAL_ADB" || REAL_ADB="/opt/android-sdk/platform-tools/adb"
    require_executable "$REAL_ADB"
    require_aarch64 "$REAL_ADB"
fi
require_command /opt/taixu/bin/java -version
require_command /opt/taixu/bin/gradle --version
require_command /opt/taixu/bin/cmake --version
require_command /opt/taixu/bin/ninja --version
require_property 'org.gradle.daemon=false'
require_property 'org.gradle.parallel=false'
require_property 'org.gradle.workers.max=2'
grep -Fq 'org.gradle.jvmargs=-Xmx1024m' /root/.gradle/gradle.properties ||
    fail 'missing Gradle property: org.gradle.jvmargs=-Xmx1024m...'
require_executable /opt/taixu/bin/flutter
/opt/taixu/bin/flutter --version >/dev/null 2>&1 || true
# Android-only policy: web/desktop/iOS artifacts are intentionally not required.
require_directory /opt/flutter/bin/cache/artifacts/engine/android-arm64-release
require_directory /opt/flutter/bin/cache/artifacts/engine/android-arm64-profile
if [ -f /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot ]; then
    require_executable /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot
    require_aarch64 /opt/flutter/bin/cache/artifacts/engine/linux-arm64/gen_snapshot
fi
if [ -f /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot ]; then
    require_executable /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot
    require_aarch64 /opt/flutter/bin/cache/artifacts/engine/android-arm64-release/linux-arm64/gen_snapshot
fi

if [ -f /opt/taixu/bin/rg ]; then
    require_executable /opt/taixu/bin/rg
    require_aarch64 /opt/taixu/bin/rg
    require_command /opt/taixu/bin/rg --version
fi

if [ -f /opt/taixu/bin/jadx ]; then
    require_executable /opt/taixu/bin/jadx
    require_command /opt/taixu/bin/jadx --version
fi

if [ -f /opt/taixu/bin/apktool ]; then
    require_executable /opt/taixu/bin/apktool
    require_file /opt/taixu/tools/android-suite-offline/lib/apktool.jar
    require_command /opt/taixu/bin/apktool --version
fi

if [ -f /opt/taixu/bin/d2j-dex2jar ]; then
    require_executable /opt/taixu/bin/d2j-dex2jar
fi

if [ -f /opt/taixu/bin/rustc ]; then
    require_executable /opt/taixu/bin/rustc
    require_aarch64 /opt/taixu/bin/rustc
    require_command /opt/taixu/bin/rustc --version
    if [ -f /opt/taixu/bin/cargo ]; then
        require_executable /opt/taixu/bin/cargo
        require_command /opt/taixu/bin/cargo --version
    fi
fi

if [ -f /opt/taixu/bin/apksigner ]; then
    require_executable /opt/taixu/bin/apksigner
fi
if [ -f /opt/taixu/bin/zipalign ]; then
    require_executable /opt/taixu/bin/zipalign
    require_aarch64 /opt/taixu/bin/zipalign
fi

echo "TaiXu Android ARM64 offline suite is ready"
