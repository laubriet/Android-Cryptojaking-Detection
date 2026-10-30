package com.example.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.monitoring.model.Alert
import com.example.monitoring.model.ShizukuState
import com.example.monitoring.model.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class UiState(
    val snapshot: Snapshot = Snapshot(),
    val alerts: List<Alert> = emptyList(),
    val error: String? = null,
)

class MonitorViewModel(
    private val source: MetricsSource,
    private val refreshMs: Long = 2_000,
) : ViewModel() {

    private val detector = Detector()

    val shizukuState: StateFlow<ShizukuState> = source.shizukuState

    /** Le polling ne tourne que tant que l'UI est visible (WhileSubscribed). */
    val state: StateFlow<UiState> = flow {
        while (true) {
            val next = try {
                val snapshot = source.collect()
                UiState(snapshot, detector.analyze(snapshot))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState(error = e.message ?: e::class.simpleName)
            }
            emit(next)
            delay(refreshMs)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun requestShizuku() = source.requestShizuku()
}
