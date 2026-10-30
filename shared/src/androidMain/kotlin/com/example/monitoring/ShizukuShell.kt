package com.example.monitoring

import android.content.pm.PackageManager
import android.util.Log
import com.example.monitoring.model.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Exécute des commandes shell avec l'UID `shell` (2000) via Shizuku.
 * Cela donne accès à /proc/stat, top, dumpsys et /proc/net bloqués pour une app normale.
 */
class ShizukuShell {

    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    init {
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener { refresh() }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
    }

    fun refresh() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.PERMISSION_REQUIRED
        }
    } catch (e: Exception) {
        ShizukuState.NOT_RUNNING
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Retourne la sortie standard de [command], ou null si Shizuku indisponible / timeout. */
    suspend fun exec(command: String, timeoutMs: Long = 8_000): String? {
        refresh()
        if (_state.value != ShizukuState.READY) return null
        return withContext(Dispatchers.IO) {
            val process = try {
                newProcess.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            } catch (e: Exception) {
                Log.w(TAG, "Impossible de lancer la commande via Shizuku", e)
                return@withContext null
            }
            try {
                coroutineScope {
                    // stderr est drainé en parallèle pour ne pas bloquer le process.
                    async { process.errorStream.bufferedReader().use { it.readText() } }
                    val out = async { process.inputStream.bufferedReader().use { it.readText() } }
                    withTimeoutOrNull(timeoutMs) { out.await() }
                        ?: run { process.destroy(); null }
                }
            } finally {
                process.destroy()
            }
        }
    }

    private companion object {
        const val TAG = "ShizukuShell"
        const val REQUEST_CODE = 4242

        /** `Shizuku.newProcess` est privé depuis l'API 13 : appel par réflexion (suffisant pour un POC). */
        val newProcess: Method by lazy {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }
    }
}
