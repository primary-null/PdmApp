package com.pdm.app.data

import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.repo.PdmException
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPollerTest {
    @Test
    fun stopsWhenStatusLeavesRunning() = runTest {
        val states = ArrayDeque(listOf("running", "running", "stopped"))
        val poller = TaskPoller(maxAttempts = 10, intervalMs = 1L, wait = {})
        val result = poller.awaitStop {
            TaskStatus(status = states.removeFirst(), exitstatus = "OK")
        }
        assertFalse(result.isRunning)
        assertEquals("OK", result.exitstatus)
        assertTrue(states.isEmpty())
    }

    @Test
    fun runningIsTheStopCondition() {
        assertTrue(TaskStatus(status = "running").isRunning)
        assertFalse(TaskStatus(status = "stopped").isRunning)
        assertFalse(TaskStatus(status = null).isRunning)
    }

    @Test
    fun twoNetworkBlipsThenSuccess() = runTest {
        val poller = TaskPoller(maxAttempts = 10, intervalMs = 1L, wait = {})
        var attempts = 0
        val result = poller.awaitStop {
            attempts++
            if (attempts <= 2) throw ConnectException("down")
            TaskStatus(status = "stopped", exitstatus = "OK")
        }
        assertEquals("OK", result.exitstatus)
        assertEquals(3, attempts)
    }

    @Test
    fun threeNetworkBlipsAbort() = runTest {
        val poller = TaskPoller(maxAttempts = 10, intervalMs = 1L, wait = {})
        var attempts = 0
        try {
            poller.awaitStop {
                attempts++
                throw IOException("timeout")
            }
            org.junit.Assert.fail("expected the third blip to surface")
        } catch (e: IOException) {
            assertEquals(3, attempts)
            assertEquals("timeout", e.message)
        }
    }

    @Test
    fun tlsIsNotABlip() = runTest {
        val poller = TaskPoller(maxAttempts = 10, intervalMs = 1L, wait = {})
        var attempts = 0
        try {
            poller.awaitStop {
                attempts++
                throw SSLHandshakeException("certificate")
            }
            org.junit.Assert.fail("TLS must abort immediately")
        } catch (_: SSLHandshakeException) {
            assertEquals(1, attempts)
        }
    }

    @Test
    fun warningsCountAsAFinishedTask() {
        assertTrue(isTaskExitOk(TaskStatus(status = "stopped", exitstatus = "OK")))
        assertTrue(isTaskExitOk(TaskStatus(status = "stopped", exitstatus = "WARNINGS: 1")))
        assertTrue(isTaskExitOk(TaskStatus(status = "stopped", exitstatus = "warnings: apt")))
        assertFalse(isTaskExitOk(TaskStatus(status = "stopped", exitstatus = "ERROR")))
        assertFalse(isTaskExitOk(TaskStatus(status = "stopped", exitstatus = null)))
    }

    @Test
    fun throwsIfStillRunning() = runTest {
        val poller = TaskPoller(maxAttempts = 3, intervalMs = 1L, wait = {})
        try {
            poller.awaitStop { TaskStatus(status = "running") }
            org.junit.Assert.fail("expected PdmException")
        } catch (_: PdmException) {
        }
    }
}
