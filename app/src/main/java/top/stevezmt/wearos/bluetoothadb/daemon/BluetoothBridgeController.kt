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
import android.util.Log
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
    private var currentHostConnection: HostConnection? = null

    fun start() {
        if (bridgeJob != null) {
            return
        }
        stopRequested.set(false)
        Log.i(TAG, "start bridge device=${config.deviceName} address=${config.deviceAddress} tcp=${if (config.exposeTcp) config.tcpPort else "disabled"}")
        bridgeJob = scope.launch {
            runBridgeLoop()
        }
    }

    fun stop() {
        requestStop()
    }

    suspend fun stopAndJoin() {
        Log.i(TAG, "stop bridge requested join=true")
        val jobsToJoin = requestStop()
        jobsToJoin.forEach { job ->
            try {
                job.join()
            } catch (_: CancellationException) {
            }
        }
        bridgeJob = null
        Log.i(TAG, "stop bridge completed")
    }

    private suspend fun runBridgeLoop() {
        val device = resolveDevice() ?: return
        while (scope.isActive) {
            var targetSocket: BluetoothSocket? = null
            var hostConnection: HostConnection? = null
            try {
                targetSocket = connectTarget(device)
                currentTargetSocket = targetSocket
                Log.i(TAG, "target connected device=${config.deviceName}")
                BridgeStateStore.update {
                    it.copy(
                        running = true,
                        serviceStatus = ServiceStatus.RUNNING,
                        config = config,
                        targetState = EndpointState(EndpointStatus.CONNECTED, config.deviceName),
                        hostState = EndpointState(),
                        phoneIpAddresses = if (config.exposeTcp) NetworkAddressHelper.ipv4Addresses() else emptyList(),
                        lastError = null,
                    )
                }

                startHostAcceptors()
                hostConnection = hostQueue.receive()
                currentHostConnection = hostConnection
                Log.i(TAG, "host connected transport=${hostConnection.transportLabel}")
                BridgeStateStore.update {
                    it.copy(
                        serviceStatus = ServiceStatus.RUNNING,
                        hostState = EndpointState(EndpointStatus.CONNECTED, hostConnection.transportLabel),
                        lastError = null,
                    )
                }
                pipeTraffic(hostConnection, targetSocket)
            } catch (cancelled: CancellationException) {
                Log.i(TAG, "bridge loop cancelled")
                throw cancelled
            } catch (error: Throwable) {
                if (scope.isActive) {
                    Log.w(TAG, "bridge loop error: ${readableError(error)}", error)
                    BridgeStateStore.update {
                        it.copy(
                            running = true,
                            serviceStatus = ServiceStatus.FAULT,
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
                Log.d(TAG, "bridge loop cleanup")
                hostReserved.set(false)
                closeHostAcceptors()
                closeQuietly(hostConnection)
                if (currentHostConnection === hostConnection) {
                    currentHostConnection = null
                }
                closeQuietly(targetSocket)
                currentTargetSocket = null
                closePendingHosts()
                if (scope.isActive) {
                    BridgeStateStore.update {
                        it.copy(
                            running = true,
                            serviceStatus = when (it.serviceStatus) {
                                ServiceStatus.FAULT -> ServiceStatus.FAULT
                                ServiceStatus.STOPPING -> ServiceStatus.STOPPING
                                else -> ServiceStatus.RUNNING
                            },
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
                    serviceStatus = ServiceStatus.FAULT,
                    config = config,
                    targetState = EndpointState(EndpointStatus.ERROR, context.getString(R.string.error_no_bluetooth_adapter_short)),
                    lastError = context.getString(R.string.error_no_bluetooth_adapter),
                )
            }
            return null
        }
        return try {
            if (!hasBluetoothConnectPermission()) {
                throw SecurityException(context.getString(R.string.error_missing_bluetooth_connect_permission))
            }
            adapter.getRemoteDevice(config.deviceAddress)
        } catch (error: IllegalArgumentException) {
            BridgeStateStore.update {
                it.copy(
                    running = true,
                    serviceStatus = ServiceStatus.FAULT,
                    config = config,
                    targetState = EndpointState(EndpointStatus.ERROR, context.getString(R.string.error_invalid_device_address_short)),
                    lastError = context.getString(R.string.error_invalid_device_address, config.deviceAddress),
                )
            }
            null
        }
    }

    private suspend fun connectTarget(device: BluetoothDevice): BluetoothSocket {
        val adapter = bluetoothAdapter ?: throw IllegalStateException(context.getString(R.string.error_bluetooth_adapter_unavailable))
        while (scope.isActive) {
            BridgeStateStore.update {
                it.copy(
                    running = true,
                    serviceStatus = when (it.serviceStatus) {
                        ServiceStatus.STOPPING -> ServiceStatus.STOPPING
                        ServiceStatus.RUNNING, ServiceStatus.FAULT -> it.serviceStatus
                        else -> ServiceStatus.STARTING
                    },
                    config = config,
                    targetState = EndpointState(EndpointStatus.CONNECTING, config.deviceName),
                    hostState = EndpointState(),
                )
            }
            if (!hasBluetoothConnectPermission()) {
                throw SecurityException(context.getString(R.string.error_missing_bluetooth_connect_permission))
            }
            if (!hasBluetoothScanPermission()) {
                throw SecurityException(context.getString(R.string.error_missing_nearby_devices_permission_scan))
            }

            var socket: BluetoothSocket? = null
            var connected = false
            try {
                adapter.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(BluetoothAdbBridgeService.ADB_BLUETOOTH_UUID)
                currentTargetSocket = socket
                Log.d(TAG, "connecting target device=${config.deviceName} address=${config.deviceAddress}")
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
                Log.w(TAG, "target connect failed: ${readableError(error)}")
                BridgeStateStore.update {
                    it.copy(
                        running = true,
                        config = config,
                        targetState = EndpointState(EndpointStatus.ERROR, context.getString(R.string.error_handshake_failed)),
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
            var server: LocalServerSocket? = null
            try {
                server = LocalServerSocket(BluetoothAdbBridgeService.LOCAL_SOCKET_NAME)
                localServerSocket = server
                Log.i(TAG, "local adb-hub listening name=${BluetoothAdbBridgeService.LOCAL_SOCKET_NAME}")
                clearRuntimeError()
                while (scope.isActive && !stopRequested.get()) {
                    val socket = server.accept()
                    if (!isAllowedLocalPeer(socket)) {
                        closeQuietly(socket)
                        continue
                    }
                    Log.i(TAG, "local host accepted")
                    offerHost(LocalHostConnection(socket))
                }
            } catch (error: IOException) {
                if (scope.isActive && !stopRequested.get()) {
                    Log.w(TAG, "local host accept failed: ${readableError(error)}")
                    BridgeStateStore.update {
                        it.copy(lastError = readableError(error))
                    }
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                closeQuietly(server)
                if (localServerSocket === server) {
                    localServerSocket = null
                }
            }
        }
    }

    private suspend fun acceptTcpHosts() {
        while (scope.isActive) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket()
                tcpServerSocket = server
                server.reuseAddress = true
                server.bind(InetSocketAddress(config.tcpPort))
                Log.i(TAG, "tcp host listening port=${config.tcpPort}")
                clearRuntimeError()
                while (scope.isActive && !stopRequested.get()) {
                    val socket = server.accept()
                    Log.i(TAG, "tcp host accepted remote=${socket.inetAddress?.hostAddress}:${socket.port}")
                    offerHost(TcpHostConnection(socket, config.tcpPort))
                }
            } catch (error: BindException) {
                if (scope.isActive && !stopRequested.get()) {
                    Log.w(TAG, "tcp bind failed port=${config.tcpPort}: ${readableError(error)}")
                    BridgeStateStore.update {
                        it.copy(lastError = context.getString(R.string.error_tcp_port_in_use, config.tcpPort))
                    }
                    delay(RETRY_DELAY_MS)
                }
            } catch (error: IOException) {
                if (scope.isActive && !stopRequested.get()) {
                    Log.w(TAG, "tcp host accept failed: ${readableError(error)}")
                    BridgeStateStore.update {
                        it.copy(lastError = readableError(error))
                    }
                    delay(RETRY_DELAY_MS)
                }
            } finally {
                closeQuietly(server)
                if (tcpServerSocket === server) {
                    tcpServerSocket = null
                }
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
            is SecurityException -> error.message ?: context.getString(R.string.error_bluetooth_access_denied)
            is SocketException -> {
                if (error.message.equals("Socket closed", ignoreCase = true)) {
                    context.getString(R.string.error_connection_closed)
                } else {
                    error.message ?: context.getString(R.string.error_socket_closed)
                }
            }

            is IOException -> error.message ?: context.getString(R.string.error_io_failed)
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    private fun clearRuntimeError() {
        Log.d(TAG, "clear runtime error")
        BridgeStateStore.update {
            it.copy(
                serviceStatus = if (it.serviceStatus == ServiceStatus.STOPPING) {
                    ServiceStatus.STOPPING
                } else {
                    ServiceStatus.RUNNING
                },
                lastError = null,
                phoneIpAddresses = if (config.exposeTcp) NetworkAddressHelper.ipv4Addresses() else emptyList(),
            )
        }
    }

    private fun requestStop(): List<Job> {
        Log.i(TAG, "stop bridge requested join=false")
        stopRequested.set(true)
        hostReserved.set(false)
        val jobsToJoin = listOfNotNull(bridgeJob, localAcceptJob, tcpAcceptJob).distinct()
        bridgeJob?.cancel()
        closeHostAcceptors()
        closeQuietly(currentHostConnection)
        currentHostConnection = null
        closeQuietly(currentTargetSocket)
        currentTargetSocket = null
        closePendingHosts()
        scope.cancel()
        bridgeJob = null
        return jobsToJoin
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
        private const val TAG = "BtAdbBridge"
        private const val RETRY_DELAY_MS = 2_000L
    }
}
