package com.pdm.app.data.api

import com.pdm.app.data.model.AptUpdateDto
import com.pdm.app.data.model.SyslogLineDto
import retrofit2.http.PUT
import com.pdm.app.data.model.ClusterStatusDto
import com.pdm.app.data.model.FirewallRemoteDto
import com.pdm.app.data.model.MetricCollectionDto
import com.pdm.app.data.model.GuestStatusDto
import com.pdm.app.data.model.MigratePreflightDto
import com.pdm.app.data.model.NetIfaceDto
import com.pdm.app.data.model.NodeStorageDto
import com.pdm.app.data.model.HostStatusDto
import com.pdm.app.data.model.HostSubscriptionDto
import com.pdm.app.data.model.PdmNodeRef
import com.pdm.app.data.model.PdmRemote
import com.pdm.app.data.model.TaskStatisticsDto
import com.pdm.app.data.model.RealmDto
import com.pdm.app.data.model.PdmResponse
import com.pdm.app.data.model.RemoteResourcesGroup
import com.pdm.app.data.model.RemoteSubscription
import com.pdm.app.data.model.RemoteTaskDto
import com.pdm.app.data.model.SnapshotDto
import com.pdm.app.data.model.ResourcesStatus
import com.pdm.app.data.model.TaskLogLine
import com.pdm.app.data.model.TaskStatus
import com.pdm.app.data.model.TicketInfo
import com.pdm.app.data.model.TopEntities
import com.pdm.app.data.model.ShellTicketDto
import com.pdm.app.data.model.UpdateSummaryDto
import com.pdm.app.data.model.VersionInfo
import com.pdm.app.data.model.VmEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Slice-1 PDM API. Paths match proxmox-datacenter-manager:
 * `GET remotes/remote`, `GET resources/list`, `GET/POST pve/remotes/{remote}/qemu|lxc/...`,
 * `GET pve/remotes/{remote}/tasks/{upid}/status`.
 */
interface PdmApi {

    @GET("version")
    suspend fun version(): PdmResponse<VersionInfo>

    @FormUrlEncoded
    @POST("access/ticket")
    suspend fun createTicket(
        @Field("username") username: String,
        @Field("password") password: String,
        /** With a TFA challenge, [password] carries the factor (`totp:`/`recovery:`), see TfaChallenge. */
        @Field("tfa-challenge") tfaChallenge: String? = null,
    ): Response<PdmResponse<TicketInfo>>

    @GET("remotes/remote")
    suspend fun listRemotes(): PdmResponse<List<PdmRemote>>

    @GET("resources/list")
    suspend fun resourcesList(
        @Query("max-age") maxAge: Int = 30,
    ): PdmResponse<List<RemoteResourcesGroup>>

    @GET("resources/status")
    suspend fun resourcesStatus(
        @Query("max-age") maxAge: Int = 30,
    ): PdmResponse<ResourcesStatus>

    @GET("resources/top-entities")
    suspend fun topEntities(
        @Query("timeframe") timeframe: String = "day",
    ): PdmResponse<TopEntities>

    @GET("resources/subscription")
    suspend fun subscriptions(): PdmResponse<List<RemoteSubscription>>

    /** Cached remote tasks. Read-only. `running=true` is a second pass so live jobs are not dropped by `limit`. */
    @GET("remotes/tasks/list")
    suspend fun listTasks(
        @Query("limit") limit: Int = 30,
        @Query("running") running: Boolean? = null,
        @Query("remote") remote: String? = null,
    ): PdmResponse<List<RemoteTaskDto>>

    /** Remote tasks with full filtering and paging (F1). */
    @GET("remotes/tasks/list")
    suspend fun listFilteredTasks(
        @Query("limit") limit: Int? = 30,
        @Query("running") running: Boolean? = null,
        @Query("remote") remote: String? = null,
        @Query("start") start: Long? = null,
        @Query("errors") errors: Boolean? = null,
        @Query("userfilter") userfilter: String? = null,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Query("typefilter") typefilter: String? = null,
        @Query("statusfilter") statusfilter: String? = null,
        @Query("view") view: String? = null,
        @retrofit2.http.Header(com.pdm.app.data.cache.LIVE_ONLY_HEADER) liveOnly: String? = null,
    ): Response<PdmResponse<List<RemoteTaskDto>>>

    @GET("pve/remotes/{remote}/qemu")
    suspend fun listQemu(
        @Path("remote") remote: String,
    ): PdmResponse<List<VmEntry>>

    @GET("pve/remotes/{remote}/lxc")
    suspend fun listLxc(
        @Path("remote") remote: String,
    ): PdmResponse<List<VmEntry>>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/qemu/{vmid}/start")
    suspend fun qemuStart(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/qemu/{vmid}/stop")
    suspend fun qemuStop(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/qemu/{vmid}/shutdown")
    suspend fun qemuShutdown(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/lxc/{vmid}/start")
    suspend fun lxcStart(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/lxc/{vmid}/stop")
    suspend fun lxcStop(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/lxc/{vmid}/shutdown")
    suspend fun lxcShutdown(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @GET("pve/remotes/{remote}/qemu/{vmid}/migrate")
    suspend fun qemuMigratePreflight(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Query("target") target: String,
    ): PdmResponse<MigratePreflightDto>

    /** In-cluster. `online` is QEMU live migration; `restart` is the LXC equivalent. */
    @FormUrlEncoded
    @POST("pve/remotes/{remote}/{type}/{vmid}/migrate")
    suspend fun migrate(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Field("target") target: String,
        @Field("online") online: Boolean? = null,
        @Field("restart") restart: Boolean? = null,
        @Field("with-local-disks") withLocalDisks: Boolean? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/{type}/{vmid}/remote-migrate")
    suspend fun remoteMigrate(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Field("target") target: String,
        @Field("target-vmid") targetVmid: Int? = null,
        @Field("target-storage") targetStorage: String,
        @Field("target-bridge") targetBridge: String,
        @Field("delete") delete: Boolean? = null,
        @Field("online") online: Boolean? = null,
        @Field("restart") restart: Boolean? = null,
    ): PdmResponse<JsonElement>

    /** `content` narrows to storages that accept that type (`images`, `rootdir`, …); null lists all. */
    @GET("pve/remotes/{remote}/nodes/{node}/storage")
    suspend fun nodeStorage(
        @Path("remote") remote: String,
        @Path("node") node: String,
        @Query("content") content: String? = null,
        @Query("enabled") enabled: Boolean? = true,
    ): PdmResponse<List<NodeStorageDto>>

    @GET("pve/remotes/{remote}/nodes/{node}/network")
    suspend fun nodeNetwork(
        @Path("remote") remote: String,
        @Path("node") node: String,
    ): PdmResponse<List<NetIfaceDto>>

    @GET("pve/remotes/{remote}/nodes/{node}/network")
    suspend fun nodeBridges(
        @Path("remote") remote: String,
        @Path("node") node: String,
        @Query("interface-type") type: String = "any_bridge",
    ): PdmResponse<List<NetIfaceDto>>

    @GET("pve/remotes/{remote}/cluster-nextid")
    suspend fun clusterNextId(@Path("remote") remote: String): PdmResponse<JsonElement>

    @GET("nodes")
    suspend fun pdmNodes(): PdmResponse<List<PdmNodeRef>>

    @GET("nodes/{node}/status")
    suspend fun hostStatus(@Path("node") node: String): PdmResponse<HostStatusDto>

    /** The same read, never answered from the offline cache: is the machine really up right now? */
    @GET("nodes/{node}/status")
    suspend fun hostStatusLive(
        @Path("node") node: String,
        @retrofit2.http.Header("X-Pdm-Live-Only") liveOnly: String = "1",
    ): PdmResponse<HostStatusDto>

    /** Installed versions of the key packages, including every installed kernel. */
    @GET("nodes/{node}/apt/versions")
    suspend fun hostAptVersions(@Path("node") node: String): PdmResponse<List<AptUpdateDto>>

    /** `systemctl reboot` on the PDM machine (Sys.PowerManagement). Returns before it goes down. */
    @FormUrlEncoded
    @POST("nodes/{node}/status")
    suspend fun hostPower(@Path("node") node: String, @Field("command") command: String): PdmResponse<JsonElement>

    @GET("nodes/{node}/apt/update")
    suspend fun hostAptUpdates(@Path("node") node: String): PdmResponse<List<AptUpdateDto>>

    @GET("nodes/{node}/subscription")
    suspend fun hostSubscription(@Path("node") node: String): PdmResponse<HostSubscriptionDto>

    /**
     * Result counts over the tasks matching the filters. PDM applies the list's `limit` here too
     * (default 50), so callers pass `limit=0` to count every match.
     */
    @GET("remotes/tasks/statistics")
    suspend fun taskStatistics(
        @Query("limit") limit: Int = 0,
        @Query("remote") remote: String? = null,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Query("userfilter") userfilter: String? = null,
        @Query("typefilter") typefilter: String? = null,
        @Query("statusfilter") statusfilter: String? = null,
        @Query("running") running: Boolean? = null,
    ): PdmResponse<TaskStatisticsDto>

    /** Public: works before login, used to fill the realm picker. */
    @GET("access/domains")
    suspend fun realms(): PdmResponse<List<RealmDto>>

    @GET("pve/remotes/{remote}/cluster-status")
    suspend fun clusterStatus(@Path("remote") remote: String): PdmResponse<List<ClusterStatusDto>>

    @GET("remotes/remote/{id}/version")
    suspend fun remoteVersion(@Path("id") id: String): PdmResponse<VersionInfo>

    @GET("pve/firewall/status")
    suspend fun firewallStatus(): PdmResponse<List<FirewallRemoteDto>>

    @GET("remotes/metric-collection/status")
    suspend fun metricCollectionStatus(): PdmResponse<List<MetricCollectionDto>>

    @GET("remotes/updates/summary")
    suspend fun updateSummary(): PdmResponse<UpdateSummaryDto>

    /** Re-poll every remote's pending-package list into PDM's cache. Starts a PDM worker. */
    @POST("remotes/updates/refresh")
    suspend fun refreshUpdateSummary(): PdmResponse<JsonElement>

    @GET("pve/remotes/{remote}/nodes/{node}/apt/update")
    suspend fun nodeAptUpdates(
        @Path("remote") remote: String,
        @Path("node") node: String,
    ): PdmResponse<List<AptUpdateDto>>

    /** `apt update` on one node: refreshes its package lists, installs nothing. Returns a UPID. */
    @POST("pve/remotes/{remote}/nodes/{node}/apt/update")
    suspend fun nodeAptRefresh(
        @Path("remote") remote: String,
        @Path("node") node: String,
    ): PdmResponse<JsonElement>

    /** Ticket for a root shell on a remote node, relayed by PDM. Password sessions only. */
    @POST("pve/remotes/{remote}/nodes/{node}/termproxy")
    suspend fun remoteShellTicket(
        @Path("remote") remote: String,
        @Path("node") node: String,
    ): PdmResponse<ShellTicketDto>

    /** Ticket for the PDM host's shell. `cmd=upgrade` (root@pam only) runs `apt full-upgrade` first. */
    @FormUrlEncoded
    @POST("nodes/{node}/termproxy")
    suspend fun hostShellTicket(
        @Path("node") node: String,
        @Field("cmd") cmd: String,
    ): PdmResponse<ShellTicketDto>

    @GET("pve/remotes/{remote}/nodes/{node}/apt/changelog")
    suspend fun aptChangelog(
        @Path("remote") remote: String,
        @Path("node") node: String,
        @Query("name") name: String,
    ): PdmResponse<String>

    @GET("access/permissions")
    suspend fun permissions(): PdmResponse<Map<String, JsonObject>>

    /** Current config (`state=active`), the same key/value map PVE stores. Description is live-only. */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("pve/remotes/{remote}/{type}/{vmid}/config")
    suspend fun guestConfig(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Query("state") state: String = "active",
    ): PdmResponse<JsonObject>

    @GET("pve/remotes/{remote}/{type}/{vmid}/status")
    suspend fun guestStatus(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
    ): PdmResponse<GuestStatusDto>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/qemu/{vmid}/resume")
    suspend fun qemuResume(
        @Path("remote") remote: String,
        @Path("vmid") vmid: Long,
        @Field("node") node: String? = null,
    ): PdmResponse<JsonElement>

    @GET("pve/remotes/{remote}/{type}/{vmid}/snapshot")
    suspend fun snapshots(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
    ): PdmResponse<List<SnapshotDto>>

    /** `vmstate` is QEMU only; leave null for containers. */
    @FormUrlEncoded
    @POST("pve/remotes/{remote}/{type}/{vmid}/snapshot")
    suspend fun createSnapshot(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Field("snapname") name: String,
        @Field("description") description: String? = null,
        @Field("vmstate") vmstate: Boolean? = null,
    ): PdmResponse<JsonElement>

    @FormUrlEncoded
    @POST("pve/remotes/{remote}/{type}/{vmid}/snapshot/{snapname}/rollback")
    suspend fun rollbackSnapshot(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Path("snapname") name: String,
        @Field("start") start: Boolean? = null,
    ): PdmResponse<JsonElement>

    @DELETE("pve/remotes/{remote}/{type}/{vmid}/snapshot/{snapname}")
    suspend fun deleteSnapshot(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Path("snapname") name: String,
    ): PdmResponse<JsonElement>

    @GET("pve/remotes/{remote}/tasks/{upid}/status")
    suspend fun taskStatus(
        @Path("remote") remote: String,
        @Path("upid", encoded = true) upid: String,
        @Query("wait") wait: Boolean = false,
    ): PdmResponse<TaskStatus>

    /** `kind` is `pve` or `pbs`; both trees expose the same task routes. */
    @GET("{kind}/remotes/{remote}/tasks/{upid}/status")
    suspend fun remoteTaskStatus(
        @Path("kind") kind: String,
        @Path("remote") remote: String,
        @Path("upid", encoded = true) upid: String,
        @retrofit2.http.Header("X-Pdm-Live-Only") liveOnly: String? = null,
    ): PdmResponse<TaskStatus>

    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("{kind}/remotes/{remote}/tasks/{upid}/log")
    suspend fun remoteTaskLog(
        @Path("kind") kind: String,
        @Path("remote") remote: String,
        @Path("upid", encoded = true) upid: String,
        @Query("start") start: Int = 0,
        @Query("limit") limit: Int = 500,
    ): PdmResponse<List<TaskLogLine>>

    @DELETE("{kind}/remotes/{remote}/tasks/{upid}")
    suspend fun stopRemoteTask(
        @Path("kind") kind: String,
        @Path("remote") remote: String,
        @Path("upid", encoded = true) upid: String,
    ): PdmResponse<JsonElement>

    /** `type` is `qemu` or `lxc`. Timeframe: hour…decade. cf: AVERAGE or MAX. */
    @GET("pve/remotes/{remote}/{type}/{vmid}/rrddata")
    suspend fun guestRrd(
        @Path("remote") remote: String,
        @Path("type") type: String,
        @Path("vmid") vmid: Long,
        @Query("timeframe") timeframe: String,
        @Query("cf") cf: String = "AVERAGE",
    ): PdmResponse<List<JsonObject>>

    @GET("pve/remotes/{remote}/nodes/{node}/rrddata")
    suspend fun nodeRrd(
        @Path("remote") remote: String,
        @Path("node") node: String,
        @Query("timeframe") timeframe: String,
        @Query("cf") cf: String = "AVERAGE",
    ): PdmResponse<List<JsonObject>>

    /** Trigger apt-get update on the PDM host (System.Modify on /). Returns task UPID. */
    @POST("nodes/{node}/apt/update")
    suspend fun hostAptUpdate(@Path("node") node: String): PdmResponse<String>

    /** Debian changelog for a package on the PDM host (System.Modify on /system). */
    @GET("nodes/{node}/apt/changelog")
    suspend fun hostAptChangelog(
        @Path("node") node: String,
        @Query("name") name: String,
    ): PdmResponse<String>

    /** Host task list. */
    @GET("nodes/{node}/tasks")
    suspend fun hostTasks(
        @Path("node") node: String,
        @Query("start") start: Long? = null,
        @Query("limit") limit: Int? = 30,
        @Query("errors") errors: Boolean? = null,
        @Query("running") running: Boolean? = null,
        @Query("userfilter") userfilter: String? = null,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Query("typefilter") typefilter: String? = null,
        @Query("statusfilter") statusfilter: String? = null,
        @retrofit2.http.Header(com.pdm.app.data.cache.LIVE_ONLY_HEADER) liveOnly: String? = null,
    ): Response<PdmResponse<List<RemoteTaskDto>>>

    /** Status of a task running directly on the PDM host. */
    @GET("nodes/{node}/tasks/{upid}/status")
    suspend fun hostTaskStatus(
        @Path("node") node: String,
        @Path("upid", encoded = true) upid: String,
        @retrofit2.http.Header("X-Pdm-Live-Only") liveOnly: String? = null,
    ): PdmResponse<TaskStatus>

    /** Log lines of a task running directly on the PDM host. */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("nodes/{node}/tasks/{upid}/log")
    suspend fun hostTaskLog(
        @Path("node") node: String,
        @Path("upid", encoded = true) upid: String,
        @Query("start") start: Int = 0,
        @Query("limit") limit: Int = 500,
    ): PdmResponse<List<TaskLogLine>>

    /** Stop a task running directly on the PDM host. */
    @DELETE("nodes/{node}/tasks/{upid}")
    suspend fun stopHostTask(
        @Path("node") node: String,
        @Path("upid", encoded = true) upid: String,
    ): PdmResponse<JsonElement>

    /** Read-only syslog on the PDM host. Live only, like the journal. */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("nodes/{node}/syslog")
    suspend fun hostSyslog(
        @Path("node") node: String,
        @Query("start") start: Int? = null,
        @Query("limit") limit: Int? = 100,
        @Query("service") service: String? = null,
        @Query("since") since: String? = null,
        @Query("until") until: String? = null,
    ): PdmResponse<List<SyslogLineDto>>

    /**
     * Read-only journal on the PDM host: mini-journalreader's own JSON, streamed. Logs can hold
     * anything a service printed, so they are always live and never stored on the device.
     */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("nodes/{node}/journal")
    suspend fun hostJournal(
        @Path("node") node: String,
        @Query("lastentries") lastentries: Int? = 100,
        @Query("since") since: Long? = null,
        @Query("until") until: Long? = null,
        @Query("service") service: String? = null,
        @Query("priority") priority: String? = null,
        @Query("startcursor") startcursor: String? = null,
        @Query("endcursor") endcursor: String? = null,
        @Query("unit") unit: String? = null,
    ): ResponseBody

    /** Historical RRD metrics for the PDM host itself. */
    @GET("nodes/{node}/rrddata")
    suspend fun hostRrd(
        @Path("node") node: String,
        @Query("timeframe") timeframe: String,
        @Query("cf") cf: String = "AVERAGE",
    ): PdmResponse<List<JsonObject>>

    /** Shared notes may hold passwords or topology: always live, never written to the offline cache. */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("config/notes")
    suspend fun getNotes(): PdmResponse<String>

    /** Update shared PDM Markdown notes with digest for conflict detection. */
    @FormUrlEncoded
    @PUT("config/notes")
    suspend fun putNotes(
        @Field("notes") notes: String,
        @Field("digest") digest: String? = null,
    ): PdmResponse<JsonElement>

    /** Remote PVE cluster options (contains description/notes). Live only, never cached. */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("pve/remotes/{remote}/options")
    suspend fun remoteClusterOptions(
        @Path("remote") remote: String,
    ): PdmResponse<JsonObject>

    /** Remote PVE node config (contains description/notes). Live only, never cached. */
    @retrofit2.http.Headers("X-Pdm-Live-Only: 1")
    @GET("pve/remotes/{remote}/nodes/{node}/config")
    suspend fun remoteNodeConfig(
        @Path("remote") remote: String,
        @Path("node") node: String,
    ): PdmResponse<JsonObject>
}
