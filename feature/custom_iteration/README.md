# 太墟（TaiXu）自定义迭代与 TaiXuDev 构建模块 (`feature:custom_iteration`)

本模块为 **太墟 · TaiXu** 量身打造的标准、无降级自迭代系统。让用户在手机上使用太墟的自主 AI Agent 进行太墟自身的二次开发、测试、云端 CI 构建，并向开源社区贡献代码。

---

## 一、模块架构与核心组件

```
taixu-custom-iteration-module/
├── build.gradle.kts                        # Gradle 多模块依赖配置（对齐太墟规范）
├── README.md                               # 完整模块架构与接入文档
├── templates/
│   └── workflows/
│       └── taixudev-build.yml              # GitHub Actions 自动化云端构建 CI 模板
└── src/
    ├── main/
    │   ├── assets/
    │   │   └── skills/
    │   │       └── taixu-custom-iteration/
    │   │           └── SKILL.md            # Agent 专有开发与 PR 规范 Skill
    │   └── java/top/wkbin/taixu/
    │       ├── iteration/engine/
    │       │   ├── CustomIterationBootstrap.kt   # 自举引擎（工作区初始化/引导 Prompt 生成）
    │       │   └── TaiXuDevBuildCoordinator.kt   # CI 调度与 APK 下载校验协调器
    │       └── ui/iteration/
    │           ├── CustomIterationScreen.kt      # Jetpack Compose UI 界面
    │           └── CustomIterationViewModel.kt   # Koin 状态管理 ViewModel
```

---

## 二、核心特性与设计规范（零降级）

1. **工作区物理隔离**：
   * 自动在 Linux 沙盒家目录创建隔离目录 `~/custom_taixu`，开发行为完全不破坏运行中的宿主系统。
2. **专属 Agent Skill 约束 (`SKILL.md`)**：
   * 指导 AI Agent 严格遵守太墟的多模块 Kotlin DSL 规范、Material3 Compose 规范、协程设计及凭证安全规则。
3. **GitHub Actions 云端 CI 构建 (`taixudev-build.yml`)**：
   * 免去手机端部署 Android SDK/NDK 的庞大体积开销；
   * 云端 Runner（`ubuntu-24.04` + JDK 17）自动编译，并通过 `gh run watch` / `gh run download` 将产物回传手机。
4. **双包共存（Dual-Flavor）**：
   * 编译产物包名自动重命名为 `top.wkbin.taixu.dev`，应用名显示为 `TaiXuDev`；
   * 测试版与手机中的正式版太墟共存运行、互不覆盖。
5. **开源 PR 交付闭环**：
   * 本地真机体验通过后，Agent 协助生成标准格式 PR 提交到 `wkbin/taixu:main`。

---

## 三、快速接入指南（集成到太墟主工程）

### 1. 注册 Gradle 子模块
在 `settings.gradle.kts` 中添加：
```kotlin
include(":feature:custom_iteration")
```

### 2. 添加导航路由
在 `TaiXuNavHost.kt` 中添加路由项：
```kotlin
composable("custom_iteration") {
    CustomIterationScreen(
        onBack = { navController.popBackStack() },
        onNavigateToChat = { prompt ->
            navController.navigate("chat?prompt=${Uri.encode(prompt)}")
        }
    )
}
```

### 3. 在设置或开发者中心添加入口
在 `SettingsScreen.kt` 或 `DeveloperScreen.kt` 中添加跳转入口按钮即可！
