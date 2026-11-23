package com.pdm.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdm.app.PdmApp
import com.pdm.app.data.api.ApiActivity
import com.pdm.app.data.cache.ResponseCache
import com.pdm.app.data.model.LastLoginForm
import com.pdm.app.data.api.PdmAuth
import com.pdm.app.data.api.isCaPin
import com.pdm.app.data.api.pinDisplayFingerprint
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.DashboardMetrics
import com.pdm.app.data.model.FleetDatastore
import com.pdm.app.data.model.FleetNetwork
import com.pdm.app.data.model.FleetNode
import com.pdm.app.data.model.FleetStorage
import com.pdm.app.data.model.Guest
import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.GuestAction
import com.pdm.app.data.model.NodeUpdates
import com.pdm.app.data.FleetMapper
import com.pdm.app.data.model.MigrateCheck
import com.pdm.app.data.model.MigrateRequest
import com.pdm.app.data.model.NodeInventory
import com.pdm.app.data.settings.MotionStyle
import com.pdm.app.data.settings.WatermarkStrength
import com.pdm.app.data.session.SavedConnection
import com.pdm.app.data.settings.UiPrefs
import com.pdm.app.data.settings.WatermarkMode
import com.pdm.app.data.settings.WatermarkTone
import com.pdm.app.data.model.PdmHost
import com.pdm.app.data.model.TaskStatisticsDto
import com.pdm.app.data.model.RemoteDetail
import com.pdm.app.data.model.RemoteTaskDto
import com.pdm.app.data.model.check
import com.pdm.app.data.repo.LoadDepth
import com.pdm.app.data.repo.MigrateTargets
import com.pdm.app.data.model.RrdPoint
import com.pdm.app.data.model.RrdTimeframe
import com.pdm.app.data.model.GuestSnapshot
import com.pdm.app.data.model.OperationKind
import com.pdm.app.data.model.OperationReceipt
import com.pdm.app.data.model.OperationSubmissionState
import com.pdm.app.data.model.ReadAvailability
import com.pdm.app.data.model.SharedNotesState
import com.pdm.app.data.model.SyslogLineDto
import com.pdm.app.data.model.TaskFilters
import com.pdm.app.data.model.GuestStatusDto
import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.LeaderEntry
import com.pdm.app.data.model.RealmDto
import com.pdm.app.data.model.Leaderboards
import com.pdm.app.data.model.LoginOutcome
import com.pdm.app.data.model.RemoteNode
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.RemoteTask
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.TaskLogLine
import com.pdm.app.data.model.TaskPhase
import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.model.taskRollup
import com.pdm.app.data.model.VersionInfo
import com.pdm.app.data.model.canCheckHostUpdates
import com.pdm.app.data.model.canEditSharedNotes
import com.pdm.app.data.model.canReadHostLogs
import com.pdm.app.data.model.canReadHostTasks
import com.pdm.app.data.model.canStopTask
import com.pdm.app.data.model.TaskOrigin
import com.pdm.app.data.model.RrdField
import kotlinx.coroutines.sync.Mutex
import com.pdm.app.data.net.DiscoveredHost
import com.pdm.app.data.net.ScanStep
import com.pdm.app.data.net.ScanTone
import com.pdm.app.data.session.CertPin
import com.pdm.app.data.session.SessionIdentity
import com.pdm.app.ui.home.DashFocus
import com.pdm.app.data.system.FleetCommands
import com.pdm.app.data.system.FleetGlance
import com.pdm.app.system.LaunchAction
import com.pdm.app.ui.home.DashNav
import com.pdm.app.ui.home.toFocus
import com.pdm.app.ui.login.jumpBackCaption
import com.pdm.app.ui.login.jumpBackNote
import com.pdm.app.ui.util.TripleTapUnlock
import com.pdm.app.data.repo.isAuthFailure
import com.pdm.app.data.repo.isNetworkFailure
import com.pdm.app.data.repo.isSessionChanged
import com.pdm.app.data.repo.isTlsFailure
import com.pdm.app.data.repo.rootMessage
import com.pdm.app.data.shell.ShellPhase
import com.pdm.app.data.shell.ShellSession
import com.pdm.app.data.shell.ShellStatus
import com.pdm.app.data.shell.ShellTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LoginFormState(
    val host: String = "",
    val port: String = "8443",
    val username: String = "root",
    val realm: String = "pam",
    val password: String = "",
    val apiToken: String = "",
    val authMode: AuthMode = AuthMode.PASSWORD,
    val remember: Boolean = true,
    val autoConnect: Boolean = false,
    val secretPrefillMasked: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val tfaRequired: Boolean = false,
    /** The second factor being typed: a TOTP code or a recovery key, per [tfaKind]. */
    val otp: String = "",
    val tfaKind: com.pdm.app.data.api.TfaKind = com.pdm.app.data.api.TfaKind.TOTP,
    /** What the account offers; null until a login asked for a second factor. */
    val tfaMethods: com.pdm.app.data.api.TfaMethods? = null,
    val fingerprint: String? = null,
    val demoUnlocked: Boolean = false,
    val connections: List<SavedConnection> = emptyList(),
    /** Set while a voluntary account switch can still return to the parked session. */
    val jumpBackLabel: String? = null,
    val jumpBackNote: String? = null,
    /** Realms read from the host in [realmsFor] (`host:port`); null until asked. */
    val realms: List<RealmDto>? = null,
    val realmsFor: String? = null,
    val realmsError: String? = null,
    /** A certificate is waiting for the user's yes or no; nothing was sent to the server yet. */
    val pendingCert: CertQuestion? = null,
    /** Launch with auto-connect: deciding between the cached view and signing in. Nothing is drawn. */
    val starting: Boolean = false,
)

/** The certificate dialog: [authorityChange] for a CA pin whose authority changed. */
data class CertQuestion(
    val host: String,
    val fingerprint: String,
    val authorityChange: Boolean,
    val knownServer: Boolean,
)

data class NetworkScanState(
    val isScanning: Boolean = false,
    val scannedCount: Int = 0,
    val totalCount: Int = 0,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val pdmDetectedHosts: List<DiscoveredHost> = emptyList(),
    val unverifiedHosts: List<String> = emptyList(),
    val error: String? = null,
    val infoMessage: String? = null,
    val isComplete: Boolean = false,
    val isDemoSim: Boolean = false,
    /** What the scan did, for the terminal view; memory only, gone with the screen. */
    val log: List<ScanStep> = emptyList(),
    val openCount: Int = 0,
    val networks: List<String> = emptyList(),
    val elapsedMs: Long? = null,
)

data class FleetUiState(
    val signedIn: Boolean = false,
    val demo: Boolean = false,
    val hostLabel: String = "",
    val version: String? = null,
    val remotes: List<RemoteNode> = emptyList(),
    val guests: List<Guest> = emptyList(),
    val nodes: List<FleetNode> = emptyList(),
    val storages: List<FleetStorage> = emptyList(),
    val datastores: List<FleetDatastore> = emptyList(),
    val networks: List<FleetNetwork> = emptyList(),
    val metrics: DashboardMetrics = DashboardMetrics(),
    val leaders: Leaderboards = Leaderboards(),
    val subscriptions: List<RemoteSubscription> = emptyList(),
    val tasks: List<RemoteTask>? = null,
    val selectedRemoteId: String? = null,
    val busyIds: Set<String> = emptySet(),
    /** Shutdown / Stop waiting for the operator to confirm. Start runs immediately. */
    val pendingPower: PendingPower? = null,
    /** Fleet load in flight, last success, failures in a row; see [RefreshPolicy]. */
    val sync: SyncState = SyncState(),
    /** Status and log of the task open in the detail pane. */
    val taskView: TaskView? = null,
    val pendingTaskStop: RemoteTask? = null,
    /** Live status + snapshots of the guest open in the detail pane. */
    val guestView: GuestView? = null,
    val pendingSnapshot: PendingSnapshot? = null,
    /** Guest id whose "new snapshot" form is open. */
    val snapshotFormFor: String? = null,
    val permissions: Permissions? = null,
    /** `null` until the first summary read lands (or when it failed). */
    val updates: List<NodeUpdates>? = null,
    val updatesRefreshing: Boolean = false,
    val nodeUpdatesView: NodeUpdatesView? = null,
    /** Shell waiting for the user's go-ahead. */
    val pendingShell: PendingShell? = null,
    /** The open terminal, shown over everything else. */
    val shell: ShellView? = null,
    /** Terminal snippets the user saved; the built-ins come from [com.pdm.app.data.shell.Snippets]. */
    val savedSnippets: List<com.pdm.app.data.shell.Snippet> = emptyList(),
    /** False for API-token and offline sessions: PDM opens shells only for password sign-ins. */
    val shellAllowed: Boolean = false,
    val host: PdmHost? = null,
    val remoteView: RemoteView? = null,
    /** Usage history for the guest or node open in the detail pane. */
    val graphView: GraphView? = null,
    val migrateView: MigrateView? = null,
    /** Storage + network of the node open in the detail pane, keyed by node id. */
    val nodeInventory: Pair<String, NodeInventory>? = null,
    val error: String? = null,
    val fingerprint: String? = null,
    val query: String = "",
    val timeframe: String = "day",
    val showSettings: Boolean = false,
    val motionStyle: MotionStyle = MotionStyle.OFF,
    val watermarkStrength: WatermarkStrength = WatermarkStrength.STANDARD,
    val sessionInfo: String = "",
    /**
     * Offline view: the session has no ticket and everything comes from the response cache.
     * Writes are refused and the controls for them are hidden until the link is back.
     */
    val offline: Boolean = false,
    /** Settings > Offline cache. */
    val offlineCache: Boolean = true,
    /** Destructive confirms also ask for the screen lock (Settings > Extras). */
    val screenLockConfirm: Boolean = false,
    /** Long-press on the header chip: the chip opens into the sync and request panel. */
    val livePanel: Boolean = false,
    /** EXPAND: dashboard tiles as full-width plates with graphs. */
    val dashboardExpanded: Boolean = false,
    /** Fleet CPU / memory / network history for the dashboard timeframe (PDM's own history). */
    val trend: TrendView? = null,
    /** Counts the app recorded, one a minute while live (see FleetHistory). */
    val history: List<com.pdm.app.data.FleetSample> = emptyList(),
    val cacheStats: ResponseCache.Stats? = null,
    val watermarkMode: WatermarkMode = WatermarkMode.FOLLOW,
    val watermarkTone: WatermarkTone = WatermarkTone.AMBER,
    val themeMode: com.pdm.app.data.settings.ThemeMode = com.pdm.app.data.settings.ThemeMode.DARK,
    /** Easter egg: the app's opinion, shown once each time Light is chosen. */
    val themeQuip: Quip? = null,
    /** A short message at the bottom of the screen ("PDM is back."). */
    val notice: Quip? = null,
    /** Restart PDM, waiting for the person to confirm. */
    val pendingHostRestart: Boolean = false,
    /** PDM is restarting: the screen keeps the last data and reconnects by itself. */
    /** Full task history with server-side filters (F1). */
    val taskHistory: com.pdm.app.data.ops.HistoryState = com.pdm.app.data.ops.HistoryState(),
    /** The filters being edited; [taskHistory] shows the last applied ones. */
    val historyDraft: com.pdm.app.data.ops.HistoryQuery = com.pdm.app.data.ops.HistoryQuery(),
    /** Search within the history rows already loaded. */
    val historySearch: String = "",
    /** A planned restart of the PDM machine and where it stands (Q1); null when none. */
    val hostRestart: com.pdm.app.data.ops.RestartProgress? = null,
    val certPins: List<CertPin> = emptyList(),
    val secretSaved: Boolean = false,
    /** Shared PDM notes being read or edited (F3). */
    val notes: com.pdm.app.data.ops.NotesEditorState = com.pdm.app.data.ops.NotesEditorState(),
    /** Leaving the PDM screen with unsaved notes: waiting for the person to confirm. */
    val pendingNotesLeave: Boolean = false,
    /** Cluster descriptions by remote (read-only, live only). */
    val remoteNotes: Map<String, com.pdm.app.ui.NoteRead> = emptyMap(),
    /** Node descriptions by "remote/node" (read-only, live only). */
    val nodeNotes: Map<String, com.pdm.app.ui.NoteRead> = emptyMap(),
    /** The PDM machine's own tasks, filtered and paged by the server (F2). */
    val hostTaskHistory: com.pdm.app.data.ops.HistoryState = com.pdm.app.data.ops.HistoryState(),
    /** The PDM machine's journal, read only when asked (F2). */
    val hostLogs: com.pdm.app.data.ops.JournalState = com.pdm.app.data.ops.JournalState(),
    /** The service typed into the log filter, not applied until Read. */
    val hostLogsService: String = "",
    /** How each optional section's latest read went (Q4); missing means never read this session. */
    val sections: Map<com.pdm.app.data.ops.FleetSection, com.pdm.app.data.ops.SectionStatus> = emptyMap(),
    /** Sections being re-read on their own Retry. */
    val sectionRetrying: Set<com.pdm.app.data.ops.FleetSection> = emptySet(),
    /** Why the host graph has no points, when it failed. */
    val hostRrdError: String? = null,
    val hostRrdData: List<RrdPoint> = emptyList(),
    val hostRrdLoading: Boolean = false,
    val activeReceipts: List<OperationReceipt> = emptyList(),
    val hostUpdatesOpenPackage: String? = null,
    val hostUpdatesChangelog: String? = null,
    val hostUpdatesLoading: Boolean = false,
    val hostRrdTimeframe: RrdTimeframe = RrdTimeframe.HOUR,
    val hostRrdField: String = RrdField.CPU,
    val nav: DashNav = DashNav(),
) {
    /**
     * What write controls (power, snapshots, migrate, task stop) may offer. Nothing while the
     * screen shows cached data (offline view, or a live session whose server stopped answering):
     * a Stop pressed on a saved picture may hit a guest that is no longer in that state.
     * PDM still checks every live call.
     */
    val writable: Permissions?
        get() = if (readOnly) NO_WRITES else permissions

    /** The screen shows saved data, not what the server says now. */
    val readOnly: Boolean
        get() = offline || sync.cachedAt != null || hostRestart?.blocksWrites == true
}

private val NO_WRITES = Permissions(emptyMap())

data class PendingPower(val guest: Guest, val action: GuestAction)

data class TrendView(
    val timeframe: String,
    val data: com.pdm.app.data.FleetTrendData? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class MigrateView(
    val guestId: String,
    val request: MigrateRequest,
    /** Other online nodes of the guest's remote (in-cluster targets). */
    val nodes: List<String>,
    /** Other PVE remotes (cross-remote targets). */
    val remotes: List<String>,
    val check: MigrateCheck? = null,
    val checking: Boolean = false,
    val targets: MigrateTargets? = null,
    val targetsLoading: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    /** Why the chosen node needs a second thought (HA maintenance or fault), from the latest refresh. */
    val haConcern: String? = null,
    /** The person chose this node anyway, knowing [haConcern]. Reset whenever the node changes. */
    val haConfirmed: Boolean = false,
) {
    val canStart: Boolean
        get() = !starting && !checking && !targetsLoading && error == null && (haConcern == null || haConfirmed) && when {
            !request.crossRemote -> request.targetNode != null && check?.blockers.isNullOrEmpty()
            else -> request.targetRemote != null && request.targetStorage != null && request.targetBridge != null &&
                check?.guestBlockers.isNullOrEmpty()
        }
}

data class GraphView(
    /** `guest:<id>` or `node:<id>`; a late answer for another entity is dropped. */
    val key: String,
    val timeframe: RrdTimeframe = RrdTimeframe.HOUR,
    val points: List<RrdPoint>? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class RemoteView(
    val remoteId: String,
    val detail: RemoteDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class NodeUpdatesView(
    val remoteId: String,
    val node: String,
    val packages: List<AptUpdateDto>? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** `apt update` is running on the node. */
    val checking: Boolean = false,
    /** Why the last Check now failed; the package list on screen is still the earlier one. */
    val checkError: String? = null,
    /** Package whose changelog is expanded, and its text once loaded. */
    val openPackage: String? = null,
    val changelog: String? = null,
)

/** [staged] is typed at the prompt once the shell is up, without Enter: the user reviews it first. */
/** [startedAt] is elapsedRealtime. [error] once it gave up waiting. */
/** What one fleet load got: all answers live, and the PDM host's status read in this load. */
data class LoadOutcome(val live: Boolean, val hostFresh: Boolean)

/** A read-only description (cluster or node notes): its text, or why it didn't load. */
data class NoteRead(val text: String? = null, val error: String? = null)

data class PendingShell(
    val target: ShellTarget,
    val staged: String?,
    /** A node shell: the node asks for its own root login, which the app can answer. */
    val nodeLogin: Boolean = false,
    /** A remembered root password for this node exists; the field may stay empty. */
    val savedPassword: Boolean = false,
)

data class ShellView(
    val target: ShellTarget,
    val status: ShellStatus,
    val staged: String?,
    val prompt: com.pdm.app.data.shell.PromptKind? = null,
    val stagedTyped: Boolean = false,
    /** Null when the app is not signing in for you (no password given, or the PDM host). */
    val login: com.pdm.app.data.shell.NodeLogin.State? = null,
    /** The node refused a remembered password, which was then forgotten. */
    val forgotSaved: Boolean = false,
)

data class GuestView(
    val guestId: String,
    val live: GuestStatusDto? = null,
    val snapshots: List<GuestSnapshot>? = null,
    /** Curated config lines; null until read or when the read failed. */
    val hardware: List<com.pdm.app.data.HardwareLine>? = null,
    val loading: Boolean = true,
    /** What is running against this guest right now ("Creating snapshot", "Rolling back"). */
    val busy: String? = null,
    /** The operation behind [busy]; it clears when that operation settles. */
    val busyReceiptId: String? = null,
    val error: String? = null,
)

/** Rollback and delete both change or drop guest state; both wait for a confirm. */
data class PendingSnapshot(val guest: Guest, val name: String, val rollback: Boolean)

/** The task detail screen: one task, its log window (Q2), and a Stop in progress. */
data class TaskView(
    val task: RemoteTask,
    val log: com.pdm.app.data.ops.TaskLogState = com.pdm.app.data.ops.TaskLogState(),
    val stopping: Boolean = false,
    /** Why Stop didn't work, when it didn't. */
    val stopError: String? = null,
) {
    val status: TaskStatus? get() = log.status
    val loading: Boolean get() = log.loading

    /** Live status wins; the list row is the fallback until the first read lands. */
    val running: Boolean
        get() = log.status?.isRunning ?: (task.phase == TaskPhase.Running)

    /** Shown with the status: a failed Stop, or a status that couldn't be read. */
    val error: String?
        get() = stopError ?: log.error?.takeIf { log.statusUnknown }
}

private const val TASK_POLL_MS = 2_000L
/** Cache key for the recorded dashboard history; not a PDM path, so it cannot collide. */
private const val HISTORY_KEY = "app/fleet-history"
private const val HISTORY_SAVE_MS = 5 * 60_000L
private const val UPDATES_SETTLE_MS = 5_000L
private const val SHELL_PING_MS = 30_000L
private const val RESTART_WAIT_MS = 15 * 60_000L
private const val RESTART_FIRST_LOOK_MS = 4_000L
private const val RESTART_PROBE_EVERY_MS = 3_000L
private const val RESTART_PROBE_TIMEOUT_MS = 6_000L

private data class ShellProgress(
    val status: ShellStatus,
    val prompt: com.pdm.app.data.shell.PromptKind?,
    val typed: Boolean,
    val login: com.pdm.app.data.shell.NodeLogin.State?,
)
private const val UPGRADE_COMMAND = "apt full-upgrade"

class FleetViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PdmApp
    private var shellSession: ShellSession? = null
    private var shellJob: Job? = null
    private val last = app.sessionStore.loadLastForm()
    private val splitUser = PdmAuth.splitUserRealm(last.username)

    private val _login = MutableStateFlow(
        LoginFormState(
            host = last.host,
            port = last.port.toString(),
            username = splitUser.first.ifBlank { "root" },
            realm = splitUser.second.ifBlank { PdmAuth.DEFAULT_REALM },
            authMode = last.authMode,
            autoConnect = last.autoConnect,
            secretPrefillMasked = last.savedSecret.isNotBlank(),
            remember = last.savedSecret.isNotBlank() || last.host.isBlank(),
            starting = last.autoConnect && last.host.isNotBlank() && last.savedSecret.isNotBlank(),
        ),
    )
    val login: StateFlow<LoginFormState> = _login.asStateFlow()

    private val uiPrefs = UiPrefs(application)

    /** Operations the app sent (Q5): receipts, observation and restore for the signed-in session. */
    private val tracker by lazy {
        OperationTracker(
            scope = viewModelScope,
            store = object : ReceiptStore {
                override fun all(identity: SessionIdentity) = app.sessionStore.getOperationReceipts(identity)
                override fun save(identity: SessionIdentity, receipt: OperationReceipt) =
                    app.sessionStore.saveOperationReceipt(identity, receipt)
                override fun update(identity: SessionIdentity, id: String, transform: (OperationReceipt) -> OperationReceipt) =
                    app.sessionStore.updateOperationReceipt(identity, id, transform)
            },
            reads = object : OperationReads {
                override suspend fun liveStatus(receipt: OperationReceipt): TaskStatus? =
                    OperationTracker.taskOf(receipt)?.let { app.fleetRepository.taskStatus(it, liveOnly = true) }
                override suspend fun recentTasks(receipt: OperationReceipt) = app.fleetRepository.recentTaskCandidates(receipt)
            },
            isFatal = { it.isAuthFailure() || it.isTlsFailure() || it.isSessionChanged() },
            onChange = { list -> _fleet.update { it.copy(activeReceipts = list) } },
            onSettled = { onOperationSettled(it) },
        )
    }
    private val cache = app.responseCache
    private fun resetFleet() = FleetUiState(
        savedSnippets = app.sessionStore.savedSnippets(),
        watermarkMode = uiPrefs.watermarkMode,
        watermarkTone = uiPrefs.watermarkTone,
        themeMode = uiPrefs.themeMode,
        motionStyle = uiPrefs.motionStyle,
        watermarkStrength = uiPrefs.watermarkStrength,
        offlineCache = uiPrefs.offlineCache,
        screenLockConfirm = uiPrefs.screenLockConfirm,
        dashboardExpanded = uiPrefs.dashboardExpanded,
    )

    /** Login whose recorded history is loaded into [FleetUiState.history]; null until read. */
    private var historyFor: String? = null
    private var historySavedAt = 0L
    private var trendJob: Job? = null

    private val _fleet = MutableStateFlow(resetFleet())
    val fleet: StateFlow<FleetUiState> = _fleet.asStateFlow()

    /**
     * Recent API calls for the activity background. Its own stream: putting it in [fleet] redrew
     * the whole home screen twice per request.
     */
    val activity: StateFlow<List<ApiActivity>> = app.activityFeed.events

    /** TLS version of the last live exchange, for the header's protocol tags. */
    val tls: StateFlow<String?> = app.activityFeed.tls

    private val _journal = MutableStateFlow<List<SyncNote>>(emptyList())

    /** What the refresh, cache and reconnect logic decided, newest last. Memory only, no data. */
    val journal: StateFlow<List<SyncNote>> = _journal.asStateFlow()

    private val _scan = MutableStateFlow(NetworkScanState())
    val scan: StateFlow<NetworkScanState> = _scan.asStateFlow()

    private var pendingTfaIdentity: SessionIdentity? = null
    private var pendingCert: LoginOutcome.NeedsCertConfirmation? = null
    private var pendingTfaTicket: String? = null
    private var scanJob: Job? = null
    private var refreshJob: Job? = null
    /** Bumped for every fleet load and on sign-out; only the newest load may touch [SyncState]. */
    private var refreshToken = 0L
    private var refreshDepth: LoadDepth? = null
    /** True while [keepFresh] runs, i.e. the home screen is resumed. */
    private val shown = MutableStateFlow(false)
    private var visible: Boolean
        get() = shown.value
        set(value) { shown.value = value }

    /** Where a shortcut, tile, widget or device control asked to go; applied once signed in. */
    private val pendingLaunch = MutableStateFlow<LaunchAction?>(null)

    /** What Android's own surfaces may ask of this screen; the same paths as its buttons. */
    private val surfaceCommands = object : FleetCommands {
        override fun refresh() {
            this@FleetViewModel.refresh(RefreshReason.PULL)
        }

        override fun power(guestId: String, action: GuestAction): Boolean {
            val state = _fleet.value
            val guest = state.guests.firstOrNull { it.id == guestId } ?: return false
            if (!state.signedIn || state.readOnly || guest.id in state.busyIds) return false
            val allowed = state.writable?.allows(Permissions.RESOURCE_MANAGE, Permissions.guestPath(guest.remoteId, guest.vmid)) == true
            if (!allowed) return false
            this@FleetViewModel.power(guest, action)
            return true
        }
    }
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkBack = object : ConnectivityManager.NetworkCallback() {
        // Binder thread. A network came back while loads were failing: try now, not at the backoff.
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                val sync = _fleet.value.sync
                if (visible && sync.failures > 0 && !sync.paused) refresh(RefreshReason.AUTO)
            }
        }
    }

    /** Live ticket parked by Exit / Switch account. Not written to disk. */
    private var parkedSession: SessionState? = null
    private var parkedDemo: Boolean = false
    private var taskJob: Job? = null
    private var scanEpoch = 0
    private val demoUnlock = TripleTapUnlock()

    init {
        app.activityFeed.setEnabled(uiPrefs.watermarkMode.showsActivity)
        app.surfaces.commands = surfaceCommands
        viewModelScope.launch {
            combine(_fleet, shown) { state, onScreen -> glanceOf(state, onScreen) }
                .distinctUntilChanged()
                .collect { app.surfaces.publish(it) }
        }
        viewModelScope.launch {
            combine(pendingLaunch, _fleet.map { it.signedIn }.distinctUntilChanged()) { action, signedIn -> action.takeIf { signedIn } }
                .collect { action ->
                    if (action != null) {
                        pendingLaunch.value = null
                        applyLaunch(action)
                    }
                }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkBack) }
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) { cache.prune() }
            _login.update { it.copy(connections = savedConnections()) }
            if (last.autoConnect && last.host.isNotBlank() && last.savedSecret.isNotBlank()) {
                // Show the cached fleet at once and go live in the background (the refresh
                // schedule reconnects immediately). Without a cache, sign in as before.
                if (!(uiPrefs.offlineCache && enterOffline(last, failed = false))) connect()
            }
            _login.update { it.copy(starting = false) }
        }
    }

    /** Picker rows, with when each login last saved offline data. */
    private fun savedConnections() = app.sessionStore.savedConnections { identity ->
        if (uiPrefs.offlineCache) cache.stats(ResponseCache.loginKey(identity)).newestAt else null
    }

    /** The saved login (with its secret) that the typed form refers to, if any. */
    private fun savedFormFor(form: LoginFormState): LastLoginForm? {
        if (form.host.isBlank()) return null
        val port = form.port.toIntOrNull() ?: PdmAuth.DEFAULT_PORT
        val hostPort = PdmAuth.parseHostPort(form.host, port).copy(port = port)
        val user = PdmAuth.normalizeUsername(form.username, form.realm.ifBlank { PdmAuth.DEFAULT_REALM })
        return app.sessionStore.savedForms().firstOrNull { PdmAuth.identityMatches(hostPort, user, it, form.authMode) }
    }

    private fun loginKeyOf(form: LastLoginForm) =
        ResponseCache.loginKey(SessionIdentity.of(app.sessionStore.configFor(form)))

    /**
     * Only the live login with a saved credential (and the cache switched on) writes to the cache:
     * offline data must not be readable for a login the user did not ask this device to remember.
     */
    private fun syncCacheWrites() {
        val session = app.sessionStore.session.value
        val identity = session
            ?.takeIf { !it.cachedOnly && !it.config.host.equals("demo", ignoreCase = true) }
            ?.let { SessionIdentity.of(it.config) }
        cache.writableLogin = identity
            ?.takeIf { uiPrefs.offlineCache && app.sessionStore.savedFormFor(it) != null }
            ?.let(ResponseCache::loginKey)
    }

    fun updateLogin(transform: (LoginFormState) -> LoginFormState) {
        _login.update { prev ->
            val next = transform(prev)
            val identityChanged =
                next.host != prev.host ||
                    next.port != prev.port ||
                    next.username != prev.username ||
                    next.realm != prev.realm ||
                    next.password != prev.password ||
                    next.apiToken != prev.apiToken ||
                    next.authMode != prev.authMode
            if (identityChanged && (prev.tfaRequired || pendingTfaTicket != null)) {
                pendingTfaIdentity = null
                pendingTfaTicket = null
                next.copy(error = null, tfaRequired = false, otp = "")
            } else {
                next.copy(error = null)
            }
        }
    }

    /** Fill the realm picker from the server. Cached per host:port; failures leave free text. */
    fun loadRealms() {
        val form = _login.value
        if (form.host.isBlank() || PdmAuth.isDemoHost(form.host)) return
        val port = form.port.toIntOrNull() ?: PdmAuth.DEFAULT_PORT
        val key = "${form.host.trim().lowercase()}:$port"
        if (form.realmsFor == key && form.realms != null) return
        _login.update { it.copy(realmsFor = key, realms = null, realmsError = null) }
        viewModelScope.launch {
            try {
                val realms = app.authRepository.realms(form.host, port)
                _login.update { if (it.realmsFor == key) it.copy(realms = realms) else it }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _login.update { if (it.realmsFor == key) it.copy(realmsError = e.rootMessage()) else it }
            }
        }
    }

    fun setRemember(remember: Boolean) {
        _login.update { it.copy(remember = remember, error = null) }
        if (!remember) {
            // Only the login in the form forgets its secret (and with it, its offline data).
            savedFormFor(_login.value)?.let { saved ->
                cache.release(loginKeyOf(saved))
                app.sessionStore.clearSavedSecret(SessionIdentity.of(app.sessionStore.configFor(saved)))
            }
            _login.update { it.copy(secretPrefillMasked = false, autoConnect = false, connections = savedConnections()) }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        _login.update { it.copy(autoConnect = enabled, error = null) }
        app.sessionStore.saveAutoConnect(enabled)
    }

    fun tapDemoUnlock() {
        if (demoUnlock.tap(android.os.SystemClock.elapsedRealtime())) {
            _login.update { it.copy(demoUnlocked = true) }
        }
    }

    private fun sessionDetails(): String {
        val session = app.sessionStore.session.value ?: return "No active session"
        val identity = SessionIdentity.of(session.config)
        val pin = app.sessionStore.getEndpointPin(session.config.host, session.config.port)
        val hasSaved = !session.config.host.equals("demo", true) && app.sessionStore.savedFormFor(identity) != null
        val last = app.sessionStore.loadLastForm()
        val autoConnect = hasSaved && last.autoConnect && last.host.isNotBlank() &&
            SessionIdentity.of(app.sessionStore.configFor(last)) == identity
        return listOf(
            "Server: ${session.config.displayHost}",
            "Mode: ${if (session.cachedOnly) "Offline (cached data, no session)" else "Live"}",
            "Account: ${session.username ?: session.config.username}",
            "Authentication: ${if (session.config.authMode == AuthMode.PASSWORD) "Password ticket" else "API token"}",
            "TLS: " + when {
                pin == null -> "Trusted by Android's certificate authorities (not pinned)"
                isCaPin(pin) -> "Pinned certificate authority (renewals from it pass)"
                else -> "Pinned certificate (you confirmed it)"
            },
            "PDM: ${session.version?.display ?: "unknown"}",
            "Saved credential: ${if (hasSaved) "yes" else "no"}",
            "Auto-connect: ${if (autoConnect) "on" else "off"}",
            (if (isCaPin(pin)) "Authority key SHA-256: " else "Certificate SHA-256: ") +
                (pin?.let(::pinDisplayFingerprint) ?: "not pinned"),
            "Passwords and tokens are never displayed here.",
        ).joinToString("\n")
    }

    fun openSettings() {
        val identity = app.sessionStore.session.value?.config?.let(SessionIdentity::of)
        _fleet.update {
            it.copy(
                showSettings = true,
                certPins = app.sessionStore.listCertPins(),
                secretSaved = !it.demo && identity != null && app.sessionStore.savedFormFor(identity) != null,
                sessionInfo = sessionDetails(),
                cacheStats = identity?.let { id -> cache.stats(ResponseCache.loginKey(id)) },
            )
        }
    }

    /** Off releases every login's offline data at once; on starts filling it again. */
    fun setOfflineCache(enabled: Boolean) {
        uiPrefs.offlineCache = enabled
        if (!enabled) cache.releaseAll()
        syncCacheWrites()
        _fleet.update { it.copy(offlineCache = enabled) }
        openSettings()
    }

    fun clearOfflineCache() {
        cache.releaseAll()
        syncCacheWrites()
        openSettings()
    }

    fun setWatermarkMode(mode: WatermarkMode) {
        if (mode == WatermarkMode.OFF) {
            val current = uiPrefs.watermarkMode
            if (current != WatermarkMode.OFF) uiPrefs.watermarkModeLatent = current
        } else {
            uiPrefs.watermarkModeLatent = mode
        }
        uiPrefs.watermarkMode = mode
        app.activityFeed.setEnabled(mode.showsActivity || _fleet.value.livePanel)
        // Re-bind cached clients when observation was turned back on; no extra API call.
        app.sessionStore.snapshot()?.let { app.activityFeed.bindSession(it.generation) }
        _fleet.update { it.copy(watermarkMode = mode) }
    }

    fun setThemeMode(mode: com.pdm.app.data.settings.ThemeMode) {
        val chosenLight = mode == com.pdm.app.data.settings.ThemeMode.LIGHT &&
            _fleet.value.themeMode != com.pdm.app.data.settings.ThemeMode.LIGHT
        uiPrefs.themeMode = mode
        _fleet.update {
            it.copy(
                themeMode = mode,
                themeQuip = if (chosenLight) Quip(pickQuip(lastQuip), ++quipSeq).also { q -> lastQuip = q.text } else it.themeQuip,
            )
        }
    }

    /** A short line in the notice strip, e.g. why a screen lock check refused. */
    fun notify(text: String) {
        _fleet.update { it.copy(notice = Quip(text, ++quipSeq)) }
    }

    fun setScreenLockConfirm(enabled: Boolean) {
        uiPrefs.screenLockConfirm = enabled
        _fleet.update { it.copy(screenLockConfirm = enabled) }
    }

    /** From a launcher shortcut, the tile, the widget or a device control. */
    fun handleLaunch(action: LaunchAction) {
        pendingLaunch.value = action
    }

    private fun applyLaunch(action: LaunchAction) {
        _fleet.update { it.copy(showSettings = false) }
        when (action) {
            LaunchAction.Refresh -> refresh(RefreshReason.PULL)
            LaunchAction.Tasks -> openFocus(DashFocus.Tasks)
            LaunchAction.Updates -> openFocus(DashFocus.Updates)
            LaunchAction.Faults -> openFocus(DashFocus.Faults)
            is LaunchAction.Guest ->
                if (_fleet.value.guests.any { it.id == action.id }) openFocus(DashFocus.GuestDetail(action.id))
        }
    }

    /** The secret-free summary Android's surfaces show; live only while this screen keeps it fresh. */
    private fun glanceOf(state: FleetUiState, onScreen: Boolean): FleetGlance {
        val sync = state.sync
        val at = sync.cachedAt
            ?: sync.lastSuccessAt?.let { System.currentTimeMillis() - (now() - it) }
            ?: 0L
        return FleetGlance.of(
            signedIn = state.signedIn,
            live = onScreen && !state.demo && !state.readOnly && sync.lastSuccessAt != null,
            demo = state.demo,
            remotes = state.remotes,
            guests = state.guests,
            nodesOnline = state.metrics.nodesOnline,
            nodes = state.metrics.nodes,
            runningTasks = state.tasks?.count { it.phase == TaskPhase.Running } ?: 0,
            writable = state.writable,
            at = at,
        )
    }

    fun dismissNotice(id: Long) {
        _fleet.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    fun requestHostRestart() {
        if (_fleet.value.host == null || !requireLive()) return
        _fleet.update { it.copy(pendingHostRestart = true) }
    }

    fun cancelHostRestart() {
        _fleet.update { it.copy(pendingHostRestart = false) }
    }

    private var restartRecovery: com.pdm.app.data.ops.RestartRecovery? = null
    private var restartJob: Job? = null

    /**
     * Restart the PDM machine once, then wait for it (Q1). The screen keeps the last data and
     * stays read-only until the machine has provably rebooted AND a full reload answered live,
     * host status included. Pull and header taps only make the watcher look sooner.
     */
    fun confirmHostRestart() {
        val host = _fleet.value.host ?: return
        _fleet.update { it.copy(pendingHostRestart = false) }
        if (!requireLive()) return
        if (restartJob?.isActive == true) return
        val session = app.sessionStore.snapshot() ?: return
        val receipt = tracker.begin(
            kind = OperationKind.REBOOT,
            origin = TaskOrigin.Host(host.node),
            description = "Restart ${host.node}",
            port = session.state.config.port,
            node = host.node,
        ) ?: return
        startRestartWatch(session, host.node, receipt, submit = true, baselineHint = host.status?.uptime)
    }

    /** Retry while a restart is unsettled: look at the machine again now. Reads only, never resends. */
    fun retryRestartWatch() {
        restartRecovery?.probeNow()
    }

    /** A restart receipt found unresolved after sign-in or a process restart: watch it, never resend. */
    private fun restoreRestart(session: com.pdm.app.data.session.SessionSnapshot) {
        if (restartJob?.isActive == true) return
        val receipt = tracker.receipts().firstOrNull { it.kind == OperationKind.REBOOT && !it.isTerminal } ?: return
        val node = receipt.node ?: return
        startRestartWatch(session, node, receipt, submit = false, baselineHint = null)
    }

    private fun startRestartWatch(
        session: com.pdm.app.data.session.SessionSnapshot,
        node: String,
        receipt: OperationReceipt,
        submit: Boolean,
        baselineHint: Long?,
    ) {
        val identity = SessionIdentity.of(session.state.config)
        // Same server and account; a re-sign-in during the wait keeps the watch going.
        fun stillOurs() = app.sessionStore.session.value?.let { SessionIdentity.of(it.config) == identity } == true
        val recovery = com.pdm.app.data.ops.RestartRecovery(
            node = node,
            probe = {
                if (!stillOurs()) throw CancellationException("session changed")
                val look = app.fleetRepository.probeHost(node)
                // A ticket that lapsed during a long reboot: renew it from the saved login, then look again.
                if (look == com.pdm.app.data.HostProbe.SignInNeeded && reauthenticate()) app.fleetRepository.probeHost(node) else look
            },
            recoveryLoad = { recoveryLoad(node) },
            persist = { ev ->
                if (stillOurs()) {
                    app.sessionStore.updateOperationReceipt(identity, receipt.id) {
                        it.copy(
                            state = when (ev.phase) {
                                com.pdm.app.data.ops.RestartPhase.SUBMITTING -> OperationSubmissionState.SUBMITTING
                                com.pdm.app.data.ops.RestartPhase.REJECTED -> OperationSubmissionState.REJECTED
                                com.pdm.app.data.ops.RestartPhase.DONE -> OperationSubmissionState.SUCCEEDED
                                else -> OperationSubmissionState.RUNNING
                            },
                            baselineUptime = ev.baselineUptime,
                            sawDown = ev.sawDown,
                            lastObservedAt = System.currentTimeMillis(),
                        )
                    }
                    _fleet.update { it.copy(activeReceipts = tracker.receipts()) }
                }
            },
            wallClock = System::currentTimeMillis,
        )
        restartRecovery = recovery
        restartJob = viewModelScope.launch {
            val progressJob = launch {
                recovery.progress.collect { p -> if (p != null) _fleet.update { it.copy(hostRestart = p) } }
            }
            try {
                val result = if (submit) {
                    // Baseline first: the uptime just before the request proves the reboot later.
                    val baseline = (withTimeoutOrNull(RESTART_PROBE_TIMEOUT_MS) { app.fleetRepository.probeHost(node) }
                        as? com.pdm.app.data.HostProbe.Up)?.uptimeSeconds ?: baselineHint
                    recovery.run(
                        submit = {
                            try {
                                app.fleetRepository.restartHost(node)
                                com.pdm.app.data.ops.SubmitOutcome.Accepted
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                when (val f = com.pdm.app.data.ops.Submission.classify(e)) {
                                    is com.pdm.app.data.ops.SubmitFailure.Rejected -> com.pdm.app.data.ops.SubmitOutcome.Rejected(f.reason)
                                    is com.pdm.app.data.ops.SubmitFailure.NotSent -> com.pdm.app.data.ops.SubmitOutcome.Rejected(f.reason)
                                    com.pdm.app.data.ops.SubmitFailure.Fatal -> com.pdm.app.data.ops.SubmitOutcome.Rejected(e.rootMessage())
                                    // The machine may have taken it before the line dropped: watch, never resend.
                                    com.pdm.app.data.ops.SubmitFailure.Unknown -> com.pdm.app.data.ops.SubmitOutcome.Unknown
                                }
                            }
                        },
                        baselineUptime = baseline,
                    )
                } else {
                    recovery.run(
                        submit = null,
                        baselineUptime = null,
                        evidence = com.pdm.app.data.ops.RestartEvidence(
                            node = node,
                            submittedAt = receipt.submittedAt,
                            baselineUptime = receipt.baselineUptime,
                            sawDown = receipt.sawDown,
                            phase = com.pdm.app.data.ops.RestartPhase.GOING_DOWN,
                        ),
                    )
                }
                when (result.phase) {
                    com.pdm.app.data.ops.RestartPhase.DONE -> _fleet.update {
                        it.copy(hostRestart = null, notice = Quip("$node is back.", ++quipSeq))
                    }
                    com.pdm.app.data.ops.RestartPhase.REJECTED -> _fleet.update {
                        it.copy(hostRestart = null, error = "$node didn't restart: ${result.reason.orEmpty()}")
                    }
                    else -> Unit
                }
            } finally {
                progressJob.cancel()
                if (restartRecovery === recovery) restartRecovery = null
            }
        }
    }

    /**
     * The reload after the machine came back. Fresh only if every read answered live and the
     * host's own status was read in this load; anything less keeps the screen read-only.
     */
    private suspend fun recoveryLoad(node: String): com.pdm.app.data.ops.RecoveryLoad {
        refreshJob?.cancel()
        syncLog("$node back after restart · full", NoteKind.TRIGGER)
        return try {
            val outcome = loadFleetWithReauth(LoadDepth.FULL)
            when {
                !outcome.live -> com.pdm.app.data.ops.RecoveryLoad.Stale("Some answers came from saved data.")
                !outcome.hostFresh -> com.pdm.app.data.ops.RecoveryLoad.Stale("$node's status didn't load.")
                else -> com.pdm.app.data.ops.RecoveryLoad.Fresh
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e.isTlsFailure()) return com.pdm.app.data.ops.RecoveryLoad.Stale("The certificate changed. Review it to continue.")
            if (e.isAuthFailure()) return com.pdm.app.data.ops.RecoveryLoad.Stale("Sign in again to finish reconnecting.")
            com.pdm.app.data.ops.RecoveryLoad.Stale(e.rootMessage())
        }
    }

    fun dismissThemeQuip(id: Long) {
        _fleet.update { if (it.themeQuip?.id == id) it.copy(themeQuip = null) else it }
    }

    private var lastQuip: String? = null
    private var quipSeq = 0L

    fun setWatermarkTone(tone: WatermarkTone) {
        uiPrefs.watermarkTone = tone
        _fleet.update { it.copy(watermarkTone = tone) }
    }

    fun setMotionStyle(style: MotionStyle) {
        uiPrefs.motionStyle = style
        _fleet.update { it.copy(motionStyle = style) }
    }

    fun setWatermarkStrength(strength: WatermarkStrength) {
        if (strength == WatermarkStrength.OFF) {
            val current = uiPrefs.watermarkStrength
            if (current != WatermarkStrength.OFF) uiPrefs.watermarkStrengthLatent = current
        } else {
            uiPrefs.watermarkStrengthLatent = strength
        }
        uiPrefs.watermarkStrength = strength
        _fleet.update { it.copy(watermarkStrength = strength) }
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        setWatermarkMode(if (enabled) uiPrefs.watermarkModeLatent else WatermarkMode.OFF)
    }

    fun setStrengthEnabled(enabled: Boolean) {
        setWatermarkStrength(if (enabled) uiPrefs.watermarkStrengthLatent else WatermarkStrength.OFF)
    }

    fun selectConnection(id: String) {
        if (_login.value.loading) return
        val form = app.sessionStore.selectConnection(id) ?: return
        val (user, realm) = PdmAuth.splitUserRealm(form.username)
        pendingTfaIdentity = null
        pendingTfaTicket = null
        _login.value = LoginFormState(host = form.host, port = form.port.toString(), username = user,
            realm = realm, authMode = form.authMode,
            remember = form.savedSecret.isNotBlank(), secretPrefillMasked = form.savedSecret.isNotBlank(),
            autoConnect = form.autoConnect, connections = savedConnections())
    }

    /** Empty form. Auto-connect is only rewritten when a login succeeds. */
    fun newConnection() {
        if (_login.value.loading) return
        pendingTfaIdentity = null
        pendingTfaTicket = null
        _login.value = LoginFormState(connections = savedConnections())
    }

    /** Removing a saved login also releases its offline data. */
    fun removeConnection(id: String) {
        if (_login.value.loading) return
        app.sessionStore.connection(id)?.takeIf { it.savedSecret.isNotBlank() }?.let { cache.release(loginKeyOf(it)) }
        app.sessionStore.removeConnection(id)
        newConnection()
    }

    /** Open a saved login's cached fleet without signing in; going live is tried right away. */
    fun openOffline(id: String) {
        if (_login.value.loading) return
        val saved = app.sessionStore.connection(id)?.takeIf { it.savedSecret.isNotBlank() } ?: return
        viewModelScope.launch {
            _login.update { it.copy(loading = true, error = null) }
            val opened = enterOffline(saved, failed = false)
            _login.update { it.copy(loading = false, error = if (opened) null else "No offline data for this login") }
        }
    }

    /**
     * Show [saved]'s cached fleet in a cache-only session. [failed]: a sign-in just failed to reach
     * the server, so the chip starts red and the next try waits for the backoff.
     */
    private suspend fun enterOffline(saved: LastLoginForm, failed: Boolean): Boolean {
        syncLog(
            if (failed) "Server unreachable at sign-in: showing saved data" else "Showing saved data while going live",
            tone = NoteTone.MUTED,
        )
        if (!uiPrefs.offlineCache || saved.savedSecret.isBlank()) return false
        val config = app.sessionStore.configFor(saved)
        if (cache.stats(ResponseCache.loginKey(SessionIdentity.of(config))).newestAt == null) return false
        dropSession()
        app.sessionStore.setSession(
            SessionState(config = config.withoutEphemeralSecrets(), username = saved.username, cachedOnly = true),
        )
        return try {
            syncLog("Opened · saved data", NoteKind.TRIGGER)
            loadFleet(demo = false)
            if (failed) _fleet.update { it.copy(sync = it.sync.copy(failures = 1, lastAttemptAt = now())) }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            dropSession()
            false
        }
    }

    fun closeSettings() {
        _fleet.update { it.copy(showSettings = false) }
    }

    /** This session's login forgets its secret and its offline data; the session stays. */
    fun forgetSavedSecret() {
        val identity = app.sessionStore.session.value?.config?.let(SessionIdentity::of) ?: return
        app.sessionStore.clearSavedSecret(identity)
        cache.release(ResponseCache.loginKey(identity))
        syncCacheWrites()
        _login.update { it.copy(secretPrefillMasked = false, autoConnect = false, remember = false, connections = savedConnections()) }
        _fleet.update { it.copy(secretSaved = false, sessionInfo = sessionDetails()) }
        openSettings()
    }

    fun forgetCertPin(host: String) {
        app.sessionStore.removeCertPin(host)
        _fleet.update { it.copy(certPins = app.sessionStore.listCertPins()) }
    }

    fun cleanSlate() {
        app.surfaces.reset()
        // Android clears all app-owned preferences, vault/keys, files, caches and databases,
        // and stops this process so no in-flight writer can recreate old state.
        val manager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (!manager.clearApplicationUserData()) {
            _fleet.update { it.copy(error = "Clean Slate could not start. Please try again.") }
        }
    }

    fun demo() {
        if (!_login.value.demoUnlocked || _login.value.loading) return
        _login.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val outcome = app.authRepository.login(
                    config = ServerConfig(host = "demo", username = "root@pdm"),
                    allowDemo = true,
                )
                if (outcome !is LoginOutcome.Success) {
                    val message = (outcome as? LoginOutcome.Failed)?.error?.rootMessage() ?: "Demo sign-in failed"
                    _login.update { it.copy(loading = false, error = message) }
                    return@launch
                }
                loadFleet(demo = true)
                clearPark()
                _login.update { it.copy(loading = false, tfaRequired = false, otp = "", jumpBackLabel = null, jumpBackNote = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                dropSession()
                _login.update { it.copy(loading = false, error = e.rootMessage()) }
            }
        }
    }

    fun connect() {
        if (_login.value.loading) return
        val form = _login.value
        if (form.host.isBlank() || PdmAuth.isDemoHost(form.host)) {
            _login.update { it.copy(error = "Host required") }
            return
        }
        val typedSecret = when (form.authMode) {
            AuthMode.PASSWORD -> form.password
            AuthMode.API_TOKEN -> form.apiToken
        }
        val port = form.port.toIntOrNull() ?: PdmAuth.DEFAULT_PORT
        // The secret saved for exactly this host, port, account and mode; not just the last login's.
        val saved = savedFormFor(form) ?: LastLoginForm()
        val savedSecretUsable = form.secretPrefillMasked && saved.savedSecret.isNotBlank()
        if (typedSecret.isBlank() && !savedSecretUsable) {
            _login.update {
                it.copy(
                    error = buildString {
                        append(if (form.authMode == AuthMode.API_TOKEN) "API token required" else "Password required")
                        if (form.secretPrefillMasked) append(" (the saved one is for a different host, user, or realm)")
                    },
                )
            }
            return
        }
        val config = PdmAuth.configFromForm(
            hostRaw = form.host,
            username = form.username,
            secret = typedSecret,
            last = saved,
            authMode = form.authMode,
            realm = form.realm,
            port = port,
        )
        val pendingTicket = pendingTfaTicket
        val pendingIdentity = pendingTfaIdentity
        if (pendingTicket != null && pendingIdentity != null && pendingIdentity != SessionIdentity.of(config)) {
            pendingTfaIdentity = null
            pendingTfaTicket = null
            _login.update { it.copy(tfaRequired = false, otp = "", error = "The sign-in target changed. Enter the password again.") }
            return
        }
        _login.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val answer = form.otp.takeIf { form.tfaRequired && it.isNotBlank() }
                    ?.let { com.pdm.app.data.api.TfaChallenge.response(form.tfaKind, it) }
                val outcome = app.authRepository.login(
                    config = config,
                    saveSecret = form.remember,
                    tfaAnswer = answer,
                    tfaChallenge = pendingTfaTicket,
                )
                onLoginOutcome(outcome, form, config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _login.update { it.copy(loading = false, error = e.rootMessage()) }
            }
        }
    }

    /** What a sign-in (or a sign-in after a confirmed certificate) leads to. */
    private suspend fun onLoginOutcome(outcome: LoginOutcome, form: LoginFormState, config: ServerConfig) {
        if (outcome !is LoginOutcome.Success && !loginFormMatches(config)) {
            _login.update { it.copy(loading = false) }
            return
        }
        when (outcome) {
            is LoginOutcome.Success -> {
                pendingTfaIdentity = null
                pendingTfaTicket = null
                try {
                    val pin = app.sessionStore.getEndpointPin(config.host, config.port)
                    app.sessionStore.saveAutoConnect(form.autoConnect)
                    // Not remembered: no offline copy may outlive this session.
                    if (!form.remember) cache.release(ResponseCache.loginKey(SessionIdentity.of(config)))
                    syncCacheWrites()
                    syncLog("Sign-in · full", NoteKind.TRIGGER)
                    loadFleet(demo = false, fingerprint = pin)
                    clearPark()
                    _login.update {
                        it.copy(
                            loading = false,
                            tfaRequired = false,
                            otp = "",
                            password = "",
                            apiToken = "",
                            secretPrefillMasked = form.remember,
                            fingerprint = pin,
                            jumpBackLabel = null,
                            jumpBackNote = null,
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    dropSession()
                    _login.update { it.copy(loading = false, error = e.rootMessage()) }
                }
            }
            is LoginOutcome.NeedsTfa -> {
                // A second NeedsTfa after an answer means the code or key was wrong; PDM issues a
                // fresh challenge, so the person just tries again.
                val retry = form.tfaRequired && form.otp.isNotBlank()
                pendingTfaIdentity = SessionIdentity.of(outcome.config)
                pendingTfaTicket = outcome.partialTicket
                val methods = outcome.methods
                _login.update {
                    it.copy(
                        loading = false,
                        tfaRequired = true,
                        tfaMethods = methods,
                        tfaKind = it.tfaKind.takeIf { k -> k in methods.usable } ?: methods.usable.firstOrNull()
                            ?: com.pdm.app.data.api.TfaKind.TOTP,
                        otp = "",
                        error = when {
                            methods.onlyHardwareKeys -> null
                            retry -> "That code or key was not accepted. Try again."
                            else -> null
                        },
                    )
                }
            }
            is LoginOutcome.NeedsCertConfirmation -> {
                pendingCert = outcome
                _login.update {
                    it.copy(
                        loading = false,
                        pendingCert = CertQuestion(
                            host = outcome.config.displayHost,
                            fingerprint = outcome.fingerprint,
                            authorityChange = isCaPin(outcome.fingerprint),
                            knownServer = outcome.knownServer,
                        ),
                    )
                }
            }
            is LoginOutcome.Failed -> {
                // Server unreachable but this login has offline data: show that instead.
                val offline = outcome.error.isNetworkFailure() && form.remember &&
                    savedFormFor(form)?.let { enterOffline(it, failed = true) } == true
                _login.update {
                    it.copy(loading = false, error = if (offline) null else outcome.error.rootMessage())
                }
            }
        }
    }

    /** The form still names the same host, account and mode the sign-in was sent for. */
    private fun loginFormMatches(config: ServerConfig): Boolean {
        val form = _login.value
        val secret = when (form.authMode) {
            AuthMode.PASSWORD -> form.password
            AuthMode.API_TOKEN -> form.apiToken
        }
        val current = PdmAuth.configFromForm(
            hostRaw = form.host,
            username = form.username,
            secret = secret,
            last = savedFormFor(form),
            authMode = form.authMode,
            realm = form.realm,
            port = form.port.toIntOrNull() ?: PdmAuth.DEFAULT_PORT,
        )
        return SessionIdentity.of(config) == SessionIdentity.of(current)
    }

    /** The user compared the fingerprint and trusts it: pin it, then sign in. */
    fun acceptCertificate() {
        if (_login.value.loading) return
        val pending = pendingCert ?: return
        pendingCert = null
        val form = _login.value
        _login.update { it.copy(pendingCert = null, loading = true, error = null) }
        viewModelScope.launch {
            try {
                onLoginOutcome(app.authRepository.confirmCertificate(pending), form, pending.config)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _login.update { it.copy(loading = false, error = e.rootMessage()) }
            }
        }
    }

    fun rejectCertificate() {
        pendingCert?.let(app.authRepository::rejectCertificate)
        pendingCert = null
        _login.update {
            it.copy(
                pendingCert = null,
                loading = false,
                error = "Certificate not trusted. Nothing was sent to the server.",
            )
        }
    }

    /** Leave the second-factor step and show the credential fields again. */
    fun cancelTfa() {
        pendingTfaIdentity = null
        pendingTfaTicket = null
        _login.update { it.copy(tfaRequired = false, otp = "", tfaMethods = null, error = null) }
    }

    fun exit() {
        val live = app.sessionStore.session.value
        // Ask the vault: the screen flag is only refreshed when Settings opens.
        val secretSaved = live != null && !_fleet.value.demo &&
            app.sessionStore.savedFormFor(SessionIdentity.of(live.config)) != null
        val newlyParked = _fleet.value.signedIn && live != null
        if (newlyParked) {
            parkedSession = live
            parkedDemo = _fleet.value.demo
        }
        val label = parkedSession?.let { jumpBackCaption(it, parkedDemo) }
        val note = if (!newlyParked) {
            _login.value.jumpBackNote
        } else if (label == null) {
            null
        } else {
            jumpBackNote(secretSaved)
        }
        dropSession()
        _login.update {
            it.copy(
                connections = savedConnections(),
                password = "",
                apiToken = "",
                loading = false,
                tfaRequired = false,
                otp = "",
                error = null,
                jumpBackLabel = label,
                jumpBackNote = note,
            )
        }
    }

    /** Restore the session parked by [exit]. A failed return keeps the park. */
    fun jumpBack() {
        val parked = parkedSession ?: return
        if (_login.value.loading) return
        val demo = parkedDemo
        viewModelScope.launch {
            _login.update { it.copy(loading = true, error = null) }
            try {
                app.sessionStore.setSession(parked)
                syncCacheWrites()
                syncLog("Jump back · full", NoteKind.TRIGGER)
                loadFleet(demo = demo)
                if (!_fleet.value.signedIn) {
                    dropSession()
                    _login.update { it.copy(loading = false, error = "Could not return to the previous session") }
                    return@launch
                }
                clearPark()
                _login.update {
                    it.copy(loading = false, tfaRequired = false, otp = "", error = null, jumpBackLabel = null, jumpBackNote = null)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                dropSession()
                _login.update { it.copy(loading = false, error = e.rootMessage()) }
            }
        }
    }

    private fun clearPark() {
        parkedSession = null
        parkedDemo = false
    }

    fun startNetworkScan() {
        if (_scan.value.isScanning) return
        val epoch = ++scanEpoch
        scanJob?.cancel()
        val demoSim = PdmAuth.isDemoHost(_login.value.host)
        scanJob = viewModelScope.launch {
            _scan.value = NetworkScanState(
                isScanning = true,
                totalCount = if (demoSim) 254 else 0,
            )
            if (demoSim) {
                val fake = listOf(
                    DiscoveredHost(ip = "192.0.2.10", port = 8443, version = "1.0", latencyMs = 9L, tls = "TLSv1.3"),
                    DiscoveredHost(ip = "192.0.2.11", port = 8443, version = "1.0", latencyMs = 14L, tls = "TLSv1.3"),
                )
                fun step(kind: String, target: String, text: String, tone: ScanTone = ScanTone.PLAIN) =
                    ScanStep(System.currentTimeMillis(), kind, target, text, tone)
                try {
                    _scan.update {
                        it.copy(
                            networks = listOf("192.0.2.0/24"),
                            log = listOf(step("net", "192.0.2.0/24", "demo · 254 addresses · port 8443")),
                        )
                    }
                    delay(400)
                    if (epoch != scanEpoch) return@launch
                    _scan.update {
                        it.copy(
                            scannedCount = 80,
                            discoveredHosts = fake.take(1),
                            isDemoSim = true,
                            openCount = 1,
                            log = it.log + listOf(
                                step("tcp", "192.0.2.10", "8443 open"),
                                step("tls", "192.0.2.10", "TLS 1.3 · not pinned"),
                                step("pdm", "192.0.2.10", "verified · 9 ms", ScanTone.GOOD),
                            ),
                        )
                    }
                    delay(500)
                    if (epoch != scanEpoch) return@launch
                    _scan.update {
                        it.copy(
                            scannedCount = 254,
                            discoveredHosts = fake,
                            isScanning = false,
                            isComplete = true,
                            isDemoSim = true,
                            openCount = 2,
                            elapsedMs = 900,
                            log = it.log + listOf(
                                step("tcp", "192.0.2.11", "8443 open"),
                                step("tls", "192.0.2.11", "TLS 1.3 · not pinned"),
                                step("pdm", "192.0.2.11", "verified · 14 ms", ScanTone.GOOD),
                                step("done", "254 checked", "0.9 s · 2 open · 2 PDM"),
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    if (epoch == scanEpoch) {
                        _scan.update { it.copy(isScanning = false) }
                    }
                    throw e
                }
                return@launch
            }
            try {
                app.localNet.scanSubnet(null).collect { progress ->
                    if (epoch != scanEpoch) return@collect
                    _scan.update {
                        it.copy(
                            isScanning = !progress.isFinished,
                            scannedCount = progress.scanned,
                            totalCount = progress.total,
                            discoveredHosts = progress.verified,
                            pdmDetectedHosts = progress.pdmDetected,
                            unverifiedHosts = progress.unverified,
                            error = progress.error,
                            infoMessage = progress.infoMessage,
                            isComplete = progress.isFinished,
                            isDemoSim = false,
                            log = progress.log,
                            openCount = progress.openCount,
                            networks = progress.networks,
                            elapsedMs = progress.elapsedMs,
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (epoch == scanEpoch) {
                    _scan.update { it.copy(isScanning = false) }
                }
                throw e
            } catch (e: Exception) {
                if (epoch == scanEpoch) {
                    _scan.update {
                        it.copy(
                            isScanning = false,
                            isComplete = true,
                            error = e.rootMessage(),
                        )
                    }
                }
            }
        }
    }

    fun stopNetworkScan() {
        scanEpoch++
        scanJob?.cancel()
        scanJob = null
        _scan.update { it.copy(isScanning = false) }
    }

    fun clearScanResults() {
        stopNetworkScan()
        _scan.value = NetworkScanState()
    }

    fun onCandidateSelected(host: DiscoveredHost) {
        if (_login.value.loading) return
        updateLogin { it.copy(host = host.ip, port = host.port.toString()) }
    }

    fun onUnverifiedSelected(ip: String) {
        if (_login.value.loading) return
        updateLogin { it.copy(host = ip, port = PdmAuth.DEFAULT_PORT.toString()) }
    }

    override fun onCleared() {
        if (app.surfaces.commands === surfaceCommands) app.surfaces.commands = null
        // Nobody keeps this data fresh any more: the tile and widget stop saying LIVE.
        app.surfaces.publish(glanceOf(_fleet.value, onScreen = false))
        endShell()
        scanEpoch++
        scanJob?.cancel()
        runCatching { connectivity?.unregisterNetworkCallback(networkBack) }
        super.onCleared()
    }

    /**
     * Tap a remote: scope the dashboard to it and open its detail on the right.
     * Tap the same remote again (or its header chip): clear the scope and go back to the guest list.
     */
    fun selectRemote(id: String?) {
        val clearing = id == null || _fleet.value.selectedRemoteId == id
        _fleet.update { current ->
            current.copy(
                selectedRemoteId = if (clearing) null else id,
                nav = if (clearing || id == null) DashNav() else DashNav().open(DashFocus.RemoteDetail(id)),
            )
        }
        onFocusChanged()
    }

    fun filterRemote(id: String) {
        _fleet.update { it.copy(selectedRemoteId = id) }
    }

    fun openFocus(focus: DashFocus) {
        _fleet.update { it.copy(nav = it.nav.open(focus)) }
        onFocusChanged()
    }

    fun popFocus() {
        // Backing out of the PDM screen with unsaved notes asks first; the draft stays otherwise.
        if (notesBlockLeaving()) return
        _fleet.update { it.copy(nav = it.nav.pop()) }
        onFocusChanged()
    }

    /** Open a task from any list; it need not be in the dashboard's recent tasks. */
    fun openTask(task: RemoteTask) {
        if (_fleet.value.taskView?.task?.upid != task.upid) _fleet.update { it.copy(taskView = TaskView(task)) }
        openFocus(DashFocus.TaskDetail(task.upid))
    }

    fun requestStopTask(task: RemoteTask) {
        if (!requireLive()) return
        _fleet.update { it.copy(pendingTaskStop = task) }
    }

    fun cancelStopTask() {
        _fleet.update { it.copy(pendingTaskStop = null) }
    }

    fun confirmStopTask() {
        if (!requireLive()) return
        val task = _fleet.value.pendingTaskStop ?: return
        _fleet.update { state ->
            state.copy(
                pendingTaskStop = null,
                taskView = state.taskView?.takeIf { it.task.upid == task.upid }?.copy(stopping = true, stopError = null),
            )
        }
        viewModelScope.launch {
            try {
                app.fleetRepository.stopTask(task)
                watchTask(task)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.isAuthFailure() || e.isSessionChanged()) {
                    onFleetFailure(e)
                    return@launch
                }
                updateTaskView(task.upid) { it.copy(stopping = false, stopError = e.rootMessage()) }
            }
        }
    }

    /** Ask PDM to re-poll every remote, then re-read the summary once the worker had time to run. */
    fun refreshUpdates() {
        if (_fleet.value.updatesRefreshing || !requireLive()) return
        _fleet.update { it.copy(updatesRefreshing = true) }
        viewModelScope.launch {
            try {
                app.fleetRepository.refreshUpdates()
                delay(UPDATES_SETTLE_MS)
                refresh(RefreshReason.ACTION, full = true)?.join()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onFleetFailure(e)
            } finally {
                _fleet.update { it.copy(updatesRefreshing = false) }
            }
        }
    }

    fun togglePackage(name: String) {
        val view = _fleet.value.nodeUpdatesView ?: return
        val session = app.sessionStore.snapshot() ?: return
        if (view.openPackage == name) {
            _fleet.update { state -> state.copy(nodeUpdatesView = state.nodeUpdatesView?.copy(openPackage = null, changelog = null)) }
            return
        }
        _fleet.update { state ->
            state.copy(nodeUpdatesView = state.nodeUpdatesView?.copy(openPackage = name, changelog = null))
        }
        viewModelScope.launch {
            val text = try {
                app.fleetRepository.changelog(view.remoteId, view.node, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Our own messages already say what failed; only wrap raw transport errors.
                if (e is com.pdm.app.data.repo.PdmException) e.rootMessage() else "Changelog did not load: ${e.rootMessage()}"
            }
            _fleet.update { state ->
                if (session != null && !app.sessionStore.isCurrent(session)) return@update state
                val current = state.nodeUpdatesView
                if (current?.openPackage != name || current.remoteId != view.remoteId || current.node != view.node) state else state.copy(nodeUpdatesView = current.copy(changelog = text))
            }
        }
    }

    /** Open the migrate dialog; a node known to be in HA maintenance or a fault state is never preselected. */
    fun openMigrate(guest: Guest) {
        if (!requireLive()) return
        val state = _fleet.value
        val choice = com.pdm.app.data.ops.MigrationSafety.inCluster(state.nodes, guest.remoteId, guest.node)
        val remotes = state.remotes
            .filter { it.kind == "pve" && it.id != guest.remoteId && it.error.isNullOrBlank() }
            .map { it.id }
        if (choice.nodes.isEmpty() && remotes.isEmpty()) return
        val cross = choice.nodes.isEmpty()
        _fleet.update {
            it.copy(
                migrateView = MigrateView(
                    guestId = guest.id,
                    request = MigrateRequest(crossRemote = cross, targetNode = null),
                    nodes = choice.nodes,
                    remotes = remotes,
                    warning = choice.note,
                ),
            )
        }
        if (cross) {
            updateMigrate { it.copy(targetRemote = remotes.first()) }
        } else if (choice.preselected != null) {
            updateMigrate { it.copy(targetNode = choice.preselected) }
        }
    }

    /** The person read the HA concern for the chosen node and wants it anyway. */
    fun confirmMigrateHa(confirmed: Boolean) {
        updateMigrateView { it.copy(haConfirmed = confirmed && it.haConcern != null) }
    }

    fun closeMigrate() {
        _fleet.update { it.copy(migrateView = null) }
    }

    /** Apply a form change; a new target node re-runs PVE's preflight, a new remote reloads storages/bridges. */
    fun updateMigrate(transform: (MigrateRequest) -> MigrateRequest) {
        val view = _fleet.value.migrateView ?: return
        val guest = _fleet.value.guests.firstOrNull { it.id == view.guestId } ?: return
        val before = view.request
        val after = transform(before)
        // In-cluster targets are nodes of the guest's own remote; a cross-remote move picks no node.
        val concern = if (after.crossRemote) null else com.pdm.app.data.ops.MigrationSafety.concern(
            _fleet.value.nodes.firstOrNull { it.remoteId == guest.remoteId && it.name == after.targetNode },
        )
        val sameTarget = after.crossRemote == before.crossRemote && after.targetNode == before.targetNode
        _fleet.update {
            it.copy(
                migrateView = view.copy(
                    request = after,
                    error = null,
                    haConcern = concern,
                    haConfirmed = view.haConfirmed && sameTarget && concern == view.haConcern,
                ),
            )
        }
        if (!after.crossRemote && after.targetNode != null &&
            (after.targetNode != before.targetNode || after.crossRemote != before.crossRemote || view.check == null)
        ) {
            runPreflight(guest, after.targetNode)
        }
        if (after.crossRemote && after.targetRemote != null &&
            (after.targetRemote != before.targetRemote || after.crossRemote != before.crossRemote || view.targets == null)
        ) {
            loadMigrateTargets(guest, after.targetRemote)
        }
    }

    private fun runPreflight(guest: Guest, target: String) {
        updateMigrateView { it.copy(checking = true, check = null) }
        viewModelScope.launch {
            try {
                val pre = app.fleetRepository.migratePreflight(guest, target)
                val check = pre?.check(target) ?: MigrateCheck(emptyList(), emptyList())
                updateMigrateView { v ->
                    if (v.request.targetNode != target) {
                        v
                    } else {
                        v.copy(
                            checking = false,
                            check = check,
                            // Local disks are moved with the VM when PVE says it has any.
                            request = v.request.copy(withLocalDisks = check.needsLocalDisks),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateMigrateView { it.copy(checking = false, error = "Preflight failed: ${e.rootMessage()}") }
            }
        }
    }

    private fun loadMigrateTargets(guest: Guest, remote: String) {
        val node = com.pdm.app.data.ops.MigrationSafety.queryNode(_fleet.value.nodes, remote)?.name
        if (node == null) {
            updateMigrateView { it.copy(error = "$remote has no online node") }
            return
        }
        updateMigrateView { it.copy(targetsLoading = true, targets = null) }
        viewModelScope.launch {
            try {
                val targets = app.fleetRepository.migrateTargets(guest, remote, node)
                updateMigrateView { v ->
                    if (v.request.targetRemote != remote) {
                        v
                    } else {
                        v.copy(
                            targetsLoading = false,
                            targets = targets,
                            request = v.request.copy(
                                targetStorage = targets.storages.firstOrNull(),
                                targetBridge = targets.bridges.firstOrNull { it.startsWith("vmbr") } ?: targets.bridges.firstOrNull(),
                                targetVmid = targets.nextVmid,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateMigrateView { it.copy(targetsLoading = false, error = "Targets did not load: ${e.rootMessage()}") }
            }
        }
    }

    fun startMigrate() {
        if (!requireLive()) return
        val view = _fleet.value.migrateView ?: return
        if (!view.canStart) return
        val guest = _fleet.value.guests.firstOrNull { it.id == view.guestId } ?: return
        if (!view.request.crossRemote && view.request.targetNode.isNullOrBlank()) {
            updateMigrateView { it.copy(starting = false, error = "Pick a destination node.") }
            return
        }
        if (!view.request.crossRemote) {
            // The node's HA state may have moved since it was chosen: check it against the latest refresh.
            val now = com.pdm.app.data.ops.MigrationSafety.concern(
                _fleet.value.nodes.firstOrNull { it.remoteId == guest.remoteId && it.name == view.request.targetNode },
            )
            if (now != view.haConcern) {
                updateMigrateView { it.copy(haConcern = now, haConfirmed = false, starting = false) }
                if (now != null) return
            }
        }
        if (tracker.mayBeRunning(guest.remoteId, guest.vmid.toLong(), OperationKind.MIGRATE) ||
            _fleet.value.busyIds.contains(guest.id)
        ) {
            updateMigrateView {
                it.copy(starting = false, error = "An earlier action on ${guest.name} hasn't finished. See Operations under Tasks.")
            }
            return
        }
        updateMigrateView { it.copy(starting = true, error = null) }
        val session = app.sessionStore.session.value
        if (session == null) {
            updateMigrateView { it.copy(starting = false, error = "Not signed in") }
            return
        }
        val receipt = tracker.begin(
            kind = OperationKind.MIGRATE,
            origin = TaskOrigin.Remote(guestRemoteKind(guest), guest.remoteId),
            description = "Migrate ${guest.name} to ${if (view.request.crossRemote) view.request.targetRemote else view.request.targetNode}",
            port = session.config.port,
            remoteId = guest.remoteId,
            node = guest.node,
            vmid = guest.vmid.toLong(),
            guestType = guest.type,
        )
        if (receipt == null) {
            updateMigrateView { it.copy(starting = false) }
            return
        }
        runOperation(
            receipt,
            request = { app.fleetRepository.startMigration(guest, view.request) },
            onAccepted = { upid ->
                _fleet.update { it.copy(migrateView = null) }
                tracker.receipts().firstOrNull { it.id == receipt.id }?.let { r ->
                    OperationTracker.taskOf(r)?.let { openTaskDirect(it.copy(workerType = if (guest.type == "lxc") "vzmigrate" else "qmigrate")) }
                }
                if (upid == null) _fleet.update { it.copy(notice = Quip("Migration of ${guest.name} accepted.", ++quipSeq)) }
            },
            onRejected = { reason -> updateMigrateView { it.copy(starting = false, error = reason) } },
            onUnknown = {
                updateMigrateView {
                    it.copy(starting = false, error = "No answer from the server. The migration may have started; see Operations under Tasks before trying again.")
                }
            },
        )
    }

    private fun updateMigrateView(transform: (MigrateView) -> MigrateView) {
        _fleet.update { s -> s.migrateView?.let { s.copy(migrateView = transform(it)) } ?: s }
    }

    /** Follow a task that is not in the cached task list yet (one the app just started). */
    private fun openTaskDirect(task: RemoteTask) {
        _fleet.update { it.copy(taskView = TaskView(task)) }
        openFocus(DashFocus.TaskDetail(task.upid))
    }

    fun setGraphTimeframe(timeframe: RrdTimeframe) {
        val key = _fleet.value.graphView?.key ?: return
        loadGraph(key, timeframe)
    }

    private var graphJob: Job? = null

    private fun loadGraph(key: String, timeframe: RrdTimeframe? = null) {
        val tf = timeframe ?: _fleet.value.graphView?.timeframe ?: RrdTimeframe.HOUR
        val state = _fleet.value
        val guest = key.removePrefix("guest:").takeIf { key.startsWith("guest:") }
            ?.let { id -> state.guests.firstOrNull { it.id == id } }
        val node = key.removePrefix("node:").takeIf { key.startsWith("node:") }
            ?.let { id -> state.nodes.firstOrNull { it.id == id } }
        if (guest == null && node == null) return
        _fleet.update { s ->
            // Same graph, new timeframe: keep its points until the new ones replace them.
            val keep = s.graphView?.takeIf { it.key == key }
            s.copy(graphView = keep?.copy(timeframe = tf, loading = true, error = null) ?: GraphView(key, tf))
        }
        graphJob?.cancel()
        graphJob = viewModelScope.launch {
            try {
                val points = if (guest != null) {
                    app.fleetRepository.guestRrd(guest, tf)
                } else {
                    app.fleetRepository.nodeRrd(node!!, tf)
                }
                _fleet.update { s ->
                    val view = s.graphView?.takeIf { it.key == key && it.timeframe == tf } ?: return@update s
                    s.copy(graphView = view.copy(points = points, loading = false, error = null))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.isAuthFailure() || e.isSessionChanged()) {
                    onFleetFailure(e)
                    return@launch
                }
                _fleet.update { s ->
                    val view = s.graphView?.takeIf { it.key == key && it.timeframe == tf } ?: return@update s
                    s.copy(graphView = view.copy(loading = false, error = e.rootMessage()))
                }
            }
        }
    }

    private fun loadNodeInventory(nodeId: String) {
        val node = _fleet.value.nodes.firstOrNull { it.id == nodeId } ?: return
        viewModelScope.launch {
            try {
                val inventory = app.fleetRepository.nodeInventory(node)
                _fleet.update { it.copy(nodeInventory = nodeId to inventory) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.isAuthFailure() || e.isTlsFailure() || e.isSessionChanged()) {
                    onFleetFailure(e)
                    return@launch
                }
                _fleet.update {
                    it.copy(nodeInventory = nodeId to NodeInventory(storages = null, interfaces = null, error = e.rootMessage()))
                }
            }
        }
    }

    private fun loadRemoteView(remoteId: String) {
        val remote = _fleet.value.remotes.firstOrNull { it.id == remoteId } ?: return
        _fleet.update { state ->
            val keep = state.remoteView?.takeIf { it.remoteId == remoteId }
            state.copy(remoteView = keep?.copy(loading = true) ?: RemoteView(remoteId))
        }
        viewModelScope.launch {
            try {
                val detail = app.fleetRepository.remoteDetail(remote)
                _fleet.update { state ->
                    val view = state.remoteView?.takeIf { it.remoteId == remoteId } ?: return@update state
                    state.copy(remoteView = view.copy(detail = detail, loading = false, error = null))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.isAuthFailure() || e.isTlsFailure() || e.isSessionChanged()) {
                    _fleet.update { state ->
                        val view = state.remoteView?.takeIf { it.remoteId == remoteId } ?: return@update state
                        state.copy(remoteView = view.copy(loading = false))
                    }
                    onFleetFailure(e)
                    return@launch
                }
                _fleet.update { state ->
                    val view = state.remoteView?.takeIf { it.remoteId == remoteId } ?: return@update state
                    state.copy(remoteView = view.copy(loading = false, error = e.rootMessage()))
                }
            }
        }
    }

    /**
     * Run `apt update` on the focused node, re-read its package list, then re-poll PDM's summary
     * so the fleet totals catch up.
     */
    fun checkNodeUpdates() {
        val focus = _fleet.value.nav.focus as? DashFocus.NodeUpdates ?: return
        val view = _fleet.value.nodeUpdatesView?.takeIf { it.remoteId == focus.remoteId && it.node == focus.node } ?: return
        if (view.checking || !requireLive()) return
        val session = app.sessionStore.snapshot() ?: return
        fun patch(transform: (NodeUpdatesView) -> NodeUpdatesView) = _fleet.update { state ->
            val v = state.nodeUpdatesView?.takeIf { it.remoteId == focus.remoteId && it.node == focus.node }
                ?: return@update state
            state.copy(nodeUpdatesView = transform(v))
        }
        patch { it.copy(checking = true, checkError = null) }
        viewModelScope.launch {
            try {
                app.fleetRepository.checkNodeUpdates(focus.remoteId, focus.node)
                if (!app.sessionStore.isCurrent(session)) return@launch
                patch { it.copy(checking = false) }
                loadNodeUpdates(focus.remoteId, focus.node)
                refreshUpdates()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!app.sessionStore.isCurrent(session)) return@launch
                if (e.isAuthFailure() || e.isSessionChanged()) {
                    onFleetFailure(e)
                    return@launch
                }
                patch { it.copy(checking = false, checkError = e.rootMessage()) }
            }
        }
    }

    /** Upgrade on a remote node: a root shell through PDM with `apt full-upgrade` typed, not run. */
    fun requestNodeUpgrade(remoteId: String, node: String) {
        if (!requireLive()) return
        val saved = currentIdentity()?.let { app.sessionStore.nodePassword(it, remoteId, node) } != null
        _fleet.update {
            it.copy(
                pendingShell = PendingShell(
                    ShellTarget.RemoteNode(remoteId, node),
                    UPGRADE_COMMAND,
                    nodeLogin = true,
                    savedPassword = saved,
                ),
            )
        }
    }

    /**
     * Upgrade on the PDM host. root@pam gets PDM's own upgrade terminal (`apt full-upgrade`, then a
     * shell); anyone else gets a login shell with the command typed.
     */
    fun requestHostUpgrade() {
        val host = _fleet.value.host ?: return
        if (!requireLive()) return
        val root = app.sessionStore.snapshot()?.state?.username.equals("root@pam", ignoreCase = true)
        _fleet.update {
            it.copy(pendingShell = PendingShell(ShellTarget.Host(host.node, upgrade = root), UPGRADE_COMMAND.takeUnless { root }))
        }
    }

    private fun currentIdentity(): SessionIdentity? =
        app.sessionStore.snapshot()?.state?.config?.let { SessionIdentity.of(it) }

    fun forgetPendingNodePassword() {
        val node = _fleet.value.pendingShell?.target as? ShellTarget.RemoteNode ?: return
        currentIdentity()?.let { app.sessionStore.forgetNodePassword(it, node.remoteId, node.node) }
        _fleet.update { it.copy(pendingShell = it.pendingShell?.copy(savedPassword = false)) }
    }

    fun cancelShell() {
        _fleet.update { it.copy(pendingShell = null) }
    }

    /**
     * [password] is the node's root password (empty: use the remembered one, or sign in by hand).
     * [remember] keeps a typed password in the encrypted store for this node.
     */
    fun confirmShell(password: String = "", remember: Boolean = false) {
        val pending = _fleet.value.pendingShell ?: return
        _fleet.update { it.copy(pendingShell = null) }
        if (!requireLive()) return
        endShell()
        val target = pending.target
        val identity = currentIdentity()
        val node = target as? ShellTarget.RemoteNode
        val secret = when {
            node == null || identity == null -> null
            password.isNotEmpty() -> password.also {
                if (remember) app.sessionStore.saveNodePassword(identity, node.remoteId, node.node, it)
            }
            else -> app.sessionStore.nodePassword(identity, node.remoteId, node.node)
        }
        val login = secret?.let { com.pdm.app.data.shell.NodeLogin("root", it) }
        // Only a remembered password that was used (not one just typed) is forgotten on refusal.
        val usedSaved = node != null && password.isEmpty() && secret != null
        _fleet.update {
            it.copy(shell = ShellView(target, ShellStatus(ShellPhase.CONNECTING), pending.staged, login = login?.state))
        }
        com.pdm.app.data.shell.ShellKeepAliveService.start(app, target.title)
        shellJob = viewModelScope.launch {
            val opened = try {
                app.shellConnector.open(target, pending.staged, login)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                com.pdm.app.data.shell.ShellKeepAliveService.stop(app)
                if (e.isAuthFailure()) {
                    onFleetFailure(e)
                    return@launch
                }
                _fleet.update { state ->
                    state.copy(shell = state.shell?.takeIf { it.target == target }?.copy(status = ShellStatus(ShellPhase.CLOSED, e.rootMessage())))
                }
                return@launch
            }
            shellSession = opened
            launch {
                kotlinx.coroutines.flow.combine(opened.status, opened.prompt, opened.stagedTyped, opened.loginState) { status, prompt, typed, loginState ->
                    ShellProgress(status, prompt, typed, loginState)
                }.collect { p ->
                    // Nothing left to keep alive once the node ended the shell.
                    if (p.status.phase == ShellPhase.CLOSED) com.pdm.app.data.shell.ShellKeepAliveService.stop(app)
                    var forgot = false
                    if (p.login == com.pdm.app.data.shell.NodeLogin.State.REFUSED && usedSaved && node != null && identity != null) {
                        app.sessionStore.forgetNodePassword(identity, node.remoteId, node.node)
                        forgot = true
                    }
                    _fleet.update { state ->
                        state.copy(
                            shell = state.shell?.takeIf { it.target == target }?.let { v ->
                                v.copy(
                                    status = p.status,
                                    prompt = p.prompt,
                                    stagedTyped = p.typed,
                                    login = p.login,
                                    forgotSaved = v.forgotSaved || forgot,
                                )
                            },
                        )
                    }
                }
            }
            // termproxy drops a terminal that stays silent; the web UI pings every 30 s too.
            while (true) {
                delay(SHELL_PING_MS)
                if (opened.status.value.phase == ShellPhase.CLOSED) break
                opened.ping()
            }
        }
    }

    /**
     * A fresh terminal on the same machine after the last one ended (the node's sign-in timed out,
     * or the connection closed). Uses a remembered root password if there is one.
     */
    fun reopenShell() {
        val view = _fleet.value.shell ?: return
        val node = view.target as? ShellTarget.RemoteNode
        val saved = node != null &&
            currentIdentity()?.let { app.sessionStore.nodePassword(it, node.remoteId, node.node) } != null
        _fleet.update {
            it.copy(pendingShell = PendingShell(view.target, view.staged, nodeLogin = node != null, savedPassword = saved))
        }
        confirmShell()
    }

    /** The live terminal, for the view that draws it. */
    fun activeShell(): ShellSession? = shellSession

    /** Types a snippet at the prompt, one line and without Enter: the person runs it. */
    fun insertSnippet(snippet: com.pdm.app.data.shell.Snippet) {
        val view = _fleet.value.shell ?: return
        if (view.status.phase != ShellPhase.OPEN || view.prompt != com.pdm.app.data.shell.PromptKind.SHELL) return
        val line = com.pdm.app.data.shell.Snippets.typed(snippet.command)
        if (line.isNotEmpty()) shellSession?.send(line)
    }

    /** Add or replace (same [id]) a saved snippet. Returns false when there is no command. */
    fun saveSnippet(id: String?, title: String, command: String): Boolean {
        val snippet = com.pdm.app.data.shell.Snippets.saved(id ?: "mine_${System.currentTimeMillis()}", title, command)
            ?: return false
        val list = _fleet.value.savedSnippets
        val next = if (list.any { it.id == snippet.id }) list.map { if (it.id == snippet.id) snippet else it } else list + snippet
        app.sessionStore.saveSnippets(next)
        _fleet.update { it.copy(savedSnippets = next) }
        return true
    }

    fun deleteSnippet(id: String) {
        val next = _fleet.value.savedSnippets.filterNot { it.id == id }
        app.sessionStore.saveSnippets(next)
        _fleet.update { it.copy(savedSnippets = next) }
    }

    fun shellInput(data: String) {
        shellSession?.send(data)
    }

    fun shellResize(cols: Int, rows: Int) {
        shellSession?.resize(cols, rows)
    }

    /** Leave the terminal, then re-read what it may have changed. */
    fun closeShell() {
        val target = _fleet.value.shell?.target ?: return
        endShell()
        _fleet.update { it.copy(shell = null) }
        if (_fleet.value.readOnly) return
        when (target) {
            is ShellTarget.RemoteNode -> {
                val focus = _fleet.value.nav.focus
                if (focus is DashFocus.NodeUpdates && focus.remoteId == target.remoteId && focus.node == target.node) {
                    loadNodeUpdates(target.remoteId, target.node)
                }
                refreshUpdates()
            }
            is ShellTarget.Host -> refresh(RefreshReason.ACTION, full = true)
        }
    }

    private fun endShell() {
        com.pdm.app.data.shell.ShellKeepAliveService.stop(app)
        shellJob?.cancel()
        shellJob = null
        shellSession?.close()
        shellSession = null
    }

    fun retryNodeUpdates() {
        val focus = _fleet.value.nav.focus as? DashFocus.NodeUpdates ?: return
        loadNodeUpdates(focus.remoteId, focus.node)
    }

    private fun loadNodeUpdates(remoteId: String, node: String) {
        val session = app.sessionStore.snapshot() ?: return
        _fleet.update { state ->
            // A re-read of the same node keeps the list and the open changelog on screen.
            val keep = state.nodeUpdatesView?.takeIf { it.remoteId == remoteId && it.node == node }
            state.copy(nodeUpdatesView = keep?.copy(loading = true, error = null) ?: NodeUpdatesView(remoteId, node))
        }
        viewModelScope.launch {
            try {
                val packages = app.fleetRepository.nodeUpdates(remoteId, node)
                if (!app.sessionStore.isCurrent(session)) return@launch
                _fleet.update { state ->
                    val view = state.nodeUpdatesView?.takeIf { it.remoteId == remoteId && it.node == node }
                        ?: return@update state
                    state.copy(nodeUpdatesView = view.copy(packages = packages, loading = false))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!app.sessionStore.isCurrent(session)) return@launch
                if (e.isAuthFailure() || e.isSessionChanged()) {
                    onFleetFailure(e)
                    return@launch
                }
                _fleet.update { state ->
                    val view = state.nodeUpdatesView?.takeIf { it.remoteId == remoteId && it.node == node }
                        ?: return@update state
                    state.copy(nodeUpdatesView = view.copy(loading = false, error = e.rootMessage()))
                }
            }
        }
    }

    fun openSnapshotForm(guest: Guest) {
        if (!requireLive()) return
        _fleet.update { it.copy(snapshotFormFor = guest.id) }
    }

    fun closeSnapshotForm() {
        _fleet.update { it.copy(snapshotFormFor = null) }
    }

    fun createSnapshot(guest: Guest, name: String, description: String, withRam: Boolean) {
        _fleet.update { it.copy(snapshotFormFor = null) }
        startSnapshotOperation(guest, OperationKind.SNAPSHOT_CREATE, "Create snapshot $name on ${guest.name}", "Creating snapshot $name") {
            app.fleetRepository.createSnapshot(guest, name, description, withRam)
        }
    }

    /** Snapshot work goes through a receipt; the guest pane shows it busy until its task ends. */
    private fun startSnapshotOperation(
        guest: Guest,
        kind: String,
        description: String,
        busyLabel: String,
        request: suspend () -> String?,
    ) {
        if (!requireLive()) return
        if (_fleet.value.guestView?.takeIf { it.guestId == guest.id }?.busy != null) return
        if (tracker.mayBeRunning(guest.remoteId, guest.vmid.toLong())) {
            updateGuestView(guest.id) { it.copy(error = "An earlier action on ${guest.name} hasn't finished. See Operations under Tasks.") }
            return
        }
        val session = app.sessionStore.session.value ?: return
        val receipt = tracker.begin(
            kind = kind,
            origin = TaskOrigin.Remote(guestRemoteKind(guest), guest.remoteId),
            description = description,
            port = session.config.port,
            remoteId = guest.remoteId,
            node = guest.node,
            vmid = guest.vmid.toLong(),
            guestType = guest.type,
        ) ?: return
        updateGuestView(guest.id) { it.copy(busy = busyLabel, busyReceiptId = receipt.id, error = null) }
        runOperation(
            receipt,
            request = request,
            onRejected = { reason -> updateGuestView(guest.id) { it.copy(busy = null, busyReceiptId = null, error = reason) } },
            onUnknown = {
                updateGuestView(guest.id) {
                    it.copy(busy = null, busyReceiptId = null, error = "No answer from the server. It may have started; see Operations under Tasks before trying again.")
                }
            },
        )
    }

    fun requestSnapshotAction(guest: Guest, name: String, rollback: Boolean) {
        _fleet.update { it.copy(pendingSnapshot = PendingSnapshot(guest, name, rollback)) }
    }

    fun cancelSnapshotAction() {
        _fleet.update { it.copy(pendingSnapshot = null) }
    }

    fun confirmSnapshotAction() {
        val pending = _fleet.value.pendingSnapshot ?: return
        _fleet.update { it.copy(pendingSnapshot = null) }
        val guest = pending.guest
        if (pending.rollback) {
            startSnapshotOperation(guest, OperationKind.SNAPSHOT_ROLLBACK, "Roll back ${guest.name} to ${pending.name}", "Rolling back to ${pending.name}") {
                app.fleetRepository.rollbackSnapshot(guest, pending.name)
            }
        } else {
            startSnapshotOperation(guest, OperationKind.SNAPSHOT_DELETE, "Delete snapshot ${pending.name} on ${guest.name}", "Deleting ${pending.name}") {
                app.fleetRepository.deleteSnapshot(guest, pending.name)
            }
        }
    }

    /**
     * Read live status + snapshots + hardware for the guest detail. Each part fails on its own.
     * [liveOnly] (the 30 s tick) re-reads just the status and keeps the rest.
     */
    private fun loadGuestView(guestId: String, liveOnly: Boolean = false) {
        val guest = _fleet.value.guests.firstOrNull { it.id == guestId } ?: return
        _fleet.update { state ->
            val keep = state.guestView?.takeIf { it.guestId == guestId }
            state.copy(guestView = keep?.copy(loading = true) ?: GuestView(guestId))
        }
        viewModelScope.launch {
            var firstError: Exception? = null
            val live = try {
                app.fleetRepository.guestStatus(guest)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                firstError = e
                null
            }
            val snapshots = if (liveOnly) null else try {
                app.fleetRepository.snapshots(guest)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                firstError = firstError ?: e
                null
            }
            val hardware = if (liveOnly) null else try {
                app.fleetRepository.hardware(guest)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            firstError?.let { e ->
                if (e.isAuthFailure() || e.isSessionChanged()) {
                    onFleetFailure(e)
                    return@launch
                }
            }
            updateGuestView(guestId) {
                it.copy(
                    live = if (liveOnly) live ?: it.live else live,
                    snapshots = if (liveOnly) it.snapshots else snapshots,
                    hardware = hardware ?: it.hardware,
                    loading = false,
                    error = firstError?.rootMessage(),
                )
            }
        }
    }

    /** One write at a time per guest; the detail shows [label] until the worker finishes. */
    private fun updateGuestView(guestId: String, transform: (GuestView) -> GuestView) {
        _fleet.update { state ->
            val view = state.guestView ?: return@update state
            if (view.guestId != guestId) state else state.copy(guestView = transform(view))
        }
    }

    /** Start or stop the task watcher to match whatever the detail pane now shows. */
    private fun onFocusChanged() {
        val focus = _fleet.value.nav.focus
        if (focus is DashFocus.GuestDetail) {
            loadGuestView(focus.id)
            loadGraph("guest:${focus.id}")
        }
        if (focus is DashFocus.NodeDetail) {
            loadGraph("node:${focus.id}")
            loadNodeInventory(focus.id)
            val node = _fleet.value.nodes.find { it.id == focus.id }
            if (node != null) loadRemoteNodeNotes(node.remoteId, node.name)
        }
        if (focus is DashFocus.NodeUpdates) loadNodeUpdates(focus.remoteId, focus.node)
        if (focus is DashFocus.RemoteDetail) {
            loadRemoteView(focus.id)
            loadRemoteClusterNotes(focus.id)
        }
        if (focus is DashFocus.Host) {
            loadSharedNotes()
            ensureHostTasks()
            loadHostRrd()
        } else {
            hostLogs?.close()
        }
        if (focus is DashFocus.Tasks) ensureHistory()
        if (focus !is DashFocus.TaskDetail) {
            taskJob?.cancel()
            taskJob = null
            taskLog?.close()
            taskLog = null
            return
        }
        if (_fleet.value.taskView?.task?.upid == focus.upid && taskJob?.isActive == true) return
        val task = _fleet.value.tasks?.firstOrNull { it.upid == focus.upid }
            ?: _fleet.value.taskView?.task?.takeIf { it.upid == focus.upid }
            ?: return
        watchTask(task)
    }

    private var taskLog: com.pdm.app.data.ops.TaskLogController? = null

    /**
     * Follow one task while its screen is open (Q2): live status, and a bounded log window that
     * starts at the end, follows new lines, and is read to its real end once the task finishes.
     * All reads go through one [com.pdm.app.data.ops.TaskLogController]; it stops with the screen.
     */
    private fun watchTask(task: RemoteTask) {
        taskJob?.cancel()
        taskLog?.close()
        val session = app.sessionStore.snapshot() ?: return
        fun <T> scoped(block: suspend () -> T): suspend () -> T = {
            if (!app.sessionStore.isCurrent(session)) throw CancellationException("session changed")
            try {
                block()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Sign-in or certificate trouble goes to the normal path, not into the log pane.
                if (e.isAuthFailure() || e.isSessionChanged() || e.isTlsFailure()) {
                    onFleetFailure(e)
                    throw CancellationException("session ended")
                }
                throw e
            }
        }
        val controller = com.pdm.app.data.ops.TaskLogController(object : com.pdm.app.data.ops.TaskLogSource {
            override suspend fun status() = scoped { app.fleetRepository.taskStatus(task, liveOnly = true) }()
            override suspend fun page(start: Int, limit: Int) = scoped { app.fleetRepository.taskLogPage(task, start, limit) }()
        })
        taskLog = controller
        _fleet.update { state ->
            val keep = state.taskView?.takeIf { it.task.upid == task.upid }
            state.copy(taskView = TaskView(task, stopping = keep?.stopping == true))
        }
        taskJob = viewModelScope.launch {
            launch {
                controller.state.collect { log ->
                    updateTaskView(task.upid) { v -> v.copy(log = log, stopping = v.stopping && log.status?.isRunning != false) }
                }
            }
            controller.open()
            var sawRunning = controller.state.value.running
            while (_fleet.value.nav.focus == DashFocus.TaskDetail(task.upid)) {
                val more = controller.poll()
                sawRunning = sawRunning || controller.state.value.running
                if (!more) break
                delay(TASK_POLL_MS)
            }
            // It ran while watched and has ended: what it changed shows up on the next load.
            if (sawRunning && controller.state.value.complete && !_fleet.value.readOnly) refresh(RefreshReason.ACTION)
            // Keep mirroring page buttons until the screen goes away.
            kotlinx.coroutines.awaitCancellation()
        }
    }

    fun loadEarlierTaskLog() {
        val c = taskLog ?: return
        viewModelScope.launch { c.loadEarlier() }
    }

    fun loadMoreTaskLog() {
        val c = taskLog ?: return
        viewModelScope.launch { c.loadLater() }
    }

    fun toggleFollowTaskLog() {
        val c = taskLog ?: return
        val on = !c.state.value.follow
        viewModelScope.launch { c.setFollow(on) }
    }

    fun retryTaskLog() {
        val c = taskLog ?: return
        viewModelScope.launch { c.retry() }
    }

    private fun updateTaskView(upid: String, transform: (TaskView) -> TaskView) {
        _fleet.update { state ->
            val view = state.taskView ?: return@update state
            if (view.task.upid != upid) state else state.copy(taskView = transform(view))
        }
    }

    fun openLeader(entry: LeaderEntry) {
        val snap = _fleet.value
        val focus = entry.toFocus(snap.guests, snap.nodes) ?: return
        openFocus(focus)
    }

    fun setQuery(query: String) {
        _fleet.update { it.copy(query = query) }
    }

    /**
     * The timeframe only changes the leader charts and the trend graphs, so only those are read
     * again. The rest of the dashboard stays as it is: no fleet reload, nothing redrawn.
     */
    fun setTimeframe(timeframe: String) {
        if (timeframe == _fleet.value.timeframe) return
        _fleet.update { it.copy(timeframe = timeframe) }
        loadLeaders(timeframe)
        loadTrend()
    }

    private var leadersJob: Job? = null

    /** The busiest guests and nodes for [timeframe]; a later tap cancels this read. */
    private fun loadLeaders(timeframe: String) {
        if (!_fleet.value.signedIn) return
        val session = app.sessionStore.snapshot() ?: return
        leadersJob?.cancel()
        leadersJob = viewModelScope.launch {
            val read = try {
                fatalGuard { app.fleetRepository.readLeaders(timeframe) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return@launch
            }
            if (!app.sessionStore.isCurrent(session)) return@launch
            _fleet.update {
                if (it.timeframe != timeframe) return@update it
                it.copy(leaders = com.pdm.app.data.ops.keepOnFailure(read, it.leaders) ?: com.pdm.app.data.model.Leaderboards())
                    .withSection(com.pdm.app.data.ops.FleetSection.LEADERS, read)
            }
        }
    }

    /**
     * Keep the fleet current while the home screen is on screen. Run it from a lifecycle-bound
     * coroutine (RESUMED): cancelling it stops the schedule, never a load already running.
     * The next load is due [RefreshPolicy.interval] after the last one ended, so coming back
     * after a while refreshes at once, a pull resets the clock, and failures back off.
     */
    suspend fun keepFresh() {
        visible = true
        try {
            while (true) {
                // PDM is restarting: its watcher owns the next load.
                if (_fleet.value.hostRestart?.settled == false) {
                    _fleet.first { it.hostRestart?.settled != false }
                    continue
                }
                val sync = _fleet.value.sync
                val wait = RefreshPolicy.dueIn(sync, now())
                if (wait > 0) {
                    // Sleep until due, or until a load elsewhere (pull, action, reconnect) moves the schedule.
                    withTimeoutOrNull(wait) {
                        _fleet.first { it.sync.lastAttemptAt != sync.lastAttemptAt || it.sync.paused != sync.paused }
                    }
                    continue
                }
                val job = refresh(RefreshReason.AUTO)
                if (job == null) delay(RefreshPolicy.INTERVAL_MS) else job.join()
            }
        } finally {
            visible = false
        }
    }

    /**
     * One fleet load at a time. An automatic tick joins a load already running. A pull, a
     * timeframe change or a finished write restarts it, because a load that began before the
     * change can carry the old state; a full load cut short that way stays full.
     * A pull (or [full]) reads both tiers and everything in the detail pane.
     * Returns the job doing the work, or null when signed out.
     */
    fun refresh(reason: RefreshReason, full: Boolean = false): Job? {
        val state = _fleet.value
        if (!state.signedIn) return null
        if (state.hostRestart?.settled == false && (reason == RefreshReason.PULL || reason == RefreshReason.AUTO)) {
            // The restart watcher owns loads until it settles; a pull only makes it look now.
            if (reason == RefreshReason.PULL) restartRecovery?.probeNow()
            return null
        }
        val running = refreshJob?.takeIf { it.isActive }
        if (running != null && (reason == RefreshReason.AUTO || (reason == RefreshReason.PULL && state.sync.manual))) {
            return running
        }
        val policyDepth = RefreshPolicy.depth(reason, state.sync, now())
        val keepFull = running != null && refreshDepth == LoadDepth.FULL
        val depth = if (full || keepFull) LoadDepth.FULL else policyDepth
        val manual = reason == RefreshReason.PULL || (running != null && state.sync.manual)
        syncLog(
            buildString {
                append(
                    when (reason) {
                        RefreshReason.AUTO -> "Timer"
                        RefreshReason.PULL -> "Pull"
                        RefreshReason.ACTION -> "After change"
                        RefreshReason.TIMEFRAME -> "Timeframe"
                    },
                )
                append(
                    when {
                        app.sessionStore.session.value?.cachedOnly == true -> " · going live"
                        depth == LoadDepth.FULL -> " · full"
                        else -> " · quick"
                    },
                )
                if (running != null) append(" · restarted")
            },
            NoteKind.TRIGGER,
        )
        running?.cancel()
        val token = ++refreshToken
        refreshDepth = depth
        _fleet.update { it.copy(sync = it.sync.copy(running = true, manual = manual)) }
        val job = viewModelScope.launch {
            try {
                // The session decides, not the screen state: they differ for a moment after reconnecting.
                if (app.sessionStore.session.value?.cachedOnly == true) {
                    reconnect()
                } else {
                    loadFleetWithReauth(depth)
                    refreshFocus(depth)
                    if (depth == LoadDepth.FULL || _fleet.value.timeframe == "hour") loadTrend()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onFleetFailure(e)
            } finally {
                if (token == refreshToken) {
                    refreshDepth = null
                    _fleet.update { it.copy(sync = it.sync.copy(running = false, manual = false)) }
                }
            }
        }
        refreshJob = job
        return job
    }

    /** Re-read what the detail pane shows. Quick: the parts that move; full: all of it. */
    private fun refreshFocus(depth: LoadDepth) {
        val full = depth == LoadDepth.FULL
        val state = _fleet.value
        val graph = state.graphView?.takeIf { full || it.timeframe == RrdTimeframe.HOUR }
        when (val focus = state.nav.focus) {
            is DashFocus.GuestDetail -> {
                loadGuestView(focus.id, liveOnly = !full)
                graph?.takeIf { it.key == "guest:${focus.id}" }?.let { loadGraph(it.key, it.timeframe) }
            }
            is DashFocus.NodeDetail -> {
                graph?.takeIf { it.key == "node:${focus.id}" }?.let { loadGraph(it.key, it.timeframe) }
                if (full) loadNodeInventory(focus.id)
            }
            is DashFocus.RemoteDetail -> if (full) loadRemoteView(focus.id)
            is DashFocus.NodeUpdates -> if (full) loadNodeUpdates(focus.remoteId, focus.node)
            is DashFocus.TaskDetail -> if (full && taskJob?.isActive != true) {
                val task = state.tasks?.firstOrNull { it.upid == focus.upid }
                    ?: state.taskView?.task?.takeIf { it.upid == focus.upid }
                task?.let(::watchTask)
            }
            else -> Unit
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    /**
     * One refresh / cache / reconnect decision, in plain words: kept for the LIVE panel (last 40,
     * memory only) and, in debug builds, logged under `PdmSync`. Never data or secrets.
     */
    private fun syncLog(
        message: String,
        kind: NoteKind = NoteKind.EVENT,
        tone: NoteTone = NoteTone.NEUTRAL,
    ) {
        _journal.update { (it + SyncNote(System.currentTimeMillis(), message, kind, tone)).takeLast(60) }
        if (com.pdm.app.BuildConfig.DEBUG) android.util.Log.d("PdmSync", message)
    }

    /** The dashboard's EXPAND / COMPACT toggle; remembered. */
    fun setDashboardExpanded(expanded: Boolean) {
        uiPrefs.dashboardExpanded = expanded
        _fleet.update { it.copy(dashboardExpanded = expanded) }
        if (expanded) loadTrend()
    }

    /**
     * Fleet history for the expanded dashboard, from every node's own history. Read on expand, on
     * a timeframe change and on full loads (the 1 h view also on quick ones); offline it comes from
     * the cache like any other read.
     */
    private fun loadTrend() {
        val state = _fleet.value
        if (!state.dashboardExpanded || !state.signedIn) return
        val tf = state.timeframe
        val nodes = state.nodes.filter { it.status == "online" }
        val rrd = RrdTimeframe.entries.firstOrNull { it.apiName == tf } ?: RrdTimeframe.DAY
        _fleet.update { s ->
            // The graphs on screen stay until the new window's data replaces them; no blank flash.
            s.copy(trend = TrendView(tf, data = s.trend?.data, loading = true))
        }
        trendJob?.cancel()
        trendJob = viewModelScope.launch {
            try {
                val data = app.fleetRepository.fleetTrend(nodes, rrd)
                _fleet.update { s ->
                    if (s.trend?.timeframe != tf) s else s.copy(trend = TrendView(tf, data, loading = false))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e.isAuthFailure()) {
                    onFleetFailure(e)
                    return@launch
                }
                _fleet.update { s ->
                    val view = s.trend?.takeIf { it.timeframe == tf } ?: return@update s
                    s.copy(trend = view.copy(loading = false, error = e.rootMessage()))
                }
            }
        }
    }

    /**
     * After a live load: add this minute's counts to the recorded history, and save it (encrypted,
     * offline cache rules) at most every 5 minutes. The first time per login, read what was saved.
     */
    private suspend fun recordHistory(login: String, live: Boolean) {
        if (historyFor != login) {
            historyFor = login
            val saved = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.get(login, HISTORY_KEY) }
            val samples = saved?.let {
                runCatching {
                    com.pdm.app.data.api.AppJson.decodeFromString(
                        com.pdm.app.data.FleetHistoryFile.serializer(),
                        it.body.decodeToString(),
                    ).samples
                }.getOrNull()
            }.orEmpty()
            _fleet.update { it.copy(history = com.pdm.app.data.FleetHistory.tidy(samples, System.currentTimeMillis())) }
        }
        if (!live) return
        val state = _fleet.value
        val m = state.metrics
        val roll = state.tasks?.taskRollup()
        val sample = com.pdm.app.data.FleetSample(
            t = System.currentTimeMillis(),
            remotes = state.remotes.size,
            remotesOk = state.remotes.count { it.error.isNullOrBlank() },
            guests = m.guestsRunning + m.guestsStopped,
            guestsUp = m.guestsRunning,
            nodes = m.nodes,
            nodesUp = m.nodesOnline,
            faults = m.remoteErrors,
            tasksRunning = roll?.running,
            tasksFailed = roll?.failed,
            updates = state.updates?.sumOf { it.pending }?.plus(state.host?.updates?.size ?: 0),
            storageUsed = m.pveStorageUsed,
            storageTotal = m.pveStorageTotal,
            sdnOk = m.sdnAvailable,
            sdnError = m.sdnError,
        )
        val next = com.pdm.app.data.FleetHistory.add(state.history, sample)
        _fleet.update { it.copy(history = next) }
        if (sample.t - historySavedAt >= HISTORY_SAVE_MS) {
            historySavedAt = sample.t
            val bytes = com.pdm.app.data.api.AppJson.encodeToString(
                com.pdm.app.data.FleetHistoryFile.serializer(),
                com.pdm.app.data.FleetHistoryFile(next),
            ).encodeToByteArray()
            cache.putAsync(login, HISTORY_KEY, bytes)
        }
    }

    /** Open or close the LIVE panel. The request feed runs while it is open, even if the background is off. */
    fun setLivePanel(open: Boolean) {
        app.activityFeed.setEnabled(uiPrefs.watermarkMode.showsActivity || open)
        app.sessionStore.snapshot()?.let { app.activityFeed.bindSession(it.generation) }
        _fleet.update { it.copy(livePanel = open, cacheStats = if (open) currentCacheStats() else it.cacheStats) }
    }

    /** This session's login is saving live answers to the offline cache right now. */
    fun cacheWriting(): Boolean = cache.writableLogin != null

    private fun currentCacheStats(): ResponseCache.Stats? =
        app.sessionStore.session.value?.config?.let { cache.stats(ResponseCache.loginKey(SessionIdentity.of(it))) }

    /**
     * Offline view -> live: sign in with the saved credential, then load everything live. A server
     * that is still unreachable keeps the offline view and backs off. A rejected credential, TOTP
     * or certificate problem pauses retries: repeating a bad login could lock the account.
     */
    private suspend fun reconnect() {
        val offlineState = app.sessionStore.session.value?.takeIf { it.cachedOnly } ?: return
        val identity = SessionIdentity.of(offlineState.config)
        val saved = app.sessionStore.savedFormFor(identity)
        if (saved == null) {
            pauseOffline("This login has no saved credential any more. Exit and sign in to go live.")
            return
        }
        val outcome = app.authRepository.login(app.sessionStore.configFor(saved), saveSecret = true)
        when (outcome) {
            is LoginOutcome.Success -> syncLog("Signed in with the saved credential", tone = NoteTone.GOOD)
            is LoginOutcome.NeedsTfa -> syncLog("Second factor needed to go live", NoteKind.RESULT, NoteTone.BAD)
            is LoginOutcome.NeedsCertConfirmation ->
                syncLog("certificate needs confirming", NoteKind.RESULT, NoteTone.BAD)
            is LoginOutcome.Failed -> syncLog("not reachable · ${outcome.error.rootMessage()}", NoteKind.RESULT, NoteTone.BAD)
        }
        if (outcome is LoginOutcome.Success) {
            syncCacheWrites()
            // Live from here on, even if the first live load below fails: later ticks load, not log in.
            _fleet.update { it.copy(offline = false) }
            loadFleetWithReauth(LoadDepth.FULL)
            refreshFocus(LoadDepth.FULL)
            return
        }
        // A failed login clears the session; put the offline view back.
        app.sessionStore.setSession(offlineState)
        when (outcome) {
            is LoginOutcome.NeedsTfa -> pauseOffline("Going live needs your second factor (code or recovery key). Exit and sign in.")
            is LoginOutcome.NeedsCertConfirmation ->
                pauseOffline("The server's certificate needs your confirmation. Exit and sign in to check it.")
            is LoginOutcome.Failed -> if (outcome.error.isNetworkFailure()) {
                val at = now()
                _fleet.update {
                    it.copy(sync = it.sync.copy(failures = it.sync.failures + 1, lastAttemptAt = at, paused = false))
                }
            } else {
                pauseOffline("Could not go live: ${outcome.error.rootMessage()}")
            }
            is LoginOutcome.Success -> Unit
        }
    }

    private fun pauseOffline(message: String) {
        syncLog("Retries paused: $message", tone = NoteTone.BAD)
        val at = now()
        _fleet.update {
            it.copy(error = message, sync = it.sync.copy(paused = true, failures = it.sync.failures + 1, lastAttemptAt = at))
        }
    }

    /** False (and says why) while the screen shows cached data: changes need live answers. */
    private fun requireLive(): Boolean {
        if (!_fleet.value.readOnly) return true
        _fleet.update { it.copy(error = com.pdm.app.data.cache.CacheOnlyInterceptor.OFFLINE_WRITE) }
        return false
    }

    /** Start runs now. Shutdown and Stop wait for [confirmPower]. */
    fun requestPower(guest: Guest, action: GuestAction) {
        if (action == GuestAction.START || action == GuestAction.RESUME) {
            power(guest, action)
        } else {
            _fleet.update { it.copy(pendingPower = PendingPower(guest, action)) }
        }
    }

    fun confirmPower() {
        val pending = _fleet.value.pendingPower ?: return
        _fleet.update { it.copy(pendingPower = null) }
        power(pending.guest, pending.action)
    }

    fun cancelPower() {
        _fleet.update { it.copy(pendingPower = null) }
    }

    private fun power(guest: Guest, action: GuestAction) {
        if (!_fleet.value.signedIn || !requireLive()) return
        if (guest.id in _fleet.value.busyIds) return
        val vmid = guest.vmid.toLong()
        // An earlier attempt whose outcome is still open: don't stack a blind duplicate on it.
        if (tracker.mayBeRunning(guest.remoteId, vmid)) {
            _fleet.update { it.copy(notice = Quip("An earlier action on ${guest.name} hasn't finished. See Operations under Tasks.", ++quipSeq)) }
            return
        }
        val session = app.sessionStore.session.value ?: return
        val receipt = tracker.begin(
            kind = OperationKind.power(action),
            origin = TaskOrigin.Remote(guestRemoteKind(guest), guest.remoteId),
            description = "${action.label} ${guest.name}",
            port = session.config.port,
            remoteId = guest.remoteId,
            node = guest.node,
            vmid = vmid,
            guestType = guest.type,
        ) ?: return
        _fleet.update { it.copy(busyIds = it.busyIds + guest.id, error = null) }
        runOperation(receipt, request = { app.fleetRepository.guestPower(guest, action) })
    }

    private fun guestRemoteKind(guest: Guest): String =
        _fleet.value.remotes.firstOrNull { it.id == guest.remoteId }?.kind ?: "pve"

    private fun dropSession() {
        notesEditor?.reset()
        notesEditor = null
        notesMirror?.cancel()
        notesGeneration = -1L
        history?.close()
        history = null
        historyMirror?.cancel()
        historyGeneration = -1L
        hostHistory?.close()
        hostHistory = null
        hostHistoryMirror?.cancel()
        hostHistoryKey = null
        hostLogs?.close()
        hostLogs = null
        hostLogsMirror?.cancel()
        hostLogsKey = null
        hostRrdJob?.cancel()
        hostRrdJob = null
        tracker.unbind()
        trackedGeneration = -1L
        restartRecovery = null
        endShell()
        restartJob?.cancel()
        restartJob = null
        refreshJob?.cancel()
        refreshJob = null
        refreshToken++
        refreshDepth = null
        cache.writableLogin = null
        historyFor = null
        trendJob?.cancel()
        app.activityFeed.clearTls()
        // The LIVE panel closes with the session; the feed goes back to what the background needs.
        app.activityFeed.setEnabled(uiPrefs.watermarkMode.showsActivity)
        taskJob?.cancel()
        taskJob = null
        app.authRepository.logout()
        pendingTfaIdentity = null
        pendingTfaTicket = null
        _fleet.value = resetFleet()
    }

    private fun onFleetFailure(e: Exception) {
        val message = e.rootMessage()
        // A request bound to a session that was since replaced (ticket renewal, reconnect): its
        // answer is just stale. Signing out is only right when no session is left.
        if (e.isSessionChanged() && !e.isAuthFailure() && app.sessionStore.session.value != null) return
        if (e.isAuthFailure() || e.isSessionChanged()) {
            clearPark()
            dropSession()
            _login.update { it.copy(error = message, jumpBackLabel = null, jumpBackNote = null) }
        } else {
            _fleet.update { it.copy(error = message) }
        }
    }

    /**
     * PDM password tickets expire (about 2 h). The password is not kept in memory, so when a
     * read comes back 401 and an encrypted secret is saved for this exact identity, sign in
     * again once and retry. Anything else is surfaced as before.
     */
    private suspend fun loadFleetWithReauth(depth: LoadDepth): LoadOutcome {
        try {
            if (!_fleet.value.demo && !app.authRepository.renewTicketIfStale()) {
                // Ticket already rejected: fall back to the saved secret. If that is not possible,
                // the load below gets the 401 and the normal sign-out path runs.
                reauthenticate()
            }
            return try {
                loadFleet(_fleet.value.demo, depth = depth)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!e.isAuthFailure() || !reauthenticate()) throw e
                loadFleet(_fleet.value.demo, depth = depth)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                val at = now()
                syncLog("failed · ${e.rootMessage()}", NoteKind.RESULT, NoteTone.BAD)
                _fleet.update { it.copy(sync = it.sync.copy(failures = it.sync.failures + 1, lastAttemptAt = at)) }
            }
            throw e
        }
    }

    private suspend fun reauthenticate(): Boolean {
        val session = app.sessionStore.session.value ?: return false
        if (_fleet.value.demo || session.cachedOnly || session.config.authMode != AuthMode.PASSWORD) return false
        val saved = app.sessionStore.savedFormFor(SessionIdentity.of(session.config)) ?: return false
        val config = app.sessionStore.configFor(saved)
        if (config.password.isBlank()) return false
        return app.authRepository.login(config, saveSecret = true) is LoginOutcome.Success
    }

    /** Read the fleet ([depth] decides the tiers) and publish it; a quick load keeps the slow tier. */
    private suspend fun loadFleet(
        demo: Boolean,
        fingerprint: String? = _fleet.value.fingerprint,
        depth: LoadDepth = LoadDepth.FULL,
    ): LoadOutcome {
        val mark = cache.servedMark()
        val snap = app.fleetRepository.load(_fleet.value.timeframe, depth, previousHost = _fleet.value.host)
        // Oldest saved answer used instead of a live one; null when the whole load was live.
        val cachedAt = cache.oldestServedSince(mark)
        val session = app.sessionStore.session.value
        if (session == null) {
            _fleet.value = resetFleet()
            return LoadOutcome(live = false, hostFresh = false)
        }
        val full = depth == LoadDepth.FULL
        val at = now()
        val stats = if (_fleet.value.livePanel) currentCacheStats() else null
        when {
            session.cachedOnly -> syncLog("offline view", NoteKind.RESULT, NoteTone.MUTED)
            cachedAt != null -> syncLog("unreachable · cache filled in", NoteKind.RESULT, NoteTone.BAD)
            else -> syncLog("live", NoteKind.RESULT, NoteTone.GOOD)
        }
        _fleet.update {
            it.copy(
                signedIn = true,
                demo = demo,
                offline = session.cachedOnly,
                hostLabel = if (demo) "DEMO" else session.config.displayHost,
                // Offline there is no live version answer; the cached PDM host read has one.
                // The host read is current (it moves after an upgrade); sign-in's answer is the fallback.
                version = snap.host?.version?.display ?: it.version ?: session.version?.display,
                sessionInfo = sessionDetails(),
                remotes = snap.remotes,
                guests = snap.guests,
                nodes = snap.nodes,
                storages = snap.storages,
                datastores = snap.datastores,
                networks = snap.networks,
                metrics = snap.metrics,
                // A section whose read failed keeps what was shown, marked by its status (Q4).
                leaders = com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.leaders, it.leaders) ?: com.pdm.app.data.model.Leaderboards(),
                subscriptions = com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.subscriptions, it.subscriptions).orEmpty(),
                tasks = com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.tasks, it.tasks),
                // Offline nothing can be changed: known-empty permissions hide every write control.
                permissions = when {
                    session.cachedOnly -> Permissions(emptyMap())
                    else -> com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.permissions, it.permissions)
                },
                shellAllowed = app.shellConnector.shellAllowed(),
                updates = com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.updates, it.updates),
                host = mergeHost(it.host, snap),
                sections = it.sections + snap.reads,
                selectedRemoteId = it.selectedRemoteId,
                fingerprint = fingerprint ?: it.fingerprint,
                error = null,
                query = it.query,
                timeframe = it.timeframe,
                showSettings = it.showSettings,
                certPins = it.certPins,
                secretSaved = it.secretSaved,
                nav = it.nav,
                cacheStats = stats ?: it.cacheStats,
                sync = when {
                    cachedAt == null -> it.sync.copy(
                        lastSuccessAt = at,
                        lastAttemptAt = at,
                        lastFullAt = if (full) at else it.sync.lastFullAt,
                        failures = 0,
                        cachedAt = null,
                        paused = false,
                    )
                    // Offline view: tries to go live are counted by reconnect().
                    session.cachedOnly -> it.sync.copy(cachedAt = cachedAt)
                    // Live session, server unreachable: cached answers filled in. That is a failure.
                    else -> it.sync.copy(cachedAt = cachedAt, failures = it.sync.failures + 1, lastAttemptAt = at)
                },
            )
        }
        if (!demo) recordHistory(ResponseCache.loginKey(SessionIdentity.of(session.config)), live = cachedAt == null)
        // First load of a session with the dashboard expanded: fetch its graphs too.
        if (_fleet.value.dashboardExpanded && _fleet.value.trend == null) loadTrend()
        val live = cachedAt == null && !session.cachedOnly
        if (live) ensureTracking()
        return LoadOutcome(live = live, hostFresh = live && snap.host?.status != null)
    }

    /**
     * The PDM machine after a load: parts whose read failed keep their earlier values (shown with
     * their status), so one failed package read never reads as "nothing pending".
     */
    private fun mergeHost(old: PdmHost?, snap: com.pdm.app.data.repo.FleetSnapshot): PdmHost? {
        val fresh = snap.host ?: return old
        if (old == null || old.node != fresh.node) return fresh
        return fresh.copy(
            status = com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.hostStatus, old.status),
            updates = com.pdm.app.data.ops.keepOnFailure(snap.sectionReads.hostUpdates, old.updates),
            subscription = fresh.subscription ?: old.subscription,
            version = fresh.version ?: old.version,
        )
    }

    /**
     * Retry one section's read (Q4). Only that section is read again; nothing else reloads, and a
     * section the account may not read is not retried.
     */
    fun retrySection(section: com.pdm.app.data.ops.FleetSection, force: Boolean = false) {
        val status = _fleet.value.sections[section]
        if (!force && status != null && !status.worthRetrying) return
        if (section in _fleet.value.sectionRetrying || _fleet.value.offline) return
        val session = app.sessionStore.snapshot() ?: return
        val repo = app.fleetRepository
        _fleet.update { it.copy(sectionRetrying = it.sectionRetrying + section) }
        viewModelScope.launch {
            try {
                val node = _fleet.value.host?.node
                val apply: (FleetUiState) -> FleetUiState = when (section) {
                    com.pdm.app.data.ops.FleetSection.TASKS -> fatalGuard { repo.readTasks() }.let { r ->
                        { st -> st.copy(tasks = com.pdm.app.data.ops.keepOnFailure(r, st.tasks)).withSection(section, r) }
                    }
                    com.pdm.app.data.ops.FleetSection.SUBSCRIPTIONS -> fatalGuard { repo.readSubscriptions() }.let { r ->
                        { st -> st.copy(subscriptions = com.pdm.app.data.ops.keepOnFailure(r, st.subscriptions).orEmpty()).withSection(section, r) }
                    }
                    com.pdm.app.data.ops.FleetSection.UPDATES -> fatalGuard { repo.readUpdates() }.let { r ->
                        { st -> st.copy(updates = com.pdm.app.data.ops.keepOnFailure(r, st.updates)).withSection(section, r) }
                    }
                    com.pdm.app.data.ops.FleetSection.PERMISSIONS -> fatalGuard { repo.readPermissions() }.let { r ->
                        { st -> st.copy(permissions = com.pdm.app.data.ops.keepOnFailure(r, st.permissions)).withSection(section, r) }
                    }
                    com.pdm.app.data.ops.FleetSection.LEADERS -> fatalGuard { repo.readLeaders(_fleet.value.timeframe) }.let { r ->
                        { st -> st.copy(leaders = com.pdm.app.data.ops.keepOnFailure(r, st.leaders) ?: com.pdm.app.data.model.Leaderboards()).withSection(section, r) }
                    }
                    com.pdm.app.data.ops.FleetSection.HOST_STATUS -> if (node == null) { st -> st } else fatalGuard { repo.readHostStatus(node) }.let { r ->
                        { st -> st.copy(host = st.host?.takeIf { h -> h.node == node }?.let { h -> h.copy(status = com.pdm.app.data.ops.keepOnFailure(r, h.status)) } ?: st.host).withSection(section, r) }
                    }
                    com.pdm.app.data.ops.FleetSection.HOST_UPDATES -> if (node == null) { st -> st } else fatalGuard { repo.readHostUpdates(node) }.let { r ->
                        { st -> st.copy(host = st.host?.takeIf { h -> h.node == node }?.let { h -> h.copy(updates = com.pdm.app.data.ops.keepOnFailure(r, h.updates)) } ?: st.host).withSection(section, r) }
                    }
                    // Remote health and the machine itself come with the whole fleet read.
                    else -> { st -> st }
                }
                if (app.sessionStore.isCurrent(session)) _fleet.update(apply)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally {
                _fleet.update { it.copy(sectionRetrying = it.sectionRetrying - section) }
            }
        }
    }

    private fun FleetUiState.withSection(section: com.pdm.app.data.ops.FleetSection, read: ReadAvailability<*>): FleetUiState =
        copy(sections = sections + (section to com.pdm.app.data.ops.SectionStatus.of(read)))

    /**
     * Follow this session's operations: bind the tracker, and once per sign-in look again at
     * anything left unresolved by an earlier run (Q5) or an unfinished restart (Q1). Reads only.
     */
    private fun ensureTracking() {
        val snapshot = app.sessionStore.snapshot() ?: return
        if (snapshot.state.cachedOnly) return
        val identity = SessionIdentity.of(snapshot.state.config)
        if (trackedGeneration == snapshot.generation) return
        trackedGeneration = snapshot.generation
        tracker.bind(identity) { app.sessionStore.session.value?.let { SessionIdentity.of(it.config) == identity } == true }
        tracker.restore()
        restoreRestart(snapshot)
    }

    private var trackedGeneration = -1L

    // --- Operations (Q5) ---

    /** Look again at an operation the app stopped watching. Reads only. */
    fun watchOperation(receiptId: String) {
        tracker.receipts().firstOrNull { it.id == receiptId }?.let(tracker::watch)
    }

    /** Open an operation's task, from its receipt (it may not be in the dashboard's task list). */
    fun openOperation(receiptId: String) {
        val receipt = tracker.receipts().firstOrNull { it.id == receiptId } ?: return
        val task = OperationTracker.taskOf(receipt) ?: return
        val known = _fleet.value.tasks?.firstOrNull { it.upid == task.upid }
        openTaskDirect(known ?: task)
    }

    /** An operation's task ended, was rejected, or stopped being watched: tidy the screen it touched. */
    private fun onOperationSettled(receipt: OperationReceipt) {
        val guestKey = receipt.remoteId?.let { r -> receipt.guestType?.let { t -> receipt.vmid?.let { "$r/$t/$it" } } }
        if (guestKey != null) {
            _fleet.update { it.copy(busyIds = it.busyIds - guestKey) }
            updateGuestView(guestKey) { v -> if (v.busyReceiptId == receipt.id) v.copy(busy = null, busyReceiptId = null) else v }
        }
        when (receipt.state) {
            OperationSubmissionState.FAILED ->
                _fleet.update { it.copy(error = "${receipt.description} failed: ${receipt.error.orEmpty()}") }
            OperationSubmissionState.OBSERVATION_PAUSED ->
                _fleet.update { it.copy(notice = Quip("${receipt.description} is still running. Follow it under Tasks → Operations.", ++quipSeq)) }
            else -> Unit
        }
        if (guestKey != null && _fleet.value.guestView?.guestId == guestKey) loadGuestView(guestKey)
        if (receipt.kind == OperationKind.HOST_APT_REFRESH && receipt.state == OperationSubmissionState.SUCCEEDED) {
            reloadHostPackages()
        }
        if (receipt.state != OperationSubmissionState.OBSERVATION_PAUSED) refresh(RefreshReason.ACTION, full = true)
    }

    /**
     * Send one operation through the tracker. [onAccepted] runs once the server accepted it (task
     * id known or not). An unknown outcome is reported, never retried.
     */
    private fun runOperation(
        receipt: OperationReceipt,
        request: suspend () -> String?,
        onAccepted: (String?) -> Unit = {},
        onRejected: (String) -> Unit = { reason -> _fleet.update { it.copy(error = "${receipt.description}: $reason") } },
        onUnknown: () -> Unit = {
            _fleet.update {
                it.copy(notice = Quip("No answer for ${receipt.description}. It may have started; see Operations under Tasks before trying again.", ++quipSeq))
            }
        },
    ) {
        viewModelScope.launch {
            when (val result = tracker.submit(receipt, request)) {
                is Submitted.Accepted -> {
                    onAccepted(result.upid)
                    if (result.upid == null) onOperationSettled(result.receipt)
                }
                is Submitted.Rejected -> {
                    onRejected(result.reason)
                    onOperationSettled(result.receipt)
                }
                is Submitted.Unknown -> {
                    onUnknown()
                    onOperationSettled(result.receipt)
                }
                is Submitted.Fatal -> {
                    onFleetFailure(result.error)
                    val settled = tracker.receipts().firstOrNull { it.id == receipt.id } ?: receipt
                    onOperationSettled(settled)
                }
            }
        }
    }

    // --- Task history (F1) ---

    private var history: com.pdm.app.data.ops.TaskHistoryController? = null
    private var historyGeneration = -1L
    private var historyMirror: Job? = null

    /** The history controller for this session; a new sign-in gets a new one. */
    private fun historyController(): com.pdm.app.data.ops.TaskHistoryController? {
        val session = app.sessionStore.snapshot() ?: return null
        history?.takeIf { historyGeneration == session.generation }?.let { return it }
        history?.close()
        historyMirror?.cancel()
        val controller = com.pdm.app.data.ops.TaskHistoryController(
            fetch = { filters ->
                if (!app.sessionStore.isCurrent(session)) throw CancellationException("session changed")
                fatalGuard { app.fleetRepository.taskHistoryPage(filters) }
            },
            stats = { filters ->
                if (!app.sessionStore.isCurrent(session)) throw CancellationException("session changed")
                fatalGuard { app.fleetRepository.taskStatistics(filters) }
            },
        )
        history = controller
        historyGeneration = session.generation
        historyMirror = viewModelScope.launch {
            controller.state.collect { st -> _fleet.update { it.copy(taskHistory = st) } }
        }
        return controller
    }

    /** Opening Tasks: load the first page once, scoped to the remote picked on the dashboard. */
    private fun ensureHistory() {
        val c = historyController() ?: return
        if (c.state.value.loaded || c.state.value.loading) return
        val draft = _fleet.value.historyDraft.let { d -> if (d.isDefault) d.copy(remote = _fleet.value.selectedRemoteId) else d }
        _fleet.update { it.copy(historyDraft = draft) }
        viewModelScope.launch { c.apply(draft) }
    }

    /** Type into the type or user field; nothing is sent until Apply. */
    fun editHistory(transform: (com.pdm.app.data.ops.HistoryQuery) -> com.pdm.app.data.ops.HistoryQuery) {
        _fleet.update { it.copy(historyDraft = transform(it.historyDraft)) }
    }

    /** A status, remote or time chip: applies at once, with whatever is typed in the fields. */
    fun pickHistory(transform: (com.pdm.app.data.ops.HistoryQuery) -> com.pdm.app.data.ops.HistoryQuery) {
        editHistory(transform)
        applyHistory()
    }

    fun applyHistory() {
        val c = historyController() ?: return
        val draft = _fleet.value.historyDraft
        viewModelScope.launch { c.apply(draft) }
    }

    fun resetHistory() {
        val c = historyController() ?: return
        val fresh = com.pdm.app.data.ops.HistoryQuery()
        _fleet.update { it.copy(historyDraft = fresh, historySearch = "") }
        viewModelScope.launch { c.apply(fresh) }
    }

    fun loadMoreHistory() {
        val c = history ?: return
        viewModelScope.launch { c.loadMore() }
    }

    fun retryHistory() {
        val c = historyController() ?: return
        viewModelScope.launch { c.retry() }
    }

    fun setHistorySearch(text: String) {
        _fleet.update { it.copy(historySearch = text) }
    }

    // --- Host Administration Basics (F2) ---
    /** True while a Check now on the PDM machine may still be running or its outcome is unknown. */
    fun hostCheckRunning(state: List<OperationReceipt> = _fleet.value.activeReceipts): Boolean =
        state.any { it.kind == OperationKind.HOST_APT_REFRESH && it.mayBeActive }

    fun requestHostCheckNow() {
        val host = _fleet.value.host ?: return
        if (!requireLive() || hostCheckRunning()) return
        if (!_fleet.value.writable.canCheckHostUpdates()) return
        val session = app.sessionStore.session.value ?: return
        val receipt = tracker.begin(
            kind = OperationKind.HOST_APT_REFRESH,
            origin = TaskOrigin.Host(host.node),
            description = "Check for updates on ${host.node}",
            port = session.config.port,
            node = host.node,
        ) ?: return
        runOperation(
            receipt,
            request = { app.fleetRepository.checkHostUpdates(host.node).ifBlank { null } },
            onAccepted = { upid ->
                if (upid != null) {
                    tracker.receipts().firstOrNull { it.id == receipt.id }
                        ?.let(OperationTracker::taskOf)
                        ?.let { openTaskDirect(it.copy(workerType = "aptupdate")) }
                }
            },
        )
    }

    /** After a host Check now finished: re-read the host's own packages, then the fleet count. */
    private fun reloadHostPackages() {
        retrySection(com.pdm.app.data.ops.FleetSection.HOST_UPDATES, force = true)
    }

    fun requestHostTerminal() {
        val host = _fleet.value.host ?: return
        _fleet.update {
            it.copy(
                pendingShell = PendingShell(
                    target = ShellTarget.Host(host.node, upgrade = false),
                    staged = null,
                ),
            )
        }
    }

    fun toggleHostPackage(packageName: String) {
        val host = _fleet.value.host ?: return
        if (_fleet.value.hostUpdatesOpenPackage == packageName) {
            _fleet.update { it.copy(hostUpdatesOpenPackage = null, hostUpdatesChangelog = null) }
            return
        }
        val session = app.sessionStore.snapshot() ?: return
        _fleet.update { it.copy(hostUpdatesOpenPackage = packageName, hostUpdatesChangelog = null, hostUpdatesLoading = true) }
        viewModelScope.launch {
            val text = try {
                fatalGuard { app.fleetRepository.hostPackageChangelog(host.node, packageName) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                "Changelog did not load: ${e.rootMessage()}"
            }
            // Only the package still open, on the same machine, in the same sign-in.
            if (!app.sessionStore.isCurrent(session)) return@launch
            _fleet.update {
                if (it.hostUpdatesOpenPackage == packageName && it.host?.node == host.node) {
                    it.copy(hostUpdatesChangelog = text, hostUpdatesLoading = false)
                } else it
            }
        }
    }

    fun setHostRrdTimeframe(tf: RrdTimeframe) {
        _fleet.update { it.copy(hostRrdTimeframe = tf) }
        loadHostRrd(tf)
    }

    fun setHostRrdField(field: String) {
        _fleet.update { it.copy(hostRrdField = field) }
    }

    private var hostRrdJob: Job? = null

    /** The PDM machine's graph. Only the latest timeframe asked for, in this sign-in, is shown. */
    fun loadHostRrd(timeframe: RrdTimeframe = _fleet.value.hostRrdTimeframe) {
        val host = _fleet.value.host ?: return
        val session = app.sessionStore.snapshot() ?: return
        hostRrdJob?.cancel()
        _fleet.update { it.copy(hostRrdLoading = true, hostRrdError = null) }
        hostRrdJob = viewModelScope.launch {
            val result = try {
                Result.success(fatalGuard { app.fleetRepository.hostRrd(host.node, timeframe) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
            if (!app.sessionStore.isCurrent(session)) return@launch
            _fleet.update {
                if (it.hostRrdTimeframe != timeframe || it.host?.node != host.node) return@update it
                result.fold(
                    onSuccess = { points -> it.copy(hostRrdData = points, hostRrdLoading = false, hostRrdError = null) },
                    // A failed read leaves no points from another timeframe behind.
                    onFailure = { e -> it.copy(hostRrdData = emptyList(), hostRrdLoading = false, hostRrdError = "The graph didn't load: ${e.rootMessage()}") },
                )
            }
        }
    }

    // Host tasks (F2): the same server-side filters and paging as the Tasks screen.
    private var hostHistory: com.pdm.app.data.ops.TaskHistoryController? = null
    private var hostHistoryKey: Pair<Long, String>? = null
    private var hostHistoryMirror: Job? = null

    private fun hostHistoryController(): com.pdm.app.data.ops.TaskHistoryController? {
        val session = app.sessionStore.snapshot() ?: return null
        val node = _fleet.value.host?.node ?: return null
        val key = session.generation to node
        hostHistory?.takeIf { hostHistoryKey == key }?.let { return it }
        hostHistory?.close()
        hostHistoryMirror?.cancel()
        val controller = com.pdm.app.data.ops.TaskHistoryController(fetch = { filters ->
            if (!app.sessionStore.isCurrent(session)) throw CancellationException("session changed")
            fatalGuard { app.fleetRepository.hostTaskHistoryPage(node, filters) }
        }, pageSize = 30)
        hostHistory = controller
        hostHistoryKey = key
        hostHistoryMirror = viewModelScope.launch {
            controller.state.collect { st -> _fleet.update { it.copy(hostTaskHistory = st) } }
        }
        return controller
    }

    private fun ensureHostTasks() {
        if (!_fleet.value.writable.canReadHostTasks()) return
        val c = hostHistoryController() ?: return
        if (c.state.value.loaded || c.state.value.loading) return
        viewModelScope.launch { c.apply(com.pdm.app.data.ops.HistoryQuery()) }
    }

    fun setHostTaskOutcome(outcome: com.pdm.app.data.ops.HistoryOutcome) {
        val c = hostHistoryController() ?: return
        viewModelScope.launch { c.apply(com.pdm.app.data.ops.HistoryQuery(outcome = outcome)) }
    }

    fun loadMoreHostTasks() {
        val c = hostHistory ?: return
        viewModelScope.launch { c.loadMore() }
    }

    fun retryHostTasks() {
        val c = hostHistoryController() ?: return
        viewModelScope.launch { c.retry() }
    }

    // Host journal (F2): read on request, paged by cursor, never cached or polled.
    private var hostLogs: com.pdm.app.data.ops.JournalLogController? = null
    private var hostLogsKey: Pair<Long, String>? = null
    private var hostLogsMirror: Job? = null

    private fun hostLogsController(): com.pdm.app.data.ops.JournalLogController? {
        val session = app.sessionStore.snapshot() ?: return null
        val node = _fleet.value.host?.node ?: return null
        val key = session.generation to node
        hostLogs?.takeIf { hostLogsKey == key }?.let { return it }
        hostLogs?.close()
        hostLogsMirror?.cancel()
        val controller = com.pdm.app.data.ops.JournalLogController(fetch = { query ->
            if (!app.sessionStore.isCurrent(session)) throw CancellationException("session changed")
            try {
                fatalGuard { app.fleetRepository.hostJournalPage(node, query) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw IllegalStateException(hostLogsError(e), e)
            }
        })
        hostLogs = controller
        hostLogsKey = key
        hostLogsMirror = viewModelScope.launch {
            controller.state.collect { st -> _fleet.update { it.copy(hostLogs = st) } }
        }
        return controller
    }

    private fun hostLogsError(e: Exception): String {
        val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<com.pdm.app.data.repo.PdmHttpException>().firstOrNull()
        return when {
            http?.code == 403 -> "You can't read this machine's logs."
            e.isNetworkFailure() -> "Logs didn't load: no connection."
            else -> "Logs didn't load: ${e.rootMessage()}"
        }
    }

    fun setHostLogsService(text: String) {
        _fleet.update { it.copy(hostLogsService = text.take(64)) }
    }

    fun readHostLogs() {
        if (_fleet.value.readOnly || !_fleet.value.writable.canReadHostLogs()) return
        val c = hostLogsController() ?: return
        val filters = com.pdm.app.data.ops.JournalFilters(service = _fleet.value.hostLogsService)
        viewModelScope.launch { c.open(filters) }
    }

    fun earlierHostLogs() {
        val c = hostLogs ?: return
        viewModelScope.launch { c.earlier() }
    }

    fun newerHostLogs() {
        val c = hostLogs ?: return
        viewModelScope.launch { c.newer() }
    }

    fun retryHostLogs() {
        val c = hostLogsController() ?: return
        viewModelScope.launch { c.retry() }
    }

    // --- Shared Notes (F3) ---
    private var notesEditor: com.pdm.app.data.ops.NotesEditor? = null
    private var notesGeneration = -1L
    private var notesMirror: Job? = null

    /** The notes editor for this session; another sign-in gets a fresh one with no old draft. */
    private fun notesEditor(): com.pdm.app.data.ops.NotesEditor? {
        val session = app.sessionStore.snapshot() ?: return null
        notesEditor?.takeIf { notesGeneration == session.generation }?.let { return it }
        notesEditor?.reset()
        notesMirror?.cancel()
        fun guard() { if (!app.sessionStore.isCurrent(session)) throw CancellationException("session changed") }
        val editor = com.pdm.app.data.ops.NotesEditor(
            read = { guard(); fatalGuard { app.fleetRepository.readSharedNotes() } },
            write = { text, digest -> guard(); fatalGuard { app.fleetRepository.writeSharedNotes(text, digest) } },
            classify = { e ->
                com.pdm.app.data.repo.notesTrouble(e)
            },
        )
        notesEditor = editor
        notesGeneration = session.generation
        notesMirror = viewModelScope.launch { editor.state.collect { st -> _fleet.update { it.copy(notes = st) } } }
        return editor
    }

    /** Sign-in or certificate trouble goes to the normal path instead of into a pane's error. */
    private suspend fun <T> fatalGuard(block: suspend () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e.isAuthFailure() || e.isSessionChanged() || e.isTlsFailure()) {
            onFleetFailure(e)
            throw CancellationException("session ended")
        }
        throw e
    }

    fun loadSharedNotes() {
        if (_fleet.value.readOnly && _fleet.value.notes.server == null) {
            // Offline: notes are never stored, so there is nothing to show.
            return
        }
        val e = notesEditor() ?: return
        viewModelScope.launch { e.load() }
    }

    fun startEditingSharedNotes() {
        if (!requireLive() || !_fleet.value.writable.canEditSharedNotes()) return
        notesEditor()?.startEditing()
    }

    fun updateNotesDraft(draft: String) {
        notesEditor?.edit(draft)
    }

    fun cancelEditingSharedNotes() {
        notesEditor?.discard()
    }

    fun saveSharedNotes() {
        if (!requireLive() || !_fleet.value.writable.canEditSharedNotes()) return
        val e = notesEditor() ?: return
        viewModelScope.launch {
            e.save()
            if (e.state.value.savedAt != null && e.state.value.draft == null) {
                _fleet.update { it.copy(notice = Quip("Notes saved.", ++quipSeq)) }
            }
        }
    }

    /** After a conflict: drop the draft, keep the version someone else saved. */
    fun notesKeepTheirs() {
        notesEditor?.keepTheirs()
    }

    /** After a conflict: continue editing over their version; saving is still a separate step. */
    fun notesReplaceTheirs() {
        notesEditor?.replaceTheirs()
    }

    fun confirmNotesLeave() {
        notesEditor?.discard()
        _fleet.update { it.copy(pendingNotesLeave = false) }
        popFocus()
    }

    fun cancelNotesLeave() {
        _fleet.update { it.copy(pendingNotesLeave = false) }
    }

    /** Leaving the PDM screen with unsaved notes asks first. */
    private fun notesBlockLeaving(): Boolean {
        if (_fleet.value.nav.focus != DashFocus.Host || !_fleet.value.notes.hasUnsavedChanges) return false
        _fleet.update { it.copy(pendingNotesLeave = true) }
        return true
    }

    fun loadRemoteClusterNotes(remote: String) {
        val session = app.sessionStore.snapshot() ?: return
        viewModelScope.launch {
            val read = try {
                NoteRead(text = fatalGuard { app.fleetRepository.getRemoteClusterNotes(remote) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                NoteRead(error = notesReadError(e))
            }
            if (app.sessionStore.isCurrent(session)) _fleet.update { it.copy(remoteNotes = it.remoteNotes + (remote to read)) }
        }
    }

    fun loadRemoteNodeNotes(remote: String, node: String) {
        val session = app.sessionStore.snapshot() ?: return
        val key = "$remote/$node"
        viewModelScope.launch {
            val read = try {
                NoteRead(text = fatalGuard { app.fleetRepository.getRemoteNodeNotes(remote, node) })
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                NoteRead(error = notesReadError(e))
            }
            if (app.sessionStore.isCurrent(session)) _fleet.update { it.copy(nodeNotes = it.nodeNotes + (key to read)) }
        }
    }

    private fun notesReadError(e: Exception): String = when (com.pdm.app.data.repo.notesTrouble(e)) {
        com.pdm.app.data.ops.NotesTrouble.FORBIDDEN -> "You can't read these notes."
        com.pdm.app.data.ops.NotesTrouble.UNREACHABLE -> "Notes didn't load: no connection."
        com.pdm.app.data.ops.NotesTrouble.OTHER -> "Notes didn't load."
    }
}
