package com.pdm.app.ui.home

import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.RemoteEndpoint
import com.pdm.app.data.model.RemoteNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetSearchTest {
    private val remotes = listOf(
        RemoteNode("node-b", "NODE-B", "pve", endpoints = listOf(RemoteEndpoint("198.51.100.33", 8006))),
        RemoteNode("node-c", "NODE-C", "pve"),
    )
    private val nodes = listOf(
        FleetNode("node-b/node/pve", "node-b", "pve", "online"),
        FleetNode("node-c/node/edge", "node-c", "edge", "online"),
    )
    private val storages = listOf(
        FleetStorage("node-b/storage/local", "node-b", "local", "pve", "available"),
        FleetStorage("node-c/storage/fast-pool", "node-c", "fast-pool", "edge", "available"),
    )
    private val guests = listOf(
        Guest("g1", "node-b", "dns-service", "lxc", 101, "stopped", node = "pve", tags = listOf("dns")),
        Guest("g2", "node-c", "application", "qemu", 100, "running", node = "edge"),
    )

    @Test
    fun blankQueryKeepsEveryResourceInScope() {
        val hits = fleetSearch("", remotes, nodes, storages, guests)
        assertEquals(2, hits.remotes.size)
        assertEquals(2, hits.nodes.size)
        assertEquals(2, hits.storages.size)
        assertEquals(2, hits.guests.size)
    }

    @Test
    fun nameMatchReturnsThatKindOnly() {
        val hits = fleetSearch("fast-pool", remotes, nodes, storages, guests)
        assertTrue(hits.remotes.isEmpty())
        assertTrue(hits.nodes.isEmpty())
        assertTrue(hits.guests.isEmpty())
        assertEquals(listOf("fast-pool"), hits.storages.map { it.name })
    }

    @Test
    fun guestTagAndRemoteStillMatchGuests() {
        assertEquals(listOf("dns-service"), fleetSearch("dns", remotes, nodes, storages, guests).guests.map { it.name })
        assertEquals(listOf("application"), fleetSearch("qemu", remotes, nodes, storages, guests).guests.map { it.name })
    }

    @Test
    fun remoteWordFindsTheRemoteItsNodeAndItsStorage() {
        val hits = fleetSearch("node-c", remotes, nodes, storages, guests)
        assertEquals(listOf("NODE-C"), hits.remotes.map { it.name })
        assertEquals(listOf("edge"), hits.nodes.map { it.name })
        assertEquals(listOf("fast-pool"), hits.storages.map { it.name })
        assertEquals(listOf("application"), hits.guests.map { it.name })
    }

    @Test
    fun endpointAndScopedRemoteNarrowTheHits() {
        assertEquals(listOf("NODE-B"), fleetSearch("198.51.100.33", remotes, nodes, storages, guests).remotes.map { it.name })
        val scoped = fleetSearch("local", remotes, nodes, storages, guests, remoteId = "node-c")
        assertTrue(scoped.isEmpty)
        assertEquals(listOf("local"), fleetSearch("local", remotes, nodes, storages, guests, remoteId = "node-b").storages.map { it.name })
    }
}
