package com.example.monitoring

import com.example.monitoring.model.Alert
import com.example.monitoring.model.AlertLevel
import com.example.monitoring.model.NetConnection
import com.example.monitoring.model.Snapshot
import com.example.monitoring.parsing.Parsers

/**
 * Heuristiques simples de détection de cryptominer.
 * Garde un historique minimal entre deux appels à [analyze] (un appel par tick).
 */
class Detector(
    private val processCpuThreshold: Double = 70.0,
    private val warnTicks: Int = 5,
    private val criticalTicks: Int = 15,
    private val globalCpuThreshold: Double = 80.0,
) {
    companion object {
        /** Ports couramment utilisés par les pools de minage (stratum). */
        val MINING_PORTS = setOf(3333, 3334, 4444, 5555, 6666, 7777, 8888, 9999, 14433, 14444, 20580, 45560, 45700)

        val SUSPICIOUS_KEYWORDS = listOf("xmrig", "minerd", "cpuminer", "cgminer", "bfgminer", "ethminer", "nicehash", "stratum", "miner", "monero", "coinhive")

        fun isSuspiciousName(name: String): Boolean {
            val lower = name.lowercase()
            return SUSPICIOUS_KEYWORDS.any { it in lower }
        }

        fun isMiningConnection(c: NetConnection): Boolean =
            c.remotePort in MINING_PORTS && !Parsers.isUnspecified(c.remoteAddress) && c.state != "LISTEN"
    }

    /** Nombre de ticks consécutifs au-dessus du seuil, par process (clé = pid:nom). */
    private val highCpuStreak = mutableMapOf<String, Int>()
    private var globalHighStreak = 0

    fun analyze(snapshot: Snapshot): List<Alert> {
        val alerts = mutableListOf<Alert>()

        snapshot.cpu?.let { cpu ->
            val seen = mutableSetOf<String>()
            for (p in cpu.topProcesses) {
                val key = "${p.pid}:${p.name}"
                if (p.cpuPercent >= processCpuThreshold) {
                    seen += key
                    val streak = (highCpuStreak[key] ?: 0) + 1
                    highCpuStreak[key] = streak
                    when {
                        streak >= criticalTicks -> alerts += Alert(AlertLevel.CRITICAL, "CPU élevé soutenu (${p.cpuPercent.toInt()} %, $streak ticks)", p.name)
                        streak >= warnTicks -> alerts += Alert(AlertLevel.WARN, "CPU élevé (${p.cpuPercent.toInt()} %, $streak ticks)", p.name)
                    }
                }
                if (isSuspiciousName(p.name)) {
                    alerts += Alert(AlertLevel.CRITICAL, "Nom de process suspect", p.name)
                }
            }
            highCpuStreak.keys.retainAll(seen)

            val total = cpu.totalPercent
            globalHighStreak = if (total != null && total >= globalCpuThreshold) globalHighStreak + 1 else 0
            if (globalHighStreak >= warnTicks) {
                alerts += Alert(AlertLevel.WARN, "CPU global > ${globalCpuThreshold.toInt()} % depuis $globalHighStreak ticks", "système")
            }
        }

        snapshot.connections?.forEach { c ->
            val who = c.owner ?: "uid ${c.uid}"
            if (isMiningConnection(c)) {
                alerts += Alert(AlertLevel.CRITICAL, "Connexion vers port de pool ${c.remoteAddress}:${c.remotePort}", who)
            }
            if (c.owner != null && isSuspiciousName(c.owner)) {
                alerts += Alert(AlertLevel.CRITICAL, "Accès réseau d'une app au nom suspect", who)
            }
        }

        snapshot.services?.filter { isSuspiciousName(it.packageName) || isSuspiciousName(it.serviceName) }?.forEach {
            alerts += Alert(AlertLevel.CRITICAL, "Service suspect${if (it.foreground) " (foreground)" else ""}", it.serviceName)
        }

        snapshot.thermal?.let { t ->
            if (t.thermalStatus >= 3) {
                alerts += Alert(AlertLevel.CRITICAL, "Statut thermique critique (niveau ${t.thermalStatus})", "système")
            }
            if (t.batteryCelsius > 45) {
                alerts += Alert(AlertLevel.WARN, "Batterie chaude (${t.batteryCelsius} °C)", "batterie")
            }
            t.zones.filter { it.celsius > 80 }.forEach {
                alerts += Alert(AlertLevel.WARN, "Zone thermique chaude (${it.celsius.toInt()} °C)", it.name)
            }
        }

        return alerts.distinct().sortedByDescending { it.level }
    }
}
