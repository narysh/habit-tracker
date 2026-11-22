package dev.habittracker.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.useWriterConnection
import androidx.room.immediateTransaction
import androidx.work.*
import dev.habittracker.shared.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID

class Repository(val app: HabitApplication,val session: Session,private val autoSchedule: Boolean = true) {
    val serverUrl = app.preferences.url()
    private val key = app.preferences.storageKey()
    val database = TrackerDatabase.open(app,key)
    val dao = database.dao()
    val items = dao.observeItems().map { rows -> rows.map { WireJson.decodeFromString<Item>(it.localJson) } }
    val conflicts = dao.observeConflicts()
    val files = dao.observeFiles()
    val errors = dao.observeErrors()
    private val mutex = Mutex()
    private suspend fun <T> transaction(block: suspend ()->T): T = database.useWriterConnection { it.immediateTransaction { block() } }
    suspend fun <T> exclusive(block: suspend ()->T): T=mutex.withLock {block()}
    private val api get() = Api(serverUrl) {
        app.preferences.tokenFor(session.userId, serverUrl)
            ?: throw NetworkFailure(401,"Аккаунт изменился; синхронизация остановлена")
    }
    suspend fun save(content: Content,id: String=UUID.randomUUID().toString(),deleted: Boolean=false,notify: Boolean=true) {
        transaction {
            require(dao.conflict(id)==null) { "Сначала выбери версию в конфликте" }
            val row = dao.item(id)
            val previous=row?.let {WireJson.decodeFromString<Item>(it.localJson).content as? TaskContent}
            val unknown=content is TaskContent && content.completed && content.completionDateUnknown && previous?.completed!=false
            val value=if(content is TaskContent) content.copy(completionDateUnknown=unknown,completedAt=if(content.completed && !unknown) content.completedAt ?: if(previous?.completed==true)previous.completedAt else Instant.now().toString() else null) else content
            value.validate()
            val remote = row?.serverJson?.let { WireJson.decodeFromString<Item>(it) }
            val mutation = Mutation(UUID.randomUUID().toString(),id,remote?.version ?: 0,value,deleted)
            val local = Item(id,remote?.version ?: 0,value,deleted,Instant.now().toString(),remote?.sequence ?: 0)
            dao.item(LocalItem(id,WireJson.encodeToString(local),row?.serverJson))
            dao.pending(PendingMutation(id,WireJson.encodeToString(mutation)))
        }
        if(notify){scheduleSync(); Reminders.reschedule(app,this); TrackerWidget.updateAll(app)}
    }
    suspend fun applyPlan(item: Item) {
        val plan=item.content as WeekPlanContent
        synchronize()
        for(move in plan.moves) {
            require(dao.pending(move.itemId)==null && dao.conflict(move.itemId)==null) { "Сначала отправь локальные изменения и разреши конфликты" }
            val remote=dao.item(move.itemId)?.serverJson?.let {WireJson.decodeFromString<Item>(it)}
            require(remote?.version==move.expectedVersion) { "План устарел. Попроси ассистента обновить его." }
        }
        api.post<ApplyPlanRequest,Item>("/api/v1/week-plans/${item.id}/apply",ApplyPlanRequest(item.version))
        synchronize()
    }
    fun scheduleSync() {
        if(!autoSchedule) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(app).enqueueUniqueWork("sync-$key",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build())
    }
    suspend fun resolve(conflict: Conflict,keepLocal: Boolean) {
        transaction {
            val remote = conflict.remoteJson?.let { WireJson.decodeFromString<Item>(it) }
            val local = WireJson.decodeFromString<Item>(conflict.localJson)
            if(keepLocal) {
                dao.item(LocalItem(local.id,WireJson.encodeToString(local.copy(version=remote?.version ?: 0)),conflict.remoteJson))
                dao.pending(PendingMutation(local.id,WireJson.encodeToString(Mutation(UUID.randomUUID().toString(),local.id,remote?.version ?: 0,local.content,local.deleted))))
            } else {
                val selected = remote ?: local.copy(deleted=true)
                dao.item(LocalItem(local.id,WireJson.encodeToString(selected),conflict.remoteJson)); dao.removePending(local.id)
            }
            dao.removeConflict(local.id)
        }
        scheduleSync(); Reminders.reschedule(app,this); TrackerWidget.updateAll(app)
    }
    suspend fun synchronize() = mutex.withLock {
        var rounds=0
        do {
            val pending = dao.pending()
            val candidates = pending.map { WireJson.decodeFromString<Mutation>(it.operationJson) }.sortedWith(compareBy<Mutation> {
                if(it.baseVersion>0) 4 else when(it.content){is ProjectContent->0;is HabitMarkContent->2;is WeekPlanContent->3;else->1}
            }.thenBy {it.itemId}).take(100).map {mutation->
                // Create linked offline records first; acknowledge only their server base.
                // The original queued mutation then restores links after every target exists.
                if(mutation.baseVersion==0L && !mutation.deleted && mutation.content.organization.linkedIds.isNotEmpty()) mutation.copy(operationId=UUID.nameUUIDFromBytes("bootstrap:${mutation.operationId}".toByteArray()).toString(),content=mutation.content.withOrganization(mutation.content.organization.copy(linkedIds=emptySet()))) else mutation
            }
            val sent=mutableListOf<Mutation>();var bytes=256
            for(candidate in candidates){val size=WireJson.encodeToString(candidate).toByteArray().size;if(sent.isNotEmpty() && bytes+size>7*1024*1024)break;require(size<=7*1024*1024){"Одна запись превышает размер пакета синхронизации"};sent+=candidate;bytes+=size}
            val cursor = dao.metadata("cursor")?.toLong() ?: 0
            val response = api.post<SyncRequest,SyncResponse>("/api/v1/sync",SyncRequest(sent,cursor))
            transaction {
                for(result in response.results) {
                    val operation = sent.first { it.operationId == result.operationId }
                    val current = dao.pending(operation.itemId)
                    val queued = current?.let { WireJson.decodeFromString<Mutation>(it.operationJson) }
                    val row = dao.item(operation.itemId) ?: continue
                    when(result.status) {
                        MutationStatus.APPLIED -> {
                            val remote = requireNotNull(result.item)
                            val remoteJson = WireJson.encodeToString(remote)
                            if(queued?.operationId == operation.operationId) {
                                dao.item(LocalItem(remote.id,remoteJson,remoteJson)); dao.removePending(remote.id)
                            } else if(queued != null) {
                                dao.item(row.copy(serverJson=remoteJson))
                                dao.pending(PendingMutation(remote.id,WireJson.encodeToString(queued.copy(baseVersion=remote.version))))
                            }
                        }
                        MutationStatus.CONFLICT -> {
                            val remoteJson = result.item?.let { WireJson.encodeToString(it) }
                            dao.conflict(Conflict(row.id,row.localJson,remoteJson)); dao.removePending(row.id)
                            dao.item(row.copy(serverJson=remoteJson))
                        }
                        MutationStatus.REJECTED -> if(current != null && queued!=null && (queued.operationId == operation.operationId || UUID.nameUUIDFromBytes("bootstrap:${queued.operationId}".toByteArray()).toString()==operation.operationId)) dao.pending(current.copy(error=result.message ?: "Операция отклонена"))
                    }
                }
                for(remote in response.changes) {
                    val row = dao.item(remote.id)
                    val known = row?.serverJson?.let { WireJson.decodeFromString<Item>(it) }
                    if(known != null && known.version >= remote.version) continue
                    val remoteJson = WireJson.encodeToString(remote)
                    if(row != null && (dao.pending(row.id) != null || dao.conflict(row.id) != null)) {
                        dao.conflict(Conflict(row.id,row.localJson,remoteJson)); dao.removePending(row.id)
                        dao.item(row.copy(serverJson=remoteJson))
                    } else dao.item(LocalItem(remote.id,remoteJson,remoteJson))
                }
                dao.metadata(Metadata("cursor",response.nextCursor.toString()))
            }
            rounds++
        } while(rounds < 20 && (response.hasMore || dao.pending().isNotEmpty()))
        if(dao.pending().isNotEmpty())scheduleSync()
        val rows = dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }.filter { !it.deleted && it.content is NoteContent }
        for(row in rows) {
            if(dao.pending(row.id) != null || dao.conflict(row.id) != null) continue
            val references=(row.content as NoteContent).attachmentIds
            for(file in dao.files().filter { it.noteId == row.id && it.id in references && !it.uploaded && it.path != null }) {
                val query = "?noteId=${row.id}&name=${java.net.URLEncoder.encode(file.name,"UTF-8")}&mediaType=${java.net.URLEncoder.encode(file.mediaType,"UTF-8")}"
                api.request("/api/v1/attachments/${file.id}$query","PUT",File(file.path!!).readBytes(),file.mediaType)
                dao.file(file.copy(uploaded=true))
            }
            for(file in api.get<List<Attachment>>("/api/v1/notes/${row.id}/attachments")) {
                val existing = dao.file(file.id)
                dao.file(LocalFile(file.id,file.noteId,file.name,file.mediaType,file.size,existing?.path,true))
            }
        }
        Reminders.reschedule(app,this); TrackerWidget.updateAll(app)
    }
    suspend fun attach(noteId: String,uri: Uri,nameOverride: String?=null,mimeOverride: String?=null,attachmentId: String=UUID.randomUUID().toString(),notify: Boolean=true) {
        val item = dao.item(noteId)?.let { WireJson.decodeFromString<Item>(it.localJson) } ?: error("Заметка не найдена")
        val note = item.content as? NoteContent ?: error("Это не заметка")
        val id=attachmentId;UUID.fromString(id)
        if(id in note.attachmentIds && dao.file(id)!=null) return
        var name = nameOverride ?: uri.lastPathSegment ?: "Файл"
        if(nameOverride==null) app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) }
        val dir=File(app.filesDir,"attachments/$key").apply { mkdirs() }; val target=File(dir,id)
        val mime=mimeOverride ?: app.contentResolver.getType(uri) ?: "application/octet-stream"
        try {
            app.contentResolver.openInputStream(uri)!!.use { source -> target.outputStream().use { output ->
                val buffer=ByteArray(8192); var total=0L
                while(true) { val count=source.read(buffer); if(count<0) break; total+=count; require(total<=20*1024*1024) { "Файл больше 20 МБ" }; output.write(buffer,0,count) }
            } }
            dao.file(LocalFile(id,noteId,name,mime,target.length(),target.absolutePath,false))
            save(note.copy(attachmentIds=(note.attachmentIds+id).distinct()),noteId,notify=notify)
        } catch(e: Throwable) { target.delete(); dao.removeFile(id); throw e }
    }
    suspend fun fileMetadata(noteId: String): List<LocalFile> {
        val files=api.get<List<Attachment>>("/api/v1/notes/$noteId/attachments")
        files.forEach {file->val old=dao.file(file.id);dao.file(LocalFile(file.id,file.noteId,file.name,file.mediaType,file.size,old?.path,true))}
        return dao.files().filter {it.noteId==noteId}
    }
    suspend fun cachedFile(file: LocalFile): File {
        file.path?.let { if(File(it).exists()) return File(it) }
        val target=File(app.filesDir,"attachments/$key/${file.id}").apply { parentFile!!.mkdirs() }
        target.writeBytes(api.request("/api/v1/attachments/${file.id}"))
        dao.file(file.copy(path=target.absolutePath))
        return target
    }
}

class HabitApplication : android.app.Application() {
    lateinit var preferences: SessionPreferences; private set
    lateinit var shareInbox: ShareInbox; private set
    val shortcut=kotlinx.coroutines.flow.MutableStateFlow(false)
    val discovery by lazy { ServerDiscovery(this) }
    @Volatile private var cached: Repository? = null
    override fun onCreate() { super.onCreate(); preferences=SessionPreferences(this);shareInbox=ShareInbox(this) }
    fun resolveAccountServer(): String? {
        if (!preferences.automatic()) return preferences.current()?.let { preferences.url() }
        val active = preferences.current()
        val previousUrl = preferences.url()
        val verified = discovery.resolve()
        if (BuildConfig.BACKEND_ORIGIN.isEmpty()) preferences.followDiscoveredServer(active, previousUrl, verified)
        return if (preferences.current() == null && preferences.automatic()) verified else preferences.url()
    }
    @Synchronized fun repository(): Repository? {
        val session=preferences.current() ?: return null
        if(cached?.session?.userId != session.userId || cached?.serverUrl != preferences.url()) cached=Repository(this,session)
        return cached
    }
    @Synchronized fun deleteLocalAccountData() {
        val key=preferences.storageKey()
        cached?.database?.close()
        cached=null
        deleteDatabase("tracker-$key.db")
        File(filesDir,"attachments/$key").deleteRecursively()
        preferences.clearAccount()
    }
}
class SyncWorker(context: Context,params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        val app=applicationContext as HabitApplication
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.resolveAccountServer()
            app.repository()?.synchronize()
        }
        Result.success()
    } catch(e: NetworkFailure) { if(e.status==401) Result.failure() else Result.retry() }
      catch(_: java.io.IOException) { Result.retry() }
      catch(_: IllegalArgumentException) { Result.failure() }
}
