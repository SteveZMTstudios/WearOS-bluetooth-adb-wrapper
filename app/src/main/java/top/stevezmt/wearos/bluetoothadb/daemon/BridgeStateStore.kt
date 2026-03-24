package top.stevezmt.wearos.bluetoothadb.daemon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BridgeStateStore {
    private val _state = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = _state.asStateFlow()

    fun update(transform: (BridgeUiState) -> BridgeUiState) {
        _state.update(transform)
    }

    fun reset(config: BridgeConfig? = _state.value.config) {
        _state.value = BridgeUiState(config = config)
    }
}
