package top.stevezmt.wearos.bluetoothadb.daemon

import android.content.Context
import androidx.annotation.StringRes

data class BridgeConfig(
    val deviceAddress: String,
    val deviceName: String,
    val exposeTcp: Boolean,
    val tcpPort: Int = BluetoothAdbBridgeService.DEFAULT_TCP_PORT,
) {
    companion object {
        val VALID_TCP_PORT_RANGE: IntRange = 1..65535

        fun sanitizeTcpPort(port: Int): Int {
            return port.takeIf { it in VALID_TCP_PORT_RANGE } ?: BluetoothAdbBridgeService.DEFAULT_TCP_PORT
        }
    }
}

enum class ServiceStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAULT,
}

enum class EndpointStatus(@StringRes val labelResId: Int) {
    DISCONNECTED(R.string.endpoint_status_disconnected),
    CONNECTING(R.string.endpoint_status_connecting),
    CONNECTED(R.string.endpoint_status_connected),
    ERROR(R.string.endpoint_status_error),
}

data class EndpointState(
    val status: EndpointStatus = EndpointStatus.DISCONNECTED,
    val detail: String? = null,
) {
    fun render(context: Context, prefix: String): String {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$prefix: ${context.getString(status.labelResId)}$suffix"
    }
}

data class BridgeUiState(
    val running: Boolean = false,
    val serviceStatus: ServiceStatus = ServiceStatus.STOPPED,
    val config: BridgeConfig? = null,
    val hostState: EndpointState = EndpointState(),
    val targetState: EndpointState = EndpointState(),
    val phoneIpAddresses: List<String> = emptyList(),
    val lastError: String? = null,
)
