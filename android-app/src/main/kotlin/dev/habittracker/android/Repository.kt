package dev.habittracker.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
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
    private val api get() = Api(serverUrl) {
        val active=app.preferences.current()
        if(active?.userId!=session.userId || app.preferences.url()!=serverUrl) throw NetworkFailure(401,"Аккаунт изменился; синхронизация остановлена")
        active.token
    }
    suspend fun save(content: Content,id: String=UUID.randomUUID().toString(),deleted: Boolean=false) {
        content.validate()
        database.withTransaction {
            require(dao.conflict(id)==null) { "Сначала выбери версию в конфликте" }
            val row = dao.item(id)
            val remote = row?.serverJson?.let { WireJson.decodeFromString<Item>(it) }
            val mutation = Mutation(UUID.randomUUID().toString(),id,remote?.version ?: 0,content,deleted)
            val local = Item(id,remote?.version ?: 0,content,deleted,Instant.now().toString(),remote?.sequence ?: 0)
            dao.item(LocalItem(id,WireJson.encodeToString(local),row?.serverJson))
            dao.pending(PendingMutation(id,WireJson.encodeToString(mutation)))
        }
        scheduleSync(); Reminders.reschedule(app,this)
    }
    fun scheduleSync() {
        if(!autoSchedule) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(app).enqueueUniqueWork("sync-$key",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build())
    }
    suspend fun resolve(conflict: Conflict,keepLocal: Boolean) {
        database.withTransaction {
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
        scheduleSync(); Reminders.reschedule(app,this)
    }
    suspend fun synchronize() = mutex.withLock {
        var rounds=0
        do {
            val pending = dao.pending()
            val sent = pending.map { WireJson.decodeFromString<Mutation>(it.operationJson) }
            val cursor = dao.metadata("cursor")?.toLong() ?: 0
            val response = api.post<SyncRequest,SyncResponse>("/api/v1/sync",SyncRequest(sent,cursor))
            database.withTransaction {
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
                        MutationStatus.REJECTED -> if(current != null && queued?.operationId == operation.operationId) dao.pending(current.copy(error=result.message ?: "Операция отклонена"))
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
        Reminders.reschedule(app,this)
    }
    suspend fun attach(noteId: String,uri: Uri) {
        val item = dao.item(noteId)?.let { WireJson.decodeFromString<Item>(it.localJson) } ?: error("Заметка не найдена")
        val note = item.content as? NoteContent ?: error("Это не заметка")
        var name = "Файл"
        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) name=it.getString(0) }
        val id=UUID.randomUUID().toString(); val dir=File(app.filesDir,"attachments/$key").apply { mkdirs() }; val target=File(dir,id)
        val mime=app.contentResolver.getType(uri) ?: "application/octet-stream"
        try {
            app.contentResolver.openInputStream(uri)!!.use { source -> target.outputStream().use { output ->
                val buffer=ByteArray(8192); var total=0L
                while(true) { val count=source.read(buffer); if(count<0) break; total+=count; require(total<=20*1024*1024) { "Файл больше 20 МБ" }; output.write(buffer,0,count) }
            } }
            dao.file(LocalFile(id,noteId,name,mime,target.length(),target.absolutePath,false))
            save(note.copy(attachmentIds=note.attachmentIds+id),noteId)
        } catch(e: Throwable) { target.delete(); dao.removeFile(id); throw e }
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
    @Volatile private var cached: Repository? = null
    override fun onCreate() { super.onCreate(); preferences=SessionPreferences(this) }
    @Synchronized fun repository(): Repository? {
        val session=preferences.current() ?: return null
        if(cached?.session?.userId != session.userId || cached?.serverUrl != preferences.url()) cached=Repository(this,session)
        return cached
    }
}
class SyncWorker(context: Context,params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as HabitApplication).repository()?.synchronize(); Result.success()
    } catch(e: NetworkFailure) { if(e.status==401) Result.failure() else Result.retry() }
      catch(_: java.io.IOException) { Result.retry() }
      catch(_: IllegalArgumentException) { Result.failure() }
}
