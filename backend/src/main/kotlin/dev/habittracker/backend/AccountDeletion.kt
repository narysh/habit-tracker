package dev.habittracker.backend

import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.UUID

@Serializable
internal data class AccountDeletionRequest(val confirmation: String)

/** Move unreferenced attachment blobs to the durable purge queue while holding each account lock. */
internal fun queueUnlinkedAttachmentFiles(c: Connection): Int {
    val candidates = c.prepareStatement(
        """SELECT a.user_id::text,a.storage_key
           FROM attachments a
           JOIN items i ON i.user_id=a.user_id AND i.id=a.note_id
           JOIN users u ON u.id=a.user_id
           WHERE i.deleted OR NOT COALESCE(jsonb_exists(i.body->'attachmentIds',a.id::text),false)
           ORDER BY a.storage_key
           LIMIT 64
           FOR UPDATE OF u,a SKIP LOCKED"""
    ).use { statement ->
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(rows.getString(1) to rows.getString(2))
        } }
    }
    for ((userId,key) in candidates) {
        c.prepareStatement("INSERT INTO account_file_purge(storage_key) VALUES (?) ON CONFLICT DO NOTHING").use {
            it.setString(1,key)
            it.executeUpdate()
        }
        c.prepareStatement("DELETE FROM attachments WHERE user_id=?::uuid AND storage_key=?").use {
            it.setString(1,userId)
            it.setString(2,key)
            it.executeUpdate()
        }
    }
    return candidates.size
}

internal fun deleteAccountRows(c: Connection, userId: String, appTokenHash: String) {
    val recent = c.prepareStatement(
        """SELECT COALESCE(issued_at > statement_timestamp() - INTERVAL '10 minutes', false)
           FROM sessions s WHERE s.user_id=?::uuid AND s.token_hash=? AND s.expires_at>statement_timestamp()
             AND NOT EXISTS (SELECT 1 FROM oauth_refresh_tokens r WHERE r.access_hash=s.token_hash)
           FOR UPDATE"""
    ).use { statement ->
        statement.setString(1, userId)
        statement.setString(2, appTokenHash)
        statement.executeQuery().use { rows ->
            if (!rows.next()) throw ApiFailure(401, "Войди в Habit Tracker")
            rows.getBoolean(1)
        }
    }
    if (!recent) throw ApiFailure(403, "Чтобы удалить аккаунт, войди снова и повтори запрос")

    c.prepareStatement(
        """INSERT INTO account_file_purge (storage_key)
           SELECT storage_key FROM attachments WHERE user_id=?::uuid
           ON CONFLICT DO NOTHING"""
    ).use { statement ->
        statement.setString(1, userId)
        statement.executeUpdate()
    }
    // Deleting the sessions cascades to their refresh tokens.
    for (table in listOf("attachments", "operations", "items", "social_identities", "sessions")) {
        c.prepareStatement("DELETE FROM $table WHERE user_id=?::uuid").use { statement ->
            statement.setString(1, userId)
            statement.executeUpdate()
        }
    }
    c.prepareStatement("DELETE FROM users WHERE id=?::uuid").use { statement ->
        statement.setString(1, userId)
        statement.executeUpdate()
    }
}

internal fun purgeAccountFiles(c: Connection, directory: Path): Int {
    val keys = c.prepareStatement(
        """SELECT storage_key FROM account_file_purge WHERE next_attempt_at<=now()
           ORDER BY next_attempt_at,storage_key LIMIT 64 FOR UPDATE SKIP LOCKED"""
    ).use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
    c.prepareStatement("DELETE FROM account_file_purge WHERE storage_key=?").use { done ->
        c.prepareStatement("UPDATE account_file_purge SET next_attempt_at=now()+INTERVAL '5 minutes' WHERE storage_key=?").use { retry ->
            for (key in keys) {
                val result = if (deleteAttachmentFile(directory, key)) done else retry
                result.setString(1, key)
                result.executeUpdate()
            }
        }
    }
    return keys.size
}

private fun deleteAttachmentFile(directory: Path, key: String): Boolean {
    return try {
        if (UUID.fromString(key).toString() != key) return false
        Files.deleteIfExists(directory.resolve(key))
        true
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
