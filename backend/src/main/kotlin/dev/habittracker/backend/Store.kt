package dev.habittracker.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.habittracker.shared.*
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

data class Identity(val userId: String, val email: String, val scopes: Set<String>)
class Store(jdbcUrl: String, username: String, password: String) : AutoCloseable {
    val pool = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl; this.username = username; this.password = password
        maximumPoolSize = 8; connectionTimeout = 10000
    })
    init { Flyway.configure().dataSource(pool).load().migrate() }
    fun databaseAvailable(): Boolean = try {
        pool.connection.use { it.isValid(2) }
    } catch (_: java.sql.SQLException) {
        false
    }
    private fun <T> tx(block: (Connection) -> T): T = pool.connection.use { c ->
        c.autoCommit = false
        try { val result = block(c); c.commit(); result } catch (e: Throwable) { c.rollback(); throw e }
    }
    private fun lockAccount(c: Connection, userId: String) {
        c.prepareStatement("SELECT id FROM users WHERE id=?::uuid FOR UPDATE").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { if (!it.next()) throw ApiFailure(401, "Войди в аккаунт") }
        }
    }
    private fun normalize(email: String): String = email.trim().lowercase(Locale.ROOT)
    fun register(credentials: Credentials): Session {
        val email = normalize(credentials.email)
        require(email.length <= 254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email)) { "Некорректный email" }
        require(credentials.password.length in 10..128) { "Пароль: от 10 до 128 символов" }
        val hash = Passwords.hash(credentials.password)
        val id = UUID.randomUUID().toString()
        try {
            tx { c -> c.prepareStatement("INSERT INTO users(id,email,password_hash) VALUES (?::uuid,?,?)").use {
                it.setString(1,id); it.setString(2,email); it.setString(3,hash); it.executeUpdate()
            } }
        } catch (e: java.sql.SQLException) {
            if (e.sqlState == "23505") throw ApiFailure(409,"Этот email уже зарегистрирован") else throw e
        }
        return newSession(Identity(id,email,setOf("tracker:read","tracker:write")))
    }
    fun checkPassword(credentials: Credentials): Identity? {
        require(credentials.password.length <= 128)
        val user = pool.connection.use { c -> c.prepareStatement("SELECT id,email,password_hash FROM users WHERE email=?").use {
            it.setString(1,normalize(credentials.email)); it.executeQuery().use { r ->
                if (r.next()) Triple(r.getString(1),r.getString(2),r.getString(3)) else null
            }
        } }
        if (user == null) { Passwords.hash(credentials.password); return null }
        if (user.third == null || !Passwords.verify(credentials.password,user.third)) return null
        return Identity(user.first,user.second,setOf("tracker:read","tracker:write"))
    }
    fun login(credentials: Credentials): Session = newSession(checkPassword(credentials) ?: throw ApiFailure(401,"Неверный email или пароль"))
    fun linkedProviders(userId: String): Set<String> = pool.connection.use { c ->
        c.prepareStatement("SELECT provider FROM social_identities WHERE user_id=?::uuid").use { p ->
            p.setString(1,userId); p.executeQuery().use { r -> buildSet { while(r.next()) add(r.getString(1)) } }
        }
    }
    fun socialIdentity(provider: String,subject: String,email: String?,linkUser: String?): Identity = tx { c ->
        require(provider in setOf("google","vk","yandex") && subject.isNotBlank() && subject.length<=512)
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { it.setString(1,"social:$provider:$subject");it.execute() }
        val existing=c.prepareStatement("SELECT user_id FROM social_identities WHERE provider=? AND subject=?").use { p -> p.setString(1,provider);p.setString(2,subject);p.executeQuery().use { if(it.next()) it.getString(1) else null } }
        if(existing!=null && linkUser!=null && existing!=linkUser) throw ApiFailure(409,"Этот способ входа уже привязан к другому аккаунту")
        val id=existing ?: linkUser ?: UUID.randomUUID().toString()
        if (existing != null || linkUser != null) lockAccount(c, id)
        if(existing==null) {
            if(linkUser==null) {
                val address=email?.trim()?.lowercase()?.takeIf { it.length<=254 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(it) } ?: "$provider-${digest(subject).take(32)}@identity.invalid"
                val taken=c.prepareStatement("SELECT 1 FROM users WHERE email=?").use { p -> p.setString(1,address);p.executeQuery().use {it.next()} }
                if(taken) throw ApiFailure(409,"Аккаунт с этой почтой уже существует. Войди прежним способом и привяжи провайдера в настройках.")
                c.prepareStatement("INSERT INTO users(id,email,password_hash) VALUES (?::uuid,?,NULL)").use {p->p.setString(1,id);p.setString(2,address);p.executeUpdate()}
            }
            val linked=c.prepareStatement("SELECT 1 FROM social_identities WHERE user_id=?::uuid AND provider=?").use {p->p.setString(1,id);p.setString(2,provider);p.executeQuery().use {it.next()}}
            if(linked) throw ApiFailure(409,"Другой профиль этого провайдера уже привязан")
            c.prepareStatement("INSERT INTO social_identities VALUES (?,?,?::uuid)").use {p->p.setString(1,provider);p.setString(2,subject);p.setString(3,id);p.executeUpdate()}
        }
        val address=c.prepareStatement("SELECT email FROM users WHERE id=?::uuid").use {p->p.setString(1,id);p.executeQuery().use {check(it.next());it.getString(1)}}
        Identity(id,address,setOf("tracker:read","tracker:write"))
    }
    fun newSession(identity: Identity, hours: Long = 24 * 30): Session {
        val token = secret(); val expiry = Instant.now().plusSeconds(hours * 3600)
        tx { c ->
            lockAccount(c, identity.userId)
            c.prepareStatement("INSERT INTO sessions (token_hash,user_id,scopes,expires_at) VALUES (?,?::uuid,?,?)").use {
                it.setString(1,digest(token)); it.setString(2,identity.userId); it.setString(3,identity.scopes.sorted().joinToString(" "))
                it.setTimestamp(4,Timestamp.from(expiry)); it.executeUpdate()
            }
        }
        return Session(token,identity.userId,identity.email,expiry.toString())
    }
    fun authenticate(token: String, appSessionOnly: Boolean = false): Identity? = pool.connection.use { c -> c.prepareStatement(
        """SELECT u.id,u.email,s.scopes FROM sessions s JOIN users u ON u.id=s.user_id
            WHERE s.token_hash=? AND s.expires_at>now()
            AND (NOT ? OR NOT EXISTS (SELECT 1 FROM oauth_refresh_tokens r WHERE r.access_hash=s.token_hash))"""
    ).use { it.setString(1,digest(token)); it.setBoolean(2,appSessionOnly); it.executeQuery().use { r -> if(r.next()) Identity(r.getString(1),r.getString(2),r.getString(3).split(' ').toSet()) else null } } }
    fun authenticatePlugin(token: String, clientId: String, resource: String): Identity? = pool.connection.use { c ->
        c.prepareStatement("""
            SELECT u.id, u.email, s.scopes FROM sessions s
            JOIN users u ON u.id = s.user_id
            JOIN oauth_refresh_tokens r ON r.access_hash = s.token_hash AND r.user_id = s.user_id
            WHERE s.token_hash = ? AND s.expires_at > now() AND r.client_id = ? AND r.resource = ?
        """.trimIndent()).use { statement ->
            statement.setString(1, digest(token))
            statement.setString(2, clientId)
            statement.setString(3, resource)
            statement.executeQuery().use { rows ->
                if (rows.next()) Identity(rows.getString(1), rows.getString(2), rows.getString(3).split(' ').toSet()) else null
            }
        }
    }
    fun revokeOAuth(token: String, clientId: String, resource: String) = tx { c ->
        val hash = digest(token)
        c.prepareStatement("""DELETE FROM sessions WHERE token_hash IN (
            SELECT access_hash FROM oauth_refresh_tokens WHERE client_id=? AND resource=?
            AND (access_hash=? OR token_hash=?))""").use { statement ->
            statement.setString(1, clientId)
            statement.setString(2, resource)
            statement.setString(3, hash)
            statement.setString(4, hash)
            statement.executeUpdate()
        }
    }
    fun hasOAuthConnection(userId: String, clientId: String, resource: String): Boolean = pool.connection.use { c ->
        c.prepareStatement("SELECT 1 FROM oauth_refresh_tokens WHERE user_id=?::uuid AND client_id=? AND resource=? AND expires_at>now() LIMIT 1").use { statement ->
            statement.setString(1, userId)
            statement.setString(2, clientId)
            statement.setString(3, resource)
            statement.executeQuery().use { it.next() }
        }
    }
    fun revokeOAuthConnection(userId: String, clientId: String, resource: String) = tx { c ->
        c.prepareStatement("""DELETE FROM sessions WHERE user_id=?::uuid AND token_hash IN (
            SELECT access_hash FROM oauth_refresh_tokens WHERE user_id=?::uuid AND client_id=? AND resource=?)""").use { statement ->
            statement.setString(1, userId)
            statement.setString(2, userId)
            statement.setString(3, clientId)
            statement.setString(4, resource)
            statement.executeUpdate()
        }
    }
    fun revoke(token: String) { tx { c ->
        val hash=digest(token)
        val access=c.prepareStatement("SELECT access_hash FROM oauth_refresh_tokens WHERE token_hash=?").use { it.setString(1,hash); it.executeQuery().use { r -> if(r.next()) r.getString(1) else null } }
        c.prepareStatement("DELETE FROM sessions WHERE token_hash=? OR token_hash=?").use { it.setString(1,hash); it.setString(2,access ?: hash); it.executeUpdate() }
    } }
    fun revokeAll(userId: String) { tx { c -> c.prepareStatement("DELETE FROM sessions WHERE user_id=?::uuid").use { it.setString(1,userId); it.executeUpdate() } } }
    fun deleteAccount(userId: String, appToken: String) = tx { c ->
        lockAccount(c, userId)
        deleteAccountRows(c, userId, digest(appToken))
    }
    fun accountExists(userId: String): Boolean = pool.connection.use { c ->
        c.prepareStatement("SELECT 1 FROM users WHERE id=?::uuid").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { it.next() }
        }
    }
    fun purgeDeletedAccountFiles(directory: Path): Int = tx { c -> purgeAccountFiles(c, directory) }

    private fun issueOAuth(c: Connection,identity: Identity,clientId: String,resource: String): OAuthTokens {
        lockAccount(c, identity.userId)
        val access=secret(); val refresh=secret(); val now=Instant.now(); val scopes=identity.scopes.sorted().joinToString(" ")
        c.prepareStatement("INSERT INTO sessions (token_hash,user_id,scopes,expires_at) VALUES (?,?::uuid,?,?)").use { it.setString(1,digest(access));it.setString(2,identity.userId);it.setString(3,scopes);it.setTimestamp(4,Timestamp.from(now.plusSeconds(3600)));it.executeUpdate() }
        c.prepareStatement("INSERT INTO oauth_refresh_tokens VALUES (?,?,?::uuid,?,?,?,?)").use { it.setString(1,digest(refresh));it.setString(2,digest(access));it.setString(3,identity.userId);it.setString(4,clientId);it.setString(5,resource);it.setString(6,scopes);it.setTimestamp(7,Timestamp.from(now.plusSeconds(30L*24*3600)));it.executeUpdate() }
        return OAuthTokens(access,refresh,scopes)
    }
    fun issueOAuth(identity: Identity,clientId: String,resource: String): OAuthTokens = tx { issueOAuth(it,identity,clientId,resource) }
    fun refreshOAuth(refresh: String,clientId: String,resource: String): OAuthTokens = tx { c ->
        val refreshHash = digest(refresh)
        val userId = c.prepareStatement("SELECT user_id FROM oauth_refresh_tokens WHERE token_hash=?").use { statement ->
            statement.setString(1, refreshHash)
            statement.executeQuery().use { if (it.next()) it.getString(1) else throw ApiFailure(401, "invalid_grant") }
        }
        lockAccount(c, userId)
        val grant=c.prepareStatement("SELECT r.user_id,u.email,r.scopes,r.access_hash,r.client_id,r.resource FROM oauth_refresh_tokens r JOIN users u ON u.id=r.user_id WHERE r.token_hash=? AND r.expires_at>now()").use {
            it.setString(1,refreshHash); it.executeQuery().use { r -> if(!r.next()) throw ApiFailure(401,"invalid_grant") else listOf(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)) }
        }
        require(grant[4]==clientId && grant[5]==resource) { "Invalid refresh token binding" }
        c.prepareStatement("DELETE FROM sessions WHERE token_hash=?").use {
            it.setString(1,grant[3])
            if (it.executeUpdate() != 1) throw ApiFailure(401, "invalid_grant")
        }
        issueOAuth(c,Identity(grant[0],grant[1],grant[2].split(' ').toSet()),clientId,resource)
    }
    fun list(userId: String): List<Item> = pool.connection.use { c -> c.prepareStatement("SELECT * FROM items WHERE user_id=?::uuid ORDER BY sequence").use {
        it.setString(1,userId); it.executeQuery().use { r -> buildList { while(r.next()) add(r.item()) } }
    } }
    fun get(userId: String,id: String): Item? = pool.connection.use { find(it,userId,id) }
    private fun find(c: Connection, userId: String,id: String): Item? = c.prepareStatement("SELECT * FROM items WHERE user_id=?::uuid AND id=?::uuid").use {
        it.setString(1,userId); it.setString(2,id); it.executeQuery().use { r -> if(r.next()) r.item() else null }
    }
    private fun ResultSet.item() = Item(getString("id"),getLong("version"),WireJson.decodeFromString<Content>(getString("body")),getBoolean("deleted"),getTimestamp("updated_at").toInstant().toString(),getLong("sequence"))
    fun mutate(userId: String, mutation: Mutation): MutationResult = tx { c -> mutate(c,userId,mutation) }
    private fun mutate(c: Connection,userId: String,mutation: Mutation,allowPlanApply: Boolean=false): MutationResult {
        UUID.fromString(mutation.operationId); UUID.fromString(mutation.itemId)
        require(mutation.baseVersion >= 0)
        // One account's sequence and operation journal advance in commit order.
        lockAccount(c, userId)
        val request = WireJson.encodeToString(mutation)
        val replay = c.prepareStatement("SELECT request,result FROM operations WHERE user_id=?::uuid AND id=?::uuid").use {
            it.setString(1,userId); it.setString(2,mutation.operationId)
            it.executeQuery().use { r -> if(r.next()) r.getString(1) to r.getString(2) else null }
        }
        if (replay != null) {
            if (replay.first != request) throw ApiFailure(409,"operationId уже использован с другими данными")
            return WireJson.decodeFromString<MutationResult>(replay.second)
        }
        val current = find(c,userId,mutation.itemId)
        val result = if ((current?.version ?: 0) != mutation.baseVersion) {
            MutationResult(mutation.operationId,MutationStatus.CONFLICT,current,"Запись изменена на другом устройстве")
        } else {
            mutation.content.validate()
            val incomingPlan=mutation.content as? WeekPlanContent
            if(incomingPlan!=null && !allowPlanApply) {
                val previous=current?.content as? WeekPlanContent
                require(incomingPlan.applied==(previous?.applied ?: false)) { "План применяется только через подтверждение в приложении" }
                require(mutation.deleted || previous?.applied!=true || incomingPlan==previous) { "Применённый план нельзя изменить" }
            }
            if(!mutation.deleted) {
                val org=mutation.content.organization
                org.projectId?.let { require(find(c,userId,it)?.content is ProjectContent) { "Проект не найден в аккаунте" } }
                org.goalId?.let { require((find(c,userId,it)?.content as? ProjectContent)?.isGoal==true) { "Цель не найдена в аккаунте" } }
                org.linkedIds.forEach { require(it!=mutation.itemId && find(c,userId,it)?.content?.let {it !is HabitMarkContent}==true) { "Связанная запись не найдена в аккаунте" } }
                (mutation.content as? WeekPlanContent)?.moves?.forEach { require(find(c,userId,it.itemId)?.content is TaskContent) { "В плане есть чужая или отсутствующая задача" } }
            }
            require(current == null || current.content.typeName() == mutation.content.typeName()) { "Нельзя менять тип записи" }
            val oldProject=current?.content as? ProjectContent;val newProject=mutation.content as? ProjectContent
            if(!mutation.deleted && oldProject?.isGoal==true && newProject?.isGoal==false) {
                val linked=c.prepareStatement("SELECT 1 FROM items WHERE user_id=?::uuid AND NOT deleted AND body->'organization'->>'goalId'=? LIMIT 1").use {p->p.setString(1,userId);p.setString(2,mutation.itemId);p.executeQuery().use {it.next()}}
                require(!linked){"Сначала убери эту цель из связанных записей"}
            }
            // A create followed by an offline delete must synchronize as a tombstone,
            // even when the server has never seen the active record.
            val mark = mutation.content as? HabitMarkContent
            if (mark != null) {
                require(mutation.itemId == markId(mark.habitId,LocalDate.parse(mark.date))) { "Неверный идентификатор отметки" }
                val parent = find(c,userId,mark.habitId)
                val habit = parent?.content as? HabitContent ?: throw IllegalArgumentException("Привычка не найдена")
                require(!parent.deleted)
                val date = LocalDate.parse(mark.date)
                require(date <= LocalDate.now(ZoneId.of(habit.zoneId)) && habit.isDue(date)) { "Нельзя отметить будущий или незапланированный день" }
            }
            val sequence = c.prepareStatement("UPDATE users SET revision=revision+1 WHERE id=?::uuid RETURNING revision").use {
                it.setString(1,userId); it.executeQuery().use { r -> r.next(); r.getLong(1) }
            }
            val persisted=when(val content=mutation.content) {
                is TaskContent->if(!content.completed) content.copy(completedAt=null,completionDateUnknown=false) else {val previous=current?.content as? TaskContent;content.copy(completedAt=if(content.completionDateUnknown)null else content.completedAt ?: if(previous?.completed==true)previous.completedAt else Instant.now().toString())}
                else->content
            }
            val item = Item(mutation.itemId,(current?.version ?: 0)+1,persisted,mutation.deleted,Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString(),sequence)
            c.prepareStatement("""INSERT INTO items(user_id,id,version,body,deleted,updated_at,sequence) VALUES (?::uuid,?::uuid,?,?::jsonb,?,?,?)
                ON CONFLICT(user_id,id) DO UPDATE SET version=excluded.version,body=excluded.body,deleted=excluded.deleted,updated_at=excluded.updated_at,sequence=excluded.sequence""").use {
                it.setString(1,userId); it.setString(2,item.id); it.setLong(3,item.version); it.setString(4,WireJson.encodeToString<Content>(item.content))
                it.setBoolean(5,item.deleted); it.setTimestamp(6,Timestamp.from(Instant.parse(item.updatedAt))); it.setLong(7,sequence); it.executeUpdate()
            }
            MutationResult(mutation.operationId,MutationStatus.APPLIED,item)
        }
        c.prepareStatement("INSERT INTO operations VALUES (?::uuid,?::uuid,?,?)").use {
            it.setString(1,userId); it.setString(2,mutation.operationId); it.setString(3,request); it.setString(4,WireJson.encodeToString(result)); it.executeUpdate()
        }
        return result
    }
    fun applyWeekPlan(userId: String,id: String,expectedVersion: Long): Item = tx { c ->
        UUID.fromString(id)
        lockAccount(c, userId)
        val item=find(c,userId,id) ?: throw ApiFailure(404,"План не найден")
        val plan=item.content as? WeekPlanContent ?: throw ApiFailure(400,"Это не план недели")
        require(!item.deleted)
        if(plan.applied) return@tx item
        if(item.version!=expectedVersion) throw ApiFailure(409,"План изменился. Открой его заново.")
        val tasks=plan.moves.map { move ->
            val task=find(c,userId,move.itemId) ?: throw ApiFailure(409,"Задача из плана больше не существует")
            if(task.deleted || task.version!=move.expectedVersion || (task.content as? TaskContent)?.completed!=false) throw ApiFailure(409,"Задачи изменились. Попроси ассистента обновить план; ничего не применено.")
            task to move
        }
        tasks.forEach { (task,move)->
            val operation=UUID.nameUUIDFromBytes("plan:$id:${item.version}:${task.id}".toByteArray()).toString()
            val result=mutate(c,userId,Mutation(operation,task.id,task.version,(task.content as TaskContent).copy(dueDate=move.dueDate)))
            check(result.status==MutationStatus.APPLIED)
        }
        val result=mutate(c,userId,Mutation(UUID.nameUUIDFromBytes("plan:$id:${item.version}:apply".toByteArray()).toString(),id,item.version,plan.copy(applied=true)),allowPlanApply=true)
        requireNotNull(result.item)
    }
    fun sync(userId: String, request: SyncRequest): SyncResponse {
        require(request.cursor >= 0 && request.mutations.size <= 100)
        val orderedResults = request.mutations.sortedBy { when(it.content){is ProjectContent->0;is HabitMarkContent->2;is WeekPlanContent->3;else->1} }.map { mutation ->
            try { mutate(userId,mutation) } catch (e: IllegalArgumentException) {
                MutationResult(mutation.operationId,MutationStatus.REJECTED,message=e.message ?: "Некорректная операция")
            }
        }
        val results = request.mutations.map { operation -> orderedResults.first { it.operationId==operation.operationId } }
        val changes = pool.connection.use { c -> c.prepareStatement("SELECT * FROM items WHERE user_id=?::uuid AND sequence>? ORDER BY sequence LIMIT 201").use {
            it.setString(1,userId); it.setLong(2,request.cursor); it.executeQuery().use { r -> buildList { while(r.next()) add(r.item()) } }
        } }
        val page = changes.take(200)
        return SyncResponse(results,page,page.lastOrNull()?.sequence ?: request.cursor,changes.size > 200)
    }
    fun attachments(userId: String, noteId: String): List<Attachment> = pool.connection.use { c -> c.prepareStatement("SELECT * FROM attachments WHERE user_id=?::uuid AND note_id=?::uuid").use {
        it.setString(1,userId); it.setString(2,noteId); it.executeQuery().use { r -> buildList { while(r.next()) add(Attachment(r.getString("id"),r.getString("note_id"),r.getString("filename"),r.getString("media_type"),r.getLong("size"))) } }
    } }
    fun saveAttachment(userId: String, attachment: Attachment, storageKey: String, hash: String) = tx { c ->
        lockAccount(c, userId)
        val note = find(c,userId,attachment.noteId) ?: throw ApiFailure(404,"Заметка не найдена")
        val content = note.content as? NoteContent
        require(!note.deleted && content != null && attachment.id in content.attachmentIds)
        c.prepareStatement("INSERT INTO attachments VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?) ON CONFLICT(id) DO NOTHING").use {
            it.setString(1,attachment.id); it.setString(2,userId); it.setString(3,attachment.noteId); it.setString(4,attachment.name)
            it.setString(5,attachment.mediaType); it.setLong(6,attachment.size); it.setString(7,storageKey); it.setString(8,hash); it.executeUpdate()
        }
        val actual = attachmentRow(c,userId,attachment.id) ?: throw ApiFailure(409,"Этот идентификатор файла уже занят")
        require(actual.first == attachment && actual.third == hash) { "Нельзя заменять содержимое существующего вложения" }
        actual.second
    }
    private fun attachmentRow(c: Connection,userId: String,id: String): Triple<Attachment,String,String>? = c.prepareStatement("SELECT * FROM attachments WHERE user_id=?::uuid AND id=?::uuid").use {
        it.setString(1,userId); it.setString(2,id); it.executeQuery().use { r -> if(r.next()) Triple(Attachment(id,r.getString("note_id"),r.getString("filename"),r.getString("media_type"),r.getLong("size")),r.getString("storage_key"),r.getString("sha256")) else null }
    }
    fun attachment(userId: String,id: String): Pair<Attachment,String>? = pool.connection.use { c ->
        val row = attachmentRow(c,userId,id) ?: return@use null
        val parent = find(c,userId,row.first.noteId)
        val note = parent?.content as? NoteContent
        if (parent == null || parent.deleted || note == null || id !in note.attachmentIds) null else row.first to row.second
    }
    override fun close() { pool.close() }
}
