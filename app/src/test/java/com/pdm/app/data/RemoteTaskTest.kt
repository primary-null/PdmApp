package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.RemoteTaskDto
import com.pdm.app.data.model.TaskPhase
import com.pdm.app.data.model.series
import com.pdm.app.data.model.taskRollup
import com.pdm.app.data.model.taskPlateSubtitle
import com.pdm.app.data.model.taskPlateValue
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTaskTest {
    @Test
    fun listJsonUsesSnakeCaseAndParsesRemote() {
        val json = """
            {"data":[{
              "upid":"pve:NODE-B!UPID:NODE-B:0001:0002:0003:qmstart:101:root@pam:",
              "node":"NODE-B","pid":1,"pstart":2,"starttime":1700000000,
              "worker_type":"qmstart","worker_id":"101","user":"root@pam"
            },{
              "upid":"edge-a!UPID:pve-a:0001:0002:0003:vzdump:200:root@pam:",
              "node":"pve-a","starttime":1699990000,"endtime":1699991000,
              "worker-type":"vzdump","worker-id":"200","user":"root@pam","status":"OK"
            }]}
        """.trimIndent()
        val parsed = AppJson.decodeFromString(
            PdmResponse.serializer(ListSerializer(RemoteTaskDto.serializer())),
            json,
        )
        val tasks = FleetMapper.tasksFromApi(parsed.data, emptyList())!!
        assertEquals("NODE-B", tasks[0].remoteId)
        assertEquals(TaskPhase.Running, tasks[0].phase)
        assertEquals("qmstart 101", tasks[0].headline)
        assertEquals("edge-a", tasks[1].remoteId)
        assertEquals(TaskPhase.Ok, tasks[1].phase)
        assertTrue(tasks[0].startTime > tasks[1].startTime)
    }

    @Test
    fun runningPassIsMergedAheadOfFinished() {
        val running = RemoteTaskDto(
            upid = "pve:NODE-A!UPID:NODE-D:1:1:1:vzstart:104:root@pam:",
            node = "NODE-D",
            startTime = 100,
            workerType = "vzstart",
            workerId = "104",
        )
        val recent = listOf(
            running.copy(startTime = 100),
            RemoteTaskDto(
                upid = "pbs:backup!UPID:pbs:1:1:1:backup:tank:root@pam:",
                node = "pbs",
                startTime = 500,
                endTime = 600,
                workerType = "backup",
                status = "command error",
            ),
            RemoteTaskDto(
                upid = "pve:NODE-C!UPID:NODE-C:1:1:1:aptupdate::root@pam:",
                node = "NODE-C",
                startTime = 400,
                endTime = 410,
                workerType = "aptupdate",
                status = "WARNINGS: 1",
            ),
        )
        val tasks = FleetMapper.tasksFromApi(recent, listOf(running))!!
        assertEquals(3, tasks.size)
        assertEquals(TaskPhase.Running, tasks[0].phase)
        assertEquals("backup", tasks[1].remoteId)
        assertEquals(TaskPhase.Error, tasks[1].phase)
        assertEquals(TaskPhase.Warning, tasks[2].phase)
        assertEquals("3", taskPlateValue(tasks))
        assertEquals("1 running · 1 failed · 1 warn", taskPlateSubtitle(tasks))
        assertNull(FleetMapper.tasksFromApi(null, null))
        assertEquals("—", taskPlateValue(null))
        assertEquals("unavailable", taskPlateSubtitle(null))
    }

    @Test
    fun taskKindFollowsUpidPrefix() {
        fun kindOf(upid: String) = FleetMapper.taskFromDto(RemoteTaskDto(upid = upid)).kind
        assertEquals("pbs", kindOf("pbs:backup!UPID:pbs:1:1:1:backup:tank:root@pam:"))
        assertEquals("pve", kindOf("pve:NODE-B!UPID:NODE-B:1:1:1:qmstart:101:root@pam:"))
        assertEquals("pve", kindOf("edge-a!UPID:pve-a:1:1:1:vzdump:200:root@pam:"))
    }

    @Test
    fun rrdRowsBecomeSortedNumericPoints() {
        val rows = AppJson.parseToJsonElement(
            """[
              {"time":120,"cpu_current":0.5,"mem_used":2048,"note":"x"},
              {"time":60,"cpu_current":0.25},
              {"cpu_current":0.9}
            ]""",
        ).let { it as kotlinx.serialization.json.JsonArray }.map { it as kotlinx.serialization.json.JsonObject }
        val points = FleetMapper.rrdFromJson(rows)
        assertEquals(listOf(60L, 120L), points.map { it.time })
        assertEquals(listOf(0.25f, 0.5f), points.series("cpu_current"))
        assertEquals(listOf(null, 2048f), points.series("mem_used"))
        assertTrue("note" !in points[1].values)
    }

    @Test
    fun demoTaskDetailReadsStatusAndLog() = kotlinx.coroutines.test.runTest {
        val store = com.pdm.app.data.session.SessionStore(injectedPrefs = FakeSharedPreferences())
        store.setSession(
            com.pdm.app.data.model.SessionState(
                config = com.pdm.app.data.model.ServerConfig(host = "demo", username = "root@pam"),
            ),
        )
        val repo = com.pdm.app.data.repo.FleetRepository(store, { com.pdm.app.data.api.DemoApi() })
        val task = FleetMapper.taskFromDto(RemoteTaskDto(upid = "pve:edge-a!UPID:a:1:1:1:qmstart:100:root@pam:"))
        val detail = repo.taskDetail(task)
        assertEquals("OK", detail.status?.exitstatus)
        assertEquals("TASK OK", detail.log.last().t)
    }

    @Test
    fun cacheStatusRunningIsRunningNotFailed() {
        val task = FleetMapper.taskFromDto(
            RemoteTaskDto(upid = "pve:NODE-C!UPID:NODE-C:1:1:1:vncproxy:101:root@pam:", status = "RUNNING"),
        )
        assertEquals(TaskPhase.Running, task.phase)
        assertEquals(0, listOf(task).taskRollup().failed)
    }
}
