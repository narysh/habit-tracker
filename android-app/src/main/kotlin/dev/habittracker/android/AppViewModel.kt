package dev.habittracker.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import dev.habittracker.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.content.Intent
import java.security.SecureRandom
import java.util.Base64
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    val app=application as HabitApplication
    private val repository=MutableStateFlow(app.repository())
    val session=MutableStateFlow(app.preferences.current())
    val items=repository.flatMapLatest { it?.items ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val conflicts=repository.flatMapLatest { it?.conflicts ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val files=repository.flatMapLatest { it?.files ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val errors=repository.flatMapLatest { it?.errors ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val pendingCount=repository.flatMapLatest { it?.dao?.observePendingCount() ?: flowOf(0) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val message=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    val syncStatus=MutableStateFlow("Подключение…")
    val serverUrl=MutableStateFlow(app.preferences.url())
    val providers=MutableStateFlow<List<SocialProviderInfo>>(emptyList())
    val socialBusy=MutableStateFlow(false)
    val backupPreview=MutableStateFlow<BackupPreview?>(null)
    val reminderSettings=MutableStateFlow(Reminders.settings(app))
    private var socialJob: Job?=null
    private val discovery=ServerDiscovery(app)
    init { if(session.value!=null) { periodic(); synchronize() } }
    suspend fun findServer() {
        try { prepareConnection() }
        catch(e: java.io.IOException) { syncStatus.value="Нет связи с сервером. Повторим автоматически." }
        catch(e: IllegalArgumentException) { syncStatus.value=e.message ?: "Ошибка адреса сервера" }
    }
    private suspend fun prepareConnection(): String = withContext(Dispatchers.IO) {
        val url=if(app.preferences.automatic()) discovery.resolve() else serverUrl.value
        if(url.isNotBlank()) {
            val active=app.preferences.current()
            if(active!=null && app.preferences.url()!=url) {
                app.preferences.save(active,url,app.preferences.storageKey());repository.value=app.repository()
            }
            serverUrl.value=url
            providers.value=Api(url) { active?.token }.get("/api/v1/auth/social/providers")
        }
        url
    }
    fun automaticConnection(enabled: Boolean) { app.preferences.automatic(enabled);action { prepareConnection() } }
    fun manualAddress(url: String) { serverUrl.value=url }
    fun socialLogin(provider: String,link: Boolean=false) {
        if(socialBusy.value) return
        socialBusy.value=true
        socialJob=viewModelScope.launch(Dispatchers.IO) {
            try {
                val url=prepareConnection()
                val api=Api(url) { if(link) session.value?.token else null }
                val verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
                val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                val attempt=api.post<SocialStart,SocialAttempt>("/api/v1/auth/social/start",SocialStart(provider,challenge,link))
                withContext(Dispatchers.Main) { app.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(attempt.authorizationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                repeat(150) {
                    delay(2000)
                    val result=api.post<SocialPoll,SocialResult>("/api/v1/auth/social/poll",SocialPoll(attempt.id,verifier))
                    currentCoroutineContext().ensureActive()
                    when(result.status) {
                        "DONE"->{val signedIn=requireNotNull(result.session);app.preferences.save(signedIn,url);repository.value=app.repository();session.value=signedIn;periodic();refreshFromServer();return@launch}
                        "LINKED"->{prepareConnection();message.value="Способ входа привязан";return@launch}
                        "FAILED"->throw IllegalStateException(result.message ?: "Вход отменён")
                    }
                }
                message.value="Время ожидания входа истекло. Попробуй снова."
            } catch(e: CancellationException) { throw e }
              catch(e: Exception) { message.value=e.message ?: "Не удалось войти" }
            finally {socialBusy.value=false}
        }
    }
    fun cancelSocialLogin() { socialJob?.cancel();socialBusy.value=false }
    private fun action(block: suspend ()->Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { message.value=e.message ?: "Не удалось выполнить действие" } } }
    fun changeServer(url: String,password: String,after: ()->Unit) {
        busy.value=true
        action {
            try {
                val normalized=url.trim().trimEnd('/')
                val email=requireNotNull(session.value).email
                val result=Api(normalized).post<Credentials,Session>("/api/v1/auth/login",Credentials(email,password))
                app.preferences.reconnect(result,normalized)
                app.preferences.automatic(false);serverUrl.value=normalized
                repository.value=app.repository(); session.value=result
                periodic(); refreshFromServer()
                withContext(Dispatchers.Main) { after() }
            } finally { busy.value=false }
        }
    }
    fun login(url: String,email: String,password: String,register: Boolean) {
        busy.value=true
        action {
            try {
                val normalized=(if(app.preferences.automatic()) prepareConnection() else url).trim().trimEnd('/')
                val result=Api(normalized).post<Credentials,Session>("/api/v1/auth/${if(register) "register" else "login"}",Credentials(email.trim(),password))
                UUID.fromString(result.userId)
                app.preferences.save(result,normalized)
                repository.value=app.repository(); session.value=result; periodic(); synchronize()
            } finally { busy.value=false }
        }
    }
    private fun periodic() {
        val work=PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork("account-sync",ExistingPeriodicWorkPolicy.UPDATE,work)
    }
    fun synchronize() = action {
        busy.value=true
        try { refreshFromServer() }
        finally { busy.value=false }
    }
    suspend fun refreshFromServer() = withContext(Dispatchers.IO) {
        try {
            prepareConnection()
            repository.value?.synchronize()
            syncStatus.value="Синхронизировано · ${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}"
        } catch(e: NetworkFailure) {
            syncStatus.value=if(e.status==401) "Нужно войти снова — данные на телефоне сохранены" else "Ошибка сервера: ${e.status}"
        } catch (_: java.io.IOException) { syncStatus.value="Нет связи — изменения сохранены на телефоне" }
    }
    fun save(content: Content,id: String=UUID.randomUUID().toString(),after: ()->Unit = {}) = action {
        repository.value!!.save(content,id)
        withContext(Dispatchers.Main) { after() }
    }
    fun delete(item: Item) = action { repository.value!!.save(item.content,item.id,true) }
    fun complete(item: Item,completed: Boolean) { (item.content as? TaskContent)?.let { save(it.copy(completed=completed),item.id) } }
    fun mark(item: Item,date: LocalDate,completed: Boolean) { save(HabitMarkContent(item.id,date.toString(),completed),markId(item.id,date)) }
    fun resolve(conflict: Conflict,keepLocal: Boolean) = action { repository.value!!.resolve(conflict,keepLocal) }
    fun attach(noteId: String,uri: Uri) = action { repository.value!!.attach(noteId,uri) }
    fun applyPlan(item: Item,after: ()->Unit) = action {
        busy.value=true
        try {repository.value!!.applyPlan(item);message.value="План применён";withContext(Dispatchers.Main){after()}} finally {busy.value=false}
    }
    fun saveReminderSettings(value: ReminderSettings,after: ()->Unit)=action {
        Reminders.saveSettings(app,value);reminderSettings.value=value;repository.value?.let {Reminders.reschedule(app,it)}
        withContext(Dispatchers.Main){after()}
    }
    fun exportBackup(uri: Uri)=action {busy.value=true;try{Backups.export(repository.value!!,uri);message.value="Резервная копия сохранена"}finally{busy.value=false}}
    fun inspectBackup(uri: Uri)=action {busy.value=true;try{backupPreview.value?.archive?.delete();backupPreview.value=null;backupPreview.value=Backups.inspect(app,uri)}finally{busy.value=false}}
    fun cancelBackup(){backupPreview.value?.archive?.delete();backupPreview.value=null}
    fun restoreBackup()=action {busy.value=true;try{val preview=requireNotNull(backupPreview.value);val count=Backups.restore(repository.value!!,preview);cancelBackup();message.value="Восстановлено записей: $count"}finally{busy.value=false}}
    fun acceptShare(draft: SharedDraft,asTask: Boolean)=action {
        busy.value=true
        try {
            val repo=repository.value ?: error("Сначала войди в аккаунт")
            if(repo.dao.item(draft.id)==null)repo.save(if(asTask)TaskContent(draft.title.trim(),description=draft.text,zoneId=ZoneId.systemDefault().id)else NoteContent(draft.title.trim(),draft.text),draft.id)
            draft.files.forEachIndexed {index,file->val id=UUID.nameUUIDFromBytes("share:${draft.id}:$index".toByteArray()).toString();repo.attach(draft.id,Uri.fromFile(java.io.File(file.path)),file.name,file.mime,id)}
            app.shareInbox.remove(draft.id);message.value="Сохранено из другого приложения"
        } finally {busy.value=false}
    }
    fun open(file: LocalFile,onFile: (java.io.File)->Unit) = action {
        val local=repository.value!!.cachedFile(file)
        withContext(Dispatchers.Main) { onFile(local) }
    }
    fun logout() = action {
        cancelSocialLogin()
        val token=session.value?.token
        if(token!=null) runCatching { Api(app.preferences.url()) { token }.request("/api/v1/logout","POST") }
        app.preferences.clear(); Reminders.clear(app); WorkManager.getInstance(app).cancelUniqueWork("account-sync")
        session.value=null; repository.value=null
        TrackerWidget.updateAll(app)
    }
}
