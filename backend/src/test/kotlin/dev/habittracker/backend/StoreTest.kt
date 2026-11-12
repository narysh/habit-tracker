package dev.habittracker.backend

import dev.habittracker.shared.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
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
