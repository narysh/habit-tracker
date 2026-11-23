package dev.habittracker.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.habittracker.shared.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

private val mobileChatGptInstructions = """Когда я прошу создать задачу в Habit Tracker, добавляй Markdown-ссылку [Открыть задачу](https://narysh.github.io/habit-tracker/task/#title=...). Передавай только названные мной поля: title, description, date (ГГГГ-ММ-ДД), time (ЧЧ:ММ только вместе с датой). Когда я прошу сохранить заметку, добавляй ссылку [Открыть заметку](https://narysh.github.io/habit-tracker/task/#kind=note&title=...&text=...). Кодируй значения URL-кодированием UTF-8. Не придумывай сроки. Напиши, что запись сохранится только после того, как я проверю её и нажму «Сохранить» в Habit Tracker."""

class MainActivity : ComponentActivity() {
    private fun receive(intent: Intent) {
        val app=application as HabitApplication
        if(intent.getBooleanExtra("quick",false))app.shortcut.value=true
        if(intent.action==Intent.ACTION_SEND || intent.action==Intent.ACTION_SEND_MULTIPLE) lifecycleScope.launch(Dispatchers.IO) {try {app.shareInbox.receive(intent)}catch(e:Exception){app.shareInbox.error.value=e.message ?: "Не удалось принять данные"}}
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let { link ->
            lifecycleScope.launch(Dispatchers.IO) {
                try { app.shareInbox.receiveDraftLink(link) }
                catch (e: Exception) { app.shareInbox.error.value = e.message ?: "Не удалось открыть черновик задачи" }
            }
        }
    }
    override fun onNewIntent(intent: Intent){super.onNewIntent(intent);setIntent(intent);receive(intent)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if(savedInstanceState==null)receive(intent)
        setContent {
            val colors=if(isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFF8ED5B0),primaryContainer=Color(0xFF155038),onPrimaryContainer=Color(0xFFB6F2D1),secondary=Color(0xFFB5CCBE),secondaryContainer=Color(0xFF354B3E),onSecondaryContainer=Color(0xFFD4E8D8),surfaceContainer=Color(0xFF1B2620),surfaceContainerHighest=Color(0xFF2D3A32)) else lightColorScheme(primary=Color(0xFF24694D),primaryContainer=Color(0xFFD5EDDE),onPrimaryContainer=Color(0xFF123D29),secondary=Color(0xFF546B5F),secondaryContainer=Color(0xFFE0ECE0),onSecondaryContainer=Color(0xFF284533),background=Color(0xFFF7F9F5),surface=Color(0xFFF7F9F5),surfaceContainer=Color(0xFFECF2EA),surfaceContainerHighest=Color(0xFFE5EEE5))
            MaterialTheme(colorScheme=colors) { TrackerApp() }
        }
    }
}
private val dateFormat=DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
private fun localDate(value: String?): String = value?.let { LocalDate.parse(it).format(dateFormat) }.orEmpty()
private fun parseDate(value: String): String? = value.trim().takeIf(String::isNotEmpty)?.let { LocalDate.parse(it,dateFormat).toString() }
private fun countLabel(count: Int,one: String,few: String,many: String): String {
    val word=if(count%100 in 11..14) many else when(count%10) { 1->one; 2,3,4->few; else->many }
    return "$count $word"
}
private fun Content.description(): String = when(this) {
    is TaskContent -> listOf(title,description,dueDate?.let { "Срок: ${localDate(it)} ${dueTime.orEmpty()}" }.orEmpty(),if(completed) "Выполнено" else "Не выполнено").filter(String::isNotBlank).joinToString("\n")
    is NoteContent -> "$title\n$text\nВложений: ${attachmentIds.size}"
    is HabitContent -> "$title\n$description\n${if(mode==HabitMode.WEEKLY_TARGET) "$targetPerWeek дней в неделю" else weekdays.sorted().joinToString { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it-1] }}\nНачало: ${localDate(startsOn)}\nНапоминание: ${reminderTime ?: "выключено"}"
    is HabitMarkContent -> "${localDate(date)}: ${if(completed) "выполнено" else "не выполнено"}"
    is ProjectContent -> "$title\n$description"
    is WeekPlanContent -> "$title\n$summary"
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TrackerApp(vm: AppViewModel=viewModel()) {
    val session by vm.session.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle,session?.userId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) { if(session!=null) vm.refreshFromServer() else vm.findServer(); delay(10_000) }
        }
    }
    val records by vm.items.collectAsStateWithLifecycle()
    val conflicts by vm.conflicts.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingCount.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value=null } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var confirmAccountDeletion by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf(false) }
    var socialSettings by remember { mutableStateOf(false) }
    var chatGptLink by remember(session?.userId) { mutableStateOf(false) }
    var mobileChatSetup by remember { mutableStateOf(false) }
    var feature by remember { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var selectedPlan by remember { mutableStateOf<Item?>(null) }
    var editor by remember { mutableStateOf<Pair<String,Content>?>(null) }
    var deleting by remember { mutableStateOf<Item?>(null) }
    var history by remember { mutableStateOf<Item?>(null) }
    var chooser by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val now by produceState(LocalDate.now()) { while(true) { value=LocalDate.now(); kotlinx.coroutines.delay(30000) } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(session?.userId) { if(session!=null && Build.VERSION.SDK_INT>=33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val incoming by vm.app.shareInbox.drafts.collectAsStateWithLifecycle()
    val shareError by vm.app.shareInbox.error.collectAsStateWithLifecycle()
    val shortcut by vm.app.shortcut.collectAsStateWithLifecycle()
    val backupPreview by vm.backupPreview.collectAsStateWithLifecycle()
    val reminderOptions by vm.reminderSettings.collectAsStateWithLifecycle()
    LaunchedEffect(shareError){shareError?.let {vm.message.value=it;vm.app.shareInbox.error.value=null}}
    LaunchedEffect(shortcut,session?.userId){if(shortcut && session!=null){feature="quick";vm.app.shortcut.value=false}}
    val exportPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri->uri?.let {vm.exportBackup(it)}}
    val importPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let {vm.inspectBackup(it)}}
    fun openRecord(item: Item){feature=null;if(item.content is WeekPlanContent)selectedPlan=item else editor=item.id to item.content}
    val visible=records.filterNot { it.deleted }
    val tasks=visible.filter { it.content is TaskContent }.sortedWith(compareBy<Item> { (it.content as TaskContent).completed }.thenBy { (it.content as TaskContent).dueDate ?: "9999" })
    val habits=visible.filter { it.content is HabitContent }
    fun newItem(kind: Int) {
        editor=UUID.randomUUID().toString() to when(kind) {
            1 -> TaskContent("",zoneId=ZoneId.systemDefault().id)
            2 -> HabitContent("",startsOn=now.toString(),zoneId=ZoneId.systemDefault().id)
            else -> NoteContent("")
        }
    }
    Scaffold(
        snackbarHost={ SnackbarHost(snackbar) },
        topBar={ if(session!=null) TopAppBar(title={ Column { Text(if(tab==0) "Сегодня" else listOf("Сегодня","Задачи","Привычки","Заметки")[tab],fontWeight=FontWeight.Bold); Text(now.format(DateTimeFormatter.ofPattern("d MMMM, EEEE",Locale.forLanguageTag("ru"))),style=MaterialTheme.typography.labelMedium) } },actions={ IconButton(onClick={vm.synchronize()},enabled=!busy) { Icon(Icons.Outlined.Sync,"Синхронизировать") }; IconButton(onClick={more=true}) { Icon(Icons.Outlined.MoreVert,"Возможности") }; DropdownMenu(more,{more=false}) { listOf("quick" to "Быстрый ввод","week" to "План недели","search" to "Поиск и фильтры","projects" to "Проекты и цели","stats" to "Статистика привычек","review" to "Разбор недели с AI","backup" to "Резервные копии","reminders" to "Напоминания").forEach { (id,label)->DropdownMenuItem(text={Text(label)},onClick={feature=id;more=false}) } }; IconButton(onClick={settings=true}) { Icon(Icons.Outlined.Settings,"Настройки") } }) },
        bottomBar={ if(session!=null) NavigationBar { listOf("Сегодня","Задачи","Привычки","Заметки").forEachIndexed { index,label ->
            NavigationBarItem(selected=tab==index,onClick={tab=index},icon={ Icon(listOf(Icons.Outlined.Today,Icons.Outlined.Checklist,Icons.Outlined.Loop,Icons.Outlined.Notes)[index],label) },label={Text(label)})
        } } },
        floatingActionButton={ if(session!=null) FloatingActionButton(onClick={if(tab==0) chooser=true else newItem(tab)}) { Icon(Icons.Outlined.Add,"Добавить") } },
    ) { padding ->
        if(session==null) LoginScreen(vm,Modifier.padding(padding)) else LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=90.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text(syncStatus + if(pendingCount>0) " · ждут отправки: $pendingCount" else "",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if(conflicts.isNotEmpty()) item {
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.padding(16.dp)) {
                    Text("Нужно выбрать версию: ${conflicts.size}",fontWeight=FontWeight.Bold)
                    Text("Изменения на телефоне и сервере сохранены. Выбери, какие оставить.")
                } }
            }
            items(conflicts,key={"conflict-${it.itemId}"}) { conflict -> ConflictCard(conflict) { vm.resolve(conflict,it) } }
            if(errors.isNotEmpty()) item { Text("Не отправлено: ${errors.size}. ${errors.first().error}",color=MaterialTheme.colorScheme.error) }
            if(tab==0) {
                item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp)) { Text("Твой день — в твоём ритме",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text("${countLabel(tasks.count { !(it.content as TaskContent).completed },"задача","задачи","задач")} в работе · ${countLabel(habits.size,"привычка","привычки","привычек")}") }
                } }
                item { Row { TextButton(onClick={feature="quick"}) {Text("Быстрый ввод")}; TextButton(onClick={feature="week"}) {Text("План недели")} } }
                item { SectionTitle("На сегодня") }
                val todayTasks=tasks.filter { val task=it.content as TaskContent; !task.completed && (task.dueDate==null || LocalDate.parse(task.dueDate)<=now) }
                if(todayTasks.isEmpty()) item { EmptyState("Нет незавершённых задач на сегодня") }
                items(todayTasks,key={it.id}) { item -> TaskCard(item,{vm.complete(item,it)},{editor=item.id to item.content},{deleting=item}) }
                item { SectionTitle("Привычки") }
                if(habits.none { (it.content as HabitContent).isDue(now) }) item {EmptyState("Нет привычек на сегодня")}
                items(habits.filter { (it.content as HabitContent).isDue(now) },key={it.id}) { item -> HabitCard(item,visible,now,{vm.mark(item,now,it)},{editor=item.id to item.content},{deleting=item},{history=item}) }
            }
            if(tab==1) {
                if(tasks.isEmpty()) item { EmptyState("Пока нет задач. Добавь первую с помощью +") }
                items(tasks,key={it.id}) { item -> TaskCard(item,{vm.complete(item,it)},{editor=item.id to item.content},{deleting=item}) }
            }
            if(tab==2) {
                if(habits.isEmpty()) item { EmptyState("Начни с одной небольшой привычки") }
                items(habits,key={it.id}) { item -> HabitCard(item,visible,now,{vm.mark(item,now,it)},{editor=item.id to item.content},{deleting=item},{history=item}) }
            }
            if(tab==3) {
                item { OutlinedTextField(query,{query=it},label={Text("Найти заметку")},singleLine=true,modifier=Modifier.fillMaxWidth(),leadingIcon={Icon(Icons.Outlined.Search,null)}) }
                val notes=visible.filter { val note=it.content as? NoteContent; note!=null && (note.title.contains(query,true)||note.text.contains(query,true)) }.sortedByDescending { it.updatedAt }
                if(notes.isEmpty()) item { EmptyState(if(query.isBlank()) "Место для мыслей, идей и файлов" else "Ничего не найдено") }
                items(notes,key={it.id}) { item ->
                    val note=item.content as NoteContent
                    Card(onClick={editor=item.id to note}) { Column(Modifier.fillMaxWidth().padding(18.dp)) { Text(note.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold); if(note.text.isNotBlank()) Text(note.text,maxLines=4,modifier=Modifier.padding(top=8.dp)); if(note.attachmentIds.isNotEmpty()) Text("Вложений: ${note.attachmentIds.size}",style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(top=8.dp)); Row { TextButton(onClick={editor=item.id to note}) { Text("Открыть") }; TextButton(onClick={deleting=item}) { Text("Удалить") } } } }
                }
            }
        }
    }
    if(session!=null) when(feature) {
        "quick"->QuickEntryDialog({feature=null}){draft->feature=null;editor=UUID.randomUUID().toString() to draft}
        "projects"->ProjectsDialog(records,{feature=null},{openRecord(it)}){goal->feature=null;editor=UUID.randomUUID().toString() to ProjectContent("",isGoal=goal)}
        "search"->SearchDialog(records,{feature=null},{openRecord(it)})
        "week"->WeekDialog(records,{feature=null},{openRecord(it)}){item,date->val task=item.content as TaskContent;vm.save(task.copy(dueDate=date.toString()),item.id)}
        "stats"->StatisticsDialog(records){feature=null}
        "review"->ReviewDialog(records,{feature=null}){selectedPlan=it}
        "reminders"->ReminderSettingsDialog(reminderOptions,{feature=null}){value->vm.saveReminderSettings(value){feature=null}}
        "backup"->BackupDialog(backupPreview,busy,{feature=null},{exportPicker.launch("habit-tracker-${LocalDate.now()}.zip")},{importPicker.launch(arrayOf("application/zip","application/octet-stream","application/x-zip-compressed"))},{vm.restoreBackup()},{vm.cancelBackup()})
    }
    selectedPlan?.let {snapshot->val current=records.firstOrNull {it.id==snapshot.id} ?: snapshot;PlanDialog(current,records,busy,{selectedPlan=null}){vm.applyPlan(current){selectedPlan=null}}}
    if (session != null && editor == null && selectedPlan == null && feature == null && !settings && !connection && !chatGptLink && !socialSettings) incoming.firstOrNull()?.let { draft ->
        val content = draft.task ?: draft.note
        if (content == null) ShareDraftDialog(draft, busy, { value, asTask -> vm.acceptShare(value, asTask) }, { vm.app.shareInbox.remove(draft.id) })
        else Editor(draft.id, content, files, vm, { vm.app.shareInbox.remove(draft.id) }, { openRecord(it) })
    }
    if(chooser) AlertDialog(onDismissRequest={chooser=false},title={Text("Добавить")},text={Column { listOf("Задачу","Привычку","Заметку").forEachIndexed { index,label -> TextButton(onClick={chooser=false;newItem(index+1)}) { Text(label) } } }},confirmButton={TextButton(onClick={chooser=false}) {Text("Закрыть")}})
    editor?.let { (id,content) -> Editor(id,content,files,vm,{editor=null},{openRecord(it)}) }
    deleting?.let { item -> AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить «${item.content.title}»?")},text={Text("Удаление будет синхронизировано с аккаунтом.")},confirmButton={TextButton(onClick={vm.delete(item);deleting=null}) {Text("Удалить")}},dismissButton={TextButton(onClick={deleting=null}) {Text("Отмена")}}) }
    history?.let { item -> HabitHistory(item,visible,now,{date,checked -> vm.mark(item,date,checked)},{history=null}) }
    if(socialSettings) SocialAccountDialog(vm) { socialSettings=false }
    if(chatGptLink) session?.let { account -> ChatGptLinkDialog(vm, account) { chatGptLink=false } }
    if(mobileChatSetup) MobileChatGptSetupDialog { mobileChatSetup=false }
    if(connection) ConnectionDialog(vm) { connection=false }
    if(confirmAccountDeletion) AlertDialog(
        onDismissRequest={if(!busy)confirmAccountDeletion=false},
        title={Text("Удалить аккаунт безвозвратно?",color=MaterialTheme.colorScheme.error)},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Будут удалены аккаунт, все задачи, привычки, заметки и вложения в облаке, а также локальные данные этого аккаунта на телефоне. Восстановить их нельзя.")
            Text("Для защиты от случайного удаления нужно было войти заново в течение последних 10 минут. Если сервер попросит войти снова, отмена удаления сохранит данные.",style=MaterialTheme.typography.bodySmall)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        confirmButton={TextButton(enabled=!busy,onClick={confirmAccountDeletion=false;vm.deleteAccount()},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Удалить аккаунт и данные")}},
        dismissButton={TextButton(enabled=!busy,onClick={confirmAccountDeletion=false}){Text("Отмена")}}
    )
    if(settings) AlertDialog(onDismissRequest={settings=false},title={Text("Настройки")},text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(session?.email.orEmpty()); Text("Сервер: ${vm.app.preferences.url()}"); TextButton(onClick={settings=false;feature="backup"}) {Text("Резервные копии")}; TextButton(onClick={settings=false;feature="reminders"}) {Text("Напоминания")}; TextButton(onClick={settings=false;socialSettings=true}) {Text("Способы входа")}; TextButton(onClick={settings=false;chatGptLink=true}) {Text("Подключить ChatGPT через MCP")}; TextButton(onClick={settings=false;mobileChatSetup=true}) {Text("Настроить импорт из ChatGPT на телефоне")}; TextButton(onClick={settings=false;connection=true}) {Text("Подключение к серверу")}; Text("Данные сохраняются на телефоне и синхронизируются при подключении. Напоминания могут задерживаться из-за ограничений Android."); TextButton(onClick={settings=false;vm.synchronize()}) {Text("Синхронизировать сейчас")}; TextButton(onClick={settings=false;vm.logout()}) {Text("Выйти из аккаунта")}; HorizontalDivider(); TextButton(onClick={settings=false;confirmAccountDeletion=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) {Text("Удалить аккаунт и все данные")} } },confirmButton={TextButton(onClick={settings=false}) {Text("Готово")}})
}
@Composable private fun ConnectionDialog(vm: AppViewModel,onClose: ()->Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(vm.app.preferences.url()) }
    var automatic by remember { mutableStateOf(vm.app.preferences.automatic()) }
    var password by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text("Подключение к серверу")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Switch(automatic,{automatic=it}); Text("Подключаться автоматически") }
            Text("Автоматическое подключение проверяет подлинность нашего сервера и сохраняет локальные данные. Для собственного сервера можно указать адрес вручную.")
            Text(session?.email.orEmpty())
            if(!automatic) OutlinedTextField(url,{url=it},label={Text("Адрес сервера")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(!automatic) OutlinedTextField(password,{password=it},label={Text("Пароль аккаунта")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("При бесплатном туннеле адрес может меняться. Компьютер и сервер должны быть включены.",style=MaterialTheme.typography.bodySmall)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    },confirmButton={TextButton(enabled=!busy && (automatic || (password.isNotBlank() && url.isNotBlank())),onClick={if(automatic){vm.automaticConnection(true);onClose()}else vm.changeServer(url,password,onClose)}) {Text("Подключить")}},dismissButton={TextButton(enabled=!busy,onClick=onClose){Text("Отмена")}})
}
@Composable private fun SocialButtons(vm: AppViewModel,link: Boolean) {
    val providers by vm.providers.collectAsStateWithLifecycle()
    val pending by vm.socialBusy.collectAsStateWithLifecycle()
    val entries=providers.ifEmpty { listOf(SocialProviderInfo("google","Google",false),SocialProviderInfo("vk","VK",false),SocialProviderInfo("yandex","Яндекс",false)) }
    entries.forEach { provider ->
        OutlinedButton(onClick={vm.socialLogin(provider.id,link)},enabled=provider.enabled && !provider.linked && !pending,modifier=Modifier.fillMaxWidth()) {
            Text(if(provider.linked) "${provider.title} — привязан" else if(!provider.enabled) "${provider.title} — требуется настройка сервера" else "${if(link) "Привязать" else "Войти через"} ${provider.title}")
        }
    }
    if(pending) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Заверши вход у провайдера и вернись сюда."); TextButton(onClick={vm.cancelSocialLogin()}){Text("Отменить вход")} }
}
@Composable private fun SocialAccountDialog(vm: AppViewModel,onClose: ()->Unit) {
    LaunchedEffect(vm) { vm.findServer() }
    AlertDialog(onDismissRequest=onClose,title={Text("Способы входа")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Привязка добавляет способ входа к текущему аккаунту. Данные останутся общими.")
        SocialButtons(vm,true)
    }},confirmButton={TextButton(onClick=onClose){Text("Готово")}})
}
@Composable private fun LoginScreen(vm: AppViewModel,modifier: Modifier) {
    var email by rememberSaveable { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf(vm.app.preferences.url()) }; var register by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connectedUrl by vm.serverUrl.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(!vm.app.preferences.automatic()) }
    LaunchedEffect(connectedUrl) { if(!advanced) url=connectedUrl }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(42.dp)); Text("Habit Tracker",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
        Text("Задачи, заметки и привычки",style=MaterialTheme.typography.titleLarge)
        Text(if(register) "Создай свой аккаунт" else "Войди, чтобы синхронизировать данные")
        OutlinedTextField(email,{email=it},label={Text("Email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(password,{password=it},label={Text("Пароль${if(register) " — от 10 символов" else ""}")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        SocialButtons(vm,false)
        TextButton(onClick={advanced=!advanced;vm.automaticConnection(!advanced)}) { Text(if(advanced) "Использовать автоматическое подключение" else "Дополнительные настройки") }
        if(advanced) OutlinedTextField(url,{url=it;vm.manualAddress(it)},label={Text("Адрес сервера")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
        else Text(if(url.isBlank()) "Ищем сервер…" else "Сервер подключён автоматически",style=MaterialTheme.typography.bodySmall)
        Button(onClick={vm.login(url,email,password,register)},enabled=!busy && email.isNotBlank() && password.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(busy) "Подключение…" else if(register) "Создать аккаунт" else "Войти") }
        TextButton(onClick={register=!register}) {Text(if(register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")}
        Text("В первой версии email используется как логин. Подтверждение почты и восстановление пароля пока недоступны.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun SectionTitle(text: String) { Text(text,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp)) }
@Composable private fun EmptyState(text: String) { Text(text,modifier=Modifier.padding(vertical=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun TaskCard(item: Item,complete: (Boolean)->Unit,edit: ()->Unit,delete: ()->Unit) {
    val task=item.content as TaskContent
    Card { Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(task.completed,complete)
        Column(Modifier.weight(1f).clickable(onClick=edit).padding(6.dp)) { Text(task.title,fontWeight=FontWeight.Medium); if(task.dueDate!=null) Text("${localDate(task.dueDate)} ${task.dueTime.orEmpty()}",style=MaterialTheme.typography.labelMedium); if(task.description.isNotBlank()) Text(task.description,maxLines=2,style=MaterialTheme.typography.bodySmall) }
        IconButton(onClick=delete) { Icon(Icons.Outlined.Delete,"Удалить задачу") }
    } }
}
@Composable private fun HabitCard(item: Item,items: List<Item>,today: LocalDate,mark: (Boolean)->Unit,edit: ()->Unit,delete: ()->Unit,history: ()->Unit) {
    val habit=item.content as HabitContent
    val marks=items.mapNotNull { (it.content as? HabitMarkContent)?.takeIf { it.habitId==item.id && it.completed }?.date?.let(LocalDate::parse) }.toSet()
    Card { Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f).clickable(onClick=edit)) { Text(habit.title,fontWeight=FontWeight.Bold); Text(if(habit.mode==HabitMode.WEEKLY_TARGET) "${habit.weekProgress(today,marks)} из ${habit.targetPerWeek} дней на неделе" else habit.weekdays.sorted().joinToString(" · ") { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс")[it-1] },style=MaterialTheme.typography.bodySmall) }; Checkbox(today in marks,if(habit.isDue(today)) mark else null) }
        Row(verticalAlignment=Alignment.CenterVertically) { Text("Серия: ${habit.streak(today,marks)} ${if(habit.mode==HabitMode.WEEKLY_TARGET) "нед." else "вып."}",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium); TextButton(onClick=history) {Text("История")}; IconButton(onClick=delete) {Icon(Icons.Outlined.Delete,"Удалить привычку")} }
    } }
}
@Composable private fun ConflictCard(conflict: Conflict,resolve: (Boolean)->Unit) {
    var open by remember { mutableStateOf(false) }
    val local=remember(conflict) { WireJson.decodeFromString<Item>(conflict.localJson) }
    OutlinedCard(onClick={open=true}) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(local.content.title.ifBlank { "Отметка привычки" },fontWeight=FontWeight.Bold); Text("Разобрать конфликт",color=MaterialTheme.colorScheme.primary) } }
    if(open) {
        val remote=conflict.remoteJson?.let { WireJson.decodeFromString<Item>(it) }
        AlertDialog(onDismissRequest={open=false},title={Text("Какие изменения оставить?")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("На телефоне",fontWeight=FontWeight.Bold); Text(if(local.deleted) "Удалено" else local.content.description())
            HorizontalDivider(); Text("На сервере",fontWeight=FontWeight.Bold); Text(if(remote==null || remote.deleted) "Удалено" else remote.content.description())
        }},confirmButton={TextButton(onClick={resolve(true);open=false}) {Text("Оставить телефон")}},dismissButton={TextButton(onClick={resolve(false);open=false}) {Text("Оставить сервер")}})
    }
}
@Composable private fun HabitHistory(item: Item,items: List<Item>,today: LocalDate,mark: (LocalDate,Boolean)->Unit,close: ()->Unit) {
    val habit=item.content as HabitContent
    val marks=items.mapNotNull { (it.content as? HabitMarkContent)?.takeIf { it.habitId==item.id && it.completed }?.date }.toSet()
    val count=(ChronoUnit.DAYS.between(LocalDate.parse(habit.startsOn),today)+1).coerceIn(0,100000).toInt()
    AlertDialog(onDismissRequest=close,title={Text("История: ${habit.title}")},text={LazyColumn(Modifier.heightIn(max=420.dp)) { items(count) { index -> val date=today.minusDays(index.toLong()); Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(date.format(dateFormat),Modifier.weight(1f)); if(habit.isDue(date)) Checkbox(date.toString() in marks,{mark(date,it)}) else Text("Выходной",style=MaterialTheme.typography.labelSmall) } } }},confirmButton={TextButton(onClick=close) {Text("Готово")}})
}
@Composable private fun Editor(id: String,initial: Content,files: List<LocalFile>,vm: AppViewModel,close: ()->Unit,openLinked: (Item)->Unit) {
    var title by remember(id) { mutableStateOf(initial.title) }
    var body by remember(id) { mutableStateOf(when(initial) { is TaskContent -> initial.description; is NoteContent -> initial.text; is HabitContent -> initial.description; is ProjectContent -> initial.description; else -> "" }) }
    var date by remember(id) { mutableStateOf(localDate((initial as? TaskContent)?.dueDate)) }
    var time by remember(id) { mutableStateOf((initial as? TaskContent)?.dueTime.orEmpty()) }
    var reminder by remember(id) { mutableStateOf((initial as? TaskContent)?.reminderMinutes?.toString().orEmpty()) }
    var mode by remember(id) { mutableStateOf((initial as? HabitContent)?.mode ?: HabitMode.WEEKDAYS) }
    var days by remember(id) { mutableStateOf((initial as? HabitContent)?.weekdays ?: setOf(1,2,3,4,5,6,7)) }
    var target by remember(id) { mutableStateOf(((initial as? HabitContent)?.targetPerWeek ?: 3).toString()) }
    var starts by remember(id) { mutableStateOf(localDate((initial as? HabitContent)?.startsOn ?: LocalDate.now().toString())) }
    var habitTime by remember(id) { mutableStateOf((initial as? HabitContent)?.reminderTime.orEmpty()) }
    var organization by remember(id) { mutableStateOf(initial.organization) }
    var goal by remember(id) { mutableStateOf((initial as? ProjectContent)?.isGoal ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context=androidx.compose.ui.platform.LocalContext.current
    val currentItems by vm.items.collectAsStateWithLifecycle()
    fun content(): Content = when(initial) {
        is TaskContent -> initial.copy(title=title.trim(),description=body,dueDate=parseDate(date),dueTime=time.trim().ifBlank { null },reminderMinutes=reminder.trim().takeIf(String::isNotEmpty)?.toInt())
        is NoteContent -> (currentItems.firstOrNull { it.id==id }?.content as? NoteContent ?: initial).copy(title=title.trim(),text=body)
        is HabitContent -> initial.copy(title=title.trim(),description=body,mode=mode,weekdays=days,targetPerWeek=target.toInt(),startsOn=requireNotNull(parseDate(starts)),reminderTime=habitTime.trim().ifBlank { null })
        is ProjectContent -> initial.copy(title=title.trim(),description=body,isGoal=goal)
        else -> initial
    }.withOrganization(organization)
    fun submit(after: ()->Unit) { try { val value=content();value.validate();vm.save(value,id,after) } catch(e: Exception) { error=e.message ?: "Проверь поля формы" } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.attach(id,it) } }
    AlertDialog(onDismissRequest=close,title={Text(if(initial is TaskContent) "Задача" else if(initial is HabitContent) "Привычка" else if(initial is ProjectContent) "Проект или цель" else "Заметка")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(title,{title=it},label={Text("Название")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(body,{body=it},label={Text(if(initial is NoteContent) "Текст" else "Описание")},minLines=3,modifier=Modifier.fillMaxWidth())
        if(initial is ProjectContent) Row(verticalAlignment=Alignment.CenterVertically) { Switch(goal,{goal=it});Text("Это цель") }
        if(initial !is ProjectContent) OrganizationFields(organization,id,currentItems,{organization=it}) {item->submit {close();openLinked(item)}}
        if(initial is TaskContent) {
            OutlinedTextField(date,{date=it},label={Text("Дата · дд.мм.гггг")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(time,{time=it},label={Text("Время · чч:мм, необязательно")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(reminder,{reminder=it},label={Text("Напомнить за минут · необязательно")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
        }
        if(initial is HabitContent) {
            Row { FilterChip(mode==HabitMode.WEEKDAYS,{mode=HabitMode.WEEKDAYS},label={Text("Дни недели")}); Spacer(Modifier.width(8.dp)); FilterChip(mode==HabitMode.WEEKLY_TARGET,{mode=HabitMode.WEEKLY_TARGET},label={Text("N дней")}) }
            if(mode==HabitMode.WEEKDAYS) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEachIndexed { index,label -> FilterChip(index+1 in days,{days=if(index+1 in days) days-(index+1) else days+(index+1)},label={Text(label)}) } }
            else OutlinedTextField(target,{target=it},label={Text("Дней в неделю · от 1 до 7")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            OutlinedTextField(starts,{starts=it},label={Text("Начало · дд.мм.гггг")},singleLine=true)
            OutlinedTextField(habitTime,{habitTime=it},label={Text("Напоминание · чч:мм, необязательно")},singleLine=true)
        }
        if(initial is NoteContent) {
            TextButton(onClick={submit {picker.launch(arrayOf("*/*"))}}) {Icon(Icons.Outlined.AttachFile,null); Text("Прикрепить файл")}
            val attached=(currentItems.firstOrNull { it.id==id }?.content as? NoteContent)?.attachmentIds ?: initial.attachmentIds
            files.filter { it.id in attached }.forEach { file -> Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={vm.open(file) { local ->
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",local)
                    try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mediaType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch(_: android.content.ActivityNotFoundException) { vm.message.value="Нет приложения для открытия этого файла" }
                }},modifier=Modifier.weight(1f)) {Text(file.name)}
                IconButton(onClick={try {val note=content() as NoteContent;vm.save(note.copy(attachmentIds=note.attachmentIds-file.id),id)}catch(e:Exception){error=e.message}}) {Icon(Icons.Outlined.Close,"Убрать вложение")}
            } }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={submit(close)}) {Text("Сохранить")}},dismissButton={TextButton(onClick=close) {Text("Закрыть")}})
}

@Composable private fun MobileChatGptSetupDialog(close: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Импорт из ChatGPT на телефоне") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Мобильное приложение ChatGPT пока не подключает MCP. Можно настроить один раз: скопируй инструкцию, вставь её в ChatGPT → Настройки → Пользовательские инструкции. После этого ChatGPT будет добавлять ссылки для задач и заметок в любом чате.")
                Text("Когда появится ссылка, открой её, проверь запись и нажми «Сохранить». Без этого шага запись не создаётся.", style = MaterialTheme.typography.bodySmall)
                Text(mobileChatGptInstructions, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Habit Tracker", mobileChatGptInstructions))
                    copied = true
                }) { Text(if (copied) "Инструкция скопирована" else "Скопировать инструкцию") }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Готово") } }
    )
}
