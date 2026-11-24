package dev.habittracker.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.habittracker.shared.*
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

data class Identity(val userId: String, val email: String, val scopes: Set<String>)
internal data class OAuthPendingState(val request: AuthorizationRequest, val approvedUserId: String?)
internal data class OAuthPendingCompletion(val request: AuthorizationRequest, val userId: String?)
private data class OAuthCodeState(val redirect: String, val challenge: String, val scopes: Set<String>, val userId: String, val clientId: String, val resource: String)
internal data class ClaimedSocialAttempt(
    val provider: String,
    val clientChallenge: String,
    val providerVerifierCiphertext: ByteArray,
    val linkUserId: String?,
    val linkSessionHash: String?,
    val callbackClaim: String = "",
)
private data class SocialPollState(val challenge: String,val status: String,val resultUserId: String?,val message: String?)
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
    fun checkRateLimit(key: String, maxAttempts: Int) {
        require(maxAttempts > 0)
        val attempts = tx { c ->
            c.prepareStatement("""INSERT INTO rate_limit_windows(key_hash,window_started_at,attempts)
                VALUES (?,now(),1)
                ON CONFLICT(key_hash) DO UPDATE SET
                  attempts=CASE
                    WHEN rate_limit_windows.window_started_at <= now()-INTERVAL '60 seconds' THEN 1
                    WHEN rate_limit_windows.attempts <= ? THEN rate_limit_windows.attempts+1
                    ELSE rate_limit_windows.attempts
                  END,
                  window_started_at=CASE
                    WHEN rate_limit_windows.window_started_at <= now()-INTERVAL '60 seconds' THEN now()
                    ELSE rate_limit_windows.window_started_at
                  END
                RETURNING attempts""").use { statement ->
                statement.setString(1, digest(key))
                statement.setInt(2, maxAttempts)
                statement.executeQuery().use { result -> check(result.next()); result.getInt(1) }
            }
        }
        if (attempts > maxAttempts) throw ApiFailure(429, "Подожди минуту перед следующей попыткой")
    }
    fun purgeExpiredRateLimits(): Int = pool.connection.use { c ->
        c.prepareStatement("DELETE FROM rate_limit_windows WHERE window_started_at <= now()-INTERVAL '2 minutes'").use { it.executeUpdate() }
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
        if (user == null || user.third == null) {
            Passwords.hash(credentials.password)
            return null
        }
        if (!Passwords.verify(credentials.password,user.third)) return null
        return Identity(user.first,user.second,setOf("tracker:read","tracker:write"))
    }
    fun login(credentials: Credentials): Session = newSession(checkPassword(credentials) ?: throw ApiFailure(401,"Неверный email или пароль"))
    fun linkedProviders(userId: String): Set<String> = pool.connection.use { c ->
        c.prepareStatement("SELECT provider FROM social_identities WHERE user_id=?::uuid").use { p ->
            p.setString(1,userId); p.executeQuery().use { r -> buildSet { while(r.next()) add(r.getString(1)) } }
        }
    }
    fun socialIdentity(provider: String,subject: String,email: String?,linkUser: String?): Identity = tx { c ->
        socialIdentity(c,provider,subject,email,linkUser)
    }
    private fun socialIdentity(c: Connection,provider: String,subject: String,email: String?,linkUser: String?): Identity {
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
        return Identity(id,address,setOf("tracker:read","tracker:write"))
    }
    fun newSession(identity: Identity, hours: Long = 24 * 30): Session {
        return tx { c -> lockAccount(c,identity.userId);createSession(c,identity,hours) }
    }
    private fun createSession(c: Connection,identity: Identity,hours: Long = 24 * 30): Session {
        val token=secret();val expiry=Instant.now().plusSeconds(hours*3600)
        c.prepareStatement("INSERT INTO sessions (token_hash,user_id,scopes,expires_at) VALUES (?,?::uuid,?,?)").use {
            it.setString(1,digest(token));it.setString(2,identity.userId);it.setString(3,identity.scopes.sorted().joinToString(" "));it.setTimestamp(4,Timestamp.from(expiry));it.executeUpdate()
        }
        return Session(token,identity.userId,identity.email,expiry.toString())
    }

    fun createSocialAttempt(stateHash: String,provider: String,clientChallenge: String,providerVerifierCiphertext: ByteArray,
                            linkUserId: String?,linkSessionHash: String?) = tx { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('habit_tracker.social_attempt_limit',0))").use { it.execute() }
        c.prepareStatement("DELETE FROM social_login_attempts WHERE expires_at<=now()").use { it.executeUpdate() }
        val count=c.prepareStatement("SELECT count(*) FROM social_login_attempts WHERE expires_at>now()").use { p->p.executeQuery().use { r->r.next();r.getLong(1) } }
        require(count<1000) { "Слишком много попыток входа" }
        c.prepareStatement("""INSERT INTO social_login_attempts
            (state_hash,provider,client_challenge,provider_verifier_ciphertext,link_user_id,link_session_hash,status,expires_at)
            VALUES (?,?,?,?,?::uuid,?,'PENDING',now()+INTERVAL '5 minutes')""").use { p ->
            p.setString(1,stateHash);p.setString(2,provider);p.setString(3,clientChallenge);p.setBytes(4,providerVerifierCiphertext)
            if(linkUserId==null) p.setNull(5,Types.OTHER) else p.setString(5,linkUserId)
            p.setString(6,linkSessionHash);p.executeUpdate()
        }
    }

    internal fun claimSocialCallback(stateHash: String,provider: String): ClaimedSocialAttempt = tx { c ->
        val row=c.prepareStatement("""SELECT provider,client_challenge,provider_verifier_ciphertext,link_user_id::text,link_session_hash,status,
            (callback_lease_until IS NULL OR callback_lease_until<=now())
            FROM social_login_attempts WHERE state_hash=? AND expires_at>now() FOR UPDATE""").use { p ->
            p.setString(1,stateHash);p.executeQuery().use { r ->
                if(!r.next()) throw ApiFailure(400,"Попытка входа не найдена или истекла")
                ClaimedSocialAttempt(r.getString(1),r.getString(2),r.getBytes(3),r.getString(4),r.getString(5)) to
                    (r.getString(6) to r.getBoolean(7))
            }
        }
        if(row.first.provider!=provider) throw ApiFailure(400,"Попытка входа не найдена")
        val canClaim=row.second.first=="PENDING" || (row.second.first=="PROCESSING" && row.second.second)
        if(!canClaim) throw ApiFailure(409,"Ответ провайдера уже обрабатывается или завершён")
        val claim=secret()
        c.prepareStatement("UPDATE social_login_attempts SET status='PROCESSING',callback_lease_until=now()+INTERVAL '90 seconds',callback_claim_hash=? WHERE state_hash=?").use {
            it.setString(1,digest(claim));it.setString(2,stateHash);check(it.executeUpdate()==1)
        }
        row.first.copy(callbackClaim=claim)
    }

    fun completeSocialCallback(stateHash: String,callbackClaim: String,person: ProviderPerson): Boolean = tx { c ->
        val linkedUserRow=c.prepareStatement("SELECT link_user_id::text FROM social_login_attempts WHERE state_hash=? AND status='PROCESSING' AND callback_claim_hash=? AND expires_at>now()").use { p ->
            p.setString(1,stateHash);p.setString(2,digest(callbackClaim));p.executeQuery().use { r -> if(r.next()) Pair(true,r.getString(1)) else Pair(false,null) }
        }
        if(!linkedUserRow.first) return@tx false
        val linkedUser=linkedUserRow.second
        linkedUser?.let { lockAccount(c,it) }
        val pending=c.prepareStatement("""SELECT provider,link_user_id::text,link_session_hash FROM social_login_attempts
            WHERE state_hash=? AND status='PROCESSING' AND callback_claim_hash=? AND expires_at>now() FOR UPDATE""").use { p ->
            p.setString(1,stateHash);p.setString(2,digest(callbackClaim));p.executeQuery().use { r -> if(r.next()) Triple(r.getString(1),r.getString(2),r.getString(3)) else null }
        } ?: return@tx false
        val linkUserId=pending.second
        val identity=if(linkUserId==null) socialIdentity(c,pending.first,person.subject,person.email,null) else {
            val valid=c.prepareStatement("""SELECT 1 FROM sessions s WHERE s.token_hash=? AND s.user_id=?::uuid AND s.expires_at>now()
                AND NOT EXISTS (SELECT 1 FROM oauth_refresh_tokens r WHERE r.access_hash=s.token_hash)""").use { p ->
                p.setString(1,pending.third);p.setString(2,linkUserId);p.executeQuery().use { it.next() }
            }
            if(!valid) {
                c.prepareStatement("UPDATE social_login_attempts SET status='READY_FAILED',result_message=?,callback_lease_until=NULL,callback_claim_hash=NULL WHERE state_hash=? AND status='PROCESSING' AND callback_claim_hash=?").use {
                    it.setString(1,"Сессия привязки отозвана. Войди и повтори попытку.");it.setString(2,stateHash);it.setString(3,digest(callbackClaim));it.executeUpdate()
                }
                return@tx true
            }
            socialIdentity(c,pending.first,person.subject,person.email,linkUserId)
        }
        val status=if(linkUserId==null) "READY_SUCCESS" else "READY_LINKED"
        c.prepareStatement("UPDATE social_login_attempts SET status=?,result_user_id=?::uuid,callback_lease_until=NULL,callback_claim_hash=NULL WHERE state_hash=? AND status='PROCESSING' AND callback_claim_hash=?").use { p ->
            p.setString(1,status);p.setString(2,identity.userId);p.setString(3,stateHash);p.setString(4,digest(callbackClaim));p.executeUpdate()==1
        }
    }

    fun failSocialCallback(stateHash: String,callbackClaim: String,message: String): Boolean = tx { c ->
        c.prepareStatement("UPDATE social_login_attempts SET status='READY_FAILED',result_message=?,callback_lease_until=NULL,callback_claim_hash=NULL WHERE state_hash=? AND status='PROCESSING' AND callback_claim_hash=? AND expires_at>now()").use { p ->
            p.setString(1,message.take(300));p.setString(2,stateHash);p.setString(3,digest(callbackClaim));p.executeUpdate()==1
        }
    }

    fun pollSocialAttempt(stateHash: String,verifier: String): SocialResult = tx { c ->
        val owner=c.prepareStatement("SELECT COALESCE(result_user_id,link_user_id)::text FROM social_login_attempts WHERE state_hash=?").use { p ->
            p.setString(1,stateHash);p.executeQuery().use { r -> if(r.next()) r.getString(1) else null }
        }
        if(owner!=null) {
            try { lockAccount(c,owner) } catch(_: ApiFailure) { throw ApiFailure(410,"Попытка входа больше не действует") }
        }
        val row=c.prepareStatement("SELECT client_challenge,status,result_user_id::text,result_message,expires_at>now() FROM social_login_attempts WHERE state_hash=? FOR UPDATE").use { p ->
            p.setString(1,stateHash);p.executeQuery().use { r ->
                if(!r.next()) throw ApiFailure(410,"Попытка входа истекла")
                SocialPollState(r.getString(1),r.getString(2),r.getString(3),r.getString(4)) to r.getBoolean(5)
            }
        }
        if(!row.second) throw ApiFailure(410,"Попытка входа истекла")
        val state=row.first
        if(!equalSecrets(challenge(verifier),state.challenge)) throw ApiFailure(403,"Неверный ключ попытки")
        when(state.status) {
            "PENDING","PROCESSING" -> SocialResult("WAITING")
            "READY_SUCCESS","READY_LINKED" -> {
                val userId=requireNotNull(state.resultUserId)
                if(owner==null) {
                    try { lockAccount(c,userId) } catch(_: ApiFailure) { throw ApiFailure(410,"Попытка входа больше не действует") }
                }
                val email=c.prepareStatement("SELECT email FROM users WHERE id=?::uuid").use { p -> p.setString(1,userId);p.executeQuery().use { r -> if(r.next()) r.getString(1) else throw ApiFailure(410,"Попытка входа больше не действует") } }
                val result=if(state.status=="READY_SUCCESS") SocialResult("DONE",createSession(c,Identity(userId,email,setOf("tracker:read","tracker:write")))) else SocialResult("LINKED")
                c.prepareStatement("DELETE FROM social_login_attempts WHERE state_hash=?").use { it.setString(1,stateHash);it.executeUpdate() }
                result
            }
            "READY_FAILED" -> {
                val result=SocialResult("FAILED",message=state.message ?: "Не удалось подтвердить вход. Попробуй снова.")
                c.prepareStatement("DELETE FROM social_login_attempts WHERE state_hash=?").use { it.setString(1,stateHash);it.executeUpdate() }
                result
            }
            else -> throw ApiFailure(410,"Попытка входа истекла")
        }
    }

    fun removeSocialAttemptsForAccount(userId: String) = tx { c ->
        c.prepareStatement("DELETE FROM social_login_attempts WHERE link_user_id=?::uuid OR result_user_id=?::uuid").use { p ->
            p.setString(1,userId);p.setString(2,userId);p.executeUpdate()
        }
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
        lockAccount(c, userId)
        c.prepareStatement("""DELETE FROM sessions WHERE user_id=?::uuid AND token_hash IN (
            SELECT access_hash FROM oauth_refresh_tokens WHERE user_id=?::uuid AND client_id=? AND resource=?)""").use { statement ->
            statement.setString(1, userId)
            statement.setString(2, userId)
            statement.setString(3, clientId)
            statement.setString(4, resource)
            statement.executeUpdate()
        }
        c.prepareStatement("DELETE FROM oauth_authorization_requests WHERE approved_user_id=?::uuid").use { statement ->
            statement.setString(1, userId); statement.executeUpdate()
        }
        c.prepareStatement("DELETE FROM oauth_authorization_codes WHERE user_id=?::uuid AND client_id=? AND resource=?").use { statement ->
            statement.setString(1, userId); statement.setString(2, clientId); statement.setString(3, resource); statement.executeUpdate()
        }
    }
    fun createOAuthPending(nonceHash: String, request: AuthorizationRequest, appCodeHash: String?) = tx { c ->
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('habit_tracker.oauth_pending_limit',0))").use { it.execute() }
        c.prepareStatement("DELETE FROM oauth_authorization_requests WHERE expires_at<=now()").use { it.executeUpdate() }
        c.prepareStatement("DELETE FROM oauth_authorization_codes WHERE expires_at<=now()").use { it.executeUpdate() }
        val count = c.prepareStatement("SELECT count(*) FROM oauth_authorization_requests WHERE expires_at>now()").use { statement ->
            statement.executeQuery().use { rows -> rows.next(); rows.getLong(1) }
        }
        require(count < 100) { "Слишком много запросов подключения" }
        c.prepareStatement("""INSERT INTO oauth_authorization_requests
            (nonce_hash,redirect_uri,state,code_challenge,scopes,app_code_hash,expires_at)
            VALUES (?,?,?,?,?,?,?)""").use { statement ->
            statement.setString(1, nonceHash)
            statement.setString(2, request.redirect)
            statement.setString(3, request.state)
            statement.setString(4, request.challenge)
            statement.setString(5, request.scopes.sorted().joinToString(" "))
            if (appCodeHash == null) statement.setNull(6, Types.VARCHAR) else statement.setString(6, appCodeHash)
            statement.setTimestamp(7, Timestamp.from(request.expires))
            statement.executeUpdate()
        }
    }

    internal fun oauthPendingByAppCode(appCodeHash: String): OAuthPendingState? = pool.connection.use { c ->
        c.prepareStatement("SELECT redirect_uri,state,code_challenge,scopes,approved_user_id,expires_at FROM oauth_authorization_requests WHERE app_code_hash=? AND approved_user_id IS NULL AND expires_at>now()").use { statement ->
            statement.setString(1, appCodeHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.oauthPending() else null }
        }
    }

    private fun ResultSet.oauthPending(): OAuthPendingState {
        val scopes = getString("scopes").split(' ').filter(String::isNotEmpty).toSet()
        return OAuthPendingState(
            AuthorizationRequest(getString("redirect_uri"), getString("state"), getString("code_challenge"), scopes, getTimestamp("expires_at").toInstant()),
            getString("approved_user_id")
        )
    }

    fun approveOAuthPending(appCodeHash: String, appToken: String) = tx { c ->
        val tokenHash = digest(appToken)
        val identity = appIdentity(c, tokenHash) ?: throw ApiFailure(401, "Войди в Habit Tracker")
        lockAccount(c, identity.userId)
        // Recheck the app session after taking the account lock so revocation cannot race approval.
        val currentIdentity = appIdentity(c, tokenHash) ?: throw ApiFailure(401, "Войди в Habit Tracker")
        require(currentIdentity.userId == identity.userId)
        val requestedScopes = c.prepareStatement("SELECT scopes FROM oauth_authorization_requests WHERE app_code_hash=? AND expires_at>now() AND approved_user_id IS NULL FOR UPDATE").use { statement ->
            statement.setString(1, appCodeHash)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1).split(' ').filter(String::isNotEmpty).toSet()
                else throw ApiFailure(404, "Код неверный, уже использован или истёк")
            }
        }
        require(currentIdentity.scopes.containsAll(requestedScopes)) { "Недостаточно прав для подключения" }
        c.prepareStatement("UPDATE oauth_authorization_requests SET approved_user_id=?::uuid WHERE app_code_hash=? AND approved_user_id IS NULL AND expires_at>now()").use { statement ->
            statement.setString(1, identity.userId); statement.setString(2, appCodeHash)
            if (statement.executeUpdate() != 1) throw ApiFailure(404, "Код неверный, уже использован или истёк")
        }
    }

    enum class OAuthPendingDecision { BROWSER_ALLOW, BROWSER_DENY, APP_FINISH }

    internal fun finishOAuthPending(
        nonceHash: String,
        decision: OAuthPendingDecision,
        browserUserId: String?,
        codeHash: String?,
        clientId: String,
        resource: String,
    ): OAuthPendingCompletion? = tx { c ->
        val appFinishUserId = if (decision == OAuthPendingDecision.APP_FINISH) c.prepareStatement(
            "SELECT approved_user_id::text FROM oauth_authorization_requests WHERE nonce_hash=? AND expires_at>now()"
        ).use { statement ->
            statement.setString(1, nonceHash)
            statement.executeQuery().use { rows ->
                if (!rows.next()) throw ApiFailure(410, "Запрос подключения истёк")
                rows.getString(1)
            }
        } else null
        val accountLockId = when (decision) {
            OAuthPendingDecision.BROWSER_ALLOW -> requireNotNull(browserUserId)
            OAuthPendingDecision.APP_FINISH -> appFinishUserId
            OAuthPendingDecision.BROWSER_DENY -> null
        }
        accountLockId?.let { lockAccount(c, it) }
        val row = c.prepareStatement("""SELECT redirect_uri,state,code_challenge,scopes,approved_user_id,expires_at
            FROM oauth_authorization_requests WHERE nonce_hash=? AND expires_at>now() FOR UPDATE""").use { statement ->
            statement.setString(1, nonceHash)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.oauthPending() else throw ApiFailure(410, "Запрос подключения истёк")
            }
        }
        val userId = when (decision) {
            OAuthPendingDecision.BROWSER_DENY -> null
            OAuthPendingDecision.BROWSER_ALLOW -> {
                require(row.approvedUserId == null) { "Подключение уже подтверждено в приложении. Нажми «Завершить подключение»." }
                val requestedUser = requireNotNull(browserUserId)
                requestedUser
            }
            OAuthPendingDecision.APP_FINISH -> {
                if (appFinishUserId == null || row.approvedUserId != appFinishUserId) return@tx null
                appFinishUserId
            }
        }
        if (userId != null) {
            val hashedCode = requireNotNull(codeHash)
            c.prepareStatement("""INSERT INTO oauth_authorization_codes
                (code_hash,redirect_uri,code_challenge,scopes,user_id,client_id,resource,expires_at)
                VALUES (?,?,?,?,?::uuid,?,?,now()+INTERVAL '60 seconds')""").use { statement ->
                statement.setString(1, hashedCode); statement.setString(2, row.request.redirect)
                statement.setString(3, row.request.challenge); statement.setString(4, row.request.scopes.sorted().joinToString(" "))
                statement.setString(5, userId); statement.setString(6, clientId); statement.setString(7, resource)
                statement.executeUpdate()
            }
        }
        c.prepareStatement("DELETE FROM oauth_authorization_requests WHERE nonce_hash=?").use { statement ->
            statement.setString(1, nonceHash)
            if (statement.executeUpdate() != 1) throw ApiFailure(410, "Запрос подключения истёк")
        }
        OAuthPendingCompletion(row.request, userId)
    }

    private fun appIdentity(c: Connection, tokenHash: String): Identity? = c.prepareStatement("""SELECT u.id,u.email,s.scopes FROM sessions s
        JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>now()
        AND NOT EXISTS (SELECT 1 FROM oauth_refresh_tokens r WHERE r.access_hash=s.token_hash)""").use { statement ->
        statement.setString(1, tokenHash)
        statement.executeQuery().use { rows ->
            if (rows.next()) Identity(rows.getString(1), rows.getString(2), rows.getString(3).split(' ').toSet()) else null
        }
    }

    fun redeemOAuthCode(codeHash: String, clientId: String, resource: String, redirectUri: String, verifier: String): OAuthTokens = tx { c ->
        val codeUserId = c.prepareStatement("SELECT user_id::text FROM oauth_authorization_codes WHERE code_hash=? AND expires_at>now()").use { statement ->
            statement.setString(1, codeHash)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else throw ApiFailure(401, "Invalid grant") }
        }
        lockAccount(c, codeUserId)
        val row = c.prepareStatement("""SELECT redirect_uri,code_challenge,scopes,user_id::text,client_id,resource
            FROM oauth_authorization_codes WHERE code_hash=? AND expires_at>now() FOR UPDATE""").use { statement ->
            statement.setString(1, codeHash)
            statement.executeQuery().use { rows ->
                if (rows.next()) OAuthCodeState(
                    rows.getString(1), rows.getString(2), rows.getString(3).split(' ').filter(String::isNotEmpty).toSet(),
                    rows.getString(4), rows.getString(5), rows.getString(6)
                ) else throw ApiFailure(401, "Invalid grant")
            }
        }
        require(row.clientId == clientId && row.resource == resource) { "Invalid grant" }
        require(row.userId == codeUserId) { "Invalid grant" }
        require(redirectUri == row.redirect) { "Invalid grant" }
        require(Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier) && equalSecrets(challenge(verifier), row.challenge)) { "Invalid grant" }
        c.prepareStatement("DELETE FROM oauth_authorization_codes WHERE code_hash=?").use { statement ->
            statement.setString(1, codeHash)
            if (statement.executeUpdate() != 1) throw ApiFailure(401, "Invalid grant")
        }
        val email = c.prepareStatement("SELECT email FROM users WHERE id=?::uuid").use { statement ->
            statement.setString(1, row.userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else throw ApiFailure(401, "Invalid grant") }
        }
        issueOAuth(c, Identity(row.userId, email, row.scopes), clientId, resource)
    }

    fun revoke(token: String) { tx { c ->
        val hash=digest(token)
        val access=c.prepareStatement("SELECT access_hash FROM oauth_refresh_tokens WHERE token_hash=?").use { it.setString(1,hash); it.executeQuery().use { r -> if(r.next()) r.getString(1) else null } }
        c.prepareStatement("DELETE FROM sessions WHERE token_hash=? OR token_hash=?").use { it.setString(1,hash); it.setString(2,access ?: hash); it.executeUpdate() }
    } }
    fun revokeAll(userId: String) { tx { c -> c.prepareStatement("DELETE FROM sessions WHERE user_id=?::uuid").use { it.setString(1,userId); it.executeUpdate() } } }
    fun changePassword(userId: String, currentToken: String, currentPassword: String, newPassword: String) {
        require(newPassword.length in 10..128) { "Пароль: от 10 до 128 символов" }
        val newHash=Passwords.hash(newPassword)
        val currentTokenHash=digest(currentToken)
        tx { c ->
            lockAccount(c,userId)
            val oldHash=c.prepareStatement("SELECT password_hash FROM users WHERE id=?::uuid").use { p ->
                p.setString(1,userId);p.executeQuery().use { r -> if(r.next()) r.getString(1) else throw ApiFailure(401,"Войди в аккаунт") }
            }
            if(oldHash == null) {
                require(currentPassword.isEmpty()) { "У аккаунта пока нет пароля — оставь поле текущего пароля пустым" }
                val recentlyAuthenticated=c.prepareStatement("""SELECT 1 FROM sessions
                    WHERE token_hash=? AND user_id=?::uuid AND expires_at>now()
                    AND issued_at>=now()-INTERVAL '10 minutes'""").use { p ->
                    p.setString(1,currentTokenHash);p.setString(2,userId);p.executeQuery().use { it.next() }
                }
                if(!recentlyAuthenticated) throw ApiFailure(401,"Сначала войди в аккаунт через привязанный способ ещё раз")
            } else {
                if(!Passwords.verify(currentPassword,oldHash)) throw ApiFailure(401,"Неверный текущий пароль")
                require(!Passwords.verify(newPassword,oldHash)) { "Новый пароль должен отличаться от текущего" }
            }
            c.prepareStatement("UPDATE users SET password_hash=? WHERE id=?::uuid").use { p -> p.setString(1,newHash);p.setString(2,userId);p.executeUpdate() }
            c.prepareStatement("DELETE FROM sessions WHERE user_id=?::uuid AND token_hash<>?").use { p -> p.setString(1,userId);p.setString(2,currentTokenHash);p.executeUpdate() }
        }
    }
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
    fun purgeDeletedAccountFiles(storage: AttachmentStorage): Int = tx { c -> purgeAccountFiles(c, storage) }
    fun queueUnlinkedAttachmentFiles(): Int = tx { c -> queueUnlinkedAttachmentFiles(c) }
    fun queueAttachmentCleanup(storageKey: String) = tx { c ->
        require(UUID.fromString(storageKey).toString() == storageKey)
        c.prepareStatement("INSERT INTO account_file_purge(storage_key) VALUES (?) ON CONFLICT DO NOTHING").use {
            it.setString(1, storageKey)
            it.executeUpdate()
        }
    }

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
            if (mutation.deleted && oldProject != null) {
                val referenced = c.prepareStatement("""SELECT 1 FROM items WHERE user_id=?::uuid AND id<>?::uuid AND NOT deleted AND (
                    body->'organization'->>'projectId'=? OR body->'organization'->>'goalId'=? OR jsonb_exists(body->'organization'->'linkedIds', ?)) LIMIT 1""").use { p ->
                    p.setString(1,userId);p.setString(2,mutation.itemId);p.setString(3,mutation.itemId);p.setString(4,mutation.itemId);p.setString(5,mutation.itemId)
                    p.executeQuery().use { it.next() }
                }
                require(!referenced) { "Сначала убери этот проект или цель из связанных записей" }
            }
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
    fun attachments(userId: String, noteId: String): List<Attachment> = pool.connection.use { c ->
        val item=find(c,userId,noteId)?.takeIf { !it.deleted } ?: return@use emptyList()
        val note=item.content as? NoteContent ?: return@use emptyList()
        val referenced=note.attachmentIds.toSet()
        c.prepareStatement("SELECT * FROM attachments WHERE user_id=?::uuid AND note_id=?::uuid").use {
            it.setString(1,userId); it.setString(2,noteId); it.executeQuery().use { r -> buildList {
                while(r.next()) {
                    val id=r.getString("id")
                    if(id in referenced) add(Attachment(id,r.getString("note_id"),r.getString("filename"),r.getString("media_type"),r.getLong("size")))
                }
            } }
        }
    }
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
