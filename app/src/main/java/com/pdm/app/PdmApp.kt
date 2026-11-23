package com.pdm.app

import android.app.Application
import com.pdm.app.data.api.PdmClientFactory
import com.pdm.app.data.cache.KeystoreSealer
import com.pdm.app.data.cache.ResponseCache
import com.pdm.app.data.net.LocalNet
import com.pdm.app.data.repo.AuthRepository
import com.pdm.app.data.repo.FleetRepository
import com.pdm.app.data.session.SessionStore

open class PdmApp : Application() {
    val activityFeed = com.pdm.app.data.api.ApiActivityFeed()
    lateinit var responseCache: ResponseCache
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var clientFactory: PdmClientFactory
        private set
    lateinit var authRepository: AuthRepository
        private set
    lateinit var fleetRepository: FleetRepository
        private set
    lateinit var localNet: LocalNet
        private set
    lateinit var shellConnector: com.pdm.app.data.shell.ShellConnector
        private set
    /** Tile, widget and device controls: what they show and what they may ask the open app. */
    lateinit var surfaces: com.pdm.app.data.system.SystemSurfaces
        private set

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        // noBackupFilesDir: never leaves the device in a backup. Pruned off the main thread by the view model.
        responseCache = ResponseCache(java.io.File(noBackupFilesDir, "response-cache"), KeystoreSealer())
        clientFactory = PdmClientFactory(sessionStore, activityFeed, responseCache)
        authRepository = AuthRepository(sessionStore, clientFactory)
        fleetRepository = FleetRepository(sessionStore, clientFactory)
        localNet = LocalNet(this, sessionStore)
        shellConnector = com.pdm.app.data.shell.ShellConnector(sessionStore, clientFactory)
        surfaces = com.pdm.app.data.system.SystemSurfaces(this)
        com.pdm.app.system.LaunchAction.publishShortcuts(this)
    }
}
