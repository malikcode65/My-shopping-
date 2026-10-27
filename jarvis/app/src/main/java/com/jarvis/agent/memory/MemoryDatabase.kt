package com.jarvis.agent.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A lasting fact about the user ("Meri ammi ka naam Shabana hai", "I prefer WhatsApp over SMS"). */
@Entity(tableName = "facts")
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: String = "general",
    val createdAt: Long = System.currentTimeMillis(),
)

/** "Ammi" → the contact saved as "Shabana Khan". */
@Entity(tableName = "nicknames")
data class ContactNickname(
    @PrimaryKey val nickname: String,
    val contactName: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A scheduled task: run [prompt] at [hour]:[minute] on the days in [daysMask] (0 = every day). */
@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val prompt: String,
    val hour: Int,
    val minute: Int,
    /** bit 0 = Sunday … bit 6 = Saturday; 0 means every day. */
    val daysMask: Int = 0,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
)

@Entity(tableName = "conversations")
data class ConversationSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val userText: String,
    val reply: String,
    val steps: Int,
)

@Dao
interface MemoryDao {
    // facts
    @Insert suspend fun insertFact(f: MemoryFact): Long
    @Query("SELECT * FROM facts ORDER BY id DESC LIMIT :limit") suspend fun recentFacts(limit: Int): List<MemoryFact>
    @Query("SELECT * FROM facts WHERE text LIKE '%' || :q || '%' ORDER BY id DESC LIMIT 20") suspend fun searchFacts(q: String): List<MemoryFact>
    @Query("SELECT * FROM facts WHERE id = :id") suspend fun fact(id: Long): MemoryFact?
    @Query("DELETE FROM facts WHERE id = :id") suspend fun deleteFact(id: Long): Int
    @Query("SELECT * FROM facts ORDER BY id DESC") fun factsFlow(): Flow<List<MemoryFact>>

    // nicknames
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertNickname(n: ContactNickname)
    @Query("SELECT * FROM nicknames WHERE nickname = :nickname") suspend fun nickname(nickname: String): ContactNickname?
    @Query("SELECT * FROM nicknames ORDER BY nickname") suspend fun allNicknames(): List<ContactNickname>
    @Query("DELETE FROM nicknames WHERE nickname = :nickname") suspend fun deleteNickname(nickname: String)
    @Query("SELECT * FROM nicknames ORDER BY nickname") fun nicknamesFlow(): Flow<List<ContactNickname>>

    // routines
    @Insert suspend fun insertRoutine(r: Routine): Long
    @Update suspend fun updateRoutine(r: Routine)
    @Query("SELECT * FROM routines WHERE id = :id") suspend fun routine(id: Long): Routine?
    @Query("SELECT * FROM routines ORDER BY hour, minute") suspend fun allRoutines(): List<Routine>
    @Query("DELETE FROM routines WHERE id = :id") suspend fun deleteRoutine(id: Long): Int
    @Query("SELECT * FROM routines ORDER BY hour, minute") fun routinesFlow(): Flow<List<Routine>>

    // conversation summaries
    @Insert suspend fun insertConversation(c: ConversationSummary)
    @Query("SELECT * FROM conversations ORDER BY id DESC LIMIT :limit") suspend fun recentConversations(limit: Int): List<ConversationSummary>
    @Query("SELECT * FROM conversations WHERE userText LIKE '%' || :q || '%' OR reply LIKE '%' || :q || '%' ORDER BY id DESC LIMIT 10")
    suspend fun searchConversations(q: String): List<ConversationSummary>
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT id FROM conversations ORDER BY id DESC LIMIT :keep)")
    suspend fun pruneConversations(keep: Int)
    @Query("DELETE FROM conversations") suspend fun clearConversations()
    @Query("SELECT * FROM conversations ORDER BY id DESC LIMIT 50") fun conversationsFlow(): Flow<List<ConversationSummary>>
}

@Database(
    entities = [MemoryFact::class, ContactNickname::class, Routine::class, ConversationSummary::class],
    version = 1,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        fun create(context: Context): MemoryDatabase =
            Room.databaseBuilder(context, MemoryDatabase::class.java, "memory.db").build()
    }
}
