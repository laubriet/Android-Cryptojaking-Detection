package com.example.monitoring

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.monitoring.model.Alert
import com.example.monitoring.model.AlertLevel
import com.example.monitoring.model.ShizukuState
import kotlin.math.roundToInt

private val Critical = Color(0xFFC62828)
private val Warn = Color(0xFFEF6C00)
private val Info = Color(0xFF1565C0)

private const val MAX_ROWS = 15

/** [createSource] n'est appelé qu'une fois : la source vit avec le ViewModel (survit aux rotations). */
@Composable
fun App(createSource: () -> MetricsSource) {
    val vm = viewModel { MonitorViewModel(createSource()) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val shizuku by vm.shizukuState.collectAsStateWithLifecycle()
    val s = ui.snapshot

    MaterialTheme {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Moniteur anti-cryptominer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            if (shizuku != ShizukuState.READY) {
                item { ShizukuBanner(shizuku, vm::requestShizuku) }
            }
            ui.error?.let { item { Text("Erreur : $it", color = Critical) } }

            item { AlertsCard(ui.alerts) }

            item {
                Section("Tension / batterie") {
                    val p = s.power
                    if (p == null) Na() else {
                        Line("Tension", "${p.voltageMv} mV (${(p.voltageMv / 1000.0).fmt(2)} V)")
                        Line("Courant", p.currentUa?.let { "${it / 1000} mA" } ?: "n/d")
                        Line("Niveau", "${p.levelPercent} %" + if (p.charging) " (en charge)" else "")
                    }
                }
            }

            item {
                Section("Température") {
                    val t = s.thermal
                    if (t == null) Na() else {
                        Line("Batterie", "${t.batteryCelsius} °C", if (t.batteryCelsius > 45) Warn else null)
                        Line("Statut thermique", thermalLabel(t.thermalStatus), if (t.thermalStatus >= 3) Critical else null)
                        Line("Marge thermique", if (t.headroom.isNaN()) "n/d" else "${(t.headroom * 100).roundToInt()} %")
                        if (t.zones.isEmpty() && shizuku != ShizukuState.READY) {
                            Hint("Zones thermiques détaillées : Shizuku requis")
                        }
                        t.zones.sortedByDescending { it.celsius }.take(MAX_ROWS).forEach {
                            Line(it.name, "${it.celsius.fmt(1)} °C", if (it.celsius > 80) Warn else null)
                        }
                    }
                }
            }

            item {
                Section("CPU") {
                    val cpu = s.cpu
                    if (cpu == null) ShizukuRequired() else {
                        val total = cpu.totalPercent
                        Line("Global (${cpu.coreCount} cœurs)", total?.let { "${it.roundToInt()} %" } ?: "calcul…")
                        if (total != null) {
                            LinearProgressIndicator(
                                progress = { (total / 100).toFloat() },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                color = if (total > 80) Critical else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Hint("Top process (%CPU par cœur, 100 % = 1 cœur plein)")
                        cpu.topProcesses.take(10).forEach {
                            val color = when {
                                Detector.isSuspiciousName(it.name) -> Critical
                                it.cpuPercent >= 70 -> Warn
                                else -> null
                            }
                            Line("${it.pid}  ${it.name}", "${it.cpuPercent.fmt(1)} %", color, mono = true)
                        }
                    }
                }
            }

            item {
                Section("Services en cours") {
                    val services = s.services
                    if (services == null) ShizukuRequired() else {
                        Hint("${services.size} services, ${services.count { it.foreground }} en premier plan")
                        services.take(MAX_ROWS * 2).forEach {
                            val color = if (Detector.isSuspiciousName(it.serviceName)) Critical else null
                            Line(it.serviceName, if (it.foreground) "FG" else "", color, mono = true)
                        }
                    }
                }
            }

            item {
                Section("Réseau") {
                    s.traffic?.let {
                        Line("Débit ↓", formatRate(it.rxBytesPerSec))
                        Line("Débit ↑", formatRate(it.txBytesPerSec))
                    }
                    val conns = s.connections
                    if (conns == null) ShizukuRequired() else {
                        val active = conns.filter { it.state != "LISTEN" && it.state != "UNCONN" }
                            .sortedByDescending { Detector.isMiningConnection(it) }
                        Hint("${active.size} connexions actives, ${conns.count { it.state == "LISTEN" }} ports en écoute")
                        active.take(MAX_ROWS * 2).forEach {
                            val color = if (Detector.isMiningConnection(it)) Critical else null
                            Line(
                                "${it.owner ?: "uid ${it.uid}"}\n${it.protocol} ${it.remoteAddress}:${it.remotePort}",
                                it.state,
                                color,
                                mono = true,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun ShizukuBanner(state: ShizukuState, onRequest: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
        Column(Modifier.padding(12.dp)) {
            when (state) {
                ShizukuState.NOT_RUNNING -> Text(
                    "Shizuku n'est pas démarré : CPU, services et connexions réseau sont indisponibles. " +
                        "Installez Shizuku et démarrez-le via ADB ou le débogage sans fil.",
                )
                ShizukuState.PERMISSION_REQUIRED -> {
                    Text("Shizuku est actif mais l'application n'est pas autorisée.")
                    Button(onClick = onRequest, modifier = Modifier.padding(top = 8.dp)) { Text("Autoriser via Shizuku") }
                }
                ShizukuState.READY -> Unit
            }
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<Alert>) {
    val worst = alerts.maxOfOrNull { it.level }
    val bg = when (worst) {
        AlertLevel.CRITICAL -> Color(0xFFFFEBEE)
        AlertLevel.WARN -> Color(0xFFFFF8E1)
        else -> Color(0xFFE8F5E9)
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Alertes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (alerts.isEmpty()) {
                Text("Aucune activité suspecte détectée", color = Color(0xFF2E7D32))
            }
            alerts.forEach {
                val color = when (it.level) {
                    AlertLevel.CRITICAL -> Critical
                    AlertLevel.WARN -> Warn
                    AlertLevel.INFO -> Info
                }
                Text("[${it.level}] ${it.culprit} — ${it.message}", color = color, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(2.dp))
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String, color: Color? = null, mono: Boolean = false) {
    val c = color ?: MaterialTheme.colorScheme.onSurface
    val family = if (mono) FontFamily.Monospace else FontFamily.Default
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = c,
            fontFamily = family,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, color = c, fontFamily = family, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ShizukuRequired() = Hint("Shizuku requis")

@Composable
private fun Na() = Hint("n/d")

private fun thermalLabel(status: Int) = when (status) {
    0 -> "Normal"
    1 -> "Léger"
    2 -> "Modéré"
    3 -> "Sévère"
    4 -> "Critique"
    5 -> "Urgence"
    6 -> "Arrêt"
    else -> "Inconnu ($status)"
}

private fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000 -> "${(bytesPerSec / 1_000_000.0).fmt(1)} Mo/s"
    bytesPerSec >= 1_000 -> "${(bytesPerSec / 1_000.0).fmt(1)} ko/s"
    else -> "$bytesPerSec o/s"
}

/** Arrondi à n décimales (String.format n'existe pas en commonMain). */
private fun Double.fmt(decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return ((this * factor).roundToInt() / factor).toString()
}
