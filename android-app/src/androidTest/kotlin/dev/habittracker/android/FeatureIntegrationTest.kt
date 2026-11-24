package dev.habittracker.android

import android.Manifest
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.core.content.ContextCompat
import dev.habittracker.shared.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class FeatureIntegrationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<HabitApplication>()
    @Test fun shareInboxStartupRemovesOnlyOldUnreferencedAttachmentCopies() {
        val files=File(app.cacheDir,"share-inbox-${UUID.randomUUID()}").apply {mkdirs()}
        val isolated=object: android.content.ContextWrapper(app) { override fun getFilesDir()=files }
        val directory=File(files,"incoming").apply {mkdirs()}
        val orphan=File(directory,UUID.randomUUID().toString()).apply {writeText("orphan");setLastModified(System.currentTimeMillis()-48L*60*60*1000)}
        val referenced=File(directory,UUID.randomUUID().toString()).apply {writeText("draft attachment");setLastModified(System.currentTimeMillis()-48L*60*60*1000)}
        val draft=SharedDraft(UUID.randomUUID().toString(),"Note","",listOf(SharedFile(referenced.absolutePath,"file.txt","text/plain")))
        File(directory,"drafts.json").writeText(WireJson.encodeToString(listOf(draft)))

        val inbox=ShareInbox(isolated)

        assertFalse("abandoned attachment should be removed",orphan.exists())
        assertTrue("attachment referenced by a saved draft must remain",referenced.exists())
        inbox.remove(draft.id)
        files.deleteRecursively()
    }
    @Test fun retryingSharedNoteFinishesAfterPartialAttachmentFailure()=runBlocking(Dispatchers.IO) {
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29)
        account()
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        val resolver=app.contentResolver
        fun insert(name: String, text: String): Uri {
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
            })!!
            resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            return uri
        }
        val first=insert("share-first-${UUID.randomUUID()}.txt","first attachment")
        val second=insert("share-second-${UUID.randomUUID()}.txt","second attachment")
        app.shareInbox.receive(Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,"Заметка: Повтор вложений")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM,arrayListOf(first,second))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        resolver.delete(first,null,null);resolver.delete(second,null,null)
        val draft=app.shareInbox.drafts.value.single()
        val firstCopy=File(draft.files[0].path)
        val secondCopy=File(draft.files[1].path)
        assertTrue(secondCopy.delete())

        val owner=object: ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        try {
            val vm=ViewModelProvider(owner,ViewModelProvider.AndroidViewModelFactory.getInstance(app))
                .get(AppViewModel::class.java)
            val repo=app.repository()!!
            vm.acceptShare(draft,false)
            withTimeout(15000) { while(vm.busy.value || vm.message.value==null) delay(50) }
            val partial=WireJson.decodeFromString<Item>(repo.dao.item(draft.id)!!.localJson).content as NoteContent
            assertEquals(1,partial.attachmentIds.size)
            assertEquals(draft.id,app.shareInbox.drafts.value.single().id)
            assertTrue("A failed attachment must not discard the inbox draft's retry source",firstCopy.exists())
            assertEquals("first attachment",firstCopy.readText())

            secondCopy.writeText("second attachment")
            vm.message.value=null
            vm.acceptShare(draft,false)
            withTimeout(15000) { while(vm.busy.value || vm.message.value==null) delay(50) }
            assertEquals("Заметка добавлена",vm.message.value)
            val completed=WireJson.decodeFromString<Item>(repo.dao.item(draft.id)!!.localJson).content as NoteContent
            assertEquals(2,completed.attachmentIds.size)
            val savedCopies=completed.attachmentIds.map { id->File(requireNotNull(repo.dao.file(id)?.path)) }
            assertEquals(listOf("first attachment","second attachment"),savedCopies.map(File::readText))
            assertTrue("Inbox source copies are removed only after all attachments are durably copied",draft.files.none { File(it.path).exists() })
            assertEquals(1,repo.dao.items().count { WireJson.decodeFromString<Item>(it.localJson).id==draft.id })
            assertTrue(app.shareInbox.drafts.value.isEmpty())
        } finally {
            owner.viewModelStore.clear()
        }
    }
    private fun account(grantNotificationPermission: Boolean = true): Session {
        if (grantNotificationPermission && Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(app.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val session=Api(TestServer.url).post<Credentials,Session>("/api/v1/auth/register",Credentials("${UUID.randomUUID()}@feature.android.test","test-password-123"))
        app.preferences.automatic(false);app.preferences.save(session,TestServer.url)
        assertEquals("The test session must survive encrypted preference storage",session.userId,app.preferences.current()?.userId)
        return session
    }
    @Test fun reminderPermissionIsRequestedAfterSavingAndDeferringKeepsTheTask() {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT >= 33)
        org.junit.Assume.assumeTrue(ContextCompat.checkSelfPermission(app,Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_DENIED)
        account(grantNotificationPermission=false)
        app.getSharedPreferences("reminder-settings",Context.MODE_PRIVATE).edit().remove("notification-permission-prompt-shown").commit()
        val title="Task with reminder without permission"
        val date=LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Добавить").performClick()
            compose.onNodeWithText("Задачу").performClick()
            compose.onNodeWithText("Название").performTextInput(title)
            compose.onNodeWithText("Дата · дд.мм.гггг").performTextInput(date)
            compose.onNodeWithText("Время · чч:мм, необязательно").performTextInput("18:00")
            compose.onNodeWithText("Напомнить за минут · необязательно").performTextInput("30")
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Напоминания Habit Tracker").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(app,Manifest.permission.POST_NOTIFICATIONS))
            compose.onNodeWithText("Не сейчас").performClick()
            runBlocking(Dispatchers.IO) {
                val saved=app.repository()!!.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }
                    .mapNotNull { it.content as? TaskContent }.single { it.title==title }
                assertEquals(30,saved.reminderMinutes)
            }
        }
    }
    @Test fun unsavedEditorDraftSurvivesActivityRecreation() {
        account()
        val title = "Draft survives rotation ${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(15000) { compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Добавить").performClick()
            compose.onNodeWithText("Задачу").performClick()
            compose.onNodeWithText("Название").performTextInput(title)
            compose.onNodeWithText("Описание").performTextInput("Текст, который ещё не сохранён")

            scenario.recreate()

            compose.waitUntil(15000) { compose.onAllNodesWithText("Сохранить").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).assertExists()
            compose.onNodeWithText("Текст, который ещё не сохранён").assertExists()
            compose.onNodeWithText("Закрыть").performClick()
            assertTrue(runBlocking(Dispatchers.IO) { app.repository()!!.dao.items().none {
                (WireJson.decodeFromString<Item>(it.localJson).content as? TaskContent)?.title == title
            } })
        }
    }
    @Test fun passwordSettingsCanBeOpenedAndCancelled() {
        account()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Настройки").performClick()
            compose.onNodeWithText("Изменить пароль").performClick()
            compose.onNodeWithText("Текущий пароль, если есть").assertExists()
            compose.onNodeWithText("Сохранить пароль").assertExists()
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithText("Сохранить пароль").assertDoesNotExist()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Сегодня").fetchSemanticsNodes().isNotEmpty() }
        }
    }
    @Test fun taskLinkCreatesTaskWhenTheUserOpensIt() = runBlocking(Dispatchers.IO) {
        val session = account()
        val repo = Repository(app, session, false)
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        app.shareInbox.error.value = null
        val link = Uri.parse("habittracker://import/task?title=Draft%20from%20ChatGPT&date=2027-01-20&time=18%3A00")
        val intent = Intent(Intent.ACTION_VIEW, link).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(app.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            withTimeout(45000) {
                while (repo.dao.items().none {
                    (WireJson.decodeFromString<Item>(it.localJson).content as? TaskContent)?.title == "Draft from ChatGPT"
                } && app.shareInbox.error.value == null) delay(100)
            }
            assertNull(app.shareInbox.error.value)
            val task = repo.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }
                .mapNotNull { it.content as? TaskContent }.single { it.title == "Draft from ChatGPT" }
            assertEquals("Draft from ChatGPT", task.title)
            assertEquals("2027-01-20", task.dueDate)
            assertEquals("18:00", task.dueTime)
            withTimeout(15000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
            compose.onNodeWithText("Draft from ChatGPT").assertIsDisplayed()
        }
        repo.database.close()
    }
    @Test fun noteLinkCreatesNoteWhenTheUserOpensIt() = runBlocking(Dispatchers.IO) {
        val session = account()
        val repo = Repository(app, session, false)
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        app.shareInbox.error.value = null
        val link = Uri.parse("habittracker://import/note?title=Note%20from%20ChatGPT&text=Keep%20this%20idea")
        val intent = Intent(Intent.ACTION_VIEW, link).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(app.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            it.onActivity { activity ->
                assertEquals("A note link must open the signed-in account",session.userId,
                    ViewModelProvider(activity).get(AppViewModel::class.java).session.value?.userId)
            }
            withTimeout(20000) {
                while (repo.dao.items().none {
                    (WireJson.decodeFromString<Item>(it.localJson).content as? NoteContent)?.title == "Note from ChatGPT"
                } && app.shareInbox.error.value == null) delay(100)
            }
            assertNull(app.shareInbox.error.value)
            val note = repo.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }
                .mapNotNull { it.content as? NoteContent }.single { it.title == "Note from ChatGPT" }
            assertEquals("Note from ChatGPT", note.title)
            assertEquals("Keep this idea", note.text)
            withTimeout(15000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
            compose.onNodeWithText("Note from ChatGPT").assertIsDisplayed()
        }
        repo.database.close()
    }
    @Test fun weekdayHabitLinkCreatesHabitWhenTheUserOpensIt() = runBlocking(Dispatchers.IO) {
        val session = account()
        val repo = Repository(app, session, false)
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        app.shareInbox.error.value = null
        val title = "Run weekdays from ChatGPT"
        val link = Uri.parse("habittracker://import/habit?title=Run%20weekdays%20from%20ChatGPT&days=2%2C4")
        val intent = Intent(Intent.ACTION_VIEW, link).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(app.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            withTimeout(20000) {
                while (repo.dao.items().none {
                    (WireJson.decodeFromString<Item>(it.localJson).content as? HabitContent)?.title == title
                } && app.shareInbox.error.value == null) delay(100)
            }
            assertNull(app.shareInbox.error.value)
            val habit = repo.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }
                .mapNotNull { it.content as? HabitContent }.single { it.title == title }
            assertEquals(HabitMode.WEEKDAYS, habit.mode)
            assertEquals(setOf(2, 4), habit.weekdays)
            assertEquals(LocalDate.now(ZoneId.systemDefault()).toString(), habit.startsOn)
            withTimeout(30000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            withTimeout(30000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
        }
        assertEquals(1, repo.dao.items().count { (WireJson.decodeFromString<Item>(it.localJson).content as? HabitContent)?.title == title })
        repo.database.close()
    }
    @Test fun weeklyTargetHabitLinkCreatesHabitWhenTheUserOpensIt() = runBlocking(Dispatchers.IO) {
        val session = account()
        val repo = Repository(app, session, false)
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        app.shareInbox.error.value = null
        val title = "Stretch three times from ChatGPT"
        val link = Uri.parse("habittracker://import/habit?title=Stretch%20three%20times%20from%20ChatGPT&target=3")
        val intent = Intent(Intent.ACTION_VIEW, link).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(app.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            withTimeout(20000) {
                while (repo.dao.items().none {
                    (WireJson.decodeFromString<Item>(it.localJson).content as? HabitContent)?.title == title
                } && app.shareInbox.error.value == null) delay(100)
            }
            assertNull(app.shareInbox.error.value)
            val habit = repo.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }
                .mapNotNull { it.content as? HabitContent }.single { it.title == title }
            assertEquals(HabitMode.WEEKLY_TARGET, habit.mode)
            assertEquals(3, habit.targetPerWeek)
            assertTrue(habit.weekdays.isEmpty())
            withTimeout(15000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
            compose.onNodeWithText(title).assertIsDisplayed()
        }
        repo.database.close()
    }
    @Test fun verifiedHttpsAppLinkCreatesHabitWhenOpened() = runBlocking(Dispatchers.IO) {
        val session = account()
        val repo = Repository(app, session, false)
        app.shareInbox.drafts.value.toList().forEach { app.shareInbox.remove(it.id) }
        app.shareInbox.error.value = null
        val title = "Habit from verified web link"
        val link = Uri.parse("https://narysh.github.io/habit-tracker/task/#kind=habit&title=Habit%20from%20verified%20web%20link&days=2%2C4")
        val intent = Intent(Intent.ACTION_VIEW, link).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(app.packageManager)?.className)
        ActivityScenario.launch<MainActivity>(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            withTimeout(20000) {
                while (repo.dao.items().none {
                    (WireJson.decodeFromString<Item>(it.localJson).content as? HabitContent)?.title == title
                } && app.shareInbox.error.value == null) delay(100)
            }
            assertNull(app.shareInbox.error.value)
            val habit = repo.dao.items().map { WireJson.decodeFromString<Item>(it.localJson) }
                .mapNotNull { it.content as? HabitContent }.single { it.title == title }
            assertEquals(HabitMode.WEEKDAYS, habit.mode)
            assertEquals(setOf(2, 4), habit.weekdays)
            withTimeout(15000) { while (app.shareInbox.drafts.value.isNotEmpty()) delay(100) }
            compose.onNodeWithText(title).assertIsDisplayed()
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
        ActivityScenario.launch<MainActivity>(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use {
            it.onActivity { activity ->
                assertEquals("Quick entry must open the signed-in account",session.userId,
                    ViewModelProvider(activity).get(AppViewModel::class.java).session.value?.userId)
            }
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
    @Test fun sharingTextAndFileSavesNoteAutomatically()=runBlocking(Dispatchers.IO) {
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
        assertTrue(draft.autoSave)
        assertTrue(repo.dao.items().isEmpty())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000){compose.onAllNodesWithText("Заметка добавлена").fetchSemanticsNodes().isNotEmpty()}
            withTimeout(15000){while(app.shareInbox.drafts.value.isNotEmpty())delay(100)}
            val note=WireJson.decodeFromString<Item>(repo.dao.item(draft.id)!!.localJson).content as NoteContent
            assertEquals("https://example.com/article",note.text)
            assertEquals("shared-private-copy",File(repo.dao.file(note.attachmentIds.single())!!.path!!).readText())
        }
        repo.database.close()
    }
    @Test fun noteSearchFindsAttachmentNameAndStillFindsNoteText()=runBlocking(Dispatchers.IO) {
        Assume.assumeTrue(Build.VERSION.SDK_INT>=29)
        val session=account();val repo=Repository(app,session,false)
        val noteId=UUID.randomUUID().toString()
        val fileName="search-attachment-${UUID.randomUUID()}.txt"
        val uri=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,fileName)
            put(MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
        })!!
        app.contentResolver.openOutputStream(uri)!!.use { it.write("attachment search fixture".toByteArray()) }
        repo.save(NoteContent("Заметка для поиска", "Редкая фраза внутри заметки"),noteId,notify=false)
        repo.attach(noteId,uri,fileName,"text/plain",notify=false)
        app.contentResolver.delete(uri,null,null)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Заметки").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Заметки").performClick()
            compose.onNodeWithText("Найти заметку").performTextInput("редкая фраза")
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Заметка для поиска").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Заметка для поиска").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Найти заметку").performTextClearance()
            compose.onNodeWithText("Найти заметку").performTextInput(fileName)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Заметка для поиска").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Заметка для поиска").performScrollTo().assertIsDisplayed()
        }
        repo.database.close()
    }
    @Test fun sharingTaskDirectiveCreatesTaskAndParsesItsDeadline()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        app.shareInbox.drafts.value.toList().forEach {app.shareInbox.remove(it.id)}
        val tomorrow=LocalDate.now().plusDays(1)
        app.shareInbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,"Задача: Позвонить врачу завтра в 18:30\nПопросить направление на анализы"
        ))
        val draft=app.shareInbox.drafts.value.single()
        assertTrue(draft.autoSave);assertNotNull(draft.task)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000){runBlocking(Dispatchers.IO){repo.dao.item(draft.id)!=null && app.shareInbox.drafts.value.isEmpty()}}
            val task=WireJson.decodeFromString<Item>(repo.dao.item(draft.id)!!.localJson).content as TaskContent
            assertEquals("Позвонить врачу",task.title)
            assertEquals(tomorrow.toString(),task.dueDate)
            assertEquals("18:30",task.dueTime)
            assertEquals("Попросить направление на анализы",task.description)
            compose.onNodeWithText("Позвонить врачу").assertIsDisplayed()
        }
        repo.database.close()
    }
    @Test fun sharingAmbiguousTaskOpensReviewInsteadOfGuessing()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        app.shareInbox.drafts.value.toList().forEach {app.shareInbox.remove(it.id)}
        app.shareInbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,"Задача: Позвонить врачу в пятницу вечером"
        ))
        val draft=app.shareInbox.drafts.value.single()
        assertFalse(draft.autoSave);assertNotNull(draft.task)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000){compose.onAllNodesWithText("Сохранить").fetchSemanticsNodes().isNotEmpty()}
            assertNull(repo.dao.item(draft.id))
            compose.onNodeWithText("Сохранить").performClick()
            withTimeout(15000){while(repo.dao.item(draft.id)==null)delay(100)}
        }
        repo.database.close()
    }
    @Test fun sharingHabitDirectiveCreatesHabitOnSelectedWeekdays()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        app.shareInbox.drafts.value.toList().forEach {app.shareInbox.remove(it.id)}
        app.shareInbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,"Привычка: Бегать\nДни: вторник, четверг"
        ))
        val draft=app.shareInbox.drafts.value.single()
        assertTrue(draft.autoSave);assertEquals(setOf(2,4),draft.habit?.weekdays)
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(15000){while(repo.dao.item(draft.id)==null || app.shareInbox.drafts.value.isNotEmpty())delay(100)}
            val habit=WireJson.decodeFromString<Item>(repo.dao.item(draft.id)!!.localJson).content as HabitContent
            assertEquals("Бегать",habit.title);assertEquals(setOf(2,4),habit.weekdays)
            compose.onNodeWithText("Бегать").assertIsDisplayed()
        }
        repo.database.close()
    }
    @Test fun sharingHabitWeeklyTargetParsesTargetWithoutGuessingDays()=runBlocking(Dispatchers.IO) {
        app.shareInbox.drafts.value.toList().forEach {app.shareInbox.remove(it.id)}
        app.shareInbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,"Привычка: Растяжка\nЦель: 3 дня в неделю"
        ))
        val draft=app.shareInbox.drafts.value.single()
        assertTrue(draft.autoSave)
        assertEquals(HabitMode.WEEKLY_TARGET,draft.habit?.mode)
        assertEquals(3,draft.habit?.targetPerWeek)
        app.shareInbox.remove(draft.id)
    }
    @Test fun sharingHabitWithUnknownScheduleRequiresReview()=runBlocking(Dispatchers.IO) {
        val session=account();val repo=Repository(app,session,false)
        app.shareInbox.drafts.value.toList().forEach {app.shareInbox.remove(it.id)}
        app.shareInbox.receive(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
            Intent.EXTRA_TEXT,"Привычка: Бегать\nДни: когда будет время"
        ))
        val draft=app.shareInbox.drafts.value.single()
        assertFalse(draft.autoSave);assertTrue(draft.habit?.weekdays.isNullOrEmpty());assertNotNull(draft.reviewWarning)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000){compose.onAllNodesWithText(draft.reviewWarning!!).fetchSemanticsNodes().isNotEmpty()}
            assertNull(repo.dao.item(draft.id))
        }
        app.shareInbox.remove(draft.id)
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
        withTimeout(10000){while(manager.activeNotifications.any {it.id==id.hashCode()})delay(100)}
        Reminders.show(app,item,session.userId)
        withTimeout(10000){while(manager.activeNotifications.none {it.id==id.hashCode()})delay(100)}
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
