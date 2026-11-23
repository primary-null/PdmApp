package com.pdm.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class TagsTest {
    @Test
    fun pdmListAndPveStringBothParse() {
        // PDM 1.1.7 sends a list; PVE's own API sends "a;b".
        assertEquals(
            listOf("adblock", "community-script"),
            FleetMapper.parseTags(JsonArray(listOf(JsonPrimitive("adblock"), JsonPrimitive("community-script")))),
        )
        assertEquals(listOf("infra", "test"), FleetMapper.parseTags(JsonPrimitive("infra;test")))
        assertEquals(emptyList<String>(), FleetMapper.parseTags(null))
    }

    @Test
    fun caseDuplicatesCollapseToFirstSpelling() {
        assertEquals(listOf("INFRA"), FleetMapper.parseTags(JsonArray(listOf(JsonPrimitive("INFRA"), JsonPrimitive("infra")))))
    }
}
