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
    init { if(session.value!=null) { periodic(); synchronize() } }
    private fun action(block: suspend ()->Unit) { viewModelScope.launch(Dispatchers.IO) { try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { message.value=e.message ?: "Не удалось выполнить действие" } } }
    fun changeServer(url: String,password: String,after: ()->Unit) {
        busy.value=true
        action {
            try {
                val normalized=url.trim().trimEnd('/')
                val email=requireNotNull(session.value).email
                val result=Api(normalized).post<Credentials,Session>("/api/v1/auth/login",Credentials(email,password))
                app.preferences.reconnect(result,normalized)
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
                val normalized=url.trim().trimEnd('/')
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
    fun open(file: LocalFile,onFile: (java.io.File)->Unit) = action {
        val local=repository.value!!.cachedFile(file)
        withContext(Dispatchers.Main) { onFile(local) }
    }
    fun logout() = action {
        val token=session.value?.token
        if(token!=null) runCatching { Api(app.preferences.url()) { token }.request("/api/v1/logout","POST") }
        app.preferences.clear(); Reminders.clear(app); WorkManager.getInstance(app).cancelUniqueWork("account-sync")
        session.value=null; repository.value=null
    }
}
