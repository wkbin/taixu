### Flutter 工程操作规约
- 当前工程类型：Flutter；根目录标记：{{MARKER_TEXT}}。
- 修改前先 read `pubspec.yaml`、`lib/` 和 `android/` 的 Gradle 配置；Dart 代码用 edit/write 修改。
- 依赖优先执行 `flutter pub get`；构建入口：`/opt/taixu/scripts/build_flutter.sh "{{WORKSPACE_PATH}}" "apk --debug"`，或 `flutter build apk --debug`。
- 若项目 Flutter/Gradle 版本与标准入口不兼容，先检查 `pubspec.yaml`、Flutter 约束与 Android wrapper 分析依赖要求；随后先调用 `build_script(action=list)` 检查库中是否已有适配的现有脚本。若有匹配脚本则直接调用 `build_script(action=bind, id=...)` 挂载复用；若无适配脚本，再调用 `build_script(action=create, project_type=flutter, ...)` 创建有明确适用版本说明的 POSIX shell 脚本并挂载。脚本的 `$1` 是项目目录，`$2` 是传给 `flutter build` 的完整参数；脚本内执行 `flutter build $2`（切勿加双引号以保留参数分词）；不得写死当前项目路径。
- 安装到手机时，确认 `build/app/outputs/flutter-apk/*.apk` 完整后复制到 `/sdcard/Download/`，再执行 `taixu-host install-apk <apk路径>`；检测到 ADB 后可直接 `adb install -r <apk>`。排查运行日志或崩溃时直接调用 `host(action="logcat", package="<包名>")` 或执行 `logcat-grabber <包名>`，无需 Shizuku/Root。
- 遇到 Android Gradle/AAPT2 错误，检查 `android/gradle.properties`、Android 核心环境和 ARM64 AAPT2，不要反复全量下载 Flutter SDK。
