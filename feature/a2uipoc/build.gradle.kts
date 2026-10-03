plugins {
    alias(libs.plugins.taixu.android.feature)
}

android {
    namespace = "top.wkbin.taixu.feature.a2uipoc"
    resourcePrefix = "fa2ui_"
}

dependencies {
    // A2uiSurfaceContract / A2uiSurfaceBus：render_surface 工具的协议契约与界面载荷总线
    implementation(project(":harness"))
    implementation(project(":feature:theme"))
    // RuntimeTopBar：与其他二级页一致的顶栏（返回箭头/状态栏间距/单行标题）
    implementation(project(":feature:components"))
    implementation(libs.kotlinx.serialization.json)

    // A2UI 官方渲染器（1.0.0-alpha01，API 不稳定，仅限本 PoC 模块使用）
    implementation(libs.a2ui.model)
    implementation(libs.a2ui.compose.runtime)
    implementation(libs.a2ui.compose.ui)
    implementation(libs.a2ui.material3)

    // 值型组件「常量 value 归一化」的单测（纯 JVM，无 Android 依赖）
    testImplementation(libs.bundles.test.robolectric)
}
