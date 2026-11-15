package dev.habittracker.android

import android.net.Uri
import dev.habittracker.shared.*
import kotlinx.serialization.Serializable
import java.io.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.*

@Serializable data class BackupFile(val id: String,val noteId: String,val name: String,val mime: String,val size: Long)
@Serializable data class BackupConflict(val itemId: String,val local: Item,val remote: Item?)
@Serializable data class BackupManifest(val schema: Int=1,val exportedAt: String,val items: List<Item>,val files: List<BackupFile>,val conflicts: List<BackupConflict> = emptyList())
data class BackupPreview(val archive: File,val hash: String,val manifest: BackupManifest)
object Backups {
    private const val maxArchive=1024L*1024*1024
    private fun copy(input: InputStream,output: OutputStream,limit: Long): Long {val buffer=ByteArray(8192);var count=0L;while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=limit){"Превышен безопасный размер файла резервной копии"};output.write(buffer,0,n)};return count}
    suspend fun export(repo: Repository,uri: Uri) = repo.exclusive {
        val items=repo.dao.items().map {WireJson.decodeFromString<Item>(it.localJson)}.filterNot {it.deleted}
        val conflicts=repo.dao.allConflicts().map {BackupConflict(it.itemId,WireJson.decodeFromString(it.localJson),it.remoteJson?.let {json->WireJson.decodeFromString<Item>(json)})}
        val notes=(items+conflicts.flatMap {listOfNotNull(it.local,it.remote)}).filter {it.content is NoteContent}
        val references=notes.flatMap {item->(item.content as NoteContent).attachmentIds.map {it to item.id}}.toMap()
        val files=references.map { (id,noteId)->
            val local=repo.dao.file(id) ?: run {repo.fileMetadata(noteId);repo.dao.file(id)} ?: error("Вложение недоступно. Подключись к серверу перед экспортом.")
            local to repo.cachedFile(local)
        }
        require(files.sumOf {it.second.length()}<=maxArchive){"Резервная копия больше 1 ГБ"}
        val manifest=BackupManifest(exportedAt=Instant.now().toString(),items=items,files=files.map {(meta,file)->BackupFile(meta.id,meta.noteId,meta.name,meta.mediaType,file.length())},conflicts=conflicts)
        val json=WireJson.encodeToString(manifest).toByteArray();require(json.size<=32*1024*1024){"Текстовая часть копии больше 32 МБ"}
        repo.app.contentResolver.openOutputStream(uri,"w")!!.use {raw->ZipOutputStream(BufferedOutputStream(raw)).use {zip->
            zip.putNextEntry(ZipEntry("manifest.json"));zip.write(json);zip.closeEntry()
            files.forEach {(meta,file)->zip.putNextEntry(ZipEntry("files/${meta.id}"));file.inputStream().use {copy(it,zip,20L*1024*1024)};zip.closeEntry()}
        }}
    }
    fun inspect(app: HabitApplication,uri: Uri): BackupPreview {
        val archive=File(app.cacheDir,"import-${UUID.randomUUID()}.zip")
        try {
            app.contentResolver.openInputStream(uri)!!.use {input->archive.outputStream().use {copy(input,it,maxArchive)}}
            val digest=MessageDigest.getInstance("SHA-256");archive.inputStream().use {input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            val hash=digest.digest().joinToString(""){"%02x".format(it)}
            ZipFile(archive).use {zip->
                val entries=zip.entries().asSequence().toList();require(entries.size<=20001 && entries.map {it.name}.distinct().size==entries.size){"Некорректная структура архива"}
                val json=zip.getEntry("manifest.json") ?: error("В архиве нет manifest.json")
                val jsonBytes=ByteArrayOutputStream().also {out->zip.getInputStream(json).use {copy(it,out,32L*1024*1024)}}.toByteArray()
                val manifest=WireJson.decodeFromString<BackupManifest>(jsonBytes.toString(Charsets.UTF_8))
                require(manifest.schema==1 && manifest.items.size<=10000 && manifest.files.size<=10000){"Неподдерживаемая версия или размер копии"}
                require(manifest.items.map {it.id}.distinct().size==manifest.items.size)
                manifest.items.forEach {UUID.fromString(it.id);it.content.validate()}
                manifest.conflicts.forEach {UUID.fromString(it.itemId);it.local.content.validate();it.remote?.content?.validate()}
                val notes=(manifest.items+manifest.conflicts.flatMap {listOfNotNull(it.local,it.remote)}).filter {it.content is NoteContent}
                manifest.files.forEach {file->require(notes.any {it.id==file.noteId && file.id in (it.content as NoteContent).attachmentIds}) {"Вложение не связано с заметкой"}}
                require(notes.all {note->(note.content as NoteContent).attachmentIds.all {id->manifest.files.any {it.id==id && it.noteId==note.id}}}) {"В копии отсутствует вложение заметки"}
                val permitted=manifest.files.map {file->UUID.fromString(file.id);UUID.fromString(file.noteId);require(file.size in 0..20L*1024*1024 && file.name.isNotBlank() && file.name.length<=255 && Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(file.mime));"files/${file.id}"}.toSet()+"manifest.json"
                require(permitted.size==manifest.files.size+1 && entries.all {it.name in permitted} && entries.size==permitted.size){"В архиве есть неизвестные или отсутствующие файлы"}
                var total=0L
                val discard=object:OutputStream(){override fun write(b: Int){};override fun write(b:ByteArray,off:Int,len:Int){}}
                manifest.files.forEach {file->val entry=zip.getEntry("files/${file.id}") ?: error("Нет вложения");val size=zip.getInputStream(entry).use {copy(it,discard,20L*1024*1024)};require(size==file.size);total+=size;require(total<=maxArchive)}
                return BackupPreview(archive,hash,manifest)
            }
        }catch(e:Exception){archive.delete();throw e}
    }
    suspend fun restore(repo: Repository,preview: BackupPreview): Int = repo.exclusive {
        val source=preview.manifest.items.filterNot {it.deleted}
        fun id(old: String)=UUID.nameUUIDFromBytes("restore:${repo.session.userId}:${preview.hash}:$old".toByteArray()).toString()
        val ids=source.filterNot {it.content is HabitMarkContent}.associate {it.id to id(it.id)}.toMutableMap()
        source.filter {it.content is HabitMarkContent}.forEach {item->val mark=item.content as HabitMarkContent;ids[mark.habitId]?.let {ids[item.id]=markId(it,LocalDate.parse(mark.date))}}
        val files=preview.manifest.files.associate {it.id to id("file:${it.id}")}
        val transformed=source.mapNotNull {item->
            val targetId=ids[item.id] ?: return@mapNotNull null
            val content=when(val value=item.content){
                is HabitMarkContent->value.copy(habitId=ids[value.habitId] ?: return@mapNotNull null)
                is NoteContent->value.copy(attachmentIds=value.attachmentIds.map {files[it] ?: error("В копии отсутствует вложение")})
                is TaskContent->value.copy(completionDateUnknown=value.completed && value.completedAt==null)
                is WeekPlanContent->NoteContent(value.title+" (архив плана)",value.summary+"\n\n"+value.moves.joinToString("\n"){"${it.itemId} → ${it.dueDate}: ${it.reason}"})
                else->value
            }
            val org=content.organization
            targetId to content.withOrganization(org.copy(projectId=org.projectId?.let {ids[it]},goalId=org.goalId?.let {ids[it]},linkedIds=org.linkedIds.mapNotNull {ids[it]}.toSet()))
        }
        var restored=0
        for((target,content) in transformed.sortedBy {when(it.second){is ProjectContent->0;is HabitMarkContent->2;else->1}}){if(repo.dao.item(target)==null){repo.save(content,target,notify=false);restored++}}
        val attachments=preview.manifest.files.mapNotNull {meta->ids[meta.noteId]?.let {Triple(it,meta,files.getValue(meta.id))}}.toMutableList()
        for(conflict in preview.manifest.conflicts) for((name,version) in listOf("телефон" to conflict.local,"сервер" to conflict.remote)){
            if(version==null)continue
            val originalFiles=(version.content as? NoteContent)?.attachmentIds.orEmpty()
            val restoredFiles=originalFiles.associateWith {id("conflict-file:${conflict.itemId}:$name:$it")}
            for((index,text) in WireJson.encodeToString(version).chunked(900000).withIndex()) {
                val target=id("conflict:${conflict.itemId}:$name:$index")
                if(repo.dao.item(target)==null){repo.save(NoteContent("Конфликт · $name · ${version.content.title} · ${index+1}".take(300),text,if(index==0)restoredFiles.values.toList()else emptyList()),target,notify=false);restored++}
                if(index==0)originalFiles.forEach {old->val meta=preview.manifest.files.firstOrNull {it.id==old} ?: error("Нет вложения версии конфликта");attachments+=Triple(target,meta,restoredFiles.getValue(old))}
            }
        }
        ZipFile(preview.archive).use {zip->for((noteId,meta,newId) in attachments){
            if(repo.dao.file(newId)!=null)continue
            val temp=File(repo.app.cacheDir,"restore-${UUID.randomUUID()}")
            try {zip.getInputStream(zip.getEntry("files/${meta.id}")).use {input->temp.outputStream().use {copy(input,it,20L*1024*1024)}};repo.attach(noteId,Uri.fromFile(temp),meta.name,meta.mime,newId,notify=false)}finally{temp.delete()}
        }}
        repo.scheduleSync();Reminders.reschedule(repo.app,repo);TrackerWidget.updateAll(repo.app)
        restored
    }
}
