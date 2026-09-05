package top.wkbin.taixu.ui.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.wkbin.taixu.core.datastore.FtpPreferences
import top.wkbin.taixu.core.datastore.SshPreferences
import top.wkbin.taixu.runtime.FtpServiceManager
import top.wkbin.taixu.runtime.LinuxRuntime
import top.wkbin.taixu.runtime.SshServiceManager

data class FtpSettingsUiState(
    val distroId: String = "ubuntu",
    val enabled: Boolean = false,
    val port: Int = 2121,
    val username: String = "root",
    val anonymousEnabled: Boolean = false,
    val readOnly: Boolean = false,
    val passwordConfigured: Boolean = false,
    val sshPasswordConfigured: Boolean = false,
) {
    val connectionHost: String
        get() = SshServiceManager.localIpv4Address() ?: "<设备局域网IP>"

    val connectionUrl: String
        get() = if (anonymousEnabled) {
            "ftp://$connectionHost:$port/"
        } else {
            "ftp://$username@$connectionHost:$port/"
        }

    val localUrl: String
        get() = if (anonymousEnabled) {
            "ftp://127.0.0.1:$port/"
        } else {
            "ftp://$username@127.0.0.1:$port/"
        }
}

@HiltViewModel
class FtpSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linuxRuntime: LinuxRuntime,
    private val preferences: FtpPreferences,
    private val sshPreferences: SshPreferences,
    private val manager: FtpServiceManager,
) : ViewModel() {
    val serviceState = manager.state
    val logs = manager.logs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _vpnActive = MutableStateFlow(false)
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()
    private var vpnCallback: ConnectivityManager.NetworkCallback? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val settings: StateFlow<FtpSettingsUiState> = linuxRuntime.activeDistroId
        .flatMapLatest { distroId ->
            combine(
                preferences.enabled(distroId),
                preferences.port(distroId),
                preferences.username(distroId),
                preferences.anonymousEnabled(distroId),
                preferences.readOnly(distroId),
            ) { enabled, port, username, anonymous, readOnly ->
                FtpSettingsUiState(
                    distroId = distroId,
                    enabled = enabled,
                    port = port,
                    username = username,
                    anonymousEnabled = anonymous,
                    readOnly = readOnly,
                )
            }.combine(preferences.passwordConfigured(distroId)) { state, passwordConfigured ->
                state.copy(passwordConfigured = passwordConfigured)
            }.combine(sshPreferences.passwordConfigured(distroId)) { state, sshPasswordConfigured ->
                state.copy(sshPasswordConfigured = sshPasswordConfigured)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FtpSettingsUiState())

    private val _operating = MutableStateFlow(false)
    val operating = _operating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _messageIsError = MutableStateFlow(false)
    val messageIsError = _messageIsError.asStateFlow()

    init {
        manager.startObserving()
        refresh()
        observeVpn()
    }

    fun toggleEnabled(enabled: Boolean) = runOperation {
        manager.setEnabled(enabled)
    }

    fun savePort(value: String) = runOperation(successMessage = "FTP 端口已保存") {
        val port = value.trim().toIntOrNull() ?: error("请输入有效的 FTP 端口")
        manager.setPort(port)
    }

    fun saveUsername(value: String) = runOperation(successMessage = "FTP 用户名已保存") {
        val username = value.trim().ifBlank { "root" }
        manager.setUsername(username)
    }

    fun savePassword(value: String) = runOperation(successMessage = "FTP 登录密码已保存") {
        manager.setPassword(value.trim())
    }

    fun clearPassword() = runOperation(successMessage = "FTP 登录密码已清除") {
        manager.clearPassword()
    }

    fun toggleAnonymous(enabled: Boolean) = runOperation {
        manager.setAnonymousEnabled(enabled)
    }

    fun toggleReadOnly(readOnly: Boolean) = runOperation {
        manager.setReadOnly(readOnly)
    }

    fun refresh() = runOperation(showProgress = false) {
        manager.refresh()
    }

    fun consumeMessage() {
        _message.value = null
        _messageIsError.value = false
    }

    private fun observeVpn() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _vpnActive.value = vpnActiveNow(cm)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                _vpnActive.value = vpnActiveNow(cm)
            }

            override fun onLost(network: Network) {
                _vpnActive.value = vpnActiveNow(cm)
            }
        }
        vpnCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { _vpnActive.value = vpnActiveNow(cm) }
        _vpnActive.value = vpnActiveNow(cm)
    }

    private fun vpnActiveNow(cm: ConnectivityManager): Boolean = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    override fun onCleared() {
        vpnCallback?.let { callback ->
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(callback)
            }
        }
        super.onCleared()
    }

    private fun runOperation(
        showProgress: Boolean = true,
        successMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            if (showProgress) _operating.value = true
            try {
                block()
                if (successMessage != null) {
                    _message.value = successMessage
                    _messageIsError.value = false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _message.value = throwable.message ?: "FTP 操作失败"
                _messageIsError.value = true
            } finally {
                if (showProgress) _operating.value = false
            }
        }
    }
}
