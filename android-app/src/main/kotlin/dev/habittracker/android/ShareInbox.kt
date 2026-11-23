package dev.habittracker.android

import android.content.*
import android.net.Uri
import android.provider.OpenableColumns
import dev.habittracker.shared.WireJson
import dev.habittracker.shared.TaskContent
import dev.habittracker.shared.NoteContent
import dev.habittracker.shared.parseQuickTask
import dev.habittracker.shared.TASK_DRAFT_PAGE
import dev.habittracker.shared.contentDraftFromLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import java.time.ZoneId

@Serializable data class SharedFile(val path: String,val name: String,val mime: String)
@Serializable data class SharedDraft(
    val id: String,
    val title: String,
    val text: String,
    val files: List<SharedFile>,
    val task: TaskContent? = null,
    val note: NoteContent? = null,
    val autoSave: Boolean = false,
)
class ShareInbox(private val context: Context) {
    private val directory=File(context.filesDir,"incoming").apply {mkdirs()}
    private val manifest=File(directory,"drafts.json")
    val drafts=MutableStateFlow(runCatching {WireJson.decodeFromString<List<SharedDraft>>(manifest.readText())}.getOrDefault(emptyList()))
    val error=MutableStateFlow<String?>(null)
    private fun store(value: List<SharedDraft>){val temp=File(directory,"drafts.tmp");temp.writeText(WireJson.encodeToString(value));check(temp.renameTo(manifest));drafts.value=value}
    @Synchronized fun receiveDraftLink(link: String) {
        val content = contentDraftFromLink(link, ZoneId.systemDefault().id)
        if (drafts.value.any { it.task == content || it.note == content }) return
        require(drafts.value.size < 10) { "Сначала сохрани или отклони предыдущие черновики" }
        val task = content as? TaskContent
        val note = content as? NoteContent
        store(drafts.value + SharedDraft(UUID.randomUUID().toString(), content.title, task?.description ?: note?.text.orEmpty(), emptyList(), task, note, autoSave = true))
    }
    @Synchronized fun receive(intent: Intent) {
        require(intent.action in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE))
        require(drafts.value.size<10){"Сначала сохрани или отклони предыдущие отправки"}
        var storedBytes=directory.listFiles().orEmpty().sumOf {it.length()}
        val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull {intent.clipData?.getItemAt(it)?.text?.toString()}.joinToString("\n");require(text.length<=1_000_000)
        val subject=intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().take(300)
        @Suppress("DEPRECATION") val streams=if(intent.action==Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty() else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val uris=(streams+(0 until (intent.clipData?.itemCount ?: 0)).mapNotNull {intent.clipData?.getItemAt(it)?.uri}).distinct()
        if (uris.isEmpty() && text.trim().startsWith("$TASK_DRAFT_PAGE#")) {
            receiveDraftLink(text.trim())
            return
        }
        require(uris.size<=30){"В одной заметке поддерживается до 30 вложений"}
        val copied=mutableListOf<SharedFile>()
        try {
            for(uri in uris) {
                require(uri.scheme=="content" && uri.authority!="${context.packageName}.files") {"Выбери файл через системное меню «Поделиться»"}
                var name="Файл"
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())name=it.getString(0)}
                name=name.replace('\n',' ').replace('\r',' ').take(255).ifBlank {"Файл"}
                val mime=context.contentResolver.getType(uri)?.takeIf {Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(it)} ?: "application/octet-stream"
                val file=File(directory,UUID.randomUUID().toString());copied+=SharedFile(file.absolutePath,name,mime)
                context.contentResolver.openInputStream(uri)!!.use {input->file.outputStream().use {output->val buffer=ByteArray(8192);var total=0L;while(true){val n=input.read(buffer);if(n<0)break;total+=n;storedBytes+=n;require(total<=20L*1024*1024){"Файл больше 20 МБ"};require(storedBytes<=512L*1024*1024){"Входящие заполнены. Сохрани или отклони предыдущие файлы."};output.write(buffer,0,n)}}}
            }
            require(text.isNotBlank() || copied.isNotEmpty()) {"Нет текста или файлов для сохранения"}
            val imported = importText(subject, text, copied)
            store(drafts.value + imported.copy(id = UUID.randomUUID().toString(), files = copied))
        }catch(e: Exception){copied.forEach {File(it.path).delete()};throw e}
    }

    private fun importText(subject: String, text: String, files: List<SharedFile>): SharedDraft {
        val lines = text.lines()
        val headerIndex = lines.indexOfFirst { it.isNotBlank() }
        val header = lines.getOrNull(headerIndex).orEmpty().trim()
        val taskHeader = Regex("(?i)^(?:[-*•]\\s*)?(?:(?:создай|добавь|поставь|запланируй)\\s+(?:мне\\s+)?)?(?:задача|задачу|task)\\s*[:—-]\\s*(.+)$")
            .matchEntire(header)
        if (taskHeader != null && files.isEmpty()) {
            val zone = ZoneId.systemDefault().id
            val titleAndDate = taskHeader.groupValues[1].trim()
            val description = lines.drop(headerIndex + 1).joinToString("\n").trim()
            val parsed = runCatching { parseQuickTask(titleAndDate, java.time.LocalDate.now(ZoneId.of(zone)), zone) }.getOrNull()
            val task = parsed?.content?.copy(description = description)
                ?: TaskContent(titleAndDate, description = description, zoneId = zone)
            return SharedDraft("", task.title, task.description, emptyList(), task = task, autoSave = parsed?.warnings?.isEmpty() == true)
        }

        val noteHeader = Regex("(?i)^(?:[-*•]\\s*)?(?:(?:сохрани|создай|добавь|запиши)\\s+(?:мне\\s+)?)?(?:заметка|заметку|note)\\s*[:—-]\\s*(.*)$")
            .matchEntire(header)
        if (noteHeader != null) {
            val firstLine = noteHeader.groupValues[1].trim()
            val title = subject.takeIf { it.isNotBlank() && !it.equals("ChatGPT", ignoreCase = true) }
                ?: firstLine.take(100).ifBlank { files.firstOrNull()?.name ?: "Заметка" }
            val body = lines.drop(headerIndex + 1).joinToString("\n").trim().ifBlank { firstLine }
            val note = NoteContent(title, body)
            return SharedDraft("", title, body, emptyList(), note = note, autoSave = true)
        }

        val title = subject.takeIf { it.isNotBlank() && !it.equals("ChatGPT", ignoreCase = true) }
            ?: text.lineSequence().firstOrNull()?.trim()?.take(100)?.ifBlank { null }
            ?: files.firstOrNull()?.name
            ?: "Из другого приложения"
        val note = NoteContent(title, text)
        return SharedDraft("", title, text, emptyList(), note = note, autoSave = true)
    }

    @Synchronized fun remove(id: String){val draft=drafts.value.firstOrNull {it.id==id} ?: return;store(drafts.value.filterNot {it.id==id});draft.files.forEach {File(it.path).delete()}}
}
