package dev.habittracker.android

import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class FeatureIntegrationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<HabitApplication>()
    private fun account(): Session {
        val session=Api(TestServer.url).post<Credentials,Session>("/api/v1/auth/register",Credentials("${UUID.randomUUID()}@feature.android.test","test-password-123"))
        app.preferences.automatic(false);app.preferences.save(session,TestServer.url);return session
    }
    @Test fun taskLinkOpensEditableDraftAndOnlySavesOnConfirmation() = runBlocking(Dispatchers.IO) {
        val session = account()
        val repo = Repository(app, session, false)
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        val link = Uri.parse("habittracker://import/task?title=Draft%20from%20ChatGPT&date=2027-01-20&time=18%3A00")
        val intent = Intent(Intent.ACTION_VIEW, link).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(app.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            compose.waitUntil(20000) { compose.onAllNodesWithText("Draft from ChatGPT").fetchSemanticsNodes().isNotEmpty() }
            val draft = app.shareInbox.drafts.value.single()
            assertNull(repo.dao.item(draft.id))
            compose.onNodeWithText("Сохранить").performClick()
            withTimeout(15000) { while (repo.dao.item(draft.id) == null) delay(100) }
            val task = WireJson.decodeFromString<Item>(checkNotNull(repo.dao.item(draft.id)).localJson).content as TaskContent
            assertEquals("Draft from ChatGPT", task.title)
            assertEquals("2027-01-20", task.dueDate)
            assertEquals("18:00", task.dueTime)
            withTimeout(15000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
        }
        repo.database.close()
    }
    @Test fun existingRoomDatabaseOpensWithBundledDriverWithoutLosingOutbox()=runBlocking(Dispatchers.IO) {
        val key="driver-migration-${UUID.randomUUID()}";val id=UUID.randomUUID().toString()
        val item=Item(id,0,TaskContent("Сохранено в старой версии"),false,Instant.now().toString(),0)
        val old=androidx.room.Room.databaseBuilder(app,TrackerDatabase::class.java,"tracker-$key.db").build()
        old.dao().item(LocalItem(id,WireJson.encodeToString(item),null))
        old.dao().pending(PendingMutation(id,WireJson.encodeToString(Mutation(UUID.randomUUID().toString(),id,0,item.content))))
        old.close()
        val updated=TrackerDatabase.open(app,key)
        assertEquals(item,WireJson.decodeFromString<Item>(updated.dao().item(id)!!.localJson))
        assertEquals(id,updated.dao().pending().single().itemId)
        updated.close();app.deleteDatabase("tracker-$key.db");Unit
    }
    @Test fun backupRestoresOfflineRecordsAndFilesAsIdempotentCopies()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        val project=UUID.randomUUID().toString();val task=UUID.randomUUID().toString();val note=UUID.randomUUID().toString();val habit=UUID.randomUUID().toString();val today=LocalDate.now()
        repo.save(ProjectContent("Проект",isGoal=true),project)
        repo.save(TaskContent("Исходная задача",completed=true,completionDateUnknown=true,organization=Organization(project,project,setOf("Учёба"))),task)
        repo.save(NoteContent("Исходная заметка","Текст",organization=Organization(projectId=project,linkedIds=setOf(task))),note)
        val file=File(app.cacheDir,"source-backup.txt").apply {writeText("private-file-roundtrip")}
        repo.attach(note,Uri.fromFile(file),"original.txt","text/plain")
        repo.save(HabitContent("Привычка",mode=HabitMode.WEEKLY_TARGET,startsOn=today.minusDays(7).toString()),habit)
        repo.save(HabitMarkContent(habit,today.toString()),markId(habit,today))
        val archive=File(app.cacheDir,"backup-${UUID.randomUUID()}.zip")
        Backups.export(repo,Uri.fromFile(archive))
        val preview=Backups.inspect(app,Uri.fromFile(archive))
        assertEquals(5,preview.manifest.items.size);assertEquals(1,preview.manifest.files.size)
        assertFalse(WireJson.encodeToString(preview.manifest).contains(session.token))
        assertEquals(5,Backups.restore(repo,preview));val count=repo.dao.items().size
        assertEquals(0,Backups.restore(repo,preview));assertEquals(count,repo.dao.items().size)
        val imported=repo.dao.items().map {WireJson.decodeFromString<Item>(it.localJson)}.single {it.id!=note && it.content is NoteContent}
        val importedNote=imported.content as NoteContent
        val restoredFile=repo.dao.file(importedNote.attachmentIds.single())!!
        assertEquals("original.txt",restoredFile.name);assertEquals("private-file-roundtrip",File(restoredFile.path!!).readText())
        val importedTask=repo.dao.items().map {WireJson.decodeFromString<Item>(it.localJson)}.single {it.id!=task && it.content is TaskContent}
        assertNull((importedTask.content as TaskContent).completedAt)
        assertTrue((importedTask.content as TaskContent).completionDateUnknown)
        assertEquals(setOf(importedTask.id),importedNote.organization.linkedIds)
        repo.synchronize();assertTrue(repo.dao.pending().isEmpty())
        assertEquals("Исходная задача",(repo.dao.item(task)!!.let {WireJson.decodeFromString<Item>(it.localJson)}.content as TaskContent).title)
        preview.archive.delete();archive.delete();file.delete();repo.database.close()
    }
    @Test fun importRejectsUnexpectedZipPathsBeforeCreatingRecords()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        val archive=File(app.cacheDir,"malformed-${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use {zip->zip.putNextEntry(ZipEntry("manifest.json"));zip.write(WireJson.encodeToString(BackupManifest(exportedAt=Instant.now().toString(),items=emptyList(),files=emptyList())).toByteArray());zip.closeEntry();zip.putNextEntry(ZipEntry("../escape.txt"));zip.write("bad".toByteArray());zip.closeEntry()}
        try {Backups.inspect(app,Uri.fromFile(archive));fail("Unexpected archive entry accepted")}catch(e:Exception){assertTrue(e is IllegalArgumentException || e is ZipException)}
        assertTrue(repo.dao.items().isEmpty());archive.delete();repo.database.close()
    }
    @Test fun quickEntryCreatesPreviewedTaskAndProjectSearchWorks() {
        val session=account()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20000){compose.onAllNodesWithText("Быстрый ввод").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Быстрый ввод").performClick()
            compose.onNodeWithText("Что нужно сделать?").performTextInput("Задача из быстрого ввода завтра в 18:00")
            compose.onNodeWithText("Распознать").performClick()
            compose.onNodeWithText("Открыть редактор перед сохранением").performClick()
            compose.onNodeWithText("Сохранить").performClick()
            compose.onNodeWithText("Задачи",useUnmergedTree=false).performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("Задача из быстрого ввода").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Задача из быстрого ввода").assertIsDisplayed()
            compose.onNodeWithContentDescription("Возможности").performClick();compose.onNodeWithText("План недели").performClick()
            val tomorrow=LocalDate.now().plusDays(1);val monday=LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if(tomorrow>monday.plusDays(6))compose.onNodeWithText("→").performClick()
            compose.onNodeWithText("Перенести").performScrollTo().performClick()
            val week=tomorrow.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));val moved=week.plusDays(if(tomorrow==week.plusDays(5))3 else 5)
            compose.onNodeWithText(moved.format(java.time.format.DateTimeFormatter.ofPattern("d MMM",java.util.Locale.forLanguageTag("ru")))).performScrollTo().assertIsDisplayed().performClick()
            val api=Api(TestServer.url){session.token}
            val repo=app.repository()!!
            compose.waitUntil(15000){runBlocking(Dispatchers.IO){repo.dao.items().any {(WireJson.decodeFromString<Item>(it.localJson).content as? TaskContent)?.let {task->task.title=="Задача из быстрого ввода" && task.dueDate==moved.toString()}==true}}}
            runBlocking(Dispatchers.IO){repo.synchronize()}
            assertTrue(api.get<List<Item>>("/api/v1/items").any {(it.content as? TaskContent)?.let {task->task.title=="Задача из быстрого ввода" && task.dueDate==moved.toString()}==true})
            compose.onNodeWithText("Закрыть").performClick()
            compose.onNodeWithContentDescription("Возможности").performClick();compose.onNodeWithText("Проекты и цели").performClick()
            compose.onNodeWithText("+ Проект").performClick();compose.onNodeWithText("Название").performTextInput("Проект из интерфейса");compose.onNodeWithText("Сохранить").performClick()
            compose.onNodeWithContentDescription("Возможности").performClick();compose.onNodeWithText("Поиск и фильтры").performClick()
            compose.onNodeWithText("Название, текст или тег").performTextInput("Проект из интерфейса")
            compose.waitUntil(10000){compose.onAllNodesWithText("Проект из интерфейса").fetchSemanticsNodes().size>=2}
            compose.onAllNodesWithText("Проект из интерфейса").onLast().assertIsDisplayed()
        }
    }
    @Test fun shareCopiesFileBeforeSourceDisappearsAndRequiresConfirmation()=runBlocking(Dispatchers.IO) {
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29)
        val session=account();val repo=Repository(app,session,false)
        app.shareInbox.drafts.value.toList().forEach {app.shareInbox.remove(it.id)}
        val resolver=app.contentResolver
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {put(MediaStore.MediaColumns.DISPLAY_NAME,"shared-${UUID.randomUUID()}.txt");put(MediaStore.MediaColumns.MIME_TYPE,"text/plain");put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)})!!
        resolver.openOutputStream(uri)!!.use {it.write("shared-private-copy".toByteArray())}
        app.shareInbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://example.com/article").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        resolver.delete(uri,null,null)
        val draft=app.shareInbox.drafts.value.single()
        assertEquals("shared-private-copy",File(draft.files.single().path).readText())
        assertTrue(repo.dao.items().isEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000){compose.onAllNodesWithText("Создать заметку").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Создать заметку").performClick()
            withTimeout(15000){while(app.shareInbox.drafts.value.isNotEmpty())delay(100)}
            val note=WireJson.decodeFromString<Item>(repo.dao.item(draft.id)!!.localJson).content as NoteContent
            assertEquals("https://example.com/article",note.text)
            assertEquals("shared-private-copy",File(repo.dao.file(note.attachmentIds.single())!!.path!!).readText())
        }
        repo.database.close()
    }
    @Test fun cancellingAiPlanDoesNotMoveTaskAndConfirmationAppliesIt() {
        val session=account();val api=Api(TestServer.url){session.token};val monday=LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val id=UUID.randomUUID().toString();val task=api.post<Mutation,MutationResult>("/api/v1/mutations",Mutation(UUID.randomUUID().toString(),id,0,TaskContent("Задача плана",dueDate=monday.toString()))).item!!
        val plan=api.post<Mutation,MutationResult>("/api/v1/mutations",Mutation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,WeekPlanContent("План для проверки отмены",monday.toString(),"Освободить понедельник",listOf(PlanMove(id,task.version,monday.plusDays(2).toString(),"Распределить нагрузку"))))).item!!
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20000){compose.onAllNodesWithText("Задача плана").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Возможности").performClick();compose.onNodeWithText("Разбор недели с AI").performClick()
            compose.onNodeWithText(plan.content.title).performScrollTo().performClick();compose.onNodeWithText("Применить предложение").performScrollTo().performClick()
            compose.onNodeWithText("Отмена").performClick()
            assertEquals(monday.toString(),(api.get<Item>("/api/v1/items/$id").content as TaskContent).dueDate)
            compose.onNodeWithText("Применить предложение").performScrollTo().performClick();compose.onNodeWithText("Подтвердить").performClick()
            compose.waitUntil(15000){(api.get<Item>("/api/v1/items/$id").content as TaskContent).dueDate==monday.plusDays(2).toString()}
        }
    }
    @Test fun largeOfflineQueueSynchronizesMutualLinksAcrossBatches()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        val a=UUID.randomUUID().toString();val b=UUID.randomUUID().toString()
        repo.save(TaskContent("A",organization=Organization(linkedIds=setOf(b))),a,notify=false)
        repeat(105){repo.save(TaskContent("Пакет $it"),notify=false)}
        repo.save(NoteContent("B",organization=Organization(linkedIds=setOf(a))),b,notify=false)
        repo.synchronize()
        assertTrue(repo.dao.pending().isEmpty());assertTrue(repo.dao.allConflicts().isEmpty())
        val api=Api(TestServer.url){session.token}
        assertEquals(setOf(b),api.get<Item>("/api/v1/items/$a").content.organization.linkedIds)
        assertEquals(setOf(a),api.get<Item>("/api/v1/items/$b").content.organization.linkedIds)
        assertEquals(107,api.get<List<Item>>("/api/v1/items").size)
        repo.database.close()
    }
    @Test fun notificationAndWidgetActionsUseCurrentAccountAndPreserveOfflineWork()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false);val id=UUID.randomUUID().toString()
        repo.save(TaskContent("Уведомление"),id)
        Reminders.createChannel(app)
        val item=WireJson.decodeFromString<Item>(repo.dao.item(id)!!.localJson)
        Reminders.show(app,item,session.userId)
        val manager=app.getSystemService(android.app.NotificationManager::class.java)
        withTimeout(10000){while(manager.activeNotifications.none {it.id==id.hashCode()})delay(100)}
        val notification=manager.activeNotifications.first {it.id==id.hashCode()}.notification
        assertEquals(3,notification!!.actions.size)
        notification.actions[1].actionIntent.send()
        withTimeout(10000){while(app.getSharedPreferences("reminders",Context.MODE_PRIVATE).getLong("snooze:$id",0)<=System.currentTimeMillis())delay(100)}
        Reminders.show(app,item,session.userId)
        manager.activeNotifications.first {it.id==id.hashCode()}.notification.actions[0].actionIntent.send()
        withTimeout(10000){while((WireJson.decodeFromString<Item>(repo.dao.item(id)!!.localJson).content as TaskContent).completed!=true)delay(100)}
        assertNotNull((WireJson.decodeFromString<Item>(repo.dao.item(id)!!.localJson).content as TaskContent).completedAt)
        val widgetTask=UUID.randomUUID().toString();repo.save(TaskContent("Виджет"),widgetTask)
        app.sendBroadcast(Intent(app,WidgetActionReceiver::class.java).putExtra("id",widgetTask).putExtra("user","different-user"))
        delay(300);assertFalse((WireJson.decodeFromString<Item>(repo.dao.item(widgetTask)!!.localJson).content as TaskContent).completed)
        app.sendBroadcast(Intent(app,WidgetActionReceiver::class.java).putExtra("id",widgetTask).putExtra("user",session.userId))
        withTimeout(10000){while(!(WireJson.decodeFromString<Item>(repo.dao.item(widgetTask)!!.localJson).content as TaskContent).completed)delay(100)}
        Reminders.clear(app);repo.database.close()
    }
    @Test fun longUnicodeNotesAreSplitIntoSafeSyncBatches()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        val text="大".repeat(900000)
        repeat(4){repo.save(NoteContent("Большая заметка $it",text),notify=false)}
        repo.synchronize();assertTrue(repo.dao.pending().isEmpty())
        val items=Api(TestServer.url){session.token}.get<List<Item>>("/api/v1/items")
        assertEquals(4,items.size);assertTrue(items.all {(it.content as NoteContent).text.length==900000})
        repo.database.close()
    }
}
