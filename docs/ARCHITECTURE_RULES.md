# 🛡️ 太墟 (TaiXu) — 架构铁律与 AI 编码避坑指南 (Architecture & UX/UI Rules)

---

## 1. 纯粹模型层隔离 (Model Layer Purity)

- `:core:model` **严禁** 引入 `android.*`、`androidx.*` 或 Compose 依赖，必须保持 Pure Kotlin。
- `architectureCheck` 已接入 `app:preBuild` 验证阶段，会主动阻止模型层平台化、非法 feature 横向依赖和业务层直连 DAO。

---

## 2. 持久化与偏好边界 (Persistence & Preference Boundaries)

- 业务层（feature / runtime / harness）**只能依赖** `core/database/PersistenceRepositories.kt` 暴露的 Repository 接口，严禁直接注入或操作 Room DAO。
- 偏好设置按领域统一通过 `core/datastore/PreferenceFacades.kt` 提供的领域分面接口读取或写入，严禁随意引入私人键值。

---

## 3. 🎨 M3 Expressive 与 UX/UI 设计系统与前端架构铁律

1. **统一设计系统组件（严禁 raw Material 组件散落）**：
   - 容器必须使用 `top.wkbin.taixu.ui.components.RuntimeCard`（支持 `containerColor`, `borderColor`, `contentPadding`, `onClick`），**严禁**使用原生 `androidx.compose.material3.Card` 或自定义 `Modifier.border/background` 拼接容器，确保在**玄同（标准 M3）**与**澄明（液态玻璃）**双主题下毛玻璃、折射与微边框自适应生效。
   - 按钮统一使用 `RuntimeButton`, `RuntimeFilledTonalButton`, `RuntimeOutlinedButton`, `RuntimeTextButton`, `RuntimeIconButton`。
   - 弹窗统一使用 `top.wkbin.taixu.ui.components.RuntimeAlertDialog`。
   - 开关与进度指示使用 `RuntimeSwitch`, `RuntimeLinearProgressIndicator`, `RuntimeCircularProgressIndicator`。
2. **弹窗与表单软键盘安全（IME 防遮挡）**：
   - 所有包含 `OutlinedTextField` 或列表的 Dialog `text` 内容区域，**严禁**设置死锁的固定高宽或外层硬编码 `offset`，**必须**包裹 `modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())`，确保在移动端软键盘弹起时表单可顺畅滑动，操作按钮始终可见可达。
3. **顶部与底部导航栏规范**：
   - 顶部导航统一使用 `RuntimeTopBar`（支持 `title`, `statusText`, `onBack`）。
   - 底部导航统一使用基于 M3 原生 NavigationBar 的 `RuntimeBottomBar`。
4. **响应式与小屏防截断/防折行**：
   - 消息气泡（如 `UserBubble`）必须采用响应式宽度（`Modifier.widthIn(max = 560.dp)`），避免在平板/折叠屏/大屏上被限制过窄。
   - 单行文本、路径或标签（如文件路径、短语标题、状态标签）必须显式声明 `maxLines = 1, overflow = TextOverflow.Ellipsis`，严禁长字符串将同行的操作按钮挤出屏幕。
   - 横向多按钮行（如操作栏、卡片底部动作）必须配置适当紧凑的 `contentPadding` 与 `labelSmall` 单行字号，杜绝 360dp 窄屏宽度下文字断行造成按钮高度参差不齐。
5. **层级与多层材质防冲突**：
   - 在 `RuntimeCard` 内嵌的日志区、终端代码块或次级子项中，应使用 `Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest/Highest)`，避免多层嵌套 Card 导致液态玻璃与阴影层级计算异常。

---

## 4. 安全与网络策略 (Security & Network Policy)

- 本地模型（`LOCAL` 分组）允许 `http://127.0.0.1:*` 或 `http://localhost:*`，且 `apiKeyOptional = true`。
- 外部模型（`OFFICIAL` / `CHINA` / `AGGREGATOR`）强制要求 HTTPS。

---

## 5. PRoot 挂载与命令执行安全 (PRoot Sandbox Security)

- 挂载路径必须经过 `ProotCommandBuilder` 正规化处理，避免 Shell 注入与危险的系统根目录穿越。
- `base` 只用于有界前台命令；默认 10 分钟，可由用户配置为 1–60 分钟，也可用 `timeout_seconds` 对单次调用覆盖 1–3600 秒。
- 不要在 `base` 中用 `nohup` / `setsid` / `&` 模拟守护进程。长任务必须调用 `process(action="start", ...)`，后续用 `status` / `logs` / `list` / `stop` 管理。
- `process` 的外部 ID 会加 `agent-process:` 命名空间，禁止与工具中心服务进程混用。

---

## 6. 移动端 Gradle 资源策略 (Mobile Sandbox Toolchain Strategy)

- 沙箱 Android/Flutter 构建固定 `--no-daemon --max-workers=2`，全局配置保持 `org.gradle.daemon=false`、`org.gradle.parallel=false`、`org.gradle.workers.max=2`。
- NDK 只允许由 init policy 的 `android.ndkPath` 注入。构建脚本可清理旧 `local.properties` 的 `ndk.dir`，不得同时重新写入 `ndk.dir` 或在项目中再声明 `ndkPath`，否则 AGP 会报双 locator 冲突。

---

## 7. 工作区与插件安全 (Workspace & Plugins Safety)

- ZIP 导入必须使用 `WorkspaceManager.importProjectArchive()`，禁止直接解压不受信路径；Git HTTP(S) 与 SSH URL 必须按用户选择的 transport 分别校验。
- 导入类型写入 `.taixu-project.properties`，不能只靠文件扩展名或目录内容重新猜测用户选择。
- 同一插件 ID + version 已存在时返回“已导入”，不得递归覆盖现有 payload；安装框架应先保证 `$toolDir/bin` 存在。

---

## 8. 无线 ADB 与 PRoot 守护进程约束 (Wireless ADB & PRoot Daemon Strategy)

- **PRoot ADB 进程隔离**：PRoot 沙箱内无持久 init/systemd 守护进程，后台 `adb fork-server` 无法跨独立命令存活，每次调用后都会销毁。命令行 ADB 操作需在单条执行中完成 `connect + 操作`（沙箱 `/opt/taixu/bin/adb` 封装脚本已内置自动 connect），或优先通过 `HostBridge` (`logcat-grabber` / `taixu-host logcat`) 经由宿主常驻 Kadb 连接。
- **日志抓取免特权**：抓取日志（logcat）通过内置无线 ADB 协议与 HostBridge 桥接，**完全不依赖 Shizuku 或 Root 权限**。AI Agent 在遇到抓取日志/排查崩溃任务时，应直接使用 `host(action="logcat", ...)` 或 `logcat-grabber <包名> [-P 端口]`，严禁要求用户先开启 Shizuku/Root 或做多余权限排查。

