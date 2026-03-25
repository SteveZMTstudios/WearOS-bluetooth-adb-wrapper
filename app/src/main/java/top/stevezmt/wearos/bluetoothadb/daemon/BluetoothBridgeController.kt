package top.stevezmt.wearos.bluetoothadb.daemon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothBridgeController(
    private val context: Context,
    private val config: BridgeConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostQueue = Channel<HostConnection>(capacity = 1)
    private val hostReserved = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        context.getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private var bridgeJob: Job? = null
    private var localAcceptJob: Job? = null
    private var tcpAcceptJob: Job? = null
    private var localServerSocket: LocalServerSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var currentTargetSocket: BluetoothSocket? = null

    fun start() {
        if (bridgeJob != null) {
            return
        }
        stopRequested.set(false)
        bridgeJob = scope.launch {
            runBridgeLoop()
        }
    }

    fun stop() {
        stopRequested.set(true)
        bridgeJob?.cancel()
        bridgeJob = null
        closeHostAcceptors()
        closeQuietly(currentTargetSocket)
        currentTargetSocket = null
        closePendingHosts()
        scope.cancel()
    }

    private suspend fun runBridgeLoop() {
        val device = resolveDevice() ?: return
        while (scope.isActive) {
            var targetSocket: BluetoothSocket? = null
            var hostConnection: HostConnection? = null
            try {
                targetSocket = connectTarget(device)
                currentTargetSocket = targetSocket
                BridgeStateStore.update {
                    it.copy(
                        running = true,
                        config = config,
                        targetState = EndpointState(EndpointStatus.CONNECTED, config.deviceName),
                        hostState = EndpointState(),
                        phoneIpAddresses = if (config.exposeTcp) NetworkAddressHelper.ipv4Addresses() else emptyList(),
                        lastError = null,
                    )
                }

                startHostAcceptors()
                hostConnection = hostQueue.receive()
                BridgeStateStore.update {
                    it.copy(
                        hostState = EndpointState(EndpointStatus.CONNECTED, hostConnection.transportLabel),
                        lastError = null,
                    )
                }
                pipeTraffic(hostConnection, targetSocket)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (scope.isActive) {
                    BridgeStateStore.update {
                        it.copy(
                            running = true,
                            config = config,
                            hostState = EndpointState(),
                            targetState = EndpointState(EndpointStatus.ERROR, error.message ?: error.javaClass.simpleName),
                            phoneIpAddresses = if (config.exposeTcp) NetworkAddressHelper.ipv4Addresses() else emptyList(),
                            lastError = readableError(error),
                        )
                    }
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                hostReserved.set(false)
                closeHostAcceptors()
                closeQuietly(hostConnection)
                closeQuietly(targetSocket)
                currentTargetSocket = null
                closePendingHosts()
                if (scope.isActive) {
                    BridgeStateStore.update {
                        it.copy(
                            running = true,
                            config = config,
                            hostState = EndpointState(),
                            targetState = EndpointState(),
                            phoneIpAddresses = if (config.exposeTcp) NetworkAddressHelper.ipv4Addresses() else emptyList(),
                        )
                    }
                }
            }
        }
    }

    private fun resolveDevice(): BluetoothDevice? {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            BridgeStateStore.update {
                it.copy(
                    running = true,
                    config = config,
                    targetState = EndpointState(EndpointStatus.ERROR, "手机不支持蓝牙"),
                    lastError = "手机没有可用的蓝牙适配器。",
                )
            }
            return null
        }
        return try {
            if (!hasBluetoothConnectPermission()) {
                throw SecurityException("缺少蓝牙连接权限")
            }
            adapter.getRemoteDevice(config.deviceAddress)
        } catch (error: IllegalArgumentException) {
            BridgeStateStore.update {
                it.copy(
                    running = true,
                    config = config,
                    targetState = EndpointState(EndpointStatus.ERROR, "设备地址无效"),
                    lastError = "无法解析蓝牙地址 ${config.deviceAddress}。",
                )
            }
            null
        }
    }

    private suspend fun connectTarget(device: BluetoothDevice): BluetoothSocket {
        val adapter = bluetoothAdapter ?: throw IllegalStateException("蓝牙适配器不可用")
        while (scope.isActive) {
            BridgeStateStore.update {
                it.copy(
                    running = true,
                    config = config,
                    targetState = EndpointState(EndpointStatus.CONNECTING, config.deviceName),
                    hostState = EndpointState(),
                )
            }
            if (!hasBluetoothConnectPermission()) {
                throw SecurityException("缺少蓝牙连接权限")
            }
            if (!hasBluetoothScanPermission()) {
                throw SecurityException("缺少附近设备权限，无法取消系统蓝牙扫描。")
            }

            var socket: BluetoothSocket? = null
            var connected = false
            try {
                adapter.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(BluetoothAdbBridgeService.ADB_BLUETOOTH_UUID)
                currentTargetSocket = socket
                socket.connect()
                if (!scope.isActive || stopRequested.get()) {
                    closeQuietly(socket)
                    throw CancellationException("bridge stopped")
                }
                connected = true
                return socket
            } catch (error: IOException) {
                closeQuietly(socket)
                if (!scope.isActive || stopRequested.get()) {
                    throw CancellationException("bridge stopped")
                }
                BridgeStateStore.update {
                    it.copy(
                        running = true,
                        config = config,
                        targetState = EndpointState(EndpointStatus.ERROR, "握手失败"),
                        lastError = readableError(error),
                    )
                }
                delay(RETRY_DELAY_MS)
            } finally {
                if (!connected && currentTargetSocket === socket) {
                    currentTargetSocket = null
                }
            }
        }
        throw CancellationException("bridge stopped")
    }

    private fun startHostAcceptors() {
        closeHostAcceptors()
        localAcceptJob = scope.launch {
            acceptLocalHosts()
        }
        if (config.exposeTcp) {
            tcpAcceptJob = scope.launch {
                acceptTcpHosts()
            }
        }
    }

    private suspend fun acceptLocalHosts() {
        while (scope.isActive) {
            try {
                val server = LocalServerSocket(BluetoothAdbBridgeService.LOCAL_SOCKET_NAME)
                localServerSocket = server
                val socket = server.accept()
                if (!isAllowedLocalPeer(socket)) {
                    closeQuietly(socket)
                    continue
                }
                offerHost(LocalHostConnection(socket))
            } catch (error: IOException) {
                if (scope.isActive) {
                    BridgeStateStore.update {
                        it.copy(lastError = readableError(error))
                    }
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                closeQuietly(localServerSocket)
                localServerSocket = null
            }
        }
    }

    private suspend fun acceptTcpHosts() {
        while (scope.isActive) {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(config.tcpPort))
                }
                tcpServerSocket = server
                val socket = server.accept()
                offerHost(TcpHostConnection(socket, config.tcpPort))
            } catch (error: BindException) {
                if (scope.isActive) {
                    BridgeStateStore.update {
                        it.copy(lastError = "TCP ${config.tcpPort} 端口已被占用。")
                    }
                    delay(RETRY_DELAY_MS)
                }
            } catch (error: IOException) {
                if (scope.isActive) {
                    BridgeStateStore.update {
                        it.copy(lastError = readableError(error))
                    }
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                closeQuietly(tcpServerSocket)
                tcpServerSocket = null
            }
        }
    }

    private fun isAllowedLocalPeer(socket: LocalSocket): Boolean {
        return try {
            val uid = socket.peerCredentials.uid
            uid == 0 || uid == 2000
        } catch (_: IOException) {
            false
        }
    }

    private fun offerHost(hostConnection: HostConnection) {
        if (!hostReserved.compareAndSet(false, true)) {
            closeQuietly(hostConnection)
            return
        }
        if (!hostQueue.trySend(hostConnection).isSuccess) {
            hostReserved.set(false)
            closeQuietly(hostConnection)
        }
    }

    private suspend fun pipeTraffic(host: HostConnection, target: BluetoothSocket) = coroutineScope {
        val closeOnce = AtomicBoolean(false)

        fun closeAll() {
            if (closeOnce.compareAndSet(false, true)) {
                closeQuietly(host)
                closeQuietly(target)
            }
        }

        val upstream = launch {
            try {
                pump(host.inputStream(), target.outputStream)
            } finally {
                closeAll()
            }
        }
        val downstream = launch {
            try {
                pump(target.inputStream, host.outputStream())
            } finally {
                closeAll()
            }
        }

        upstream.join()
        downstream.join()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (scope.isActive && !stopRequested.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    return
                }
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (error: IOException) {
            if (isExpectedClosure(error)) {
                return
            }
            throw error
        }
    }

    private fun closeHostAcceptors() {
        localAcceptJob?.cancel()
        localAcceptJob = null
        tcpAcceptJob?.cancel()
        tcpAcceptJob = null
        closeQuietly(localServerSocket)
        localServerSocket = null
        closeQuietly(tcpServerSocket)
        tcpServerSocket = null
    }

    private fun closePendingHosts() {
        while (true) {
            val host = hostQueue.tryReceive().getOrNull() ?: return
            closeQuietly(host)
        }
    }

    private fun readableError(error: Throwable): String {
        return when (error) {
            is SecurityException -> error.message ?: "系统拒绝了蓝牙访问请求。"
            is SocketException -> {
                if (error.message.equals("Socket closed", ignoreCase = true)) {
                    "连接已关闭。"
                } else {
                    error.message ?: "套接字已关闭。"
                }
            }

            is IOException -> error.message ?: "I/O 读写失败。"
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    private fun isExpectedClosure(error: IOException): Boolean {
        if (stopRequested.get() || !scope.isActive) {
            return true
        }
        val message = error.message?.lowercase().orEmpty()
        return message.contains("socket closed") ||
            message.contains("bt socket closed") ||
            message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("read return: -1")
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(server: LocalServerSocket?) {
        try {
            server?.close()
        } catch (_: IOException) {
        }
    }

    private fun closeQuietly(server: ServerSocket?) {
        try {
            server?.close()
        } catch (_: IOException) {
        }
    }

    private interface HostConnection : Closeable {
        val transportLabel: String
        fun inputStream(): InputStream
        fun outputStream(): OutputStream
    }

    private class LocalHostConnection(
        private val socket: LocalSocket,
    ) : HostConnection {
        override val transportLabel: String = "adb-hub"

        override fun inputStream(): InputStream = socket.inputStream

        override fun outputStream(): OutputStream = socket.outputStream

        override fun close() {
            socket.close()
        }
    }

    private class TcpHostConnection(
        private val socket: Socket,
        port: Int,
    ) : HostConnection {
        override val transportLabel: String = "TCP $port"

        override fun inputStream(): InputStream = socket.getInputStream()

        override fun outputStream(): OutputStream = socket.getOutputStream()

        override fun close() {
            socket.close()
        }
    }

    companion object {
        private const val RETRY_DELAY_MS = 2_000L
    }
}
