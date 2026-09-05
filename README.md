<p align="center">
  <img src="app/src/main/res/drawable/taixu_logo.webp" width="96" alt="太墟 Logo" />
</p>

<h1 align="center">太墟 · TaiXu</h1>

<p align="center"><strong>掌中归墟，万象可期。</strong></p>

<p align="center">
  Android 无 Root Linux Runtime · 智能体引擎 (Agent Harness) · 原生 PTY 终端 · 移动开发工作区 · 无线 ADB 诊断
</p>

<p align="center">
  <code>v0.11.0</code> · <code>Android 10+ (SDK 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin 2.4</code> · <code>Jetpack Compose</code>
</p>

<p align="center">
  <strong>简体中文</strong> · <a href="README_EN.md">English</a>
</p>

---

## 🌌 何为太墟

《列子·汤问》云：“渤海之东……其中有大壑焉，实惟无底之谷，其下无底，名曰归墟。八纮九野之水，天汉之流，莫不注之，而无增无减焉。”

**太墟**取意于此：在 Android 严格受限的应用沙盒与权限边界内，构筑一方**可运行、可观测、可自愈、可演进**的 Linux 世界。

它不是给 LLM 聊天简单套壳，也不是玩具式的终端模拟器。它让大模型、MCP 工具、Linux 系统、原生 PTY 终端与项目工作区共享同构的执行上下文与因果链——使一句自然语言意图，能够落成物理文件、真实进程、经过验证的代码与可交付的 Android / Flutter 构建产物。

> 于太墟中立极，于方寸间创世。

```text
人的意图 (Intent) ─→ 计划拆解 (TaskPlan) ─→ 工具 / MCP / Linux / 浏览器 ─→ 结果验证 (Verification)
                             ↑                                                  ↓
                             └────────────────── 未完成则继续推进 ──────────────┘
```

---

## ⚡ 核心能力全景

| 领域 | 核心能力与技术实现 |
| :--- | :--- |
| **Linux 沙箱** | 基于 **PRoot** 用户态运行 10 种 ARM64 发行版（Ubuntu 24.04 / Debian 12 / Kali / Arch / Fedora / Alpine 等）；通过 OCI Registry 拉取并校验 RootFS；支持多系统无缝切换、两阶段提交回滚、持久化目录绑定与 Android 共享存储挂载。 |
| **Agent 智能体引擎** | 深度兼容 **OpenAI 兼容接口** 与 **Anthropic Messages API**；支持流式 SSE、思考链（`reasoning_content` / DeepSeek / Claude）、任务拆解进度卡（TaskPlanCard）、结构化分级工具审批与多厂商中转站配置。 |
| **对话回退与快照** | 基于 **SessionFork 会话树派生** 实现一键「撤回到此轮」（Rewind）；配套每轮对话前的 **Checkpoints 文件快照安全网** 并磁盘持久化，重大代码重构与指令随时可安全回滚。 |
| **语义记忆与子智能体** | Agent 记忆语义模型（冲突消解、版本 revision、置顶 pinned、新鲜度 recency）；支持多子智能体协同调度（文件写租约波式调度、结构化 facts pack 回传与超限分页落盘）。 |
| **原生 PTY 终端** | JNI `openpty`/`forkpty` 底层桥接（`libtaixu_pty.so`），提供真实 Linux 进程生命周期、控制终端、ANSI/VT100 增量解析、触觉反馈按键条与多会话后台持久化；原生不可用时自动回退至 `script` PTY。 |
| **内置浏览器与 CDP 调试** | 内置 WebView 多 Tab 池与 In-process 浏览器 MCP 服务；支持页面脚本**注入式 Hook 引擎**、**CDP 断点**与 **Worker 级 Fetch 拦截**，提供可视化的网络请求时间线面板与调试状态横幅。 |
| **无线 ADB 与日志工作台** | 独立常驻一级工作台入口；支持**通知栏免切屏输入配对码**秒级配对无线 ADB、mDNS 局域网调试服务自动发现、PRoot / Android 双端日志实时抓取、设备状态一键体检与系统 Intent 诊断。 |
| **移动工作区与构建** | 支持创建空项目、本地 ZIP 导入（防 Zip Slip 校验）与 **GitHub 仓库导入（带实时 clone 进度）**；提供代码浏览、可视化行级 Diff 比对、沙箱内 Gradle / Flutter 后台静默构建与 APK 签名安装。 |
| **工具生态与 MCP** | 离线插件包一键导入、内置/签名 Registry、Recipe 事务安装与依赖管理；集成 **Open-WebSearch** 联网搜索、CodeGraph 代码知识图谱，全面支持 Stdio / SSE / Streamable HTTP 协议 MCP 服务。 |
| **端侧协作与全局助手** | **智枢全局桌面悬浮小窗**（支持跨应用前台协作）、局域网 WebChat、内置 FTP 文件传输服务、沙箱内 `llama.cpp` 本地 GGUF 离线运行，以及 FGS 唤醒锁与 Wi-Fi 锁后台保活。 |

---

## 🚀 快速上手

1. **安装运行**：在 ARM64、Android 10+（推荐 Android 12+）设备上安装 [最新 Release APK](https://github.com/wkbin/taixu/releases)。
2. **就绪沙箱**：首次进入跟随「启程向导」选择 Linux 发行版（如 Ubuntu 24.04），联网完成 RootFS 初始化。
3. **配置模型**：在设置中配置你的 API Key（支持 DeepSeek、Claude、OpenAI、SiliconFlow 等），或在沙箱内部署 `llama.cpp` 本地模型。
4. **开启工作区**：在工坊中创建新工程，或从 GitHub / 本地 ZIP 导入已有项目。
5. **意图驱动开发**：向智枢描述你的工程目标；智枢将自主规划步骤、调用 Linux/MCP 工具、读写代码、运行构建并验证结果。

> 💡 **提示**：RootFS 镜像不内置于 APK 包体中。首次初始化需要网络连接与存储空间；长任务后台构建建议开启前台服务与系统电池优化白名单。

---

## 🛠️ 从源码构建

### 环境要求

- **JDK**：Java 17 或 Java 21（推荐 Android Studio JBR）
- **Android SDK**：compileSdk 37 / targetSdk 37 / minSdk 29
- **Android NDK**：`30.0.15729638`
- **CMake**：`3.22.1`
- **构建系统**：Gradle 9.7.0 / AGP 9.3.1 / Kotlin 2.4.10

### 构建步骤

```powershell
# 1. 首次检出仓库后，准备 PRoot ARM64 原生运行时预编译包
.\tools\prepare-proot-runtime.ps1

# 2. 配置 JDK 路径并执行架构合规检查与单元测试
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat architectureCheck --console=plain
.\gradlew.bat testDebugUnitTest --console=plain

# 3. 编译 Debug APK
.\gradlew.bat assembleDebug --console=plain
```

构建产物位于：`app/build/outputs/apk/debug/taixu-v0.11.0-debug.apk`

> 📦 **TaiXuDev 双包共存构建**：在 CI 或本地设置环境变量 `$env:TAIXU_DEV_BUILD="1"`，可产出包名为 `top.wkbin.taixu.dev` 的独立预览版，与正式版完全共存互不干扰。

---

## 📐 模块拓扑一览

```text
LinuxAIRuntime/
├── app/                  # 宿主壳工程：MainActivity、Hilt 装配、JNI C 代码、前台保活 Service
├── core/
│   ├── model/           # 纯 Kotlin 数据模型 (Pure Kotlin，严禁平台依赖)
│   ├── common/          # 协程调度器、日志组件、全局导航总线
│   ├── database/        # Room 数据库：Harness 会话、消息流、Checkpoints 快照、执行历史
│   ├── datastore/       # Jetpack DataStore：用户偏好、存储挂载、外观、引导状态
│   ├── network/         # OkHttp 客户端、SSE 流式解析器、网络策略
│   └── security/        # API Key 安全存储与硬件加密
├── runtime/              # Linux 沙箱核心：PRoot 挂载、进程生命周期托管、PTY 会话、工作区构建
│   └── browser/         # 内置浏览器：WebView 池、Hook 引擎、CDP 断点、时间线与 Browser MCP
├── project-template/     # 标准化项目模板：Manifest 解析、动态表单协议与项目物化引擎
├── harness/              # 智能体引擎：Agent 循环、流式推理、工具调度、子智能体、MCP、回退
├── tools/                # 生态中心：插件 Registry、Recipe 安装事务、依赖解析、Provider 仓储
└── feature/              # Jetpack Compose 业务特性层
    ├── components/      # 太墟 M3 Expressive 设计规范、Lucide 图标、Spotlight 引导、通用卡片
    ├── theme/           # 主题风格调色板（玄统 / 澄明壁纸背景）
    ├── home/            # 首页仪表盘：系统状态、内存/磁盘/进程实时监控与快速体检
    ├── chat/            # 智枢对话界面：任务拆解卡、代码 Diff 预览、桌面悬浮小窗
    ├── terminal/        # 原生终端 UI、触觉反馈按键条与多会话切换
    ├── workspace/       # 工坊项目管理、代码树浏览、构建日志与 APK 交付
    ├── settings/        # 设置中心：模型档案、ADB 调试工作台、插件管理、重看引导
    ├── developer/       # 开发者沙箱与底层诊断面板
    └── onboarding/      # 首次启动引导与 RootFS 初始化流程
```

更多详细设计与调用链请参阅：
- 🧭 [AI 语义导航总览](docs/AI_NAVIGATION.md)
- 🏗️ [系统架构与模块拓扑](docs/ARCHITECTURE.md)
- ⏱️ [核心执行调用链与时序](docs/EXECUTION_TRACES.md)
- 📜 [架构避坑与 UI 设计铁律](docs/ARCHITECTURE_RULES.md)
- 🌐 [内置浏览器与 Hook 引擎设计](docs/BROWSER_DESIGN.md)
- 🔌 [插件生态开发指南](docs/PLUGIN_DEVELOPMENT_GUIDELINES.md)

---

## ⚠️ 边界与已知限制

- **ABI 架构**：当前仅适配 `arm64-v8a` 架构；其他架构设备将在初始化阶段拦截并终止。
- **用户态沙箱**：PRoot 是基于 `ptrace` 的用户态系统调用拦截与路径重写机制，不是硬件虚拟化或完整 KVM 虚拟机，不提供 Root 特权或底层内核模块加载能力。
- **环境兼容性**：复杂 TUI（如部分全屏 curses 应用）、特定软键盘组合键及重型 C/C++ 交叉编译仍需依据具体 ARM64 设备性能与内存情况调优。
- **网络安全**：模型 API 及远程下载端点强制遵循安全传输协议；请妥善保管私有 API Key 与凭据，切勿提交至公开代码库。

---

## 📜 注脚

> 须弥纳于芥子，太墟纳于掌中。

限制从未真正消失；但自由可以来自身处限制之中，仍有能力去构筑、去验证属于自己的世界。

欢迎提交 Issue、Pull Request 或分享你的真机使用记录！
