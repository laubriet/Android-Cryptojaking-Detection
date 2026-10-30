package com.example.monitoring

import com.example.monitoring.model.ShizukuState
import com.example.monitoring.model.Snapshot
import kotlinx.coroutines.flow.StateFlow

interface MetricsSource {
    val shizukuState: StateFlow<ShizukuState>

    fun requestShizuku()

    /** Collecte un instantané de toutes les métriques disponibles. */
    suspend fun collect(): Snapshot
}
