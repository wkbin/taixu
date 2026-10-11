package top.wkbin.taixu.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.wkbin.taixu.core.model.RunningSendMode

/**
 * 「运行中发送方式」偏好：当前轮还没跑完时，新的输入是排队还是作为修正插队。
 *
 * 与 ChatRoundCollapsePreferences.kt 同样的取舍：承载偏好的 SettingsDataStore.kt
 * 已触及 architecture-policy.json 的文件尺寸棘轮上限（基线只许下调），因此新增偏好
 * 以 Context 扩展独立存放，由 SettingsDataStore 转发给偏好门面。
 *
 * 语义：缺省 QUEUE —— 保持既有的「排队等下一轮」行为，与历史版本完全一致；
 * 选 STEER 后只在**运行中**生效，空闲时发送仍然是正常发起一轮。
 */
internal val runningSendModeKey = stringPreferencesKey("chat_running_send_mode")

/** 运行中发送方式（缺省 QUEUE）。未知/脏值经 [RunningSendMode.fromId] 安全回落。 */
internal val Context.runningSendModePreference: Flow<RunningSendMode>
    get() = settingsDataStore.data.map { RunningSendMode.fromId(it[runningSendModeKey]) }

/** 写入运行中发送方式，落库为稳定 id 而非枚举序号。 */
internal suspend fun Context.setRunningSendModePreference(value: RunningSendMode) {
    settingsDataStore.edit { it[runningSendModeKey] = value.id }
}
