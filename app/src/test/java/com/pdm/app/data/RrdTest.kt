package com.pdm.app.data

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.model.RrdField
import com.pdm.app.data.model.ratio
import com.pdm.app.data.model.series
import com.pdm.app.data.model.stats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RrdTest {
    // Two sample PDM 1.1.7 RRD rows, with one empty slot first.
    private val rows = AppJson.parseToJsonElement(
        """[
          {"time":1792756294},
          {"cpu-current":0.1647,"cpu-max":6.0,"disk-read":0.0,"disk-total":34359738368.0,"disk-write":0.0,
           "mem-total":4294967296.0,"mem-used":696770560.0,"net-in":2390.6,"net-out":31.0,"time":1792756354,"uptime":117876.0}
        ]""",
    ).let { (it as JsonArray).map { row -> row as JsonObject } }

    @Test
    fun kebabFieldsParseAndEmptySlotsStayNull() {
        val points = FleetMapper.rrdFromJson(rows)
        assertEquals(2, points.size)
        val cpu = points.series(RrdField.CPU)
        assertNull(cpu[0])
        assertEquals(0.1647f, cpu[1]!!, 1e-6f)
        assertEquals(2390.6f, points.series(RrdField.NET_IN)[1]!!, 1e-3f)
    }

    @Test
    fun memoryRatioAndStats() {
        val points = FleetMapper.rrdFromJson(rows)
        val mem = points.ratio(RrdField.MEM_USED, RrdField.MEM_TOTAL)
        assertNull(mem[0])
        assertEquals(696770560f / 4294967296f, mem[1]!!, 1e-6f)
        val s = points.series(RrdField.CPU).stats()
        assertEquals(0.1647f, s.last!!, 1e-6f)
        assertEquals(0.1647f, s.max!!, 1e-6f)
    }
}
