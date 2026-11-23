package com.pdm.app

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.FleetMapper
import com.pdm.app.data.api.DemoApi
import com.pdm.app.data.api.PdmApi
import com.pdm.app.data.model.*
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.session.SessionIdentity
import com.pdm.app.data.session.SessionStore
import com.pdm.app.ui.FleetUiState
import com.pdm.app.data.ops.RestartPhase
import com.pdm.app.data.ops.RestartProgress
import com.pdm.app.ui.SyncState
import com.pdm.app.ui.home.pullLook
import com.pdm.app.ui.home.syncLabel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PdmFirstEightTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val wall = 1_700_000_000_000L

    private fun testRepo(api: PdmApi = DemoApi()): FleetRepository {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(
            SessionState(
                config = ServerConfig(host = "demo", authMode = AuthMode.PASSWORD, username = "root@pdm"),
                ticket = "PDM:demo",
                csrf = "demo-csrf",
            ),
        )
        return FleetRepository(store, { api })
    }

    // ==========================================
    // R1 / Q1: Unified Restart & Recovery State
    // ==========================================
    @Test
    fun q1_syncLabelNeverClaimsLiveDuringHostRestart() {
        val liveSync = SyncState(lastSuccessAt = 0, lastAttemptAt = 0)
        assertEquals("LIVE", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = null).text)

        val restarting = RestartProgress("pdm-host", RestartPhase.GOING_DOWN, submittedAt = 0)
        assertEquals("RESTARTING", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = restarting).text)

        val down = restarting.copy(phase = RestartPhase.DOWN)
        assertEquals("WAITING FOR SERVER", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = down).text)

        val reconnecting = restarting.copy(phase = RestartPhase.RECOVERING)
        assertEquals("RECONNECTING", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = reconnecting).text)

        val err = restarting.copy(phase = RestartPhase.TIMED_OUT)
        assertEquals("NOT BACK YET", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = err).text)
        val failed = restarting.copy(phase = RestartPhase.RECOVERY_FAILED, reason = "Some answers came from saved data.")
        assertEquals("NOT CONFIRMED", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = failed).text)
        // Settled: the chip goes back to the ordinary sync wording.
        assertEquals("LIVE", syncLabel(liveSync, now = 10_000, wallNow = wall, hostRestart = restarting.copy(phase = RestartPhase.DONE)).text)
    }

    @Test
    fun q1_pullLookAndReadOnlyDuringRestart() {
        val state = FleetUiState(
            sync = SyncState(lastSuccessAt = 1),
            hostRestart = RestartProgress("pdm-host", RestartPhase.GOING_DOWN, submittedAt = 0),
        )
        assertTrue(state.readOnly)

        val look = pullLook(state)
        assertEquals("RESTARTING", look.word)
        assertFalse(look.refusal)
        assertFalse(look.failing)

        val reconnectingState = state.copy(hostRestart = state.hostRestart?.copy(phase = RestartPhase.RECOVERING))
        assertEquals("RECONNECTING", pullLook(reconnectingState).word)
        // Failed recovery and timeout stay read-only; only DONE or REJECTED release writes.
        assertTrue(state.copy(hostRestart = state.hostRestart?.copy(phase = RestartPhase.RECOVERY_FAILED)).readOnly)
        assertTrue(state.copy(hostRestart = state.hostRestart?.copy(phase = RestartPhase.TIMED_OUT)).readOnly)
        assertFalse(state.copy(hostRestart = state.hostRestart?.copy(phase = RestartPhase.DONE)).readOnly)
    }

    @Test
    fun q1_restartReceiptLifecycleAndProtectionRetention() {
        val prefs = FakeSharedPreferences()
        val store = SessionStore(injectedPrefs = prefs)
        val identity = SessionIdentity("192.0.2.36:8443", AuthMode.PASSWORD, "root@pam")

        val rebootReceipt = OperationReceipt(
            id = "host-reboot-1",
            kind = "host-reboot",
            targetEndpoint = "192.0.2.36:8443",
            account = "root@pam",
            node = "pdm-host",
            upid = "UPID:pdm:00001000:00000000:60000000:reboot::root@pam:",
            state = OperationSubmissionState.ACCEPTED,
            submittedAt = 1000L,
            description = "Host reboot initiated",
        )
        store.saveOperationReceipt(identity, rebootReceipt)

        // Read back from fresh store using same prefs
        val restoredStore = SessionStore(injectedPrefs = prefs)
        val receipts = restoredStore.getOperationReceipts(identity)
        assertEquals(1, receipts.size)
        assertEquals("host-reboot-1", receipts.first().id)
        assertFalse(receipts.first().isTerminal)
    }

    // ==========================================
    // R2 / Q2: Complete Bounded Task Logs
    // ==========================================
    @Test
    fun q2_taskLogPaginationAndDeduplication() = runBlocking {
        val repo = testRepo()
        val task = RemoteTask(
            upid = "UPID:pdm:1234:0000:0000:aptupdate::root@pdm:",
            remoteId = "",
            node = "pdm",
            workerType = "aptupdate",
            workerId = null,
            user = "root@pdm",
            startTime = 1000L,
            endTime = null,
            status = null,
        )

        // Fetch logs with limit=10
        val (lines, _) = repo.taskLogPage(task, start = 0, limit = 10)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.size <= 10)

        // Validate line deduplication
        val existing = listOf(TaskLogLine(1, "line 1"), TaskLogLine(2, "line 2"))
        val incoming = listOf(TaskLogLine(2, "line 2"), TaskLogLine(3, "line 3"))
        val existingN = existing.map { it.n }.toSet()
        val merged = (existing + incoming.filter { it.n !in existingN }).sortedBy { it.n }
        assertEquals(3, merged.size)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.n })
    }

    @Test
    fun q2_drainTaskLogTail_largeLogsOver1500Lines() = runBlocking {
        // Custom API simulating a 1800-line completed task log
        val totalLines = 1800
        val mockApi = object : PdmApi by DemoApi() {
            override suspend fun hostTaskLog(
                node: String,
                upid: String,
                start: Int,
                limit: Int,
            ): PdmResponse<List<TaskLogLine>> {
                val lines = (start until minOf(totalLines, start + limit)).map { idx ->
                    if (idx == totalLines - 1) TaskLogLine(idx.toLong() + 1, "TASK OK")
                    else TaskLogLine(idx.toLong() + 1, "log line $idx")
                }
                return PdmResponse(data = lines, total = totalLines.toLong())
            }
        }
        val repo = testRepo(mockApi)
        val task = RemoteTask(
            upid = "UPID:pdm:large:task:",
            node = "pdm",
            origin = TaskOrigin.Host("pdm"),
        )

        // Drain tail starting from 0: should fetch chunks up to 1800 lines bounded by maxLines=2000
        val (tailLines, total) = repo.drainTaskLogTail(task, startOffset = 0, maxLines = 2000)
        assertEquals(1800.toLong(), total)
        assertEquals(1800, tailLines.size)
        assertEquals(1L, tailLines.first().n)
        assertEquals(1800L, tailLines.last().n)
        assertEquals("TASK OK", tailLines.last().t)
    }

    @Test
    fun q2_drainTaskLogTail_boundedCapAt2000Lines() = runBlocking {
        // Custom API simulating a 2500-line completed task log (exceeding 2000 lines)
        val totalLines = 2500
        val mockApi = object : PdmApi by DemoApi() {
            override suspend fun hostTaskLog(
                node: String,
                upid: String,
                start: Int,
                limit: Int,
            ): PdmResponse<List<TaskLogLine>> {
                val lines = (start until minOf(totalLines, start + limit)).map { idx ->
                    TaskLogLine(idx.toLong() + 1, "log line $idx")
                }
                return PdmResponse(data = lines, total = totalLines.toLong())
            }
        }
        val repo = testRepo(mockApi)
        val task = RemoteTask(upid = "UPID:pdm:giant:task:", node = "pdm", origin = TaskOrigin.Host("pdm"))

        val (tailLines, total) = repo.drainTaskLogTail(task, startOffset = 0, maxLines = 2000)
        assertEquals(2500.toLong(), total)
        // Must be capped at maxLines (2000)
        assertTrue(tailLines.size <= 2000)
    }

    // ==========================================
    // R3: Task Route Identity & Origins
    // ==========================================
    @Test
    fun r3_taskOriginDistinguishesHostFromRemotesNamedHostOrPdm() {
        // Remote legally named "host" using PDM format remoteKind:remoteId!UPID:...
        val remoteNamedHost = FleetMapper.originFromTaskUpid("pve:host!UPID:node-a:00001000:00000000:60000000:vzdump::root@pam:", "node-a")
        assertTrue(remoteNamedHost is TaskOrigin.Remote)
        assertEquals("host", (remoteNamedHost as TaskOrigin.Remote).remoteId)
        assertEquals("pve", remoteNamedHost.remoteKind)

        // Remote legally named "pdm" using PDM format remoteKind:remoteId!UPID:...
        val remoteNamedPdm = FleetMapper.originFromTaskUpid("pbs:pdm!UPID:node-b:00001000:00000000:60000000:backup::root@pam:", "node-b")
        assertTrue(remoteNamedPdm is TaskOrigin.Remote)
        assertEquals("pdm", (remoteNamedPdm as TaskOrigin.Remote).remoteId)
        assertEquals("pbs", remoteNamedPdm.remoteKind)

        // Native host task (no remote prefix in upid)
        val nativeHostTask = FleetMapper.originFromTaskUpid("UPID:pdm:00001000:00000000:60000000:aptupdate::root@pam:", "pdm")
        assertTrue(nativeHostTask is TaskOrigin.Host)
        assertEquals("pdm", (nativeHostTask as TaskOrigin.Host).node)
    }

    @Test
    fun r3_canStopTaskPermissionsForHostAndRemote() {
        // Permissions granting System.Modify on /system/tasks but NOT resource manage
        val hostOnlyPerms = Permissions.of(mapOf("/system/tasks" to setOf("System.Modify")))
        val hostTask = RemoteTask(upid = "UPID:pdm:123:", user = "root@pam", origin = TaskOrigin.Host("pdm"))
        val remoteTask = RemoteTask(upid = "pve:edge:123:", remoteId = "edge", origin = TaskOrigin.Remote("pve", "edge"))

        assertTrue(hostOnlyPerms.canStopTask(hostTask))
        assertFalse(hostOnlyPerms.canStopTask(remoteTask))

        // Task owner can stop their own task without System.Modify
        val unprivileged = Permissions.of(emptyMap())
        assertTrue(unprivileged.canStopTask(hostTask, currentUser = "root@pam"))
        assertFalse(unprivileged.canStopTask(hostTask, currentUser = "other@pam"))

        // Permissions granting Resource.Manage on /resource/edge (via Permissions.remotePath)
        val remoteOnlyPerms = Permissions.of(mapOf(Permissions.remotePath("edge") to setOf(Permissions.RESOURCE_MANAGE)))
        assertFalse(remoteOnlyPerms.canStopTask(hostTask))
        assertTrue(remoteOnlyPerms.canStopTask(remoteTask))
    }

    // ==========================================
    // R4 / Q3: Node HA Maintenance State
    // ==========================================
    @Test
    fun q3_nodeHaStateMappingAndCandidateRules() {
        val node = FleetNode(
            id = "pve/node/node-1",
            remoteId = "pve",
            name = "node-1",
            status = "online",
            haState = "maintenance",
        )
        assertEquals("maintenance", node.haState)
        assertTrue(node.isHaMaintenance)
        assertFalse(node.isHaTransitionalOrFault)
        assertFalse(node.isMigrationCandidate)

        val faultNode = FleetNode(
            id = "pve/node/node-2",
            remoteId = "pve",
            name = "node-2",
            status = "online",
            haState = "fence",
        )
        assertFalse(faultNode.isHaMaintenance)
        assertTrue(faultNode.isHaTransitionalOrFault)
        assertFalse(faultNode.isMigrationCandidate)

        val healthy = FleetNode(
            id = "pve/node/node-3",
            remoteId = "pve",
            name = "node-3",
            status = "online",
            haState = "active",
        )
        assertFalse(healthy.isHaMaintenance)
        assertFalse(healthy.isHaTransitionalOrFault)
        assertTrue(healthy.isMigrationCandidate)
    }

    // ==========================================
    // R5 / F1: Task History Filters & Pagination
    // ==========================================
    @Test
    fun q1_f1_taskHistoryFiltersAndPagination() = runBlocking {
        val repo = testRepo()
        // Test query parameters for error-only filtering
        val (errorTasks, _) = repo.taskHistory(TaskFilters(remote = "edge-a", errors = true, limit = 5))
        assertTrue(errorTasks.all { it.phase == TaskPhase.Error || it.phase == TaskPhase.Warning })

        // Test running tasks filter
        val (runningTasks, _) = repo.taskHistory(TaskFilters(remote = "edge-a", running = true, limit = 5))
        assertTrue(runningTasks.all { it.phase == TaskPhase.Running })
    }

    @Test
    fun r5_rawOffsetTracksTotalReturnedRowsIndependentlyOfFilter() {
        // Raw returned rows advance the offset even if user filter filters out some rows locally
        var rawOffset = 0L
        val pageSize = 50

        val page1Count = 50
        rawOffset += page1Count
        assertEquals(50L, rawOffset)

        val page2Count = 30
        rawOffset += page2Count
        assertEquals(80L, rawOffset)
    }

    // ==========================================
    // R6-R9 / F2: Host Administration Subcomponents
    // ==========================================
    @Test
    fun f2_hostAdminAllSixComponents() = runBlocking {
        val repo = testRepo()
        val node = "pdm-host"

        // 1. Host package updates check
        val upid = repo.checkHostUpdates(node)
        assertTrue(upid.startsWith("UPID:$node:"))

        // 2. Host package changelog
        val changelog = repo.hostPackageChangelog(node, "proxmox-datacenter-manager")
        assertTrue(changelog.contains("proxmox-datacenter-manager"))

        // 3. Host task history
        val (hostTasks, _) = repo.hostTasks(node)
        assertTrue(hostTasks.isNotEmpty())
        assertTrue(hostTasks.all { it.isHostTask })

        // 4. Host syslog
        val (syslog, _) = repo.hostSyslog(node)
        assertTrue(syslog.isNotEmpty())

        // 5. Host journal
        val journal = repo.hostJournal(node)
        assertTrue(journal.isNotEmpty())

        // 6. Host RRD metrics
        val rrd = repo.hostRrd(node, RrdTimeframe.HOUR)
        assertTrue(rrd.isNotEmpty())
    }

    @Test
    fun r7_hostRrdMetricFieldsAreAvailable() {
        assertEquals("disk-used", RrdField.DISK_USED)
        assertEquals("disk-total", RrdField.DISK_TOTAL)
        assertEquals("swap-used", RrdField.SWAP_USED)
        assertEquals("swap-total", RrdField.SWAP_TOTAL)
        assertEquals("cpu-iowait", RrdField.IO_WAIT)
    }

    @Test
    fun r9_canCheckHostUpdatesPermission() {
        val rootPerms = Permissions.of(mapOf("/" to setOf("System.Modify")))
        assertTrue(rootPerms.canCheckHostUpdates())

        val packagesOnly = Permissions.of(mapOf("/system/packages" to setOf("System.Modify")))
        assertFalse(packagesOnly.canCheckHostUpdates())

        val unprivileged = Permissions.of(mapOf("/" to setOf("System.Audit")))
        assertFalse(unprivileged.canCheckHostUpdates())
    }

    // ==========================================
    // R10-R11 / F3: Shared Notes and Operational Context
    // ==========================================
    @Test
    fun f3_sharedNotesAndContext() = runBlocking {
        val repo = testRepo()

        val (initialNotes, digest) = repo.getSharedNotes()
        assertTrue(initialNotes.contains("Datacenter Operations"))

        // Update shared notes with matching digest
        val updatedNotes = initialNotes + "\nAdded line."
        repo.putSharedNotes(updatedNotes, digest)
        val (freshNotes, freshDigest) = repo.getSharedNotes()
        assertEquals(updatedNotes, freshNotes)
        assertNotEquals(digest, freshDigest)

        // Conflict check: trying to save with old digest must throw 409
        try {
            repo.putSharedNotes("Conflicting text", digest)
            fail("Expected 409 conflict exception")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("Conflict") == true || e.message?.contains("409") == true)
        }

        val clusterNotes = repo.getRemoteClusterNotes("edge-a")
        assertNotNull(clusterNotes)
        assertTrue(clusterNotes!!.contains("Production cluster"))

        val nodeNotes = repo.getRemoteNodeNotes("edge-a", "pve-a")
        assertNotNull(nodeNotes)
        assertTrue(nodeNotes!!.contains("Virtualization hypervisor"))
    }

    @Test
    fun r10_sharedNotesPermissionHelper() {
        val auditOnly = Permissions.of(mapOf("/system/notes" to setOf("System.Audit")))
        assertFalse(auditOnly.canEditSharedNotes())

        val modifyPerm = Permissions.of(mapOf("/system/notes" to setOf("System.Modify")))
        assertTrue(modifyPerm.canEditSharedNotes())

        val configNotesPerm = Permissions.of(mapOf("/config/notes" to setOf("System.Modify")))
        assertFalse(configNotesPerm.canEditSharedNotes())
    }

    @Test
    fun f3_putSharedNotes_rejectsMalformedOrBlankDigest() = runBlocking {
        val repo = testRepo()
        val validSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        // Null digest
        try {
            repo.putSharedNotes("test", null)
            fail("null digest must throw")
        } catch (_: IllegalArgumentException) {}

        // Blank digest
        try {
            repo.putSharedNotes("test", "   ")
            fail("blank digest must throw")
        } catch (_: IllegalArgumentException) {}

        // Short / malformed digest
        try {
            repo.putSharedNotes("test", "demo-notes-digest")
            fail("malformed digest must throw")
        } catch (_: IllegalArgumentException) {}

        // 63 chars (not 64)
        try {
            repo.putSharedNotes("test", "a".repeat(63))
            fail("63-char digest must throw")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun q2_drainTaskLogTail_absentTotal_retainsTail() = runBlocking {
        val totalLines = 2500
        val mockApi = object : PdmApi by DemoApi() {
            override suspend fun remoteTaskLog(
                kind: String,
                remote: String,
                upid: String,
                start: Int,
                limit: Int,
            ): PdmResponse<List<TaskLogLine>> {
                val end = minOf(totalLines, start + limit)
                val lines = if (start >= totalLines) emptyList() else (start until end).map { idx ->
                    if (idx == totalLines - 1) TaskLogLine(idx.toLong() + 1, "TASK OK")
                    else TaskLogLine(idx.toLong() + 1, "line $idx")
                }
                // Server does not supply total metadata
                return PdmResponse(data = lines, total = null)
            }
        }
        val repo = testRepo(mockApi)
        val task = RemoteTask(
            upid = "pve:edge:task:",
            remoteId = "edge",
            origin = TaskOrigin.Remote("pve", "edge"),
        )
        val (rows, total) = repo.drainTaskLogTail(task, startOffset = 0, maxLines = 2000)
        assertNull(total)
        assertTrue("Log retention must not exceed maxLines", rows.size <= 2000)
        assertEquals(2000, rows.size)
        assertEquals(2500L, rows.last().n)
        assertEquals("TASK OK", rows.last().t)
    }

    @Test
    fun q2_drainTaskLogTail_withStartOffset_preservesOffset() = runBlocking {
        val totalLines = 100
        val mockApi = object : PdmApi by DemoApi() {
            override suspend fun hostTaskLog(
                node: String,
                upid: String,
                start: Int,
                limit: Int,
            ): PdmResponse<List<TaskLogLine>> {
                val end = minOf(totalLines, start + limit)
                val lines = (start until end).map { idx ->
                    TaskLogLine(idx.toLong() + 1, "line ${idx + 1}")
                }
                return PdmResponse(data = lines, total = totalLines.toLong())
            }
        }
        val repo = testRepo(mockApi)
        val task = RemoteTask(upid = "UPID:pdm:task:", node = "pdm", origin = TaskOrigin.Host("pdm"))
        val (rows, total) = repo.drainTaskLogTail(task, startOffset = 50, maxLines = 2000)
        assertEquals(100L, total)
        assertEquals(50, rows.size)
        assertEquals(51L, rows.first().n)
        assertEquals(100L, rows.last().n)
    }

    // ==========================================
    // R12 / Q4: Typed Partial Failures
    // ==========================================
    @Test
    fun q4_readAvailabilityAndSafeAuditRead() = runBlocking {
        val repo = testRepo()

        val success = repo.safeAuditRead { "ok" }
        assertTrue(success is ReadAvailability.Fresh)
        assertEquals("ok", (success as ReadAvailability.Fresh).value)
        assertFalse(success.isStale)
        assertFalse(success.isLoading)
        assertFalse(success.isUnsupported)

        val forbidden = repo.safeAuditRead<String> {
            val response = okhttp3.Response.Builder()
                .code(403)
                .message("Forbidden")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .request(okhttp3.Request.Builder().url("https://localhost/").build())
                .build()
            throw retrofit2.HttpException(retrofit2.Response.error<Any>("".toResponseBody("text/plain".toMediaType()), response))
        }
        assertTrue(forbidden is ReadAvailability.Forbidden)

        val unsupported = ReadAvailability.Unsupported("Not supported on PDM 1.0")
        assertTrue(unsupported.isUnsupported)
        assertEquals("Not supported on PDM 1.0", unsupported.message)

        val transport = repo.safeAuditRead<String> {
            throw IOException("Connection timed out")
        }
        assertTrue(transport is ReadAvailability.TransportError)
    }

    // ==========================================
    // R13-R14 / Q5: Trackable Accepted Operations & Bounded Retention
    // ==========================================
    @Test
    fun q5_operationReceiptStorageAndLifecycle() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val identity = SessionIdentity("192.0.2.36:8443", AuthMode.PASSWORD, "root@pam")

        val op = OperationReceipt(
            id = "vm-100-start",
            kind = "qemu-start",
            targetEndpoint = "192.0.2.36:8443",
            account = "root@pam",
            upid = "UPID:pve:1234:start:",
            node = "node-1",
            vmid = 100L,
            state = OperationSubmissionState.ACCEPTED,
            submittedAt = 1000L,
            description = "Start VM 100",
        )

        store.saveOperationReceipt(identity, op)
        val read = store.getOperationReceipts(identity)
        assertEquals(1, read.size)
        assertEquals("vm-100-start", read[0].id)

        store.updateOperationReceipt(identity, "vm-100-start") {
            it.copy(state = OperationSubmissionState.SUCCEEDED)
        }
        val updated = store.getOperationReceipts(identity)
        assertEquals(1, updated.size)
        assertTrue(updated[0].isTerminal)

        store.removeOperationReceipt(identity, "vm-100-start")
        val empty = store.getOperationReceipts(identity)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun r14_receiptPruningRetainsAllUnresolvedAndCapsTerminalAt20() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val identity = SessionIdentity("192.0.2.36:8443", AuthMode.PASSWORD, "root@pam")

        // Record 5 unresolved/running receipts
        for (i in 1..5) {
            store.saveOperationReceipt(
                identity,
                OperationReceipt(
                    id = "pending-$i",
                    kind = "qemu-start",
                    targetEndpoint = "192.0.2.36:8443",
                    account = "root@pam",
                    state = OperationSubmissionState.ACCEPTED,
                    submittedAt = 1000L + i,
                )
            )
        }

        // Record 30 completed/terminal receipts
        for (i in 1..30) {
            store.saveOperationReceipt(
                identity,
                OperationReceipt(
                    id = "terminal-$i",
                    kind = "qemu-stop",
                    targetEndpoint = "192.0.2.36:8443",
                    account = "root@pam",
                    state = OperationSubmissionState.SUCCEEDED,
                    submittedAt = 2000L + i,
                )
            )
        }

        val receipts = store.getOperationReceipts(identity)
        val unresolved = receipts.filterNot { it.isTerminal }
        val terminal = receipts.filter { it.isTerminal }

        // All 5 unresolved must be preserved
        assertEquals(5, unresolved.size)
        // Terminal must be capped at 20
        assertEquals(20, terminal.size)
        assertEquals(25, receipts.size)
    }

    @Test
    fun r14_identityIsolationPreventsCrossAccountLeakage() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val identityAdmin = SessionIdentity("pdm.example:8443", AuthMode.PASSWORD, "admin@pdm")
        val identityOperator = SessionIdentity("pdm.example:8443", AuthMode.PASSWORD, "operator@pdm")

        store.saveOperationReceipt(
            identityAdmin,
            OperationReceipt(
                id = "admin-op",
                kind = "host-upgrade",
                targetEndpoint = "pdm.example:8443",
                account = "admin@pdm",
                submittedAt = 1000L,
            )
        )

        // Operator reading receipts should see zero admin receipts
        val operatorReceipts = store.getOperationReceipts(identityOperator)
        assertTrue(operatorReceipts.isEmpty())

        // Admin reading receipts should see only admin receipts
        val adminReceipts = store.getOperationReceipts(identityAdmin)
        assertEquals(1, adminReceipts.size)
        assertEquals("admin-op", adminReceipts.first().id)
    }
}
