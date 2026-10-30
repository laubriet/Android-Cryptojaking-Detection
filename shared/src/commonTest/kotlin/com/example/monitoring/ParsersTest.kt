package com.example.monitoring

import com.example.monitoring.model.Protocol
import com.example.monitoring.parsing.CpuTimes
import com.example.monitoring.parsing.Parsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsersTest {

    @Test
    fun splitSections() {
        val s = Parsers.splitSections("@@a\nline1\nline2\n@@b\nx\n")
        assertEquals(setOf("a", "b"), s.keys)
        assertTrue(s.getValue("a").contains("line2"))
    }

    @Test
    fun procStatAndPercent() {
        val a = Parsers.parseProcStat("cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 1 2 3 4")!!
        assertEquals(CpuTimes(idle = 800, total = 1000), a)
        val b = Parsers.parseProcStat("cpu  200 0 200 1400 0 0 0 0 0 0")!!
        assertEquals(25.0, Parsers.cpuPercent(a, b))
    }

    @Test
    fun topKeepsLastFrame() {
        val out = """
            Tasks: 500 total
            800%cpu  10%user
              PID USER         [%CPU ARGS
             1234 u0_a99       50.0 com.old.frame
            Tasks: 500 total
            800%cpu  10%user
              PID USER         [%CPU ARGS
             4321 u0_a150     398.5 ./xmrig --donate-level 1
              999 u0_a12        2.0 com.android.systemui
             5555 shell         1.0 top -b -n 2
        """.trimIndent()
        val procs = Parsers.parseTop(out)
        assertEquals(listOf(4321, 999), procs.map { it.pid })
        assertEquals(398.5, procs[0].cpuPercent)
        assertEquals("./xmrig --donate-level 1", procs[0].name)
    }

    @Test
    fun procNetTcpIpv4() {
        val text = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:13AD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1 1
               1: 0F02000A:C350 2A00A8C0:0D05 01 00000000:00000000 00:00000000 00000000 10150        0 2 1
        """.trimIndent()
        val conns = Parsers.parseProcNet(text, Protocol.TCP)
        assertEquals(2, conns.size)
        assertEquals("127.0.0.1", conns[0].localAddress)
        assertEquals(5037, conns[0].localPort)
        assertEquals("LISTEN", conns[0].state)
        assertEquals("192.168.0.42", conns[1].remoteAddress)
        assertEquals(3333, conns[1].remotePort)
        assertEquals("ESTABLISHED", conns[1].state)
        assertEquals(10150, conns[1].uid)
    }

    @Test
    fun procNetTcp6MappedIpv4() {
        val text = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid
               0: 0000000000000000FFFF00000F02000A:C350 0000000000000000FFFF00002A00A8C0:115C 01 00000000:00000000 00:00000000 00000000 10150 0 3 1
        """.trimIndent()
        val c = Parsers.parseProcNet(text, Protocol.TCP6).single()
        assertEquals("10.0.2.15", c.localAddress)
        assertEquals("192.168.0.42", c.remoteAddress)
        assertEquals(4444, c.remotePort)
    }

    @Test
    fun services() {
        val text = """
            ACTIVITY MANAGER SERVICES (dumpsys activity services)
              User 0 active services:
              * ServiceRecord{a1b2c3 u0 com.evil.app/.MiningService}
                packageName=com.evil.app
                isForeground=true foregroundId=1
              * ServiceRecord{d4e5f6 u0 com.google.android.gms/com.google.android.gms.chimera.GmsApiService}
                isForeground=false
        """.trimIndent()
        val services = Parsers.parseServices(text)
        assertEquals(2, services.size)
        assertEquals("com.evil.app.MiningService", services[0].serviceName)
        assertTrue(services[0].foreground)
        assertEquals("com.google.android.gms", services[1].packageName)
    }

    @Test
    fun thermalZones() {
        val zones = Parsers.parseThermalZones("cpu-0-0 45200\nbattery 31\nbogus -273000\nskin therm 38000\n")
        assertEquals(listOf("cpu-0-0" to 45.2, "battery" to 31.0, "skin therm" to 38.0), zones.map { it.name to it.celsius })
    }
}
