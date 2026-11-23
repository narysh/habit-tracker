package dev.habittracker.backend

import dev.habittracker.shared.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeatureStoreTest {
    private lateinit var pg:EmbeddedPostgres;private lateinit var store:Store
    @BeforeAll fun start(){pg=EmbeddedPostgres.builder().setPort(0).start();store=Store(pg.getJdbcUrl("postgres","postgres"),"postgres","")}
    @AfterAll fun stop(){store.close();pg.close()}
    private fun user()=store.register(Credentials("${UUID.randomUUID()}@feature.test","test-password-123"))
    private fun create(user: Session,content: Content): Item=store.mutate(user.userId,Mutation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,content)).item!!
    @Test fun `projects goals tags and links remain account scoped`() {
        val alice=user();val bob=user();val project=create(alice,ProjectContent("Учёба"));val goal=create(alice,ProjectContent("Стажировка",isGoal=true))
        val note=create(alice,NoteContent("Конспект"));val task=create(alice,TaskContent("Повторить Kotlin",organization=Organization(project.id,goal.id,setOf("Kotlin"),setOf(note.id))))
        assertTrue(store.get(alice.userId,task.id)!!.content.searchableText().contains("Kotlin"))
        assertFails {create(bob,TaskContent("Чужой проект",organization=Organization(projectId=project.id)))}
        assertFails {create(bob,NoteContent("Чужая связь",organization=Organization(linkedIds=setOf(note.id))))}
        assertNull(store.get(bob.userId,task.id))
    }
    @Test fun `a referenced project or goal cannot be deleted until active records are unlinked`() {
        val alice=user()
        val project=create(alice,ProjectContent("Учёба"))
        val goal=create(alice,ProjectContent("Стажировка",isGoal=true))
        val task=create(alice,TaskContent("Откликнуться",organization=Organization(projectId=project.id,goalId=goal.id)))

        assertFailsWith<IllegalArgumentException> {
            store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),project.id,project.version,project.content,true))
        }
        assertFailsWith<IllegalArgumentException> {
            store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),goal.id,goal.version,goal.content,true))
        }
        assertFalse(store.get(alice.userId,project.id)!!.deleted)
        assertFalse(store.get(alice.userId,goal.id)!!.deleted)

        val unlinked=store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),task.id,task.version,
            (task.content as TaskContent).copy(organization=Organization()))).item!!
        assertTrue(store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),project.id,project.version,project.content,true)).item!!.deleted)
        assertTrue(store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),goal.id,goal.version,goal.content,true)).item!!.deleted)
        assertEquals(2L,unlinked.version)
    }
    @Test fun `AI proposal does not move tasks until explicit atomic application and retry is idempotent`() {
        val alice=user();val a=create(alice,TaskContent("A",dueDate="2026-11-09"));val b=create(alice,TaskContent("B",dueDate="2026-11-09"))
        val plan=create(alice,WeekPlanContent("Более свободная неделя","2026-11-09","Распределить задачи",listOf(PlanMove(a.id,a.version,"2026-11-10"),PlanMove(b.id,b.version,"2026-11-12"))))
        assertEquals("2026-11-09",(store.get(alice.userId,a.id)!!.content as TaskContent).dueDate)
        assertFails {store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),plan.id,plan.version,(plan.content as WeekPlanContent).copy(applied=true)))}
        val applied=store.applyWeekPlan(alice.userId,plan.id,plan.version)
        assertTrue((applied.content as WeekPlanContent).applied)
        assertEquals("2026-11-10",(store.get(alice.userId,a.id)!!.content as TaskContent).dueDate)
        assertEquals("2026-11-12",(store.get(alice.userId,b.id)!!.content as TaskContent).dueDate)
        assertEquals(applied,store.applyWeekPlan(alice.userId,plan.id,plan.version))
        assertEquals(2L,store.get(alice.userId,a.id)!!.version)
    }
    @Test fun `stale proposal changes nothing and cannot refer to another user task`() {
        val alice=user();val bob=user();val a=create(alice,TaskContent("A",dueDate="2026-11-09"));val b=create(alice,TaskContent("B",dueDate="2026-11-09"))
        val plan=create(alice,WeekPlanContent("План","2026-11-09","",listOf(PlanMove(a.id,1,"2026-11-10"),PlanMove(b.id,1,"2026-11-11"))))
        store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),b.id,b.version,(b.content as TaskContent).copy(title="Новая правка")))
        assertFailsWith<ApiFailure>{store.applyWeekPlan(alice.userId,plan.id,1)}
        assertEquals("2026-11-09",(store.get(alice.userId,a.id)!!.content as TaskContent).dueDate)
        assertFalse((store.get(alice.userId,plan.id)!!.content as WeekPlanContent).applied)
        assertFails {create(bob,WeekPlanContent("Чужой план","2026-11-09","",listOf(PlanMove(a.id,1,"2026-11-10"))))}
        assertFailsWith<ApiFailure>{store.applyWeekPlan(bob.userId,plan.id,1)}
    }
    @Test fun `task completion records time while legacy completed history stays unknown`() {
        val alice=user();val task=create(alice,TaskContent("Сделать"))
        val completed=store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),task.id,1,(task.content as TaskContent).copy(completed=true))).item!!
        assertNotNull((completed.content as TaskContent).completedAt)
        store.pool.connection.use {c->c.prepareStatement("UPDATE items SET body=body-'completedAt' WHERE user_id=?::uuid AND id=?::uuid").use {it.setString(1,alice.userId);it.setString(2,task.id);it.executeUpdate()}}
        val edited=store.mutate(alice.userId,Mutation(UUID.randomUUID().toString(),task.id,2,TaskContent("Другое название",completed=true))).item!!
        assertNull((edited.content as TaskContent).completedAt)
    }
}
