package top.stevezmt.wearos.bluetoothadb.daemon

import android.content.Context

class BridgePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(): BridgeConfig? {
        val address = prefs.getString(KEY_DEVICE_ADDRESS, null) ?: return null
        val name = prefs.getString(KEY_DEVICE_NAME, null) ?: address
        return BridgeConfig(
            deviceAddress = address,
            deviceName = name,
            exposeTcp = prefs.getBoolean(KEY_EXPOSE_TCP, false),
            tcpPort = BridgeConfig.sanitizeTcpPort(
                prefs.getInt(KEY_TCP_PORT, BluetoothAdbBridgeService.DEFAULT_TCP_PORT),
            ),
        )
    }

    fun saveConfig(config: BridgeConfig) {
        prefs.edit()
            .putString(KEY_DEVICE_ADDRESS, config.deviceAddress)
            .putString(KEY_DEVICE_NAME, config.deviceName)
            .putBoolean(KEY_EXPOSE_TCP, config.exposeTcp)
            .putInt(KEY_TCP_PORT, BridgeConfig.sanitizeTcpPort(config.tcpPort))
            .apply()
    }

    fun setKeepRunning(keepRunning: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_RUNNING, keepRunning).apply()
    }

    fun shouldKeepRunning(): Boolean {
        return prefs.getBoolean(KEY_KEEP_RUNNING, false)
    }

    companion object {
        private const val PREFS_NAME = "bridge_prefs"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_EXPOSE_TCP = "expose_tcp"
        private const val KEY_TCP_PORT = "tcp_port"
        private const val KEY_KEEP_RUNNING = "keep_running"
    }
}
