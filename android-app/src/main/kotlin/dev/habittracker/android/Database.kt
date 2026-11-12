package dev.habittracker.android

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="items") data class LocalItem(@PrimaryKey val id: String, val localJson: String, val serverJson: String?)
@Entity(tableName="outbox") data class PendingMutation(@PrimaryKey val itemId: String, val operationJson: String, val error: String? = null)
@Entity(tableName="conflicts") data class Conflict(@PrimaryKey val itemId: String, val localJson: String, val remoteJson: String?)
@Entity(tableName="files") data class LocalFile(@PrimaryKey val id: String, val noteId: String, val name: String, val mediaType: String, val size: Long, val path: String?, val uploaded: Boolean)
@Entity(tableName="metadata") data class Metadata(@PrimaryKey val key: String, val value: String)
@Dao interface TrackerDao {
    @Query("SELECT * FROM items") fun observeItems(): Flow<List<LocalItem>>
    @Query("SELECT * FROM conflicts") fun observeConflicts(): Flow<List<Conflict>>
    @Query("SELECT * FROM files") fun observeFiles(): Flow<List<LocalFile>>
    @Query("SELECT * FROM outbox WHERE error IS NOT NULL") fun observeErrors(): Flow<List<PendingMutation>>
    @Query("SELECT * FROM items") suspend fun items(): List<LocalItem>
    @Query("SELECT * FROM items WHERE id=:id") suspend fun item(id: String): LocalItem?
    @Upsert suspend fun item(value: LocalItem)
    @Query("SELECT * FROM outbox WHERE error IS NULL LIMIT 100") suspend fun pending(): List<PendingMutation>
    @Query("SELECT * FROM outbox WHERE itemId=:id") suspend fun pending(id: String): PendingMutation?
    @Upsert suspend fun pending(value: PendingMutation)
    @Query("DELETE FROM outbox WHERE itemId=:id") suspend fun removePending(id: String)
    @Upsert suspend fun conflict(value: Conflict)
    @Query("SELECT * FROM conflicts WHERE itemId=:id") suspend fun conflict(id: String): Conflict?
    @Query("DELETE FROM conflicts WHERE itemId=:id") suspend fun removeConflict(id: String)
    @Query("SELECT * FROM files") suspend fun files(): List<LocalFile>
    @Query("SELECT * FROM files WHERE id=:id") suspend fun file(id: String): LocalFile?
    @Upsert suspend fun file(value: LocalFile)
    @Query("DELETE FROM files WHERE id=:id") suspend fun removeFile(id: String)
    @Query("SELECT value FROM metadata WHERE `key`=:key") suspend fun metadata(key: String): String?
    @Upsert suspend fun metadata(value: Metadata)
}
@Database(entities=[LocalItem::class,PendingMutation::class,Conflict::class,LocalFile::class,Metadata::class],version=1,exportSchema=true)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun dao(): TrackerDao
    companion object {
        fun open(context: Context,key: String): TrackerDatabase = Room.databaseBuilder(context,TrackerDatabase::class.java,"tracker-$key.db").build()
    }
}
