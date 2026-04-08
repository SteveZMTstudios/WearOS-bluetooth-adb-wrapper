package top.stevezmt.wearos.bluetoothadb.daemon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.remote.interactions.RemoteActivityHelper
import androidx.concurrent.futures.await
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.stevezmt.wearos.bluetoothadb.daemon.databinding.ActivityMainBinding
import top.stevezmt.wearos.bluetoothadb.daemon.databinding.ActivityWatchMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: BridgePreferences

    private var deviceEntries: List<DeviceEntry> = emptyList()
    private var pendingStartAfterPermission = false
    private var isEndpointExpanded = false
    private var isInstructionsExpanded = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasRequiredPermissions()) {
                refreshBondedDevices()
                if (pendingStartAfterPermission) {
                    pendingStartAfterPermission = false
                    startSelectedBridge()
                }
            } else {
                pendingStartAfterPermission = false
                renderPermissionHint()
                Toast.makeText(this, R.string.permission_denied_message, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            val watchBinding = ActivityWatchMainBinding.inflate(layoutInflater)
            setContentView(watchBinding.root)
            
            watchBinding.btnOpenPhone.setOnClickListener {
                val remoteActivityHelper = RemoteActivityHelper(this)
                val intent = Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse("https://github.com/SteveZMTstudios/WearOS-bluetooth-adb-wrapper/releases/latest"))
                remoteActivityHelper.startRemoteActivity(intent)
            }
            
            watchBinding.btnUninstall.setOnClickListener {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = BridgePreferences(this)

        binding.localSocketValue.text = "localabstract:${BluetoothAdbBridgeService.LOCAL_SOCKET_NAME}"
        binding.exposeTcpSwitch.isChecked = prefs.loadConfig()?.exposeTcp == true
        binding.tcpPortEditText.setText(
            (prefs.loadConfig()?.tcpPort ?: BluetoothAdbBridgeService.DEFAULT_TCP_PORT).toString(),
        )

        setupUi()
        observeBridgeState()
        if (savedInstanceState == null) {
            ensurePermissionsForManualAction(startAfterGrant = false)
        }
        refreshBondedDevices()
        renderPermissionHint()
        renderTcpPortState()
    }

    override fun onResume() {
        super.onResume()
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) return
        refreshBondedDevices()
    }

    private fun setupUi() {
        setEndpointExpanded(expanded = false)
        binding.endpointHeader.setOnClickListener {
            setEndpointExpanded(!isEndpointExpanded)
        }
        binding.endpointCopyButton.setOnClickListener {
            copyTextToClipboard(getString(R.string.endpoint_title), endpointClipboardText())
        }
        binding.endpointContent.setOnClickListener {
            copyTextToClipboard(getString(R.string.endpoint_title), endpointClipboardText())
        }

        setInstructionsExpanded(expanded = false)
        binding.instructionsHeader.setOnClickListener {
            setInstructionsExpanded(!isInstructionsExpanded)
        }
        binding.instructionsCopyButton.setOnClickListener {
            copyTextToClipboard(getString(R.string.instructions_title), instructionsClipboardText())
        }
        binding.instructionsContent.setOnClickListener {
            copyTextToClipboard(getString(R.string.instructions_title), instructionsClipboardText())
        }

        binding.refreshDevicesButton.setOnClickListener {
            ensurePermissionsForManualAction(startAfterGrant = false)
            refreshBondedDevices()
        }
        binding.startServiceButton.setOnClickListener {
            ensurePermissionsForManualAction(startAfterGrant = true)
            if (hasRequiredPermissions()) {
                startSelectedBridge()
            }
        }
        binding.stopServiceButton.setOnClickListener {
            if (BridgeStateStore.state.value.serviceStatus == ServiceStatus.STOPPING) {
                BluetoothAdbBridgeService.forceStop(this)
            } else {
                BluetoothAdbBridgeService.stop(this)
            }
        }
        binding.exposeTcpSwitch.setOnCheckedChangeListener { _, _ ->
            ensureTcpPortDefault()
            renderTcpPortState()
            updateEndpointPreview()
            updateInstructions(BridgeStateStore.state.value)
            refreshControls()
        }
        binding.tcpPortEditText.doAfterTextChanged {
            renderTcpPortState()
            updateEndpointPreview()
            updateInstructions(BridgeStateStore.state.value)
            refreshControls()
        }
        binding.deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                updateSelectionPreview()
                updateInstructions(BridgeStateStore.state.value)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateSelectionPreview()
                updateInstructions(BridgeStateStore.state.value)
            }
        }
    }

    private fun observeBridgeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BridgeStateStore.state.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: BridgeUiState) {
        val runningConfig = state.config
        val activeConfig = if (state.running) runningConfig else null
        val selected = selectedDevice()

        applyStatusVisual(
            binding.serviceStateValue,
            serviceStatusText(state),
            serviceStatusColorRes(state),
        )
        applyStatusVisual(
            binding.hostStatusValue,
            state.hostState.render(this, getString(R.string.host_label)),
            endpointStatusColorRes(state.hostState.status),
        )
        applyStatusVisual(
            binding.targetStatusValue,
            state.targetState.render(this, getString(R.string.target_label)),
            endpointStatusColorRes(state.targetState.status),
        )

        binding.selectedDeviceValue.text = when {
            activeConfig != null -> "${activeConfig.deviceName}\n${activeConfig.deviceAddress}"
            selected != null -> "${selected.name}\n${selected.address}"
            else -> getString(R.string.no_device_selected)
        }

        binding.errorTitle.isVisible = !state.lastError.isNullOrBlank()
        binding.errorValue.isVisible = !state.lastError.isNullOrBlank()
        binding.errorValue.text = state.lastError ?: ""

        if (state.running && runningConfig != null) {
            binding.exposeTcpSwitch.isChecked = runningConfig.exposeTcp
            setTcpPortTextIfNeeded(runningConfig.tcpPort)
        }

        binding.stopServiceButton.text = if (state.serviceStatus == ServiceStatus.STOPPING) {
            getString(R.string.force_stop_service)
        } else {
            getString(R.string.stop_service)
        }

        renderTcpPortState()
        updateEndpointPreview(state)
        updateInstructions(state)
        renderPermissionHint()
        refreshControls(state, selected)
    }

    private fun refreshBondedDevices() {
        if (!hasRequiredPermissions()) {
            deviceEntries = emptyList()
            binding.deviceSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf(getString(R.string.permission_required_for_devices)),
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            updateSelectionPreview()
            refreshControls()
            return
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            deviceEntries = emptyList()
            binding.deviceSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf(getString(R.string.bluetooth_not_supported)),
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            updateSelectionPreview()
            refreshControls()
            return
        }

        val bonded = adapter.bondedDevices.orEmpty()
            .map { device ->
                DeviceEntry(
                    name = device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.unnamed_device),
                    address = device.address.orEmpty(),
                )
            }
            .sortedWith(compareBy<DeviceEntry> { it.name.lowercase() }.thenBy { it.address })

        deviceEntries = bonded
        val labels = if (bonded.isEmpty()) {
            listOf(getString(R.string.no_paired_devices))
        } else {
            bonded
        }

        binding.deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        selectPreferredDevice()
        updateSelectionPreview()
        refreshControls()
    }

    private fun selectPreferredDevice() {
        if (deviceEntries.isEmpty()) {
            binding.deviceSpinner.setSelection(0, false)
            return
        }

        val runningConfig = BridgeStateStore.state.value.config
        val preferredAddress = runningConfig?.deviceAddress ?: prefs.loadConfig()?.deviceAddress
        val index = deviceEntries.indexOfFirst { it.address == preferredAddress }.takeIf { it >= 0 } ?: 0
        binding.deviceSpinner.setSelection(index, false)
    }

    private fun updateSelectionPreview() {
        val selected = selectedDevice()
        binding.selectedDeviceValue.text = if (selected == null) {
            getString(R.string.no_device_selected)
        } else {
            "${selected.name}\n${selected.address}"
        }
    }

    private fun updateEndpointPreview(state: BridgeUiState = BridgeStateStore.state.value) {
        val exposeTcp = binding.exposeTcpSwitch.isChecked
        val tcpPort = currentPreviewTcpPort(state)
        val phoneIps = if (state.running && state.config?.exposeTcp == true) {
            state.phoneIpAddresses
        } else {
            NetworkAddressHelper.ipv4Addresses()
        }

        binding.tcpEndpointValue.text = when {
            !exposeTcp -> getString(R.string.tcp_disabled)
            tcpPort == null -> getString(R.string.tcp_port_invalid)
            phoneIps.isEmpty() -> getString(
                R.string.tcp_waiting_for_network,
                tcpPort,
            )
            else -> phoneIps.joinToString("\n") { ip ->
                "$ip:$tcpPort"
            }
        }
    }

    private fun updateInstructions(state: BridgeUiState) {
        val tcpPort = currentPreviewTcpPort(state)
        val commandPort = tcpPort ?: BluetoothAdbBridgeService.DEFAULT_TCP_PORT
        val classicInstructions = buildString {
            appendLine("adb forward tcp:$commandPort localabstract:${BluetoothAdbBridgeService.LOCAL_SOCKET_NAME}")
            append("adb connect 127.0.0.1:$commandPort")
        }

        val tcpInstructions = if (!binding.exposeTcpSwitch.isChecked) {
            getString(R.string.tcp_disabled)
        } else if (tcpPort == null) {
            getString(R.string.tcp_port_invalid)
        } else {
            val phoneIps = if (state.running && state.config?.exposeTcp == true) {
                state.phoneIpAddresses
            } else {
                NetworkAddressHelper.ipv4Addresses()
            }
            if (phoneIps.isEmpty()) {
                getString(
                    R.string.tcp_waiting_for_network,
                    tcpPort,
                )
            } else {
                phoneIps.joinToString("\n") { ip ->
                    "adb connect $ip:$tcpPort"
                }
            }
        }

        binding.instructionsValue.text = buildString {
            appendLine(getString(R.string.instructions_classic_title))
            appendLine(classicInstructions)
            appendLine()
            appendLine(getString(R.string.instructions_tcp_title))
            append(tcpInstructions)
        }
    }

    private fun startSelectedBridge() {
        val selected = selectedDevice()
        if (selected == null) {
            Toast.makeText(this, R.string.no_paired_devices, Toast.LENGTH_SHORT).show()
            return
        }
        val tcpPort = validatedTcpPort()
        if (binding.exposeTcpSwitch.isChecked && tcpPort == null) {
            renderTcpPortState()
            Toast.makeText(this, R.string.tcp_port_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val config = BridgeConfig(
            deviceAddress = selected.address,
            deviceName = selected.name,
            exposeTcp = binding.exposeTcpSwitch.isChecked,
            tcpPort = tcpPort ?: BluetoothAdbBridgeService.DEFAULT_TCP_PORT,
        )
        prefs.saveConfig(config)
        BluetoothAdbBridgeService.start(this, config)
    }

    private fun selectedDevice(): DeviceEntry? {
        if (deviceEntries.isEmpty()) {
            return null
        }
        val position = binding.deviceSpinner.selectedItemPosition
        if (position !in deviceEntries.indices) {
            return null
        }
        return deviceEntries[position]
    }

    private fun ensurePermissionsForManualAction(startAfterGrant: Boolean) {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            return
        }
        pendingStartAfterPermission = startAfterGrant
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun renderPermissionHint() {
        val missing = missingPermissionLabels()
        binding.permissionHintValue.text = if (missing.isEmpty()) {
            getString(R.string.permission_ready)
        } else {
            getString(R.string.permission_missing, missing.joinToString())
        }
    }

    private fun hasRequiredPermissions(): Boolean = missingPermissions().isEmpty()

    private fun serviceStatusText(state: BridgeUiState): String {
        return when (state.serviceStatus) {
            ServiceStatus.STOPPED -> getString(R.string.service_stopped)
            ServiceStatus.STARTING -> getString(R.string.service_starting)
            ServiceStatus.RUNNING -> getString(R.string.service_running)
            ServiceStatus.STOPPING -> getString(R.string.service_stopping)
            ServiceStatus.FAULT -> getString(R.string.service_fault)
        }
    }

    private fun renderTcpPortState() {
        val showTcpPort = binding.exposeTcpSwitch.isChecked
        binding.tcpPortInputLayout.isVisible = showTcpPort
        binding.tcpPortInputLayout.error = if (showTcpPort && validatedTcpPort() == null) {
            getString(R.string.tcp_port_invalid)
        } else {
            null
        }
    }

    private fun ensureTcpPortDefault() {
        if (!binding.exposeTcpSwitch.isChecked) {
            return
        }
        if (binding.tcpPortEditText.text?.toString()?.trim().isNullOrEmpty()) {
            binding.tcpPortEditText.setText(BluetoothAdbBridgeService.DEFAULT_TCP_PORT.toString())
        }
    }

    private fun validatedTcpPort(): Int? {
        val portText = binding.tcpPortEditText.text?.toString()?.trim().orEmpty()
        val port = portText.toIntOrNull() ?: return null
        return port.takeIf { it in BridgeConfig.VALID_TCP_PORT_RANGE }
    }

    private fun currentPreviewTcpPort(state: BridgeUiState): Int? {
        return when {
            state.running && state.config?.exposeTcp == true -> state.config.tcpPort
            binding.exposeTcpSwitch.isChecked -> validatedTcpPort()
            else -> null
        }
    }

    private fun refreshControls(
        state: BridgeUiState = BridgeStateStore.state.value,
        selected: DeviceEntry? = selectedDevice(),
    ) {
        val tcpReady = !binding.exposeTcpSwitch.isChecked || validatedTcpPort() != null
        val canStart = state.serviceStatus == ServiceStatus.STOPPED
        val canStop = state.serviceStatus != ServiceStatus.STOPPED
        binding.stopServiceButton.isEnabled = canStop
        binding.startServiceButton.isEnabled =
            canStart &&
            selected?.address?.isNotBlank() == true &&
            hasRequiredPermissions() &&
            tcpReady
        binding.deviceSpinner.isEnabled = canStart
        binding.refreshDevicesButton.isEnabled = canStart
        binding.exposeTcpSwitch.isEnabled = canStart
        binding.tcpPortInputLayout.isEnabled = canStart && binding.exposeTcpSwitch.isChecked
        binding.tcpPortEditText.isEnabled = canStart && binding.exposeTcpSwitch.isChecked
    }

    private fun setEndpointExpanded(expanded: Boolean) {
        isEndpointExpanded = expanded
        binding.endpointContent.isVisible = expanded
        binding.endpointToggleIcon.setImageResource(if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right)
        binding.endpointHeader.contentDescription = if (expanded) {
            getString(R.string.endpoint_section_collapse)
        } else {
            getString(R.string.endpoint_section_expand)
        }
    }

    private fun setInstructionsExpanded(expanded: Boolean) {
        isInstructionsExpanded = expanded
        binding.instructionsContent.isVisible = expanded
        binding.instructionsToggleIcon.setImageResource(
            if (expanded) R.drawable.ic_expand_more else R.drawable.ic_chevron_right,
        )
        binding.instructionsHeader.contentDescription = if (expanded) {
            getString(R.string.instructions_section_collapse)
        } else {
            getString(R.string.instructions_section_expand)
        }
    }

    private fun setTcpPortTextIfNeeded(port: Int) {
        val expected = port.toString()
        if (binding.tcpPortEditText.text?.toString() != expected) {
            binding.tcpPortEditText.setText(expected)
        }
    }

    private fun copyTextToClipboard(label: String, text: String) {
        if (text.isBlank()) {
            return
        }
        val clipboardManager = getSystemService(ClipboardManager::class.java) ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun endpointClipboardText(): String {
        return buildString {
            appendLine("${getString(R.string.current_device_label)}：")
            appendLine(binding.selectedDeviceValue.text)
            appendLine()
            appendLine("${getString(R.string.local_socket_label)}：")
            appendLine(binding.localSocketValue.text)
            appendLine()
            appendLine("${getString(R.string.tcp_endpoint_label)}：")
            append(binding.tcpEndpointValue.text)
        }
    }

    private fun instructionsClipboardText(): String {
        return binding.instructionsValue.text?.toString().orEmpty()
    }

    private fun serviceStatusColorRes(state: BridgeUiState): Int {
        return when (state.serviceStatus) {
            ServiceStatus.STOPPED -> R.color.status_stopped
            ServiceStatus.STARTING -> R.color.status_connecting
            ServiceStatus.RUNNING -> R.color.status_connected
            ServiceStatus.STOPPING -> R.color.status_connecting
            ServiceStatus.FAULT -> R.color.status_error
        }
    }

    private fun endpointStatusColorRes(status: EndpointStatus): Int {
        return when (status) {
            EndpointStatus.CONNECTED -> R.color.status_connected
            EndpointStatus.CONNECTING -> R.color.status_connecting
            EndpointStatus.ERROR -> R.color.status_error
            EndpointStatus.DISCONNECTED -> R.color.status_disconnected
        }
    }

    private fun applyStatusVisual(
        textView: TextView,
        text: String,
        @ColorRes colorRes: Int,
    ) {
        val color = ContextCompat.getColor(this, colorRes)
        val icon = AppCompatResources.getDrawable(this, R.drawable.ic_status_circle)?.mutate()
        icon?.let { DrawableCompat.setTint(it, color) }
        textView.text = text
        textView.setTextColor(color)
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(textView, icon, null, null, null)
        textView.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    }

    private fun missingPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    private fun missingPermissionLabels(): List<String> {
        return missingPermissions().map { permission ->
            when (permission) {
                Manifest.permission.BLUETOOTH_SCAN -> getString(R.string.permission_nearby_devices)
                Manifest.permission.BLUETOOTH_CONNECT -> getString(R.string.permission_bluetooth_connect)
                Manifest.permission.POST_NOTIFICATIONS -> getString(R.string.permission_notifications)
                else -> permission
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        return getSystemService(BluetoothManager::class.java)?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private data class DeviceEntry(
        val name: String,
        val address: String,
    ) {
        override fun toString(): String = "$name ($address)"
    }
}
