package com.example.monitoring

import com.example.monitoring.model.AlertLevel
import com.example.monitoring.model.CpuInfo
import com.example.monitoring.model.NetConnection
import com.example.monitoring.model.ProcessInfo
import com.example.monitoring.model.Protocol
import com.example.monitoring.model.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetectorTest {

    private fun cpuSnapshot(cpu: Double, name: String = "com.example.busy") = Snapshot(
        cpu = CpuInfo(totalPercent = 50.0, coreCount = 8, topProcesses = listOf(ProcessInfo(42, "u0_a1", cpu, name))),
    )

    @Test
    fun sustainedCpuEscalates() {
        val d = Detector(warnTicks = 2, criticalTicks = 4)
        assertTrue(d.analyze(cpuSnapshot(95.0)).isEmpty())
        assertEquals(AlertLevel.WARN, d.analyze(cpuSnapshot(95.0)).single().level)
        d.analyze(cpuSnapshot(95.0))
        assertEquals(AlertLevel.CRITICAL, d.analyze(cpuSnapshot(95.0)).single().level)
        // La série est remise à zéro dès que le process repasse sous le seuil.
        assertTrue(d.analyze(cpuSnapshot(5.0)).isEmpty())
        assertTrue(d.analyze(cpuSnapshot(95.0)).isEmpty())
    }

    @Test
    fun suspiciousProcessName() {
        val alerts = Detector().analyze(cpuSnapshot(1.0, "./xmrig -o pool"))
        assertEquals(AlertLevel.CRITICAL, alerts.single().level)
    }

    @Test
    fun miningPortConnection() {
        val conn = NetConnection(Protocol.TCP, "10.0.0.2", 50000, "1.2.3.4", 3333, "ESTABLISHED", 10150, "com.evil")
        val listening = conn.copy(remoteAddress = "0.0.0.0", remotePort = 3333, state = "LISTEN")
        val alerts = Detector().analyze(Snapshot(connections = listOf(conn, listening)))
        assertEquals(1, alerts.size)
        assertEquals("com.evil", alerts[0].culprit)
    }
}
