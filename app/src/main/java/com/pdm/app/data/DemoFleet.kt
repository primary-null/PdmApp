package com.pdm.app.data

import com.pdm.app.data.model.FleetDatastore
import com.pdm.app.data.model.FleetNetwork
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.RemoteEndpoint
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.RemoteTaskDto
import kotlin.math.sin

object DemoFleet {
    val remotes = listOf(
        RemoteNode(
            "edge-a",
            "edge-a",
            "pve",
            endpoints = listOf(RemoteEndpoint("192.0.2.10", 8006)),
            health = "Good",
        ),
        RemoteNode(
            "edge-b",
            "edge-b",
            "pve",
            endpoints = listOf(RemoteEndpoint("192.0.2.11", 8006)),
            health = "Good",
        ),
        RemoteNode(
            "backup",
            "backup",
            "pbs",
            endpoints = listOf(RemoteEndpoint("192.0.2.12", 8007)),
            health = "Good",
        ),
    )

    val subscriptions = remotes.map { remote ->
        RemoteSubscription(
            remote = remote.id,
            state = if (remote.id == "backup") "active" else "none",
        )
    }

    fun guests(): List<Guest> = listOf(
        Guest("g1", "edge-a", "dns", "qemu", 100, "running", "pve-a", 0.12, 2.0, gib(1), gib(2), gib(4), gib(32)),
        Guest("g2", "edge-a", "web", "lxc", 101, "stopped", "pve-a", 0.0, 1.0, 0, gib(1), 0, gib(8)),
        Guest("g3", "edge-b", "db", "qemu", 200, "running", "pve-b", 0.41, 4.0, gib(6), gib(8), gib(20), gib(64)),
        Guest("g4", "edge-b", "lab", "qemu", 201, "stopped", "pve-b", 0.0, 2.0, 0, gib(4), 0, gib(16)),
    )

    fun nodes(): List<FleetNode> = listOf(
        FleetNode("edge-a/node/pve-a", "edge-a", "pve-a", "online", 0.18, 8.0, gib(12), gib(32), 86_400),
        FleetNode("edge-b/node/pve-b", "edge-b", "pve-b", "online", 0.44, 16.0, gib(28), gib(64), 172_800),
        FleetNode("backup/node/pbs-1", "backup", "pbs-1", "online", 0.07, 4.0, gib(3), gib(16), 43_200),
    )

    fun storages(): List<FleetStorage> = listOf(
        FleetStorage("edge-a/storage/pve-a/local", "edge-a", "local", "pve-a", "available", gib(80), gib(200), false),
        FleetStorage("edge-b/storage/pve-b/local", "edge-b", "local", "pve-b", "available", gib(40), gib(400), false),
        FleetStorage("edge-a/storage/pve-a/ceph", "edge-a", "ceph", "pve-a", "available", gib(900), gib(2000), true),
    )

    fun datastores(): List<FleetDatastore> = listOf(
        FleetDatastore("backup/datastore/tank", "backup", "tank", gib(1400), gib(4000), 0.35),
    )

    fun networks(): List<FleetNetwork> = listOf(
        FleetNetwork("edge-a/network/core", "edge-a", "core", "pve-a", "available", "simple"),
        FleetNetwork("edge-b/network/core", "edge-b", "core", "pve-b", "available", "simple"),
    )

    fun taskDtos(now: Long = System.currentTimeMillis() / 1000): List<RemoteTaskDto> = listOf(
        RemoteTaskDto(
            upid = "pve:edge-a!UPID:pve-a:00000064:00000010:00000001:qmstart:100:root@pam:",
            node = "pve-a",
            workerType = "qmstart",
            workerId = "100",
            user = "root@pam",
            startTime = now - 120,
            status = null,
        ),
        RemoteTaskDto(
            upid = "pve:edge-b!UPID:pve-b:00000065:00000011:00000002:vzdump:200:root@pam:",
            node = "pve-b",
            workerType = "vzdump",
            workerId = "200",
            user = "root@pam",
            startTime = now - 3_600,
            endTime = now - 3_000,
            status = "OK",
        ),
        RemoteTaskDto(
            upid = "pbs:backup!UPID:pbs-1:00000066:00000012:00000003:backup:tank:root@pam:",
            node = "pbs-1",
            workerType = "backup",
            workerId = "tank",
            user = "root@pam",
            startTime = now - 7_200,
            endTime = now - 7_000,
            status = "command error",
        ),
    )

    fun series(seed: Int, n: Int = 48, base: Double = 0.2): List<Double?> =
        List(n) { i -> (base + 0.12 * sin((i + seed) / 4.0)).coerceIn(0.0, 1.0) }

    fun toggled(guest: Guest): Guest =
        guest.copy(status = if (guest.status == "running") "stopped" else "running")

    private fun gib(n: Long): Long = n * 1024L * 1024L * 1024L
}
