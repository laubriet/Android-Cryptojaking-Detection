package com.example.monitoring.parsing

import com.example.monitoring.model.NetConnection
import com.example.monitoring.model.ProcessInfo
import com.example.monitoring.model.Protocol
import com.example.monitoring.model.ServiceInfo
import com.example.monitoring.model.ThermalZone

/** Compteurs cumulés de la ligne `cpu` de /proc/stat (en jiffies). */
data class CpuTimes(val idle: Long, val total: Long)

object Parsers {

    private val whitespace = Regex("\\s+")
    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    /** Découpe une sortie shell structurée en sections `@@nom`. */
    fun splitSections(output: String): Map<String, String> {
        val sections = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in output.lineSequence()) {
            if (line.startsWith("@@")) {
                current = StringBuilder().also { sections[line.removePrefix("@@").trim()] = it }
            } else {
                current?.appendLine(line)
            }
        }
        return sections.mapValues { it.value.toString() }
    }

    // ---------- CPU ----------

    fun parseProcStat(text: String): CpuTimes? {
        val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val values = line.trim().split(whitespace).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        // user nice system idle iowait irq softirq steal (guest est déjà inclus dans user)
        val idle = values[3] + values.getOrElse(4) { 0 }
        val total = values.take(8).sum()
        return CpuTimes(idle, total)
    }

    fun cpuPercent(previous: CpuTimes, current: CpuTimes): Double? {
        val dTotal = current.total - previous.total
        val dIdle = current.idle - previous.idle
        if (dTotal <= 0) return null
        return ((dTotal - dIdle).toDouble() / dTotal * 100).coerceIn(0.0, 100.0)
    }

    /**
     * Parse la sortie de `top -b -n 2 -d 1 -m N -o PID,USER,%CPU,ARGS`.
     * Seule la dernière trame est gardée (la première n'a pas de delta fiable).
     */
    fun parseTop(text: String): List<ProcessInfo> {
        val clean = text.replace(ansi, "")
        val lines = clean.lines()
        val lastFrameStart = lines.indexOfLast { it.trimStart().startsWith("Tasks:") }
        return lines.drop(maxOf(lastFrameStart, 0))
            .mapNotNull { parseTopRow(it) }
            .filterNot { it.name.startsWith("top ") || it.name == "top" }
            .sortedByDescending { it.cpuPercent }
    }

    private fun parseTopRow(line: String): ProcessInfo? {
        val parts = line.trim().split(whitespace, limit = 4)
        if (parts.size < 4) return null
        val pid = parts[0].toIntOrNull() ?: return null
        val cpu = parts[2].toDoubleOrNull() ?: return null
        return ProcessInfo(pid = pid, user = parts[1], cpuPercent = cpu, name = parts[3].trim())
    }

    // ---------- Réseau ----------

    private val tcpStates = mapOf(
        "01" to "ESTABLISHED", "02" to "SYN_SENT", "03" to "SYN_RECV",
        "04" to "FIN_WAIT1", "05" to "FIN_WAIT2", "06" to "TIME_WAIT",
        "07" to "CLOSE", "08" to "CLOSE_WAIT", "09" to "LAST_ACK",
        "0A" to "LISTEN", "0B" to "CLOSING",
    )

    /** Parse le contenu de /proc/net/{tcp,tcp6,udp,udp6}. */
    fun parseProcNet(text: String, protocol: Protocol): List<NetConnection> =
        text.lineSequence()
            .drop(1) // en-tête
            .mapNotNull { parseProcNetRow(it, protocol) }
            .toList()

    private fun parseProcNetRow(line: String, protocol: Protocol): NetConnection? {
        val f = line.trim().split(whitespace)
        if (f.size < 8) return null
        val (localIp, localPort) = parseHexEndpoint(f[1]) ?: return null
        val (remoteIp, remotePort) = parseHexEndpoint(f[2]) ?: return null
        val stateHex = f[3].uppercase()
        val state = when (protocol) {
            Protocol.TCP, Protocol.TCP6 -> tcpStates[stateHex] ?: stateHex
            Protocol.UDP, Protocol.UDP6 -> if (stateHex == "01") "ESTABLISHED" else "UNCONN"
        }
        val uid = f[7].toIntOrNull() ?: return null
        return NetConnection(protocol, localIp, localPort, remoteIp, remotePort, state, uid)
    }

    private fun parseHexEndpoint(s: String): Pair<String, Int>? {
        val idx = s.indexOf(':')
        if (idx < 0) return null
        val port = s.substring(idx + 1).toIntOrNull(16) ?: return null
        val hex = s.substring(0, idx)
        val ip = when (hex.length) {
            8 -> ipv4FromLittleEndianHex(hex)
            32 -> ipv6FromProcHex(hex)
            else -> null
        } ?: return null
        return ip to port
    }

    /** "0100007F" -> "127.0.0.1" (mot 32 bits en little-endian). */
    private fun ipv4FromLittleEndianHex(hex: String): String? {
        val bytes = (0 until 4).map { hex.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }
        return bytes.reversed().joinToString(".")
    }

    /** IPv6 : 4 mots 32 bits, chacun en little-endian. Les adresses IPv4-mapped sont rendues en IPv4. */
    private fun ipv6FromProcHex(hex: String): String? {
        val bytes = ArrayList<Int>(16)
        for (word in 0 until 4) {
            val w = hex.substring(word * 8, word * 8 + 8)
            val wb = (0 until 4).map { w.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: return null }
            bytes.addAll(wb.reversed())
        }
        val isV4Mapped = bytes.subList(0, 10).all { it == 0 } && bytes[10] == 0xFF && bytes[11] == 0xFF
        if (isV4Mapped) return bytes.subList(12, 16).joinToString(".")
        if (bytes.all { it == 0 }) return "::"
        return (0 until 8).joinToString(":") { i ->
            ((bytes[i * 2] shl 8) or bytes[i * 2 + 1]).toString(16)
        }
    }

    fun isUnspecified(address: String): Boolean = address == "0.0.0.0" || address == "::"

    // ---------- Services ----------

    private val serviceRecord = Regex("""\*\s*ServiceRecord\{\S+\s+u\d+\s+([^/\s]+)/([^}\s]+)\}""")

    /** Parse `dumpsys activity services`. */
    fun parseServices(text: String): List<ServiceInfo> {
        val result = LinkedHashMap<String, ServiceInfo>()
        var current: ServiceInfo? = null
        fun flush() {
            current?.let { s ->
                val key = "${s.packageName}/${s.serviceName}"
                val existing = result[key]
                if (existing == null || (!existing.foreground && s.foreground)) result[key] = s
            }
        }
        for (line in text.lineSequence()) {
            val match = serviceRecord.find(line)
            if (match != null) {
                flush()
                val pkg = match.groupValues[1]
                val cls = match.groupValues[2]
                current = ServiceInfo(pkg, if (cls.startsWith(".")) pkg + cls else cls, foreground = false)
            } else if (current != null && line.contains("isForeground=true")) {
                current = current.copy(foreground = true)
            }
        }
        flush()
        return result.values.sortedWith(compareByDescending<ServiceInfo> { it.foreground }.thenBy { it.packageName })
    }

    // ---------- Thermique ----------

    /** Lignes "type valeur" issues de /sys/class/thermal/thermal_zone*. */
    fun parseThermalZones(text: String): List<ThermalZone> =
        text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(whitespace)
            if (parts.size < 2) return@mapNotNull null
            val raw = parts.last().toDoubleOrNull() ?: return@mapNotNull null
            val celsius = if (raw > 1000 || raw < -1000) raw / 1000 else raw
            if (celsius <= 0 || celsius > 150) return@mapNotNull null
            ThermalZone(parts.dropLast(1).joinToString(" "), celsius)
        }.toList()
}
