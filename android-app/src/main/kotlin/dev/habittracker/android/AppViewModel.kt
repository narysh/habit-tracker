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
    val items=repository.flatMapLatest { it?.items ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyList())
    val conflicts=repository.flatMapLatest { it?.conflicts ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyList())
    val files=repository.flatMapLatest { it?.files ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyList())
    val errors=repository.flatMapLatest { it?.errors ?: flowOf(emptyList()) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyList())
    val pendingCount=repository.flatMapLatest { it?.dao?.observePendingCount() ?: flowOf(0) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),0)
    val message=MutableStateFlow<String?>(null)
    val authError=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    val syncStatus=MutableStateFlow("Подключение…")
    val serverUrl=MutableStateFlow(app.preferences.url())
    val providers=MutableStateFlow<List<SocialProviderInfo>>(emptyList())
    val socialBusy=MutableStateFlow(false)
    val backupPreview=MutableStateFlow<BackupPreview?>(null)
    val reminderSettings=MutableStateFlow(Reminders.settings(app))
    private var socialJob: Job?=null
    init { if(session.value!=null) { periodic(); synchronize() } }
    suspend fun findServer() {
        try { prepareConnection() }
        catch(e: java.io.IOException) { syncStatus.value="Нет связи с сервером. Повторим автоматически." }
        catch(e: IllegalArgumentException) { syncStatus.value=e.message ?: "Ошибка адреса сервера" }
    }
    private suspend fun prepareConnection(refreshProviders: Boolean = true): String = withContext(Dispatchers.IO) {
        val url=app.resolveAccountServer() ?: serverUrl.value
        if(url.isNotBlank()) {
            repository.value=app.repository()
            serverUrl.value=url
            if(refreshProviders) {
                val account=app.preferences.current()
                providers.value=Api(url) { account?.let { app.preferences.tokenFor(it.userId,url) } }.get("/api/v1/auth/social/providers")
            }
        }
        url
    }
    fun automaticConnection(enabled: Boolean) {
        if (!enabled || app.preferences.current() == null) {
            app.preferences.automatic(enabled)
            action { prepareConnection() }
        } else {
            action {
                app.preferences.automatic(true, app.discovery.resolve())
                prepareConnection()
            }
        }
    }
    fun manualAddress(url: String) { serverUrl.value=url }
    fun socialLogin(provider: String,link: Boolean=false) {
        if(socialBusy.value) return
        socialBusy.value=true
        socialJob=viewModelScope.launch(Dispatchers.IO) {
            try {
                val automatic=app.preferences.automatic()
                val linkedUserId=if(link) requireNotNull(session.value).userId else null
                val url=prepareConnection()
                val api=Api(url) { linkedUserId?.let { app.preferences.tokenFor(it,url) } }
                val verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
                val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
                val attempt=api.post<SocialStart,SocialAttempt>("/api/v1/auth/social/start",SocialStart(provider,challenge,link))
                withContext(Dispatchers.Main) { app.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(attempt.authorizationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                repeat(150) {
                    delay(2000)
                    val result=api.post<SocialPoll,SocialResult>("/api/v1/auth/social/poll",SocialPoll(attempt.id,verifier))
                    currentCoroutineContext().ensureActive()
                    when(result.status) {
                        "DONE"->{val signedIn=requireNotNull(result.session);app.preferences.save(signedIn,url,automatic=automatic);repository.value=app.repository();session.value=signedIn;periodic();refreshFromServer();return@launch}
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
    suspend fun chatGptConnection(account: Session): ChatGptConnection = withContext(Dispatchers.IO) {
        chatGptLinkApi(account).get("/api/v1/account/chatgpt")
    }
    suspend fun disconnectChatGpt(account: Session) = withContext(Dispatchers.IO) {
        chatGptLinkApi(account).request("/api/v1/account/chatgpt", "DELETE")
        Unit
    }
    suspend fun previewChatGptLink(code: String, account: Session): ChatGptLinkPreview = withContext(Dispatchers.IO) {
        chatGptLinkApi(account).post("/api/v1/account/chatgpt/preview", ChatGptLinkRequest(code))
    }
    suspend fun approveChatGptLink(code: String, account: Session) = withContext(Dispatchers.IO) {
        val body = WireJson.encodeToString(ChatGptLinkRequest(code)).encodeToByteArray()
        chatGptLinkApi(account).request("/api/v1/account/chatgpt/approve", "POST", body)
        Unit
    }
    private fun chatGptLinkApi(account: Session): Api {
        val endpoint = app.preferences.url()
        require(Uri.parse(endpoint).scheme == "https") { "Для подключения ChatGPT нужен HTTPS-сервер Habit Tracker" }
        return Api(endpoint) {
            check(app.preferences.tokenFor(account.userId,endpoint) == account.token) { "Аккаунт или сервер изменился. Начни подключение заново." }
            account.token
        }
    }
    private fun action(block: suspend ()->Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { message.value=e.message ?: "Не удалось выполнить действие" } } }
    fun changeServer(url: String,password: String,after: ()->Unit) {
        busy.value=true
        action {
            try {
                val normalized=url.trim().trimEnd('/')
                val email=requireNotNull(session.value).email
                val result=Api(normalized).post<Credentials,Session>("/api/v1/auth/login",Credentials(email,password))
                app.preferences.reconnect(result,normalized)
                serverUrl.value=normalized
                repository.value=app.repository(); session.value=result
                periodic(); refreshFromServer()
                withContext(Dispatchers.Main) { after() }
            } finally { busy.value=false }
        }
    }
    fun login(url: String,email: String,password: String,register: Boolean) {
        busy.value=true
        authError.value=null
        action {
            try {
                val automatic=app.preferences.automatic()
                val normalized=(if(automatic) prepareConnection() else url).trim().trimEnd('/')
                val result=Api(normalized).post<Credentials,Session>("/api/v1/auth/${if(register) "register" else "login"}",Credentials(email.trim(),password))
                UUID.fromString(result.userId)
                app.preferences.save(result,normalized,automatic=automatic)
                repository.value=app.repository(); session.value=result; periodic(); synchronize()
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                authError.value=e.message ?: "Не удалось войти в аккаунт"
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
            prepareConnection(refreshProviders=false)
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
    fun acceptShare(draft: SharedDraft,asTask: Boolean,after: ()->Unit = {})=action {
        busy.value=true
        try {
            val repo=repository.value ?: error("Сначала войди в аккаунт")
            if(repo.dao.item(draft.id)==null) {
                val content = draft.task ?: draft.habit ?: if(asTask) TaskContent(draft.title.trim(),description=draft.text,zoneId=ZoneId.systemDefault().id) else draft.note ?: NoteContent(draft.title.trim(),draft.text)
                repo.save(content,draft.id)
            }
            // Keep attachment retries independent from note creation: a failed copy must not
            // leave a record that causes the next attempt to skip the remaining files.
            val existing=repo.dao.item(draft.id)
            if(draft.files.isNotEmpty()) {
                val item=existing?.let { WireJson.decodeFromString<Item>(it.localJson) }
                require(item!=null && !item.deleted && item.content is NoteContent) { "Заметку с вложениями не удалось найти. Черновик сохранён для повтора." }
            }
            draft.files.forEachIndexed {index,file->val id=UUID.nameUUIDFromBytes("share:${draft.id}:$index".toByteArray()).toString();repo.attach(draft.id,Uri.fromFile(java.io.File(file.path)),file.name,file.mime,id)}
            app.shareInbox.remove(draft.id)
            message.value=when { draft.task!=null || asTask -> "Задача добавлена"; draft.habit!=null -> "Привычка добавлена"; else -> "Заметка добавлена" }
            withContext(Dispatchers.Main) { after() }
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
    fun changePassword(currentPassword: String,newPassword: String,after: ()->Unit) = action {
        busy.value=true
        try {
            val account=requireNotNull(session.value) { "Сначала войди в аккаунт" }
            Api(app.preferences.url()) { account.token }.request(
                "/api/v1/account/password", "POST",
                WireJson.encodeToString(PasswordChangeRequest(currentPassword,newPassword)).toByteArray()
            )
            message.value="Пароль изменён. Другие сеансы и подключение ChatGPT завершены."
            withContext(Dispatchers.Main) { after() }
        } finally { busy.value=false }
    }
    fun deleteAccount() = action {
        val account=requireNotNull(session.value) { "Сначала войди в аккаунт" }
        busy.value=true
        try {
            Api(app.preferences.url()) { account.token }.request(
                "/api/v1/account/delete", "POST",
                WireJson.encodeToString(AccountDeletionRequest("DELETE")).toByteArray()
            )
            cancelSocialLogin()
            WorkManager.getInstance(app).cancelAllWork()
            val localData=app.detachLocalAccountData()
            withContext(Dispatchers.Main) {
                session.value=null
                repository.value=null
                kotlinx.coroutines.yield()
            }
            delay(120)
            app.eraseLocalAccountData(localData)
            Reminders.clear(app)
            TrackerWidget.updateAll(app)
            message.value="Аккаунт и облачные данные удалены"
        } finally { busy.value=false }
    }
}

@kotlinx.serialization.Serializable
private data class AccountDeletionRequest(val confirmation: String)
