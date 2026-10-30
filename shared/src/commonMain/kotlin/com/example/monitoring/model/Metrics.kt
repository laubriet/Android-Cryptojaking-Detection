package com.example.monitoring.model

data class PowerInfo(
    val voltageMv: Int,
    val currentUa: Long?,
    val levelPercent: Int,
    val charging: Boolean,
)

data class ThermalZone(val name: String, val celsius: Double)

data class ThermalInfo(
    val batteryCelsius: Double,
    /** Valeur PowerManager.THERMAL_STATUS_* (0 = NONE … 6 = SHUTDOWN). */
    val thermalStatus: Int,
    /** 1.0 = throttling imminent, NaN si non supporté. */
    val headroom: Float,
    val zones: List<ThermalZone> = emptyList(),
)

data class ProcessInfo(
    val pid: Int,
    val user: String,
    val cpuPercent: Double,
    val name: String,
)

data class CpuInfo(
    val totalPercent: Double?,
    val coreCount: Int,
    val topProcesses: List<ProcessInfo>,
)

data class ServiceInfo(
    val packageName: String,
    val serviceName: String,
    val foreground: Boolean,
)

enum class Protocol { TCP, TCP6, UDP, UDP6 }

data class NetConnection(
    val protocol: Protocol,
    val localAddress: String,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val state: String,
    val uid: Int,
    val owner: String? = null,
)

data class NetTraffic(
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
)

enum class AlertLevel { INFO, WARN, CRITICAL }

data class Alert(
    val level: AlertLevel,
    val message: String,
    val culprit: String,
)

enum class ShizukuState {
    /** Shizuku n'est pas installé ou pas démarré. */
    NOT_RUNNING,
    /** Shizuku tourne mais l'app n'a pas été autorisée. */
    PERMISSION_REQUIRED,
    READY,
}

data class Snapshot(
    val power: PowerInfo? = null,
    val thermal: ThermalInfo? = null,
    /** null = Shizuku requis. */
    val cpu: CpuInfo? = null,
    val services: List<ServiceInfo>? = null,
    val traffic: NetTraffic? = null,
    val connections: List<NetConnection>? = null,
)
