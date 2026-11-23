package com.pdm.app.data

/**
 * Why each permission the app declares is there, in operator words. Settings lists what Android
 * actually granted (read from the package, not from this list); this only supplies the reasons.
 * The manifest test keeps the two in step: a permission without a reason here fails the build.
 */
object AppPermissions {
    data class Reason(val label: String, val why: String)

    val reasons: Map<String, Reason> = mapOf(
        "android.permission.INTERNET" to Reason(
            "Network",
            "Talk to your PDM server over HTTPS, find it on the local network, and relay node shells. " +
                "Nothing goes anywhere else.",
        ),
        "android.permission.ACCESS_NETWORK_STATE" to Reason(
            "Network state",
            "See which networks are up: retry when the connection returns and pick the subnet to scan.",
        ),
        "android.permission.VIBRATE" to Reason(
            "Vibration",
            "Haptic ticks on pull-to-refresh and the Clean Slate hold. Off when touch feedback is off.",
        ),
        "android.permission.FOREGROUND_SERVICE" to Reason(
            "Keep shell alive",
            "While a node shell is open, keep the app running when you switch away, so an upgrade is not cut off. Stops when the shell closes.",
        ),
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" to Reason(
            "Keep shell alive (type)",
            "The kind of background work above: a remote admin terminal. Android asks apps to name it.",
        ),
        "android.permission.POST_NOTIFICATIONS" to Reason(
            "Notifications",
            "Only the \"shell open\" notice while a node shell runs. Optional: declining just hides it.",
        ),
        "android.permission.USE_BIOMETRIC" to Reason(
            "Screen lock check",
            "Only if you turn it on in Extras: fingerprint, face or PIN before Stop, Shutdown, rollback or task stop. Nothing is stored.",
        ),
        // AndroidX adds this signature permission so broadcasts the app registers stay private to it.
        "com.pdm.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to Reason(
            "Private broadcasts",
            "Internal: only this app can send to its own receivers. Not shown to or granted by you.",
        ),
    )

    /**
     * Never declared: the app keeps everything in its private storage, so shared-storage access
     * would be overreach. Listed so a test can refuse them.
     */
    val refused: Set<String> = setOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.QUERY_ALL_PACKAGES",
    )

    /** First SDK whose targets must declare and request local network access (Android 17). */
    const val LOCAL_NETWORK_SDK = 37
    const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
}
