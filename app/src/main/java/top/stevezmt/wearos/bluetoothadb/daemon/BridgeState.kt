package top.stevezmt.wearos.bluetoothadb.daemon

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

enum class EndpointStatus(val label: String) {
    DISCONNECTED("已断开连接"),
    CONNECTING("连接中"),
    CONNECTED("已连接"),
    ERROR("连接失败"),
}

data class EndpointState(
    val status: EndpointStatus = EndpointStatus.DISCONNECTED,
    val detail: String? = null,
) {
    fun render(prefix: String): String {
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$prefix：${status.label}$suffix"
    }
}

data class BridgeUiState(
    val running: Boolean = false,
    val config: BridgeConfig? = null,
    val hostState: EndpointState = EndpointState(),
    val targetState: EndpointState = EndpointState(),
    val phoneIpAddresses: List<String> = emptyList(),
    val lastError: String? = null,
)
