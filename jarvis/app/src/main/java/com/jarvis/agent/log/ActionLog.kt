package com.jarvis.agent.log

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Everything the agent did, shown on the Action Log screen. */
@Entity(tableName = "action_log")
data class ActionLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val timestamp: Long,
    /** TASK, TOOL, CONFIRM, RESULT, REPLY, ERROR, KILL */
    val kind: String,
    val toolName: String? = null,
    val risk: String? = null,
    val summary: String,
    val detail: String? = null,
    val outcome: String? = null,
)

@Dao
interface ActionLogDao {
    @Insert
    suspend fun insert(entry: ActionLogEntry): Long

    @Query("SELECT * FROM action_log ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ActionLogEntry>>

    @Query("DELETE FROM action_log WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

@Database(entities = [ActionLogEntry::class], version = 1, exportSchema = false)
abstract class ActionLogDatabase : RoomDatabase() {
    abstract fun dao(): ActionLogDao

    companion object {
        fun create(context: Context): ActionLogDatabase =
            Room.databaseBuilder(context, ActionLogDatabase::class.java, "action_log.db").build()
    }
}

/** Fire-and-forget logger. Details are truncated; secrets never reach tool inputs in the first place. */
class ActionLogger(private val dao: ActionLogDao, private val scope: CoroutineScope) {

    fun log(
        taskId: Long,
        kind: String,
        summary: String,
        toolName: String? = null,
        risk: String? = null,
        detail: String? = null,
        outcome: String? = null,
    ) {
        val entry = ActionLogEntry(
            taskId = taskId,
            timestamp = System.currentTimeMillis(),
            kind = kind,
            toolName = toolName,
            risk = risk,
            summary = summary.take(500),
            detail = detail?.take(2000),
            outcome = outcome?.take(1000),
        )
        scope.launch { runCatching { dao.insert(entry) } }
    }

    fun recent(limit: Int = 300) = dao.recent(limit)

    fun clear() {
        scope.launch { dao.clear() }
    }

    fun pruneOlderThanDays(days: Int) {
        scope.launch { dao.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L) }
    }
}
