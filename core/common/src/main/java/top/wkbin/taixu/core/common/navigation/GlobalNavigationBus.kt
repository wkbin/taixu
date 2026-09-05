package top.wkbin.taixu.core.common.navigation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 应用跨模块/跨系统组件（Activity、Service、Receiver 等）的全局导航目标。 */
sealed interface AppNavigationTarget {
    /** 无线 ADB 与日志抓取工作台 */
    data object AdbLogcat : AppNavigationTarget
}

/**
 * 全局导航总线。
 *
 * 用于在非 UI 逻辑（系统通知、后台服务、快捷方式、Intent 处理）与 Compose Navigation 之间
 * 建立解耦、可靠的导航事件传递通道。
 */
@Singleton
class GlobalNavigationBus @Inject constructor() {
    private val _events = MutableSharedFlow<AppNavigationTarget>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AppNavigationTarget> = _events.asSharedFlow()

    fun navigateTo(target: AppNavigationTarget) {
        _events.tryEmit(target)
    }

    /** 消费并重置当前重放事件，防止同一目标在旋转屏幕或重组时被重复处理。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearLatest(target: AppNavigationTarget) {
        _events.resetReplayCache()
    }
}
