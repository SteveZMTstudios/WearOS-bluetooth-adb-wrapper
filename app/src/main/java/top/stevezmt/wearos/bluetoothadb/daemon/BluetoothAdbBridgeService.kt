package top.stevezmt.wearos.bluetoothadb.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

class BluetoothAdbBridgeService : Service() {
    private val prefs by lazy { BridgePreferences(this) }
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: BluetoothBridgeController? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            BridgeStateStore.state.collectLatest { state ->
                if (state.running) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                } else {
                    notificationManager.cancel(NOTIFICATION_ID)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
                START_NOT_STICKY
            }

            ACTION_START -> {
                val config = parseConfig(intent)
                if (config == null) {
                    stopSelf()
                    START_NOT_STICKY
                } else {
                    startBridge(config)
                    START_STICKY
                }
            }

            else -> {
                restoreBridgeIfNeeded()
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun restoreBridgeIfNeeded() {
        if (controller != null) {
            return
        }
        val config = prefs.loadConfig()
        if (prefs.shouldKeepRunning() && config != null) {
            startBridge(config)
        } else {
            stopSelf()
        }
    }

    private fun parseConfig(intent: Intent): BridgeConfig? {
        val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return null
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME)?.ifBlank { address } ?: address
        val tcpPort = BridgeConfig.sanitizeTcpPort(intent.getIntExtra(EXTRA_TCP_PORT, DEFAULT_TCP_PORT))
        return BridgeConfig(
            deviceAddress = address,
            deviceName = name,
            exposeTcp = intent.getBooleanExtra(EXTRA_EXPOSE_TCP, false),
            tcpPort = tcpPort,
        )
    }

    private fun startBridge(config: BridgeConfig) {
        prefs.saveConfig(config)
        prefs.setKeepRunning(true)

        BridgeStateStore.update {
            BridgeUiState(
                running = true,
                config = config,
                hostState = EndpointState(),
                targetState = EndpointState(EndpointStatus.CONNECTING, "蓝牙握手"),
                phoneIpAddresses = if (config.exposeTcp) NetworkAddressHelper.ipv4Addresses() else emptyList(),
                lastError = null,
            )
        }

        startForegroundCompat(buildNotification(BridgeStateStore.state.value))

        controller?.stop()
        controller = BluetoothBridgeController(applicationContext, config).also { it.start() }
    }

    private fun stopBridge() {
        prefs.setKeepRunning(false)
        controller?.stop()
        controller = null
        BridgeStateStore.reset(prefs.loadConfig())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: BridgeUiState): Notification {
        val config = state.config
        val serviceLine = when {
            !state.running -> getString(R.string.service_stopped)
            !state.lastError.isNullOrBlank() -> getString(R.string.service_fault)
            else -> getString(R.string.service_running)
        }
        val hostLine = state.hostState.render("主机")
        val targetLine = state.targetState.render("目标")
        val tcpLine = when {
            config == null -> getString(R.string.notification_tcp_disabled)
            !config.exposeTcp -> getString(R.string.notification_tcp_disabled)
            state.phoneIpAddresses.isEmpty() -> getString(R.string.notification_tcp_pending, config.tcpPort)
            else -> getString(
                R.string.notification_tcp_ready,
                state.phoneIpAddresses.joinToString(),
                config.tcpPort,
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val body = buildString {
            config?.let { appendLine(getString(R.string.notification_device, it.deviceName)) }
            appendLine(serviceLine)
            appendLine(hostLine)
            appendLine(targetLine)
            appendLine(getString(R.string.notification_local_socket, LOCAL_SOCKET_NAME))
            append(tcpLine)
            state.lastError?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(getString(R.string.notification_error, it))
            }
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("$serviceLine  $targetLine")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "top.stevezmt.wearos.bluetoothadb.daemon.action.START"
        const val ACTION_STOP = "top.stevezmt.wearos.bluetoothadb.daemon.action.STOP"

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_EXPOSE_TCP = "extra_expose_tcp"
        const val EXTRA_TCP_PORT = "extra_tcp_port"

        const val DEFAULT_TCP_PORT = 44444
        const val LOCAL_SOCKET_NAME = "/adb-hub"
        val ADB_BLUETOOTH_UUID: UUID = UUID.fromString("a706c740-fd9a-4f7f-a539-a97dd46baf56")

        private const val NOTIFICATION_CHANNEL_ID = "bridge_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(
            context: Context,
            config: BridgeConfig,
        ) {
            val intent = Intent(context, BluetoothAdbBridgeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, config.deviceAddress)
                putExtra(EXTRA_DEVICE_NAME, config.deviceName)
                putExtra(EXTRA_EXPOSE_TCP, config.exposeTcp)
                putExtra(EXTRA_TCP_PORT, config.tcpPort)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BluetoothAdbBridgeService::class.java).apply {
                    action = ACTION_STOP
                },
            )
        }
    }
}
