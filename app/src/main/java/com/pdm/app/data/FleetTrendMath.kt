package com.pdm.app.data

import com.pdm.app.data.model.RrdField
import com.pdm.app.data.model.RrdPoint

/** Fleet CPU, memory and network over time, summed from every node's own history (PDM rrddata). */
data class FleetTrendData(
    val cpu: List<Float?> = emptyList(),
    val mem: List<Float?> = emptyList(),
    /** Bytes per second, in plus out. */
    val net: List<Float?> = emptyList(),
)

object FleetTrendMath {
    /**
     * Sum per timestamp. CPU is weighted by cores (`cpu-max`, or the node's core count when the
     * history has none), memory is used over total, network adds every node's in and out.
     * A timestamp where not every node reported network shows a gap rather than a dip.
     * Long histories are averaged down to [maxPoints].
     */
    fun aggregate(perNode: List<Pair<List<RrdPoint>, Double?>>, maxPoints: Int = 120): FleetTrendData {
        val nodes = perNode.filter { it.first.isNotEmpty() }
        if (nodes.isEmpty()) return FleetTrendData()
        val byTime = nodes.map { (points, cores) -> points.associateBy { it.time } to cores }
        val times = nodes.flatMap { (points, _) -> points.map { it.time } }.distinct().sorted()
        val cpu = ArrayList<Float?>(times.size)
        val mem = ArrayList<Float?>(times.size)
        val net = ArrayList<Float?>(times.size)
        for (t in times) {
            var cpuUsed = 0.0
            var cpuMax = 0.0
            var memUsed = 0.0
            var memTotal = 0.0
            var traffic = 0.0
            var netNodes = 0
            for ((points, cores) in byTime) {
                val v = points[t]?.values ?: continue
                val c = v[RrdField.CPU]
                val max = v[RrdField.CPU_MAX] ?: cores
                if (c != null && max != null && max > 0) {
                    cpuUsed += c * max
                    cpuMax += max
                }
                val used = v[RrdField.MEM_USED]
                val total = v[RrdField.MEM_TOTAL]
                if (used != null && total != null && total > 0) {
                    memUsed += used
                    memTotal += total
                }
                val inBytes = v[RrdField.NET_IN]
                val outBytes = v[RrdField.NET_OUT]
                if (inBytes != null || outBytes != null) {
                    traffic += (inBytes ?: 0.0) + (outBytes ?: 0.0)
                    netNodes++
                }
            }
            cpu += if (cpuMax > 0) (cpuUsed / cpuMax).toFloat() else null
            mem += if (memTotal > 0) (memUsed / memTotal).toFloat() else null
            net += if (netNodes == byTime.size) traffic.toFloat() else null
        }
        return FleetTrendData(downsample(cpu, maxPoints), downsample(mem, maxPoints), downsample(net, maxPoints))
    }

    /** Average consecutive values into at most [max] slots; all-null slots stay null. */
    fun downsample(values: List<Float?>, max: Int): List<Float?> {
        if (values.size <= max) return values
        val step = values.size.toDouble() / max
        return List(max) { i ->
            val from = (i * step).toInt()
            val to = ((i + 1) * step).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
            val slice = values.subList(from, to).filterNotNull()
            if (slice.isEmpty()) null else slice.average().toFloat()
        }
    }
}
