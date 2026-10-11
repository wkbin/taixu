# 凭证与暴露面清单 (Security Surface)

> 本文逐条说明权限边界、凭证归属与暴露面，核验当前实现并标注代码出处；代码演进后请随改动更新。
>
> **一句话定性：PRoot 沙箱是 ptrace 系统调用虚拟化的兼容层，不是防恶意代码的安全边界。** guest 内进程实际以 App uid 运行；真正的安全边界是 Android 权限模型 + `ApprovalPolicyEngine` 审批门控 + 用户显式授权。对外文案应避免「安全沙箱」这类表述（另见 [`ADR_SANDBOX_BACKEND.md`](ADR_SANDBOX_BACKEND.md) 的选型依据）。

## 1. 权限域总览

| 权限域 | 实际身份 | 进入方式 | 能做什么 |
| --- | --- | --- | --- |
| App 进程 | Android app uid | — | 持有全部数据：密钥仓储（AndroidKeyStore 加密）、Room、设置、工作区 |
| PRoot guest | 名义 root，实际 App uid | ptrace 虚拟化（`--change-id=0:0`） | rootfs 内一切 + 第 3 节挂载清单；网络命名空间与 App 共享 |
| Shizuku | shell uid | `PrivilegeManager.executeShellCommand`（SHIZUKU 模式） | 宿主 shell 级命令 |
| Root | root uid | 同上（ROOT 模式，`su -c`） | 宿主任意命令 |
| 内置无线 ADB | shell uid（localhost adb） | `EmbeddedAdbManager`（HostBridge `/api/shell` 兜底） | 设备侧 shell、logcat、APK 安装 |

出处：`runtime/privilege/PrivilegeManager.kt` —— 模式判定与 `executeShellCommand`；PRoot 模式下宿主 shell 执行直接拒绝。

## 2. 密钥流：从存储到进程

### 2.1 Provider 密钥

- 存储于 `core/security/SecretManager.kt`（AndroidKeyStore 密钥加密持久化），读取后仅存在于内存数据结构，随请求头发出（`ProviderTransport`）。
- **不进入进程环境变量**：`RuntimePathManager.hostProcessEnvironment()` 只构造 `PROOT_L2S_DIR` / `PROOT_LOADER(_32)` / `TMPDIR` / `PROOT_TMP_DIR` / `LD_LIBRARY_PATH` 等路径变量（`RuntimePathManager.kt:229-244`）。
- 宿主侧子进程一律先 `environment().clear()` 再放白名单（`ProcessShellExecutor.kt:32`、`ProcessLinuxSession.kt:37`、`ProotInstaller.kt:68`）→ App 进程环境不扩散到 PRoot 与宿主辅助进程。
- 已知例外（记录在案，风险低）：`PrivilegeManager` Root 通道 `ProcessBuilder("su", "-c", …)` 与 `ShizukuHostUserService` 的 `ProcessBuilder` 未清空环境，继承 App / Shizuku 服务进程的标准 Android 环境变量。用户密钥不在进程环境里，故不构成密钥泄漏面；**若未来向 App env 注入敏感变量，必须先给这两处补 clear**。

### 2.2 进入沙箱的环境变量

- 固定基座（`EnvironmentResolver.baseEnvironment`）：`HOME` / `LANG` / `TMPDIR` / `PATH` / `TAIXU_BRIDGE_URL` / `TAIXU_BRIDGE_PORT` / `ANDROID_BIN_PATH` / `ANDROID_LIB_PATH` / `TERM` 等，无敏感项。
- 叠加通道：distro manifest 与 per-command / provider env（含 MCP STDIO 服务器配置的 env）→ `ProotCommandBuilder.shellCommand` 逐个 `export`（键名经 `ENVIRONMENT_KEY` 正则校验 + 单引号转义，`ProotCommandBuilder.kt:169-181`）。
- 含义：**用户在 MCP / 环境配置里写的变量（可能是第三方 API Key）会明文进入沙箱进程环境**。输出侧由 2.3 脱敏兜底；输入侧是用户配置责任，配置页宜有风险提示。

### 2.3 输出脱敏（`core/security/SecretRedactor.kt`）

- 识别范围：labelled secrets（password / secret / api_key / token / authorization / cookie…）、`Bearer xxx`、`sk-` / `AIza` / `xox` 品牌前缀、JSON 与脚本赋值中的密钥值、大陆手机号。
- privacyMode 下叠加「已知密钥值遮蔽」：以 `LinuxEnvironmentManager` 中用户配置的环境变量值作为已知 secret 列表（`ToolExecutor.kt:96-104`）。
- 已绑定的工具调用及子任务报告通过当前环境的 `outputSecrets` 获取已知值；本地后端固定发行版并在读取锁内复制快照，远端不会刷新手机当前发行版。子任务报告在保存到当前环境前完成同样的脱敏；刷新失败不会使用另一发行版的旧缓存。
- 应用点：ToolExecutor 工具结果后处理（4 处调用）、`RequestDiagnosticsStore` 请求预览、浏览器 `SecretRedactingInterceptor`。
- **局限（务必知道）**：正则启发式，不是密码学保证；无标签的随机串（如 HostBridge key 本身是 UUID hex）不在模式内；Base64 等编码变形与非赋值形态不保证命中。高危场景需叠加人工检查。
- 请求诊断仅将脱敏后的有界预览和字段指纹交给 `RequestDiagnosticsRepository`，用 `SecretManager` 的 AndroidKeyStore AES/GCM 加密后原子写入 `noBackupFilesDir/request-diagnostics/requests.enc`。不保存请求头、原始请求体或媒体载荷；加密失败不回退明文。普通对话和文件内容仍可能保留，脱敏不等于匿名化。会话删除等待加密存档清理完成；存档排除系统备份，并受大小及格式校验约束。

## 3. PRoot 挂载清单（沙箱可见的宿主路径）

默认绑定（`ProotCommandBuilder.build/buildInteractive` + `ProotMountLayout.hostSystemPaths`）：

| 宿主路径 | guest 视角 | 说明 |
| --- | --- | --- |
| rootfs 目录 | `/`（`-r`） | App 私有目录内 |
| `/dev` `/proc` `/sys` | 同名 | 宿主系统视图；`/proc` 内容以 App 权限可见范围为准 |
| `/system` `/system_ext` `/vendor` `/product` `/odm` `/apex` `/data/app` `/data/dalvik-cache` 等 | 同名 | 面向 Android 二进制执行的系统路径（存在且可读才绑定） |
| tmp 目录 | `/tmp` | App 私有 |
| workspace 目录 | `/workspace` | Agent 工作区 |
| home 目录 | `/root` | guest HOME |
| opt 目录（含 `.bridge-key`、`bin/taixu-host`、`bin/taixu-android-exec`） | `/opt/taixu` | 桥接脚本与 API 密钥 |
| attachments 目录 | `/attachments` | 会话附件 |
| link2symlink 后备存储 | 同名绝对路径 | `rootfs/.l2s` 硬链接代理 |
| 用户配置存储挂载 | `/sdcard/*` 等自定义 guest 路径 | `StorageMountBinding.validationError` 校验 + `require` 硬门槛，DB 被篡改也不放行越界绑定（`ProotCommandBuilder.kt:236-255`） |

要点：

- guest 内进程对上述挂载的读写权限等于 App 对这些目录的权限；rootfs 本身就在 App 私有目录里。
- guest「root」是 PRoot 虚拟身份，不能越过 Android 对 App uid 的限制；setuid 位不生效（`DistroConfigurator` 的 apt hook 主动清除 setuid，避免 dpkg 卡死）。

## 4. HostBridge（127.0.0.1:7980）

| 端点 | 认证 | 动作 | 暴露面 |
| --- | --- | --- | --- |
| `GET /api/health` | **免认证** | 特权模式、Shizuku/Root/ADB 可用性、ADB 端口 | localhost 内信息泄露（低危）：沙箱内任意进程可探测权限状态 |
| `POST /api/install-apk` | Bearer | 解析路径 → 无线 ADB 静默安装 / 系统安装器 | 持 key 即可安装任意可达的 APK |
| `POST /api/shell` | Bearer | Shizuku/Root 优先，否则无线 ADB | **持 key = 在宿主执行任意 shell（shell uid 或 root）** |
| `POST /api/logcat` | Bearer | 抓取 / 清除日志 | 日志可能含其他应用的敏感信息 |

- 密钥：`HostBridge` 构造时生成 UUID，写入沙箱 `/opt/taixu/.bridge-key`（`DistroConfigurator.installHostBridgeScripts`），每次配置刷新以保证与 App 重启后同步。
- **信任模型必须写明**：key 放在沙箱可读位置，等于设计上把「沙箱内代码」视为可触发宿主特权动作的半信任方。被攻陷的沙箱内容物拿到 key 后即可在宿主侧执行 shell（受当时生效的 Shizuku/Root/ADB 状态限制）。这是功能需要而非疏漏；**「在沙箱内运行不受信代码」的场景必须先切换到 PRoot 模式或收紧桥接授权**。
- `resolveSandboxPath` 兜底分支（`HostBridge.kt:398-425`）：路径非 `/sdcard`、`/workspace`、`/attachments` 前缀时先在工作区试一次，否则**原样返回宿主绝对路径**——`install-apk` 因此可读取任意宿主路径下的 `.apk`（有后缀校验，无目录白名单）；另有工作区全树按文件名的模糊兜底扫描（maxDepth 8）。
- 日志只记录路径与结果，不记录 key（`HostBridge.kt` 各 `logger.i`）。

## 5. 工具审批与特权执行链

- `ApprovalPolicyEngine`：风险矩阵决定审批；`use_capability` 的 list / inspect / decline 为只读元操作免审；`use_capability(call)` 从 arguments 合成 `(server, tool)` 后套同一矩阵（`ApprovalPolicyEngine.kt:82-89, 305-331`）。
- PLAN 模式：宿主侧硬拦截（`ToolExecutor → ApprovalPolicyEngine.planBlock`），提示词约束只是第二层。
- 路径边界：`HarnessPathResolver` 与 Lane 写租约约束写入范围；`ToolCheckpoint` 只能观察 / 否决 / 追加脱敏说明，不能授予权限或替换结果状态（`docs/ARCHITECTURE.md`）。
- 工具入口：主回合与 Lane 先检查工具名称，再解析并校验参数；未知名称不能借映射回退执行命令。Lane 对结构化写工具按执行器的参数视图检查租约，覆盖路径别名与包装参数；`base` 的任意 shell 写入仍不属于结构化路径租约检查范围。
- 取消：`PrivilegeManager.cancelShellCommand` 同步终止对应 Shizuku/Root 宿主子进程。
- MCP：工具 schema 不进 provider 可见面（`use_capability` 统一代理 + 护栏测试 `BuiltinToolContractTest`）；STDIO 服务器进程在 PRoot 沙箱内启动（`McpStdioTransport` → `McpStdioChannelFactory`），其 env 走 2.2 的 provider 通道。

## 6. codemode 脚本执行面（use_capability action=script）

模型可写一段 JS（上限 32 KiB）经 `CapabilityScriptRunner` 在 App 进程内执行，脚本通过全局 `capability.call/inspect/list` 调用能力域。暴露面逐条：

- **Java 互操作全禁**：Rhino `ClassShutter` 对所有类名返回 false——脚本无法触达 `java.*`、反射或宿主类路径（由 `CapabilityScriptRunnerTest` 锁定）。
- **无系统 API**：脚本环境不暴露文件、网络、进程句柄；唯一出口是 capability 绑定。
- **非免审通道**：每条 `capability.call` 经 `ScriptCapabilityDispatcher` 校验后重入 `ToolExecutor.execute`，执行同一 PLAN、审批、检查点及脱敏链路；MCP 首次调用先发现工具，使注解升级参与审批。未注入受控入口的路由器拒绝脚本执行。
- **审批与恢复**：首次遇到待审批调用即停止脚本，父结果保留 `awaitingApproval` / `approvalDeferred`。审批请求绑定父调用 ID，但参数仅包含当前 `call` 及内部来源标记；批准后只执行这一条调用，模型根据结果继续剩余任务，不重放脚本及已完成副作用。后台 Lane 不创建审批 UI。
- **资源熔断与取消**：解释模式（optimizationLevel=-1，Android dex 限制）+ 指令观察器检查父 Job 和单调时钟期限（默认 60s、上限 300s）；内层调用继承父 Job，并受剩余时间预算约束，返回与结果序列化后再次核验期限。JS 的 try/catch 无法吞掉取消、超时或审批交接。结果正文 64 KiB 截断。
- **依赖**：Rhino 1.7.15（MPL-2.0，与 GPL-3.0 兼容，已记录于 libs.versions.toml）。
- **线程面**：内层调用以 `runBlocking` 占用单个 IO 线程至完成——长耗时宿主动作期间该线程不可复用（Dispatchers.IO 池 64 线程，可接受；记录在案）。

## 7. 风险登记与待办

| # | 事项 | 定级 | 状态 |
| --- | --- | --- | --- |
| 1 | PRoot 非安全边界的定性认知 | 认知 | 本文已明确；对外文案避免「安全沙箱」表述 |
| 2 | `/api/health` 免认证返回特权状态 | 低 | localhost 限制下可接受；如需收紧可复用 Bearer |
| 3 | `resolveSandboxPath` 兜底允许任意宿主绝对路径（限 `.apk`） | 低-中 | 记录在案；如需收紧可加目录白名单 |
| 4 | Root / Shizuku 通道未清空子进程环境 | 低 | App env 无用户密钥；若未来注入敏感 env 需先补 clear |
| 5 | `.bridge-key` 文件权限依赖默认 umask | 已修复 | 写入后显式 `Os.chmod(…, 0600)`（`DistroConfigurator.installHostBridgeScripts`，与脚本 0755 同一处理点） |
| 6 | SecretRedactor 启发式局限 | 提示 | 见 2.3；高危场景叠加人工检查 |
| 7 | 用户配置的 MCP / 环境变量明文进沙箱 | 提示 | 配置页宜加风险提示文案 |

## 维护约定

- 改动 `ProotCommandBuilder` / `ProotMountLayout` / `HostBridge` / `SecretRedactor` / `EnvironmentResolver` / `PrivilegeManager` / `CapabilityScriptRunner` 的 PR，请同步核对本文是否需要更新。
- 本文只描述事实与登记风险，不替代代码注释；修复某项时请更新第 7 节状态列。
