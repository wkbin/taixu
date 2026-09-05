<p align="center">
  <img src="app/src/main/res/drawable/taixu_logo.webp" width="96" alt="TaiXu Logo" />
</p>

<h1 align="center">TaiXu · 太墟</h1>

<p align="center"><strong>The Myriad Manifestations in the Great Void.</strong></p>

<p align="center">
  Android No-Root Linux Runtime · Native Agent Harness · PTY Terminal · Mobile Dev Workspace · Wireless ADB Diagnostics
</p>

<p align="center">
  <code>v0.11.0</code> · <code>Android 10+ (SDK 29+)</code> · <code>arm64-v8a</code> · <code>Kotlin 2.4</code> · <code>Jetpack Compose</code>
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <strong>English</strong>
</p>

---

## 🌌 Prologue: What is TaiXu

In *Liezi: Questions of Tang*, it is written:

> To the east of the Bohai Sea... there is a vast ravine, indeed a bottomless valley. Its depths are unfathomable, and it is called GuiXu (The Return to the Void). The waters of the eight horizons and nine heavens, the flow of the celestial river, all pour into it, yet it neither increases nor diminishes.

**TaiXu (太墟)** borrows its name and spirit from this: within the strictly sandboxed boundaries of Android, it builds a **runnable, observable, self-healing, and continuously evolving** Linux world.

It is neither a superficial chat wrapper nor a toy terminal emulator. It allows LLMs, MCP tools, the Linux user space, native PTY terminals, and project workspaces to share an isomorphic execution context and causality chain—turning natural language intent into verifiable files, living processes, validated code, and deliverable Android / Flutter build artifacts.

> Establish the pole in the Great Void; create the world within an inch.

```text
Human Intent ─→ Task Decomposition (TaskPlan) ─→ Tools / MCP / Linux / Browser ─→ Verification
                           ↑                                                            ↓
                           └────────────────── Continue if unfinished ──────────────────┘
```

---

## ⚡ Full Capabilities Matrix

| Domain | Core Capabilities & Technical Highlights |
| :--- | :--- |
| **Linux Sandbox** | Runs 10 ARM64 Linux distributions (Ubuntu 24.04, Debian 12, Kali, Arch, Fedora, Alpine, etc.) in user space via **PRoot**; OCI Registry layer pulling & SHA-256 verification; two-phase commit rollback, persistent bindings, and Android shared storage mounting. |
| **Agent Harness** | Compatible with **OpenAI standards** and **Anthropic Messages API**; SSE streaming, chain-of-thought (`reasoning_content` / DeepSeek / Claude), task plan progress cards (TaskPlanCard), and multi-tier tool approval workflows. |
| **Dialogue Rewind & Checkpoints** | One-click **Dialogue Rewind** based on **SessionFork tree branching**; automatic disk-persisted **file snapshots (Checkpoints)** before each conversational turn for instant rollback of risky refactoring. |
| **Semantic Memory & Subagents** | Semantic memory model (conflict resolution, revisions, pinned items, recency scoring); multi-subagent coordination with **write-lease wave scheduling**, structured facts pack aggregation, and pagination spill-over protection. |
| **Native PTY Terminal** | Low-level JNI `openpty`/`forkpty` bridge (`libtaixu_pty.so`) with real process lifecycles, control terminals, ANSI/VT100 state machines, haptic keybars, and multi-session persistence; fallback to `script` PTY if needed. |
| **In-App Browser & CDP DevTools** | Integrated WebView multi-tab pool and in-process Browser MCP; script-based **injection Hook engine**, **CDP breakpoints**, **Worker-level Fetch interception**, and visual Network Timeline inspector. |
| **Wireless ADB & Diagnostics** | Dedicated first-tier **Wireless ADB Workbench**; **notification bar pairing code input** without leaving current screen, mDNS local discovery, unified PRoot / Android logcat streaming, and one-click hardware health checks. |
| **Workspaces & Build Engine** | Empty project creation, ZIP import (with Zip Slip security validation), and **GitHub repo import with live clone progress**; code browsing, line-level visual Diff, background Gradle / Flutter compilation, and APK signing. |
| **Tool Ecosystem & MCP** | Offline plugin package imports, built-in/signed Registry, recipe transaction manager, and dependency resolution; built-in **Open-WebSearch**, CodeGraph knowledge graph, and full Stdio / SSE / Streamable HTTP MCP support. |
| **End-Side Collaboration & Floating UI** | **Global desktop floating window** for cross-app multitasking, local LAN WebChat, built-in FTP file server, offline `llama.cpp` GGUF execution, and FGS wake/Wi-Fi lock background persistence. |

---

## 🚀 Quick Start

1. **Install & Launch**: Download and install the [Latest Release APK](https://github.com/wkbin/taixu/releases) on an ARM64 Android 10+ device (Android 12+ recommended).
2. **Initialize Sandbox**: Follow the Onboarding Wizard to select a distribution (e.g., Ubuntu 24.04) and complete the initial RootFS setup over network.
3. **Configure Model**: Add your LLM API Key (DeepSeek, Claude, OpenAI, SiliconFlow, etc.) in Settings, or run local GGUF models via `llama.cpp` in the sandbox.
4. **Open Workspace**: Create a project in the Workshop or import an existing repository from GitHub / local ZIP.
5. **Intent-Driven Engineering**: Describe your goal to the Agent; it will autonomously plan steps, invoke Linux/MCP tools, edit code, run builds, and verify outcomes.

---

## 🛠️ Build from Source

### Requirements

- **JDK**: Java 17 or Java 21 (Android Studio JBR recommended)
- **Android SDK**: compileSdk 37 / targetSdk 37 / minSdk 29
- **Android NDK**: `30.0.15729638`
- **CMake**: `3.22.1`
- **Build System**: Gradle 9.7.0 / AGP 9.3.1 / Kotlin 2.4.10

### Build Commands

```powershell
# 1. Prepare PRoot ARM64 native runtime binaries
.\tools\prepare-proot-runtime.ps1

# 2. Set JAVA_HOME and run architecture compliance check & unit tests
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat architectureCheck --console=plain
.\gradlew.bat testDebugUnitTest --console=plain

# 3. Assemble Debug APK
.\gradlew.bat assembleDebug --console=plain
```

Debug APK output: `app/build/outputs/apk/debug/taixu-v0.11.0-debug.apk`

> 📦 **TaiXuDev Dual-Package Build**: Set `$env:TAIXU_DEV_BUILD="1"` in CI or local environment to build the preview APK `top.wkbin.taixu.dev`, which can be installed side-by-side with the release package.

---

## 📐 Architecture Topology

```text
LinuxAIRuntime/
├── app/                  # Host application: MainActivity, Hilt DI, JNI C code, Foreground Service
├── core/
│   ├── model/           # Pure Kotlin data models (Strictly decoupled from Android SDK)
│   ├── common/          # Coroutine dispatchers, logging, Global Navigation Bus
│   ├── database/        # Room Database: Sessions, message streams, Checkpoints, tool history
│   ├── datastore/       # Jetpack DataStore: Preferences, mounts, themes, onboarding state
│   ├── network/         # OkHttp client, SSE streaming parser, security policies
│   └── security/        # API Key secure encryption storage
├── runtime/              # Linux sandbox core: PRoot bindings, process registry, PTY sessions, build engine
│   └── browser/         # In-App Browser: WebView pool, Hook engine, CDP breakpoints, Browser MCP
├── project-template/     # Project template manifest parser, dynamic variable forms & materializer
├── harness/              # Agent engine: Agent loop, streaming inference, tool dispatcher, subagents, MCP, rewind
├── tools/                # Ecosystem: Plugin Registry, Recipe transaction manager, Provider repositories
└── feature/              # Jetpack Compose UI modules
    ├── components/      # Material 3 Expressive design system, Lucide icons, Spotlight Guide overlays
    ├── theme/           # Theme palettes & wallpaper background support
    ├── home/            # Runtime dashboard, system resource monitors & hardware health check
    ├── chat/            # Agent conversation UI: Task decomposition cards, Diff view, floating window
    ├── terminal/        # Native terminal UI, haptic keybars & multi-session management
    ├── workspace/       # Workshop project manager, code tree browser, build logs & APK installer
    ├── settings/        # Settings center: Model profiles, ADB workbench, plugin manager
    ├── developer/       # Developer sandbox & low-level diagnostic panels
    └── onboarding/      # First-launch onboarding wizard & RootFS setup flow
```

---

## ⚠️ Boundaries & Known Limitations

- **Architecture**: Officially supports `arm64-v8a` only. Other ABIs will halt during initialization.
- **User-Space PRoot**: PRoot intercepts system calls via `ptrace`. It is not hardware virtualization or a KVM instance and does not provide Root privileges or custom kernel modules.
- **Compatibility**: Complex TUIs and heavy compilation tasks depend on device RAM and CPU performance.
- **Security**: Remote APIs and download endpoints require HTTPS; never commit sensitive API keys or credentials to public repositories.

---

## 📜 Philosophical Footnote

> Mount Sumeru is contained within a mustard seed; the Great Void is contained within the palm.

Constraints never truly disappear; but the freedom that engineering provides often comes from understanding boundaries, establishing laws within them, and possessing the ability to construct our own world.

Welcome to submit Issues, Pull Requests, or share your real-device experiences!

