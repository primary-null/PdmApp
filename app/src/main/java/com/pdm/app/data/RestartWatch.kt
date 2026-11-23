package com.pdm.app.data

/** One look at the PDM machine while it restarts. */
sealed interface HostProbe {
    /** It answered with its uptime. */
    data class Up(val uptimeSeconds: Long) : HostProbe

    /** Something answered, but not with a usable status (still starting, or an HTTP error). */
    data object Answering : HostProbe

    /** It answered that the sign-in is no longer valid (401). Says nothing about the reboot. */
    data object SignInNeeded : HostProbe

    /** The certificate check refused it (changed or unconfirmed certificate). */
    data object CertificateRefused : HostProbe

    /** Nothing answered: connection refused, timed out, no route. */
    data object Silent : HostProbe
}

/**
 * Decides when a restarted machine is back. Back needs a healthy status ([HostProbe.Up]) plus proof
 * that it rebooted after the request:
 *  - its uptime is shorter than [uptimeBefore], the baseline read just before the request; or
 *  - its uptime is shorter than the time since the request was sent ([secondsSinceSubmit]): it
 *    booted after the request, even if no outage was ever seen (fast reboot, app was closed); or
 *  - with neither number known, an outage was observed first.
 * HTTP errors, sign-in or certificate refusals and silence never prove it; an answer carrying the
 * old uptime is the machine before it went down.
 */
class RestartWatch(
    private val uptimeBefore: Long?,
    private val secondsSinceSubmit: () -> Long? = { null },
    sawDown: Boolean = false,
) {
    enum class Phase { WAITING, DOWN, BACK }

    var sawDown: Boolean = sawDown
        private set

    fun observe(probe: HostProbe): Phase = when (probe) {
        HostProbe.Silent -> {
            sawDown = true
            Phase.DOWN
        }
        HostProbe.Answering, HostProbe.SignInNeeded, HostProbe.CertificateRefused ->
            if (sawDown) Phase.DOWN else Phase.WAITING
        is HostProbe.Up -> {
            val since = secondsSinceSubmit()
            val rebooted = (uptimeBefore != null && probe.uptimeSeconds < uptimeBefore) ||
                (since != null && probe.uptimeSeconds < since) ||
                (uptimeBefore == null && since == null && sawDown)
            when {
                rebooted -> Phase.BACK
                sawDown -> Phase.DOWN
                else -> Phase.WAITING
            }
        }
    }
}
