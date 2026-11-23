package com.pdm.app.data

import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.repo.PdmException
import com.pdm.app.data.repo.isNetworkFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class TaskPoller(
    // ~3.5 min: a guest Shutdown waits for the OS, which routinely outlasts 30 s.
    private val maxAttempts: Int = 210,
    private val intervalMs: Long = 1000L,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun awaitStop(fetch: suspend () -> TaskStatus): TaskStatus {
        var last = TaskStatus(status = "running")
        var blips = 0
        repeat(maxAttempts) {
            try {
                last = fetch()
                blips = 0
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // TLS, auth and a changed session are not a blip: stop the watch.
                if (!e.isNetworkFailure()) throw e
                blips += 1
                if (blips >= NETWORK_BLIPS) throw e
                wait(intervalMs)
                return@repeat
            }
            if (!last.isRunning) return last
            wait(intervalMs)
        }
        throw PdmException("Remote task still running after $maxAttempts polls (${last.upid ?: last.status})")
    }

    private companion object {
        const val NETWORK_BLIPS = 3
    }
}

/** OK, or a finished task whose only problem was warnings. A blank status is not a success. */
internal fun isTaskExitOk(status: TaskStatus): Boolean =
    status.isOk || status.exitstatus.orEmpty().startsWith("WARNINGS", ignoreCase = true)
