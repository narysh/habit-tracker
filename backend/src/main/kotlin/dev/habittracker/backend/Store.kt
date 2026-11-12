package dev.habittracker.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.habittracker.shared.*
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
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
    private fun <T> tx(block: (Connection) -> T): T = pool.connection.use { c ->
        c.autoCommit = false
        try { val result = block(c); c.commit(); result } catch (e: Throwable) { c.rollback(); throw e }
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
        if (!Passwords.verify(credentials.password,user.third)) return null
        return Identity(user.first,user.second,setOf("tracker:read","tracker:write"))
    }
    fun login(credentials: Credentials): Session = newSession(checkPassword(credentials) ?: throw ApiFailure(401,"Неверный email или пароль"))
    fun newSession(identity: Identity, hours: Long = 24 * 30): Session {
        val token = secret(); val expiry = Instant.now().plusSeconds(hours * 3600)
        tx { c -> c.prepareStatement("INSERT INTO sessions VALUES (?,?::uuid,?,?)").use {
            it.setString(1,digest(token)); it.setString(2,identity.userId); it.setString(3,identity.scopes.sorted().joinToString(" "))
            it.setTimestamp(4,Timestamp.from(expiry)); it.executeUpdate()
        } }
        return Session(token,identity.userId,identity.email,expiry.toString())
    }
    fun authenticate(token: String): Identity? = pool.connection.use { c -> c.prepareStatement(
        "SELECT u.id,u.email,s.scopes FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now()"
    ).use { it.setString(1,digest(token)); it.executeQuery().use { r -> if(r.next()) Identity(r.getString(1),r.getString(2),r.getString(3).split(' ').toSet()) else null } } }
    fun revoke(token: String) { tx { c ->
        val hash=digest(token)
        val access=c.prepareStatement("SELECT access_hash FROM oauth_refresh_tokens WHERE token_hash=?").use { it.setString(1,hash); it.executeQuery().use { r -> if(r.next()) r.getString(1) else null } }
        c.prepareStatement("DELETE FROM sessions WHERE token_hash=? OR token_hash=?").use { it.setString(1,hash); it.setString(2,access ?: hash); it.executeUpdate() }
    } }
    fun revokeAll(userId: String) { tx { c -> c.prepareStatement("DELETE FROM sessions WHERE user_id=?::uuid").use { it.setString(1,userId); it.executeUpdate() } } }
    private fun issueOAuth(c: Connection,identity: Identity,clientId: String,resource: String): OAuthTokens {
        val access=secret(); val refresh=secret(); val now=Instant.now(); val scopes=identity.scopes.sorted().joinToString(" ")
        c.prepareStatement("INSERT INTO sessions VALUES (?,?::uuid,?,?)").use { it.setString(1,digest(access));it.setString(2,identity.userId);it.setString(3,scopes);it.setTimestamp(4,Timestamp.from(now.plusSeconds(3600)));it.executeUpdate() }
        c.prepareStatement("INSERT INTO oauth_refresh_tokens VALUES (?,?,?::uuid,?,?,?,?)").use { it.setString(1,digest(refresh));it.setString(2,digest(access));it.setString(3,identity.userId);it.setString(4,clientId);it.setString(5,resource);it.setString(6,scopes);it.setTimestamp(7,Timestamp.from(now.plusSeconds(30L*24*3600)));it.executeUpdate() }
        return OAuthTokens(access,refresh,scopes)
    }
    fun issueOAuth(identity: Identity,clientId: String,resource: String): OAuthTokens = tx { issueOAuth(it,identity,clientId,resource) }
    fun refreshOAuth(refresh: String,clientId: String,resource: String): OAuthTokens = tx { c ->
        val grant=c.prepareStatement("SELECT r.user_id,u.email,r.scopes,r.access_hash,r.client_id,r.resource FROM oauth_refresh_tokens r JOIN users u ON u.id=r.user_id WHERE r.token_hash=? AND r.expires_at>now() FOR UPDATE OF r").use {
            it.setString(1,digest(refresh)); it.executeQuery().use { r -> if(!r.next()) throw ApiFailure(401,"invalid_grant") else listOf(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6)) }
        }
        require(grant[4]==clientId && grant[5]==resource) { "Invalid refresh token binding" }
        c.prepareStatement("DELETE FROM sessions WHERE token_hash=?").use { it.setString(1,grant[3]);it.executeUpdate() }
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
    fun mutate(userId: String, mutation: Mutation): MutationResult = tx { c ->
        UUID.fromString(mutation.operationId); UUID.fromString(mutation.itemId)
        require(mutation.baseVersion >= 0)
        // One account's sequence and operation journal advance in commit order.
        c.prepareStatement("SELECT revision FROM users WHERE id=?::uuid FOR UPDATE").use { it.setString(1,userId); it.executeQuery().use { r -> check(r.next()) } }
        val request = WireJson.encodeToString(mutation)
        val replay = c.prepareStatement("SELECT request,result FROM operations WHERE user_id=?::uuid AND id=?::uuid").use {
            it.setString(1,userId); it.setString(2,mutation.operationId)
            it.executeQuery().use { r -> if(r.next()) r.getString(1) to r.getString(2) else null }
        }
        if (replay != null) {
            if (replay.first != request) throw ApiFailure(409,"operationId уже использован с другими данными")
            return@tx WireJson.decodeFromString<MutationResult>(replay.second)
        }
        val current = find(c,userId,mutation.itemId)
        val result = if ((current?.version ?: 0) != mutation.baseVersion) {
            MutationResult(mutation.operationId,MutationStatus.CONFLICT,current,"Запись изменена на другом устройстве")
        } else {
            mutation.content.validate()
            require(current == null || current.content.typeName() == mutation.content.typeName()) { "Нельзя менять тип записи" }
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
            val item = Item(mutation.itemId,(current?.version ?: 0)+1,mutation.content,mutation.deleted,Instant.now().toString(),sequence)
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
        result
    }
    fun sync(userId: String, request: SyncRequest): SyncResponse {
        require(request.cursor >= 0 && request.mutations.size <= 100)
        val orderedResults = request.mutations.sortedBy { it.content is HabitMarkContent }.map { mutation ->
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
