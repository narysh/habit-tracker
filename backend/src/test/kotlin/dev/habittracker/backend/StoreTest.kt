package dev.habittracker.backend

import dev.habittracker.shared.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreTest {
    private lateinit var postgres: EmbeddedPostgres
    private lateinit var store: Store
    @BeforeAll fun start() {
        postgres = EmbeddedPostgres.builder().setPort(0).start()
        store = Store(postgres.getJdbcUrl("postgres","postgres"),"postgres","")
    }
    @AfterAll fun stop() { if(::store.isInitialized) store.close(); if(::postgres.isInitialized) postgres.close() }
    private fun account() = store.register(Credentials("${UUID.randomUUID()}@test.invalid","test-password-123"))
    private fun mutation(content: Content = TaskContent("Проверить"),id: String = UUID.randomUUID().toString(),version: Long = 0) = Mutation(UUID.randomUUID().toString(),id,version,content)
    @Test fun `rate limits survive backend restart and discard expired windows`() {
        repeat(3) { store.checkRateLimit("192.0.2.1", 3) }
        assertFailsWith<ApiFailure> { store.checkRateLimit("192.0.2.1", 3) }
        Store(postgres.getJdbcUrl("postgres","postgres"), "postgres", "").use { restarted ->
            assertFailsWith<ApiFailure> { restarted.checkRateLimit("192.0.2.1", 3) }
            restarted.checkRateLimit("192.0.2.2", 3)
            postgres.postgresDatabase.connection.use { connection ->
                connection.prepareStatement("UPDATE rate_limit_windows SET window_started_at=now()-INTERVAL '3 minutes' WHERE key_hash=?").use {
                    it.setString(1, digest("192.0.2.1")); assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(1, restarted.purgeExpiredRateLimits())
            restarted.checkRateLimit("192.0.2.1", 3)
        }
    }
    @Test fun `OAuth refresh is single use bound to client and revoked with access token`() {
        val user=account()
        val token=store.issueOAuth(Identity(user.userId,user.email,setOf("tracker:read")),"client","https://resource/mcp")
        assertFails {store.refreshOAuth(token.refreshToken,"other","https://resource/mcp")}
        val renewed=store.refreshOAuth(token.refreshToken,"client","https://resource/mcp")
        assertNull(store.authenticate(token.accessToken))
        assertEquals(setOf("tracker:read"),store.authenticate(renewed.accessToken)!!.scopes)
        assertFails {store.refreshOAuth(token.refreshToken,"client","https://resource/mcp")}
        store.revoke(renewed.accessToken)
        assertFails {store.refreshOAuth(renewed.refreshToken,"client","https://resource/mcp")}
    }
    @Test fun `accounts authenticate isolate data and revoke access`() {
        val alice = account(); val bob = account()
        assertNotNull(store.authenticate(alice.token))
        assertFails { store.login(Credentials(alice.email,"wrong-password")) }
        val item = store.mutate(alice.userId,mutation()).item!!
        assertNull(store.get(bob.userId,item.id)); assertTrue(store.list(bob.userId).isEmpty())
        assertEquals(item.id,store.list(alice.userId).single().id)
        store.revoke(alice.token); assertNull(store.authenticate(alice.token)); assertNotNull(store.authenticate(bob.token))
    }
    @Test fun `account deletion persists retryable purge jobs for all owned attachments`() {
        val owner = account()
        val other = account()
        val dir = Files.createTempDirectory("habit-deletion-")
        val storage = LocalFilesystemAttachmentStorage(dir)
        fun noteWithFile(userId: String, key: String): Attachment {
            val fileId = UUID.randomUUID().toString()
            val note = mutation(NoteContent("Файл", attachmentIds = listOf(fileId)))
            store.mutate(userId, note)
            return Attachment(fileId, note.itemId, "note.txt", "text/plain", 1).also {
                store.saveAttachment(userId, it, key, "hash")
            }
        }
        val retry = dir.resolve(UUID.randomUUID().toString())
        val ready = dir.resolve(UUID.randomUUID().toString())
        val kept = dir.resolve(UUID.randomUUID().toString())
        val blockedFile = retry.resolve("actively-used")
        try {
            Files.createDirectory(retry)
            Files.writeString(blockedFile, "simulate a failed delete")
            Files.writeString(ready, "a")
            Files.writeString(kept, "b")
            val retryAttachment = noteWithFile(owner.userId, retry.fileName.toString())
            noteWithFile(owner.userId, ready.fileName.toString())
            val keptAttachment = noteWithFile(other.userId, kept.fileName.toString())

            store.deleteAccount(owner.userId, owner.token)
            assertNull(store.attachment(owner.userId, retryAttachment.id))
            assertNotNull(store.attachment(other.userId, keptAttachment.id))
            assertEquals(2, store.purgeDeletedAccountFiles(storage))
            assertFalse(Files.exists(ready))
            assertTrue(Files.exists(retry))
            assertEquals(0, store.purgeDeletedAccountFiles(storage))

            Files.delete(blockedFile)
            postgres.postgresDatabase.connection.use { connection ->
                connection.prepareStatement("UPDATE account_file_purge SET next_attempt_at=now() WHERE storage_key=?").use {
                    it.setString(1, retry.fileName.toString())
                    assertEquals(1, it.executeUpdate())
                }
            }
            Store(postgres.getJdbcUrl("postgres","postgres"), "postgres", "").use { restarted ->
                assertEquals(1, restarted.purgeDeletedAccountFiles(storage))
                assertEquals(0, restarted.purgeDeletedAccountFiles(storage))
            }
            assertFalse(Files.exists(retry))
            assertEquals("b", Files.readString(kept))
        } finally {
            Files.deleteIfExists(blockedFile)
            Files.deleteIfExists(retry)
            Files.deleteIfExists(ready)
            Files.deleteIfExists(kept)
            Files.deleteIfExists(dir)
        }
    }
    @Test fun `unlinked and deleted note attachments are queued and removed without touching another account`() {
        val owner = account()
        val other = account()
        val dir = Files.createTempDirectory("habit-attachment-gc-")
        val storage = LocalFilesystemAttachmentStorage(dir)
        fun noteWithFile(userId: String, key: String): Pair<Item,Attachment> {
            val fileId = UUID.randomUUID().toString()
            val item = store.mutate(userId, mutation(NoteContent("Файл", attachmentIds = listOf(fileId)))).item!!
            val attachment = Attachment(fileId, item.id, "note.txt", "text/plain", 1)
            store.saveAttachment(userId, attachment, key, "test-hash")
            Files.writeString(dir.resolve(key), "x")
            return item to attachment
        }
        val removedKey = UUID.randomUUID().toString()
        val keptKey = UUID.randomUUID().toString()
        val deletedKey = UUID.randomUUID().toString()
        val (note, removed) = noteWithFile(owner.userId, removedKey)
        val (deletedNote, deleted) = noteWithFile(owner.userId, deletedKey)
        val (_, kept) = noteWithFile(other.userId, keptKey)
        try {
            assertEquals(listOf(removed), store.attachments(owner.userId, note.id))
            store.mutate(owner.userId, Mutation(UUID.randomUUID().toString(), note.id, note.version, NoteContent("Файл")))
            assertTrue(store.attachments(owner.userId, note.id).isEmpty())
            assertNull(store.attachment(owner.userId, removed.id))
            assertEquals(1, store.queueUnlinkedAttachmentFiles())
            assertNull(store.attachment(owner.userId, removed.id))
            assertNotNull(store.attachment(other.userId, kept.id))
            assertTrue(store.purgeDeletedAccountFiles(storage) >= 1)
            assertFalse(Files.exists(dir.resolve(removed.id)))
            assertTrue(Files.exists(dir.resolve(keptKey)))

            store.mutate(owner.userId, Mutation(UUID.randomUUID().toString(), deletedNote.id, deletedNote.version, deletedNote.content, deleted = true))
            assertTrue(store.attachments(owner.userId, deletedNote.id).isEmpty())
            assertEquals(1, store.queueUnlinkedAttachmentFiles())
            assertTrue(store.purgeDeletedAccountFiles(storage) >= 1)
            assertFalse(Files.exists(dir.resolve(deletedKey)))
        } finally {
            Files.deleteIfExists(dir.resolve(removedKey))
            Files.deleteIfExists(dir.resolve(deletedKey))
            Files.deleteIfExists(dir.resolve(keptKey))
            Files.deleteIfExists(dir)
        }
    }
    @Test fun `a concurrent OAuth refresh cannot leave valid credentials after account deletion`() {
        val owner = account()
        val identity = checkNotNull(store.authenticate(owner.token))
        val grant = store.issueOAuth(identity, "client", "https://resource/mcp")
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val refresh = executor.submit<OAuthTokens?> {
                start.await()
                try {
                    store.refreshOAuth(grant.refreshToken, "client", "https://resource/mcp")
                } catch (failure: ApiFailure) {
                    assertEquals(401, failure.status)
                    null
                }
            }
            val deletion = executor.submit {
                start.await()
                store.deleteAccount(owner.userId, owner.token)
            }
            start.countDown()
            deletion.get(10, TimeUnit.SECONDS)
            val rotated = refresh.get(10, TimeUnit.SECONDS)
            assertNull(store.authenticate(grant.accessToken))
            rotated?.let {
                assertNull(store.authenticate(it.accessToken))
                assertFailsWith<ApiFailure> { store.refreshOAuth(it.refreshToken, "client", "https://resource/mcp") }
            }
        } finally {
            executor.shutdownNow()
        }
    }
    @Test fun `operation replay is persistent and mismatched reuse fails`() {
        val user = account(); val operation = mutation()
        val first = store.mutate(user.userId,operation)
        assertEquals(first,store.mutate(user.userId,operation))
        assertEquals(1,store.list(user.userId).size)
        assertFails { store.mutate(user.userId,operation.copy(content=TaskContent("Другой текст"))) }
    }
    @Test fun `concurrent writers produce one update and one conflict without losing versions`() {
        val user = account(); val original = store.mutate(user.userId,mutation()).item!!
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index -> executor.submit<MutationResult> { store.mutate(user.userId,mutation(TaskContent("edit $index"),original.id,original.version)) } }
            val results = futures.map { it.get() }
            assertEquals(1,results.count { it.status == MutationStatus.APPLIED })
            assertEquals(1,results.count { it.status == MutationStatus.CONFLICT })
            assertEquals(2,store.get(user.userId,original.id)!!.version)
        } finally { executor.shutdownNow() }
    }
    @Test fun `sync pages changes and preserves tombstones`() {
        val user = account(); val original = store.mutate(user.userId,mutation()).item!!
        val initial = store.sync(user.userId,SyncRequest())
        assertEquals(1,initial.changes.size)
        val deletion = mutation(original.content,original.id,1).copy(deleted=true)
        val after = store.sync(user.userId,SyncRequest(listOf(deletion),initial.nextCursor))
        assertTrue(after.changes.single().deleted)
        assertTrue(store.sync(user.userId,SyncRequest(cursor=after.nextCursor)).changes.isEmpty())
        val offlineDeleted=mutation(TaskContent("Удалено до первой синхронизации")).copy(deleted=true)
        assertTrue(store.mutate(user.userId,offlineDeleted).item!!.deleted)
    }
    @Test fun `habit mark requires owned habit canonical id and nonfuture scheduled day`() {
        val user = account(); val today = LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))
        val habit = store.mutate(user.userId,mutation(HabitContent("Бег",startsOn=today.minusDays(7).toString()))).item!!
        val mark = mutation(HabitMarkContent(habit.id,today.toString()),markId(habit.id,today))
        assertEquals(MutationStatus.APPLIED,store.mutate(user.userId,mark).status)
        assertFails { store.mutate(user.userId,mutation(HabitMarkContent(habit.id,today.plusDays(1).toString()),markId(habit.id,today.plusDays(1)))) }
        assertFails { store.mutate(account().userId,mutation(HabitMarkContent(habit.id,today.toString()),markId(habit.id,today))) }
    }
}
