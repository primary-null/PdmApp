package com.pdm.app.data.ops

import com.pdm.app.data.model.ReadAvailability

/**
 * The optional parts of a fleet read (Q4). Each is read on its own and can fail on its own; the
 * remotes and resources lists are not here because without them there is no fleet to show.
 */
enum class FleetSection(val label: String, val retryable: Boolean = true) {
    REMOTE_HEALTH("Remote health", retryable = false),
    LEADERS("Busiest guests and nodes"),
    TASKS("Recent tasks"),
    SUBSCRIPTIONS("Subscriptions"),
    UPDATES("Updates summary"),
    PERMISSIONS("Your permissions"),
    HOST("PDM machine", retryable = false),
    HOST_STATUS("PDM machine status"),
    HOST_UPDATES("PDM machine packages"),
    HOST_SUBSCRIPTION("PDM machine subscription", retryable = false),
}

/** How one section's latest read went. Kept apart from the data so old values can stay on screen. */
data class SectionStatus(val kind: Kind, val cachedAt: Long? = null) {
    enum class Kind { LIVE, CACHED, FORBIDDEN, UNSUPPORTED, MISSING, UNREACHABLE, FAILED }

    val live: Boolean get() = kind == Kind.LIVE

    /** A plain reason for the person: what it means for what they see. */
    val reason: String
        get() = when (kind) {
            Kind.LIVE -> "Up to date"
            Kind.CACHED -> "Saved copy"
            Kind.FORBIDDEN -> "Your account can't read this"
            Kind.UNSUPPORTED -> "This server doesn't offer it"
            Kind.MISSING -> "Not found on the server"
            Kind.UNREACHABLE -> "No connection"
            Kind.FAILED -> "Didn't load"
        }

    /** Retrying makes sense: the cause can go away by itself. */
    val worthRetrying: Boolean get() = kind == Kind.UNREACHABLE || kind == Kind.FAILED || kind == Kind.CACHED

    companion object {
        val LIVE = SectionStatus(Kind.LIVE)

        fun of(read: ReadAvailability<*>): SectionStatus = when (read) {
            is ReadAvailability.Fresh -> LIVE
            is ReadAvailability.Stale -> SectionStatus(Kind.CACHED, read.cachedAt)
            is ReadAvailability.Forbidden -> SectionStatus(Kind.FORBIDDEN)
            is ReadAvailability.Unsupported -> SectionStatus(Kind.UNSUPPORTED)
            is ReadAvailability.NotFound -> SectionStatus(Kind.MISSING)
            is ReadAvailability.TransportError -> SectionStatus(Kind.UNREACHABLE)
            is ReadAvailability.Failed -> SectionStatus(Kind.FAILED)
            is ReadAvailability.Loading, ReadAvailability.NotRequested -> SectionStatus(Kind.FAILED)
        }
    }
}

/**
 * Merge one section's new read over what is on screen: a read that produced nothing keeps the old
 * value (it is then shown with its status, never as a current empty list or zero).
 */
fun <T> keepOnFailure(read: ReadAvailability<T>?, previous: T?): T? = when (read) {
    null -> previous
    is ReadAvailability.Fresh -> read.value
    is ReadAvailability.Stale -> read.value
    // Forbidden: there is nothing the account may see, so nothing old is kept either.
    is ReadAvailability.Forbidden -> null
    else -> previous
}
