package com.example.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import com.example.monitoring.model.CpuInfo
import com.example.monitoring.model.NetConnection
import com.example.monitoring.model.NetTraffic
import com.example.monitoring.model.PowerInfo
import com.example.monitoring.model.Protocol
import com.example.monitoring.model.ServiceInfo
import com.example.monitoring.model.ShizukuState
import com.example.monitoring.model.Snapshot
import com.example.monitoring.model.ThermalInfo
import com.example.monitoring.model.ThermalZone
import com.example.monitoring.parsing.CpuTimes
import com.example.monitoring.parsing.Parsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class AndroidMetricsSource(private val context: Context) : MetricsSource {

    private val shell = ShizukuShell()
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val packageManager = context.packageManager
    private val coreCount = Runtime.getRuntime().availableProcessors()

    private var previousCpu: CpuTimes? = null
    private var previousTraffic: Triple<Long, Long, Long>? = null // rx, tx, elapsedMs
    private var cachedServices: List<ServiceInfo>? = null
    private var tick = 0
    private val uidNames = mutableMapOf<Int, String>()

    override val shizukuState: StateFlow<ShizukuState> = shell.state

    override fun requestShizuku() = shell.requestPermission()

    override suspend fun collect(): Snapshot = withContext(Dispatchers.IO) {
        val refreshServices = tick++ % SERVICES_EVERY_N_TICKS == 0 || cachedServices == null
        val sections = shell.exec(buildScript(refreshServices))?.let(Parsers::splitSections)

        val thermalZones = sections?.get("thermal")?.let(Parsers::parseThermalZones).orEmpty()
        val cpu = sections?.let { readCpu(it) }
        val connections = sections?.let { readConnections(it) }
        if (sections == null) {
            cachedServices = null
            previousCpu = null
        } else if (refreshServices) {
            cachedServices = sections["services"]?.let(Parsers::parseServices)
        }

        Snapshot(
            power = readPower(),
            thermal = readThermal(thermalZones),
            cpu = cpu,
            services = cachedServices,
            traffic = readTraffic(),
            connections = connections,
        )
    }

    /** Un seul appel Shizuku par tick ; chaque bloc est préfixé par un marqueur `@@section`. */
    private fun buildScript(withServices: Boolean) = buildString {
        appendLine("echo @@stat; head -n 1 /proc/stat")
        appendLine("echo @@top; top -b -n 2 -d 1 -m $TOP_PROCESSES -o PID,USER,%CPU,ARGS 2>/dev/null")
        for (p in listOf("tcp", "tcp6", "udp", "udp6")) appendLine("echo @@$p; cat /proc/net/$p 2>/dev/null")
        appendLine("echo @@thermal; for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat \$z/type 2>/dev/null) $(cat \$z/temp 2>/dev/null)\"; done")
        if (withServices) appendLine("echo @@services; dumpsys activity services 2>/dev/null")
    }

    private fun readCpu(sections: Map<String, String>): CpuInfo {
        val current = sections["stat"]?.let(Parsers::parseProcStat)
        val total = previousCpu?.let { prev -> current?.let { Parsers.cpuPercent(prev, it) } }
        previousCpu = current
        val top = sections["top"]?.let(Parsers::parseTop).orEmpty()
        return CpuInfo(totalPercent = total, coreCount = coreCount, topProcesses = top)
    }

    private fun readConnections(sections: Map<String, String>): List<NetConnection> =
        listOf("tcp" to Protocol.TCP, "tcp6" to Protocol.TCP6, "udp" to Protocol.UDP, "udp6" to Protocol.UDP6)
            .flatMap { (key, proto) -> sections[key]?.let { Parsers.parseProcNet(it, proto) }.orEmpty() }
            .map { it.copy(owner = nameForUid(it.uid)) }

    private fun nameForUid(uid: Int): String = uidNames.getOrPut(uid) {
        when (uid) {
            0 -> "root"
            else -> packageManager.getPackagesForUid(uid)?.joinToString(",")
                ?: packageManager.getNameForUid(uid)
                ?: "uid $uid"
        }
    }

    private fun readPower(): PowerInfo? {
        val intent = batteryIntent() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return PowerInfo(
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            currentUa = current.takeIf { it != Long.MIN_VALUE && it != 0L },
            levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    private fun readThermal(zones: List<ThermalZone>): ThermalInfo {
        val tenths = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return ThermalInfo(
            batteryCelsius = tenths / 10.0,
            thermalStatus = powerManager.currentThermalStatus,
            headroom = powerManager.getThermalHeadroom(10),
            zones = zones,
        )
    }

    private fun readTraffic(): NetTraffic? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (rx == TrafficStats.UNSUPPORTED.toLong()) return null
        val prev = previousTraffic
        previousTraffic = Triple(rx, tx, now)
        if (prev == null) return NetTraffic(0, 0)
        val dt = (now - prev.third).coerceAtLeast(1)
        return NetTraffic(
            rxBytesPerSec = (rx - prev.first) * 1000 / dt,
            txBytesPerSec = (tx - prev.second) * 1000 / dt,
        )
    }

    /** Intent "sticky" : pas besoin d'enregistrer un vrai receiver. */
    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)

    private companion object {
        const val TOP_PROCESSES = 15
        const val SERVICES_EVERY_N_TICKS = 5
    }
}
