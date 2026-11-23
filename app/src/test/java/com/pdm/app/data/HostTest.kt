package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.HostStatusDto
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.TaskStatisticsDto
import com.pdm.app.ui.home.subscriptionLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class HostTest {
    @Test
    fun hostStatusFromPdm() {
        // Trimmed from PDM 1.1.7 `nodes/pdm/status`.
        val json = """{"data":{"cpu":0.0036,"cpuinfo":{"cpus":8,"model":"QEMU Virtual CPU version 2.5+","sockets":1},
            "current-kernel":{"machine":"x86_64","release":"7.0.14-20-pve"},"loadavg":[0.23,0.15,0.1],
            "memory":{"free":7319359488,"total":8323928064,"used":612331520},
            "root":{"avail":49466187776,"total":56348631040,"used":3986907136},"uptime":135249}}"""
        val st = AppJson.decodeFromString(PdmResponse.serializer(HostStatusDto.serializer()), json).data!!
        assertEquals(8, st.cpuinfo?.cpus)
        assertEquals("7.0.14-20-pve", st.kernel?.release)
        assertEquals(612331520L, st.memory?.used)
        assertEquals(3, st.loadavg.size)
    }

    @Test
    fun taskStatisticsByRemoteAndType() {
        val json = """{"data":{"by-remote":{"NODE-B":{"error":15,"ok":29,"unknown":0,"warning":0}},
            "by-type":{"vncproxy":{"error":11,"ok":16,"unknown":0,"warning":0}}}}"""
        val s = AppJson.decodeFromString(PdmResponse.serializer(TaskStatisticsDto.serializer()), json).data!!
        assertEquals(44, s.byRemote.getValue("NODE-B").total)
        assertEquals(11, s.byType.getValue("vncproxy").error)
    }

    @Test
    fun subscriptionIsWordedQuietly() {
        // No alarm words for the normal homelab case.
        assertEquals("community (none)", subscriptionLabel("none"))
        assertEquals("community (none)", subscriptionLabel("invalid"))
        assertEquals("active", subscriptionLabel("active"))
    }
}
