package com.pdm.app

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.api.PdmApi
import com.pdm.app.data.model.*
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.repo.PdmHttpException
import com.pdm.app.data.session.SessionStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

/** Parent-authored regressions, grounded in the released PDM API rather than DemoApi assumptions. */
class ParentAcceptanceRegressionTest {
    private fun repository(api: PdmApi): FleetRepository {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(
            SessionState(
                config = ServerConfig(host = "test.invalid", username = "operator@pdm", authMode = AuthMode.PASSWORD),
                username = "operator@pdm",
                ticket = "test-ticket",
                csrf = "test-csrf",
            ),
        )
        return FleetRepository(store, { api })
    }

    @Test
    fun hostAptCheckUsesSystemModifyAtApiRoot() {
        val permitted = Permissions.of(mapOf("/" to setOf("System.Modify")))
        assertTrue("PDM host APT POST requires System.Modify at /", permitted.canCheckHostUpdates())
        val wrongScope = Permissions.of(mapOf("/system/packages" to setOf("System.Modify")))
        assertFalse("A child-path grant cannot satisfy the API root requirement", wrongScope.canCheckHostUpdates())
    }

    @Test
    fun sharedNotesEditUsesTheServerPrivilegeAndPath() {
        val permitted = Permissions.of(mapOf("/system/notes" to setOf("System.Modify")))
        assertTrue("PDM notes PUT requires System.Modify at /system/notes", permitted.canEditSharedNotes())
        val wrongScope = Permissions.of(mapOf("/config/notes" to setOf("System.Modify")))
        assertFalse(wrongScope.canEditSharedNotes())
    }

    @Test
    fun applicationHttpExceptionIsClassifiedAsForbidden() = runBlocking {
        val result = repository(DemoApi()).safeAuditRead<String> {
            throw PdmHttpException(403, null, "Forbidden")
        }
        assertTrue("The app's own HTTP exception must retain forbidden semantics", result is ReadAvailability.Forbidden)
    }

    @Test
    fun boundedLogTailIncludesTheEndBeyondItsRetentionLimit() = runBlocking {
        val count = 3500
        val api = object : PdmApi by DemoApi() {
            override suspend fun remoteTaskLog(
                kind: String, remote: String, upid: String, start: Int, limit: Int,
            ): PdmResponse<List<TaskLogLine>> {
                val end = (start + limit).coerceAtMost(count)
                val rows = if (start >= count) emptyList() else (start until end).map {
                    TaskLogLine(it.toLong() + 1, if (it == count - 1) "TASK OK" else "line " + (it + 1))
                }
                return PdmResponse(data = rows, total = count.toLong())
            }
        }
        val task = RemoteTask(
            upid = "pve:edge-a!UPID:node:00000001:00000001:00000001:test::operator@pdm:",
            remoteId = "edge-a", node = "node", origin = TaskOrigin.Remote("pve", "edge-a"),
        )
        val (rows, total) = repository(api).drainTaskLogTail(task, maxLines = 2000)
        assertEquals(count.toLong(), total)
        assertTrue("Retained log must remain bounded", rows.size <= 2000)
        assertEquals("Tail must include the terminal line, not stop after the first 2000", count.toLong(), rows.last().n)
        assertEquals("TASK OK", rows.last().t)
    }

    @Test
    fun notesWriteWithoutADigestNeverReachesTheServer() = runBlocking {
        var writes = 0
        val api = object : PdmApi by DemoApi() {
            override suspend fun putNotes(notes: String, digest: String?): PdmResponse<JsonElement> {
                writes++
                return PdmResponse()
            }
            override suspend fun getNotes(): PdmResponse<String> =
                PdmResponse(data = "server notes", digest = "d".repeat(64))
        }
        val result = runCatching { repository(api).putSharedNotes("draft", null) }
        assertEquals("Missing digest must be rejected before PUT", 0, writes)
        assertTrue(result.isFailure)
    }

    @Test
    fun aRemoteNameIsNeverAReservedHostMarker() {
        for (id in listOf("host", "pdm")) {
            val task = RemoteTask(upid = "UPID:node:00000001:00000001:00000001:test::operator@pdm:", remoteId = id, node = "node")
            assertTrue("A supplied remote identity must stay remote: $id", task.origin is TaskOrigin.Remote)
            assertEquals(id, (task.origin as TaskOrigin.Remote).remoteId)
        }
    }
}

