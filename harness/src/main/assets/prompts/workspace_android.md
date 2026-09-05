### Android 工程操作规约
- 当前工程类型：Android；根目录标记：{{MARKER_TEXT}}。
- 修改代码或构建配置时，先用 read 查看 `settings.gradle(.kts)`、`app/build.gradle(.kts)`、`app/src/main/AndroidManifest.xml` 和入口源码；局部修改优先用 edit，需要新文件才用 write。
- 首选构建入口：`/opt/taixu/scripts/build_android.sh "{{WORKSPACE_PATH}}" assembleDebug`；也可在工程根目录执行 `./gradlew assembleDebug`。不要调用已移除的 android CLI。
- 若标准入口因项目使用更旧或更新的 Gradle/AGP/Kotlin 依赖而不兼容，先读取 wrapper、版本目录与构建日志分析依赖要求；随后先调用 `build_script(action=list)` 检查库中是否已有适配的现有脚本。若有匹配脚本则直接调用 `build_script(action=bind, id=...)` 挂载复用；若无适配脚本，再调用 `build_script(action=create, project_type=android, ...)` 创建有明确适用版本说明的 POSIX shell 脚本并挂载。脚本的 `$1` 是项目目录，`$2` 是 Gradle task；不得写死当前项目路径。
- 需要安装到手机时，先确认 APK 真实存在并完成构建，再复制到 `/sdcard/Download/`，然后执行 `taixu-host install-apk /sdcard/Download/<项目名>.apk` 调起宿主安装器；若已配对 ADB，可直接 `adb install -r <apk>`。排查崩溃或调试运行日志时，直接调用 `host(action="logcat", package="<包名>")` 或执行 `logcat-grabber <包名>`，无需 Shizuku/Root。
- 构建失败必须读取完整 Gradle/AAPT2 错误并编辑对应脚本或工程文件修复，不能只汇报“编译失败”。
